//! Live per-device `FocusDeviceStream` and verified focus-interval extractor.
//!
//! Converts native desktop `activity::Span` / `pause_history::PauseRec` records and
//! typed peer device snapshots (phone/TV) into one `FocusDeviceStream` per enrolled device
//! clipped to `[day_start_ms, day_end_ms)`.
//!
//! Safety invariants:
//! - Never uses raw window titles as route evidence for unverified browser tabs.
//! - Keeps each device's pauses scoped strictly to that device's stream.
//! - Preserves unknown gaps; never treats missing spans or a recent snapshot timestamp
//!   as proof of idle coverage (coverage attestations and finalization belong to the
//!   profile authority).

use crate::activity::{Span, Tracker};
use crate::activity_rules;
use crate::habits::focus_rewards::{
    verified_focus_intervals, FocusCategory, FocusDeviceStream, FocusSpan,
};
use crate::pause_history::{self, PauseRec};
use boa_engine::{js_string, object::JsObject, Context, JsValue, NativeFunction, Source};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::Path;

const CLASSIFIER: &str = include_str!("../../ui/activity-classifier.js");

const DESKTOP_BROWSERS: [&str; 10] = [
    "chrome",
    "edge",
    "msedge",
    "firefox",
    "brave",
    "opera",
    "vivaldi",
    "arc",
    "zen",
    "thorium",
];

const MOBILE_BROWSER_PACKAGES: [&str; 12] = [
    "com.android.chrome",
    "com.chrome.beta",
    "com.chrome.dev",
    "org.mozilla.firefox",
    "org.mozilla.fenix",
    "com.brave.browser",
    "com.microsoft.emmx",
    "com.sec.android.app.sbrowser",
    "com.opera.browser",
    "com.vivaldi.browser",
    "com.kiwibrowser.browser",
    "com.duckduckgo.mobile.android",
];

const URL_ADAPTER: &str = r#"
globalThis.URL = function URL(raw) {
  const parsed = parseFocusUrl(raw);
  this.hostname = parsed.hostname;
  this.pathname = parsed.pathname;
  this.port = parsed.port;
  this.searchParams = { get: (name) => parsed.pairs.find(([key]) => key === name)?.[1] ?? null };
};
"#;

const CLASSIFY_FINE: &str = r#"
(span, kind, rules, cache) => {
  const c = ActivityClassifier.classifySpan(span, kind, rules);
  const eligibility = ActivityClassifier.focusEligibility(span, kind, rules, cache || null);
  const explicit = cache?.items?.[`app:${c.app}`];
  const noise = Boolean(c.noise && !explicit);
  return {
    eligible_app: eligibility.appKey || c.app || span.app || 'Unknown',
    eligible_fine: eligibility.category,
    is_waste: eligibility.isWaste,
    noise,
  };
}
"#;

#[derive(Clone, Debug, serde::Deserialize)]
struct FineResult {
    eligible_app: String,
    eligible_fine: String,
    is_waste: bool,
    noise: bool,
}

/// Extracted per-device evidence for one resolved profile day `[day_start_ms, day_end_ms)`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ExtractedDevice {
    pub device_id: String,
    pub kind: String,
    pub stream: FocusDeviceStream,
    /// Qualifying single-app deep-focus intervals for this device inside `[day_start_ms, day_end_ms)`.
    pub verified_focus_intervals: Vec<(u64, u64)>,
    /// True if any verified foreground span on this device was confirmed wasting/Drift activity
    /// (social/video/tv/games/adult or extension `habit_feed`). Never inferred from raw titles.
    pub confirmed_drift: bool,
    /// Total merged ms of verified non-noise foreground activity on this device inside the day.
    pub verified_active_ms: u64,
    /// Total merged ms of browser foreground activity that lacked a verified URL/domain route.
    pub unverified_browser_ms: u64,
}

fn is_desktop_browser(app: &str) -> bool {
    let lower = app.trim().to_ascii_lowercase();
    DESKTOP_BROWSERS.iter().any(|&b| lower == b)
}

