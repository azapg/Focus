//! Runs the same local Creating/Consuming classifier used by the stats panel.

use super::{Activity, Kind};
use crate::activity::Span;
use boa_engine::{js_string, object::JsObject, Context, JsValue, NativeFunction, Source};
use serde_json::{json, Value};
use std::collections::HashMap;

const CLASSIFIER: &str = include_str!("../../../ui/activity-classifier.js");

// The classifier uses this subset of the browser URL API. Parse and decode in Rust
// with the same WHATWG URL crate that the browser bridge uses.
const URL_ADAPTER: &str = r#"
globalThis.URL = function URL(raw) {
  const parsed = parseFocusUrl(raw);
  this.hostname = parsed.hostname;
  this.pathname = parsed.pathname;
  this.port = parsed.port;
  this.searchParams = { get: (name) => parsed.pairs.find(([key]) => key === name)?.[1] ?? null };
};
"#;

const CLASSIFY: &str = r#"
(span, rules) => {
  const c = ActivityClassifier.classifySpan(span, 'windows', rules);
  const group = ActivityClassifier.CATEGORY_MAP[c.category]?.group ?? 'unknown';
  // Reddit's classifier labels both index pages and individual posts as threads.
  const itemKey = c.itemKey || '';
  const redditIndex = /^reddit:\/(?:r\/[^/]+\/?|(?:popular|all)\/?)?$/.test(itemKey);
  // YouTube's fallback item kind also covers search, playlists, and live URLs.
  const youtubeBrowse = c.itemKind === 'feed' && itemKey.startsWith('yt:');
  const youtubeFeed = youtubeBrowse && /^yt:\/(?:feed\/(?:subscriptions|trending|explore)\/?|shorts(?:\/[^/]+)?\/?)?$/.test(itemKey);
  const twitchBrowse = /^twitch:(?:home|directory|search|settings|inventory|drops|downloads|wallet|subscriptions)$/.test(itemKey);
  const feed = (c.itemKind === 'feed' && !youtubeBrowse) || youtubeFeed || redditIndex;
  // Display names and verb overrides cannot resolve ambiguous page evidence.
  const browsing = c.category === 'browsing' || c.itemKind === 'web_coarse' || youtubeBrowse || twitchBrowse;
  const confirmedFeed = span.browser?.habit_feed && (!c.userRule || group === 'consuming');
  const kind = c.noise ? 'ignored'
    : group === 'creating' ? 'creating'
    : confirmedFeed ? 'feed'
    : c.itemKind === 'browser_untracked' && !c.userRule ? 'neutral'
    : group === 'consuming' ? (feed ? 'feed'
      : browsing ? 'browsing' : 'consuming')
    : 'neutral';
  const identity = group === 'unknown' && c.matchType === 'host' ? c.matchValue : c.app;
  return { identity: (identity || span.label || span.app || 'Unknown').toLowerCase(), kind };
}
"#;

#[derive(serde::Deserialize)]
struct Classification {
    identity: String,
    kind: ClassifiedKind,
}

#[derive(serde::Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum ClassifiedKind {
    Creating,
    Consuming,
    Browsing,
    Feed,
    Neutral,
    Ignored,
}

pub struct Classifier {
    context: Context,
    classify: JsObject,
    rules: JsValue,
    cache: HashMap<String, Classification>,
}

impl Classifier {
    pub fn new(rules: &Value) -> Result<Self, String> {
        let mut context = Context::default();
        context.register_global_builtin_callable(
            js_string!("parseFocusUrl"), 1, NativeFunction::from_fn_ptr(|_, args, ctx| {
                let raw = args.first().cloned().unwrap_or_default().to_string(ctx)?;
                let url = url::Url::parse(&raw.to_std_string_lossy())
                    .map_err(|_| boa_engine::JsNativeError::typ().with_message("invalid URL"))?;
                let pairs: Vec<_> = url.query_pairs().collect();
                JsValue::from_json(&json!({
                    "hostname": url.host_str().unwrap_or(""), "pathname": url.path(),
                    "port": url.port().map(|p| p.to_string()).unwrap_or_default(), "pairs": pairs,
                }), ctx)
            }),
        ).map_err(|e| e.to_string())?;
        // Install the web API before compiling the shared classifier, just as
        // the browser provides URL before loading it.
        context
            .eval(Source::from_bytes(URL_ADAPTER))
            .map_err(|e| e.to_string())?;
        let source = CLASSIFIER
            .trim_end()
            .strip_suffix("export default ActivityClassifier;")
            .ok_or("missing activity classifier export")?;
        context
            .eval(Source::from_bytes(source))
            .map_err(|e| e.to_string())?;
        let classify = context
            .eval(Source::from_bytes(CLASSIFY))
            .map_err(|e| e.to_string())?
            .as_callable()
            .ok_or("invalid classifier adapter")?;
        let rules = JsValue::from_json(rules, &mut context).map_err(|e| e.to_string())?;
        Ok(Self {
            context,
            classify,
            rules,
            cache: HashMap::new(),
        })
    }

    pub fn classify(&mut self, span: &Span) -> Result<Activity, String> {
        let browser = span.browser.as_ref().map(|b| {
            json!({
                "url": b.url, "title": b.title, "audible": b.audible, "habit_feed": b.habit_feed,
            })
        });
        let input = json!({ "app": span.app, "label": span.label, "source": span.source, "browser": browser });
        let key = input.to_string();
        if !self.cache.contains_key(&key) {
            let input = JsValue::from_json(&input, &mut self.context).map_err(|e| e.to_string())?;
            let value = self
                .classify
                .call(
                    &JsValue::undefined(),
                    &[input, self.rules.clone()],
                    &mut self.context,
                )
                .map_err(|e| e.to_string())?
                .to_json(&mut self.context)
                .map_err(|e| e.to_string())?
                .ok_or("missing classifier result")?;
            let classified = serde_json::from_value(value).map_err(|e| e.to_string())?;
            if self.cache.len() >= 2048 {
                self.cache.clear();
            }
            self.cache.insert(key.clone(), classified);
        }
        let entry = &self.cache[&key];
        let kind = match entry.kind {
            ClassifiedKind::Creating => Kind::Creating,
            ClassifiedKind::Consuming => Kind::Consuming,
            ClassifiedKind::Browsing => Kind::Browsing,
            ClassifiedKind::Feed => Kind::Feed,
            ClassifiedKind::Neutral => Kind::Neutral,
            ClassifiedKind::Ignored => Kind::Ignored,
        };
        Ok(Activity {
            start: span.s,
            end: span.e,
            identity: entry.identity.clone(),
            kind,
        })
    }
}
