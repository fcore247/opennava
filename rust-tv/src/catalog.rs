//! Playable programme catalogues from the German ARD, ZDF and ARTE editorial APIs.
//! The persisted ID is a provider detail URL. Stream URLs are requested at playback time.
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashSet;
use url::Url;

const ARD: &str = "https://api.ardmediathek.de";
const ZDF: &str = "https://zdf-prod-futura.zdf.de/mediathekV2";
const ARTE: &str = "https://api.arte.tv/api/emac/v4/de/web";
const ARTE_PLAYER: &str = "https://api.arte.tv/api/player/v2/config/de/";
const LIMIT: usize = 30;
const MAX_JSON_BYTES: usize = 8 * 1024 * 1024;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogItem {
    pub id: String,
    pub title: String,
    pub provider: usize,
    pub subtitle: String,
    pub thumbnail: String,
    /// The name of the viewer's own library; empty for the built-in broadcasters.
    #[serde(default)]
    pub source: String,
}

/// Provider number of items from the viewer's own libraries. Their ID is the playable address.
pub const OWN: usize = 5;

#[derive(Debug, Default)]
pub struct CatalogPage {
    pub items: Vec<CatalogItem>,
    /// Bit 0 is ARD, bit 1 is ZDF, bit 2 is ARTE.
    pub remaining: u8,
}

fn json(url: &str) -> Result<Value, String> {
    let bytes = crate::net::get(url, MAX_JSON_BYTES)?;
    serde_json::from_slice(&bytes)
        .map_err(|_| "Der Sender hat ungültige Katalogdaten geliefert.".into())
}

fn text<'a>(v: &'a Value, key: &str) -> &'a str {
    v[key].as_str().unwrap_or_default().trim()
}
fn rows<'a>(v: &'a Value, key: &str) -> impl Iterator<Item = &'a Value> {
    v[key].as_array().into_iter().flatten()
}
fn uri(value: &str) -> Option<Url> {
    let url = Url::parse(value).ok()?;
    (url.scheme() == "https"
        && url.username().is_empty()
        && url.password().is_none()
        && url.port().is_none()
        && url.fragment().is_none()
        && url.host_str().is_some()
        && !value.contains('\\')
        && value.len() <= 8192)
        .then_some(url)
}
fn valid_detail(provider: usize, value: &str) -> bool {
    let Some(url) = uri(value) else { return false };
    let path = url.path();
    match (provider, url.host_str().unwrap_or_default()) {
        (1, "api.ardmediathek.de") => {
            path.starts_with("/page-gateway/pages/") && path.contains("/item/")
        }
        (2, "zdf-prod-futura.zdf.de") => {
            path.starts_with("/mediathekV2/document/")
                && path.len() > "/mediathekV2/document/".len()
        }
        (3, "api.arte.tv") => value.strip_prefix(ARTE_PLAYER).is_some_and(|id| {
            let b = id.as_bytes();
            b.len() == 12
                && b[..6].iter().all(u8::is_ascii_digit)
                && b[6] == b'-'
                && b[7..10].iter().all(u8::is_ascii_digit)
                && b[10] == b'-'
                && b[11].is_ascii_uppercase()
        }),
        _ => false,
    }
}
fn thumbnail(value: &str) -> String {
    let Some(u) = uri(value) else {
        return String::new();
    };
    if matches!(
        u.host_str(),
        Some(
            "api.ardmediathek.de"
                | "www.zdf.de"
                | "www.zdfheute.de"
                | "epg-image.zdf.de"
                | "api-cdn.arte.tv"
        )
    ) {
        value.to_owned()
    } else {
        String::new()
    }
}
fn episode(
    provider: usize,
    detail: &str,
    title: &str,
    subtitle: &str,
    image: &str,
) -> Option<CatalogItem> {
    if title.is_empty() || !valid_detail(provider, detail) {
        return None;
    }
    Some(CatalogItem {
        id: detail.to_owned(),
        title: title.chars().take(220).collect(),
        provider,
        subtitle: subtitle.chars().take(450).collect(),
        thumbnail: thumbnail(image),
        source: String::new(),
    })
}
fn ard_item(t: &Value) -> Option<CatalogItem> {
    if !matches!(text(t, "type"), "ondemand" | "poster")
        || text(t, "coreAssetType").contains("LIVESTREAM")
    {
        return None;
    }
    let title = ["longTitle", "mediumTitle", "shortTitle"]
        .into_iter()
        .map(|k| text(t, k))
        .find(|s| !s.is_empty())?;
    episode(
        1,
        text(&t["links"]["target"], "href"),
        title,
        text(&t["show"], "title"),
        &text(&t["images"]["aspect16x9"], "src").replace("{width}", "384"),
    )
}
fn zdf_item(t: &Value) -> Option<CatalogItem> {
    if text(t, "type") != "video"
        || text(t, "currentVideoType")
            .to_ascii_lowercase()
            .contains("live")
        || !text(t, "channel").to_ascii_uppercase().starts_with("ZDF")
    {
        return None;
    }
    let image = ["384", "768"]
        .into_iter()
        .map(|k| text(&t["teaserBild"][k], "url"))
        .find(|s| !s.is_empty())
        .unwrap_or_default();
    episode(
        2,
        text(t, "url"),
        text(t, "titel"),
        text(t, "brandTitle"),
        image,
    )
}
fn arte_item(t: &Value) -> Option<CatalogItem> {
    let availability = &t["availability"];
    if t["kind"]["isCollection"].as_bool() != Some(false)
        || text(availability, "type") != "VOD"
        || availability["hasVideoStreams"].as_bool() != Some(true)
    {
        return None;
    }
    let title = text(t, "title");
    if title.is_empty() {
        return None;
    }
    let subtitle = text(t, "subtitle");
    let title = if !subtitle.is_empty() && !title.eq_ignore_ascii_case(subtitle) {
        format!("{title} – {subtitle}")
    } else {
        title.to_owned()
    };
    episode(
        3,
        &format!("{ARTE_PLAYER}{}", text(t, "programId")),
        &title,
        text(t, "shortDescription"),
        &text(&t["mainImage"], "url").replace("__SIZE__", "384x216"),
    )
}
fn query_url(endpoint: &str, params: &[(&str, &str)]) -> String {
    Url::parse_with_params(endpoint, params)
        .expect("static broadcaster URL")
        .to_string()
}
fn distinct(items: impl IntoIterator<Item = CatalogItem>) -> Vec<CatalogItem> {
    let mut seen = HashSet::new();
    items
        .into_iter()
        .filter(|i| seen.insert(i.id.clone()))
        .collect()
}

