//! A single public post in, a static snapshot or a structured refusal out.
mod card;
mod links;
mod net;
#[cfg(windows)]
mod system_dns;
mod x;

use serde::{Deserialize, Serialize};
use std::{fs, io::Read, path::Path, time::Duration};
use url::Url;

const RESOLVE_TIMEOUT: Duration = Duration::from_secs(16);

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Refusal {
    Permalink,
    Unsupported,
    Unavailable,
    ProtectionOff,
}

#[derive(Serialize)]
pub struct Snapshot {
    pub site: &'static str,
    pub title: Option<String>,
    pub text: Option<String>,
    pub image: Option<String>,
    pub image_alt: Option<String>,
}

#[derive(Deserialize)]
struct Saved {
    paused_until: u64,
    profile: Option<String>,
}

fn small_file(dir: &Path, name: &str) -> Option<String> {
    let mut text = String::new();
    fs::File::open(dir.join(name))
        .ok()?
        .take(8192)
        .read_to_string(&mut text)
        .ok()?;
    Some(text)
}

fn app_online(dir: &Path) -> bool {
    let now = crate::now_ms();
    small_file(dir, "browser/app-seen")
        .and_then(|s| s.parse::<u64>().ok())
        .is_some_and(|at| at <= now && now - at < 5000)
}

fn active_profile(dir: &Path) -> Option<String> {
    if !app_online(dir) {
        return None;
    }
    let state: Saved = serde_json::from_str(&small_file(dir, "state.json")?).ok()?;
    if state.paused_until != 0 {
        return None;
    }
    crate::configured_nextdns_profile(dir, state.profile.as_deref()?)
}

fn protected(dir: &Path, profile: &str) -> bool {
    active_profile(dir).as_deref() == Some(profile)
}

async fn target(
    raw: &str,
    profile: &str,
    protected: &mut impl FnMut() -> bool,
    system_check: impl Fn(&str) -> bool,
) -> Result<(Url, links::PostLink), Refusal> {
    if !protected() {
        return Err(Refusal::ProtectionOff);
    }
    let mut url = links::unwrap(raw).ok_or(Refusal::Unsupported)?;
    for _ in 0..3 {
        if !links::is_shortener(&url) {
            break;
        }
        let short = links::short_link(url).ok_or(Refusal::Permalink)?;
        let next = net::short_redirect(&short, profile)
            .await
            .ok_or(Refusal::Unavailable)?;
        url = links::unwrap(next.as_str()).ok_or(Refusal::Unsupported)?;
    }
    let link = links::permalink(url.clone())?;
    let host = url.host_str().ok_or(Refusal::Unsupported)?;
    let canonical = link.url.host_str().ok_or(Refusal::Unsupported)?;
    if !net::blocked(host, profile).await
        || !system_check(host)
        || (host != canonical
            && (!net::blocked(canonical, profile).await || !system_check(canonical)))
    {
        return Err(Refusal::Unavailable);
    }
    if !protected() {
        return Err(Refusal::ProtectionOff);
    }
    url.set_query(None);
    Ok((url, link))
}

async fn follow_short(raw: &str, dir: &Path) -> Option<Url> {
    let profile = active_profile(dir)?;
    let short = links::short_link(links::unwrap(raw)?)?;
    tokio::time::timeout(
        Duration::from_secs(8),
        target(
            short.as_str(),
            &profile,
            &mut || protected(dir, &profile),
            |_| true,
        ),
    )
    .await
    .ok()?
    .ok()
    .map(|(url, _)| url)
}

/// Shared action for callers that have one candidate URL. This action checks
/// the active profile before it opens any unfiltered connection.
pub async fn post(raw: &str, dir: &Path) -> Result<Snapshot, Refusal> {
    let profile = active_profile(dir).ok_or(Refusal::ProtectionOff)?;
    let snapshot = fetch_post(raw, &profile, || protected(dir, &profile), |_| true).await?;
    let _ = crate::peek_history::record(dir, crate::now_ms());
    Ok(snapshot)
}

