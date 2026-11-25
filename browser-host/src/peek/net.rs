use super::links::{self, Network};
use reqwest::{header, Client};
use serde::Deserialize;
use std::{
    cell::RefCell,
    collections::HashMap,
    net::{IpAddr, Ipv4Addr, SocketAddr},
    time::Duration,
};
use url::Url;

pub const HTTP_TIMEOUT: Duration = Duration::from_secs(4);
pub const HTML_LIMIT: usize = 3 * 1024 * 1024;
pub const IMAGE_LIMIT: usize = 2 * 1024 * 1024;
fn nextdns(profile: &str) -> Option<String> {
    crate::normalize_nextdns_profile(profile)
        .map(|profile| format!("https://dns.nextdns.io/{profile}"))
}
const DNS_LIMIT: usize = 16 * 1024;
const TOTAL_LIMIT: usize = 14 * 1024 * 1024;

#[derive(Debug, Deserialize)]
struct Record {
    name: String,
    #[serde(rename = "type")]
    kind: u16,
    data: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct Answer {
    status: u16,
    #[serde(rename = "TC")]
    truncated: bool,
    question: Vec<Question>,
    #[serde(default)]
    answer: Vec<Record>,
    #[serde(default)]
    additional: Vec<Record>,
}

#[derive(Deserialize)]
struct Question {
    name: String,
    #[serde(rename = "type")]
    kind: u16,
}

fn http() -> reqwest::ClientBuilder {
    Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(2))
        .timeout(HTTP_TIMEOUT)
}

fn nextdns_client() -> Result<Client, reqwest::Error> {
    // The profile endpoint is a fixed service. It uses the hostname for TLS
    // and connects only to the provider's public anycast addresses.
    http()
        .resolve_to_addrs(
            "dns.nextdns.io",
            &[
                SocketAddr::from(([45, 90, 28, 0], 443)),
                SocketAddr::from(([45, 90, 30, 0], 443)),
            ],
        )
        .build()
}

fn same_host(a: &str, b: &str) -> bool {
    a.trim_end_matches('.').eq_ignore_ascii_case(b)
}

async fn dns(client: &Client, endpoint: &str, host: &str, kind: u16) -> Option<Answer> {
    let kind_name = if kind == 1 { "A" } else { "AAAA" };
    let response = client
        .get(endpoint)
        .query(&[("name", host), ("type", kind_name)])
        .header(header::ACCEPT, "application/dns-json")
        .send()
        .await
        .ok()?
        .error_for_status()
        .ok()?;
    let body = read_bounded(response, DNS_LIMIT).await?;
    let answer: Answer = serde_json::from_slice(&body).ok()?;
    let [question] = answer.question.as_slice() else {
        return None;
    };
    (!answer.truncated
        && matches!(answer.status, 0 | 3)
        && question.kind == kind
        && same_host(&question.name, host))
    .then_some(answer)
}

fn filtered(answer: &Answer, kind: u16) -> bool {
    let addresses: Vec<_> = answer
        .answer
        .iter()
        .filter(|record| record.kind == kind)
        .collect();
    answer.additional.iter().any(|record| {
        record.kind == 41
            && record.data.contains("(Blocked by NextDNS")
            && [15, 16, 17, 18]
                .iter()
                .any(|code| record.data.contains(&format!("EDE: {code} (")))
    }) || answer
        .answer
        .iter()
        .any(|record| record.kind == 5 && same_host(&record.data, "blockpage.nextdns.io"))
        || (!addresses.is_empty()
            && addresses.iter().all(|record| {
                record
                    .data
                    .parse::<IpAddr>()
                    .is_ok_and(|ip| ip.is_unspecified())
            }))
}

pub async fn blocked(host: &str, profile: &str) -> bool {
    if links::network(host).is_none() {
        return false;
    }
    let Ok(client) = nextdns_client() else {
        return false;
    };
    let Some(endpoint) = nextdns(profile) else {
        return false;
    };
    let Some(answer) = dns(&client, &endpoint, host, 1).await else {
        return false;
    };
    filtered(&answer, 1)
        || (answer.status == 0
            && !answer.answer.iter().any(|record| record.kind == 1)
            && dns(&client, &endpoint, host, 28)
                .await
                .is_some_and(|answer| filtered(&answer, 28)))
}

