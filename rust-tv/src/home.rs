//! Personal TV home shared with the paired phone. The original home is the empty row layout.
use super::*;

const FIRST_TILE: usize = 1000;
const COLUMNS: usize = 4;

#[derive(Clone, PartialEq, Default, Deserialize)]
pub(crate) struct HomeLayout {
    #[serde(default)]
    layout: Layout,
    #[serde(default)]
    items: Vec<Item>,
    #[serde(default)]
    apps: Vec<App>,
}

#[derive(Clone, Copy, PartialEq, Default, Deserialize)]
#[serde(rename_all = "lowercase")]
enum Layout {
    #[default]
    Row,
    Grid,
}

#[derive(Clone, PartialEq, Deserialize)]
struct App {
    package: String,
    name: String,
}

#[derive(Clone, PartialEq, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
enum Item {
    App {
        package: String,
    },
    Folder {
        id: String,
        name: String,
        apps: Vec<String>,
    },
}

impl HomeLayout {
    pub(crate) fn pinned_packages(&self) -> Vec<&str> {
        let mut result = Vec::new();
        for item in &self.items {
            match item {
                Item::App { package } => result.push(package.as_str()),
                Item::Folder { apps, .. } => result.extend(apps.iter().map(String::as_str)),
            }
        }
        result.sort_unstable();
        result.dedup();
        result
    }

    pub(super) fn is_grid(&self) -> bool {
        self.layout == Layout::Grid
    }
    pub(super) fn has_items(&self) -> bool {
        !self.items.is_empty()
    }

    fn label<'a>(&'a self, package: &'a str) -> &'a str {
        self.apps
            .iter()
            .find(|app| app.package == package)
            .map(|app| app.name.as_str())
            .unwrap_or(package)
    }
}

impl Ui {
    pub(crate) fn watches_home(&self) -> bool {
        matches!(self.page, Page::Home | Page::HomeFolder(_))
    }

    pub(crate) fn set_home(&mut self, home: HomeLayout, icons: HashMap<String, RgbaImage>) {
        let previous_folder = match self.page {
            Page::HomeFolder(i) => match self.home.items.get(i) {
                Some(Item::Folder { id, .. }) => Some(id.as_str()),
                _ => None,
            },
            _ => None,
        };
        if let Some(id) = previous_folder {
            self.page = home
                .items
                .iter()
                .position(|item| matches!(item, Item::Folder { id: saved, .. } if saved == id))
                .map(Page::HomeFolder)
                .unwrap_or(Page::Home);
        }
        if self.page == Page::Home && self.focus == 0 && !self.home.has_items() && home.has_items()
        {
            self.focus = FIRST_TILE;
        }
        self.home = home;
        self.home_icons = icons;
        self.pictures
            .retain(|(key, _, _), _| !key.starts_with("app:"));
        let size = self.home_page_size();
        let len = self.home_count();
        self.home_offset = self.home_offset.min(len.saturating_sub(1)) / size * size;
        if self.watches_home() && self.focus >= FIRST_TILE && self.focus - FIRST_TILE >= len {
            self.focus = FIRST_TILE + self.home_offset;
        }
    }

    fn home_page_size(&self) -> usize {
        if matches!(self.page, Page::HomeFolder(_)) || self.home.layout == Layout::Grid {
            2 * COLUMNS
        } else {
            COLUMNS
        }
    }

    fn home_count(&self) -> usize {
        if let Page::HomeFolder(i) = self.page {
            return match self.home.items.get(i) {
                Some(Item::Folder { apps, .. }) => apps.len(),
                _ => 0,
            };
        }
        self.home.items.len()
    }

    pub(super) fn home_key(&mut self, key: Key) -> bool {
        if !self.watches_home() || self.focus < FIRST_TILE {
            return false;
        }
        let index = self.focus - FIRST_TILE;
        let next = match key {
            Key::Right if index + 1 < self.home_count() => index + 1,
            Key::Left if index > 0 => index - 1,
            _ => return false,
        };
        self.focus = FIRST_TILE + next;
        self.home_offset = next / self.home_page_size() * self.home_page_size();
        true
    }

