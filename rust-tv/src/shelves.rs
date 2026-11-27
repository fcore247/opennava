use super::*;
use crate::catalog::CatalogPage;
use crate::youtube::{YoutubePage, YoutubeVideo};
use std::collections::HashSet;
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
impl From<YoutubeVideo> for Selection {
    fn from(video: YoutubeVideo) -> Self {
        Self::Video {
            id: video.id,
            title: video.title,
            channel: video.channel,
            thumbnail: video.thumbnail,
        }
    }
}
fn mixed_results(shows: Vec<CatalogItem>, videos: Vec<YoutubeVideo>) -> Vec<Selection> {
    let mut shows = shows.into_iter().map(Selection::Broadcast);
    let mut videos = videos.into_iter().map(Selection::from);
    let mut items = Vec::with_capacity(shows.len() + videos.len());
    while shows.len() > 0 && videos.len() > 0 {
        items.extend(shows.next());
        items.extend(videos.next());
    }
    items.extend(shows);
    items.extend(videos);
    items
}

impl Selection {
    fn identity(&self) -> (usize, &str) {
        match self {
            Self::Broadcast(item) => (item.provider, &item.id),
            Self::Video { id, .. } => (YOUTUBE_PROVIDER, id),
        }
    }
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
            Self::Broadcast(x) if !x.source.is_empty() => &x.source,
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
        self.identity() == other.identity()
    }
}
#[derive(Default)]
pub(super) struct Shelf {
    pub request: u64,
    items: Vec<Selection>,
    offset: usize,
    loading: bool,
    pub(super) loading_more: bool,
    focus_more: bool,
    next: Option<String>,
    publisher_mask: u8,
    publisher_page: u32,
    consumed: HashSet<String>,
    skipped_empty_pages: u8,
    error: Option<String>,
    selected: Option<Selection>,
    origin: Option<(Page, usize)>,
    watch_offset: usize,
    pub(super) images: HashMap<String, RgbaImage>,
    image_order: VecDeque<String>,
}
impl Ui {
    fn focused_video(&self) -> Option<&Selection> {
        if let Some(index) = self.focused_result_index() {
            return self
                .shelf
                .items
                .get(index)
                .filter(|item| matches!(item, Selection::Video { .. }));
        }
        let slot = self.focus.checked_sub(50)?;
        if self.page == Page::Watchlist && slot < self.per_page() {
            return self
                .prefs
                .programmes
                .get(self.shelf.watch_offset + slot)
                .filter(|item| matches!(item, Selection::Video { .. }));
        }
        None
    }
    pub fn focused_video_id(&self) -> Option<&str> {
        match self.focused_video()? {
            Selection::Video { id, .. } => Some(id),
            _ => None,
        }
    }
    pub(super) fn video_controls_hint(&self) -> Option<&'static str> {
        let item = self.focused_video()?;
        Some(
            if self.prefs.programmes.iter().any(|saved| saved.same(item)) {
                "OK zum Abspielen · OK halten zum Entfernen aus der Merkliste"
            } else {
                "OK zum Abspielen · OK halten zum Vormerken"
            },
        )
    }
    pub(super) fn toggle_focused_video(&mut self) {
        if let Some(item) = self.focused_video().cloned() {
            self.toggle_saved(item);
            if self.page == Page::Watchlist {
                let count = self.prefs.programmes.len() + self.prefs.saved.len();
                if count == 0 {
                    self.focus = 0;
                } else {
                    let index = (self.shelf.watch_offset + self.focus - 50).min(count - 1);
                    self.shelf.watch_offset = index / self.per_page() * self.per_page();
                    self.focus = 50 + index % self.per_page();
                }
            }
        }
    }
    pub fn is_current_request(&self, request: u64) -> bool {
        self.shelf.request == request
    }
    pub(crate) fn request_page(&mut self) -> Action {
        if !matches!(
            self.page,
            Page::Youtube | Page::YoutubeSearch | Page::Search | Page::Channel(_) | Page::Own(_)
        ) {
            return Action::None;
        }
        self.shelf.request = self.shelf.request.wrapping_add(1);
        self.shelf.loading = true;
        self.shelf.loading_more = false;
        self.shelf.next = None;
        self.shelf.publisher_mask = 0;
        self.shelf.publisher_page = 0;
        self.shelf.consumed.clear();
        self.shelf.skipped_empty_pages = 0;
        self.shelf.error = None;
        self.shelf.offset = 0;
        self.shelf.items.clear();
        let request = self.shelf.request;
        match self.page {
            Page::Youtube => Action::YoutubeCatalog { request },
            Page::YoutubeSearch => {
                let query = self.youtube_query.trim();
                self.shelf.loading = !query.is_empty();
                if query.is_empty() {
                    Action::None
                } else {
                    Action::YoutubeSearch {
                        request,
                        query: query.to_owned(),
                    }
                }
            }
            Page::Search if self.provider == YOUTUBE_PROVIDER => {
                let query = self.query.trim();
                if query.is_empty() {
                    Action::YoutubeCatalog { request }
                } else {
                    Action::YoutubeSearch {
                        request,
                        query: query.to_owned(),
                    }
                }
            }
            Page::Own(i) => match self.libraries.get(i) {
                Some(library) => Action::OwnCatalog {
                    request,
                    library: library.clone(),
                },
                None => Action::None,
            },
            Page::Channel(provider) => Action::Catalog {
                request,
                provider,
                query: String::new(),
            },
            _ => Action::MainSearch {
                request,
                provider: self.provider,
                query: self.query.trim().into(),
            },
        }
    }
    pub fn catalog_loaded(&mut self, request: u64, result: Result<Vec<CatalogItem>, String>) {
        if self.is_current_request(request) && matches!(self.page, Page::Channel(_) | Page::Own(_))
        {
            self.finish_feed(
                result.map(|items| items.into_iter().map(Selection::Broadcast).collect()),
            );
        }
    }
    /// The account's YouTube results and the public broadcaster results arrive together.
    /// Alternate the two sources so both are reachable on the first row.
    pub fn combined_search_loaded(
        &mut self,
        request: u64,
        broadcasts: Result<CatalogPage, String>,
        youtube: Result<YoutubePage, String>,
    ) -> Action {
        if !self.is_current_request(request)
            || self.page != Page::Search
            || self.provider == YOUTUBE_PROVIDER
        {
            return Action::None;
        }
        let (broadcasts, youtube) = match (broadcasts, youtube) {
            (Err(error), Ok(videos)) if videos.videos.is_empty() && videos.next.is_none() => {
                self.finish_feed(Err(error));
                return Action::None;
            }
            (Ok(shows), Err(error)) if shows.items.is_empty() && shows.remaining == 0 => {
                self.finish_feed(Err(error));
                return Action::None;
            }
            (Err(error), Err(_)) => {
                self.finish_feed(Err(error));
                return Action::None;
            }
            (broadcasts, youtube) => (broadcasts.unwrap_or_default(), youtube.unwrap_or_default()),
        };
        self.shelf.next = youtube.next;
        self.shelf.publisher_mask = broadcasts.remaining;
        self.shelf.publisher_page = 1;
        let items = mixed_results(broadcasts.items, youtube.videos);
        self.finish_feed(Ok(items));
        if self.shelf.items.is_empty() && self.has_more() {
            return self.request_more();
        }
        Action::None
    }
    pub fn youtube_loaded(&mut self, request: u64, result: Result<YoutubePage, String>) -> Action {
        if self.is_current_request(request) && self.is_youtube_shelf() {
            let items = result.map(|page| {
                self.shelf.next = page.next;
                page.videos.into_iter().map(Selection::from).collect()
            });
            self.finish_feed(items);
            if self.shelf.items.is_empty() && self.has_more() {
                return self.request_more();
            }
        }
        Action::None
    }

    fn has_more(&self) -> bool {
        self.shelf.next.is_some() || self.shelf.publisher_mask != 0
    }

    fn request_more(&mut self) -> Action {
        if !self.has_more() {
            return Action::None;
        }
        self.shelf.focus_more = true;
        if self.shelf.loading_more {
            return Action::None;
        }
        self.shelf.loading_more = true;
        self.shelf.request = self.shelf.request.wrapping_add(1);
        self.notice("Weitere Videos werden geladen …");
        if self.shelf.publisher_mask != 0 {
            return Action::SearchMore {
                request: self.shelf.request,
                query: self.query.trim().to_owned(),
                providers: self.shelf.publisher_mask,
                page: self.shelf.publisher_page,
                youtube_cursor: self.shelf.next.clone(),
            };
        }
        Action::YoutubeMore {
            request: self.shelf.request,
            cursor: self
                .shelf
                .next
                .clone()
                .expect("a YouTube continuation is available"),
        }
    }

    pub fn youtube_more_loaded(
        &mut self,
        request: u64,
        result: Result<YoutubePage, String>,
    ) -> Action {
        self.search_more_loaded(request, None, result)
    }

    pub fn search_more_loaded(
        &mut self,
        request: u64,
        broadcasts: Option<Result<CatalogPage, String>>,
        youtube: Result<YoutubePage, String>,
    ) -> Action {
        if !self.is_current_request(request)
            || !self.shelf.loading_more
            || !self.pages_with_arrows()
        {
            return Action::None;
        }
        self.shelf.loading_more = false;
        let failed = broadcasts.as_ref().is_some_and(Result::is_err) || youtube.is_err();
        let mut shows = Vec::new();
        if let Some(Ok(batch)) = broadcasts {
            self.shelf.publisher_mask = batch.remaining;
            self.shelf.publisher_page += 1;
            shows = batch.items;
        }
        let mut videos = Vec::new();
        if let Ok(batch) = youtube {
            if let Some(previous) = self.shelf.next.take() {
                self.shelf.consumed.insert(previous);
            }
            self.shelf.next = batch
                .next
                .filter(|cursor| !self.shelf.consumed.contains(cursor));
            videos = batch.videos;
        }
        let count = self.shelf.items.len();
        let at_end = self.shelf.focus_more && self.focused_result_index() == count.checked_sub(1);
        let mut seen: HashSet<_> = self.shelf.items.iter().map(Selection::identity).collect();
        let candidates = mixed_results(shows, videos);
        let additional: Vec<_> = candidates
            .iter()
            .filter(|item| seen.insert(item.identity()))
            .cloned()
            .collect();
        self.shelf.items.extend(additional);
        self.message.clear();
        if self.shelf.items.len() > count {
            self.shelf.skipped_empty_pages = 0;
            if at_end && count > 0 {
                self.focus_result(count);
            }
        } else if failed {
            if count == 0 {
                self.shelf.error = Some("Die Suche konnte keine Ergebnisse laden.".into());
            }
            self.notice("Weitere Treffer konnten nicht geladen werden. Mit → erneut versuchen.");
        } else if at_end && self.has_more() {
            if self.shelf.skipped_empty_pages < 3 {
                self.shelf.skipped_empty_pages += 1;
                return self.request_more();
            }
            if count == 0 {
                self.shelf.error = Some("Keine weiteren abspielbaren Treffer gefunden.".into());
            } else {
                self.notice("Mit → weitere Videos laden.");
            }
        }
        Action::None
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
        if matches!(self.page, Page::Channel(_) | Page::Own(_))
            && (self.focus == 0 || self.focus == 900)
        {
            self.focus = if self.shelf.items.is_empty() { 80 } else { 50 };
        }
    }
    fn per_page(&self) -> usize {
        if matches!(self.page, Page::Search | Page::YoutubeSearch) {
            3
        } else {
            6
        }
    }
    pub(super) fn is_youtube_shelf(&self) -> bool {
        matches!(self.page, Page::Youtube | Page::YoutubeSearch)
            || (self.page == Page::Search && self.provider == YOUTUBE_PROVIDER)
    }

    fn pages_with_arrows(&self) -> bool {
        matches!(
            self.page,
            Page::Search | Page::Youtube | Page::YoutubeSearch
        )
    }

    fn focus_result(&mut self, index: usize) {
        let page_size = self.per_page();
        self.shelf.offset = index / page_size * page_size;
        self.focus = 50 + index % page_size;
    }

    pub(super) fn focused_result_index(&self) -> Option<usize> {
        if !self.pages_with_arrows() {
            return None;
        }
        let slot = self.focus.checked_sub(50)?;
        let index = self.shelf.offset + slot;
        (slot < self.per_page() && index < self.shelf.items.len()).then_some(index)
    }

    pub(super) fn move_result(&mut self, index: usize, key: Key) -> Action {
        if matches!(key, Key::Right) && index + 1 == self.shelf.items.len() {
            self.shelf.skipped_empty_pages = 0;
            return self.request_more();
        }
        let next = match key {
            Key::Left => index.saturating_sub(1),
            Key::Right => (index + 1).min(self.shelf.items.len() - 1),
            _ => return Action::None,
        };
        self.focus_result(next);
        Action::None
    }

    pub fn swipe(&mut self, from: (f32, f32), to: (f32, f32), size: (u32, u32)) -> Option<Action> {
        if size.0 == 0 || size.1 == 0 {
            return None;
        }
        let (sx, sy) = (W as f32 / size.0 as f32, H as f32 / size.1 as f32);
        let (dx, dy) = ((to.0 - from.0) * sx, (to.1 - from.1) * sy);
        if dx.abs() < 65. || dx.abs() < 1.5 * dy.abs() {
            return None;
        }
        let (x, y) = (from.0 * sx, from.1 * sy);
        if self.watches_home() && self.home.has_items() && (155.0..431.0).contains(&y) {
            self.turn_home(if dx < 0. { 1 } else { -1 });
            return Some(Action::None);
        }
        if !self.pages_with_arrows()
            || !self.controls.iter().any(|c| {
                matches!(c.command, Command::Pick(_))
                    && x >= c.x
                    && x <= c.x + c.w
                    && y >= c.y
                    && y <= c.y + c.h
            })
        {
            return None;
        }
        let offset = self.shelf.offset;
        if dx < 0. && offset + self.per_page() < self.shelf.items.len() {
            self.focus_result(offset + self.per_page());
        } else if dx < 0. && self.has_more() {
            self.shelf.skipped_empty_pages = 0;
            self.focus_result(self.shelf.items.len() - 1);
            return Some(self.request_more());
        } else if dx > 0. && offset > 0 {
            self.focus_result(offset - 1);
        }
        Some(Action::None)
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
            return self.select_programme(item);
        }
        Action::None
    }
    pub(super) fn select_programme(&mut self, item: Selection) -> Action {
        if let Selection::Video {
            id, title, channel, ..
        } = item
        {
            // Keep the playing card selected even if a pending next page arrives during playback.
            self.shelf.focus_more = false;
            return Action::YoutubePlay { id, title, channel };
        }
        self.shelf.origin = Some((self.page, self.focus));
        self.shelf.selected = Some(item);
        self.go(Page::Programme);
        Action::None
    }
    pub(super) fn return_to_results(&mut self) {
        let (page, focus) = self.shelf.origin.take().unwrap_or((Page::Library, 0));
        self.go(page);
        self.focus = focus;
    }
    pub(super) fn play_selection(&mut self) -> Action {
        match self.shelf.selected.clone() {
            Some(Selection::Broadcast(item)) => {
                self.shelf.request = self.shelf.request.wrapping_add(1);
                self.notice("Wiedergabe wird vorbereitet …");
                Action::CatalogPlay {
                    request: self.shelf.request,
                    item,
                }
            }
            _ => Action::None,
        }
    }
    pub(super) fn save_selection(&mut self) {
        if let Some(item) = self.shelf.selected.clone() {
            self.toggle_saved(item);
        }
    }
    fn toggle_saved(&mut self, item: Selection) {
        if let Some(i) = self.prefs.programmes.iter().position(|x| x.same(&item)) {
            self.prefs.programmes.remove(i);
        } else {
            self.prefs.programmes.insert(0, item);
        }
        self.persist();
    }
    pub(super) fn ellipsis(&self, text: &str, size: u16, width: f32) -> String {
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
            Page::Channel(_)
            | Page::Own(_)
            | Page::Search
            | Page::YoutubeSearch
            | Page::Youtube => self
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
        if w == 0 || h == 0 || u64::from(w) * u64::from(h) > 10_000_000 {
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
        let compact = matches!(self.page, Page::Search | Page::YoutubeSearch);
        let h = if compact { 145. } else { 177. };
        let rect = [x, y, 374., h];
        let id = 50 + slot;
        self.control(id, rect, command);
        let bg = match item {
            Selection::Broadcast(t) => match t.provider {
                1 => [28, 58, 106, 255],
                2 => [109, 59, 28, 255],
                crate::catalog::OWN => [58, 48, 104, 255],
                _ => [100, 52, 46, 255],
            },
            Selection::Video { .. } => [63, 39, 55, 255],
        };
        fill(c, rect, 16., bg);
        if self.shelf.images.contains_key(item.thumbnail()) {
            self.picture(c, item.thumbnail(), rect, 16.);
        }
        fill(c, [x + 1., y + h - 65., 372., 64.], 10., [12, 18, 31, 228]);
        let name = if self.page == Page::Search && matches!(item, Selection::Video { .. }) {
            self.ellipsis(&format!("YouTube · {}", item.service()), 14, 334.)
        } else {
            self.ellipsis(item.service(), 14, 334.)
        };
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
            if self.page == Page::YoutubeSearch && self.youtube_query.trim().is_empty() {
                return;
            }
            let youtube = self.is_youtube_shelf();
            let message = if (self.shelf.loading || self.shelf.loading_more) && youtube {
                "Videos werden geladen …"
            } else if self.shelf.loading {
                "Sendungen werden geladen …"
            } else if self.shelf.error.is_some() && youtube {
                "YouTube ist gerade nicht erreichbar."
            } else if self.shelf.error.is_some() {
                "Der Katalog ist gerade nicht erreichbar."
            } else {
                "Keine passenden Titel gefunden."
            };
            let y = if compact { 189. } else { 296. };
            self.centered(c, message, [150., y, 980., 50.], 25, WHITE);
            if !self.shelf.loading && (!youtube || self.shelf.error.is_some()) {
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
            if !self.pages_with_arrows() {
                self.navigation_buttons(c, count, offset, compact);
            }
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
    fn shows(items: Vec<CatalogItem>, remaining: u8) -> Result<CatalogPage, String> {
        Ok(CatalogPage { items, remaining })
    }
    fn item(id: &str) -> CatalogItem {
        CatalogItem {
            source: String::new(),
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
        let Action::MainSearch { request: first, .. } = ui.execute(Command::Page(Page::Search))
        else {
            panic!()
        };
        let Action::MainSearch {
            request: second,
            provider: 2,
            ..
        } = ui.execute(Command::Provider(2))
        else {
            panic!()
        };
        ui.combined_search_loaded(
            first,
            shows(vec![item("old")], 0),
            Ok(YoutubePage::default()),
        );
        assert!(ui.shelf.loading);
        ui.combined_search_loaded(
            second,
            shows(vec![item("current")], 0),
            Ok(YoutubePage::default()),
        );
        ui.open_result(0);
        ui.execute(Command::SaveSelection);
        assert_eq!(ui.prefs.programmes.len(), 1);
        assert!(matches!(ui.play_selection(),Action::CatalogPlay{item,..} if item.id=="current"));
        ui.key(Key::Back);
        assert_eq!(ui.page, Page::Search);
        assert_eq!(ui.shelf.items[0].title(), "Morgenmagazin");
    }
    fn video_page(range: std::ops::Range<usize>, next: Option<&str>) -> YoutubePage {
        YoutubePage {
            videos: range
                .map(|i| YoutubeVideo {
                    id: format!("{i:011}"),
                    title: format!("Video {i}"),
                    channel: "Kanal".into(),
                    published: String::new(),
                    thumbnail: String::new(),
                })
                .collect(),
            next: next.map(str::to_string),
        }
    }

    #[test]
    fn main_search_interleaves_sources_keeps_account_video_actions_and_pages_on_arrow() {
        let mut ui = Ui::new();
        ui.query = "Welt".into();
        let Action::MainSearch {
            request,
            provider: 0,
            ..
        } = ui.execute(Command::Page(Page::Search))
        else {
            panic!()
        };
        let movies = vec![item("a"), item("b"), item("c")];
        ui.combined_search_loaded(
            request,
            shows(movies, 0),
            Ok(video_page(0..3, Some("further"))),
        );
        ui.render(W, H);
        assert!(
            !ui.controls
                .iter()
                .any(|c| matches!(c.command, Command::More(_)))
        );
        assert_eq!(ui.shelf.items.len(), 6);
        ui.focus = 50;
        assert_eq!(ui.focused_video_id(), None);
        ui.key(Key::Right);
        assert!(
            matches!(ui.key(Key::Select), Action::YoutubePlay { id, .. } if id == "00000000000")
        );
        ui.key(Key::Right);
        ui.key(Key::Right);
        assert_eq!((ui.shelf.offset, ui.focus), (3, 50));
        ui.key(Key::Left);
        assert_eq!((ui.shelf.offset, ui.focus), (0, 52));
        for _ in 0..3 {
            ui.key(Key::Right);
        }
        let Action::YoutubeMore { request, .. } = ui.key(Key::Right) else {
            panic!()
        };
        ui.youtube_more_loaded(request, Ok(video_page(3..4, None)));
        assert_eq!(ui.focused_video_id(), Some("00000000003"));
        let Action::YoutubeSearch {
            request: youtube_only,
            query,
        } = ui.execute(Command::Provider(YOUTUBE_PROVIDER))
        else {
            panic!()
        };
        assert_eq!(query, "Welt");
        ui.combined_search_loaded(
            request,
            shows(vec![item("stale")], 0),
            Ok(video_page(20..30, None)),
        );
        ui.youtube_loaded(youtube_only, Ok(video_page(10..13, None)));
        assert_eq!(ui.shelf.items.len(), 3);
        ui.focus = 50;
        assert_eq!(ui.focused_video_id(), Some("00000000010"));
        ui.execute(Command::Provider(1));
        ui.combined_search_loaded(
            ui.shelf.request,
            shows((0..7).map(|i| item(&i.to_string())).collect(), 0),
            Ok(YoutubePage::default()),
        );
        ui.render(W, H);
        assert!(
            !ui.controls
                .iter()
                .any(|c| matches!(c.command, Command::More(_)))
        );
        for _ in 0..3 {
            ui.key(Key::Right);
        }
        assert_eq!((ui.shelf.offset, ui.focus), (3, 50));
        assert_eq!(ui.focused_video_id(), None);
        ui.key(Key::Select);
        assert_eq!(ui.page, Page::Programme);
    }

    #[test]
    fn combined_remote_pages_preserve_both_cursors_and_focused_cards() {
        let mut ui = Ui::new();
        ui.query = "Welt".into();
        ui.execute(Command::Page(Page::Search));
        ui.combined_search_loaded(
            ui.shelf.request,
            shows(vec![item("a"), item("b")], 3),
            Ok(video_page(0..1, Some("youtube-next"))),
        );
        ui.focus = 52;
        let Action::SearchMore {
            request,
            providers: 3,
            page: 1,
            youtube_cursor,
            query,
        } = ui.key(Key::Right)
        else {
            panic!()
        };
        assert_eq!(query, "Welt");
        assert_eq!(youtube_cursor.as_deref(), Some("youtube-next"));
        assert!(matches!(ui.key(Key::Right), Action::None));
        ui.search_more_loaded(
            request,
            Some(shows(vec![item("b"), item("c")], 2)),
            Err("offline".into()),
        );
        assert_eq!(ui.shelf.items.len(), 4);
        assert_eq!(ui.focused_result_index(), Some(3));
        let Action::SearchMore {
            request,
            providers: 2,
            page: 2,
            youtube_cursor,
            ..
        } = ui.key(Key::Right)
        else {
            panic!()
        };
        assert_eq!(youtube_cursor.as_deref(), Some("youtube-next"));
        ui.key(Key::Left);
        ui.search_more_loaded(
            request,
            Some(Err("offline".into())),
            Ok(video_page(0..2, None)),
        );
        assert_eq!(ui.shelf.items.len(), 5);
        assert_eq!(ui.focused_result_index(), Some(2));
        ui.key(Key::Right);
        ui.key(Key::Right);
        let Action::SearchMore {
            request,
            providers: 2,
            page: 2,
            youtube_cursor: None,
            ..
        } = ui.key(Key::Right)
        else {
            panic!()
        };
        ui.execute(Command::Provider(1));
        ui.search_more_loaded(
            request,
            Some(shows(vec![item("stale")], 0)),
            Ok(YoutubePage::default()),
        );
        assert!(ui.shelf.items.is_empty());
    }
    #[test]
    fn combined_search_keeps_the_available_provider() {
        let mut ui = Ui::new();
        ui.execute(Command::Page(Page::Search));
        ui.combined_search_loaded(
            ui.shelf.request,
            Err("offline".into()),
            Ok(video_page(0..2, None)),
        );
        assert_eq!(ui.shelf.items.len(), 2);
        ui.combined_search_loaded(
            ui.shelf.request,
            shows(vec![item("broadcast")], 0),
            Err("offline".into()),
        );
        assert!(matches!(
            ui.shelf.items.as_slice(),
            [Selection::Broadcast(_)]
        ));
        ui.combined_search_loaded(
            ui.shelf.request,
            Err("offline".into()),
            Err("offline".into()),
        );
        assert!(ui.shelf.error.is_some());
    }

    #[test]
    fn youtube_pages_follow_card_focus_and_support_horizontal_swipes() {
        for page in [Page::Youtube, Page::YoutubeSearch] {
            let mut ui = Ui::new();
            ui.youtube_query = "video".into();
            ui.execute(Command::Page(page));
            ui.youtube_loaded(ui.shelf.request, Ok(video_page(0..8, None)));
            ui.render(W, H);
            assert!(
                !ui.controls
                    .iter()
                    .any(|c| matches!(c.command, Command::More(_)))
            );
            let step = ui.per_page();
            ui.focus = 50;
            ui.key(Key::Left);
            assert_eq!(ui.focused_result_index(), Some(0));
            for index in 1..=step {
                ui.key(Key::Right);
                assert_eq!(ui.focused_result_index(), Some(index));
            }
            assert_eq!((ui.shelf.offset, ui.focus), (step, 50));
            ui.key(Key::Left);
            assert_eq!(ui.focused_result_index(), Some(step - 1));
            ui.swipe((320., 220.), (125., 227.), (W, H));
            assert_eq!(ui.focused_result_index(), Some(step));
            ui.render(W, H);
            ui.swipe((125., 220.), (320., 227.), (W, H));
            assert_eq!(ui.focused_result_index(), Some(step - 1));
            for _ in 0..10 {
                ui.key(Key::Right);
            }
            assert_eq!(ui.focused_result_index(), Some(7));
            assert!(
                matches!(ui.key(Key::Select), Action::YoutubePlay { id, .. } if id == "00000000007")
            );
            assert_eq!(ui.page, page);
            assert_eq!(ui.focused_result_index(), Some(7));
        }
    }

    #[test]
    fn youtube_edge_fetches_once_retries_deduplicates_and_ignores_a_stale_cursor() {
        let mut ui = Ui::new();
        ui.execute(Command::Page(Page::YoutubeSearch));
        let Action::YoutubeSearch { request, .. } = ui.key(Key::Character('X')) else {
            panic!()
        };
        ui.youtube_loaded(request, Ok(video_page(0..3, Some("next"))));
        ui.focus = 52;
        let Action::YoutubeMore {
            request: first,
            cursor,
        } = ui.key(Key::Right)
        else {
            panic!()
        };
        assert_eq!(cursor, "next");
        assert!(matches!(ui.key(Key::Right), Action::None));
        ui.youtube_more_loaded(first, Err("offline".into()));
        assert_eq!(ui.focused_result_index(), Some(2));
        let Action::YoutubeMore { request: retry, .. } = ui.key(Key::Right) else {
            panic!()
        };
        ui.youtube_more_loaded(retry, Ok(video_page(2..5, Some("third"))));
        assert_eq!(
            (ui.shelf.items.len(), ui.focused_result_index()),
            (5, Some(3))
        );
        ui.key(Key::Right);
        let Action::YoutubeMore { request: third, .. } = ui.key(Key::Right) else {
            panic!()
        };
        let Action::YoutubeMore {
            request: fourth,
            cursor,
        } = ui.youtube_more_loaded(third, Ok(video_page(2..4, Some("fourth"))))
        else {
            panic!()
        };
        assert_eq!(cursor, "fourth");
        ui.key(Key::Character('Y'));
        ui.youtube_more_loaded(fourth, Ok(video_page(50..60, None)));
        assert!(ui.shelf.items.is_empty());
        assert!(ui.shelf.next.is_none());
    }

    #[test]
    fn menu_and_held_tap_save_videos_without_playing_and_watchlist_ok_plays_directly() {
        let mut ui = Ui::new();
        ui.execute(Command::Page(Page::Youtube));
        ui.youtube_loaded(ui.shelf.request, Ok(video_page(0..8, Some("edge"))));
        ui.render(W, H);
        assert!(matches!(
            ui.activate_at(175., 230., W, H, true),
            Action::None
        ));
        assert_eq!(ui.prefs.programmes.len(), 1);
        assert_eq!(ui.page, Page::Youtube);
        for index in 1..8 {
            ui.focus_result(index);
            ui.key(Key::Menu);
        }
        let Action::YoutubeMore { request, .. } = ui.key(Key::Right) else {
            panic!()
        };
        assert!(
            matches!(ui.key(Key::Select), Action::YoutubePlay { id, .. } if id == "00000000007")
        );
        ui.youtube_more_loaded(request, Ok(video_page(8..10, None)));
        assert_eq!(ui.focused_result_index(), Some(7));
        ui.execute(Command::Page(Page::Watchlist));
        ui.render(W, H);
        assert_eq!(ui.focus, 50);
        assert!(
            matches!(ui.key(Key::Select), Action::YoutubePlay { id, title, .. }
            if id == "00000000007" && title == "Video 7")
        );
        assert_eq!(ui.page, Page::Watchlist);
        ui.turn_page(1);
        ui.focus = 51;
        ui.render(W, H);
        ui.key(Key::Menu);
        assert_eq!((ui.shelf.watch_offset, ui.focus), (6, 50));
        ui.key(Key::Menu);
        assert_eq!((ui.shelf.watch_offset, ui.focus), (0, 55));
        for _ in 0..6 {
            ui.key(Key::Menu);
        }
        ui.render(W, H);
        assert!(ui.prefs.programmes.is_empty());
        assert_eq!(ui.focus, 0);
    }

    #[test]
    fn remote_can_retry_failed_youtube_home_and_search_without_retyping() {
        let mut ui = Ui::new();
        let Action::YoutubeCatalog { request: first } = ui.execute(Command::Page(Page::Youtube))
        else {
            panic!()
        };
        ui.youtube_loaded(first, Err("offline".into()));
        ui.render(W, H);
        ui.key(Key::Down);
        let Action::YoutubeCatalog { request: retry } = ui.key(Key::Select) else {
            panic!("Home retry unreachable")
        };
        assert_ne!(retry, first);
        ui.execute(Command::Page(Page::YoutubeSearch));
        let Action::YoutubeSearch { request, .. } = ui.key(Key::Character('K')) else {
            panic!()
        };
        ui.youtube_loaded(request, Err("offline".into()));
        ui.render(W, H);
        ui.key(Key::Up);
        let Action::YoutubeSearch {
            request: newer,
            query,
        } = ui.key(Key::Select)
        else {
            panic!("Search retry unreachable")
        };
        assert_ne!(newer, request);
        assert_eq!(query, "K");
    }

    #[test]
    fn youtube_dpad_search_ignores_old_results_and_preserves_the_other_search() {
        let mut ui = Ui::new();
        ui.query = "Tatort".into();
        let Action::YoutubeCatalog { request: home } = ui.execute(Command::Page(Page::Youtube))
        else {
            panic!()
        };
        ui.render(W, H);
        ui.key(Key::Right);
        assert!(matches!(ui.key(Key::Select), Action::YoutubeAccount));
        ui.key(Key::Left);
        ui.key(Key::Select);
        assert_eq!(ui.page, Page::YoutubeSearch);
        ui.render(W, H);
        let Action::YoutubeSearch {
            request: first,
            query,
        } = ui.key(Key::Select)
        else {
            panic!()
        };
        assert_eq!(query, "Q");
        let Action::YoutubeSearch {
            request: second,
            query,
        } = ui.key(Key::Character('ä'))
        else {
            panic!()
        };
        assert_eq!(query, "Qä");
        ui.youtube_loaded(home, Ok(YoutubePage::default()));
        ui.youtube_loaded(first, Ok(YoutubePage::default()));
        assert!(ui.shelf.loading);
        let hit = YoutubeVideo {
            id: "abcdefghijk".into(),
            title: "Ergebnis".into(),
            channel: String::new(),
            thumbnail: String::new(),
            published: String::new(),
        };
        ui.youtube_loaded(second, Ok(vec![hit].into()));
        ui.render(W, H);
        ui.key(Key::Up);
        assert!(
            matches!(ui.key(Key::Select), Action::YoutubePlay { id, title, .. } if id == "abcdefghijk" && title == "Ergebnis")
        );
        assert_eq!(ui.page, Page::YoutubeSearch);
        assert_eq!(ui.youtube_query, "Qä");
        assert!(matches!(ui.key(Key::Back), Action::YoutubeCatalog { .. }));
        assert_eq!(ui.page, Page::Youtube);
        assert_eq!(ui.query, "Tatort");
    }

    #[test]
    fn youtube_tap_plays_with_metadata_while_catalogue_stays_in_place() {
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
            }]
            .into()),
        );
        ui.render(W, H);
        assert!(
            matches!(ui.tap(175., 230., W, H), Action::YoutubePlay { id, title, channel }
            if id == "abcdefghijk" && title == "Aktuelle Nachrichten" && channel == "tagesschau")
        );
        assert_eq!(ui.page, Page::Youtube);
        assert_eq!(ui.shelf.items.len(), 1);
    }
}
