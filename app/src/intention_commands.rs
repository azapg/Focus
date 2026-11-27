//! Windows Tauri commands, AI Gateway parsing with local fallback, and live tick updates for Intentions.

use crate::{
    judge::{self, Failure, Gateway},
    judge_context, now_ms, pause_judge, Core,
};
use chrono::{Local, TimeZone};
use focus_dns::{
    activity::Tracker,
    intentions::{
        self, IntentionView, Outcome, ParseSource, ParsedIntention, EXTEND_MINUTES, MINUTE_MS,
    },
};
use serde_json::json;
use std::{sync::Arc, time::Instant};
use tauri::{AppHandle, Emitter, State};

pub fn current_view(core: &Core, tracker: &Tracker, now: u64) -> IntentionView {
    let day_start = judge_context::day_start(now);
    let spans = core
        .intentions
        .no_intention_since(day_start)
        .map(|since| tracker.spans(since, now))
        .unwrap_or_default();
    let (view, nag_just_triggered) = core.intentions.view(&spans, day_start, now);
    if nag_just_triggered {
        let active_min = view.no_intention_active_ms / MINUTE_MS;
        core.log(&format!("intention nag-shown active_min={active_min}"));
        core.event(
            "intention",
            "nag_shown",
            "ok",
            "ok",
            0,
            json!({ "origin": "laptop", "active_minutes": active_min }),
        );
    }
    view
}

pub fn tick(
    app: &AppHandle,
    core: &Core,
    tracker: &Tracker,
    prev_time_up: &mut Option<String>,
    last_nag_check: &mut u64,
    last_nag_emit: &mut u64,
) {
    let now = now_ms();
    if let Some(active) = core.intentions.active() {
        if now >= active.planned_until {
            if prev_time_up.as_deref() != Some(active.id.as_str()) {
                *prev_time_up = Some(active.id);
                let _ = app.emit("intention-state", current_view(core, tracker, now));
            }
        } else if prev_time_up.is_some() {
            *prev_time_up = None;
        }
        return;
    }

    *prev_time_up = None;
    if now.saturating_sub(*last_nag_check) < 5_000 {
        return;
    }
    *last_nag_check = now;
    let day_start = judge_context::day_start(now);
    let spans = core
        .intentions
        .no_intention_since(day_start)
        .map(|since| tracker.spans(since, now))
        .unwrap_or_default();
    let (view, nag_just_triggered) = core.intentions.view(&spans, day_start, now);
    if nag_just_triggered {
        let active_min = view.no_intention_active_ms / MINUTE_MS;
        core.log(&format!("intention nag-shown active_min={active_min}"));
        core.event(
            "intention",
            "nag_shown",
            "ok",
            "ok",
            0,
            json!({ "origin": "laptop", "active_minutes": active_min }),
        );
        *last_nag_emit = now;
        let _ = app.emit("intention-state", view);
    } else if view.nag_active && now.saturating_sub(*last_nag_emit) >= 30_000 {
        *last_nag_emit = now;
        let _ = app.emit("intention-state", view);
    }
}

