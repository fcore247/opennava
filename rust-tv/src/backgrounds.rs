//! The viewer's own photos as the launcher background, and the settings page with its chapters.
//! The companion writes backgrounds.json and backgrounds/<id>.jpg through the authenticated provider.
use super::*;
use std::{
    path::Path,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

pub(super) const LIMIT: usize = 10;
pub(super) const INTERVALS: [(u64, &str); 3] = [
    (30, "Alle 30 Sekunden"),
    (3_600, "Jede Stunde"),
    (86_400, "Jeden Tag"),
];
pub(super) const CHAPTERS: [(&str, &str); 4] = [
    ("Darstellung", "Startseite und Aussehen"),
    ("Hintergrundbilder", "Deine eigenen Fotos"),
    ("Handy & Konten", "Verbinden und anmelden"),
    ("System", "Updates und Beenden"),
];
pub(super) const CHAPTER_ID: usize = 600;
pub(super) const SLIDER_ID: usize = 650;
const PAIR_ID: usize = 660;
const DEFAULT_ID: usize = 661;
const PHOTO_ID: usize = 670;

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub(super) struct Rotation {
    pub interval: u64,
    /// Milliseconds since the epoch when the rotation was last changed.
    pub since: u64,
    pub images: Vec<String>,
    /// Photos that are kept on the TV but currently not shown.
    pub off: Vec<String>,
}

impl Rotation {
    fn valid_id(id: &str) -> bool {
        id.strip_prefix("bg-").is_some_and(|hex| {
            hex.len() == 12 && hex.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        })
    }
    fn read(dir: &Path) -> Self {
        let mut value: Self = std::fs::read(dir.join("backgrounds.json"))
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
        value.images.retain(|id| Self::valid_id(id));
        value.images.truncate(LIMIT);
        if !INTERVALS.iter().any(|(s, _)| *s == value.interval) {
            value.interval = 3_600;
        }
        value
    }
    pub fn active(&self) -> Vec<&String> {
        self.images.iter().filter(|id| !self.off.contains(id)).collect()
    }
    /// The photo on screen at `now`, or none for the bundled landscape.
    pub fn current(&self, now: u64) -> Option<&String> {
        let active = self.active();
        if active.is_empty() {
            return None;
        }
        let step = now.saturating_sub(self.since) / (self.interval.max(1) * 1000);
        Some(active[(step % active.len() as u64) as usize])
    }
    pub fn wait(&self, now: u64) -> Option<Duration> {
        if self.active().len() < 2 {
            return None;
        }
        let every = self.interval.max(1) * 1000;
        Some(Duration::from_millis(every - now.saturating_sub(self.since) % every + 20))
    }
}

#[derive(Default)]
pub(super) struct Backgrounds {
    pub rotation: Rotation,
    pub thumbs: HashMap<String, RgbaImage>,
    shown: Option<String>,
    original: Option<RgbaImage>,
}

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn load(dir: &Path, id: &str) -> Option<RgbaImage> {
    let mut reader = image::ImageReader::open(dir.join("backgrounds").join(format!("{id}.jpg")))
        .ok()?
        .with_guessed_format()
        .ok()?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(4096);
    limits.max_image_height = Some(4096);
    reader.limits(limits);
    Some(reader.decode().ok()?.resize_to_fill(W, H, FilterType::Triangle).to_rgba8())
}

impl Ui {
    /// Reads the rotation again and shows the photo that is due. Returns true when the screen changed.
    pub fn refresh_backgrounds(&mut self) -> bool {
        let Some(dir) = self.storage.clone() else {
            return false;
        };
        let latest = Rotation::read(&dir);
        let changed = latest != self.backgrounds.rotation;
        if changed {
            self.backgrounds.thumbs.retain(|name, _| latest.images.iter().any(|id| name.ends_with(id.as_str())));
            for id in &latest.images {
                let name = format!("photo:{id}");
                if !self.backgrounds.thumbs.contains_key(&name)
                    && let Some(photo) = load(&dir, id)
                {
                    let small = image::imageops::resize(&photo, 320, 180, FilterType::Triangle);
                    self.backgrounds.thumbs.insert(name, small);
                }
            }
            self.backgrounds.rotation = latest;
        }
        let due = self.backgrounds.rotation.current(now()).cloned();
        if due == self.backgrounds.shown {
            return changed;
        }
        if self.backgrounds.original.is_none() {
            self.backgrounds.original = self.assets.get("background").cloned();
        }
        let picture = due
            .as_deref()
            .and_then(|id| load(&dir, id))
            .or_else(|| self.backgrounds.original.clone());
        if let Some(picture) = picture {
            self.assets.insert("background", picture);
            self.pictures.retain(|key, _| key.0 != "background");
        }
        self.backgrounds.shown = due;
        true
    }
    pub fn background_wait(&self) -> Option<Duration> {
        self.backgrounds.rotation.wait(now())
    }
    pub(crate) fn in_settings(&self) -> bool {
        self.page == Page::Settings
    }
    fn save_rotation(&mut self) {
        let Some(dir) = self.storage.clone() else {
            return;
        };
        self.backgrounds.rotation.since = now();
        let temp = dir.join("backgrounds.json.tv.tmp");
        let saved = serde_json::to_vec(&self.backgrounds.rotation)
            .map_err(std::io::Error::from)
            .and_then(|data| std::fs::write(&temp, data))
            .and_then(|_| std::fs::rename(&temp, dir.join("backgrounds.json")));
        if saved.is_err() {
            self.notice("Die Änderung konnte nicht gespeichert werden.");
        }
        self.refresh_backgrounds();
    }
    pub(super) fn toggle_photo(&mut self, index: usize) {
        let rotation = &mut self.backgrounds.rotation;
        let Some(id) = rotation.images.get(index).cloned() else {
            return;
        };
        if rotation.off.contains(&id) {
            rotation.off.retain(|other| *other != id);
        } else {
            rotation.off.push(id);
        }
        self.save_rotation();
    }
    pub(super) fn default_background(&mut self) {
        self.backgrounds.rotation.off = self.backgrounds.rotation.images.clone();
        self.save_rotation();
    }
    pub(super) fn step_interval(&mut self, step: isize, wrap: bool) {
        let at = INTERVALS
            .iter()
            .position(|(s, _)| *s == self.backgrounds.rotation.interval)
            .unwrap_or(1) as isize;
        let next = if wrap {
            (at + step).rem_euclid(3)
        } else {
            (at + step).clamp(0, 2)
        };
        self.backgrounds.rotation.interval = INTERVALS[next as usize].0;
        self.save_rotation();
    }
    /// The slider takes Left and Right; the chapter list switches while the remote moves along it.
    pub(super) fn settings_key(&mut self, key: Key) -> bool {
        if self.page != Page::Settings {
            return false;
        }
        if self.focus == SLIDER_ID && matches!(key, Key::Left | Key::Right) {
            self.step_interval(if matches!(key, Key::Left) { -1 } else { 1 }, false);
            return true;
        }
        let chapter = self.focus.checked_sub(CHAPTER_ID).filter(|i| *i < CHAPTERS.len());
        if let Some(i) = chapter {
            match key {
                Key::Up if i > 0 => {
                    self.chapter = i - 1;
                    self.focus = CHAPTER_ID + i - 1;
                    return true;
                }
                Key::Down if i + 1 < CHAPTERS.len() => {
                    self.chapter = i + 1;
                    self.focus = CHAPTER_ID + i + 1;
                    return true;
                }
                Key::Right | Key::Select => {
                    let first = self.controls.iter().find(|c| c.x >= 400. && c.y < 620.);
                    if let Some(id) = first.map(|c| c.id) {
                        self.focus = id;
                    }
                    return true;
                }
                _ => {}
            }
        }
        // Leaving the panel to the left returns to the chapter it belongs to.
        if matches!(key, Key::Left)
            && let Some(current) = self.controls.iter().find(|c| c.id == self.focus)
            && current.x >= 400.
            && current.y < 620.
            && !self.controls.iter().any(|other| {
                other.x >= 400.
                    && other.x + other.w <= current.x + 2.
                    && other.y < current.y + current.h
                    && other.y + other.h > current.y
            })
        {
            self.focus = CHAPTER_ID + self.chapter;
            return true;
        }
        false
    }
    fn setting_row(
        &mut self,
        c: &mut Pixmap,
        id: usize,
        slot: usize,
        title: &str,
        detail: &str,
        command: Command,
    ) {
        let rect = [440., 234. + slot as f32 * 77., 744., 68.];
        self.control(id, rect, command);
        let focused = self.focus == id;
        fill(c, rect, 18., if focused { ACCENT } else { [255, 255, 255, 16] });
        let (ink, soft) = if focused {
            ([28, 25, 38, 255], [78, 70, 98, 255])
        } else {
            (WHITE, SOFT)
        };
        self.text(c, title, rect[0] + 24., rect[1] + 29., 21, ink, false);
        self.text(c, detail, rect[0] + 24., rect[1] + 53., 15, soft, false);
        self.text(c, "›", rect[0] + rect[2] - 40., rect[1] + 46., 30, soft, false);
    }
    pub(super) fn draw_settings(&mut self, c: &mut Pixmap) {
        let chapter = self.chapter.min(CHAPTERS.len() - 1);
        for (i, (name, detail)) in CHAPTERS.iter().enumerate() {
            let rect = [64., 180. + i as f32 * 94., 328., 82.];
            self.control(CHAPTER_ID + i, rect, Command::Chapter(i));
            let focused = self.focus == CHAPTER_ID + i;
            let open = i == chapter;
            let back = if focused {
                ACCENT
            } else if open {
                [232, 217, 251, 40]
            } else {
                [10, 18, 32, 150]
            };
            fill(c, rect, 20., back);
            if open && !focused {
                fill(c, [rect[0] + 10., rect[1] + 22., 5., 38.], 2.5, ACCENT);
            }
            let (ink, soft) = if focused {
                ([28, 25, 38, 255], [78, 70, 98, 255])
            } else {
                (WHITE, SOFT)
            };
            self.text(c, name, rect[0] + 28., rect[1] + 36., 22, ink, false);
            self.text(c, detail, rect[0] + 28., rect[1] + 61., 14, soft, false);
            if i == 3 && self.update_available {
                fill(c, [rect[0] + rect[2] - 28., rect[1] + 36., 11., 11.], 5.5, [112, 219, 144, 255]);
            }
        }
        let panel = [416., 162., 800., 458.];
        fill(c, panel, 28., [9, 16, 30, 214]);
        stroke(c, panel, 28., [255, 255, 255, 30], 1.);
        self.text(c, CHAPTERS[chapter].0, 440., 210., 27, WHITE, false);
        match chapter {
            0 => {
                self.setting_row(c, 4, 0, "Personalisierung", "Apps und Ordner auf der Startseite", Command::Personalization);
                let (title, detail) = if self.prefs.darker {
                    ("Abdunkeln: stark", "Der Hintergrund ist sehr dunkel")
                } else {
                    ("Abdunkeln: leicht", "Der Hintergrund bleibt gut sichtbar")
                };
                self.setting_row(c, 0, 1, title, detail, Command::Shade);
            }
            1 => self.draw_photo_settings(c),
            2 => {
                self.setting_row(c, 7, 0, "Handy verbinden", "Fernbedienung, Mediatheken und Fotos vom Handy", Command::ConnectPhone);
                self.setting_row(c, 3, 1, "YouTube-Konto verwalten", "An- und abmelden", Command::YoutubeAccount);
                self.setting_row(c, 9, 2, "Einrichtung erneut starten", "App laden, verbinden und kennenlernen", Command::Setup);
            }
            _ => {
                let update = if self.update_available { "Update verfügbar" } else { "Nach Updates suchen" };
                self.setting_row(c, 5, 0, update, "OpenNava auf diesem Fernseher", Command::Updates);
                let mut slot = 1;
                if let Some(enabled) = self.fire_home_enabled {
                    let title = if enabled { "Home-Taste: OpenNava" } else { "Home-Taste: Amazon" };
                    self.setting_row(c, 6, slot, title, "Welche Startseite die Taste öffnet", Command::SetFireHome(!enabled));
                    slot += 1;
                }
                self.setting_row(c, 2, slot, "Vorschau schließen", "Zurück zu deinem Fernseher", Command::Page(Page::Exit));
                self.setting_row(c, 10, slot + 1, "OpenNava zurücksetzen", "Alles löschen und neu einrichten", Command::Reset);
            }
        }
    }
    fn draw_photo_settings(&mut self, c: &mut Pixmap) {
        let images = self.backgrounds.rotation.images.clone();
        if images.is_empty() {
            self.text(c, "Noch keine eigenen Fotos", 440., 292., 25, WHITE, false);
            for (i, line) in [
                "1.  Öffne OpenNava Companion auf deinem Handy.",
                "2.  Einstellungen  ›  Background images  ›  Add photos.",
                "3.  Wähle ein Foto oder mehrere – sie erscheinen hier.",
            ]
            .iter()
            .enumerate()
            {
                self.text(c, line, 440., 340. + i as f32 * 38., 19, SOFT, false);
            }
            self.button(c, PAIR_ID, "Handy verbinden", [440., 470., 330., 60.], Command::ConnectPhone, false);
            return;
        }
        let active = self.backgrounds.rotation.active().len();
        let summary = match active {
            0 => "Standardbild aktiv".to_owned(),
            1 => "1 Foto als Hintergrund".to_owned(),
            n => format!("{n} Fotos wechseln sich ab"),
        };
        self.text(c, &summary, 440., 238., 16, SOFT, false);
        self.button(c, DEFAULT_ID, "Standardbild", [1004., 184., 180., 42.], Command::DefaultBackground, active == 0);
        for (i, id) in images.iter().enumerate() {
            let rect = [440. + (i % 5) as f32 * 151., 256. + (i / 5) as f32 * 97., 140., 84.];
            self.control(PHOTO_ID + i, rect, Command::Photo(i));
            let name = format!("photo:{id}");
            if self.backgrounds.thumbs.contains_key(&name) {
                self.picture(c, &name, rect, 14.);
            } else {
                fill(c, rect, 14., [40, 48, 66, 255]);
            }
            let used = !self.backgrounds.rotation.off.contains(id);
            if !used {
                fill(c, rect, 14., [5, 8, 16, 170]);
            } else {
                let dot = [rect[0] + rect[2] - 30., rect[1] + 8., 22., 22.];
                fill(c, dot, 11., ACCENT);
                check_mark(c, dot[0] + 5., dot[1] + 6.);
            }
            if self.focus == PHOTO_ID + i {
                stroke(c, rect, 14., ACCENT, 4.);
            }
        }
        if active < 2 {
            self.text(c, "Markiere mit OK mehrere Fotos, um sie automatisch zu wechseln.", 440., 505., 17, SOFT, false);
            return;
        }
        self.draw_slider(c);
    }
    /// A thick track with one dot for each interval.
    fn draw_slider(&mut self, c: &mut Pixmap) {
        let rect = [440., 466., 744., 138.];
        self.control(SLIDER_ID, rect, Command::NextInterval);
        let focused = self.focus == SLIDER_ID;
        fill(c, rect, 20., [255, 255, 255, if focused { 26 } else { 12 }]);
        if focused {
            stroke(c, rect, 20., ACCENT, 3.);
        }
        self.text(c, "Foto wechseln", 464., 497., 17, SOFT, false);
        let at = INTERVALS
            .iter()
            .position(|(s, _)| *s == self.backgrounds.rotation.interval)
            .unwrap_or(1);
        let (left, span, middle) = (530., 564., 536.);
        fill(c, [left - 22., middle - 18., span + 44., 36.], 18., [255, 255, 255, 36]);
        let knob = left + span / 2. * at as f32;
        gradient(c, [left - 22., middle - 18., knob - left + 44., 36.], 18., [158, 134, 244, 255], [226, 208, 255, 255]);
        for i in 0..3 {
            let x = left + span / 2. * i as f32;
            if i != at {
                let color = if i < at { [43, 32, 82, 200] } else { [225, 227, 240, 200] };
                fill(c, [x - 7., middle - 7., 14., 14.], 7., color);
            }
        }
        fill(c, [knob - 27., middle - 27., 54., 54.], 27., WHITE);
        fill(c, [knob - 10., middle - 10., 20., 20.], 10., [140, 112, 232, 255]);
        for (i, (_, label)) in INTERVALS.iter().enumerate() {
            let x = left + span / 2. * i as f32;
            self.centered(c, label, [x - 100., 569., 200., 28.], 17, if i == at { WHITE } else { SOFT });
        }
    }
}

/// Manrope has no check glyph, so the mark is drawn.
fn check_mark(c: &mut Pixmap, x: f32, y: f32) {
    let mut path = PathBuilder::new();
    path.move_to(x, y + 5.);
    path.line_to(x + 4.5, y + 9.5);
    path.line_to(x + 12., y + 1.);
    let Some(path) = path.finish() else { return };
    let mut paint = Paint::default();
    paint.set_color_rgba8(28, 25, 38, 255);
    let line = Stroke { width: 2.8, line_cap: tiny_skia::LineCap::Round, line_join: tiny_skia::LineJoin::Round, ..Stroke::default() };
    c.stroke_path(&path, &paint, &line, Transform::identity(), None);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rotation(interval: u64) -> Rotation {
        Rotation {
            interval,
            since: 1_000,
            images: ["bg-aaaaaaaaaaaa", "bg-bbbbbbbbbbbb"].map(String::from).to_vec(),
            off: vec![],
        }
    }

    #[test]
    fn photos_change_after_each_interval_and_one_photo_stays() {
        let mut r = rotation(30);
        assert_eq!(r.current(1_000).unwrap(), "bg-aaaaaaaaaaaa");
        assert_eq!(r.current(30_999).unwrap(), "bg-aaaaaaaaaaaa");
        assert_eq!(r.current(31_000).unwrap(), "bg-bbbbbbbbbbbb");
        assert_eq!(r.current(61_000).unwrap(), "bg-aaaaaaaaaaaa");
        assert_eq!(r.wait(11_000), Some(Duration::from_millis(20_020)));
        r.off.push("bg-aaaaaaaaaaaa".into());
        assert_eq!(r.current(9_999_999).unwrap(), "bg-bbbbbbbbbbbb");
        assert_eq!(r.wait(11_000), None);
        r.off.push("bg-bbbbbbbbbbbb".into());
        assert_eq!(r.current(5_000), None);
    }

    #[test]
    fn remote_chooses_photos_and_moves_the_slider() {
        let dir = std::env::temp_dir().join(format!("opennava-photos-{}", std::process::id()));
        std::fs::create_dir_all(dir.join("backgrounds")).unwrap();
        for id in &rotation(30).images {
            image::RgbImage::from_pixel(64, 36, image::Rgb([200, 80, 40]))
                .save(dir.join("backgrounds").join(format!("{id}.jpg")))
                .unwrap();
        }
        std::fs::write(dir.join("backgrounds.json"), serde_json::to_vec(&rotation(30)).unwrap()).unwrap();
        let mut ui = Ui::new();
        ui.set_storage(dir.clone());
        assert_eq!(ui.backgrounds.thumbs.len(), 2);
        assert!(ui.assets["background"].get_pixel(5, 5)[0] > 150);
        ui.go(Page::Settings);
        ui.render(W, H);
        ui.key(Key::Down);
        ui.render(W, H);
        assert_eq!((ui.chapter, ui.focus), (1, CHAPTER_ID + 1));
        ui.focus = SLIDER_ID;
        ui.key(Key::Right);
        assert_eq!(ui.backgrounds.rotation.interval, 3_600);
        ui.key(Key::Right);
        ui.key(Key::Right);
        assert_eq!(Rotation::read(&dir).interval, 86_400);
        ui.render(W, H);
        ui.focus = PHOTO_ID;
        ui.key(Key::Left);
        assert_eq!(ui.focus, CHAPTER_ID + 1);
        ui.render(W, H);
        ui.focus = PHOTO_ID;
        ui.key(Key::Select);
        assert_eq!(Rotation::read(&dir).off, vec!["bg-aaaaaaaaaaaa".to_owned()]);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn every_settings_chapter_is_reachable_with_the_remote() {
        for chapter in 0..CHAPTERS.len() {
            let mut ui = Ui::new();
            ui.go(Page::Settings);
            ui.chapter = chapter;
            ui.focus = CHAPTER_ID + chapter;
            ui.render(W, H);
            let panel: Vec<usize> = ui.controls.iter().filter(|c| c.x >= 400. && c.y < 620.).map(|c| c.id).collect();
            let mut seen = std::collections::HashSet::new();
            let mut queue = vec![ui.focus];
            while let Some(id) = queue.pop() {
                for key in [Key::Up, Key::Down, Key::Left, Key::Right] {
                    ui.chapter = chapter;
                    ui.focus = id;
                    ui.render(W, H);
                    ui.focus = id;
                    ui.key(key);
                    if ui.chapter == chapter && seen.insert(ui.focus) {
                        queue.push(ui.focus);
                    }
                }
            }
            for id in panel {
                assert!(seen.contains(&id), "chapter {chapter}: control {id} cannot be reached");
            }
        }
    }
}
