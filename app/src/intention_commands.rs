//! Windows Tauri commands, AI Gateway parsing with local fallback, and live tick updates for Intentions.

use crate::{
    device_commands::{Devices, LaptopHost},
    judge::{self, Failure, Gateway},
    judge_context, now_ms, pause_judge, Core,
};
use chrono::{Local, TimeZone};
use focus_dns::{
    activity::Tracker,
    intentions::{
        self, IntentionNote, IntentionRec, IntentionView, Outcome, ParseSource, ParsedIntention,
        EXTEND_MINUTES, MINUTE_MS,
    },
};
use serde_json::json;
use std::{sync::Arc, time::Instant};
use tauri::{AppHandle, Emitter, State};

#[tauri::command(async)]
pub fn intention_save_note(
    note: IntentionNote,
    core: State<Arc<Core>>,
) -> Result<Option<IntentionNote>, String> {
    core.intentions.save_note(note, now_ms())
}

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

/// Oldest change a device may replay after being offline.
const MAX_REPLAY_MS: u64 = 12 * 60 * MINUTE_MS;

/// Clamps a change time reported by another device to the last 12 hours.
pub fn replay_time(at: Option<u64>, now: u64) -> u64 {
    at.unwrap_or(now).min(now).max(now.saturating_sub(MAX_REPLAY_MS))
}

/// A queued phone change is stale when the laptop already moved on: a newer intention
/// started here, or the phone refers to an intention that is no longer active.
pub fn is_stale(active: Option<&IntentionRec>, target_id: Option<&str>, at: u64) -> bool {
    match (active, target_id) {
        (Some(active), Some(id)) => active.id != id,
        (None, Some(_)) => true,
        (Some(active), None) => active.start > at,
        (None, None) => false,
    }
}

pub struct StartInput {
    pub label: String,
    pub planned_minutes: u64,
    pub done_when: Option<String>,
    pub raw: String,
    pub parse_source: ParseSource,
}

/// Starts an intention, logging it under `origin` ("laptop" or "phone").
pub fn start_intention(core: &Core, at: u64, input: StartInput, origin: &str) -> Result<IntentionRec, String> {
    let (rec, replaced) = core.intentions.start(
        at, input.label, input.planned_minutes, input.done_when, input.raw, input.parse_source,
    )?;
    if let Some(prev) = replaced {
        core.log(&format!("intention end id={} outcome=replaced origin={origin}", prev.id));
        core.event(
            "intention",
            "end",
            "ok",
            "ok",
            0,
            json!({ "origin": origin, "intention_outcome": "replaced", "extensions": prev.extensions }),
        );
    }
    core.log(&format!(
        "intention set id={} planned_min={} source={} origin={origin} label={}",
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
            "origin": origin,
            "planned_minutes": rec.planned_minutes,
            "parse_source": rec.parse_source.as_str(),
        }),
    );
    Ok(rec)
}

/// Adds minutes to the active intention, logging it under `origin`.
pub fn extend_intention(core: &Core, at: u64, minutes: u64, origin: &str) -> Result<IntentionRec, String> {
    let rec = core.intentions.extend(at, minutes)?;
    core.log(&format!(
        "intention extend id={} added_min={} extensions={} origin={origin}",
        rec.id, minutes, rec.extensions
    ));
    core.event(
        "intention",
        "extend",
        "ok",
        "ok",
        0,
        json!({ "origin": origin, "planned_minutes": minutes, "extensions": rec.extensions }),
    );
    Ok(rec)
}

/// Ends the active intention with `outcome`, logging it under `origin`.
pub fn end_intention(core: &Core, at: u64, outcome: Outcome, origin: &str) -> Result<IntentionRec, String> {
    let rec = core.intentions.end(at, outcome)?;
    let actual_min = rec.end.unwrap_or(at).saturating_sub(rec.start).div_ceil(MINUTE_MS);
    core.log(&format!(
        "intention end id={} outcome={} actual_min={} extensions={} origin={origin}",
        rec.id,
        outcome.as_str(),
        actual_min,
        rec.extensions
    ));
    let fields = json!({
        "origin": origin,
        "intention_outcome": outcome.as_str(),
        "planned_minutes": rec.planned_minutes,
        "active_minutes": actual_min,
        "extensions": rec.extensions,
    });
    core.event("intention", "end", "ok", "ok", 0, fields.clone());
    core.event("intention", "outcome", "ok", "ok", 0, fields);
    Ok(rec)
}

/// Handles intention changes sent by the phone over LAN. Replies with whether the change
/// applied and the laptop's current intention, which the phone adopts either way.
pub fn remote(core: &Core, input: &serde_json::Value) -> Result<serde_json::Value, String> {
    let now = now_ms();
    let at = replay_time(input["at"].as_u64(), now);
    let target = input["id"].as_str();
    let action = input["action"].as_str().unwrap_or("");
    let active = core.intentions.active();
    let stale = is_stale(active.as_ref(), if action == "intention_start" { None } else { target }, at);
    if stale {
        core.log(&format!("intention phone-change skipped action={action} cause=stale"));
        core.event("intention", "sync", "skipped", "stale", 0, json!({ "origin": "phone" }));
    } else {
        match action {
            "intention_start" => {
                let input = StartInput {
                    label: input["label"].as_str().unwrap_or("").to_owned(),
                    planned_minutes: input["planned_minutes"].as_u64().unwrap_or(intentions::DEFAULT_MINUTES),
                    done_when: input["done_when"].as_str().map(str::to_owned),
                    raw: input["raw"].as_str().unwrap_or("").to_owned(),
                    parse_source: serde_json::from_value(input["parse_source"].clone()).unwrap_or(ParseSource::Local),
                };
                start_intention(core, at, input, "phone")?;
            }
            "intention_extend" => {
                extend_intention(core, at, input["minutes"].as_u64().unwrap_or(EXTEND_MINUTES), "phone")?;
            }
            "intention_end" => {
                let outcome = serde_json::from_value(input["outcome"].clone())
                    .map_err(|_| "Choose done, partly or no".to_string())?;
                end_intention(core, at, outcome, "phone")?;
            }
            _ => return Err("Unsupported intention change".into()),
        }
    }
    Ok(json!({ "applied": !stale, "intention": core.intentions.active(), "now": now }))
}

