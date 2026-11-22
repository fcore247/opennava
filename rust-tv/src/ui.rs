//! Native, resolution-independent TV interface. No browser or web runtime.
#[path = "home.rs"]
pub(crate) mod home;
#[path = "shelves.rs"]
mod shelves;
use crate::catalog::CatalogItem;
use fontdue::{Font, FontSettings};
use home::HomeLayout;
use image::{RgbaImage, imageops::FilterType};
use serde::{Deserialize, Serialize};
use shelves::{Selection, Shelf};
use std::{collections::HashMap, path::PathBuf};
use tiny_skia::{
    Color, FillRule, Paint, PathBuilder, Pixmap, PixmapPaint, Rect, Stroke, Transform,
};

const W: u32 = 1280;
const H: u32 = 720;
const WHITE: [u8; 4] = [248, 246, 250, 255];
const SOFT: [u8; 4] = [193, 199, 211, 255];
const ACCENT: [u8; 4] = [232, 217, 251, 255];
const ARD: &str = "de.swr.avp.ard.tv";
const ZDF: &str = "com.zdf.android.mediathek";
const ARTE: &str = "tv.arte.plus7";

#[derive(Clone, Copy, Debug)]
pub enum Key {
    Up,
    Down,
    Left,
    Right,
    Select,
    Menu,
    Back,
    Character(char),
    Delete,
}
#[derive(Debug)]
pub enum Action {
    None,
    Launch(Vec<&'static str>),
    LaunchApp(String),
    Personalization,
    ConnectPhone,
    Updates,
    SetFireHome(bool),
    YoutubeCatalog {
        request: u64,
    },
    YoutubeSearch {
        request: u64,
        query: String,
    },
    YoutubeMore {
        request: u64,
        cursor: String,
    },
    MainSearch {
        request: u64,
        provider: usize,
        query: String,
    },
    SearchMore {
        request: u64,
        query: String,
        providers: u8,
        page: u32,
        youtube_cursor: Option<String>,
    },
    YoutubeAccount,
    YoutubePlay {
        id: String,
        title: String,
        channel: String,
    },
    LiveTv,
    Catalog {
        request: u64,
        provider: usize,
        query: String,
    },
    CatalogPlay {
        request: u64,
        item: CatalogItem,
    },
    MediaPlay {
        url: String,
        title: String,
    },
    OpenUrl(String),
    ReturnClassic,
    Exit,
}
#[derive(Clone, Copy, Debug, PartialEq)]
enum Page {
    Home,
    HomeFolder(usize),
    Library,
    Channel(usize),
    Youtube,
    YoutubeSearch,
    Programme,
    TvApps,
    Apps,
    Search,
    Watchlist,
    Settings,
    Detail(usize),
    Exit,
}
#[derive(Clone)]
enum Command {
    Page(Page),
    Launch(Vec<&'static str>),
    LaunchApp(String),
    Personalization,
    ConnectPhone,
    Updates,
    SetFireHome(bool),
    Provider(usize),
    More(isize),
    HomeTurn(isize),
    CloseFolder(usize),
    Pick(usize),
    SavedProgramme(usize),
    PlaySelection,
    YoutubeAccount,
    LiveTv,
    SaveSelection,
    Retry,
    FindTitle(usize),
    Char(char),
    Space,
    Delete,
    Clear,
    Numbers,
    Save(usize),
    Shade,
    ReturnClassic,
    Exit,
}
#[derive(Clone)]
struct Control {
    id: usize,
    x: f32,
    y: f32,
    w: f32,
    h: f32,
    command: Command,
}
#[derive(Default, Serialize, Deserialize)]
struct Preferences {
    saved: Vec<usize>,
    darker: bool,
    #[serde(default)]
    programmes: Vec<Selection>,
}
struct Title {
    name: &'static str,
    provider: usize,
    art: &'static str,
    description: &'static str,
}
const TITLES: [Title; 6] = [
    Title {
        name: "Tagesschau",
        provider: 1,
        art: "ard",
        description: "Nachrichten und Hintergründe aus der ARD.",
    },
    Title {
        name: "Bares für Rares",
        provider: 2,
        art: "zdf",
        description: "Lieblingsstücke und ihre Geschichten.",
    },
    Title {
        name: "Re: Champions der Lüfte",
        provider: 3,
        art: "arte",
        description: "Ein Perspektivwechsel mit ARTE.",
    },
    Title {
        name: "Tatort",
        provider: 1,
        art: "ard",
        description: "Krimis aus der ARD Mediathek.",
    },
    Title {
        name: "heute",
        provider: 2,
        art: "zdf",
        description: "Die Nachrichten im ZDF.",
    },
    Title {
        name: "Stadt Land Kunst",
        provider: 3,
        art: "arte",
        description: "Orte, Menschen und Kultur bei ARTE.",
    },
];

pub struct Ui {
    home: HomeLayout,
    update_available: bool,
    fire_home_enabled: Option<bool>,
    home_offset: usize,
    home_icons: HashMap<String, RgbaImage>,
    shelf: Shelf,
    page: Page,
    focus: usize,
    controls: Vec<Control>,
    query: String,
    youtube_query: String,
    provider: usize,
    numbers: bool,
    prefs: Preferences,
    storage: Option<PathBuf>,
    message: String,
    font: Font,
    handwriting: Font,
    assets: HashMap<&'static str, RgbaImage>,
    pictures: HashMap<(String, u32, u32), Pixmap>,
    glyphs: HashMap<(char, u16, bool), (fontdue::Metrics, Pixmap)>,
}

impl Default for Ui {
    fn default() -> Self {
        Self::new()
    }
}
impl Ui {
    pub fn new() -> Self {
        let mut assets = HashMap::new();
        for (key, bytes) in [
            (
                "background",
                include_bytes!("../assets/alpine-lake.webp").as_slice(),
            ),
            ("ocean", include_bytes!("../assets/ocean.jpg").as_slice()),
            ("forest", include_bytes!("../assets/forest.jpg").as_slice()),
            (
                "mountains",
                include_bytes!("../assets/mountains.jpg").as_slice(),
            ),
            ("ard", include_bytes!("../assets/ard-cover.jpg").as_slice()),
            ("zdf", include_bytes!("../assets/zdf-cover.jpg").as_slice()),
            (
                "arte",
                include_bytes!("../assets/arte-cover.jpg").as_slice(),
            ),
            (
                "ard-logo",
                include_bytes!("../assets/ard-logo.png").as_slice(),
            ),
            (
                "zdf-logo",
                include_bytes!("../assets/zdf-logo.png").as_slice(),
            ),
            (
                "arte-logo",
                include_bytes!("../assets/arte-logo.png").as_slice(),
            ),
        ] {
            assets.insert(
                key,
                image::load_from_memory(bytes)
                    .expect("bundled image")
                    .to_rgba8(),
            );
        }
        Self {
            shelf: Shelf::default(),
            home: HomeLayout::default(),
            update_available: false,
            fire_home_enabled: None,
            home_offset: 0,
            home_icons: HashMap::new(),
            page: Page::Home,
            focus: 0,
            controls: vec![],
            query: String::new(),
            youtube_query: String::new(),
            provider: 0,
            numbers: false,
            prefs: Preferences::default(),
            storage: None,
            message: String::new(),
            font: Font::from_bytes(
                include_bytes!("../assets/Manrope.ttf").as_slice(),
                FontSettings::default(),
            )
            .unwrap(),
            handwriting: Font::from_bytes(
                include_bytes!("../assets/ReenieBeanie.ttf").as_slice(),
                FontSettings::default(),
            )
            .unwrap(),
            assets,
            pictures: HashMap::new(),
            glyphs: HashMap::new(),
        }
    }
    pub fn set_storage(&mut self, path: PathBuf) {
        let file = path.join("rust-preview.json");
        if let Ok(bytes) = std::fs::read(&file) {
            if let Ok(mut prefs) = serde_json::from_slice::<Preferences>(&bytes) {
                prefs.saved.retain(|i| *i < TITLES.len());
                prefs.saved.sort_unstable();
                prefs.saved.dedup();
                self.prefs = prefs;
            }
        }
        self.storage = Some(path);
    }
    fn persist(&mut self) {
        let Some(dir) = &self.storage else {
            return;
        };
        let result = (|| -> std::io::Result<()> {
            std::fs::create_dir_all(dir)?;
            let data = serde_json::to_vec(&self.prefs)?;
            let temp = dir.join("rust-preview.json.tmp");
            std::fs::write(&temp, data)?;
            std::fs::rename(temp, dir.join("rust-preview.json"))
        })();
        if result.is_err() {
            self.notice("Die Änderung konnte nicht gespeichert werden.");
        }
    }
    pub fn set_fire_home_enabled(&mut self, enabled: Option<bool>) {
        self.fire_home_enabled = enabled;
    }

