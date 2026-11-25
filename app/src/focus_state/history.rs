//! Converts the existing local pause and device histories into timestamp-only signals.

use super::Config;
use crate::pause_history::read_pauses;
use chrono::{Local, NaiveDateTime, TimeZone};
use serde_json::Value;
use std::{fs, io, path::Path, time::SystemTime};

const PHONE_QUIET_MS: u64 = 120_000;
pub const PANEL_LOG: &str = "focus-panel-opens.jsonl";

pub fn pause_restarts(text: &str, config: &Config) -> Vec<u64> {
    let mut pauses = read_pauses(text, None, 0);
    pauses.sort_by_key(|p| p.t);
    pauses
        .windows(2)
        .filter_map(|pair| {
            let previous = &pair[0];
            let next = &pair[1];
            (previous.end > previous.t
                && next
                    .t
                    .checked_sub(previous.end)
                    .is_some_and(|d| d <= config.pause_restart_seconds * 1000))
            .then_some(next.t)
        })
        .collect()
}

/// Phone snapshots have foreground usage spans. Activity beginning after a
/// quiet gap is a pickup proxy. Require a previous span to avoid counting the
/// start of a clipped sync range as a new pickup.
pub fn inferred_pickups(registry: &Value) -> Option<Vec<u64>> {
    let mut pickups = Vec::new();
    let mut available = false;
    for device in registry["devices"].as_array()? {
        let snapshot = &device["snapshot"];
        if snapshot["kind"]
            .as_str()
            .or_else(|| device["kind"].as_str())
            != Some("phone")
        {
            continue;
        }
        let Some(spans) = snapshot["spans"].as_array() else {
            continue;
        };
        available = true;
        let mut intervals: Vec<_> = spans
            .iter()
            .filter_map(|s| {
                let (start, end) = (s["s"].as_u64()?, s["e"].as_u64()?);
                (end > start).then_some((start, end))
            })
            .collect();
        intervals.sort_unstable();
        let mut previous_end: Option<u64> = None;
        for (start, end) in intervals {
            if previous_end.is_some_and(|previous| start.saturating_sub(previous) >= PHONE_QUIET_MS)
            {
                pickups.push(start);
            }
            previous_end = Some(previous_end.unwrap_or(0).max(end));
        }
    }
    pickups.sort_unstable();
    pickups.dedup();
    available.then_some(pickups)
}

pub fn panel_timestamps(text: &str) -> Vec<u64> {
    let mut times: Vec<_> = text
        .lines()
        .filter_map(|line| line.trim().parse().ok())
        .collect();
    times.sort_unstable();
    times
}

pub fn is_panel_open(message: &str) -> bool {
    matches!(
        message,
        "stats panel opened (today)" | "stats panel opened (week)"
    )
}

pub fn legacy_panel_timestamps(text: &str) -> impl Iterator<Item = u64> + '_ {
    text.lines().filter_map(|line| {
        let (when, entry) = line.split_at_checked(20)?;
        if !is_panel_open(entry.strip_prefix("ui: ")?) {
            return None;
        }
        let when = NaiveDateTime::parse_from_str(when.trim_end(), "%Y-%m-%d %H:%M:%S").ok()?;
        let timestamp = Local
            .from_local_datetime(&when)
            .earliest()?
            .timestamp_millis();
        u64::try_from(timestamp).ok()
    })
}

pub fn read_optional(path: &Path) -> io::Result<String> {
    match fs::read_to_string(path) {
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(String::new()),
        other => other,
    }
}

/// Poll existing logs without reparsing unchanged files every sampling tick.
#[derive(Default)]
pub struct CachedInput {
    signature: Option<(Option<SystemTime>, u64)>,
}

impl CachedInput {
    pub fn refresh(&mut self, path: &Path) -> io::Result<Option<String>> {
        let signature = match fs::metadata(path) {
            Ok(metadata) => Some((metadata.modified().ok(), metadata.len())),
            Err(e) if e.kind() == io::ErrorKind::NotFound => None,
            Err(e) => return Err(e),
        };
        if signature == self.signature {
            return Ok(None);
        }
        let text = read_optional(path)?;
        self.signature = signature;
        Ok(Some(text))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn quick_restarts_use_the_actual_pause_end() {
        let text = concat!(
            "{\"type\":\"pause\",\"t\":1000,\"until\":301000}\n",
            "{\"type\":\"resume\",\"paused_at\":1000,\"t\":330000}\n",
            "{\"type\":\"pause\",\"t\":440000,\"until\":1040000}\n",
            "{\"type\":\"resume\",\"paused_at\":440000,\"t\":900000}\n",
            "{\"type\":\"pause\",\"t\":1030000,\"until\":2830000}\n",
        );
        assert_eq!(pause_restarts(text, &Config::default()), [440000]);
    }

    #[test]
    fn pickups_require_phone_usage_after_a_quiet_gap() {
        let registry = json!({"devices": [
            {"kind": "phone", "snapshot": {"spans": [
                {"s": 50, "e": 100}, {"s": 100, "e": 200}, {"s": 120200, "e": 121000},
            ]}},
            {"kind": "tv", "snapshot": {"spans": [
                {"s": 50, "e": 100}, {"s": 121000, "e": 130000},
            ]}},
        ]});
        assert_eq!(inferred_pickups(&registry), Some(vec![120200]));
        assert_eq!(inferred_pickups(&json!({"devices": []})), None);
    }
}
