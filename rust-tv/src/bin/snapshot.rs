use opennava_rust::{
    catalog, net,
    ui::{Action, Key, Ui},
    youtube,
};

// Drive the desktop preview through the same inputs and callbacks as the TV.
fn load(ui: &mut Ui, action: Action) {
    match action {
        Action::Catalog {
            request,
            provider,
            query,
        } => ui.catalog_loaded(request, catalog::fetch(provider, &query)),
        Action::MainSearch {
            request,
            provider,
            query,
        } => {
            let mask = if provider == 0 {
                7
            } else {
                1 << (provider - 1)
            };
            ui.combined_search_loaded(
                request,
                catalog::search_page(mask, &query, 0),
                Ok(Default::default()),
            );
        }
        Action::YoutubeCatalog { request } => {
            ui.youtube_loaded(
                request,
                youtube::catalog(|url| net::get(url, 512 * 1024)).map(Into::into),
            );
        }
        _ => {}
    }
    for url in ui.wanted_images() {
        if let Ok(bytes) = net::get(&url, 3 * 1024 * 1024) {
            ui.image_loaded(url, bytes);
        }
    }
}
fn main() {
    let dir = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "target/previews".into());
    std::fs::create_dir_all(&dir).unwrap();
    let mut ui = Ui::new();
    save(&mut ui, &dir, "home");
    ui.key(Key::Down);
    let action = ui.key(Key::Select);
    load(&mut ui, action);
    save(&mut ui, &dir, "search");
    let mut action = Action::None;
    for ch in "bares".chars() {
        action = ui.key(Key::Character(ch));
    }
    load(&mut ui, action);
    save(&mut ui, &dir, "search-bares");
    ui.key(Key::Back);
    ui.render(1280, 720);
    ui.key(Key::Right);
    ui.key(Key::Select);
    save(&mut ui, &dir, "library");
    ui.tap(890.0, 660.0, 1280, 720);
    save(&mut ui, &dir, "settings");
    ui.key(Key::Back);
    ui.render(1280, 720);
    ui.key(Key::Back);
    save(&mut ui, &dir, "exit");
}
fn save(ui: &mut Ui, dir: &str, name: &str) {
    let data = ui.render(1280, 720);
    image::save_buffer(
        format!("{dir}/{name}.png"),
        &data,
        1280,
        720,
        image::ColorType::Rgba8,
    )
    .unwrap();
}