    pub(super) fn turn_home(&mut self, direction: isize) {
        let size = self.home_page_size();
        let next = self
            .home_offset
            .saturating_add_signed(direction * size as isize);
        if next < self.home_count() {
            self.home_offset = next;
            self.focus = FIRST_TILE + next;
        }
    }

    pub(super) fn close_folder(&mut self, index: usize) {
        self.go(Page::Home);
        let size = self.home_page_size();
        self.home_offset = index / size * size;
        self.focus = FIRST_TILE + index;
    }

    pub(super) fn draw_home_apps(&mut self, c: &mut Pixmap) {
        let folder = matches!(self.page, Page::HomeFolder(_));
        let grid = self.home_page_size() > COLUMNS;
        let count = self.home_count();
        let first = self.home_offset;
        let end = (first + self.home_page_size()).min(count);
        let title = match self.page {
            Page::HomeFolder(i) => match self.home.items.get(i) {
                Some(Item::Folder { name, .. }) => name.as_str(),
                _ => "Ordner",
            },
            _ => "Meine Apps",
        };
        let title = self.ellipsis(title, 30, 810.);
        self.text(c, &title, 64., 135., 30, WHITE, false);
        if count > self.home_page_size() {
            let label = format!(
                "{} / {}",
                first / self.home_page_size() + 1,
                count.div_ceil(self.home_page_size())
            );
            self.text(c, &label, 1058., 134., 17, SOFT, false);
            if first > 0 {
                self.button(
                    c,
                    830,
                    "‹",
                    [994., 104., 49., 42.],
                    Command::HomeTurn(-1),
                    false,
                );
            }
            if end < count {
                self.button(
                    c,
                    831,
                    "›",
                    [1167., 104., 49., 42.],
                    Command::HomeTurn(1),
                    false,
                );
            }
        }
        if count == 0 && folder {
            self.text(c, "Dieser Ordner ist leer.", 64., 289., 22, SOFT, false);
        }
        for index in first..end {
            let slot = index - first;
            let item = if let Page::HomeFolder(i) = self.page {
                match &self.home.items[i] {
                    Item::Folder { apps, .. } => Item::App {
                        package: apps[index].clone(),
                    },
                    _ => continue,
                }
            } else {
                self.home.items[index].clone()
            };
            let rect = [
                64. + (slot % COLUMNS) as f32 * 292.,
                163. + (slot / COLUMNS) as f32 * 137.,
                276.,
                if grid { 124. } else { 258. },
            ];
            let (label, cmd) = match &item {
                Item::App { package } => (
                    self.home.label(package).to_owned(),
                    Command::LaunchApp(package.clone()),
                ),
                Item::Folder { name, .. } => (name.clone(), Command::Page(Page::HomeFolder(index))),
            };
            let id = FIRST_TILE + index;
            let [x, y, w, h] = rect;
            self.control(id, rect, cmd);
            fill(
                c,
                rect,
                21.,
                if matches!(item, Item::Folder { .. }) {
                    [48, 44, 68, 233]
                } else {
                    [29, 38, 55, 235]
                },
            );
            let art_size = if grid { 54. } else { 86. };
            let art_x = x + 21.;
            let art_y = y + if grid { 13. } else { 38. };
            match &item {
                Item::App { package } => self.draw_home_icon(c, package, art_x, art_y, art_size),
                Item::Folder { apps, .. } => {
                    fill(
                        c,
                        [art_x, art_y, art_size, art_size],
                        10.,
                        [106, 99, 133, 255],
                    );
                    let small = (art_size - 12.) / 2.;
                    for (p, package) in apps.iter().take(4).enumerate() {
                        self.draw_home_icon(
                            c,
                            package,
                            art_x + 4. + (p % 2) as f32 * (small + 4.),
                            art_y + 4. + (p / 2) as f32 * (small + 4.),
                            small,
                        );
                    }
                }
            }
            let name = self.ellipsis(&label, 22, w - 40.);
            self.text(c, &name, x + 20., y + h - 18., 22, WHITE, false);
            if self.focus == id {
                stroke(c, rect, 21., ACCENT, 3.5);
            } else {
                stroke(c, rect, 21., [234, 228, 251, 54], 1.);
            }
        }
        if let Page::HomeFolder(i) = self.page {
            self.button(
                c,
                4,
                "Ordner schließen",
                [470., 499., 340., 58.],
                Command::CloseFolder(i),
                false,
            );
        } else {
            for (i, (label, command)) in [
                ("Live-TV", Command::LiveTv),
                ("Mediatheken", Command::Page(Page::Library)),
                ("YouTube", Command::Page(Page::Youtube)),
            ]
            .into_iter()
            .enumerate()
            {
                self.button(
                    c,
                    i,
                    label,
                    [250. + i as f32 * 264., 445., 252., 48.],
                    command,
                    false,
                );
            }
        }
    }

