use super::{
    card,
    links::{Network, PostLink},
    net::{self, Transport},
    Refusal, Snapshot,
};
use xeet_core::{
    limits::Limits,
    model::{Post, RenderOptions, Surface, Theme},
};
use xeet_resolve::{
    Cache, CacheEntry, Clock, Fetch, FetchError, Lookup, LookupError, Options, Request, Response,
};

const WIDTH: f32 = 580.0;
const DPR: f32 = 1.0;

struct FetchPost<'a> {
    net: &'a Transport,
    link: &'a PostLink,
}

impl Fetch for FetchPost<'_> {
    async fn fetch(&self, request: Request) -> Result<Response, FetchError> {
        let blocked = || FetchError::Blocked("outside the single-post policy".into());
        let mut url = url::Url::parse(&request.url).map_err(|_| blocked())?;
        let get = |key| {
            request
                .headers
                .iter()
                .find(|(name, _)| name == key)
                .map(|(_, value)| value.as_str())
                .unwrap_or_default()
        };
        let is_image = get("accept") == "image/*";
        let allowed = if is_image {
            net::media_url(&url, Network::X)
        } else {
            self.link.matches(&url)
        };
        if !net::secure_url(&url) || !allowed {
            return Err(blocked());
        }
        if !is_image {
            url.set_query(None);
        }
        let body = tokio::time::timeout(
            request.timeout,
            self.net.get(
                &url,
                get("user-agent"),
                get("accept"),
                request.max_bytes.min(if is_image {
                    net::IMAGE_LIMIT
                } else {
                    net::HTML_LIMIT
                }),
            ),
        )
        .await
        .map_err(|_| FetchError::Timeout)?
        .ok_or_else(|| FetchError::Transport("post resource unavailable".into()))?;
        let mut headers = Vec::new();
        if let Some(location) = body.redirect(&url) {
            headers.push(("location".into(), location.into()));
        }
        if let Some(mime) = body.mime {
            headers.push(("content-type".into(), mime));
        }
        Ok(Response {
            status: body.status,
            headers,
            body: body.bytes,
            truncated: false,
            final_url: None,
        })
    }
}

// Peek has no shared cache or external clock. Address verification and
// connection pinning happen together inside Transport.
enum NoHooks {}
impl Cache for NoHooks {
    async fn get(&self, _: &str) -> Option<CacheEntry> {
        match *self {}
    }
    async fn set(&self, _: &str, _: CacheEntry) {
        match *self {}
    }
}
impl Lookup for NoHooks {
    async fn lookup(&self, _: &str) -> Result<Vec<std::net::IpAddr>, LookupError> {
        match *self {}
    }
}
impl Clock for NoHooks {
    fn now_ms(&self) -> u64 {
        match *self {}
    }
}

fn limits() -> Limits {
    Limits {
        max_media: 4,
        max_image_bytes: net::IMAGE_LIMIT,
        max_image_bytes_total: 12 * 1024 * 1024,
        max_image_dimension: 3000,
        max_image_pixels: 4_000_000,
        max_image_pixels_total: 16_000_000,
        max_output_height: 3300,
        max_output_pixels: 2_000_000,
        ..Limits::standard()
    }
}

pub async fn resolve(net: &Transport, link: &PostLink) -> Result<Snapshot, Refusal> {
    let options = Options {
        media: true,
        max_links: 0,
        width: WIDTH,
        dpr: DPR,
        hops: 3,
        html_bytes: net::HTML_LIMIT,
        image_bytes: net::IMAGE_LIMIT,
        timeout_ms: 6000,
        limits: limits(),
        ..Options::default()
    };
    let fetch = FetchPost { net, link };
    let resolved = xeet_resolve::resolve(
        link.url.as_str(),
        options,
        &fetch,
        None::<&NoHooks>,
        None::<&NoHooks>,
        None::<&NoHooks>,
    )
    .await
    .map_err(|_| Refusal::Unavailable)?;
    if resolved.source.id.as_deref() != Some(link.id.as_str()) {
        return Err(Refusal::Unavailable);
    }
    render(&resolved.post)
}

pub(crate) fn render(post: &Post) -> Result<Snapshot, Refusal> {
    let options = RenderOptions {
        width: WIDTH,
        dpr: DPR,
        surface: Surface::WebDetail,
        theme: Theme::Dark,
        ..RenderOptions::default()
    };
    let (png, report) = xeet_core::render::render_with(post, &options, &limits())
        .map_err(|_| Refusal::Unavailable)?;
    let image = card::image_data(&png, true).ok_or(Refusal::Unavailable)?;
    let alt = card::clean_text(
        &format!("Post by {}. {}", post.author.name, report.text.visible),
        1600,
    );
    Ok(Snapshot {
        site: "X",
        title: None,
        text: None,
        image: Some(image),
        image_alt: Some(alt),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn renders_xeet_post_json_as_a_bounded_png() {
        use base64::{engine::general_purpose::STANDARD, Engine};
        let post = serde_json::from_str(include_str!("fixtures/x-post.json")).unwrap();
        let snapshot = render(&post).unwrap();
        let image = snapshot.image.unwrap();
        let png = STANDARD
            .decode(image.strip_prefix("data:image/png;base64,").unwrap())
            .unwrap();
        assert_eq!(&png[..8], b"\x89PNG\r\n\x1a\n");
        assert_eq!(
            u32::from_be_bytes(png[16..20].try_into().unwrap()),
            WIDTH as u32
        );
        assert!(snapshot.image_alt.unwrap().contains("harbor walk"));
        assert!(png.len() <= card::OUTPUT_LIMIT);
    }
}