/// Android checks its Private DNS configuration in the activity. The native
/// fetcher independently requires the selected NextDNS profile to block the host.
#[cfg(target_os = "android")]
pub fn phone_post(
    raw: &str,
    profile: &str,
    protected: impl FnMut() -> bool,
) -> Result<Snapshot, Refusal> {
    run(fetch_post(raw, profile, protected, |_| true)).unwrap_or(Err(Refusal::Unavailable))
}

/// Desktop paste runs in a stateless worker. The parent checks its live DNS state.
pub fn worker_post(raw: &str, profile: &str, protected: impl FnMut() -> bool) -> Result<Snapshot, Refusal> {
    #[cfg(windows)]
    let check = system_dns::blocked;
    #[cfg(not(windows))]
    let check = |_: &str| true;
    let profile = crate::normalize_nextdns_profile(profile).ok_or(Refusal::ProtectionOff)?;
    run(fetch_post(raw, &profile, protected, check)).unwrap_or(Err(Refusal::Unavailable))
}

async fn fetch_post(
    raw: &str,
    profile: &str,
    mut protected: impl FnMut() -> bool,
    system_check: impl Fn(&str) -> bool,
) -> Result<Snapshot, Refusal> {
    let snapshot = tokio::time::timeout(RESOLVE_TIMEOUT, async {
        let (_, link) = target(raw, profile, &mut protected, system_check).await?;
        let transport = net::Transport::new().ok_or(Refusal::Unavailable)?;
        match link.network {
            links::Network::X => x::resolve(&transport, &link).await,
            links::Network::Facebook => card::facebook(&transport, &link).await,
            _ => Err(Refusal::Unsupported),
        }
    })
    .await
    .map_err(|_| Refusal::Unavailable)??;
    if !protected() {
        return Err(Refusal::ProtectionOff);
    }
    Ok(snapshot)
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
enum Request {
    PeekCheck { version: u8, host: String },
    PeekFollow { version: u8, url: String },
    PeekPost { version: u8, url: String },
    PeekPause { version: u8 },
}

/// Called on a dedicated one-shot browser port so media I/O doesn't delay capture acks.
pub fn native(frame: &[u8], dir: &Path) -> Option<serde_json::Value> {
    use serde_json::json;
    let request = serde_json::from_slice::<Request>(frame).ok()?;
    let reply = match request {
        Request::PeekCheck { version: 1, host } => {
            let blocked = active_profile(dir).is_some_and(|profile| {
                run(net::blocked(&host, &profile)).unwrap_or(false) && protected(dir, &profile)
            });
            json!({ "blocked": blocked })
        }
        Request::PeekFollow { version: 1, url } => {
            json!({ "url": run(follow_short(&url, dir)).flatten().map(|url| url.to_string()) })
        }
        Request::PeekPost { version: 1, url } => {
            match run(post(&url, dir)).unwrap_or(Err(Refusal::Unavailable)) {
                Ok(snapshot) => json!({ "snapshot": snapshot }),
                Err(refusal) => json!({ "refusal": refusal }),
            }
        }
        Request::PeekPause { version: 1 } => {
            json!({ "opened": app_online(dir) && crate::peek_history::request_pause(dir, crate::now_ms()).is_ok() })
        }
        _ => json!({}),
    };
    Some(json!({ "type": "peek_reply", "version": 1, "result": reply }))
}

fn run<T>(future: impl std::future::Future<Output = T>) -> Option<T> {
    Some(
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .ok()?
            .block_on(future),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unwraps_known_redirects_before_classifying() {
        let post = "https://www.facebook.com/shop/posts/-men%C3%BA-del-d%C3%ADa-/1234567890123456/?tracking=secret";
        let wrapped = format!(
            "https://l.facebook.com/l.php?u={}",
            url::form_urlencoded::byte_serialize(post.as_bytes()).collect::<String>()
        );
        let google = format!(
            "https://www.google.com/url?ved=tracker&url={}",
            url::form_urlencoded::byte_serialize(wrapped.as_bytes()).collect::<String>()
        );
        let link = links::permalink(links::unwrap(&google).unwrap()).unwrap();
        assert_eq!(
            link.url.as_str(),
            "https://www.facebook.com/shop/posts/-men%C3%BA-del-d%C3%ADa-/1234567890123456/"
        );
        assert!(
            links::unwrap("https://google.com.invalid/url?url=https://x.com/post/status/20")
                .is_none()
        );
        assert!(
            links::unwrap("https://l.facebook.com/l.php?u=https%3A%2F%2F127.0.0.1%2F").is_none()
        );
        assert!(
            links::short_link(links::unwrap("https://t.co/HNYDOs?ignored=1").unwrap()).is_some()
        );
        assert!(links::short_link(links::unwrap("https://t.co/anything/else").unwrap()).is_none());
        for raw in [
            "https://search.app/short?tracker=1",
            "https://share.google/short?tracker=1",
        ] {
            let link = links::short_link(links::unwrap(raw).unwrap()).unwrap();
            assert_eq!(link.path(), "/short");
            assert!(link.query().is_none());
        }
        let hop = links::unwrap("https://www.google.com/share.google?q=short&tracker=1").unwrap();
        assert_eq!(
            links::short_link(hop).unwrap().as_str(),
            "https://www.google.com/share.google?q=short"
        );
        for raw in [
            "https://www.google.com/share.google?q=one&q=two",
            "https://www.google.com/share.google?q=https%3A%2F%2F127.0.0.1",
            "https://www.google.com/search?q=short",
            "https://google.com.invalid/share.google?q=short",
            "https://search.app/one/two",
        ] {
            assert!(
                links::unwrap(raw).and_then(links::short_link).is_none(),
                "{raw}"
            );
        }
    }

    #[test]
    fn only_supported_permalinks_have_a_fetch_target() {
        for raw in [
            "https://x.com/handle/status/20/photo/1?token=secret",
            "https://twitter.com/i/web/status/20",
            "https://www.facebook.com/sample-garden/posts/pfbidExample123ABC",
            "https://m.facebook.com/reel/123456",
        ] {
            assert!(
                links::unwrap(raw)
                    .and_then(|url| links::permalink(url).ok())
                    .is_some(),
                "{raw}"
            );
        }
        for raw in [
            "https://x.com/",
            "https://x.com/explore",
            "https://x.com/handle",
            "https://x.com/search?q=food",
            "https://x.com/u/status/20/replies",
            "https://x.com/status/status/20",
            "https://x.com/st-at/status/20",
            "https://x.com/u/status/20%2Fprofile",
            "https://user:pass@x.com/i/status/20",
            "https://x.com:8443/u/status/20",
            "https://x.com.example.org/u/status/20",
            "https://facebook.com/search/top",
            "https://www.facebook.com/shop/posts/%2e%2e%2fprofile/123/",
            "https://www.facebook.com/shop",
            "https://www.instagram.com/p/CHJ9-j",
            "https://www.reddit.com/r/test/comments/123/test",
        ] {
            assert!(
                links::unwrap(raw)
                    .and_then(|url| links::permalink(url).ok())
                    .is_none(),
                "{raw}"
            );
        }
        let link =
            links::permalink(links::unwrap("https://x.com/handle/status/20").unwrap()).unwrap();
        assert!(!link.matches(&Url::parse("https://x.com/handle/status/21").unwrap()));
        assert!(!link.matches(&Url::parse("https://x.com/settings").unwrap()));
    }

    #[test]
    fn old_or_missing_state_fails_closed_without_network() {
        let dir = std::env::temp_dir().join("focus-peek-missing-app-state");
        let reply = native(
            br#"{"type":"peek_post","version":1,"url":"https://x.com/jack/status/20"}"#,
            &dir,
        )
        .unwrap();
        assert_eq!(reply["result"]["refusal"], "protection_off");
    }
}