fn parse_with_fallback(core: &Core, text: &str, now: u64) -> Result<ParsedIntention, String> {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return Err("Enter what you are working on".into());
    }
    let started = Instant::now();
    let fallback = |cause: &'static str, attempts_len: usize| {
        core.log(&format!(
            "intention parse-fallback cause={cause} attempts={attempts_len}"
        ));
        core.event(
            "intention",
            "parse_fallback",
            "ok",
            cause,
            focus_devices::events::elapsed(started),
            json!({ "origin": "laptop", "parse_source": "local" }),
        );
        intentions::parse_local(trimmed, now)
    };

    let (model, key) = match pause_judge::gateway_credentials(&core.dir) {
        Ok(creds) => creds,
        Err((cause, _)) => return Ok(fallback(cause, 0)),
    };

    let local = Local
        .timestamp_millis_opt(now as i64)
        .single()
        .unwrap_or_else(Local::now);
    let local_time = local.format("%Y-%m-%d %A %H:%M %:z").to_string();
    let mut attempts = Vec::new();
    let result = Gateway::shared().and_then(|gw| {
        gw.parse_intention(
            &key,
            &model,
            trimmed,
            &local_time,
            judge::ask_budget(None, false),
            &mut attempts,
        )
    });
    match result {
        Ok(parsed) => Ok(ParsedIntention {
            label: parsed.label,
            planned_minutes: parsed.planned_minutes,
            until: now.saturating_add(parsed.planned_minutes.saturating_mul(MINUTE_MS)),
            done_when: parsed.done_when,
            raw: intentions::clean_text(trimmed, intentions::MAX_RAW_CHARS),
            parse_source: ParseSource::Gateway,
        }),
        Err(failure) => {
            let (cause, _) = judge::gateway_diagnostic(&attempts, Some(failure)).unwrap_or((
                match failure {
                    Failure::Timeout => "gateway_timeout",
                    Failure::Unavailable => "gateway_unreachable",
                    Failure::Configuration => "gateway_no_key",
                    Failure::Invalid => "gateway_invalid_reply",
                },
                None,
            ));
            Ok(fallback(cause, attempts.len()))
        }
    }
}

#[tauri::command(async)]
pub fn intention_state(core: State<Arc<Core>>, tracker: State<Arc<Tracker>>) -> IntentionView {
    current_view(&core, &tracker, now_ms())
}

#[tauri::command]
pub async fn intention_parse(
    text: String,
    core: State<'_, Arc<Core>>,
) -> Result<ParsedIntention, String> {
    let trimmed = text.trim().to_owned();
    if trimmed.is_empty() {
        return Err("Enter what you are working on".into());
    }
    let core = core.inner().clone();
    let budget = judge::ask_budget(None, false);
    let fallback_core = core.clone();
    let fallback_text = trimmed.clone();
    let started = Instant::now();
    let task = tauri::async_runtime::spawn_blocking(move || {
        parse_with_fallback(&core, &trimmed, now_ms())
    });
    match tokio::time::timeout(budget, task).await {
        Ok(Ok(result)) => result,
        _ => {
            fallback_core.log("intention parse-fallback cause=gateway_timeout attempts=0");
            fallback_core.event(
                "intention",
                "parse_fallback",
                "ok",
                "gateway_timeout",
                focus_devices::events::elapsed(started),
                json!({ "origin": "laptop", "parse_source": "local" }),
            );
            Ok(intentions::parse_local(&fallback_text, now_ms()))
        }
    }
}

#[tauri::command]
pub fn intention_parse_local(text: String) -> Result<ParsedIntention, String> {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return Err("Enter what you are working on".into());
    }
    Ok(intentions::parse_local(trimmed, now_ms()))
}

#[tauri::command(async)]
pub fn intention_start(
    label: String,
    planned_minutes: u64,
    done_when: Option<String>,
    raw: String,
    parse_source: Option<ParseSource>,
    app: AppHandle,
    core: State<Arc<Core>>,
    tracker: State<Arc<Tracker>>,
) -> Result<IntentionView, String> {
    let now = now_ms();
    let source = parse_source.unwrap_or(ParseSource::Local);
    let (rec, replaced) =
        core.intentions
            .start(now, label, planned_minutes, done_when, raw, source)?;
    if let Some(prev) = replaced {
        core.log(&format!("intention end id={} outcome=replaced", prev.id));
        core.event(
            "intention",
            "end",
            "ok",
            "ok",
            0,
            json!({ "origin": "laptop", "intention_outcome": "replaced", "extensions": prev.extensions }),
        );
    }
    core.log(&format!(
        "intention set id={} planned_min={} source={} label={}",
        rec.id,
        rec.planned_minutes,
        rec.parse_source.as_str(),
        rec.label
    ));
    core.event(
        "intention",
        "set",
        "ok",
        "ok",
        0,
        json!({
            "origin": "laptop",
            "planned_minutes": rec.planned_minutes,
            "parse_source": rec.parse_source.as_str(),
        }),
    );
    let view = current_view(&core, &tracker, now);
    let _ = app.emit("intention-state", &view);
    Ok(view)
}