    fn draw_home_icon(&mut self, c: &mut Pixmap, package: &str, x: f32, y: f32, side: f32) {
        let key = format!("app:{package}");
        if self.home_icons.contains_key(&key) {
            self.picture(c, &key, [x, y, side, side], side / 6.);
        } else {
            let first = self
                .home
                .label(package)
                .chars()
                .next()
                .unwrap_or('?')
                .to_uppercase()
                .to_string();
            fill(c, [x, y, side, side], side / 5., [76, 83, 108, 255]);
            self.centered(c, &first, [x, y, side, side], (side * 0.55) as u16, WHITE);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn layout(mode: &str, apps: usize) -> HomeLayout {
        let mut items = vec![
            json!({"type":"folder","id":"evening","name":"Am Abend","apps":["org.example.tv"]}),
        ];
        items.extend(
            (0..apps).map(|i| json!({"type":"app","package":format!("org.example.app{i}")})),
        );
        serde_json::from_value(json!({"layout":mode,"items":items,"apps":[{"package":"org.example.tv","name":"Fernsehen"}]})).unwrap()
    }

    #[test]
    fn phone_home_folder_launch_and_back_preserve_context() {
        let mut ui = Ui::new();
        ui.set_home(layout("row", 5), HashMap::new());
        ui.render(W, H);
        assert_eq!(ui.focus, FIRST_TILE);
        assert!(matches!(ui.key(Key::Select), Action::None));
        assert_eq!(ui.page, Page::HomeFolder(0));
        ui.render(W, H);
        assert!(
            matches!(ui.key(Key::Select), Action::LaunchApp(package) if package=="org.example.tv")
        );
        ui.key(Key::Back);
        assert_eq!((ui.page, ui.focus), (Page::Home, FIRST_TILE));
        for _ in 0..4 {
            ui.key(Key::Right);
        }
        ui.render(W, H);
        assert_eq!(ui.home_offset, 4);
        assert!(
            matches!(ui.key(Key::Select), Action::LaunchApp(package) if package=="org.example.app3")
        );
    }

    #[test]
    fn grid_pages_and_folder_edits_from_another_device() {
        let mut ui = Ui::new();
        ui.set_home(layout("grid", 11), HashMap::new());
        ui.render(W, H);
        for _ in 0..8 {
            ui.key(Key::Right);
        }
        assert_eq!((ui.home_offset, ui.focus), (8, FIRST_TILE + 8));
        ui.key(Key::Left);
        assert_eq!((ui.home_offset, ui.focus), (0, FIRST_TILE + 7));
        ui.go(Page::HomeFolder(0));
        let mut reordered = layout("row", 5);
        reordered.items.rotate_left(1);
        ui.set_home(reordered, HashMap::new());
        assert_eq!(ui.page, Page::HomeFolder(5));
        ui.key(Key::Back);
        assert_eq!(
            (ui.page, ui.home_offset, ui.focus),
            (Page::Home, 4, FIRST_TILE + 5)
        );
        ui.go(Page::HomeFolder(5));
        ui.set_home(HomeLayout::default(), HashMap::new());
        ui.render(W, H);
        assert_eq!((ui.page, ui.focus), (Page::Home, 0));
    }
}