fn is_mobile_browser_package(pkg: &str) -> bool {
    let lower = pkg.trim().to_ascii_lowercase();
    MOBILE_BROWSER_PACKAGES.iter().any(|&b| lower == b)
}

fn map_fine_category(fine: &str) -> FocusCategory {
    match fine {
        "coding" => FocusCategory::Coding,
        "business" => FocusCategory::Business,
        "writing" => FocusCategory::Writing,
        "design" => FocusCategory::Design,
        "reading" => FocusCategory::Reading,
        "learning" => FocusCategory::Learning,
        "social" | "video" | "tv" | "games" | "adult" => FocusCategory::Waste,
        _ => FocusCategory::Other,
    }
}

fn merged_duration_ms(intervals: &[(u64, u64)]) -> u64 {
    let mut valid: Vec<(u64, u64)> = intervals.iter().copied().filter(|(s, e)| e > s).collect();
    valid.sort_unstable_by_key(|&(s, e)| (s, e));
    let mut total = 0u64;
    let mut cur: Option<(u64, u64)> = None;
    for (s, e) in valid {
        match cur {
            Some((cs, ce)) if s <= ce => cur = Some((cs, ce.max(e))),
            Some((cs, ce)) => {
                total += ce - cs;
                cur = Some((s, e));
            }
            None => cur = Some((s, e)),
        }
    }
    if let Some((cs, ce)) = cur {
        total += ce - cs;
    }
    total
}

/// Stateful wrapper over the shared `ui/activity-classifier.js` rules for live desktop & peer extraction.
pub struct FocusExtractor {
    context: Context,
    classify: JsObject,
    rules: JsValue,
    categories: JsValue,
    cache: HashMap<String, FineResult>,
}

impl FocusExtractor {
    pub fn new(rules: &Value, categories: Option<&Value>) -> Result<Self, String> {
        let mut context = Context::default();
        context
            .register_global_builtin_callable(
                js_string!("parseFocusUrl"),
                1,
                NativeFunction::from_fn_ptr(|_, args, ctx| {
                    let raw = args.first().cloned().unwrap_or_default().to_string(ctx)?;
                    let url = url::Url::parse(&raw.to_std_string_lossy()).map_err(|_| {
                        boa_engine::JsNativeError::typ().with_message("invalid URL")
                    })?;
                    let pairs: Vec<_> = url.query_pairs().collect();
                    JsValue::from_json(
                        &json!({
                            "hostname": url.host_str().unwrap_or(""),
                            "pathname": url.path(),
                            "port": url.port().map(|p| p.to_string()).unwrap_or_default(),
                            "pairs": pairs,
                        }),
                        ctx,
                    )
                }),
            )
            .map_err(|e| e.to_string())?;
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
            .eval(Source::from_bytes(CLASSIFY_FINE))
            .map_err(|e| e.to_string())?
            .as_callable()
            .ok_or("invalid fine classifier adapter")?;
        let rules = JsValue::from_json(rules, &mut context).map_err(|e| e.to_string())?;
        let categories = match categories {
            Some(cat) if cat.is_object() => {
                JsValue::from_json(cat, &mut context).map_err(|e| e.to_string())?
            }
            _ => JsValue::null(),
        };
        Ok(Self {
            context,
            classify,
            rules,
            categories,
            cache: HashMap::new(),
        })
    }

    pub fn from_data_dir(data_dir: &Path) -> Result<Self, String> {
        let rules = activity_rules::load_rules(data_dir)?;
        let categories =
            activity_rules::load_optional_object(data_dir, "categories.json", 512 * 1024)?;
        Self::new(&rules, Some(&categories))
    }

