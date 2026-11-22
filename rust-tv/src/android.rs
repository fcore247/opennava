use std::time::{Duration, SystemTime, UNIX_EPOCH};

use android_activity::{
    AndroidApp, InputStatus, MainEvent, PollEvent, Rect, WindowManagerFlags,
    input::{InputEvent, KeyAction, KeyEvent, KeyMapChar, Keycode, MotionAction, MotionEvent},
    ndk::{hardware_buffer_format::HardwareBufferFormat, native_window::NativeWindow},
};
use jni::{
    Env, JValue, JavaVM, errors::Result as JniResult, jni_sig, jni_str, objects::JObject,
    refs::Global,
};

use crate::{
    ui::{Action, Key, Ui},
    worker::{Job, Reply, Worker},
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

fn key_input(app: &AndroidApp, ui: &mut Ui, event: &KeyEvent) -> (InputStatus, Option<Action>) {
    use Keycode as K;
    let (key, repeat) = match event.key_code() {
        K::DpadUp => (Key::Up, true),
        K::DpadDown => (Key::Down, true),
        K::DpadLeft => (Key::Left, true),
        K::DpadRight => (Key::Right, true),
        K::DpadCenter | K::Enter | K::NumpadEnter | K::ButtonA | K::ButtonSelect => {
            (Key::Select, false)
        }
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
                (id == from.id && (x - from.x).hypot(y - from.y) < 24.0)
                    .then(|| ui.tap(x, y, surface.drawn.0, surface.drawn.1))
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

fn platform_action(app: &AndroidApp, action: &Action, ui: &Ui) -> JniResult<bool> {
    JavaVM::singleton()?.attach_current_thread(|env| {
        // Borrow the global Activity reference owned by android-activity for this callback only.
        let raw = app.activity_as_ptr().cast();
        let activity_global = unsafe { env.as_cast_raw::<Global<JObject>>(&raw)? };
        let activity: &JObject = &activity_global;
        match action {
            Action::YoutubePlay(id) => {
                if !youtube::valid_id(id) {
                    return Ok(false);
                }
                let metadata = ui.youtube_metadata(id);
                let (title, author) = metadata
                    .as_ref()
                    .map_or((id.as_str(), ""), |(title, author)| {
                        (title.as_str(), author.as_str())
                    });
                internal_player(
                    env,
                    activity,
                    "site.navatv.full.NavaPlayerActivity",
                    &[("video_id", id), ("title", title), ("author", author)],
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
            Action::YoutubeCatalog { .. } | Action::Catalog { .. } | Action::CatalogPlay { .. } => {
                Ok(false)
            }
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
    let mut resumed = false;
    let mut running = true;
    let mut worker = Worker::new(app.create_waker());
    log::info!("NativeActivity gestartet");
    while running {
        // A displayed clock needs one refresh on the minute. Suspended activities sleep indefinitely.
        let timeout = (resumed && surface.is_some()).then(|| {
            let now = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default();
            Duration::from_millis(60_000 - (now.as_millis() % 60_000) as u64)
        });
        app.poll_events(timeout, |event| {
            let mut redraw = false;
            let mut actions = Vec::new();
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
                    resumed = true;
                    redraw = true;
                }
                PollEvent::Main(MainEvent::Pause | MainEvent::Stop) => {
                    resumed = false;
                    touch = None;
                }
                PollEvent::Main(MainEvent::LostFocus) => {
                    touch = None;
                }
                PollEvent::Main(MainEvent::RedrawNeeded { .. } | MainEvent::GainedFocus)
                | PollEvent::Timeout => {
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
                                InputEvent::KeyEvent(key) => key_input(&app, &mut ui, key),
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
            while let Ok(reply) = worker.replies.try_recv() {
                match reply {
                    Reply::Youtube(id, result) if ui.is_current_request(id) => {
                        match &result {
                            Ok(rows) => {
                                log::info!("YouTube-Katalog: {} Videos", rows.len());
                            }
                            Err(_) => log::warn!("YouTube-Katalog nicht verfügbar"),
                        }
                        ui.youtube_loaded(id, result);
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
                    Action::YoutubeCatalog { request } => {
                        worker.submit(Job::Youtube(request));
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
                let result = platform_action(&app, &action, &ui);
                if !matches!(result, Ok(true)) {
                    if let Err(error) = result {
                        log::warn!("Android-Intent fehlgeschlagen: {error}");
                    }
                    ui.notice(match action {
                        Action::ReturnClassic => "Die klassische OpenNava-App ist nicht verfügbar.",
                        Action::Launch(_) => "Diese App ist auf diesem Fernseher nicht verfügbar.",
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
