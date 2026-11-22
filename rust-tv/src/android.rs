use std::{
    collections::HashMap,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use android_activity::{
    AndroidApp, InputStatus, MainEvent, PollEvent, Rect, WindowManagerFlags,
    input::{InputEvent, KeyAction, KeyEvent, KeyMapChar, Keycode, MotionAction, MotionEvent},
    ndk::{hardware_buffer_format::HardwareBufferFormat, native_window::NativeWindow},
};
use jni::{
    Env, JValue, JavaVM,
    errors::Result as JniResult,
    jni_sig, jni_str,
    objects::JObject,
    objects::{JByteArray, JClass, JString},
    refs::Global,
};

use crate::{
    ui::{Action, Key, Ui, home::HomeLayout},
    worker::{Job, Reply, SearchScope, Worker},
    youtube,
};

const FORMAT: HardwareBufferFormat = HardwareBufferFormat::R8G8B8A8_UNORM;

struct Surface {
    window: NativeWindow,
    bounds: Rect,
    requested: (i32, i32),
    drawn: (u32, u32),
}

impl Surface {
    fn new(window: NativeWindow, rect: Rect) -> std::io::Result<Self> {
        let mut surface = Self {
            bounds: Rect {
                left: 0,
                top: 0,
                right: window.width(),
                bottom: window.height(),
            },
            window,
            requested: (0, 0),
            drawn: (0, 0),
        };
        surface.resize(rect)?;
        Ok(surface)
    }

    fn resize(&mut self, bounds: Rect) -> std::io::Result<()> {
        if bounds.right > bounds.left && bounds.bottom > bounds.top {
            self.bounds = bounds;
        }
        let width = (self.bounds.right - self.bounds.left).max(1);
        let height = (self.bounds.bottom - self.bounds.top).max(1);
        // Android's compositor scales this opaque buffer to the TV's output resolution.
        let scale = (1280.0 / f64::from(width))
            .min(720.0 / f64::from(height))
            .min(1.0);
        let size = (
            (f64::from(width) * scale).round().max(1.0) as i32,
            (f64::from(height) * scale).round().max(1.0) as i32,
        );
        if size != self.requested {
            self.window
                .set_buffers_geometry(size.0, size.1, Some(FORMAT))?;
            self.requested = size;
        }
        Ok(())
    }

    fn render(&mut self, ui: &mut Ui) -> std::io::Result<()> {
        let mut frame = self.window.lock(None)?;
        if frame.format() != FORMAT {
            return Err(std::io::Error::other(
                "Unbekanntes Pixelformat der TV-Oberfläche",
            ));
        }
        let (width, height) = (frame.width(), frame.height());
        if width == 0 || height == 0 || width > 4096 || height > 4096 {
            return Err(std::io::Error::other("Ungültige Größe der TV-Oberfläche"));
        }
        let pixels = ui.render(width as u32, height as u32);
        if pixels.len() != width * height * 4 {
            return Err(std::io::Error::other("Unvollständiges RGBA-Bild"));
        }
        let rows = frame
            .lines()
            .ok_or_else(|| std::io::Error::other("Kein RGBA-Schreibpuffer"))?;
        for (row, source) in rows.zip(pixels.chunks_exact(width * 4)) {
            for (dest, value) in row.iter_mut().zip(source) {
                dest.write(*value);
            }
        }
        self.drawn = (width as u32, height as u32);
        // The NDK guard posts the completed buffer when it drops.
        Ok(())
    }

    fn point(&self, x: f32, y: f32) -> Option<(f32, f32)> {
        let (width, height) = self.drawn;
        if width == 0 || height == 0 {
            return None;
        }
        let (x, y) = (x - self.bounds.left as f32, y - self.bounds.top as f32);
        let w = (self.bounds.right - self.bounds.left) as f32;
        let h = (self.bounds.bottom - self.bounds.top) as f32;
        if x < 0. || y < 0. || x >= w || y >= h {
            return None;
        }
        Some((x * width as f32 / w, y * height as f32 / h))
    }
}

const LONG_PRESS_NS: i64 = 500_000_000;

struct VideoPress {
    id: String,
    key: Keycode,
    device: i32,
    repeated: bool,
}
fn key_input(
    app: &AndroidApp,
    ui: &mut Ui,
    event: &KeyEvent,
    press: &mut Option<VideoPress>,
) -> (InputStatus, Option<Action>) {
    use Keycode as K;
    let (key, repeat) = match event.key_code() {
        K::DpadUp => (Key::Up, true),
        K::DpadDown => (Key::Down, true),
        K::DpadLeft => (Key::Left, true),
        K::DpadRight => (Key::Right, true),
        K::DpadCenter | K::Enter | K::NumpadEnter | K::ButtonA | K::ButtonSelect => {
            let key = event.key_code();
            let device = event.device_id();
            let held = event.event_time().saturating_sub(event.down_time()) >= LONG_PRESS_NS;
            if event.action() == KeyAction::Down && event.repeat_count() == 0 {
                *press = ui.focused_video_id().map(|id| VideoPress {
                    id: id.to_owned(),
                    key,
                    device,
                    repeated: false,
                });
            }
            if press
                .as_ref()
                .is_some_and(|p| p.key == key && p.device == device)
            {
                if event.action() == KeyAction::Up {
                    let p = press.take().unwrap();
                    let action = (ui.focused_video_id() == Some(p.id.as_str())).then(|| {
                        ui.key(if p.repeated || held {
                            Key::Menu
                        } else {
                            Key::Select
                        })
                    });
                    return (InputStatus::Handled, action);
                }
                if let Some(p) = press.as_mut() {
                    p.repeated |= event.repeat_count() > 0;
                }
                return (InputStatus::Handled, None);
            }
            (Key::Select, false)
        }
        K::Menu if ui.focused_video_id().is_some() => (Key::Menu, false),
        K::Back | K::Escape | K::ButtonB => (Key::Back, false),
        K::Del | K::ForwardDel => (Key::Delete, true),
        code => {
            // The device's actual keyboard layout supplies accented and non-Latin characters.
            let character = app
                .device_key_character_map(event.device_id())
                .and_then(|map| map.get(code, event.meta_state()));
            match character {
                Ok(KeyMapChar::Unicode(c)) if !c.is_control() => (Key::Character(c), true),
                _ => return (InputStatus::Unhandled, None),
            }
        }
    };
    let action = (event.action() == KeyAction::Down && (repeat || event.repeat_count() == 0))
        .then(|| ui.key(key));
    (InputStatus::Handled, action)
}

struct TouchStart {
    id: i32,
    x: f32,
    y: f32,
}
fn touch_input(
    ui: &mut Ui,
    surface: &Surface,
    start: &mut Option<TouchStart>,
    event: &MotionEvent,
) -> (InputStatus, Option<Action>) {
    use MotionAction as M;
    let action = match event.action() {
        M::Down | M::Up if event.pointer_index() < event.pointer_count() => {
            let p = event.pointer_at_index(event.pointer_index());
            let id = p.pointer_id();
            let point = surface.point(p.x(), p.y());
            if event.action() == M::Down {
                *start = point.map(|(x, y)| TouchStart { id, x, y });
                None
            } else if let (Some(from), Some((x, y))) = (start.take(), point) {
                if id != from.id {
                    None
                } else if (x - from.x).hypot(y - from.y) < 24.0 {
                    let held =
                        event.event_time().saturating_sub(event.down_time()) >= LONG_PRESS_NS;
                    Some(ui.activate_at(x, y, surface.drawn.0, surface.drawn.1, held))
                } else {
                    ui.swipe((from.x, from.y), (x, y), surface.drawn)
                }
            } else {
                None
            }
        }
        M::Cancel | M::PointerDown => {
            *start = None;
            None
        }
        M::Move | M::PointerUp => None,
        _ => return (InputStatus::Unhandled, None),
    };
    (InputStatus::Handled, action)
}

fn start_intent(env: &mut Env, activity: &JObject, intent: &JObject) -> JniResult<()> {
    env.call_method(
        activity,
        jni_str!("startActivity"),
        jni_sig!("(Landroid/content/Intent;)V"),
        &[JValue::Object(intent)],
    )?
    .v()
}

fn internal_player(
    env: &mut Env,
    activity: &JObject,
    class_name: &str,
    extras: &[(&str, &str)],
) -> JniResult<()> {
    let intent = env.new_object(jni_str!("android/content/Intent"), jni_sig!("()V"), &[])?;
    let package = env
        .call_method(
            activity,
            jni_str!("getPackageName"),
            jni_sig!("()Ljava/lang/String;"),
            &[],
        )?
        .l()?;
    let class_name = env.new_string(class_name)?;
    env.call_method(
        &intent,
        jni_str!("setClassName"),
        jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;"),
        &[JValue::Object(&package), JValue::Object(&class_name)],
    )?;
    for (name, value) in extras {
        let key = env.new_string(name)?;
        let value = env.new_string(value)?;
        env.call_method(
            &intent,
            jni_str!("putExtra"),
            jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;"),
            &[JValue::Object(&key), JValue::Object(&value)],
        )?;
    }
    start_intent(env, activity, &intent)
}

fn launch_packages(env: &mut Env, activity: &JObject, packages: &[&str]) -> JniResult<bool> {
    let manager = env
        .call_method(
            activity,
            jni_str!("getPackageManager"),
            jni_sig!("()Landroid/content/pm/PackageManager;"),
            &[],
        )?
        .l()?;
    for package in packages {
        let name = env.new_string(package)?;
        for method in [
            jni_str!("getLeanbackLaunchIntentForPackage"),
            jni_str!("getLaunchIntentForPackage"),
        ] {
            let result = (|| -> JniResult<bool> {
                let intent = env
                    .call_method(
                        &manager,
                        method,
                        jni_sig!("(Ljava/lang/String;)Landroid/content/Intent;"),
                        &[JValue::Object(&name)],
                    )?
                    .l()?;
                if intent.is_null() {
                    return Ok(false);
                }
                start_intent(env, activity, &intent)?;
                Ok(true)
            })();
            match result {
                Ok(true) => return Ok(true),
                Err(error) => {
                    log::warn!("Start von {package} nicht möglich: {error}");
                    if env.exception_check() {
                        env.exception_clear();
                    }
                }
                Ok(false) => {}
            }
        }
    }
    Ok(false)
}

fn return_classic(env: &mut Env, activity: &JObject) -> JniResult<bool> {
    // Android provides the caller for startActivityForResult. The launch extra alone
    // is insufficient: an independently opened preview must find the classic app.
    let caller = env
        .call_method(
            activity,
            jni_str!("getCallingPackage"),
            jni_sig!("()Ljava/lang/String;"),
            &[],
        )?
        .l()?;
    let launch = env
        .call_method(
            activity,
            jni_str!("getIntent"),
            jni_sig!("()Landroid/content/Intent;"),
            &[],
        )?
        .l()?;
    const CLASSIC_PACKAGE: &str = "site.navatv.launcher";
    let official = env.new_string(CLASSIC_PACKAGE)?;
    let extra = env.new_string("opennava.fromClassic")?;
    let from_classic = env
        .call_method(
            &official,
            jni_str!("equals"),
            jni_sig!("(Ljava/lang/Object;)Z"),
            &[JValue::Object(&caller)],
        )?
        .z()?
        && !launch.is_null()
        && env
            .call_method(
                &launch,
                jni_str!("getBooleanExtra"),
                jni_sig!("(Ljava/lang/String;Z)Z"),
                &[JValue::Object(&extra), JValue::Bool(false)],
            )?
            .z()?;
    if !from_classic {
        let action = env.new_string("site.opennava.action.OPEN_CLASSIC")?;
        let intent = env.new_object(
            jni_str!("android/content/Intent"),
            jni_sig!("(Ljava/lang/String;)V"),
            &[JValue::Object(&action)],
        )?;
        env.call_method(
            &intent,
            jni_str!("setPackage"),
            jni_sig!("(Ljava/lang/String;)Landroid/content/Intent;"),
            &[JValue::Object(&official)],
        )?;
        let manager = env
            .call_method(
                activity,
                jni_str!("getPackageManager"),
                jni_sig!("()Landroid/content/pm/PackageManager;"),
                &[],
            )?
            .l()?;
        let component = env
            .call_method(
                &intent,
                jni_str!("resolveActivity"),
                jni_sig!("(Landroid/content/pm/PackageManager;)Landroid/content/ComponentName;"),
                &[JValue::Object(&manager)],
            )?
            .l()?;
        if !component.is_null() {
            start_intent(env, activity, &intent)?;
        } else if !launch_packages(env, activity, &[CLASSIC_PACKAGE])? {
            return Ok(false);
        }
    }
    env.call_method(activity, jni_str!("finish"), jni_sig!("()V"), &[])?
        .v()?;
    Ok(true)
}

fn app_class<'local>(
    env: &mut Env<'local>,
    activity: &JObject,
    name: &str,
) -> JniResult<JClass<'local>> {
    let loader = env
        .call_method(
            activity,
            jni_str!("getClassLoader"),
            jni_sig!("()Ljava/lang/ClassLoader;"),
            &[],
        )?
        .l()?;
    let name = env.new_string(name)?;
    let class = env
        .call_method(
            &loader,
            jni_str!("loadClass"),
            jni_sig!("(Ljava/lang/String;)Ljava/lang/Class;"),
            &[JValue::Object(&name)],
        )?
        .l()?;
    env.cast_local::<JClass>(class)
}