    fn classify_input(&mut self, span_json: &Value, device_kind: &str) -> Result<FineResult, String> {
        let key = format!("{device_kind}|{span_json}");
        if let Some(hit) = self.cache.get(&key) {
            return Ok(hit.clone());
        }
        let js_span = JsValue::from_json(span_json, &mut self.context).map_err(|e| e.to_string())?;
        let js_kind = JsValue::from(js_string!(device_kind));
        let value = self
            .classify
            .call(
                &JsValue::undefined(),
                &[js_span, js_kind, self.rules.clone(), self.categories.clone()],
                &mut self.context,
            )
            .map_err(|e| e.to_string())?
            .to_json(&mut self.context)
            .map_err(|e| e.to_string())?
            .ok_or("missing classifier result")?;
        let parsed: FineResult = serde_json::from_value(value).map_err(|e| e.to_string())?;
        if self.cache.len() >= 2048 {
            self.cache.clear();
        }
        self.cache.insert(key, parsed.clone());
        Ok(parsed)
    }

    /// Extract one desktop device's stream and verified focus intervals from raw `activity::Span`
    /// and `pause_history::PauseRec` records clipped to `[day_start_ms, day_end_ms)`.
    pub fn extract_desktop_spans(
        &mut self,
        device_id: &str,
        spans: &[Span],
        pauses: &[PauseRec],
        day_start_ms: u64,
        day_end_ms: u64,
    ) -> Result<ExtractedDevice, String> {
        let mut focus_spans = Vec::new();
        let mut focus_pauses = Vec::new();
        let mut verified_active_ivs = Vec::new();
        let mut unverified_browser_ivs = Vec::new();
        let mut confirmed_drift = false;

        if day_end_ms > day_start_ms {
            for p in pauses {
                let a = p.t.max(day_start_ms);
                let b = p.end.min(day_end_ms);
                if b > a {
                    focus_pauses.push((a, b));
                }
            }

            for sp in spans {
                let a = sp.s.max(day_start_ms);
                let b = sp.e.min(day_end_ms);
                if b <= a {
                    continue;
                }
                if sp.app.eq_ignore_ascii_case("FocusDNS")
                    || sp.label.eq_ignore_ascii_case("FocusDNS")
                {
                    continue;
                }

                let browser_app = is_desktop_browser(&sp.app);
                let ext_browser = if sp.source == "chromium_extension" {
                    sp.browser.as_ref()
                } else {
                    None
                };
                let verified_url = ext_browser
                    .and_then(|b| b.url.as_deref())
                    .map(str::trim)
                    .filter(|u| !u.is_empty());

                if browser_app && verified_url.is_none() {
                    // Never trust raw window titles as route evidence for unverified browser tabs.
                    unverified_browser_ivs.push((a, b));
                    let habit_feed = ext_browser.is_some_and(|b| b.habit_feed);
                    if habit_feed {
                        confirmed_drift = true;
                        verified_active_ivs.push((a, b));
                    }
                    focus_spans.push(FocusSpan {
                        start_ms: a,
                        end_ms: b,
                        app: sp.app.clone(),
                        category: if habit_feed {
                            FocusCategory::Waste
                        } else {
                            FocusCategory::Other
                        },
                    });
                    continue;
                }

                let input = if let (Some(url), Some(bctx)) = (verified_url, ext_browser) {
                    // Pass verified URL + extension flags only; omit raw page title so titles
                    // cannot elevate an unverified route into an eligible focus category.
                    json!({
                        "app": sp.app,
                        "label": "",
                        "source": "chromium_extension",
                        "browser": {
                            "url": url,
                            "title": "",
                            "audible": bctx.audible,
                            "habit_feed": bctx.habit_feed,
                        }
                    })
                } else {
                    // Non-browser native desktop app: process executable name is authoritative.
                    json!({
                        "app": sp.app,
                        "label": sp.app,
                        "source": "exe",
                    })
                };

                let classified = self.classify_input(&input, "windows")?;
                if classified.noise {
                    continue;
                }
                let habit_feed = ext_browser.is_some_and(|b| b.habit_feed);
                let mut cat = map_fine_category(&classified.eligible_fine);
                if habit_feed || classified.is_waste {
                    cat = FocusCategory::Waste;
                }
                if cat.is_waste() {
                    confirmed_drift = true;
                }
                verified_active_ivs.push((a, b));
                focus_spans.push(FocusSpan {
                    start_ms: a,
                    end_ms: b,
                    app: classified.eligible_app,
                    category: cat,
                });
            }
        }

        let stream = FocusDeviceStream {
            spans: focus_spans,
            pauses: focus_pauses,
        };
        let verified_focus_intervals = verified_focus_intervals(&stream, day_start_ms, day_end_ms);
        Ok(ExtractedDevice {
            device_id: device_id.to_string(),
            kind: "laptop".to_string(),
            stream,
            verified_focus_intervals,
            confirmed_drift,
            verified_active_ms: merged_duration_ms(&verified_active_ivs),
            unverified_browser_ms: merged_duration_ms(&unverified_browser_ivs),
        })
    }