#[tauri::command(async)]
pub fn intention_edit(
    label: String,
    planned_minutes: u64,
    done_when: Option<String>,
    raw: String,
    parse_source: Option<ParseSource>,
    app: AppHandle,
    core: State<Arc<Core>>,
    tracker: State<Arc<Tracker>>,
) -> Result<IntentionView, String> {
    let now = now_ms();
    let source = parse_source.unwrap_or(ParseSource::Local);
    let rec = core
        .intentions
        .edit(now, label, planned_minutes, done_when, raw, source)?;
    core.log(&format!(
        "intention edit id={} planned_min={} source={} label={}",
        rec.id,
        rec.planned_minutes,
        rec.parse_source.as_str(),
        rec.label
    ));
    core.event(
        "intention",
        "edit",
        "ok",
        "ok",
        0,
        json!({
            "origin": "laptop",
            "planned_minutes": rec.planned_minutes,
            "parse_source": rec.parse_source.as_str(),
        }),
    );
    let view = current_view(&core, &tracker, now);
    let _ = app.emit("intention-state", &view);
    Ok(view)
}

#[tauri::command(async)]
pub fn intention_extend(
    minutes: Option<u64>,
    app: AppHandle,
    core: State<Arc<Core>>,
    tracker: State<Arc<Tracker>>,
) -> Result<IntentionView, String> {
    let now = now_ms();
    let added = minutes.unwrap_or(EXTEND_MINUTES);
    let rec = core.intentions.extend(now, added)?;
    core.log(&format!(
        "intention extend id={} added_min={} extensions={}",
        rec.id, added, rec.extensions
    ));
    core.event(
        "intention",
        "extend",
        "ok",
        "ok",
        0,
        json!({
            "origin": "laptop",
            "planned_minutes": added,
            "extensions": rec.extensions,
        }),
    );
    let view = current_view(&core, &tracker, now);
    let _ = app.emit("intention-state", &view);
    Ok(view)
}

#[tauri::command(async)]
pub fn intention_end(
    outcome: Outcome,
    app: AppHandle,
    core: State<Arc<Core>>,
    tracker: State<Arc<Tracker>>,
) -> Result<IntentionView, String> {
    let now = now_ms();
    let rec = core.intentions.end(now, outcome)?;
    let actual_min = rec
        .end
        .unwrap_or(now)
        .saturating_sub(rec.start)
        .div_ceil(MINUTE_MS);
    core.log(&format!(
        "intention end id={} outcome={} actual_min={} extensions={}",
        rec.id,
        outcome.as_str(),
        actual_min,
        rec.extensions
    ));
    core.log(&format!(
        "intention outcome id={} outcome={}",
        rec.id,
        outcome.as_str()
    ));
    let fields = json!({
        "origin": "laptop",
        "intention_outcome": outcome.as_str(),
        "planned_minutes": rec.planned_minutes,
        "active_minutes": actual_min,
        "extensions": rec.extensions,
    });
    core.event("intention", "end", "ok", "ok", 0, fields.clone());
    core.event("intention", "outcome", "ok", "ok", 0, fields);
    let view = current_view(&core, &tracker, now);
    let _ = app.emit("intention-state", &view);
    Ok(view)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_falls_back_to_local_without_gateway_key_and_logs() {
        let fixture = crate::dns_tests::Fixture::new(|_| Ok(String::new()));
        let now = now_ms();
        let parsed = parse_with_fallback(
            &fixture.core,
            "cleaning the house, 45 min, done when the trash is out",
            now,
        )
        .unwrap();
        assert_eq!(parsed.parse_source, ParseSource::Local);
        assert_eq!(parsed.label, "Cleaning the house");
        assert_eq!(parsed.planned_minutes, 45);
        assert_eq!(parsed.done_when.as_deref(), Some("the trash is out"));
        let log = std::fs::read_to_string(fixture.core.dir.join("focusdns.log")).unwrap_or_default();
        assert!(log.contains("intention parse-fallback cause=gateway_no_key"), "{log}");
    }
}