fn consume_home_request(app: &AndroidApp) -> JniResult<bool> {
    JavaVM::singleton()?.attach_current_thread(|env| {
        let raw = app.activity_as_ptr().cast();
        let activity = unsafe { env.as_cast_raw::<Global<JObject>>(&raw)? };
        let class = app_class(env, &activity, "site.navatv.rustpreview.HomeEntryActivity")?;
        env.call_static_method(&class, jni_str!("consumeHomeRequest"), jni_sig!("()Z"), &[])?
            .z()
    })
}

fn with_update_backend<T>(
    app: &AndroidApp,
    call: impl FnOnce(&mut Env, &JClass, &JObject) -> JniResult<T>,
) -> JniResult<T> {
    JavaVM::singleton()?.attach_current_thread(|env| {
        let raw = app.activity_as_ptr().cast();
        let activity = unsafe { env.as_cast_raw::<Global<JObject>>(&raw)? };
        let class = app_class(env, &activity, "site.navatv.updates.Updates")?;
        call(env, &class, &activity)
    })
}

fn fire_home_setting(app: &AndroidApp) -> JniResult<Option<bool>> {
    JavaVM::singleton()?.attach_current_thread(|env| {
        let raw = app.activity_as_ptr().cast();
        let activity = unsafe { env.as_cast_raw::<Global<JObject>>(&raw)? };
        let class = app_class(env, &activity, "site.navatv.rustpreview.FireHomeSettings")?;
        let setting = env
            .call_static_method(
                &class,
                jni_str!("state"),
                jni_sig!("(Landroid/content/Context;)I"),
                &[JValue::Object(&activity)],
            )?
            .i()?;
        Ok(match setting {
            0 => Some(false),
            1 => Some(true),
            _ => None,
        })
    })
}