    pub fn set_update_available(&mut self, available: bool) -> bool {
        std::mem::replace(&mut self.update_available, available) != available
    }

    pub fn notice(&mut self, text: &str) {
        self.message = text.chars().take(100).collect();
    }
    #[cfg(target_os = "android")]
    pub(crate) fn open_home(&mut self) {
        self.go(Page::Home);
    }

    fn go(&mut self, page: Page) {
        self.shelf.loading_more = false;
        self.shelf.request = self.shelf.request.wrapping_add(1);
        self.page = page;
        self.home_offset = 0;
        self.focus = match page {
            Page::Home if self.home.has_items() => 1000,
            Page::HomeFolder(_) => 1000,
            Page::Search | Page::YoutubeSearch => 300,
            Page::Settings if self.update_available => 5,
            Page::Watchlist
                if !self.prefs.saved.is_empty() || !self.prefs.programmes.is_empty() =>
            {
                50
            }
            _ => 0,
        };
        self.message.clear();
    }
    fn active_query(&mut self) -> &mut String {
        if self.page == Page::YoutubeSearch {
            &mut self.youtube_query
        } else {
            &mut self.query
        }
    }
    fn execute(&mut self, command: Command) -> Action {
        self.message.clear();
        let updates_search = matches!(
            &command,
            Command::Provider(_)
                | Command::Char(_)
                | Command::Space
                | Command::Delete
                | Command::Clear
        );
        match command {
            Command::Page(page) => {
                self.go(page);
                return self.request_page();
            }
            Command::LaunchApp(package) => return Action::LaunchApp(package),
            Command::Personalization => return Action::Personalization,
            Command::ConnectPhone => return Action::ConnectPhone,
            Command::Updates => return Action::Updates,
            Command::SetFireHome(enabled) => return Action::SetFireHome(enabled),
            Command::CloseFolder(i) => self.close_folder(i),
            Command::HomeTurn(direction) => self.turn_home(direction),
            Command::More(direction) => {
                self.turn_page(direction);
            }
            Command::Pick(i) => return self.open_result(i),
            Command::LiveTv => return Action::LiveTv,
            Command::PlaySelection => return self.play_selection(),
            Command::YoutubeAccount => return Action::YoutubeAccount,
            Command::SavedProgramme(i) => {
                if let Some(item) = self.prefs.programmes.get(i).cloned() {
                    return self.select_programme(item);
                }
            }
            Command::SaveSelection => self.save_selection(),
            Command::Retry => return self.request_page(),
            Command::FindTitle(i) => {
                let title = &TITLES[i];
                self.provider = title.provider;
                self.query = title.name.to_owned();
                self.go(Page::Search);
                return self.request_page();
            }
            Command::Launch(packages) => return Action::Launch(packages),
            Command::Provider(p) => self.provider = p,
            Command::Char(c) => {
                let query = self.active_query();
                if query.chars().count() < 48 {
                    query.push(c);
                }
            }
            Command::Space => {
                let query = self.active_query();
                if query.chars().count() < 48 {
                    query.push(' ');
                }
            }
            Command::Delete => {
                self.active_query().pop();
            }
            Command::Clear => self.active_query().clear(),
            Command::Numbers => self.numbers = !self.numbers,
            Command::Save(i) => {
                if self.prefs.saved.contains(&i) {
                    self.prefs.saved.retain(|n| *n != i);
                } else {
                    self.prefs.saved.push(i);
                }
                self.persist();
            }
            Command::Shade => {
                self.prefs.darker = !self.prefs.darker;
                self.persist();
            }
            Command::ReturnClassic => return Action::ReturnClassic,
            Command::Exit => return Action::Exit,
        }
        if updates_search && matches!(self.page, Page::Search | Page::YoutubeSearch) {
            return self.request_page();
        }
        Action::None
    }
    pub fn key(&mut self, key: Key) -> Action {
        if self.home_key(key) {
            return Action::None;
        }
        if let Some(index) = self.focused_result_index() {
            match key {
                Key::Left | Key::Right => {
                    return self.move_result(index, key);
                }
                Key::Select => return self.open_result(index),
                _ => {}
            }
        }
        match key {
            Key::Menu => self.toggle_focused_video(),
            Key::Back => {
                if let Page::HomeFolder(i) = self.page {
                    self.close_folder(i);
                    return Action::None;
                }
                if self.page == Page::Programme {
                    self.return_to_results();
                    return Action::None;
                }
                if self.page == Page::YoutubeSearch {
                    return self.execute(Command::Page(Page::Youtube));
                }
                self.go(match self.page {
                    Page::Home => Page::Exit,
                    Page::TvApps => Page::Apps,
                    Page::Detail(_) => Page::Watchlist,
                    Page::Channel(_) => Page::Library,
                    _ => Page::Home,
                });
                return Action::None;
            }
            Key::Character(c)
                if matches!(self.page, Page::Search | Page::YoutubeSearch) && !c.is_control() =>
            {
                return self.execute(Command::Char(c));
            }
            Key::Delete if matches!(self.page, Page::Search | Page::YoutubeSearch) => {
                return self.execute(Command::Delete);
            }
            Key::Select => {
                if let Some(c) = self.controls.iter().find(|c| c.id == self.focus) {
                    return self.execute(c.command.clone());
                }
            }
            Key::Down
                if self.page == Page::Youtube
                    && self.focus <= 1
                    && self.controls.iter().any(|control| control.id == 80) =>
            {
                // The centred retry button is the only row between the toolbar and bottom nav.
                self.focus = 80;
            }
            Key::Up | Key::Down | Key::Left | Key::Right => {
                let Some(current) = self.controls.iter().find(|c| c.id == self.focus) else {
                    return Action::None;
                };
                let (cx, cy) = (current.x + current.w / 2., current.y + current.h / 2.);
                let (dx, dy) = match key {
                    Key::Up => (0., -1.),
                    Key::Down => (0., 1.),
                    Key::Left => (-1., 0.),
                    _ => (1., 0.),
                };
                let target = self
                    .controls
                    .iter()
                    .filter(|c| c.id != current.id)
                    .filter_map(|c| {
                        let x = c.x + c.w / 2. - cx;
                        let y = c.y + c.h / 2. - cy;
                        let forward = x * dx + y * dy;
                        let side = (x * dy - y * dx).abs();
                        let gap = if dx == 0. {
                            (cx - c.x - c.w).max(c.x - cx).max(0.)
                        } else {
                            (cy - c.y - c.h).max(c.y - cy).max(0.)
                        };
                        (forward > 3.).then_some((c.id, forward + gap * 6. + side * 0.3))
                    })
                    .min_by(|a, b| a.1.total_cmp(&b.1));
                if let Some((id, _)) = target {
                    self.focus = id;
                }
            }
            _ => {}
        }
        Action::None
    }
    pub fn tap(&mut self, x: f32, y: f32, width: u32, height: u32) -> Action {
        self.activate_at(x, y, width, height, false)
    }
    pub fn activate_at(&mut self, x: f32, y: f32, width: u32, height: u32, held: bool) -> Action {
        if width == 0 || height == 0 {
            return Action::None;
        }
        let (x, y) = (x * W as f32 / width as f32, y * H as f32 / height as f32);
        if let Some(c) = self
            .controls
            .iter()
            .rev()
            .find(|c| x >= c.x && x <= c.x + c.w && y >= c.y && y <= c.y + c.h)
            .cloned()
        {
            self.focus = c.id;
            if held && self.focused_video_id().is_some() {
                self.key(Key::Menu)
            } else {
                self.execute(c.command)
            }
        } else {
            Action::None
        }
    }
    fn control(&mut self, id: usize, rect: [f32; 4], command: Command) {
        self.controls.push(Control {
            id,
            x: rect[0],
            y: rect[1],
            w: rect[2],
            h: rect[3],
            command,
        });
    }
    fn text(
        &mut self,
        canvas: &mut Pixmap,
        text: &str,
        x: f32,
        baseline: f32,
        size: u16,
        color: [u8; 4],
        hand: bool,
    ) {
        let font = if hand { &self.handwriting } else { &self.font };
        let mut cursor = x;
        for c in text.chars() {
            let (metrics, _) = self.glyphs.entry((c, size, hand)).or_insert_with(|| {
                let (m, bitmap) = font.rasterize(c, size as f32);
                let mut pix = Pixmap::new(m.width.max(1) as u32, m.height.max(1) as u32).unwrap();
                for (p, a) in pix.data_mut().chunks_exact_mut(4).zip(bitmap) {
                    p.copy_from_slice(&[a, a, a, a]);
                }
                (m, pix)
            });
            let m = *metrics;
            if m.width > 0 && m.height > 0 {
                let (_, source) = &self.glyphs[&(c, size, hand)];
                let mut tinted = source.clone();
                for p in tinted.data_mut().chunks_exact_mut(4) {
                    let a = p[3] as u16 * color[3] as u16 / 255;
                    for i in 0..3 {
                        p[i] = (color[i] as u16 * a / 255) as u8;
                    }
                    p[3] = a as u8;
                }
                canvas.draw_pixmap(
                    cursor.round() as i32 + m.xmin,
                    baseline.round() as i32 - m.height as i32 - m.ymin,
                    tinted.as_ref(),
                    &PixmapPaint::default(),
                    Transform::identity(),
                    None,
                );
            }
            cursor += m.advance_width;
        }
    }
    fn centered(
        &mut self,
        canvas: &mut Pixmap,
        text: &str,
        rect: [f32; 4],
        size: u16,
        color: [u8; 4],
    ) {
        let width: f32 = text
            .chars()
            .map(|c| self.font.metrics(c, size as f32).advance_width)
            .sum();
        self.text(
            canvas,
            text,
            rect[0] + (rect[2] - width) / 2.,
            rect[1] + rect[3] / 2. + size as f32 * 0.35,
            size,
            color,
            false,
        );
    }
    fn picture(&mut self, canvas: &mut Pixmap, name: &str, rect: [f32; 4], radius: f32) {
        let (w, h) = (rect[2] as u32, rect[3] as u32);
        let key = (name.to_owned(), w, h);
        let pic = self.pictures.entry(key).or_insert_with(|| {
            let source = self
                .assets
                .get(name)
                .or_else(|| self.shelf.images.get(name))
                .or_else(|| self.home_icons.get(name))
                .expect("registered picture");
            let image = if name.ends_with("logo") {
                image::imageops::resize(source, w, h, FilterType::Lanczos3)
            } else {
                image::DynamicImage::ImageRgba8(source.clone())
                    .resize_to_fill(w, h, FilterType::Triangle)
                    .to_rgba8()
            };
            let mut pix = Pixmap::new(w, h).unwrap();
            for (out, input) in pix
                .data_mut()
                .chunks_exact_mut(4)
                .zip(image.as_raw().chunks_exact(4))
            {
                for i in 0..3 {
                    out[i] = (input[i] as u16 * input[3] as u16 / 255) as u8;
                }
                out[3] = input[3];
            }
            pix
        });
        let mut mask = tiny_skia::Mask::new(W, H).unwrap();
        mask.fill_path(
            &rounded(rect, radius),
            FillRule::Winding,
            true,
            Transform::identity(),
        );
        canvas.draw_pixmap(
            rect[0] as i32,
            rect[1] as i32,
            pic.as_ref(),
            &PixmapPaint::default(),
            Transform::identity(),
            Some(&mask),
        );
    }
    fn button(
        &mut self,
        c: &mut Pixmap,
        id: usize,
        label: &str,
        rect: [f32; 4],
        command: Command,
        active: bool,
    ) {
        self.control(id, rect, command);
        let focused = self.focus == id;
        fill(
            c,
            rect,
            rect[3].min(24.) / 2.,
            if active { ACCENT } else { [25, 36, 48, 210] },
        );
        stroke(
            c,
            rect,
            rect[3].min(24.) / 2.,
            if focused { ACCENT } else { [235, 230, 245, 45] },
            if focused { 3. } else { 1. },
        );
        self.centered(
            c,
            label,
            rect,
            18,
            if active { [28, 25, 35, 255] } else { WHITE },
        );
    }
    fn tile(
        &mut self,
        c: &mut Pixmap,
        id: usize,
        label: &str,
        art: &str,
        rect: [f32; 4],
        command: Command,
        logo: Option<&str>,
    ) {
        self.control(id, rect, command);
        self.picture(c, art, rect, 22.);
        let mut mask = tiny_skia::Mask::new(W, H).unwrap();
        mask.fill_path(
            &rounded(rect, 22.),
            FillRule::Winding,
            false,
            Transform::identity(),
        );
        for row in 0..rect[3] as i32 {
            let alpha = (30. + 170. * (row as f32 / rect[3]).powf(1.5)) as u8;
            let r = [rect[0], rect[1] + row as f32, rect[2], 1.];
            let mut paint = Paint::default();
            paint.set_color_rgba8(4, 13, 24, alpha);
            c.fill_rect(
                Rect::from_xywh(r[0], r[1], r[2], r[3]).unwrap(),
                &paint,
                Transform::identity(),
                Some(&mask),
            );
        }
        if let Some(logo) = logo {
            self.picture(c, logo, [rect[0] + 24., rect[1] + 22., 108., 54.], 0.);
        } else if art == "forest" {
            fill(
                c,
                [rect[0] + 24., rect[1] + 23., 203., 54.],
                12.,
                [5, 24, 21, 165],
            );
            for (i, name) in ["ard-logo", "zdf-logo", "arte-logo"].iter().enumerate() {
                self.picture(
                    c,
                    name,
                    [rect[0] + 35. + i as f32 * 63., rect[1] + 34., 58., 29.],
                    0.,
                );
            }
        } else if label == "YouTube" {
            fill(
                c,
                [rect[0] + 25., rect[1] + 28., 58., 40.],
                11.,
                [248, 32, 52, 255],
            );
            play(c, rect[0] + 43., rect[1] + 36., 23., WHITE);
        } else if label == "Netflix" {
            self.text(
                c,
                "N",
                rect[0] + 25.,
                rect[1] + 77.,
                54,
                [235, 18, 40, 255],
                false,
            );
        } else {
            icon(c, art, rect[0] + 30., rect[1] + 30., 35., WHITE);
        }
        self.text(
            c,
            label,
            rect[0] + 24.,
            rect[1] + rect[3] - 29.,
            28,
            WHITE,
            false,
        );
        self.text(
            c,
            "→",
            rect[0] + rect[2] - 48.,
            rect[1] + rect[3] - 29.,
            25,
            WHITE,
            false,
        );
        stroke(
            c,
            rect,
            22.,
            if self.focus == id {
                ACCENT
            } else {
                [255, 255, 255, 55]
            },
            if self.focus == id { 3.5 } else { 1. },
        );
    }
    pub fn render(&mut self, width: u32, height: u32) -> Vec<u8> {
        let mut c = Pixmap::new(W, H).unwrap();
        self.controls.clear();
        self.picture(&mut c, "background", [0., 0., 1280., 720.], 0.);
        fill(
            &mut c,
            [0., 0., 1280., 720.],
            0.,
            [5, 14, 25, if self.prefs.darker { 214 } else { 181 }],
        );
        self.text(&mut c, "Opennava", 60., 78., 70, WHITE, true);
        self.text(&mut c, "RUST / PROTOTYP", 1017., 54., 15, SOFT, false);
        let (title, hint) = match self.page {
            Page::Home | Page::HomeFolder(_) => ("", ""),
            Page::Library => ("Mediatheken", "Sender auswählen"),
            Page::Channel(p) => (provider_name(p), "Aktuelle Sendungen"),
            Page::Youtube => ("YouTube", ""),
            Page::YoutubeSearch => ("YouTube durchsuchen", ""),
            Page::Programme => ("", ""),
            Page::TvApps => ("TV-Apps", "Eine installierte App öffnen"),
            Page::Apps => ("Apps", "Bereits auf deinem Fernseher"),
            Page::Search => ("Suche", "Mediatheken und YouTube"),
            Page::Watchlist => ("Merkliste", "Auf diesem Gerät gespeichert"),
            Page::Settings => ("Einstellungen", "Für die Rust-Vorschau"),
            Page::Detail(_) => ("", ""),
            Page::Exit => (
                "Vorschau verlassen",
                "Öffne OpenNava oder kehre zu deinen Apps zurück.",
            ),
        };
        self.text(&mut c, title, 64., 124., 29, WHITE, false);
        self.text(&mut c, hint, 64., 153., 15, SOFT, false);
        match self.page {
            Page::HomeFolder(_) => self.draw_home_apps(&mut c),
            Page::Home => {
                let custom = self.home.has_items();
                if custom {
                    self.draw_home_apps(&mut c);
                } else {
                    for (i, (label, art, command)) in [
                        ("Live-TV", "ocean", Command::LiveTv),
                        ("Mediatheken", "forest", Command::Page(Page::Library)),
                        ("YouTube", "mountains", Command::Page(Page::Youtube)),
                    ]
                    .into_iter()
                    .enumerate()
                    {
                        self.tile(
                            &mut c,
                            i,
                            label,
                            art,
                            if self.home.is_grid() {
                                [64. + i as f32 * 292., 163., 276., 184.]
                            } else {
                                [64. + i as f32 * 388., 148., 376., 300.]
                            },
                            command,
                            None,
                        );
                    }
                }
                let search_y = if custom { 512. } else { 491. };
                self.button(
                    &mut c,
                    3,
                    "Was möchtest du ansehen?",
                    [250., search_y, 780., 66.],
                    Command::Page(Page::Search),
                    false,
                );
                icon(&mut c, "search", 278., search_y + 20., 24., SOFT);
            }
            Page::Library | Page::Apps => {
                let apps = self.page == Page::Apps;
                let gap = if apps { 292. } else { 388. };
                let width = if apps { 280. } else { 376. };
                for (i, (name, art, logo, _pkg)) in [
                    ("ARD Mediathek", "ard", "ard-logo", ARD),
                    ("ZDF Mediathek", "zdf", "zdf-logo", ZDF),
                    ("ARTE", "arte", "arte-logo", ARTE),
                ]
                .into_iter()
                .enumerate()
                {
                    self.tile(
                        &mut c,
                        i,
                        name,
                        art,
                        [64. + i as f32 * gap, 195., width, 335.],
                        Command::Page(Page::Channel(i + 1)),
                        Some(logo),
                    );
                }
                if apps {
                    self.tile(
                        &mut c,
                        3,
                        "Netflix",
                        "mountains",
                        [940., 195., 276., 335.],
                        Command::Launch(vec!["com.netflix.ninja"]),
                        None,
                    );
                    self.button(
                        &mut c,
                        4,
                        "Weitere TV-Apps",
                        [460., 551., 360., 50.],
                        Command::Page(Page::TvApps),
                        false,
                    );
                }
            }
            Page::TvApps => {
                for (i, (name, art, pkgs)) in [
                    ("ARD", "ard", vec![ARD]),
                    ("ZDF", "zdf", vec![ZDF]),
                    (
                        "Live-TV Apps",
                        "ocean",
                        vec!["de.exaring.waipu", "com.zattoo.player"],
                    ),
                ]
                .into_iter()
                .enumerate()
                {
                    self.tile(
                        &mut c,
                        i,
                        name,
                        art,
                        [64. + i as f32 * 388., 195., 376., 335.],
                        Command::Launch(pkgs),
                        None,
                    );
                }
            }
            Page::Channel(_) => self.draw_shelf(&mut c, false),
            Page::Youtube => {
                self.button(
                    &mut c,
                    0,
                    "Videos suchen",
                    [745., 93., 219., 53.],
                    Command::Page(Page::YoutubeSearch),
                    false,
                );
                self.button(
                    &mut c,
                    1,
                    "YouTube-Konto",
                    [981., 93., 235., 53.],
                    Command::YoutubeAccount,
                    false,
                );
                self.draw_shelf(&mut c, false);
            }
            Page::Programme => self.draw_programme(&mut c),
            Page::Search | Page::YoutubeSearch => {
                let is_youtube = self.page == Page::YoutubeSearch;
                self.draw_shelf(&mut c, true);
                if !is_youtube {
                    for p in 0..=YOUTUBE_PROVIDER {
                        self.button(
                            &mut c,
                            200 + p,
                            provider_name(p),
                            [336. + p as f32 * 124., 331., 112., 35.],
                            Command::Provider(p),
                            self.provider == p,
                        );
                    }
                }
                fill(&mut c, [250., 380., 780., 48.], 13., [13, 25, 41, 230]);
                let input = if is_youtube {
                    &self.youtube_query
                } else {
                    &self.query
                };
                let empty = input.is_empty();
                let query = if empty {
                    if is_youtube {
                        "Auf YouTube suchen …"
                    } else {
                        "Titel eingeben …"
                    }
                    .to_owned()
                } else {
                    self.ellipsis(input, 22, 735.)
                };
                self.text(
                    &mut c,
                    &query,
                    276.,
                    412.,
                    22,
                    if empty { SOFT } else { WHITE },
                    false,
                );
                let rows = if self.numbers {
                    vec!["1234567890", "!?.,:;+-/@", "ÄÖÜäöüß&()"]
                } else {
                    vec!["QWERTZUIOP", "ASDFGHJKL", "YXCVBNM"]
                };
                let mut id = 300;
                for (row, chars) in rows.iter().enumerate() {
                    let x0 = 640. - chars.chars().count() as f32 * 66. / 2.;
                    for (col, ch) in chars.chars().enumerate() {
                        self.button(
                            &mut c,
                            id,
                            &ch.to_string(),
                            [x0 + col as f32 * 66., 442. + row as f32 * 45., 60., 38.],
                            Command::Char(ch),
                            false,
                        );
                        id += 1;
                    }
                }
                for (i, (label, command)) in [
                    (
                        if self.numbers { "ABC" } else { "123 / ä" },
                        Command::Numbers,
                    ),
                    ("Leerzeichen", Command::Space),
                    ("Löschen", Command::Delete),
                    ("Leeren", Command::Clear),
                ]
                .into_iter()
                .enumerate()
                {
                    self.button(
                        &mut c,
                        400 + i,
                        label,
                        [314. + i as f32 * 166., 581., 154., 35.],
                        command,
                        false,
                    );
                }
            }
            Page::Watchlist => {
                self.draw_watchlist(&mut c);
            }
            Page::Detail(i) => {
                self.picture(&mut c, TITLES[i].art, [64., 155., 460., 382.], 24.);
                self.text(
                    &mut c,
                    provider_name(TITLES[i].provider),
                    578.,
                    205.,
                    19,
                    ACCENT,
                    false,
                );
                self.text(&mut c, TITLES[i].name, 578., 256., 34, WHITE, false);
                self.text(&mut c, TITLES[i].description, 578., 303., 19, SOFT, false);
                self.text(
                    &mut c,
                    "Gespeicherter Titel aus der ersten Vorschau.",
                    578.,
                    347.,
                    17,
                    SOFT,
                    false,
                );
                self.button(
                    &mut c,
                    0,
                    "Aktuelle Folgen suchen",
                    [578., 396., 420., 60.],
                    Command::FindTitle(i),
                    false,
                );
                self.button(
                    &mut c,
                    1,
                    if self.prefs.saved.contains(&i) {
                        "Aus Merkliste entfernen"
                    } else {
                        "Für später vormerken"
                    },
                    [578., 475., 420., 60.],
                    Command::Save(i),
                    self.prefs.saved.contains(&i),
                );
            }
            Page::Settings => {
                let spacing = if self.fire_home_enabled.is_some() {
                    64.
                } else {
                    70.
                };
                let row = |index: f32| [64., 170. + index * spacing, 430., 58.];
                self.button(
                    &mut c,
                    4,
                    "Personalisierung",
                    row(0.),
                    Command::Personalization,
                    false,
                );
                self.button(
                    &mut c,
                    0,
                    if self.prefs.darker {
                        "Hintergrund: sehr dunkel"
                    } else {
                        "Hintergrund: dunkel"
                    },
                    row(1.),
                    Command::Shade,
                    false,
                );
                self.button(
                    &mut c,
                    3,
                    "YouTube-Konto verwalten",
                    row(2.),
                    Command::YoutubeAccount,
                    false,
                );
                self.button(
                    &mut c,
                    1,
                    "Zur klassischen Ansicht",
                    row(3.),
                    Command::ReturnClassic,
                    false,
                );
                self.button(
                    &mut c,
                    2,
                    "Vorschau schließen",
                    row(4.),
                    Command::Page(Page::Exit),
                    false,
                );
                self.button(
                    &mut c,
                    5,
                    if self.update_available {
                        "Update verfügbar"
                    } else {
                        "Nach Updates suchen"
                    },
                    row(5.),
                    Command::Updates,
                    false,
                );
                if let Some(enabled) = self.fire_home_enabled {
                    self.button(
                        &mut c,
                        6,
                        if enabled {
                            "Home-Taste: OpenNava"
                        } else {
                            "Home-Taste: Amazon"
                        },
                        row(6.),
                        Command::SetFireHome(!enabled),
                        false,
                    );
                }
                self.button(
                    &mut c,
                    7,
                    "Handy verbinden",
                    [585., 170., 430., 58.],
                    Command::ConnectPhone,
                    false,
                );
                self.text(
                    &mut c,
                    "Ein eigener Platz zum Ausprobieren.",
                    585.,
                    252.,
                    29,
                    WHITE,
                    true,
                );
                for (row, text) in [
                    "Die bisherigen Apps und ihre Daten bleiben erhalten.",
                    if self.fire_home_enabled.is_some() {
                        "Unter „Home-Taste“ wechselst du die Startseite."
                    } else {
                        "Die Home-Taste funktioniert weiter wie bisher."
                    },
                    "Diese Merkliste bleibt lokal in der Rust-App.",
                    "Luna-Synchronisierung ist noch nicht angebunden.",
                ]
                .iter()
                .enumerate()
                {
                    self.text(&mut c, text, 585., 307. + row as f32 * 39., 19, SOFT, false);
                }
            }
            Page::Exit => {
                self.text(&mut c, "Wohin möchtest du?", 365., 310., 62, WHITE, true);
                self.button(
                    &mut c,
                    0,
                    "Weiter ausprobieren",
                    [108., 393., 330., 64.],
                    Command::Page(Page::Home),
                    false,
                );
                self.button(
                    &mut c,
                    1,
                    "Zur klassischen Ansicht",
                    [475., 393., 330., 64.],
                    Command::ReturnClassic,
                    false,
                );
                self.button(
                    &mut c,
                    2,
                    "Vorschau schließen",
                    [842., 393., 330., 64.],
                    Command::Exit,
                    false,
                );
            }
        }
        let items = [
            ("Start", Page::Home),
            ("Merkliste", Page::Watchlist),
            ("Apps", Page::Apps),
            ("Einstellungen", Page::Settings),
        ];
        fill(&mut c, [265., 637., 750., 59.], 29., [13, 22, 35, 235]);
        stroke(
            &mut c,
            [265., 637., 750., 59.],
            29.,
            [255, 255, 255, 40],
            1.,
        );
        for (i, (label, page)) in items.iter().enumerate() {
            let rect = [273. + i as f32 * 184., 644., 179., 45.];
            self.control(900 + i, rect, Command::Page(*page));
            if self.page == *page {
                fill(&mut c, rect, 22., [229, 217, 245, 240]);
            }
            if self.focus == 900 + i {
                stroke(&mut c, rect, 22., ACCENT, 3.);
            }
            self.centered(
                &mut c,
                label,
                rect,
                18,
                if self.page == *page {
                    [25, 26, 33, 255]
                } else {
                    SOFT
                },
            );
            if *page == Page::Settings && self.update_available {
                let dot = [rect[0] + rect[2] - 17., rect[1] + 11., 10., 10.];
                fill(&mut c, dot, 5., [112, 219, 144, 255]);
                stroke(&mut c, dot, 5., [32, 72, 46, 255], 1.);
            }
        }
        if let Some(hint) = self.video_controls_hint() {
            self.text(&mut c, hint, 64., 711., 12, SOFT, false);
        }
        if !self.message.is_empty() {
            fill(&mut c, [150., 88., 980., 52.], 14., [28, 26, 39, 255]);
            self.centered(
                &mut c,
                &self.message.clone(),
                [160., 88., 960., 52.],
                18,
                WHITE,
            );
        }
        if !self.controls.iter().any(|c| c.id == self.focus) {
            if let Some(first) = self.controls.first() {
                self.focus = first.id;
            }
        }
        if width == W && height == H {
            c.take()
        } else {
            image::imageops::resize(
                &RgbaImage::from_raw(W, H, c.take()).unwrap(),
                width.max(1),
                height.max(1),
                FilterType::Triangle,
            )
            .into_raw()
        }
    }
}

const YOUTUBE_PROVIDER: usize = 4;

fn provider_name(p: usize) -> &'static str {
    ["Alle", "ARD", "ZDF", "ARTE", "YouTube"]
        .get(p)
        .copied()
        .unwrap_or("")
}

fn rounded(r: [f32; 4], radius: f32) -> tiny_skia::Path {
    let [x, y, w, h] = r;
    let a = radius.min(w / 2.).min(h / 2.);
    let k = 0.5522848 * a;
    let mut p = PathBuilder::new();
    p.move_to(x + a, y);
    p.line_to(x + w - a, y);
    p.cubic_to(x + w - a + k, y, x + w, y + a - k, x + w, y + a);
    p.line_to(x + w, y + h - a);
    p.cubic_to(x + w, y + h - a + k, x + w - a + k, y + h, x + w - a, y + h);
    p.line_to(x + a, y + h);
    p.cubic_to(x + a - k, y + h, x, y + h - a + k, x, y + h - a);
    p.line_to(x, y + a);
    p.cubic_to(x, y + a - k, x + a - k, y, x + a, y);
    p.close();
    p.finish().unwrap()
}
fn fill(c: &mut Pixmap, r: [f32; 4], radius: f32, color: [u8; 4]) {
    let mut p = Paint::default();
    p.set_color(Color::from_rgba8(color[0], color[1], color[2], color[3]));
    c.fill_path(
        &rounded(r, radius),
        &p,
        FillRule::Winding,
        Transform::identity(),
        None,
    );
}
fn stroke(c: &mut Pixmap, r: [f32; 4], radius: f32, color: [u8; 4], width: f32) {
    let mut p = Paint::default();
    p.set_color_rgba8(color[0], color[1], color[2], color[3]);
    c.stroke_path(
        &rounded(r, radius),
        &p,
        &Stroke {
            width,
            ..Stroke::default()
        },
        Transform::identity(),
        None,
    );
}
fn icon(c: &mut Pixmap, name: &str, x: f32, y: f32, size: f32, color: [u8; 4]) {
    if name == "search" {
        let mut p = Paint::default();
        p.set_color_rgba8(color[0], color[1], color[2], color[3]);
        let circle =
            PathBuilder::from_circle(x + size * 0.38, y + size * 0.38, size * 0.32).unwrap();
        c.stroke_path(
            &circle,
            &p,
            &Stroke {
                width: 2.,
                ..Stroke::default()
            },
            Transform::identity(),
            None,
        );
        let mut path = PathBuilder::new();
        path.move_to(x + size * 0.65, y + size * 0.65);
        path.line_to(x + size, y + size);
        c.stroke_path(
            &path.finish().unwrap(),
            &p,
            &Stroke {
                width: 2.,
                ..Stroke::default()
            },
            Transform::identity(),
            None,
        );
    } else {
        stroke(c, [x, y, size * 1.4, size], 7., color, 2.2);
        play(c, x + size * 0.55, y + size * 0.27, size * 0.46, color);
        fill(
            c,
            [x + size * 0.45, y + size + 7., size * 0.5, 2.],
            1.,
            color,
        );
    }
}
fn play(c: &mut Pixmap, x: f32, y: f32, size: f32, color: [u8; 4]) {
    let mut p = PathBuilder::new();
    p.move_to(x, y);
    p.line_to(x + size * 0.85, y + size * 0.5);
    p.line_to(x, y + size);
    p.close();
    let mut paint = Paint::default();
    paint.set_color_rgba8(color[0], color[1], color[2], color[3]);
    c.fill_path(
        &p.finish().unwrap(),
        &paint,
        FillRule::Winding,
        Transform::identity(),
        None,
    );
}

#[cfg(test)]
mod tests {
    use super::*;
    fn draw(ui: &mut Ui) {
        ui.render(W, H);
    }
    #[test]
    fn search_and_watchlist_persist_in_own_directory() {
        let dir = std::env::temp_dir().join(format!("opennava-rust-test-{}", std::process::id()));
        let mut ui = Ui::new();
        ui.set_storage(dir.clone());
        ui.go(Page::Search);
        for c in "bares".chars() {
            ui.key(Key::Character(c));
        }
        assert_eq!(ui.query, "bares");
        ui.execute(Command::Save(1));
        let mut restored = Ui::new();
        restored.set_storage(dir.clone());
        assert_eq!(restored.prefs.saved, vec![1]);
        restored.execute(Command::Save(1));
        assert!(restored.prefs.saved.is_empty());
        std::fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn dpad_can_reach_search_and_keyboard_without_native_ime() {
        let mut ui = Ui::new();
        draw(&mut ui);
        ui.key(Key::Down);
        assert_eq!(ui.focus, 3);
        ui.key(Key::Select);
        draw(&mut ui);
        assert_eq!(ui.page, Page::Search);
        ui.key(Key::Select);
        assert_eq!(ui.query, "Q");
        ui.key(Key::Right);
        ui.key(Key::Select);
        assert_eq!(ui.query, "QW");
        ui.key(Key::Back);
        assert_eq!(ui.page, Page::Home);
        ui.key(Key::Back);
        assert_eq!(ui.page, Page::Exit);
    }
    #[test]
    fn return_to_classic_is_available_from_settings_and_exit() {
        let mut ui = Ui::new();
        for page in [Page::Settings, Page::Exit] {
            ui.go(page);
            draw(&mut ui);
            ui.focus = 1;
            assert!(matches!(ui.key(Key::Select), Action::ReturnClassic));
        }
        ui.focus = 2;
        assert!(matches!(ui.key(Key::Select), Action::Exit));
    }
    #[test]
    fn remote_opens_phone_pairing_from_settings() {
        let mut ui = Ui::new();
        ui.go(Page::Settings);
        draw(&mut ui);
        ui.key(Key::Up);
        assert!(matches!(ui.key(Key::Select), Action::Personalization));
        ui.key(Key::Right);
        assert!(matches!(ui.key(Key::Select), Action::ConnectPhone));
    }

    #[test]
    fn fire_home_toggle_only_appears_for_configured_service() {
        let mut ui = Ui::new();
        ui.go(Page::Settings);
        draw(&mut ui);
        assert!(ui.controls.iter().all(|control| control.id != 6));
        for enabled in [true, false] {
            ui.set_fire_home_enabled(Some(enabled));
            draw(&mut ui);
            ui.focus = 5;
            ui.key(Key::Down);
            assert_eq!(ui.focus, 6);
            assert!(matches!(ui.key(Key::Select), Action::SetFireHome(next) if next != enabled));
        }
    }

    #[test]
    fn updates_are_always_reachable_and_a_new_update_focuses_the_entry() {
        let mut ui = Ui::new();
        for available in [false, true, false] {
            ui.set_update_available(available);
            assert!(!ui.set_update_available(available));
            ui.go(Page::Settings);
            draw(&mut ui);
            assert_eq!(ui.focus, if available { 5 } else { 0 });
            ui.focus = 5;
            assert!(matches!(ui.key(Key::Select), Action::Updates));
        }
    }

    #[test]
    fn home_live_tv_opens_the_embedded_player() {
        let mut ui = Ui::new();
        draw(&mut ui);
        assert!(matches!(ui.key(Key::Select), Action::LiveTv));
        assert_eq!(ui.page, Page::Home);
        assert!(matches!(ui.tap(200., 250., W, H), Action::LiveTv));
    }
    #[test]
    fn home_youtube_opens_native_catalog() {
        let mut ui = Ui::new();
        draw(&mut ui);
        ui.key(Key::Right);
        ui.key(Key::Right);
        assert!(matches!(ui.key(Key::Select), Action::YoutubeCatalog { .. }));
        assert_eq!(ui.page, Page::Youtube);
    }
    #[test]
    fn every_page_fits_tv_and_all_controls_are_focusable() {
        let mut ui = Ui::new();
        for page in [
            Page::Home,
            Page::Library,
            Page::Apps,
            Page::TvApps,
            Page::Youtube,
            Page::YoutubeSearch,
            Page::Search,
            Page::Watchlist,
            Page::Detail(1),
            Page::Settings,
            Page::Exit,
        ] {
            ui.go(page);
            let pixels = ui.render(W, H);
            assert_eq!(pixels.len(), (W * H * 4) as usize);
            for c in &ui.controls {
                assert!(c.x >= 0. && c.y >= 0. && c.x + c.w <= W as f32 && c.y + c.h <= H as f32);
            }
            let ids: std::collections::HashSet<_> = ui.controls.iter().map(|c| c.id).collect();
            assert_eq!(
                ids.len(),
                ui.controls.len(),
                "Duplicate control IDs on {page:?}"
            );
            let mut seen = std::collections::HashSet::new();
            let mut queue = vec![ui.focus];
            while let Some(id) = queue.pop() {
                if !seen.insert(id) {
                    continue;
                }
                for key in [Key::Up, Key::Down, Key::Left, Key::Right] {
                    ui.focus = id;
                    ui.key(key);
                    if !seen.contains(&ui.focus) {
                        queue.push(ui.focus);
                    }
                }
            }
            assert_eq!(seen, ids, "An unreachable D-pad control on {page:?}");
        }
    }
}