/// Parses a sentence for the phone, with the same Gateway-then-local fallback as Windows.
pub fn remote_parse(core: &Core, text: &str) -> Result<ParsedIntention, String> {
    parse_with_fallback(core, text, now_ms())
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
    devices: State<Arc<Devices>>,
    host: State<Arc<LaptopHost>>,
) -> Result<IntentionView, String> {
    let now = now_ms();
    let source = parse_source.unwrap_or(ParseSource::Local);
    start_intention(
        &core,
        now,
        StartInput { label, planned_minutes, done_when, raw, parse_source: source },
        "laptop",
    )?;
    Ok(changed(app, &core, &tracker, &devices, &host, now))
}

/// Refreshes the Windows bar and pushes the new intention to paired phones.
fn changed(
    app: AppHandle,
    core: &State<Arc<Core>>,
    tracker: &State<Arc<Tracker>>,
    devices: &State<Arc<Devices>>,
    host: &State<Arc<LaptopHost>>,
    now: u64,
) -> IntentionView {
    let view = current_view(core, tracker, now);
    let _ = app.emit("intention-state", &view);
    crate::device_commands::trigger_phone_sync_async(
        app,
        devices.inner().clone(),
        core.inner().clone(),
        tracker.inner().clone(),
        host.inner().clone(),
    );
    view
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
    devices: State<Arc<Devices>>,
    host: State<Arc<LaptopHost>>,
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
    Ok(changed(app, &core, &tracker, &devices, &host, now))
}

#[tauri::command(async)]
pub fn intention_extend(
    minutes: Option<u64>,
    app: AppHandle,
    core: State<Arc<Core>>,
    tracker: State<Arc<Tracker>>,
    devices: State<Arc<Devices>>,
    host: State<Arc<LaptopHost>>,
) -> Result<IntentionView, String> {
    let now = now_ms();
    extend_intention(&core, now, minutes.unwrap_or(EXTEND_MINUTES), "laptop")?;
    Ok(changed(app, &core, &tracker, &devices, &host, now))
}

#[tauri::command(async)]
pub fn intention_end(
    outcome: Outcome,
    app: AppHandle,
    core: State<Arc<Core>>,
    tracker: State<Arc<Tracker>>,
    devices: State<Arc<Devices>>,
    host: State<Arc<LaptopHost>>,
) -> Result<IntentionView, String> {
    let now = now_ms();
    end_intention(&core, now, outcome, "laptop")?;
    Ok(changed(app, &core, &tracker, &devices, &host, now))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn phone_changes_apply_in_order_and_older_ones_are_skipped() {
        let fixture = crate::dns_tests::Fixture::new(|_| Ok(String::new()));
        let core = &fixture.core;
        let now = now_ms();

        // A start the phone queued 20 minutes ago while the laptop was asleep keeps its time.
        let reply = remote(core, &json!({
            "action": "intention_start", "at": now - 20 * MINUTE_MS, "label": "Cleaning house",
            "planned_minutes": 45, "done_when": "the trash is out", "raw": "cleaning house 45 min",
            "parse_source": "local",
        }))
        .unwrap();
        assert_eq!(reply["applied"], true);
        let id = reply["intention"]["id"].as_str().unwrap().to_owned();
        assert_eq!(reply["intention"]["start"], now - 20 * MINUTE_MS);
        assert_eq!(reply["intention"]["planned_until"], now + 25 * MINUTE_MS);

        let reply = remote(core, &json!({ "action": "intention_extend", "id": id, "at": now, "minutes": 10 })).unwrap();
        assert_eq!(reply["applied"], true);
        assert_eq!(reply["intention"]["planned_until"], now + 35 * MINUTE_MS);

        // A change for an intention the laptop no longer runs is skipped, not applied to the new one.
        let reply = remote(core, &json!({ "action": "intention_end", "id": "phone-1", "at": now, "outcome": "done" })).unwrap();
        assert_eq!(reply["applied"], false);
        assert_eq!(reply["intention"]["id"], id.as_str());

        // A start older than the laptop's current intention loses.
        let reply = remote(core, &json!({
            "action": "intention_start", "at": now - 30 * MINUTE_MS, "label": "Older", "planned_minutes": 30,
        }))
        .unwrap();
        assert_eq!(reply["applied"], false);
        assert_eq!(reply["intention"]["label"], "Cleaning house");

        let reply = remote(core, &json!({ "action": "intention_end", "id": id, "at": now, "outcome": "done" })).unwrap();
        assert_eq!(reply["applied"], true);
        assert!(reply["intention"].is_null());
        let log = std::fs::read_to_string(core.dir.join("focusdns.log")).unwrap_or_default();
        assert!(log.contains("origin=phone"), "{log}");
        assert!(log.contains("intention phone-change skipped action=intention_start cause=stale"), "{log}");
    }

    #[test]
    fn replayed_changes_are_limited_to_twelve_hours() {
        let now = 100 * 60 * MINUTE_MS;
        assert_eq!(replay_time(None, now), now);
        assert_eq!(replay_time(Some(now + 5_000), now), now);
        assert_eq!(replay_time(Some(1), now), now - 12 * 60 * MINUTE_MS);
    }

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