fn check_home_updates(app: &AndroidApp) {
    let current = app.clone();
    app.run_on_java_main_thread(Box::new(move || {
        let result = with_update_backend(&current, |env, class, activity| {
            env.call_static_method(
                class,
                jni_str!("home"),
                jni_sig!("(Landroid/app/Activity;)V"),
                &[JValue::Object(activity)],
            )?
            .v()
        });
        if let Err(error) = result {
            log::warn!("Updateprüfung konnte nicht gestartet werden: {error}");
        }
    }));
}

fn update_available(app: &AndroidApp) -> JniResult<bool> {
    with_update_backend(app, |env, class, activity| {
        env.call_static_method(
            class,
            jni_str!("available"),
            jni_sig!("(Landroid/content/Context;)Z"),
            &[JValue::Object(activity)],
        )?
        .z()
    })
}

fn home_store_string(app: &AndroidApp, revision: bool) -> JniResult<String> {
    JavaVM::singleton()?.attach_current_thread(|env| {
        let raw = app.activity_as_ptr().cast();
        let activity = unsafe { env.as_cast_raw::<Global<JObject>>(&raw)? };
        let class = app_class(env, &activity, "site.navatv.launcher.HomeLayoutStore")?;
        let method = if revision {
            jni_str!("revision")
        } else {
            jni_str!("snapshot")
        };
        let value = env
            .call_static_method(
                &class,
                method,
                jni_sig!("(Landroid/content/Context;)Ljava/lang/String;"),
                &[JValue::Object(&activity)],
            )?
            .l()?;
        env.cast_local::<JString>(value)?.try_to_string(env)
    })
}