fn fetch_ard(query: &str, page: u32) -> Result<CatalogPage, String> {
    let url = if query.is_empty() {
        format!("{ARD}/page-gateway/pages/ard/home")
    } else {
        query_url(
            &format!("{ARD}/search-system/search/vods/ard"),
            &[
                ("query", query),
                ("pageNumber", &page.to_string()),
                ("pageSize", "30"),
                ("platform", "MEDIA_THEK"),
                ("sortingCriteria", "SCORE_DESC"),
            ],
        )
    };
    let data = json(&url)?;
    if !data["teasers"].is_array() && !data["widgets"].is_array() {
        return Err("Das ARD-Katalogformat ist unbekannt.".into());
    }
    let pagination = &data["pagination"];
    let size = pagination["pageSize"].as_u64().unwrap_or(0);
    let total = pagination["totalElements"].as_u64().unwrap_or(0);
    let has_more =
        !query.is_empty() && size > 0 && (u64::from(page) + 1).saturating_mul(size) < total;
    Ok(CatalogPage {
        remaining: u8::from(has_more),
        items: distinct(
            rows(&data, "teasers")
                .chain(rows(&data, "widgets").flat_map(|v| rows(v, "teasers")))
                .filter_map(ard_item),
        ),
    })
}
fn fetch_zdf(query: &str, page: u32) -> Result<CatalogPage, String> {
    let url = if query.is_empty() {
        format!("{ZDF}/start-page")
    } else {
        query_url(
            &format!("{ZDF}/search"),
            &[
                ("q", query),
                ("page", &(u64::from(page) + 1).to_string()),
                ("synth", "true"),
            ],
        )
    };
    let data = json(&url)?;
    if !data["cluster"].is_array() && !data["results"].is_array() {
        return Err("Das ZDF-Katalogformat ist unbekannt.".into());
    }
    let has_more = !query.is_empty() && data["nextPage"].as_bool() == Some(true);
    Ok(CatalogPage {
        remaining: if has_more { 2 } else { 0 },
        items: distinct(
            rows(&data, "results")
                .chain(rows(&data, "cluster").flat_map(|v| rows(v, "teaser")))
                .filter_map(zdf_item),
        ),
    })
}

