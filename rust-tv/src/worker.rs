//! Catalogue work never holds up the Activity input loop. Only the newest queued navigation is fetched.
use std::{
    collections::{HashMap, HashSet, VecDeque},
    sync::{Arc, Condvar, Mutex, mpsc},
    thread,
    time::{Duration, Instant},
};

use android_activity::AndroidAppWaker;

use crate::{
    catalog::{self, CatalogItem},
    net,
    ui::Ui,
    youtube::{self, YoutubeVideo},
};

pub enum Job {
    Youtube(u64),
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
pub enum Reply {
    Youtube(u64, Result<Vec<YoutubeVideo>, String>),
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
    queue: Arc<(Mutex<Queue>, Condvar)>,
    sender: mpsc::Sender<Reply>,
    pub replies: mpsc::Receiver<Reply>,
    waker: AndroidAppWaker,
    active_images: HashSet<String>,
    failed_images: HashMap<String, Instant>,
}

fn respond(sender: &mpsc::Sender<Reply>, waker: &AndroidAppWaker, reply: Reply) {
    if sender.send(reply).is_ok() {
        waker.wake();
    }
}

impl Worker {
    pub fn new(waker: AndroidAppWaker) -> Self {
        let (sender, replies) = mpsc::channel();
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
                        let found = if let Some((_, rows)) = youtube_cache
                            .as_ref()
                            .filter(|(at, _)| at.elapsed() < freshness)
                        {
                            Ok(rows.clone())
                        } else {
                            let rows = youtube::catalog(|url| net::get(url, 512 * 1024));
                            if let Ok(rows) = &rows {
                                youtube_cache = Some((Instant::now(), rows.clone()));
                            }
                            rows
                        };
                        Reply::Youtube(request, found)
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
            queue,
            sender,
            replies,
            waker,
            active_images: HashSet::new(),
            failed_images: HashMap::new(),
        }
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