fn installed_icon(app: &AndroidApp, pkg: &str) -> Option<image::RgbaImage> {
    let bytes: JniResult<Vec<u8>> = JavaVM::singleton().and_then(|vm| {
        vm.attach_current_thread(|env| {
            let raw = app.activity_as_ptr().cast();
            let activity = unsafe { env.as_cast_raw::<Global<JObject>>(&raw)? };
            let class = app_class(env, &activity, "site.navatv.rustpreview.HomeAppIcons")?;
            let package = env.new_string(pkg)?;
            let value = env
                .call_static_method(
                    &class,
                    jni_str!("icon"),
                    jni_sig!("(Landroid/content/Context;Ljava/lang/String;)[B"),
                    &[JValue::Object(&activity), JValue::Object(&package)],
                )?
                .l()?;
            if value.is_null() {
                return Ok(vec![]);
            }
            env.convert_byte_array(env.cast_local::<JByteArray>(value)?)
        })
    });
    image::load_from_memory(&bytes.ok()?)
        .ok()
        .map(|bitmap| bitmap.into_rgba8())
}

pub(crate) fn refresh_home(
    app: &AndroidApp,
    previous: &str,
    force: bool,
) -> Option<(String, Reply)> {
    let revision = home_store_string(app, true).ok()?;
    if !force && revision == previous {
        return None;
    }
    let json = home_store_string(app, false).ok()?;
    let layout: HomeLayout = serde_json::from_str(&json).ok()?;
    let icons: HashMap<_, _> = layout
        .pinned_packages()
        .into_iter()
        .filter_map(|pkg| installed_icon(app, pkg).map(|icon| (format!("app:{pkg}"), icon)))
        .collect();
    Some((revision, Reply::Home(layout, icons)))
}
/// Rust workers are attached native threads. App classes need the Activity's own class loader.
pub(crate) fn native_youtube(
    app: &AndroidApp,
    value: &str,
    continuation: bool,
) -> Result<Option<youtube::YoutubePage>, String> {
    let json: JniResult<String> = JavaVM::singleton().and_then(|vm| {
        vm.attach_current_thread(|env| {
            let raw = app.activity_as_ptr().cast();
            let activity_global = unsafe { env.as_cast_raw::<Global<JObject>>(&raw)? };
            let activity: &JObject = &activity_global;
            let class = app_class(env, activity, "site.navatv.full.YoutubeBridge")?;
            let input = env.new_string(value)?;
            let method = if continuation {
                jni_str!("more")
            } else {
                jni_str!("videos")
            };
            let response = env
                .call_static_method(
                    &class,
                    method,
                    jni_sig!("(Landroid/app/Activity;Ljava/lang/String;)Ljava/lang/String;"),
                    &[JValue::Object(activity), JValue::Object(&input)],
                )?
                .l()?;
            env.cast_local::<JString>(response)?.try_to_string(env)
        })
    });
    let json = json.map_err(|error| {
        log::warn!("YouTube-Dienst konnte nicht abgefragt werden: {error}");
        "YouTube ist gerade nicht erreichbar.".to_owned()
    })?;
    youtube::native_videos(&json, !continuation && value.is_empty())
}

