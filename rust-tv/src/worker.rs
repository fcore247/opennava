//! Catalogue work never holds up the Activity input loop. Only the newest queued navigation is fetched.
use std::{
    collections::{HashMap, HashSet, VecDeque},
    sync::{Arc, Condvar, Mutex, mpsc},
    thread,
    time::{Duration, Instant},
};

use android_activity::{AndroidApp, AndroidAppWaker};

use crate::{
    catalog::{self, CatalogItem, CatalogPage},
    net,
    ui::{Ui, home::HomeLayout},
    youtube::{self, YoutubePage, YoutubeVideo},
};

pub enum Job {
    Youtube(u64),
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
    Catalog {
        request: u64,
        provider: usize,
        query: String,
    },
    Resolve {
        request: u64,
        item: CatalogItem,
    },
}
#[derive(Clone, Copy)]
pub enum SearchScope {
    Youtube,
    Main(usize),
}

pub enum Reply {
    Home(HomeLayout, HashMap<String, image::RgbaImage>),
    CombinedSearch(
        u64,
        Result<CatalogPage, String>,
        Result<YoutubePage, String>,
    ),
    SearchMore(
        u64,
        Result<CatalogPage, String>,
        Result<YoutubePage, String>,
    ),
    Youtube(u64, Result<YoutubePage, String>),
    YoutubeMore(u64, Result<YoutubePage, String>),
    Catalog(u64, usize, Result<Vec<CatalogItem>, String>),
    Stream(u64, String, Result<String, String>),
    Image(String, Result<Vec<u8>, String>),
}

#[derive(Default)]
struct Queue {
    pending: Option<Job>,
    stop: bool,
}

pub struct Worker {
    home: mpsc::Sender<bool>,
    queue: Arc<(Mutex<Queue>, Condvar)>,
    sender: mpsc::Sender<Reply>,
    pub replies: mpsc::Receiver<Reply>,
    waker: AndroidAppWaker,
    search: mpsc::Sender<(u64, String, SearchScope)>,
    active_images: HashSet<String>,
    failed_images: HashMap<String, Instant>,
}

fn respond(sender: &mpsc::Sender<Reply>, waker: &AndroidAppWaker, reply: Reply) {
    if sender.send(reply).is_ok() {
        waker.wake();
    }
}

/// Query broadcaster APIs independently of the account-aware YouTube client.
fn mixed_page(
    app: &AndroidApp,
    providers: u8,
    query: &str,
    page: u32,
    youtube_request: Option<(&str, bool)>,
) -> (Result<CatalogPage, String>, Result<YoutubePage, String>) {
    let Some((value, continuation)) = youtube_request else {
        return (
            catalog::search_page(providers, query, page),
            Ok(YoutubePage::default()),
        );
    };
    thread::scope(|threads| {
        let broadcasters = threads.spawn(|| catalog::search_page(providers, query, page));
        let youtube =
            crate::android::native_youtube(app, value, continuation).map(Option::unwrap_or_default);
        let broadcasts = broadcasters
            .join()
            .unwrap_or_else(|_| Err("Die Mediatheken antworten nicht.".into()));
        (broadcasts, youtube)
    })
}

fn search_reply(app: &AndroidApp, request: u64, query: &str, scope: SearchScope) -> Reply {
    match scope {
        SearchScope::Youtube => Reply::Youtube(
            request,
            crate::android::native_youtube(app, query, false).map(Option::unwrap_or_default),
        ),
        SearchScope::Main(provider) => {
            let mask = if provider == 0 {
                7
            } else {
                1 << (provider - 1)
            };
            let youtube = (provider == 0).then_some((query, false));
            let (shows, videos) = mixed_page(app, mask, query, 0, youtube);
            Reply::CombinedSearch(request, shows, videos)
        }
    }
}