/// Shortener discovery stays on the filtered profile. Read just the redirect
/// headers, pin the checked address and never fetch the indicated destination.
pub async fn short_redirect(url: &Url, profile: &str) -> Option<Url> {
    if !secure_url(url) || links::short_link(url.clone()).as_ref() != Some(url) {
        return None;
    }
    let resolver = nextdns_client().ok()?;
    let host = url.host_str()?;
    let answer = dns(&resolver, &nextdns(profile)?, host, 1).await?;
    if filtered(&answer, 1) {
        return None;
    }
    let addresses = public_addresses(answer, host)?;
    let client = http().resolve_to_addrs(host, &addresses).build().ok()?;
    let response = client
        .get(url.clone())
        .header(header::USER_AGENT, xeet_resolve::CRAWLER_USER_AGENT)
        .send()
        .await
        .ok()?;
    redirect(
        response.status().as_u16(),
        response.headers().get(header::LOCATION)?.to_str().ok()?,
        url,
    )
}

fn in_domain(host: &str, domain: &str) -> bool {
    host == domain || host.strip_suffix(domain).is_some_and(|s| s.ends_with('.'))
}

fn media_path(url: &Url) -> bool {
    url.path().strip_prefix('/').is_some_and(|path| {
        path.split('/').all(|segment| {
            !segment.is_empty()
                && percent_encoding::percent_decode_str(segment)
                    .decode_utf8()
                    .is_ok_and(|decoded| {
                        !matches!(decoded.as_ref(), "." | "..")
                            && !decoded
                                .chars()
                                .any(|c| c.is_control() || matches!(c, '/' | '\\' | '%'))
                    })
        })
    })
}

pub fn media_url(url: &Url, network: Network) -> bool {
    let Some(host) = url.host_str() else {
        return false;
    };
    // These CDNs host the platform's public media. No link-card destination or
    // generic cross-site image origin is eligible.
    if !media_path(url) {
        return false;
    }
    match network {
        Network::X => {
            host == "pbs.twimg.com"
                && [
                    "/media/",
                    "/profile_images/",
                    "/ext_tw_video_thumb/",
                    "/amplify_video_thumb/",
                    "/tweet_video_thumb/",
                    "/ad_img/",
                    "/semantic_core_img/",
                ]
                .iter()
                .any(|prefix| url.path().starts_with(prefix) && url.path().len() > prefix.len())
        }
        Network::Facebook => in_domain(host, "fbcdn.net") && url.path().starts_with("/v/"),
        _ => false,
    }
}

pub fn secure_url(url: &Url) -> bool {
    url.scheme() == "https"
        && url.port().is_none()
        && url.username().is_empty()
        && url.password().is_none()
        && url.fragment().is_none()
        && matches!(url.host(), Some(url::Host::Domain(_)))
        && url.as_str().len() <= 4096
}

async fn read_bounded(mut response: reqwest::Response, cap: usize) -> Option<Vec<u8>> {
    if response
        .content_length()
        .is_some_and(|len| len > cap as u64)
    {
        return None;
    }
    let mut body = Vec::new();
    while let Some(chunk) = response.chunk().await.ok()? {
        if body.len() + chunk.len() > cap {
            return None;
        }
        body.extend(chunk);
    }
    Some(body)
}

pub struct Transport {
    dns_client: Client,
    clients: RefCell<HashMap<String, Client>>,
    remaining: std::cell::Cell<usize>,
}

impl Transport {
    pub fn new() -> Option<Self> {
        // Bootstrap the unfiltered DoH endpoint without consulting system DNS.
        let dns_client = http()
            .resolve("cloudflare-dns.com", SocketAddr::from(([1, 1, 1, 1], 443)))
            .build()
            .ok()?;
        Some(Self {
            dns_client,
            clients: RefCell::default(),
            remaining: std::cell::Cell::new(TOTAL_LIMIT),
        })
    }

    async fn client_for(&self, host: &str) -> Option<Client> {
        let client = self.clients.borrow().get(host).cloned();
        if client.is_some() {
            return client;
        }
        let answer = dns(
            &self.dns_client,
            "https://cloudflare-dns.com/dns-query",
            host,
            1,
        )
        .await?;
        let addresses = public_addresses(answer, host)?;
        // The connection uses exactly the checked addresses, with hostname SNI
        // and certificate validation. There is no second lookup or proxy.
        let client = http().resolve_to_addrs(host, &addresses).build().ok()?;
        self.clients
            .borrow_mut()
            .insert(host.to_owned(), client.clone());
        Some(client)
    }