fn fetch_arte(query: &str, page: u32) -> Result<CatalogPage, String> {
    let url = if query.is_empty() {
        format!("{ARTE}/pages/home?bypassESI=true")
    } else {
        query_url(
            &format!("{ARTE}/search/results"),
            &[
                ("query", query),
                ("page", &(u64::from(page) + 1).to_string()),
            ],
        )
    };
    let data = json(&url)?;
    let zones = data["zones"]
        .as_array()
        .ok_or("Das ARTE-Katalogformat ist unbekannt.")?;
    let requested = u64::from(page) + 1;
    let current_zones = || {
        zones
            .iter()
            .filter(|z| page == 0 || z["content"]["pagination"]["page"].as_u64() == Some(requested))
    };
    let mut matches: Vec<_> = current_zones()
        .flat_map(|z| rows(&z["content"], "data"))
        .filter_map(arte_item)
        .collect();
    if !query.is_empty() || !matches.is_empty() {
        let has_more = !query.is_empty()
            && current_zones()
                .any(|z| z["content"]["pagination"]["pages"].as_u64().unwrap_or(0) > requested);
        return Ok(CatalogPage {
            items: distinct(matches),
            remaining: if has_more { 4 } else { 0 },
        });
    }
    // On home, editorial zones are lazy-loaded by the broadcaster. Do not request personalised zones.
    let mut zone_error = None;
    for zone in zones
        .iter()
        .filter(|z| {
            matches!(
                text(z, "code"),
                "incontournables" | "films" | "documentaires" | "top_teaser"
            )
        })
        .take(4)
    {
        let href = text(&zone["content"], "esiInclude");
        let Some(url) = uri(href) else {
            continue;
        };
        if url.host_str() != Some("api.arte.tv")
            || !url.path().starts_with("/api/emac/v4/de/web/zones/")
            || !url.path().ends_with("/content")
        {
            continue;
        }
        match json(href) {
            Ok(content) => matches.extend(rows(&content, "data").filter_map(arte_item)),
            Err(e) => zone_error = Some(e),
        }
        if matches.len() >= 12 {
            break;
        }
    }
    if matches.is_empty() {
        return Err(zone_error
            .unwrap_or_else(|| "ARTE liefert aktuell keine abspielbaren Empfehlungen.".into()));
    }
    Ok(CatalogPage {
        items: distinct(matches),
        remaining: 0,
    })
}

/// Fetch the requested publishers concurrently. Each result is a full provider page so no
/// available episodes are dropped when the user advances past the visible row.
pub fn search_page(providers: u8, query: &str, page: u32) -> Result<CatalogPage, String> {
    let query = query.trim();
    if providers == 0 || providers & !7 != 0 {
        return Err("Unbekannte Mediathek.".into());
    }
    if query.chars().count() > 300 {
        return Err("Der Suchbegriff ist zu lang.".into());
    }
    if query.is_empty() && page != 0 {
        return Ok(CatalogPage::default());
    }
    let results = std::thread::scope(|scope| {
        let mut jobs = Vec::new();
        if providers & 1 != 0 {
            jobs.push(scope.spawn(|| fetch_ard(query, page)));
        }
        if providers & 2 != 0 {
            jobs.push(scope.spawn(|| fetch_zdf(query, page)));
        }
        if providers & 4 != 0 {
            jobs.push(scope.spawn(|| fetch_arte(query, page)));
        }
        jobs.into_iter()
            .map(|job| {
                job.join()
                    .unwrap_or_else(|_| Err("Der Sender antwortet nicht.".into()))
            })
            .collect::<Vec<_>>()
    });
    let mut result = CatalogPage::default();
    let mut groups = Vec::new();
    let mut error = None;
    for response in results {
        match response {
            Ok(page) => {
                result.remaining |= page.remaining;
                groups.push(page.items.into_iter());
            }
            Err(reason) => error = Some(reason),
        }
    }
    loop {
        let before = result.items.len();
        result
            .items
            .extend(groups.iter_mut().filter_map(Iterator::next));
        if result.items.len() == before {
            break;
        }
    }
    if result.items.is_empty()
        && result.remaining == 0
        && let Some(reason) = error
    {
        return Err(reason);
    }
    Ok(result)
}