impl Worker {
    pub fn new(app: AndroidApp) -> Self {
        let waker = app.create_waker();
        let (sender, replies) = mpsc::channel();

        // Search is independent of the home feed. Coalesce remote-control typing while the
        // user is entering a word and skip queries superseded during an in-flight request.
        let (search, queries) = mpsc::channel::<(u64, String, SearchScope)>();
        let search_app = app.clone();
        let search_results = sender.clone();
        let search_wake = waker.clone();
        thread::spawn(move || {
            while let Ok((mut request, mut query, mut scope)) = queries.recv() {
                loop {
                    match queries.recv_timeout(Duration::from_millis(380)) {
                        Ok(next) => (request, query, scope) = next,
                        Err(mpsc::RecvTimeoutError::Timeout) => break,
                        Err(mpsc::RecvTimeoutError::Disconnected) => return,
                    }
                }
                let reply = search_reply(&search_app, request, &query, scope);
                respond(&search_results, &search_wake, reply);
            }
        });

        // Even a slow package-manager or companion-provider query must not delay D-pad input.
        let (home, home_checks) = mpsc::channel::<bool>();
        let home_app = app.clone();
        let home_results = sender.clone();
        let home_wake = waker.clone();
        thread::spawn(move || {
            let mut revision = String::new();
            while let Ok(force) = home_checks.recv() {
                let force = home_checks.try_iter().fold(force, |a, b| a | b);
                if let Some((updated, reply)) =
                    crate::android::refresh_home(&home_app, &revision, force)
                {
                    revision = updated;
                    respond(&home_results, &home_wake, reply);
                }
            }
        });

        let queue = Arc::new((Mutex::new(Queue::default()), Condvar::new()));
        let pending = Arc::clone(&queue);
        let results = sender.clone();
        let wake = waker.clone();
        thread::spawn(move || {
            let (lock, changed) = &*pending;
            let mut youtube_cache: Option<(Instant, Vec<YoutubeVideo>)> = None;
            let mut catalogs: VecDeque<(usize, String, Instant, Vec<CatalogItem>)> =
                VecDeque::new();
            let freshness = Duration::from_secs(5 * 60);
            loop {
                let mut state = changed
                    .wait_while(lock.lock().unwrap(), |s| !s.stop && s.pending.is_none())
                    .unwrap();
                if state.stop {
                    break;
                }
                let job = state.pending.take().unwrap();
                drop(state);
                let reply = match job {
                    Job::Youtube(request) => {
                        let found = match crate::android::native_youtube(&app, "", false) {
                            Ok(Some(items)) => Ok(items),
                            Err(error) => Err(error),
                            Ok(None) => {
                                if let Some((_, rows)) = youtube_cache
                                    .as_ref()
                                    .filter(|(at, _)| at.elapsed() < freshness)
                                {
                                    Ok(rows.clone().into())
                                } else {
                                    let rows = youtube::catalog(|url| net::get(url, 512 * 1024));
                                    if let Ok(rows) = &rows {
                                        youtube_cache = Some((Instant::now(), rows.clone()));
                                    }
                                    rows.map(Into::into)
                                }
                            }
                        };
                        Reply::Youtube(request, found)
                    }
                    Job::YoutubeMore { request, cursor } => {
                        let result = crate::android::native_youtube(&app, &cursor, true)
                            .map(Option::unwrap_or_default);
                        Reply::YoutubeMore(request, result)
                    }
                    Job::MainSearch {
                        request,
                        provider,
                        query,
                    } => search_reply(&app, request, &query, SearchScope::Main(provider)),
                    Job::SearchMore {
                        request,
                        query,
                        providers,
                        page,
                        youtube_cursor,
                    } => {
                        let youtube = youtube_cursor.as_deref().map(|cursor| (cursor, true));
                        let (shows, videos) = mixed_page(&app, providers, &query, page, youtube);
                        Reply::SearchMore(request, shows, videos)
                    }
                    Job::Catalog {
                        request,
                        provider,
                        query,
                    } => {
                        let found = if let Some((_, _, _, rows)) =
                            catalogs.iter().find(|(p, q, at, _)| {
                                *p == provider && q == &query && at.elapsed() < freshness
                            }) {
                            Ok(rows.clone())
                        } else {
                            let rows = catalog::fetch(provider, &query);
                            if let Ok(rows) = &rows {
                                if catalogs.len() >= 8 {
                                    catalogs.pop_front();
                                }
                                catalogs.push_back((provider, query, Instant::now(), rows.clone()));
                            }
                            rows
                        };
                        Reply::Catalog(request, provider, found)
                    }
                    Job::Resolve { request, item } => {
                        let url = catalog::resolve(&item);
                        Reply::Stream(request, item.title, url)
                    }
                };
                respond(&results, &wake, reply);
            }
        });
        Self {
            home,
            queue,
            sender,
            replies,
            waker,
            search,
            active_images: HashSet::new(),
            failed_images: HashMap::new(),
        }
    }

    pub fn refresh_home(&self, force: bool) {
        let _ = self.home.send(force);
    }

    pub fn search(&self, request: u64, query: String, scope: SearchScope) {
        let _ = self.search.send((request, query, scope));
    }
    pub fn submit(&self, job: Job) {
        let (lock, changed) = &*self.queue;
        lock.lock().unwrap().pending = Some(job);
        changed.notify_one();
    }
    pub fn request_images(&mut self, ui: &Ui) {
        self.failed_images
            .retain(|_, at| at.elapsed() < Duration::from_secs(120));
        for url in ui.wanted_images() {
            if self.active_images.len() >= 3 {
                break;
            }
            if url.len() > 4096
                || !url.starts_with("https://")
                || self.failed_images.contains_key(&url)
                || !self.active_images.insert(url.clone())
            {
                continue;
            }
            let sender = self.sender.clone();
            let waker = self.waker.clone();
            thread::spawn(move || {
                let result = net::get(&url, 3 * 1024 * 1024);
                respond(&sender, &waker, Reply::Image(url, result));
            });
        }
    }

    pub fn image_result(
        &mut self,
        ui: &mut Ui,
        url: String,
        result: Result<Vec<u8>, String>,
    ) -> bool {
        self.active_images.remove(&url);
        if !ui.wanted_images().contains(&url) {
            return false;
        }
        if let Ok(bytes) = result {
            ui.image_loaded(url.clone(), bytes);
            if !ui.wanted_images().contains(&url) {
                return true;
            }
        }
        self.failed_images.insert(url, Instant::now());
        false
    }
}
impl Drop for Worker {
    fn drop(&mut self) {
        let (lock, changed) = &*self.queue;
        lock.lock().unwrap().stop = true;
        changed.notify_one();
    }
}