    // Callers validate the URL's specific role (permalink, shortener or media)
    // before reaching this transport.
    pub async fn get(&self, url: &Url, ua: &str, accept: &str, cap: usize) -> Option<Body> {
        if !secure_url(url) || self.remaining.get() == 0 {
            return None;
        }
        let client = self.client_for(url.host_str()?).await?;
        let response = client
            .get(url.clone())
            .header(header::USER_AGENT, ua)
            .header(header::ACCEPT, accept)
            .send()
            .await
            .ok()?;
        let status = response.status().as_u16();
        let get_header = |key| {
            response
                .headers()
                .get(key)
                .and_then(|value| value.to_str().ok())
                .map(str::to_owned)
        };
        let location = get_header(header::LOCATION);
        let mime = get_header(header::CONTENT_TYPE);
        let bytes = if (200..300).contains(&status) && cap > 0 {
            // Reserve before reading so concurrent xeet media requests share a budget.
            let allowance = self.remaining.get().min(cap);
            self.remaining.set(self.remaining.get() - allowance);
            let body = read_bounded(response, allowance).await?;
            self.remaining
                .set(self.remaining.get() + allowance - body.len());
            body
        } else {
            Vec::new()
        };
        Some(Body {
            status,
            location,
            mime,
            bytes,
        })
    }
}

fn public_addresses(answer: Answer, host: &str) -> Option<Vec<SocketAddr>> {
    if answer.status != 0 {
        return None;
    }
    let mut owner = host.to_owned();
    for _ in 0..8 {
        let Some(alias) = answer
            .answer
            .iter()
            .find(|r| r.kind == 5 && same_host(&r.name, &owner))
        else {
            let addresses = answer
                .answer
                .iter()
                .filter(|r| r.kind == 1 && same_host(&r.name, &owner))
                .map(|r| {
                    let ip = r.data.parse::<Ipv4Addr>().ok()?;
                    (!xeet_resolve::urlsafety::is_private_v4(ip)
                        && !ip.octets().starts_with(&[192, 88, 99]))
                    .then_some(SocketAddr::from((ip, 443)))
                })
                .collect::<Option<Vec<_>>>()?;
            return (!addresses.is_empty()).then_some(addresses);
        };
        owner = alias.data.trim_end_matches('.').to_owned();
    }
    None
}

pub struct Body {
    pub status: u16,
    pub location: Option<String>,
    pub mime: Option<String>,
    pub bytes: Vec<u8>,
}

impl Body {
    pub fn redirect(&self, from: &Url) -> Option<Url> {
        redirect(self.status, self.location.as_deref()?, from)
    }
}

fn redirect(status: u16, location: &str, from: &Url) -> Option<Url> {
    if !matches!(status, 301 | 302 | 303 | 307 | 308) {
        return None;
    }
    let mut next = from.join(location).ok()?;
    next.set_fragment(None);
    secure_url(&next).then_some(next)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn selected_profile_cannot_change_the_dns_service() {
        assert_eq!(
            nextdns("A1B2C3").as_deref(),
            Some("https://dns.nextdns.io/a1b2c3")
        );
        for profile in ["", "a1b2c3?x=1", "a1b2c3/../", "a1b2cz", "0001"] {
            assert!(nextdns(profile).is_none(), "{profile}");
        }
    }

    #[test]
    fn media_paths_stay_in_their_cdn_namespace() {
        let url = |value| Url::parse(value).unwrap();
        assert!(media_url(
            &url("https://pbs.twimg.com/media/A?format=jpg"),
            Network::X
        ));
        assert!(media_url(
            &url("https://scontent.example.fbcdn.net/v/img/x.jpg?a=b"),
            Network::Facebook
        ));
        for input in [
            "https://pbs.twimg.com/media/%2F..%2Fsettings",
            "https://pbs.twimg.com/media/%255C..%255Cx",
            "https://pbs.twimg.com/media/%00.jpg",
            "https://pbs.twimg.com.example.org/media/photo.jpg",
        ] {
            assert!(!media_url(&url(input), Network::X), "{input}");
        }
    }

    #[test]
    fn rejects_a_mixed_public_and_private_dns_answer() {
        let answer = |ip| {
            serde_json::from_value(serde_json::json!({
                "Status": 0, "TC": false, "Question": [{"name":"x.com.", "type":1}],
                "Answer": [
                    {"name":"x.com.", "type":5, "data":"cdn.example."},
                    {"name":"cdn.example.", "type":1, "data":"162.159.140.229"},
                    {"name":"cdn.example.", "type":1, "data":ip}
                ]
            }))
            .unwrap()
        };
        for ip in [
            "127.0.0.1",
            "169.254.169.254",
            "10.2.3.4",
            "100.64.0.1",
            "192.88.99.2",
        ] {
            assert!(public_addresses(answer(ip), "x.com").is_none(), "{ip}");
        }
        assert_eq!(
            public_addresses(answer("162.159.140.230"), "x.com")
                .unwrap()
                .len(),
            2
        );
    }
}
