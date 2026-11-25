use super::Refusal;
use url::Url;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Network {
    X,
    Facebook,
    Unsupported,
    Short,
}

pub fn network(host: &str) -> Option<Network> {
    use Network::*;
    match host {
        "x.com" | "www.x.com" | "mobile.x.com" | "twitter.com" | "www.twitter.com"
        | "mobile.twitter.com" => Some(X),
        "facebook.com" | "www.facebook.com" | "m.facebook.com" | "l.facebook.com"
        | "lm.facebook.com" => Some(Facebook),
        "t.co" | "fb.me" | "search.app" | "share.google" => Some(Short),
        "instagram.com" | "www.instagram.com" | "threads.com" | "www.threads.com"
        | "threads.net" | "www.threads.net" | "reddit.com" | "www.reddit.com"
        | "old.reddit.com" | "redd.it" | "tiktok.com" | "www.tiktok.com" => Some(Unsupported),
        _ => None,
    }
}

#[derive(Debug)]
pub struct PostLink {
    pub network: Network,
    pub id: String,
    pub url: Url,
}

impl PostLink {
    pub fn matches(&self, url: &Url) -> bool {
        permalink(url.clone())
            .is_ok_and(|other| other.network == self.network && other.id == self.id)
    }
}

pub fn parse_url(raw: &str) -> Option<Url> {
    if raw.len() > 4096 || raw.chars().any(|c| c.is_control() || c == '\\') {
        return None;
    }
    let mut url = Url::parse(raw).ok()?;
    if !matches!(url.scheme(), "http" | "https")
        || url.port().is_some()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return None;
    }
    url.set_fragment(None);
    Some(url)
}

fn google(host: &str) -> bool {
    matches!(
        host.strip_prefix("www.").unwrap_or(host),
        "google.com"
            | "google.cl"
            | "google.com.pa"
            | "google.co.uk"
            | "google.com.au"
            | "google.ca"
            | "google.co.in"
            | "google.de"
            | "google.fr"
            | "google.es"
            | "google.com.mx"
            | "google.com.br"
    )
}

pub fn unwrap(raw: &str) -> Option<Url> {
    let mut url = parse_url(raw)?;
    for depth in 0..=4 {
        let host = url.host_str()?;
        let keys: &[&str] = match (host, url.path()) {
            ("l.facebook.com" | "lm.facebook.com", "/l.php") => &["u"],
            (host, "/url") if google(host) => &["url", "q"],
            _ => return (network(host).is_some() || google_share_hop(&url)).then_some(url),
        };
        if depth == 4 {
            return None;
        }
        let next = url
            .query_pairs()
            .find(|(key, _)| keys.contains(&key.as_ref()))?
            .1;
        url = parse_url(&next)?;
    }
    None
}

fn google_share_hop(url: &Url) -> bool {
    matches!(url.host_str(), Some("google.com" | "www.google.com")) && url.path() == "/share.google"
}

pub fn is_shortener(url: &Url) -> bool {
    network(url.host_str().unwrap_or_default()) == Some(Network::Short) || google_share_hop(url)
}

fn token(s: &str, max: usize) -> bool {
    !s.is_empty()
        && s.len() <= max
        && s.bytes()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'_' | b'-'))
}

fn number(s: &str) -> bool {
    !s.is_empty() && s.len() <= 24 && s.bytes().all(|c| c.is_ascii_digit())
}

fn x_handle(s: &str) -> bool {
    !s.eq_ignore_ascii_case("status")
        && !s.eq_ignore_ascii_case("statuses")
        && !s.is_empty()
        && s.len() <= 15
        && s.bytes().all(|c| c == b'_' || c.is_ascii_alphanumeric())
}

fn facebook_slug(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 512
        && percent_encoding::percent_decode_str(s)
            .decode_utf8()
            .is_ok_and(|decoded| {
                !matches!(decoded.as_ref(), "." | "..")
                    && decoded
                        .chars()
                        .all(|c| c.is_alphanumeric() || matches!(c, '-' | '_' | '.'))
            })
}

pub fn permalink(mut url: Url) -> Result<PostLink, Refusal> {
    use Network::*;
    let host = url.host_str().ok_or(Refusal::Permalink)?;
    let network = network(host).ok_or(Refusal::Unsupported)?;
    let parts: Vec<_> = url
        .path()
        .strip_prefix('/')
        .unwrap_or_default()
        .trim_end_matches('/')
        .split('/')
        .collect();
    let (id, canonical_host, path) = match (network, parts.as_slice()) {
        (X, [handle, "status" | "statuses", id, rest @ ..])
            if x_handle(handle)
                && number(id)
                && matches!(rest, [] | ["photo" | "video", "1" | "2" | "3" | "4"]) =>
        {
            (*id, "x.com", format!("/{handle}/status/{id}"))
        }
        (X, ["i", "web", "status", id]) if number(id) => (*id, "x.com", format!("/i/status/{id}")),
        (Facebook, [page, "posts", id])
            if token(page, 100)
                || (page.contains('.') && page.split('.').all(|s| token(s, 100))) =>
        {
            if !number(id) && !(id.starts_with("pfbid") && token(id, 160)) {
                return Err(Refusal::Permalink);
            }
            (*id, "www.facebook.com", format!("/{page}/posts/{id}/"))
        }
        (Facebook, [page, "posts", slug, id])
            if token(page, 100) && facebook_slug(slug) && number(id) =>
        {
            (
                *id,
                "www.facebook.com",
                format!("/{page}/posts/{slug}/{id}/"),
            )
        }
        (Facebook, ["reel", id]) if number(id) => (*id, "www.facebook.com", format!("/reel/{id}/")),
        (Unsupported | Short, _) => return Err(Refusal::Unsupported),
        _ => return Err(Refusal::Permalink),
    };
    let id = id.to_owned();
    url.set_scheme("https").map_err(|_| Refusal::Permalink)?;
    url.set_host(Some(canonical_host))
        .map_err(|_| Refusal::Permalink)?;
    url.set_path(&path);
    url.set_query(None);
    Ok(PostLink { network, id, url })
}

pub fn short_link(mut url: Url) -> Option<Url> {
    url.set_scheme("https").ok()?;
    if google_share_hop(&url) {
        let mut tokens = url.query_pairs().filter(|(name, _)| name == "q");
        let (_, id) = tokens.next()?;
        if !token(&id, 32) || tokens.next().is_some() {
            return None;
        }
        let id = id.into_owned();
        url.set_query(None);
        url.query_pairs_mut().append_pair("q", &id);
        return Some(url);
    }
    if !is_shortener(&url) || !token(url.path().strip_prefix('/')?, 32) {
        return None;
    }
    url.set_query(None);
    Some(url)
}