fn platform_action(app: &AndroidApp, action: &Action) -> JniResult<bool> {
    JavaVM::singleton()?.attach_current_thread(|env| {
        // Borrow the global Activity reference owned by android-activity for this callback only.
        let raw = app.activity_as_ptr().cast();
        let activity_global = unsafe { env.as_cast_raw::<Global<JObject>>(&raw)? };
        let activity: &JObject = &activity_global;
        match action {
            Action::SetFireHome(enabled) => {
                let class = app_class(env, activity, "site.navatv.rustpreview.FireHomeService")?;
                env.call_static_method(
                    &class,
                    jni_str!("setRedirectEnabled"),
                    jni_sig!("(Landroid/content/Context;Z)V"),
                    &[JValue::Object(activity), JValue::Bool(*enabled)],
                )?
                .v()?;
                Ok(true)
            }
            Action::Updates => {
                let class = app_class(env, activity, "site.navatv.updates.Updates")?;
                env.call_static_method(
                    &class,
                    jni_str!("open"),
                    jni_sig!("(Landroid/content/Context;)V"),
                    &[JValue::Object(activity)],
                )?
                .v()?;
                Ok(true)
            }
            Action::ChooseLauncher => {
                internal_player(
                    env,
                    activity,
                    "site.navatv.launcher.LauncherChoiceActivity",
                    &[],
                )?;
                Ok(true)
            }
            Action::ConnectPhone => {
                internal_player(env, activity, "site.navatv.launcher.PhoneLinkActivity", &[])?;
                Ok(true)
            }
            Action::Personalization => {
                internal_player(
                    env,
                    activity,
                    "site.navatv.launcher.HomePersonalizationActivity",
                    &[],
                )?;
                Ok(true)
            }
            Action::LaunchApp(package) => launch_packages(env, activity, &[package.as_str()]),
            Action::LiveTv => {
                let class = app_class(env, activity, "site.navatv.rustpreview.SeniorLive")?;
                env.call_static_method(
                    &class,
                    jni_str!("open"),
                    jni_sig!("(Landroid/app/Activity;)V"),
                    &[JValue::Object(activity)],
                )?
                .v()?;
                Ok(true)
            }
            Action::YoutubeAccount => {
                internal_player(
                    env,
                    activity,
                    "site.navatv.rustpreview.YoutubeAccountActivity",
                    &[],
                )?;
                Ok(true)
            }
            Action::YoutubePlay { id, title, channel } => {
                if !youtube::valid_id(id) {
                    return Ok(false);
                }
                internal_player(
                    env,
                    activity,
                    "site.navatv.full.NavaPlayerActivity",
                    &[("video_id", id), ("title", title), ("author", channel)],
                )?;
                Ok(true)
            }
            Action::MediaPlay { url, title } => {
                if !url.starts_with("https://") {
                    return Ok(false);
                }
                internal_player(
                    env,
                    activity,
                    "site.navatv.rustpreview.StreamActivity",
                    &[("stream_url", url), ("title", title)],
                )?;
                Ok(true)
            }
            Action::None => Ok(true),
            Action::YoutubeCatalog { .. }
            | Action::MainSearch { .. }
            | Action::SearchMore { .. }
            | Action::YoutubeMore { .. }
            | Action::YoutubeSearch { .. }
            | Action::Catalog { .. }
            | Action::CatalogPlay { .. } => Ok(false),
            Action::ReturnClassic => return_classic(env, activity),
            Action::Exit => {
                env.call_method(activity, jni_str!("finish"), jni_sig!("()V"), &[])?
                    .v()?;
                Ok(true)
            }
            Action::OpenUrl(url) => {
                if !url.starts_with("https://") {
                    return Ok(false);
                }
                let url = env.new_string(url)?;
                let uri = env
                    .call_static_method(
                        jni_str!("android/net/Uri"),
                        jni_str!("parse"),
                        jni_sig!("(Ljava/lang/String;)Landroid/net/Uri;"),
                        &[JValue::Object(&url)],
                    )?
                    .l()?;
                let view = env.new_string("android.intent.action.VIEW")?;
                let intent = env.new_object(
                    jni_str!("android/content/Intent"),
                    jni_sig!("(Ljava/lang/String;Landroid/net/Uri;)V"),
                    &[JValue::Object(&view), JValue::Object(&uri)],
                )?;
                start_intent(env, activity, &intent)?;
                Ok(true)
            }
            Action::Launch(packages) => launch_packages(env, activity, packages),
        }
    })
}