/// The launcher channel tiles show a limited selection from the first page.
pub fn fetch(provider: usize, query: &str) -> Result<Vec<CatalogItem>, String> {
    let mask = match provider {
        0 => 7,
        1..=3 => 1 << (provider - 1),
        _ => return Err("Unbekannte Mediathek.".into()),
    };
    let mut items = search_page(mask, query, 0)?.items;
    items.truncate(LIMIT);
    Ok(items)
}

fn trusted_stream(provider: usize, url: &str) -> bool {
    let Some(parsed) = uri(url) else {
        return false;
    };
    let host = parsed.host_str().unwrap_or_default();
    let domains: &[&str] = match provider {
        1 => &[
            "ard-mcdn.de",
            "tagesschau.de",
            "daserste.de",
            "br.de",
            "ndr.de",
            "swr.de",
            "ard.de",
            "mdr.de",
        ],
        2 => &["zdf.de"],
        _ => &[],
    };
    if domains.iter().any(|domain| {
        host == *domain
            || host
                .strip_suffix(domain)
                .is_some_and(|prefix| prefix.ends_with('.'))
    }) {
        return true;
    }
    match provider {
        1 => matches!(
            host,
            "wdradaptiv-vh.akamaihd.net"
                | "rbhlslive.akamaized.net"
                | "brevent.akamaized.net"
                | "dwamdstream102.akamaized.net"
                | "br-i.akamaihd.net"
                | "srod-vh.akamaihd.net"
                | "srstorage01-a.akamaihd.net"
                | "pdvideosdaserste-a.akamaihd.net"
        ),
        2 => matches!(
            host,
            "zdfvod.akamaized.net"
                | "zdfvod-rwrtr.akamaized.net"
                | "nrodlzdf-a.akamaihd.net"
                | "rodlzdf-a.akamaihd.net"
        ),
        3 => matches!(
            host,
            "manifest-arte.akamaized.net" | "arte-cmafhls.akamaized.net"
        ),
        _ => false,
    }
}
fn stream_score(provider: usize, v: &Value) -> Option<i64> {
    let href = text(v, "url");
    if !trusted_stream(provider, href) {
        return None;
    }
    let mime = text(v, "mimeType").to_ascii_lowercase();
    let kind = if mime.contains("mpegurl") {
        10000
    } else if mime == "video/mp4" {
        1000
    } else {
        return None;
    };
    let quality = v["maxVResolutionPx"].as_i64().unwrap_or(0).clamp(0, 2160)
        + if text(v, "quality") == "auto" {
            3000
        } else if v["hd"].as_bool() == Some(true) {
            300
        } else {
            0
        };
    Some(kind + quality)
}
fn parse_stream(item: &CatalogItem, data: &Value) -> Result<String, String> {
    let stream = match item.provider {
        1 => {
            if rows(data, "widgets").any(|w| w["blockedByFsk"].as_bool() == Some(true)) {
                return Err("Diese Sendung ist vom Sender altersbeschränkt.".into());
            }
            rows(data, "widgets")
                .flat_map(|w| rows(&w["mediaCollection"]["embedded"], "streams"))
                .filter(|s| matches!(text(s, "kind"), "" | "main"))
                .flat_map(|s| rows(s, "media"))
                .filter_map(|m| Some((stream_score(1, m)?, text(m, "url"))))
                .max_by_key(|(rank, _)| *rank)
                .map(|(_, v)| v)
        }
        2 => {
            let doc = &data["document"];
            if text(doc, "type") != "video" {
                return Err("Der Titel ist nicht mehr als Video verfügbar.".into());
            }
            rows(doc, "formitaeten")
                .filter(|f| {
                    matches!(text(f, "class"), "" | "main")
                        && matches!(text(f, "language"), "" | "deu")
                })
                .filter_map(|f| Some((stream_score(2, f)?, text(f, "url"))))
                .max_by_key(|(rank, _)| *rank)
                .map(|(_, v)| v)
        }
        3 => {
            let cfg = &data["data"]["attributes"];
            if text(&data["data"], "type") != "ConfigPlayer"
                || text(cfg, "provider") != "arte"
                || cfg["live"].as_bool() != Some(false)
                || item.id.strip_prefix(ARTE_PLAYER) != Some(text(&cfg["metadata"], "providerId"))
                || cfg["restriction"]["geoblocking"]["restrictedArea"].as_bool() == Some(true)
            {
                return Err("ARTE gibt diese Sendung aktuell nicht zur Wiedergabe frei.".into());
            }
            rows(cfg, "streams")
                .filter(|s| {
                    text(s, "protocol").contains("HLS") && trusted_stream(3, text(s, "url"))
                })
                .max_by_key(|s| {
                    rows(s, "versions").any(|v| {
                        text(v, "audioLanguage") == "de"
                            && v["audioDescription"].as_bool() != Some(true)
                    })
                })
                .map(|v| text(v, "url"))
        }
        _ => None,
    };
    stream.map(str::to_owned).ok_or_else(|| {
        "Für diese Sendung liefert der Sender derzeit keinen abspielbaren Stream.".into()
    })
}
pub fn resolve(item: &CatalogItem) -> Result<String, String> {
    if item.provider == OWN {
        return crate::own::web(&item.id).ok_or_else(|| "Ungültige Videoadresse.".into());
    }
    if !valid_detail(item.provider, &item.id) {
        return Err("Ungültige Sendungsadresse.".into());
    }
    parse_stream(item, &json(&item.id)?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn ard_selects_main_hls_and_rejects_untrusted_saved_details() {
        let mut item = episode(
            1,
            "https://api.ardmediathek.de/page-gateway/pages/ard/item/abc",
            "Folge",
            "Serie",
            "",
        )
        .unwrap();
        let stream = "https://hr-vod.ard-mcdn.de/program/master.m3u8";
        let detail = json!({"widgets":[{"mediaCollection":{"embedded":{"streams":[{"kind":"main","media":[
            {"mimeType":"application/x-mpegURL","url":stream},{"mimeType":"video/mp4","url":"https://hr-vod.ard-mcdn.de/clip.mp4","maxVResolutionPx":1080}
        ]}]}}}]});
        assert_eq!(parse_stream(&item, &detail).unwrap(), stream);
        item.id = "https://example.org/page-gateway/pages/ard/item/abc".into();
        assert!(resolve(&item).is_err());
    }
    #[test]
    fn zdf_drops_shows_and_partner_rebroadcasts() {
        let mut row = json!({"type":"video","channel":"ZDF","titel":"heute","url":"https://zdf-prod-futura.zdf.de/mediathekV2/document/heute"});
        assert!(zdf_item(&row).is_some());
        row["type"] = json!("brand");
        assert!(zdf_item(&row).is_none());
        row["type"] = json!("video");
        row["channel"] = json!("ARD");
        assert!(zdf_item(&row).is_none());
    }
    #[test]
    #[ignore = "live broadcaster pagination"]
    fn live_search_pages_add_distinct_playable_episodes() {
        for mask in [1, 2] {
            let first = search_page(mask, "Natur", 0).unwrap();
            assert_eq!(first.remaining, mask);
            let initial: HashSet<_> = first.items.iter().map(|i| &i.id).collect();
            assert!(!initial.is_empty());
            let mut remaining = first.remaining;
            // A ZDF page can contain only partner programmes, which are filtered locally.
            let found = (1..=4).any(|page| {
                if remaining == 0 {
                    return false;
                }
                let next = search_page(remaining, "Natur", page).unwrap();
                remaining = next.remaining;
                next.items.iter().any(|i| !initial.contains(&i.id))
            });
            assert!(found, "no new playable episode for provider mask {mask}");
        }
    }
    #[test]
    #[ignore = "live broadcaster APIs"]
    fn live_catalogues_and_streams() {
        for (provider, query) in [(1, "Tatort"), (2, "heute"), (3, "Kunst")] {
            for q in ["", query] {
                let items = fetch(provider, q).unwrap();
                assert!(!items.is_empty(), "provider={provider}, query={q}");
                let mut available = None;
                for i in items.iter().take(4) {
                    if let Ok(url) = resolve(i) {
                        available = Some((i, url));
                        break;
                    }
                }
                let (item, url) = available.expect("a playable current episode");
                println!(
                    "provider={provider} query={q:?}: {} titles, {:?} ({})",
                    items.len(),
                    item.title,
                    Url::parse(&url).unwrap().host_str().unwrap()
                );
            }
        }
    }
}
