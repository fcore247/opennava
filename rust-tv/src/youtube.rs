//! Video metadata from the native YouTube service and the public publisher feeds.
use serde::Deserialize;
use std::collections::HashSet;

const CHANNELS: &[&str] = &[
    "UC5NOEUbkLheQcaaRldYW5GA", // tagesschau
    "UCeqKIgPQfNInOswGRWt48kQ", // ZDFheute Nachrichten
    "UCknLrEdhRCp1aegoMqRaCZg", // DW News
    "UCLA_DiR1FfKNvjuUpBHmylQ", // NASA
];
const MAX_FEED_BYTES: usize = 512 * 1024;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct YoutubeVideo {
    pub id: String,
    pub title: String,
    pub channel: String,
    pub published: String,
    pub thumbnail: String,
}

#[derive(Default, Debug, PartialEq, Eq)]
pub struct YoutubePage {
    pub videos: Vec<YoutubeVideo>,
    pub next: Option<String>,
}
impl From<Vec<YoutubeVideo>> for YoutubePage {
    fn from(videos: Vec<YoutubeVideo>) -> Self {
        Self { videos, next: None }
    }
}

/// An explicitly anonymous, empty home response uses the publisher feeds.
pub fn native_videos(json: &str, home: bool) -> Result<Option<YoutubePage>, String> {
    #[derive(Deserialize)]
    struct Item {
        id: String,
        title: String,
        #[serde(default)]
        author: String,
        #[serde(default)]
        thumbnail: String,
    }
    #[derive(Deserialize)]
    struct List {
        #[serde(default)]
        items: Vec<Item>,
        #[serde(rename = "signedIn")]
        signed_in: Option<bool>,
        error: Option<String>,
        next: Option<String>,
    }

    let list: List =
        serde_json::from_str(json).map_err(|_| "YouTube hat ungültige Daten geliefert.")?;
    if home && list.signed_in == Some(false) && list.items.is_empty() {
        return Ok(None);
    }
    if list.error.is_some() {
        return Err("YouTube konnte die Videos nicht laden.".into());
    }
    let mut seen = HashSet::new();
    let videos = list
        .items
        .into_iter()
        .filter(|item| {
            valid_id(&item.id) && !item.title.trim().is_empty() && seen.insert(item.id.clone())
        })
        .take(180)
        .map(|item| YoutubeVideo {
            thumbnail: if item.thumbnail.starts_with("https://") {
                item.thumbnail
            } else {
                format!("https://i.ytimg.com/vi/{}/hqdefault.jpg", item.id)
            },
            id: item.id,
            title: item.title.trim().chars().take(256).collect(),
            channel: item.author.trim().chars().take(120).collect(),
            published: String::new(),
        })
        .collect();
    Ok(Some(YoutubePage {
        videos,
        next: list
            .next
            .filter(|value| !value.is_empty() && value.len() <= 1024),
    }))
}

pub fn valid_id(id: &str) -> bool {
    id.len() == 11
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

#[derive(Deserialize)]
struct Feed {
    title: String,
    #[serde(rename = "entry", default)]
    entries: Vec<Entry>,
}

#[derive(Deserialize)]
struct Entry {
    #[serde(rename = "videoId", default)]
    id: String,
    #[serde(rename = "channelId", default)]
    channel: String,
    title: String,
    #[serde(default)]
    published: String,
}

fn parse_feed(bytes: &[u8], channel_id: &str) -> Result<Vec<YoutubeVideo>, String> {
    if bytes.len() > MAX_FEED_BYTES {
        return Err("Der YouTube-Kanal ist zu groß.".into());
    }
    let feed: Feed = quick_xml::de::from_reader(bytes)
        .map_err(|_| "Die Kanalliste von YouTube ist nicht lesbar.")?;
    let channel = feed.title.trim();
    Ok(feed
        .entries
        .into_iter()
        .filter_map(|entry| {
            let title = entry.title.split_whitespace().collect::<Vec<_>>().join(" ");
            if !valid_id(&entry.id) || entry.channel != channel_id || title.is_empty() {
                return None;
            }
            Some(YoutubeVideo {
                thumbnail: format!("https://i.ytimg.com/vi/{}/hqdefault.jpg", entry.id),
                id: entry.id,
                title: title.chars().take(256).collect(),
                channel: channel.chars().take(120).collect(),
                published: entry.published.chars().take(40).collect(),
            })
        })
        .take(15)
        .collect())
}

/// Fetch independent publishers concurrently. A temporarily unavailable channel need not hide the others.
pub fn catalog(
    fetch: impl Fn(&str) -> Result<Vec<u8>, String> + Sync,
) -> Result<Vec<YoutubeVideo>, String> {
    let mut videos = std::thread::scope(|scope| {
        let workers: Vec<_> = CHANNELS
            .iter()
            .map(|&id| {
                let get = &fetch;
                scope.spawn(move || {
                    let address =
                        format!("https://www.youtube.com/feeds/videos.xml?channel_id={id}");
                    parse_feed(&get(&address)?, id)
                })
            })
            .collect();
        let mut videos = Vec::new();
        let mut succeeded = false;
        for worker in workers {
            if let Ok(Ok(mut found)) = worker.join() {
                succeeded = true;
                videos.append(&mut found);
            }
        }
        if succeeded {
            Ok(videos)
        } else {
            Err("YouTube-Kanäle konnten nicht geladen werden.".to_owned())
        }
    })?;
    videos.sort_by(|a, b| b.published.cmp(&a.published).then_with(|| a.id.cmp(&b.id)));
    let mut seen = HashSet::new();
    videos.retain(|item| seen.insert(item.id.clone()));
    Ok(videos)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_an_explicit_anonymous_home_response_uses_publisher_feeds() {
        assert_eq!(
            native_videos(r#"{"items":[],"signedIn":false}"#, true).unwrap(),
            None
        );
        assert_eq!(
            native_videos(r#"{"items":[],"signedIn":false}"#, false).unwrap(),
            Some(YoutubePage::default())
        );
        assert!(native_videos(r#"{"error":"denied","signedIn":true}"#, true).is_err());
        let list = r#"{"items":[{"id":"abcdefghijk","title":"Moon","author":"Apollo"},{"id":"abcdefghijk","title":"duplicate"},{"id":"bad","title":"invalid"}]}"#;
        let videos = native_videos(list, false).unwrap().unwrap().videos;
        assert_eq!(videos.len(), 1);
        assert_eq!(videos[0].channel, "Apollo");
    }

    #[test]
    fn atom_namespaces_and_titles_match_the_publisher() {
        let xml = r#"<feed xmlns="http://www.w3.org/2005/Atom" xmlns:yt="http://www.youtube.com/xml/schemas/2015">
        <title>Testkanal</title><entry><yt:videoId>abcdefghij_</yt:videoId><yt:channelId>channel</yt:channelId>
        <title>A &amp; B</title><published>2026-11-20T12:00:00Z</published></entry></feed>"#;
        let parsed = parse_feed(xml.as_bytes(), "channel").unwrap();
        assert_eq!(parsed[0].title, "A & B");
        assert_eq!(parsed[0].channel, "Testkanal");
        assert!(
            parse_feed(xml.as_bytes(), "someone-else")
                .unwrap()
                .is_empty()
        );
        assert!(!valid_id("../watch?id"));
    }
}
