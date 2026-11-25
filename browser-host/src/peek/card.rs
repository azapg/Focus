use super::{
    links::PostLink,
    net::{self, Transport},
    Refusal, Snapshot,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use image::{codecs::jpeg::JpegEncoder, ImageFormat, ImageReader, Limits};
use scraper::{Html, Selector};
use std::io::Cursor;
use url::Url;

const CRAWLER: &str = "facebookexternalhit/1.1";
const HTML: &str = "text/html,application/xhtml+xml";
const IMAGE: &str = "image/jpeg,image/png,image/webp";
pub const OUTPUT_LIMIT: usize = 680 * 1024;

struct Metadata {
    title: Option<String>,
    description: Option<String>,
    image: Option<Url>,
}

fn metadata(html: &str, page: &Url, link: &PostLink) -> Option<Metadata> {
    let doc = Html::parse_document(html);
    let selector = Selector::parse("head meta[content]").expect("valid meta selector");
    let values: Vec<_> = doc
        .select(&selector)
        .filter_map(|node| {
            let tag = node.value();
            Some((
                tag.attr("property").or_else(|| tag.attr("name"))?,
                tag.attr("content")?.trim(),
            ))
        })
        .filter(|(_, content)| !content.is_empty())
        .collect();
    let get = |key: &str| {
        values
            .iter()
            .find(|(name, _)| name.eq_ignore_ascii_case(key))
            .map(|(_, content)| *content)
    };
    // A generic login/error page may still return 200 and site-wide OG tags.
    let canonical = page.join(get("og:url")?).ok()?;
    if !link.matches(&canonical) {
        return None;
    }
    let image = ["og:image:secure_url", "og:image", "og:image:url"]
        .iter()
        .filter_map(|key| get(key))
        .filter_map(|value| page.join(value).ok())
        .find(|url| net::secure_url(url) && net::media_url(url, link.network));
    Some(Metadata {
        title: get("og:title").map(|t| clean_text(t, 240)),
        description: get("og:description").map(|t| clean_text(t, 1200)),
        image,
    })
}

pub fn clean_text(text: &str, max: usize) -> String {
    text.chars()
        .filter(|&c| !c.is_control() || c == '\n' || c == '\t')
        .take(max)
        .collect()
}

pub fn image_data(bytes: &[u8], png: bool) -> Option<String> {
    if png && bytes.len() <= OUTPUT_LIMIT {
        return Some(format!("data:image/png;base64,{}", STANDARD.encode(bytes)));
    }
    let mut reader = ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()
        .ok()?;
    if !matches!(
        reader.format()?,
        ImageFormat::Jpeg | ImageFormat::Png | ImageFormat::WebP
    ) {
        return None;
    }
    let mut limits = Limits::default();
    limits.max_image_width = Some(4096);
    limits.max_image_height = Some(4096);
    limits.max_alloc = Some(64 * 1024 * 1024);
    reader.limits(limits);
    // Decode and re-encode to discard metadata, animation and anything beyond
    // one bounded raster image. Keep enough resolution for a menu's fine print.
    let image = reader
        .decode()
        .ok()?
        .resize(1440, 1440, image::imageops::FilterType::Triangle)
        .to_rgb8();
    let mut out = Vec::new();
    JpegEncoder::new_with_quality(&mut out, 83)
        .encode_image(&image)
        .ok()?;
    (out.len() <= OUTPUT_LIMIT).then(|| format!("data:image/jpeg;base64,{}", STANDARD.encode(out)))
}

pub async fn facebook(net: &Transport, link: &PostLink) -> Result<Snapshot, Refusal> {
    let mut url = link.url.clone();
    for _ in 0..4 {
        let response = net
            .get(&url, CRAWLER, HTML, net::HTML_LIMIT)
            .await
            .ok_or(Refusal::Unavailable)?;
        if let Some(mut next) = response.redirect(&url) {
            if !link.matches(&next) {
                return Err(Refusal::Unavailable);
            }
            next.set_query(None);
            url = next;
            continue;
        }
        if response.status != 200
            || !response
                .mime
                .as_deref()
                .is_some_and(|s| s.starts_with("text/html"))
        {
            break;
        }
        let meta = metadata(&String::from_utf8_lossy(&response.bytes), &url, link)
            .ok_or(Refusal::Unavailable)?;
        let image = match meta.image {
            Some(url) => fetch_image(net, url, link).await,
            None => None,
        };
        if image.is_none() && meta.description.is_none() {
            return Err(Refusal::Unavailable);
        }
        return Ok(Snapshot {
            site: "Facebook",
            title: meta.title,
            text: meta.description,
            image,
            image_alt: Some("Image from the Facebook post".into()),
        });
    }
    Err(Refusal::Unavailable)
}

async fn fetch_image(net: &Transport, mut url: Url, link: &PostLink) -> Option<String> {
    for _ in 0..3 {
        if !net::media_url(&url, link.network) {
            return None;
        }
        let response = net.get(&url, CRAWLER, IMAGE, net::IMAGE_LIMIT).await?;
        if let Some(next) = response.redirect(&url) {
            url = next;
            continue;
        }
        let mime = response.mime.as_deref()?.split(';').next()?.trim();
        return (response.status == 200
            && matches!(mime, "image/jpeg" | "image/png" | "image/webp"))
        .then(|| image_data(&response.bytes, false))
        .flatten();
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::peek::links;

    #[test]
    fn parses_a_posts_og_tags_and_rejects_a_generic_page() {
        let page =
            Url::parse("https://www.facebook.com/sample-garden/posts/1234567890123456/").unwrap();
        let link = links::permalink(page.clone()).unwrap();
        let meta = metadata(include_str!("fixtures/facebook.html"), &page, &link).unwrap();
        assert_eq!(meta.title.as_deref(), Some("Jardín de Muestra & Café"));
        assert_eq!(
            meta.description.as_deref(),
            Some("Visita guiada, música y una exposición.")
        );
        assert_eq!(meta.image.unwrap().query(), Some("size=large&view=photo"));
        assert!(metadata(
            "<head><meta property='og:title' content='Log in'></head>",
            &page,
            &link
        )
        .is_none());
        let other = links::permalink(page.join("../999/").unwrap()).unwrap();
        assert!(metadata(include_str!("fixtures/facebook.html"), &page, &other).is_none());
    }
}
