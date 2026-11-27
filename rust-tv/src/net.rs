//! HTTPS transport for public catalogue and cover requests, with bounded time, redirects and response size.
use std::sync::OnceLock;
use std::time::Duration;

fn agent() -> &'static ureq::Agent {
    static AGENT: OnceLock<ureq::Agent> = OnceLock::new();
    AGENT.get_or_init(|| {
        ureq::Agent::config_builder()
            .https_only(true)
            .max_redirects(3)
            .timeout_connect(Some(Duration::from_secs(5)))
            .timeout_global(Some(Duration::from_secs(12)))
            .build()
            .into()
    })
}

fn bytes(
    response: Result<ureq::http::Response<ureq::Body>, ureq::Error>,
    limit: usize,
) -> Result<Vec<u8>, String> {
    response
        .map_err(|error| match error {
            ureq::Error::StatusCode(status) => format!("Der Anbieter antwortet mit HTTP {status}."),
            _ => "Der Anbieter ist gerade nicht erreichbar.".to_owned(),
        })?
        .body_mut()
        .with_config()
        .limit(limit as u64)
        .read_to_vec()
        .map_err(|_| "Die Antwort des Anbieters konnte nicht gelesen werden.".to_owned())
}
pub fn get(url: &str, limit: usize) -> Result<Vec<u8>, String> {
    bytes(
        agent()
            .get(url)
            .header(
                "User-Agent",
                concat!("OpenNava-Rust/", env!("CARGO_PKG_VERSION")),
            )
            .call(),
        limit,
    )
}

/// The viewer's own library addresses may use plain HTTP, for example on a home server.
pub fn get_own(url: &str, limit: usize) -> Result<Vec<u8>, String> {
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .max_redirects(3)
        .timeout_connect(Some(Duration::from_secs(5)))
        .timeout_global(Some(Duration::from_secs(15)))
        .build()
        .into();
    bytes(
        agent
            .get(url)
            .header(
                "User-Agent",
                concat!("OpenNava-Rust/", env!("CARGO_PKG_VERSION")),
            )
            .call(),
        limit,
    )
}
