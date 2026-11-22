use super::*;
use crate::youtube::YoutubeVideo;
use std::{collections::VecDeque, io::Cursor};

#[derive(Clone, Debug, Serialize, Deserialize)]
pub(super) enum Selection {
    Broadcast(CatalogItem),
    Video {
        id: String,
        title: String,
        channel: String,
        thumbnail: String,
    },
}
impl Selection {
    fn title(&self) -> &str {
        match self {
            Self::Broadcast(x) => &x.title,
            Self::Video { title, .. } => title,
        }
    }
    fn thumbnail(&self) -> &str {
        match self {
            Self::Broadcast(x) => &x.thumbnail,
            Self::Video { thumbnail, .. } => thumbnail,
        }
    }
    fn service(&self) -> &str {
        match self {
            Self::Broadcast(x) => provider_name(x.provider),
            Self::Video { channel, .. } => channel,
        }
    }
    fn description(&self) -> &str {
        match self {
            Self::Broadcast(x) => &x.subtitle,
            Self::Video { .. } => "YouTube",
        }
    }
    fn same(&self, other: &Self) -> bool {
        match (self, other) {
            (Self::Broadcast(x), Self::Broadcast(y)) => x.provider == y.provider && x.id == y.id,
            (Self::Video { id: x, .. }, Self::Video { id: y, .. }) => x == y,
            _ => false,
        }
    }
}
#[derive(Default)]
pub(super) struct Shelf {
    pub request: u64,
    items: Vec<Selection>,
    offset: usize,
    loading: bool,
    error: Option<String>,
    selected: Option<Selection>,
    origin: Option<(Page, usize)>,
    watch_offset: usize,
    pub(super) images: HashMap<String, RgbaImage>,
    image_order: VecDeque<String>,
}
impl Ui {
    pub fn youtube_metadata(&self, id: &str) -> Option<(String, String)> {
        self.shelf
            .selected
            .iter()
            .chain(self.prefs.programmes.iter())
            .find_map(|item| match item {
                Selection::Video {
                    id: video_id,
                    title,
                    channel,
                    ..
                } if video_id == id => Some((title.clone(), channel.clone())),
                _ => None,
            })
    }
    pub fn is_current_request(&self, request: u64) -> bool {
        self.shelf.request == request
    }
    pub(super) fn request_page(&mut self) -> Action {
        if !matches!(self.page, Page::Youtube | Page::Search | Page::Channel(_)) {
            return Action::None;
        }
        self.shelf.request = self.shelf.request.wrapping_add(1);
        self.shelf.loading = true;
        self.shelf.error = None;
        self.shelf.offset = 0;
        self.shelf.items.clear();
        let request = self.shelf.request;
        match self.page {
            Page::Youtube => Action::YoutubeCatalog { request },
            Page::Channel(provider) => Action::Catalog {
                request,
                provider,
                query: String::new(),
            },
            _ => Action::Catalog {
                request,
                provider: self.provider,
                query: self.query.trim().into(),
            },
        }
    }
    pub fn catalog_loaded(&mut self, request: u64, result: Result<Vec<CatalogItem>, String>) {
        if self.is_current_request(request) && matches!(self.page, Page::Channel(_) | Page::Search)
        {
            self.finish_feed(
                result.map(|items| items.into_iter().map(Selection::Broadcast).collect()),
            );
        }
    }
    pub fn youtube_loaded(&mut self, request: u64, result: Result<Vec<YoutubeVideo>, String>) {
        if self.is_current_request(request) && self.page == Page::Youtube {
            self.finish_feed(result.map(|items| {
                items
                    .into_iter()
                    .map(|v| Selection::Video {
                        id: v.id,
                        title: v.title,
                        channel: v.channel,
                        thumbnail: v.thumbnail,
                    })
                    .collect()
            }));
        }
    }
    fn finish_feed(&mut self, result: Result<Vec<Selection>, String>) {
        self.shelf.loading = false;
        match result {
            Ok(items) => {
                self.shelf.items = items;
                self.shelf.error = None;
            }
            Err(error) => {
                self.shelf.items.clear();
                self.shelf.error = Some(error);
            }
        }
        if matches!(self.page, Page::Youtube | Page::Channel(_))
            && (self.focus == 0 || self.focus == 900)
        {
            self.focus = if self.shelf.items.is_empty() { 80 } else { 50 };
        }
    }
    fn per_page(&self) -> usize {
        if self.page == Page::Search { 3 } else { 6 }
    }
    pub(super) fn turn_page(&mut self, direction: isize) {
        let step = self.per_page();
        let (offset, count) = if self.page == Page::Watchlist {
            (
                &mut self.shelf.watch_offset,
                self.prefs.programmes.len() + self.prefs.saved.len(),
            )
        } else {
            (&mut self.shelf.offset, self.shelf.items.len())
        };
        *offset = ((*offset as isize + direction * step as isize).max(0) as usize)
            .min(count.saturating_sub(1) / step * step);
        self.focus = 50;
    }
    pub(super) fn open_result(&mut self, i: usize) -> Action {
        if let Some(item) = self.shelf.items.get(i).cloned() {
            self.select_programme(item);
        }
        Action::None
    }
    pub(super) fn select_programme(&mut self, item: Selection) {
        self.shelf.origin = Some((self.page, self.focus));
        self.shelf.selected = Some(item);
        self.go(Page::Programme);
    }
    pub(super) fn return_to_results(&mut self) {
        let (page, focus) = self.shelf.origin.take().unwrap_or((Page::Library, 0));
        self.go(page);
        self.focus = focus;
    }
    pub(super) fn play_selection(&mut self) -> Action {
        match self.shelf.selected.clone() {
            Some(Selection::Video { id, .. }) => Action::YoutubePlay(id),
            Some(Selection::Broadcast(item)) => {
                self.shelf.request = self.shelf.request.wrapping_add(1);
                self.notice("Wiedergabe wird vorbereitet …");
                Action::CatalogPlay {
                    request: self.shelf.request,
                    item,
                }
            }
            None => Action::None,
        }
    }
    pub(super) fn save_selection(&mut self) {
        if let Some(item) = self.shelf.selected.clone() {
            if let Some(i) = self.prefs.programmes.iter().position(|x| x.same(&item)) {
                self.prefs.programmes.remove(i);
            } else {
                self.prefs.programmes.insert(0, item);
            }
            self.persist();
        }
    }
    fn ellipsis(&self, text: &str, size: u16, width: f32) -> String {
        let mut current = 0.;
        let mut output = String::new();
        let ell = self.font.metrics('…', size as f32).advance_width;
        for ch in text.chars() {
            let ch = if ch.is_control() { ' ' } else { ch };
            let advance = self.font.metrics(ch, size as f32).advance_width;
            if current + advance + ell > width {
                output.push('…');
                break;
            }
            output.push(ch);
            current += advance;
        }
        output
    }
    pub fn wanted_images(&self) -> Vec<String> {
        let page = self.per_page();
        let urls: Vec<_> = match self.page {
            Page::Channel(_) | Page::Search | Page::Youtube => self
                .shelf
                .items
                .iter()
                .skip(self.shelf.offset)
                .take(page)
                .collect(),
            Page::Programme => self.shelf.selected.iter().collect(),
            Page::Watchlist => self
                .prefs
                .programmes
                .iter()
                .skip(self.shelf.watch_offset)
                .take(page)
                .collect(),
            _ => vec![],
        };
        urls.into_iter()
            .map(|v| v.thumbnail())
            .filter(|url| url.starts_with("https://") && !self.shelf.images.contains_key(*url))
            .map(str::to_owned)
            .collect()
    }
    pub fn image_loaded(&mut self, url: String, bytes: Vec<u8>) {
        if bytes.is_empty() || bytes.len() > 3 * 1024 * 1024 || self.shelf.images.contains_key(&url)
        {
            return;
        }
        let Ok(reader) = image::ImageReader::new(Cursor::new(&bytes)).with_guessed_format() else {
            return;
        };
        let Ok((w, h)) = reader.into_dimensions() else {
            return;
        };
        if w == 0 || h == 0 || u64::from(w) * u64::from(h) > 6_000_000 {
            return;
        }
        let Ok(img) = image::load_from_memory(&bytes) else {
            return;
        };
        if self.shelf.image_order.len() >= 24 {
            if let Some(old) = self.shelf.image_order.pop_front() {
                self.shelf.images.remove(&old);
                self.pictures.retain(|(key, _, _), _| key != &old);
            }
        }
        self.shelf.image_order.push_back(url.clone());
        self.shelf
            .images
            .insert(url, img.thumbnail(512, 320).to_rgba8());
    }
    fn navigation_buttons(&mut self, c: &mut Pixmap, count: usize, offset: usize, compact: bool) {
        let page = self.per_page();
        if count <= page {
            return;
        }
        let (x, y, step, width) = if compact {
            (1010., 323., 103., 96.)
        } else {
            (475., 579., 170., 150.)
        };
        if offset > 0 {
            self.button(
                c,
                77,
                "‹ Zurück",
                [x, y, width, 39.],
                Command::More(-1),
                false,
            );
        }
        if offset + page < count {
            self.button(
                c,
                78,
                "Weiter ›",
                [x + step, y, width, 39.],
                Command::More(1),
                false,
            );
        }
    }
    fn result_card(&mut self, c: &mut Pixmap, slot: usize, item: &Selection, command: Command) {
        let x = 64. + (slot % 3) as f32 * 388.;
        let y = 169. + (slot / 3) as f32 * 190.;
        let compact = self.page == Page::Search;
        let h = if compact { 145. } else { 177. };
        let rect = [x, y, 374., h];
        let id = 50 + slot;
        self.control(id, rect, command);
        let bg = match item {
            Selection::Broadcast(t) => match t.provider {
                1 => [28, 58, 106, 255],
                2 => [109, 59, 28, 255],
                _ => [100, 52, 46, 255],
            },
            Selection::Video { .. } => [63, 39, 55, 255],
        };
        fill(c, rect, 16., bg);
        if self.shelf.images.contains_key(item.thumbnail()) {
            self.picture(c, item.thumbnail(), rect, 16.);
        }
        fill(c, [x + 1., y + h - 65., 372., 64.], 10., [12, 18, 31, 228]);
        let name = self.ellipsis(item.service(), 14, 334.);
        self.text(c, &name, x + 17., y + h - 41., 14, ACCENT, false);
        let title = self.ellipsis(item.title(), 21, 339.);
        self.text(c, &title, x + 17., y + h - 14., 21, WHITE, false);
        stroke(
            c,
            rect,
            16.,
            if self.focus == id {
                ACCENT
            } else {
                [255, 255, 255, 60]
            },
            if self.focus == id { 3.5 } else { 1. },
        );
    }
    pub(super) fn draw_shelf(&mut self, c: &mut Pixmap, compact: bool) {
        let count = self.shelf.items.len();
        let offset = self.shelf.offset;
        if count == 0 {
            let message = if self.shelf.loading {
                "Sendungen werden geladen …"
            } else if self.shelf.error.is_some() {
                "Der Katalog ist gerade nicht erreichbar."
            } else {
                "Keine passenden Titel gefunden."
            };
            let y = if compact { 189. } else { 296. };
            self.centered(c, message, [150., y, 980., 50.], 25, WHITE);
            if !self.shelf.loading {
                self.button(
                    c,
                    80,
                    "Erneut laden",
                    [535., y + 56., 210., 44.],
                    Command::Retry,
                    false,
                );
            }
        } else {
            for (slot, item) in self
                .shelf
                .items
                .iter()
                .skip(offset)
                .take(self.per_page())
                .cloned()
                .collect::<Vec<_>>()
                .iter()
                .enumerate()
            {
                self.result_card(c, slot, item, Command::Pick(offset + slot));
            }
            self.navigation_buttons(c, count, offset, compact);
        }
    }
    pub(super) fn draw_programme(&mut self, c: &mut Pixmap) {
        let Some(item) = self.shelf.selected.clone() else {
            return;
        };
        let saved = self.prefs.programmes.iter().any(|x| x.same(&item));
        let rect = [64., 159., 465., 343.];
        fill(c, rect, 22., [34, 43, 62, 255]);
        if self.shelf.images.contains_key(item.thumbnail()) {
            self.picture(c, item.thumbnail(), rect, 22.);
        } else {
            play(c, 254., 283., 76., ACCENT);
        }
        self.text(
            c,
            &self.ellipsis(item.service(), 18, 620.),
            577.,
            189.,
            18,
            ACCENT,
            false,
        );
        let mut lines = Vec::<String>::new();
        let mut line = String::new();
        for word in item.title().split_whitespace() {
            let next = if line.is_empty() {
                word.to_owned()
            } else {
                format!("{line} {word}")
            };
            if self.ellipsis(&next, 28, 624.) != next && !line.is_empty() {
                lines.push(std::mem::take(&mut line));
                line = word.to_owned()
            } else {
                line = next
            };
        }
        if !line.is_empty() {
            lines.push(line);
        }
        for (i, line) in lines.iter().take(3).enumerate() {
            self.text(
                c,
                &self.ellipsis(line, 28, 625.),
                577.,
                239. + i as f32 * 38.,
                28,
                WHITE,
                false,
            );
        }
        self.text(
            c,
            &self.ellipsis(item.description(), 17, 620.),
            577.,
            353.,
            17,
            SOFT,
            false,
        );
        self.button(
            c,
            0,
            "Abspielen",
            [577., 393., 425., 59.],
            Command::PlaySelection,
            false,
        );
        self.button(
            c,
            1,
            if saved {
                "Aus Merkliste entfernen"
            } else {
                "Für später vormerken"
            },
            [577., 469., 425., 59.],
            Command::SaveSelection,
            saved,
        );
    }
    pub(super) fn draw_watchlist(&mut self, c: &mut Pixmap) {
        let count = self.prefs.programmes.len() + self.prefs.saved.len();
        let offset = self.shelf.watch_offset.min(count.saturating_sub(1) / 6 * 6);
        self.shelf.watch_offset = offset;
        if count == 0 {
            self.centered(
                c,
                "Hier erscheinen deine vorgemerkten Sendungen.",
                [190., 232., 900., 82.],
                24,
                WHITE,
            );
            self.button(
                c,
                0,
                "Sendungen entdecken",
                [460., 393., 360., 58.],
                Command::Page(Page::Search),
                false,
            );
            return;
        }
        for (slot, i) in (offset..(offset + 6).min(count)).enumerate() {
            if let Some(item) = self.prefs.programmes.get(i).cloned() {
                self.result_card(c, slot, &item, Command::SavedProgramme(i));
            } else {
                let idx = self.prefs.saved[i - self.prefs.programmes.len()];
                let x = 64. + (slot % 3) as f32 * 388.;
                let y = 169. + (slot / 3) as f32 * 190.;
                self.tile(
                    c,
                    50 + slot,
                    TITLES[idx].name,
                    TITLES[idx].art,
                    [x, y, 374., 177.],
                    Command::Page(Page::Detail(idx)),
                    None,
                );
            }
        }
        self.navigation_buttons(c, count, offset, false);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn item(id: &str) -> CatalogItem {
        CatalogItem {
            id: id.into(),
            title: "Morgenmagazin".into(),
            provider: 2,
            subtitle: "ZDF".into(),
            thumbnail: String::new(),
        }
    }
    #[test]
    fn changing_provider_discards_old_response_and_opens_real_item() {
        let mut ui = Ui::new();
        let Action::Catalog { request: first, .. } = ui.execute(Command::Page(Page::Search)) else {
            panic!()
        };
        let Action::Catalog {
            request: second,
            provider: 2,
            ..
        } = ui.execute(Command::Provider(2))
        else {
            panic!()
        };
        ui.catalog_loaded(first, Ok(vec![item("old")]));
        assert!(ui.shelf.loading);
        ui.catalog_loaded(second, Ok(vec![item("current")]));
        ui.open_result(0);
        ui.execute(Command::SaveSelection);
        assert_eq!(ui.prefs.programmes.len(), 1);
        assert!(matches!(ui.play_selection(),Action::CatalogPlay{item,..} if item.id=="current"));
        ui.key(Key::Back);
        assert_eq!(ui.page, Page::Search);
        assert_eq!(ui.shelf.items[0].title(), "Morgenmagazin");
    }
    #[test]
    fn youtube_result_starts_embedded_player_and_back_returns_to_catalogue() {
        let mut ui = Ui::new();
        let Action::YoutubeCatalog { request } = ui.execute(Command::Page(Page::Youtube)) else {
            panic!()
        };
        ui.youtube_loaded(
            request,
            Ok(vec![YoutubeVideo {
                id: "abcdefghijk".into(),
                title: "Aktuelle Nachrichten".into(),
                channel: "tagesschau".into(),
                published: String::new(),
                thumbnail: String::new(),
            }]),
        );
        ui.open_result(0);
        assert!(matches!(ui.play_selection(),Action::YoutubePlay(id) if id=="abcdefghijk"));
        ui.key(Key::Back);
        assert_eq!(ui.page, Page::Youtube);
        assert_eq!(ui.shelf.items.len(), 1);
    }
}
