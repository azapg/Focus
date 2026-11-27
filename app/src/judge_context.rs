use crate::{
    activity::{Span, Tracker},
    judge::{summary_text, valid_domain, Catalog, Policy, Request, MINUTE_MS},
    pause_history::PauseRec,
};
use chrono::{Local, TimeZone, Timelike};
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};

pub fn day_start(now: u64) -> u64 {
    let local = Local.timestamp_millis_opt(now as i64).unwrap();
    Local
        .from_local_datetime(&local.date_naive().and_hms_opt(0, 0, 0).unwrap())
        .earliest()
        .map(|dt| dt.timestamp_millis() as u64)
        // A timezone may move its clock forward at midnight.
        .unwrap_or_else(|| {
            now - (local.num_seconds_from_midnight() as u64 * 1000
                + (local.nanosecond() / 1_000_000) as u64)
        })
}

pub fn usage(
    today: u64,
    now: u64,
    pauses: &[PauseRec],
    snapshots: &[Value],
) -> (usize, u64, Vec<String>) {
    let mut entries: Vec<_> = pauses
        .iter()
        .map(|p| (p.t, p.end, p.reason.as_str()))
        .collect();
    for snapshot in snapshots {
        if let Some(pauses) = snapshot["pauses"].as_array() {
            entries.extend(
                pauses
                    .iter()
                    .filter(|p| p["kind"] == "dns")
                    .filter_map(|p| {
                        let end = p["end"].as_u64()?.min(snapshot["now"].as_u64()?);
                        Some((p["t"].as_u64()?, end, p["reason"].as_str().unwrap_or("")))
                    }),
            );
        }
    }
    let used_ms = entries
        .iter()
        .map(|&(start, end, _)| end.min(now).saturating_sub(start.max(today)))
        .fold(0u64, u64::saturating_add);
    entries.retain(|&(start, _, _)| start >= today && start <= now);
    entries.sort_by_key(|&(start, ..)| std::cmp::Reverse(start));
    let reasons = entries
        .iter()
        .take(20)
        .map(|&(_, _, reason)| summarize_reason(reason))
        .collect();
    (entries.len(), used_ms, reasons)
}

fn summarize_reason(reason: &str) -> String {
    summary_text(reason).chars().take(400).collect()
}

fn observed_host(span: &Span) -> Option<String> {
    let page = span.browser.as_ref().and_then(|b| b.url.as_deref());
    if let Some(page) = page {
        return focus_browser_host::url_host(page).filter(|host| valid_domain(host));
    }
    // These are fixed labels emitted by activity::site. Other inferred labels
    // can contain arbitrary fragments of a window title.
    Some(
        match span.label.as_str() {
            "X" => "x.com",
            "YouTube" => "youtube.com",
            "Instagram" => "instagram.com",
            "Facebook" => "facebook.com",
            "Reddit" => "reddit.com",
            "TikTok" => "tiktok.com",
            "LinkedIn" => "linkedin.com",
            "GitHub" => "github.com",
            "Netflix" => "netflix.com",
            "WhatsApp" => "web.whatsapp.com",
            "Discord" => "discord.com",
            "Twitch" => "twitch.tv",
            "Gmail" => "mail.google.com",
            "ChatGPT" => "chatgpt.com",
            "Claude" => "claude.ai",
            "Google Docs" => "docs.google.com",
            "Google Calendar" => "calendar.google.com",
            "Notion" => "notion.so",
            "Hacker News" => "news.ycombinator.com",
            "Threads" => "threads.net",
            "Bluesky" => "bsky.app",
            "Prime Video" => "primevideo.com",
            "Disney+" => "disneyplus.com",
            _ => return None,
        }
        .into(),
    )
}

fn top(counts: BTreeMap<String, u64>) -> Vec<Value> {
    let mut counts: Vec<_> = counts.into_iter().collect();
    counts.sort_by_key(|(_, ms)| std::cmp::Reverse(*ms));
    counts.truncate(5);
    counts
        .into_iter()
        .map(|(name, ms)| json!({ "name": name, "seconds": ms / 1000 }))
        .collect()
}