pub fn run(app: AndroidApp) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_tag("OpenNavaRust")
            .with_max_level(log::LevelFilter::Info),
    );
    app.set_window_flags(WindowManagerFlags::FULLSCREEN, WindowManagerFlags::empty());
    let mut ui = Ui::new();
    if let Some(path) = app.internal_data_path() {
        ui.set_storage(path);
    }
    let mut surface: Option<Surface> = None;
    let mut touch: Option<TouchStart> = None;
    let mut video_press = None;
    let mut resumed = false;
    let mut running = true;
    let mut worker = Worker::new(app.clone());
    let mut refresh_youtube_on_resume = false;
    log::info!("NativeActivity gestartet");
    let mut next_home_check = Instant::now();
    let mut next_update_refresh = Instant::now();
    let mut home_started = false;
    let mut clock_minute = 0;
    while running {
        // A displayed clock needs one refresh on the minute. Suspended activities sleep indefinitely.
        let timeout = (resumed && surface.is_some()).then(|| {
            let now = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default();
            let until_clock = Duration::from_millis(60_000 - (now.as_millis() % 60_000) as u64);
            let now = Instant::now();
            let mut timeout = until_clock.min(next_update_refresh.saturating_duration_since(now));
            if ui.watches_home() {
                timeout = timeout.min(next_home_check.saturating_duration_since(now));
            }
            timeout
        });
        app.poll_events(timeout, |event| {
            let mut redraw = false;
            let mut actions = Vec::new();
            if matches!(
                &event,
                PollEvent::Main(MainEvent::Resume { .. } | MainEvent::GainedFocus)
            ) {
                match consume_home_request(&app) {
                    Ok(true) => {
                        ui.open_home();
                        refresh_youtube_on_resume = false;
                    }
                    Err(error) => log::warn!("Home-Anfrage konnte nicht gelesen werden: {error}"),
                    Ok(false) => {}
                }
            }
            match event {
                PollEvent::Main(MainEvent::InitWindow { .. }) => {
                    surface = app.native_window().and_then(|window| {
                        match Surface::new(window, app.content_rect()) {
                            Ok(surface) => Some(surface),
                            Err(error) => {
                                log::error!("NativeWindow: {error}");
                                None
                            }
                        }
                    });
                    redraw = true;
                }
                PollEvent::Main(MainEvent::TerminateWindow { .. }) => {
                    surface = None;
                    touch = None;
                    video_press = None;
                }
                PollEvent::Main(
                    MainEvent::WindowResized { .. }
                    | MainEvent::ContentRectChanged { .. }
                    | MainEvent::ConfigChanged { .. },
                ) => {
                    if let Some(surface) = surface.as_mut()
                        && let Err(error) = surface.resize(app.content_rect())
                    {
                        log::warn!("Größe des NativeWindow: {error}");
                    }
                    redraw = true;
                }
                PollEvent::Main(MainEvent::Resume { .. }) => {
                    if !home_started {
                        home_started = true;
                        check_home_updates(&app);
                    }
                    ui.set_fire_home_enabled(fire_home_setting(&app).unwrap_or(None));
                    next_update_refresh = Instant::now();
                    if std::mem::take(&mut refresh_youtube_on_resume) {
                        actions.push(ui.request_page());
                    }
                    worker.refresh_home(true);
                    next_home_check = Instant::now() + Duration::from_secs(2);
                    resumed = true;
                    redraw = true;
                }
                PollEvent::Main(MainEvent::Pause | MainEvent::Stop) => {
                    resumed = false;
                    touch = None;
                    video_press = None;
                }
                PollEvent::Main(MainEvent::LostFocus) => {
                    touch = None;
                    video_press = None;
                }
                PollEvent::Timeout => {
                    let minute = SystemTime::now()
                        .duration_since(UNIX_EPOCH)
                        .unwrap_or_default()
                        .as_secs()
                        / 60;
                    redraw = minute != clock_minute;
                    clock_minute = minute;
                }
                PollEvent::Main(MainEvent::RedrawNeeded { .. } | MainEvent::GainedFocus) => {
                    redraw = true;
                }
                PollEvent::Main(MainEvent::Destroy) => {
                    surface = None;
                    running = false;
                }
                PollEvent::Main(MainEvent::InputAvailable) => {
                    if let Ok(mut events) = app.input_events_iter() {
                        while events.next(|event| {
                            let (status, action) = match event {
                                InputEvent::KeyEvent(key) => {
                                    key_input(&app, &mut ui, key, &mut video_press)
                                }
                                InputEvent::MotionEvent(event) => match &surface {
                                    Some(surface) => {
                                        touch_input(&mut ui, surface, &mut touch, event)
                                    }
                                    None => (InputStatus::Unhandled, None),
                                },
                                _ => (InputStatus::Unhandled, None),
                            };
                            if let Some(action) = action {
                                redraw = true;
                                if !matches!(action, Action::None) {
                                    actions.push(action);
                                }
                            }
                            status
                        }) {}
                    }
                }
                _ => {}
            }
            if !running {
                return;
            }
            if resumed && Instant::now() >= next_update_refresh {
                // The shared updater caches availability. Also pick up checks that finish while
                // the launcher stays in the foreground without waking a suspended activity.
                if let Ok(available) = update_available(&app) {
                    redraw |= ui.set_update_available(available);
                }
                next_update_refresh = Instant::now() + Duration::from_secs(5);
            }
            if resumed && ui.watches_home() && Instant::now() >= next_home_check {
                worker.refresh_home(false);
                next_home_check = Instant::now() + Duration::from_secs(2);
            }
            while let Ok(reply) = worker.replies.try_recv() {
                match reply {
                    Reply::Home(layout, icons) => {
                        ui.set_home(layout, icons);
                        redraw |= ui.watches_home();
                    }
                    Reply::CombinedSearch(id, broadcasts, youtube) if ui.is_current_request(id) => {
                        log::info!(
                            "Hauptsuche: {} Mediathek-Treffer, {} YouTube-Videos",
                            broadcasts.as_ref().map_or(0, |page| page.items.len()),
                            youtube.as_ref().map_or(0, |page| page.videos.len())
                        );
                        actions.push(ui.combined_search_loaded(id, broadcasts, youtube));
                        redraw = true;
                    }
                    Reply::SearchMore(id, broadcasts, youtube) if ui.is_current_request(id) => {
                        log::info!(
                            "Hauptsuche nachgeladen: {} Mediathek-Treffer, {} YouTube-Videos",
                            broadcasts.as_ref().map_or(0, |page| page.items.len()),
                            youtube.as_ref().map_or(0, |page| page.videos.len())
                        );
                        actions.push(ui.search_more_loaded(id, Some(broadcasts), youtube));
                        redraw = true;
                    }
                    Reply::Youtube(id, result) if ui.is_current_request(id) => {
                        match &result {
                            Ok(rows) => {
                                log::info!("YouTube-Katalog: {} Videos", rows.videos.len());
                            }
                            Err(_) => log::warn!("YouTube-Katalog nicht verfügbar"),
                        }
                        actions.push(ui.youtube_loaded(id, result));
                        redraw = true;
                    }
                    Reply::YoutubeMore(id, result) if ui.is_current_request(id) => {
                        actions.push(ui.youtube_more_loaded(id, result));
                        redraw = true;
                    }
                    Reply::Catalog(id, provider, result) if ui.is_current_request(id) => {
                        match &result {
                            Ok(rows) => {
                                log::info!("Senderkatalog {provider}: {} Sendungen", rows.len())
                            }
                            Err(_) => log::warn!("Senderkatalog {provider} nicht verfügbar"),
                        }
                        ui.catalog_loaded(id, result);
                        redraw = true;
                    }
                    Reply::Stream(id, title, result) if ui.is_current_request(id) && resumed => {
                        match result {
                            Ok(url) => actions.push(Action::MediaPlay { url, title }),
                            Err(message) => {
                                log::warn!("Sendung konnte nicht aufgelöst werden");
                                ui.notice(&message);
                                redraw = true;
                            }
                        }
                    }
                    Reply::Image(url, result) => {
                        redraw |= worker.image_result(&mut ui, url, result)
                    }
                    _ => {}
                }
            }
            for action in actions {
                match action {
                    Action::YoutubeMore { request, cursor } => {
                        worker.submit(Job::YoutubeMore { request, cursor });
                        continue;
                    }
                    Action::YoutubeSearch { request, query } => {
                        worker.search(request, query, SearchScope::Youtube);
                        continue;
                    }
                    Action::YoutubeCatalog { request } => {
                        worker.submit(Job::Youtube(request));
                        continue;
                    }
                    Action::MainSearch {
                        request,
                        provider,
                        query,
                    } => {
                        if query.is_empty() {
                            worker.submit(Job::MainSearch {
                                request,
                                provider,
                                query,
                            });
                        } else {
                            worker.search(request, query, SearchScope::Main(provider));
                        }
                        continue;
                    }
                    Action::SearchMore {
                        request,
                        query,
                        providers,
                        page,
                        youtube_cursor,
                    } => {
                        worker.submit(Job::SearchMore {
                            request,
                            query,
                            providers,
                            page,
                            youtube_cursor,
                        });
                        continue;
                    }
                    Action::Catalog {
                        request,
                        provider,
                        query,
                    } => {
                        worker.submit(Job::Catalog {
                            request,
                            provider,
                            query,
                        });
                        continue;
                    }
                    Action::CatalogPlay { request, item } => {
                        worker.submit(Job::Resolve { request, item });
                        continue;
                    }
                    _ => {}
                }
                let result = platform_action(&app, &action);
                if matches!(action, Action::SetFireHome(_)) {
                    ui.set_fire_home_enabled(fire_home_setting(&app).unwrap_or(None));
                    redraw = true;
                }
                if matches!((&action, &result), (Action::YoutubeAccount, Ok(true))) {
                    refresh_youtube_on_resume = true;
                }
                if !matches!(result, Ok(true)) {
                    if let Err(error) = result {
                        log::warn!("Android-Intent fehlgeschlagen: {error}");
                    }
                    ui.notice(match action {
                        Action::ReturnClassic => "Die klassische OpenNava-App ist nicht verfügbar.",
                        Action::Launch(_) | Action::LaunchApp(_) => {
                            "Diese App ist auf diesem Fernseher nicht verfügbar."
                        }
                        _ => "Android konnte dieses Ziel nicht öffnen.",
                    });
                }
            }
            if resumed && surface.is_some() {
                worker.request_images(&ui);
            }
            if redraw
                && let Some(surface) = surface.as_mut()
                && let Err(error) = surface.render(&mut ui)
            {
                log::warn!("Bildausgabe: {error}");
            }
        });
    }
}
