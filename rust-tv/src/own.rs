//! The viewer's own media libraries: opennava.mediathek/1 JSON documents or video RSS/Atom feeds.
//! The companion (or the viewer's agent) writes libraries.json; see guide/MEDIATHEK_FORMAT.md.
use crate::catalog::{CatalogItem, OWN};
use quick_xml::{Reader, XmlVersion, events::Event};
use serde::Deserialize;
use serde_json::Value;
use std::{collections::HashSet, path::Path};
use url::Url;

const ITEM_LIMIT: usize = 400;
const LIBRARY_LIMIT: usize = 20;
const DOCUMENT_BYTES: usize = 4 * 1024 * 1024;

#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize)]
#[serde(default)]
pub struct Library {
    pub id: String,
    pub name: String,
    pub color: String,
    pub url: String,
    pub document: String,
}

pub fn registry(dir: &Path) -> Vec<Library> {
    let Ok(bytes) = std::fs::read(dir.join("libraries.json")) else {
        return Vec::new();
    };
    let mut libraries: Vec<Library> = serde_json::from_slice(&bytes).unwrap_or_default();
    libraries.retain(|library| !library.id.is_empty() && !library.name.is_empty());
    libraries.truncate(LIBRARY_LIMIT);
    libraries
}

/// A plain web address, otherwise nothing.
pub fn web(value: &str) -> Option<String> {
    let value = value.trim();
    if value.len() > 1500 {
        return None;
    }
    let url = Url::parse(value).ok()?;
    (matches!(url.scheme(), "https" | "http")
        && url.host_str().is_some()
        && url.username().is_empty()
        && url.password().is_none())
    .then(|| value.to_owned())
}

fn clean(value: &str, limit: usize) -> String {
    value
        .split(|c: char| c.is_whitespace() || c.is_control())
        .filter(|word| !word.is_empty())
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .take(limit)
        .collect()
}

#[derive(Default)]
struct Entry {
    title: String,
    url: String,
    subtitle: String,
    description: String,
    image: String,
}

fn items(library: &str, entries: Vec<Entry>) -> Vec<CatalogItem> {
    let mut seen = HashSet::new();
    entries
        .into_iter()
        .filter_map(|entry| {
            let title = clean(&entry.title, 220);
            let url = web(&entry.url)?;
            if title.is_empty() || !seen.insert(url.clone()) {
                return None;
            }
            let subtitle = if entry.subtitle.trim().is_empty() {
                &entry.description
            } else {
                &entry.subtitle
            };
            Some(CatalogItem {
                id: url,
                title,
                provider: OWN,
                subtitle: clean(subtitle, 450),
                thumbnail: web(&entry.image).unwrap_or_default(),
                source: library.to_owned(),
            })
        })
        .take(ITEM_LIMIT)
        .collect()
}

fn from_json(text: &str) -> Result<Vec<Entry>, String> {
    let root: Value =
        serde_json::from_str(text).map_err(|_| "Die Mediathek ist kein gültiges JSON.")?;
    if root["format"] != "opennava.mediathek/1" {
        return Err("Unbekanntes Mediathek-Format.".into());
    }
    let word = |value: &Value, key: &str| value[key].as_str().unwrap_or_default().to_owned();
    let mut entries = Vec::new();
    for section in root["sections"].as_array().into_iter().flatten() {
        let heading = word(section, "title");
        for row in section["items"].as_array().into_iter().flatten() {
            let mut subtitle = word(row, "subtitle");
            if subtitle.is_empty() {
                subtitle = heading.clone();
            }
            entries.push(Entry {
                title: word(row, "title"),
                url: word(row, "url"),
                subtitle,
                description: word(row, "description"),
                image: word(row, "image"),
            });
        }
    }
    Ok(entries)
}