fn activity(
    tracker: &Tracker,
    from: u64,
    to: u64,
    expected: &BTreeSet<String>,
    catalog: &Catalog,
) -> Value {
    let mut apps = BTreeMap::new();
    let mut sites = BTreeMap::new();
    let (mut compared, mut matched) = (0u64, 0u64);
    if from < to {
        for sp in tracker.spans(from, to) {
            let ms = sp.e.min(to).saturating_sub(sp.s.max(from));
            if ms == 0 {
                continue;
            }
            let site = observed_host(&sp);
            if let Some(ref host) = site {
                *sites.entry(host.clone()).or_default() += ms;
            }
            // The app field comes from executable names, independent of titles.
            let app = summary_text(&sp.app).chars().take(80).collect::<String>();
            *apps.entry(app.clone()).or_default() += ms;
            let label = site.as_deref().unwrap_or(&app);
            let name = label.to_ascii_lowercase();
            let terms = words(&name);
            let identities = catalog.matching_services(label);
            let known_site = site.is_some();
            if known_site
                || !identities.is_empty()
                || (sp.source == "window_title"
                    && sp.label == sp.app
                    && !matches!(
                        sp.app.as_str(),
                        "Chrome"
                            | "Edge"
                            | "Brave"
                            | "Firefox"
                            | "vivaldi"
                            | "opera"
                            | "thorium"
                            | "FocusDNS"
                    ))
            {
                compared += ms;
                if !expected.is_empty()
                    && (expected.contains(&name)
                        || identities.iter().any(|service| expected.contains(*service))
                        || terms.iter().any(|word| expected.contains(word)))
                {
                    matched += ms;
                }
            }
        }
    }
    let ratio = (!expected.is_empty() && compared > 0).then(|| matched * 100 / compared);
    json!({ "apps": top(apps), "sites": top(sites), "matched_percent": ratio })
}

fn words(text: &str) -> BTreeSet<String> {
    let ignored = [
        "for", "and", "the", "need", "want", "with", "quick", "check", "just", "look", "open",
        "some", "minutes", "this", "that", "get", "can", "please",
    ];
    text.split(|ch: char| !ch.is_alphanumeric() && ch != '_')
        .filter(|s| s.len() > 2 && !ignored.contains(s))
        .map(str::to_owned)
        .collect()
}