    /// Convenience wrapper reading directly from desktop `activity::Tracker` and pause log text.
    pub fn extract_desktop(
        &mut self,
        device_id: &str,
        tracker: &Tracker,
        pause_log_text: &str,
        active_pause: Option<(u64, Option<u64>)>,
        now_ms: u64,
        day_start_ms: u64,
        day_end_ms: u64,
    ) -> Result<ExtractedDevice, String> {
        let spans = tracker.spans(day_start_ms, day_end_ms);
        let pauses = pause_history::read_access_pauses(pause_log_text, active_pause, None, now_ms);
        self.extract_desktop_spans(device_id, &spans, &pauses, day_start_ms, day_end_ms)
    }

    /// Extract one paired peer device (phone/TV) from its typed JSON snapshot.
    /// Keeps pauses scoped to this peer, clips to `snapshot["now"]` when present,
    /// and never fabricates idle coverage for missing spans.
    pub fn extract_peer_snapshot(
        &mut self,
        device_id: &str,
        kind: &str,
        snapshot: &Value,
        day_start_ms: u64,
        day_end_ms: u64,
    ) -> Result<ExtractedDevice, String> {
        let mut focus_spans = Vec::new();
        let mut focus_pauses = Vec::new();
        let mut verified_active_ivs = Vec::new();
        let mut unverified_browser_ivs = Vec::new();
        let mut confirmed_drift = false;

        let effective_kind = if kind.trim().is_empty() {
            snapshot["kind"].as_str().unwrap_or("phone")
        } else {
            kind
        };
        let bound_to = snapshot["now"]
            .as_u64()
            .map_or(day_end_ms, |snap_now| day_end_ms.min(snap_now));

        if bound_to > day_start_ms {
            if let Some(pauses) = snapshot["pauses"].as_array() {
                for p in pauses {
                    let Some(t) = p["t"].as_u64() else {
                        continue;
                    };
                    let Some(end) = p["end"].as_u64() else {
                        continue;
                    };
                    let a = t.max(day_start_ms);
                    let b = end.min(bound_to);
                    if b > a {
                        focus_pauses.push((a, b));
                    }
                }
            }

            if let Some(spans) = snapshot["spans"].as_array() {
                for sp in spans {
                    let (Some(s), Some(e)) = (sp["s"].as_u64(), sp["e"].as_u64()) else {
                        continue;
                    };
                    let a = s.max(day_start_ms);
                    let b = e.min(bound_to);
                    if b <= a {
                        continue;
                    }
                    let raw_app = sp["app"].as_str().unwrap_or("").trim();
                    let raw_label = sp["label"].as_str().unwrap_or("").trim();
                    let domain = sp["domain"]
                        .as_str()
                        .map(str::trim)
                        .filter(|d| !d.is_empty())
                        .or_else(|| raw_app.strip_prefix("site:").map(str::trim).filter(|d| !d.is_empty()));

                    if raw_app.is_empty() && domain.is_none() {
                        continue;
                    }

                    // Mobile browser package with no verified domain cannot establish route evidence.
                    if domain.is_none() && is_mobile_browser_package(raw_app) {
                        unverified_browser_ivs.push((a, b));
                        focus_spans.push(FocusSpan {
                            start_ms: a,
                            end_ms: b,
                            app: if raw_label.is_empty() {
                                raw_app.to_string()
                            } else {
                                raw_label.to_string()
                            },
                            category: FocusCategory::Other,
                        });
                        continue;
                    }

                    let input = if let Some(dom) = domain {
                        json!({
                            "app": format!("site:{dom}"),
                            "label": dom,
                            "domain": dom,
                        })
                    } else {
                        json!({
                            "app": raw_app,
                            "label": if raw_label.is_empty() { raw_app } else { raw_label },
                        })
                    };

                    let classified = self.classify_input(&input, effective_kind)?;
                    if classified.noise {
                        continue;
                    }
                    let mut cat = map_fine_category(&classified.eligible_fine);
                    if classified.is_waste {
                        cat = FocusCategory::Waste;
                    }
                    if cat.is_waste() {
                        confirmed_drift = true;
                    }
                    verified_active_ivs.push((a, b));
                    focus_spans.push(FocusSpan {
                        start_ms: a,
                        end_ms: b,
                        app: classified.eligible_app,
                        category: cat,
                    });
                }
            }
        }

        let stream = FocusDeviceStream {
            spans: focus_spans,
            pauses: focus_pauses,
        };
        let verified_focus_intervals = verified_focus_intervals(&stream, day_start_ms, day_end_ms);
        Ok(ExtractedDevice {
            device_id: device_id.to_string(),
            kind: effective_kind.to_string(),
            stream,
            verified_focus_intervals,
            confirmed_drift,
            verified_active_ms: merged_duration_ms(&verified_active_ivs),
            unverified_browser_ms: merged_duration_ms(&unverified_browser_ivs),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::activity::BrowserContext;

    const MIN: u64 = 60_000;
    const SEC: u64 = 1_000;

    fn ext_browser(url: Option<&str>, title: Option<&str>, habit_feed: bool) -> BrowserContext {
        BrowserContext {
            url: url.map(str::to_string),
            title: title.map(str::to_string),
            visibility: "page".into(),
            audible: false,
            habit_feed,
            tab_id: Some(1),
            window_id: Some(1),
            browser_pid: 100,
            host_pid: 200,
        }
    }

    fn pause(t: u64, end: u64) -> PauseRec {
        PauseRec {
            t,
            pause_id: None,
            end,
            minutes: (end - t) / MIN,
            reason: "break".into(),
            check_in: None,
            rating: None,
            rating_note: None,
            completed_at: Some(end),
            active: false,
            free_time: false,
            emergency_shield: false,
        }
    }

    #[test]
    fn desktop_extractor_rejects_raw_browser_titles_and_keeps_verified_routes() {
        let mut ex = FocusExtractor::new(&activity_rules::default_rules(), None).unwrap();
        let spans = vec![
            // 1. Native T3 Code (30m) -> verified Coding focus run
            Span {
                s: 0,
                e: 30 * MIN,
                app: "T3 Code".into(),
                label: "T3 Code".into(),
                source: "window_title".into(),
                browser: None,
            },
            // 2. Unverified Chrome tab with raw window title "Notion" (35m) -> MUST NOT qualify
            Span {
                s: 40 * MIN,
                e: 75 * MIN,
                app: "Chrome".into(),
                label: "Notion".into(),
                source: "window_title".into(),
                browser: None,
            },
            // 3. Unverified Chrome tab with raw title "YouTube" (20m) -> MUST NOT trigger confirmed_drift
            Span {
                s: 80 * MIN,
                e: 100 * MIN,
                app: "Chrome".into(),
                label: "YouTube".into(),
                source: "window_title".into(),
                browser: None,
            },
            // 4. Extension-verified NotebookLM (18m) -> verified Learning focus run (>=15m)
            Span {
                s: 110 * MIN,
                e: 128 * MIN,
                app: "Chrome".into(),
                label: "notebooklm.google.com".into(),
                source: "chromium_extension".into(),
                browser: Some(ext_browser(
                    Some("https://notebooklm.google.com/notebook/abc"),
                    Some("YouTube notes"), // title must not misclassify verified URL
                    false,
                )),
            },
            // 5. Native VS Code (40m) interrupted by a 5m DNS pause -> neither half hits 25m
            Span {
                s: 140 * MIN,
                e: 180 * MIN,
                app: "VS Code".into(),
                label: "VS Code".into(),
                source: "window_title".into(),
                browser: None,
            },
        ];
        let pauses = vec![pause(158 * MIN, 163 * MIN)];

        let res = ex
            .extract_desktop_spans("laptop-1", &spans, &pauses, 0, 200 * MIN)
            .unwrap();
        assert_eq!(res.device_id, "laptop-1");
        assert_eq!(res.kind, "laptop");
        assert!(!res.confirmed_drift, "Raw browser titles must never trigger confirmed_drift");
        assert_eq!(res.unverified_browser_ms, 55 * MIN);
        assert_eq!(res.verified_active_ms, 30 * MIN + 18 * MIN + 40 * MIN);
        assert_eq!(
            res.verified_focus_intervals,
            vec![(0, 30 * MIN), (110 * MIN, 128 * MIN)]
        );
    }

    #[test]
    fn verified_extension_waste_and_habit_feed_confirm_drift_and_break_runs() {
        let mut ex = FocusExtractor::new(&activity_rules::default_rules(), None).unwrap();
        let spans = vec![
            // 15m Notion (extension-verified) + 10s verified YouTube + 15m Notion -> broken!
            Span {
                s: 0,
                e: 15 * MIN,
                app: "Chrome".into(),
                label: "notion.so".into(),
                source: "chromium_extension".into(),
                browser: Some(ext_browser(Some("https://www.notion.so/page1"), Some("Spec"), false)),
            },
            Span {
                s: 15 * MIN,
                e: 15 * MIN + 10 * SEC,
                app: "Chrome".into(),
                label: "youtube.com".into(),
                source: "chromium_extension".into(),
                browser: Some(ext_browser(Some("https://www.youtube.com/watch?v=123"), Some("Clip"), false)),
            },
            Span {
                s: 15 * MIN + 10 * SEC,
                e: 30 * MIN + 10 * SEC,
                app: "Chrome".into(),
                label: "notion.so".into(),
                source: "chromium_extension".into(),
                browser: Some(ext_browser(Some("https://www.notion.so/page1"), Some("Spec"), false)),
            },
        ];
        let res = ex
            .extract_desktop_spans("laptop-1", &spans, &[], 0, 60 * MIN)
            .unwrap();
        assert!(res.confirmed_drift);
        assert!(res.verified_focus_intervals.is_empty());

        // Extension habit_feed flag on an otherwise unverified URL still confirms drift.
        let feed_only = vec![Span {
            s: 0,
            e: 5 * MIN,
            app: "Chrome".into(),
            label: "Chrome".into(),
            source: "chromium_extension".into(),
            browser: Some(ext_browser(None, None, true)),
        }];
        let feed_res = ex
            .extract_desktop_spans("laptop-1", &feed_only, &[], 0, 60 * MIN)
            .unwrap();
        assert!(feed_res.confirmed_drift);
        assert!(feed_res.verified_focus_intervals.is_empty());
    }

    #[test]
    fn peer_snapshot_extractor_keeps_per_device_pauses_and_preserves_gaps() {
        let mut ex = FocusExtractor::new(&activity_rules::default_rules(), None).unwrap();
        let snapshot = json!({
            "kind": "phone",
            "now": 180 * MIN,
            "spans": [
                // 18m verified Duolingo -> qualifies (>=15m Learning)
                { "s": 10 * MIN, "e": 28 * MIN, "app": "com.duolingo", "label": "Duolingo" },
                // 30m unverified Chrome (no domain) -> MUST NOT qualify
                { "s": 40 * MIN, "e": 70 * MIN, "app": "com.android.chrome", "label": "Chrome" },
                // 20m verified mobile Kindle reader -> qualifies (>=15m Reading)
                { "s": 90 * MIN, "e": 110 * MIN, "app": "site:read.amazon.com", "label": "Kindle", "domain": "read.amazon.com" },
                // 20m verified Duolingo split by a phone pause -> neither half hits 15m
                { "s": 130 * MIN, "e": 150 * MIN, "app": "com.duolingo", "label": "Duolingo" }
            ],
            "pauses": [
                { "kind": "dns", "t": 139 * MIN, "end": 141 * MIN }
            ]
        });

        let res = ex
            .extract_peer_snapshot("phone-1", "phone", &snapshot, 0, 200 * MIN)
            .unwrap();
        assert_eq!(res.device_id, "phone-1");
        assert_eq!(res.kind, "phone");
        assert!(!res.confirmed_drift);
        assert_eq!(res.unverified_browser_ms, 30 * MIN);
        assert_eq!(res.verified_active_ms, 18 * MIN + 20 * MIN + 20 * MIN);
        assert_eq!(
            res.verified_focus_intervals,
            vec![(10 * MIN, 28 * MIN), (90 * MIN, 110 * MIN)]
        );
    }

    #[test]
    fn shared_reward_fixture_keeps_youtube_watch_ineligible_across_desktop_and_phone() {
        let fixture: Value = serde_json::from_str(include_str!("../../shared/focus-eligibility-fixtures.json")).unwrap();
        let duration = fixture["durationMs"].as_u64().unwrap();
        let display_rule = fixture["youtubeWatch"]["displayRule"].clone();
        let desktop_rules = json!({"rules": [{
            "match": "host", "value": "youtube.com",
            "app": display_rule["app"], "category": display_rule["category"]
        }]});
        let mut desktop = FocusExtractor::new(&desktop_rules, None).unwrap();
        let url = fixture["youtubeWatch"]["desktopUrl"].as_str().unwrap();
        let desktop_result = desktop.extract_desktop_spans(
            "laptop-fixture",
            &[Span {
                s: 0,
                e: duration,
                app: "Chrome".into(),
                label: "youtube.com".into(),
                source: "chromium_extension".into(),
                browser: Some(ext_browser(Some(url), Some("Fictional lesson"), false)),
            }],
            &[],
            0,
            duration,
        ).unwrap();
        assert!(desktop_result.verified_focus_intervals.is_empty());
        assert!(desktop_result.stream.spans.iter().all(|s| s.category != FocusCategory::Learning));

        let package = fixture["youtubeWatch"]["phonePackage"].as_str().unwrap();
        let phone_rules = json!({"rules": [{
            "match": "package", "value": package,
            "app": display_rule["app"], "category": display_rule["category"]
        }]});
        let mut phone = FocusExtractor::new(&phone_rules, None).unwrap();
        let phone_result = phone.extract_peer_snapshot(
            "phone-fixture",
            "phone",
            &json!({
                "kind": "phone", "now": duration,
                "spans": [{"s": 0, "e": duration, "app": package, "label": "YouTube"}],
                "pauses": []
            }),
            0,
            duration,
        ).unwrap();
        assert!(phone_result.verified_focus_intervals.is_empty());
        assert!(phone_result.stream.spans.iter().all(|s| s.category != FocusCategory::Learning));

        // Saved custom non-waste categories still qualify, even when the underlying app is unknown.
        let custom = &fixture["customWritingApp"];
        let custom_duration = custom["durationMs"].as_u64().unwrap();
        let custom_app = custom["app"].as_str().unwrap();
        let custom_rules = json!({"rules": [custom["rule"].clone()]});
        let mut custom_ex = FocusExtractor::new(&custom_rules, None).unwrap();
        let custom_result = custom_ex.extract_desktop_spans(
            "laptop-custom-fixture",
            &[Span {
                s: 0,
                e: custom_duration,
                app: custom_app.into(),
                label: custom_app.into(),
                source: "window_title".into(),
                browser: None,
            }],
            &[],
            0,
            custom_duration,
        ).unwrap();
        assert_eq!(custom_result.verified_focus_intervals, vec![(0, custom_duration)]);

        // Even when the YouTube home feed is user-labeled Writing, observed feed evidence is a hard break.
        let mut feed_ex = FocusExtractor::new(&json!({"rules": [
            custom["rule"].clone(),
            {"match": "host", "value": "youtube.com", "app": "Lessons", "category": "writing"}
        ]}), None).unwrap();
        let home_url = fixture["youtubeWatch"]["homeUrl"].as_str().unwrap();
        let feed_result = feed_ex.extract_desktop_spans(
            "laptop-feed-fixture",
            &[
                Span {
                    s: 0, e: 15 * MIN, app: custom_app.into(), label: custom_app.into(),
                    source: "window_title".into(), browser: None,
                },
                Span {
                    s: 15 * MIN, e: 15 * MIN + 10 * SEC, app: "Chrome".into(), label: "youtube.com".into(),
                    source: "chromium_extension".into(),
                    browser: Some(ext_browser(Some(home_url), Some("Home"), false)),
                },
                Span {
                    s: 15 * MIN + 10 * SEC, e: 30 * MIN + 10 * SEC, app: custom_app.into(), label: custom_app.into(),
                    source: "window_title".into(), browser: None,
                },
            ],
            &[],
            0,
            31 * MIN,
        ).unwrap();
        assert!(feed_result.verified_focus_intervals.is_empty());
        assert!(feed_result.confirmed_drift);
        assert!(feed_result.stream.spans.iter().any(|s| s.category == FocusCategory::Waste));

        // The same shared fixture carries positive controls for supported reading and learning evidence.
        let control_duration = fixture["learningControls"]["durationMs"].as_u64().unwrap();
        let mut learning = FocusExtractor::new(&activity_rules::default_rules(), None).unwrap();
        let app = fixture["learningControls"]["phonePackage"].as_str().unwrap();
        let learning_result = learning.extract_peer_snapshot(
            "phone-learning-fixture",
            "phone",
            &json!({
                "kind": "phone", "now": control_duration,
                "spans": [{"s": 0, "e": control_duration, "app": app, "label": "Duolingo"}],
                "pauses": []
            }),
            0,
            control_duration,
        ).unwrap();
        assert_eq!(learning_result.verified_focus_intervals, vec![(0, control_duration)]);

        let reading_duration = fixture["readingControl"]["durationMs"].as_u64().unwrap();
        let mut reading = FocusExtractor::new(&activity_rules::default_rules(), None).unwrap();
        let reading_result = reading.extract_desktop_spans(
            "laptop-reading-fixture",
            &[Span {
                s: 0,
                e: reading_duration,
                app: "Chrome".into(),
                label: "read.amazon.com".into(),
                source: "chromium_extension".into(),
                browser: Some(ext_browser(
                    fixture["readingControl"]["desktopUrl"].as_str(), Some("Fictional book"), false,
                )),
            }],
            &[],
            0,
            reading_duration,
        ).unwrap();
        assert_eq!(reading_result.verified_focus_intervals, vec![(0, reading_duration)]);

        let business = &fixture["businessApp"];
        let business_app = business["app"].as_str().unwrap();
        let business_duration = business["durationMs"].as_u64().unwrap();
        let mut items = serde_json::Map::new();
        items.insert(format!("app:{business_app}"), json!("business"));
        let business_categories = json!({"items": items});
        let mut business_ex = FocusExtractor::new(&activity_rules::default_rules(), Some(&business_categories)).unwrap();
        let business_result = business_ex.extract_desktop_spans(
            "laptop-business-fixture",
            &[Span {
                s: 0, e: business_duration, app: business_app.into(), label: business_app.into(),
                source: "window_title".into(), browser: None,
            }],
            &[],
            0,
            business_duration,
        ).unwrap();
        assert_eq!(business_result.verified_focus_intervals, vec![(0, business_duration)]);
    }
}