fn from_feed(text: &str) -> Result<Vec<Entry>, String> {
    let invalid = || "Das ist weder eine OpenNava-Mediathek noch ein Video-Feed.".to_owned();
    if text.contains("<!DOCTYPE") || text.contains("<!ENTITY") {
        return Err(invalid());
    }
    let mut reader = Reader::from_str(text);
    let mut entries = Vec::new();
    let mut entry: Option<Entry> = None;
    let mut field = String::new();
    let mut value = String::new();
    loop {
        match reader.read_event().map_err(|_| invalid())? {
            Event::Start(tag) | Event::Empty(tag) => {
                let name = tag.name().as_ref().to_string();
                if matches!(name.as_str(), "item" | "entry") {
                    entry = Some(Entry::default());
                } else if let Some(current) = entry.as_mut() {
                    let mut attributes = std::collections::HashMap::new();
                    for attribute in tag.attributes().flatten() {
                        let key = attribute.key.as_ref().to_string();
                        let text = attribute
                            .normalized_value(XmlVersion::Implicit1_0)
                            .map(|v| v.into_owned())
                            .unwrap_or_default();
                        attributes.insert(key, text);
                    }
                    let get = |key: &str| attributes.get(key).cloned().unwrap_or_default();
                    let kind = get("type").to_ascii_lowercase();
                    let video = kind.starts_with("video/")
                        || kind.contains("mpegurl")
                        || get("medium") == "video";
                    if matches!(name.as_str(), "enclosure" | "media:content")
                        && video
                        && current.url.is_empty()
                    {
                        current.url = get("url");
                    }
                    if current.image.is_empty() {
                        if name == "media:thumbnail"
                            || name == "media:content" && kind.starts_with("image/")
                        {
                            current.image = get("url");
                        } else if name == "itunes:image" {
                            current.image = get("href");
                        }
                    }
                    field = name;
                    value.clear();
                }
            }
            Event::Text(text) => value.push_str(&text),
            Event::CData(text) => value.push_str(&text),
            Event::GeneralRef(reference) => {
                let name: &str = &reference;
                if let Ok(Some(character)) = reference.resolve_char_ref() {
                    value.push(character);
                } else if let Some(text) = quick_xml::escape::resolve_predefined_entity(name) {
                    value.push_str(text);
                }
            }
            Event::End(tag) => {
                let name = tag.name().as_ref().to_string();
                if matches!(name.as_str(), "item" | "entry") {
                    entries.extend(entry.take());
                } else if let Some(current) = entry.as_mut()
                    && name == field
                {
                    match name.as_str() {
                        "title" => current.title = value.clone(),
                        "description" | "media:description" | "summary"
                            if current.description.is_empty() =>
                        {
                            current.description = strip_markup(&value)
                        }
                        _ => {}
                    }
                    field.clear();
                }
                value.clear();
            }
            Event::Eof => break,
            _ => {}
        }
    }
    Ok(entries)
}

fn strip_markup(text: &str) -> String {
    let mut inside = false;
    text.chars()
        .filter(|c| match c {
            '<' => {
                inside = true;
                false
            }
            '>' => {
                inside = false;
                false
            }
            _ => !inside,
        })
        .collect()
}

pub fn parse(library: &str, text: &str) -> Result<Vec<CatalogItem>, String> {
    let text = text.trim_start_matches('\u{feff}').trim();
    let entries = if text.starts_with('{') {
        from_json(text)?
    } else {
        from_feed(text)?
    };
    let found = items(library, entries);
    if found.is_empty() {
        return Err("Diese Mediathek enthält keine abspielbaren Videos.".into());
    }
    Ok(found)
}

/// Call off the UI thread.
pub fn fetch(library: &Library) -> Result<Vec<CatalogItem>, String> {
    if !library.document.is_empty() {
        return parse(&library.name, &library.document);
    }
    let url = web(&library.url).ok_or("Ungültige Adresse der Mediathek.")?;
    let bytes = crate::net::get_own(&url, DOCUMENT_BYTES)?;
    parse(&library.name, &String::from_utf8_lossy(&bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_opennava_document_and_drops_unplayable_rows() {
        let found = parse(
            "Meine",
            r#"{"format":"opennava.mediathek/1","name":"Meine","sections":[{"title":"Neu","items":[
            {"title":"Eins","url":"https://example.org/a.m3u8","image":"https://example.org/a.jpg"},
            {"title":"Doppelt","url":"https://example.org/a.m3u8"},
            {"title":"Datei","url":"file:///sdcard/x.mp4"},
            {"title":"","url":"https://example.org/b.mp4"}]}]}"#,
        )
        .unwrap();
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].subtitle, "Neu");
        assert_eq!(found[0].source, "Meine");
        assert_eq!(found[0].provider, OWN);
    }

    #[test]
    fn reads_video_rss_with_entities_and_thumbnails() {
        let found = parse(
            "Feed",
            r#"<?xml version="1.0"?><rss><channel><title>Feed</title><item>
            <title>Natur &amp; Tiere</title><description><![CDATA[<p>Ein Film</p>]]></description>
            <enclosure url="https://example.org/f.mp4?a=1&amp;b=2" type="video/mp4"/>
            <media:thumbnail url="https://example.org/f.jpg"/></item>
            <item><title>Nur Ton</title><enclosure url="https://example.org/a.mp3" type="audio/mpeg"/></item>
            </channel></rss>"#,
        )
        .unwrap();
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].title, "Natur & Tiere");
        assert_eq!(found[0].id, "https://example.org/f.mp4?a=1&b=2");
        assert_eq!(found[0].subtitle, "Ein Film");
        assert_eq!(found[0].thumbnail, "https://example.org/f.jpg");
    }

    #[test]
    fn rejects_documents_without_video_and_entity_tricks() {
        assert!(parse("x", r#"{"format":"other"}"#).is_err());
        assert!(parse("x", "<!DOCTYPE a [<!ENTITY b \"c\">]><rss/>").is_err());
    }
}