fn reason_terms(reason: &str, catalog: &Catalog) -> BTreeSet<String> {
    let safe = summary_text(reason).to_ascii_lowercase();
    let mut terms = words(&safe);
    for token in
        safe.split(|ch: char| !(ch.is_ascii_alphanumeric() || matches!(ch, '.' | '-' | '_')))
    {
        let token = token.trim_matches('.');
        if valid_domain(token) {
            terms.insert(token.into());
        }
        terms.extend(
            catalog
                .matching_services(token)
                .into_iter()
                .map(str::to_owned),
        );
    }
    terms
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ActiveIntentionContext {
    pub label: String,
    pub done_when: Option<String>,
    pub elapsed_minutes: u64,
    pub remaining_minutes: u64,
}

pub struct ContextMeta {
    pub habits_due: usize,
    pub wallet_balance_coins: i64,
    pub dns_check: Option<Value>,
    pub active_intention: Option<ActiveIntentionContext>,
}

pub fn build(
    request: &Request,
    now: u64,
    policy: Policy,
    reasons: Vec<String>,
    history: &[PauseRec],
    tracker: &Tracker,
    catalog: &Catalog,
    has_peek_target: bool,
    meta: &ContextMeta,
) -> Value {
    let local = Local.timestamp_millis_opt(now as i64).unwrap();
    let terms = reason_terms(&request.text, catalog);
    let mut similar = history
        .iter()
        .rev()
        .filter_map(|pause| {
            let expected = reason_terms(&pause.reason, catalog);
            let score = terms.intersection(&expected).count();
            (score > 0 && pause.end > pause.t && now.saturating_sub(pause.t) <= 30 * 86_400_000)
                .then_some((score, pause, expected))
        })
        .collect::<Vec<_>>();
    similar.sort_by_key(|(score, ..)| std::cmp::Reverse(*score));

    let similar = similar
        .into_iter()
        .take(4)
        .map(|(_, pause, expected)| {
            json!({
                "reason": summarize_reason(&pause.reason),
                "foreground": activity(tracker, pause.t, pause.end.min(now), &expected, catalog),
                "check_in": pause.check_in,
                "rating": pause.rating,
            })
        })
        .collect::<Vec<_>>();
    let recent_ratings = history
        .iter()
        .rev()
        .filter_map(|pause| pause.rating)
        .take(8)
        .collect::<Vec<_>>();
    let mut payload = json!({
        "request": summary_text(&request.text),
        "has_peek_target": has_peek_target,
        "check_in": request.check_in,
        "local_time": local.format("%Y-%m-%d %A %H:%M %:z").to_string(),
        "today": {
            "count": policy.count,
            "total_minutes": policy.used_ms as f64 / MINUTE_MS as f64,
            "reasons": reasons,
            "habits_due": meta.habits_due,
            "wallet_balance_coins": meta.wallet_balance_coins,
        },
        "last_hour": activity(tracker, now.saturating_sub(60 * MINUTE_MS), now, &BTreeSet::new(), catalog),
        "similar_pauses": similar,
        "recent_ratings": recent_ratings,
        "services": catalog.entries(),
        "max_minutes": 10,
    });
    if let Some(check) = &meta.dns_check {
        payload["dns_check"] = check.clone();
    }
    if let Some(intent) = &meta.active_intention {
        payload["active_intention"] = json!({
            "label": summary_text(&intent.label),
            "done_when": intent.done_when.as_deref().map(summary_text),
            "elapsed_minutes": intent.elapsed_minutes,
            "remaining_minutes": intent.remaining_minutes,
        });
    }
    payload
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{activity::BrowserContext, judge::CheckIn};

    #[test]
    fn gateway_context_summarizes_history_without_titles_or_url_paths() {
        let scratch = std::env::temp_dir().join(format!(
            "focus-judge-context-{}-{}",
            std::process::id(),
            crate::now_ms()
        ));
        let tracker = Tracker::new(&scratch);
        let now = crate::now_ms();
        let pause_at = now - 60_000;
        let spans = [
            Span {
                s: pause_at,
                e: now - 30_000,
                app: "Edge".into(),
                label: "x.com".into(),
                source: "browser_url".into(),
                browser: Some(BrowserContext {
                    url: Some("https://x.com/secret-user/status/123?secret=query".into()),
                    title: Some("Secret title".into()),
                    visibility: "page".into(),
                    audible: false,
                    habit_feed: true,
                    tab_id: None,
                    window_id: None,
                    browser_pid: 1,
                    host_pid: 2,
                }),
            },
            Span {
                s: now - 30_000,
                e: now,
                app: "Firefox".into(),
                label: "Private window fragment".into(),
                source: "window_title".into(),
                browser: None,
            },
        ];
        for span in spans {
            let date = Local
                .timestamp_millis_opt(span.s as i64)
                .unwrap()
                .date_naive();
            use std::io::Write;
            let path = scratch.join("activity").join(format!("{date}.jsonl"));
            writeln!(
                std::fs::OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(path)
                    .unwrap(),
                "{}",
                serde_json::to_string(&span).unwrap()
            )
            .unwrap();
        }
        let events = [
            json!({"type":"pause", "t":pause_at, "until":now - 1, "reason":"check Discord", "minutes":1}),
            json!({"type":"resume", "paused_at":pause_at, "t":now - 1}),
            json!({"type":"rating", "paused_at":pause_at, "rated_at":now, "rating":2, "rating_note":"Private rating note"}),
        ].map(|event| event.to_string()).join("\n");
        let history = crate::pause_history::read_pauses(&events, None, now);
        let request = Request {
            text: "Check Discord https://discord.com/channels/private".into(),
            check_in: Some(CheckIn::Tired),
        };
        let context = build(
            &request,
            now,
            Policy {
                daily_limit_minutes: 60,
                max_pause_minutes: 10,
                wait_seconds: 15,
                used_ms: 60_000,
                count: 1,
            },
            vec!["check Discord".into()],
            &history,
            &tracker,
            &Catalog::load(&scratch).unwrap(),
            false,
            &ContextMeta {
                habits_due: 2,
                wallet_balance_coins: 15,
                dns_check: None,
                active_intention: Some(ActiveIntentionContext {
                    label: "Cleaning house https://docs.example.org/private-room".into(),
                    done_when: Some("trash is out".into()),
                    elapsed_minutes: 14,
                    remaining_minutes: 31,
                }),
            },
        );
        let serialized = context.to_string();
        for private in [
            "secret-user",
            "secret=query",
            "Secret title",
            "habit_feed",
            "Private window fragment",
            "Private rating note",
            "channels/private",
            "private-room",
        ] {
            assert!(!serialized.contains(private), "{serialized}");
        }
        assert_eq!(context["check_in"], "tired");
        assert_eq!(context["today"]["habits_due"], 2);
        assert_eq!(context["today"]["wallet_balance_coins"], 15);
        assert_eq!(context["today"]["reasons"][0], "check Discord");
        assert_eq!(context["recent_ratings"][0], 2);
        assert_eq!(context["similar_pauses"][0]["rating"], 2);
        assert_eq!(
            context["similar_pauses"][0]["foreground"]["matched_percent"],
            0
        );
        assert_eq!(context["last_hour"]["sites"][0]["name"], "x.com");
        assert_eq!(context["active_intention"]["label"], "Cleaning house docs.example.org");
        assert_eq!(context["active_intention"]["done_when"], "trash is out");
        assert_eq!(context["active_intention"]["elapsed_minutes"], 14);
        assert_eq!(context["active_intention"]["remaining_minutes"], 31);
        std::fs::remove_dir_all(scratch).unwrap();
    }
}
