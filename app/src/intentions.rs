//! Daily intentions: one-sentence parsing, append-only journal, active-time nag tracking,
//! and restore-on-launch.

use crate::activity::Span;
use chrono::{Local, TimeZone, Timelike};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    fs::{self, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    sync::Mutex,
};

pub const MINUTE_MS: u64 = 60_000;
pub const DEFAULT_MINUTES: u64 = 60;
pub const MIN_MINUTES: u64 = 1;
pub const MAX_MINUTES: u64 = 240; // 4 hours
pub const EXTEND_MINUTES: u64 = 10;
pub const NAG_THRESHOLD_MS: u64 = 15 * MINUTE_MS;
pub const MAX_LABEL_CHARS: usize = 80;
pub const MAX_DONE_WHEN_CHARS: usize = 140;
pub const MAX_RAW_CHARS: usize = 300;
const MAX_NOTE_CHARS: usize = 500;
const FILENAME: &str = "intentions.jsonl";
const NOTES_FILENAME: &str = "intention-notes.json";

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ParseSource {
    Gateway,
    Local,
}

impl ParseSource {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Gateway => "gateway",
            Self::Local => "local",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Outcome {
    Done,
    Partly,
    No,
    Cleared,
    Replaced,
}

impl Outcome {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Done => "done",
            Self::Partly => "partly",
            Self::No => "no",
            Self::Cleared => "cleared",
            Self::Replaced => "replaced",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ParsedIntention {
    pub label: String,
    pub planned_minutes: u64,
    pub until: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub done_when: Option<String>,
    pub raw: String,
    pub parse_source: ParseSource,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct IntentionRec {
    pub id: String,
    pub start: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub end: Option<u64>,
    pub planned_until: u64,
    pub planned_minutes: u64,
    pub label: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub done_when: Option<String>,
    pub raw: String,
    pub parse_source: ParseSource,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub outcome: Option<Outcome>,
    #[serde(default)]
    pub extensions: u32,
    #[serde(default)]
    pub active: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct IntentionNote {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub intention_id: Option<String>,
    pub start: u64,
    pub end: u64,
    pub text: String,
}

impl IntentionNote {
    fn same_block(&self, other: &Self) -> bool {
        self.intention_id == other.intention_id
            && (self.intention_id.is_some() || self.start == other.start)
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct IntentionView {
    pub active: Option<IntentionRec>,
    pub time_up: bool,
    pub no_intention_active_ms: u64,
    pub nag_active: bool,
    pub now: u64,
}

pub fn clamp_minutes(minutes: u64) -> u64 {
    minutes.clamp(MIN_MINUTES, MAX_MINUTES)
}

pub fn clean_text(raw: &str, max_chars: usize) -> String {
    raw.split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .take(max_chars)
        .collect()
}

pub fn capitalize_first(text: &str) -> String {
    let mut chars = text.chars();
    match chars.next() {
        Some(first) if first.is_ascii_lowercase() => {
            let mut out = String::with_capacity(text.len());
            out.push(first.to_ascii_uppercase());
            out.push_str(chars.as_str());
            out
        }
        _ => text.to_owned(),
    }
}

/// Parses a single natural-language intention sentence locally using the host's local clock.
pub fn parse_local(raw: &str, now_ms: u64) -> ParsedIntention {
    let local = Local
        .timestamp_millis_opt(now_ms as i64)
        .single()
        .unwrap_or_else(Local::now);
    let local_minutes = local.hour() * 60 + local.minute();
    parse_local_at(raw, now_ms, local_minutes)
}

/// Deterministic local parser accepting the local clock as minutes since midnight (0..1440).
pub fn parse_local_at(raw: &str, now_ms: u64, local_minutes: u32) -> ParsedIntention {
    let cleaned_raw = clean_text(raw, MAX_RAW_CHARS);
    let (before_done, done_when) = split_done_when(&cleaned_raw);
    let (remaining, found_minutes) = extract_duration(before_done, local_minutes);
    let planned_minutes = clamp_minutes(found_minutes.unwrap_or(DEFAULT_MINUTES));
    let label = clean_label(&remaining, &cleaned_raw);
    ParsedIntention {
        label,
        planned_minutes,
        until: now_ms.saturating_add(planned_minutes.saturating_mul(MINUTE_MS)),
        done_when,
        raw: cleaned_raw,
        parse_source: ParseSource::Local,
    }
}

fn split_done_when(text: &str) -> (&str, Option<String>) {
    let lower = text.to_ascii_lowercase();
    let bytes = lower.as_bytes();
    const MARKERS: [&str; 5] = ["done when ", "done once ", "done after ", "done if ", "done:"];
    let mut best: Option<(usize, usize)> = None;
    for marker in MARKERS {
        let mut search_from = 0;
        while let Some(rel) = lower[search_from..].find(marker) {
            let idx = search_from + rel;
            let boundary_ok = idx == 0
                || bytes[idx - 1].is_ascii_whitespace()
                || matches!(bytes[idx - 1], b',' | b';' | b'-' | b':');
            if boundary_ok && best.is_none_or(|(b, _)| idx < b) {
                best = Some((idx, marker.len()));
                break;
            }
            search_from = idx + 1;
        }
    }
    let Some((idx, len)) = best else {
        return (text, None);
    };
    let head = text[..idx].trim_end_matches(|c: char| c.is_whitespace() || matches!(c, ',' | ';' | '-' | '—' | ':'));
    let tail = text[idx + len..]
        .trim()
        .trim_end_matches(['.', ',', ';'])
        .trim();
    let done = (!tail.is_empty()).then(|| clean_text(tail, MAX_DONE_WHEN_CHARS));
    (head, done)
}

fn extract_duration(text: &str, local_minutes: u32) -> (String, Option<u64>) {
    if let Some((range, mins)) = find_until_clock(text, local_minutes) {
        return (remove_range(text, range), Some(mins));
    }
    if let Some((range, mins)) = find_named_duration(text) {
        return (remove_range(text, range), Some(mins));
    }
    if let Some((range, mins)) = find_numeric_duration(text) {
        return (remove_range(text, range), Some(mins));
    }
    (text.to_owned(), None)
}

fn remove_range(text: &str, range: std::ops::Range<usize>) -> String {
    let left = text[..range.start].trim_end();
    let right = text[range.end..].trim_start();
    if left.is_empty() {
        right.to_owned()
    } else if right.is_empty() {
        left.to_owned()
    } else {
        format!("{left} {right}")
    }
}

fn find_named_duration(text: &str) -> Option<(std::ops::Range<usize>, u64)> {
    let lower = text.to_ascii_lowercase();
    let bytes = lower.as_bytes();
    for (phrase, mins) in [
        ("for half an hour", 30u64),
        ("half an hour", 30),
        ("for an hour", 60),
        ("an hour", 60),
    ] {
        let mut from = 0;
        while let Some(rel) = lower[from..].find(phrase) {
            let start = from + rel;
            let end = start + phrase.len();
            let left_ok = start == 0 || !bytes[start - 1].is_ascii_alphanumeric();
            let right_ok = end == bytes.len() || !bytes[end].is_ascii_alphanumeric();
            if left_ok && right_ok {
                return Some((start..end, mins));
            }
            from = start + 1;
        }
    }
    None
}

fn find_until_clock(text: &str, local_minutes: u32) -> Option<(std::ops::Range<usize>, u64)> {
    let lower = text.to_ascii_lowercase();
    let bytes = lower.as_bytes();
    for kw in ["until ", "till ", "til ", "by "] {
        let mut from = 0;
        while let Some(rel) = lower[from..].find(kw) {
            let kw_start = from + rel;
            let left_ok = kw_start == 0 || !bytes[kw_start - 1].is_ascii_alphanumeric();
            if left_ok {
                let mut pos = kw_start + kw.len();
                while pos < bytes.len() && bytes[pos].is_ascii_whitespace() {
                    pos += 1;
                }
                if let Some((end, target)) = parse_clock_at(&lower, pos, local_minutes) {
                    let diff = target.saturating_sub(local_minutes);
                    if diff > 0 {
                        return Some((kw_start..end, u64::from(diff)));
                    }
                }
            }
            from = kw_start + 1;
        }
    }
    None
}

fn parse_clock_at(lower: &str, start: usize, local_minutes: u32) -> Option<(usize, u32)> {
    let bytes = lower.as_bytes();
    let mut i = start;
    let h_start = i;
    while i < bytes.len() && bytes[i].is_ascii_digit() {
        i += 1;
    }
    if i == h_start || i - h_start > 2 {
        return None;
    }
    let hour: u32 = lower[h_start..i].parse().ok()?;
    let mut minute = 0u32;
    let mut has_colon = false;
    if i < bytes.len() && bytes[i] == b':' {
        has_colon = true;
        i += 1;
        let m_start = i;
        while i < bytes.len() && bytes[i].is_ascii_digit() {
            i += 1;
        }
        if i - m_start != 2 {
            return None;
        }
        minute = lower[m_start..i].parse().ok()?;
        if minute >= 60 {
            return None;
        }
    }
    let mut meridiem = None;
    let mut j = i;
    while j < bytes.len() && bytes[j].is_ascii_whitespace() {
        j += 1;
    }
    if lower[j..].starts_with("am")
        && (j + 2 == bytes.len() || !bytes[j + 2].is_ascii_alphanumeric())
    {
        meridiem = Some(false);
        i = j + 2;
    } else if lower[j..].starts_with("pm")
        && (j + 2 == bytes.len() || !bytes[j + 2].is_ascii_alphanumeric())
    {
        meridiem = Some(true);
        i = j + 2;
    } else if !has_colon {
        // Bare number without ":MM" or "am/pm" (e.g. "by 3") is too ambiguous unless meridiem is explicit.
        return None;
    }
    if i < bytes.len() && bytes[i].is_ascii_alphanumeric() {
        return None;
    }

    let mut target = match meridiem {
        Some(false) => {
            if !(1..=12).contains(&hour) {
                return None;
            }
            (hour % 12) * 60 + minute
        }
        Some(true) => {
            if !(1..=12).contains(&hour) {
                return None;
            }
            (hour % 12 + 12) * 60 + minute
        }
        None => {
            if hour > 23 {
                return None;
            }
            hour * 60 + minute
        }
    };
    if target <= local_minutes {
        if meridiem.is_none() && hour >= 1 && hour <= 12 && target + 12 * 60 > local_minutes {
            target += 12 * 60;
        } else {
            target += 24 * 60;
        }
    }
    Some((i, target))
}

fn find_numeric_duration(text: &str) -> Option<(std::ops::Range<usize>, u64)> {
    let lower = text.to_ascii_lowercase();
    let bytes = lower.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if !bytes[i].is_ascii_digit() {
            i += 1;
            continue;
        }
        let left_ok = i == 0 || !bytes[i - 1].is_ascii_alphanumeric();
        if !left_ok {
            i += 1;
            continue;
        }
        let num_start = i;
        let Some((val, after_num)) = parse_number_at(&lower, num_start) else {
            i += 1;
            continue;
        };
        let mut unit_start = after_num;
        while unit_start < bytes.len() && bytes[unit_start].is_ascii_whitespace() {
            unit_start += 1;
        }
        let Some((unit, mut end)) = parse_unit_at(&lower, unit_start) else {
            i = after_num;
            continue;
        };

        let mut total_mins = match unit {
            Unit::Hour => (val * 60.0).round() as u64,
            Unit::Minute => val.round() as u64,
        };

        // Check for compound hour + minute, e.g. "1h 30m", "1h30m", "1 hour and 15 min".
        if unit == Unit::Hour {
            let mut next = end;
            while next < bytes.len() && bytes[next].is_ascii_whitespace() {
                next += 1;
            }
            if lower[next..].starts_with("and ") {
                next += 4;
                while next < bytes.len() && bytes[next].is_ascii_whitespace() {
                    next += 1;
                }
            }
            if let Some((min_val, after_min)) = parse_number_at(&lower, next) {
                let mut m_unit = after_min;
                while m_unit < bytes.len() && bytes[m_unit].is_ascii_whitespace() {
                    m_unit += 1;
                }
                if let Some((Unit::Minute, m_end)) = parse_unit_at(&lower, m_unit) {
                    total_mins = total_mins.saturating_add(min_val.round() as u64);
                    end = m_end;
                } else if m_unit == after_min && after_min < bytes.len() && !bytes[after_min].is_ascii_alphanumeric() {
                    // Handles compact "1h30"
                    let m = min_val.round() as u64;
                    if m > 0 && m < 60 && lower[after_num..next].trim() == "h" {
                        total_mins = total_mins.saturating_add(m);
                        end = after_min;
                    }
                }
            }
        }

        if total_mins == 0 {
            i = end;
            continue;
        }

        // Expand left to include an optional preceding "for ".
        let mut range_start = num_start;
        let prefix = lower[..num_start].trim_end();
        if let Some(before_for) = prefix.strip_suffix("for") {
            let for_pos = before_for.len();
            if for_pos == 0 || !bytes[for_pos - 1].is_ascii_alphanumeric() {
                range_start = for_pos;
            }
        }
        return Some((range_start..end, total_mins));
    }
    None
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Unit {
    Hour,
    Minute,
}

fn parse_number_at(lower: &str, start: usize) -> Option<(f64, usize)> {
    let bytes = lower.as_bytes();
    let mut i = start;
    while i < bytes.len() && bytes[i].is_ascii_digit() {
        i += 1;
    }
    if i == start {
        return None;
    }
    if i + 1 < bytes.len() && bytes[i] == b'.' && bytes[i + 1].is_ascii_digit() {
        i += 1;
        while i < bytes.len() && bytes[i].is_ascii_digit() {
            i += 1;
        }
    }
    let val: f64 = lower[start..i].parse().ok()?;
    val.is_finite().then_some((val, i))
}

fn parse_unit_at(lower: &str, start: usize) -> Option<(Unit, usize)> {
    let bytes = lower.as_bytes();
    const UNITS: [(&str, Unit); 10] = [
        ("minutes", Unit::Minute),
        ("minute", Unit::Minute),
        ("mins", Unit::Minute),
        ("min", Unit::Minute),
        ("hours", Unit::Hour),
        ("hour", Unit::Hour),
        ("hrs", Unit::Hour),
        ("hr", Unit::Hour),
        ("m", Unit::Minute),
        ("h", Unit::Hour),
    ];
    for (token, unit) in UNITS {
        if lower[start..].starts_with(token) {
            let end = start + token.len();
            if end == bytes.len() || !bytes[end].is_ascii_alphanumeric() {
                return Some((unit, end));
            }
        }
    }
    None
}

fn clean_label(remaining: &str, fallback_raw: &str) -> String {
    let mut s = remaining.trim();
    // Strip leftover boundary punctuation and separators repeatedly.
    loop {
        let next = s
            .trim()
            .trim_matches(|c: char| matches!(c, ',' | ';' | '-' | '—' | '·' | ':'))
            .trim();
        if next.len() == s.len() {
            break;
        }
        s = next;
    }
    // Collapse stray ", ," or " , " left when duration was in the middle.
    let mut collapsed = String::with_capacity(s.len());
    let mut prev_sep = false;
    for part in s.split_whitespace() {
        let trimmed = part.trim_matches(|c: char| matches!(c, ',' | ';'));
        if trimmed.is_empty() {
            prev_sep = true;
            continue;
        }
        if !collapsed.is_empty() {
            collapsed.push(' ');
        }
        if prev_sep && collapsed.ends_with(", ") {
            // already separated
        }
        collapsed.push_str(part.trim_end_matches([';', ',']));
        prev_sep = false;
    }
    let cleaned = clean_text(
        collapsed
            .trim()
            .trim_matches(|c: char| matches!(c, ',' | ';' | '-' | '—' | ':')),
        MAX_LABEL_CHARS,
    );
    let chosen = if cleaned.is_empty() {
        clean_text(fallback_raw, MAX_LABEL_CHARS)
    } else {
        cleaned
    };
    capitalize_first(&chosen)
}

/// Parses an append-only `intentions.jsonl` log into deduplicated chronological intention records.
pub fn read_intentions(text: &str) -> Vec<IntentionRec> {
    let mut list: Vec<IntentionRec> = Vec::new();
    for raw in text.lines() {
        let Ok(v) = serde_json::from_str::<Value>(raw) else {
            continue;
        };
        match v["type"].as_str() {
            Some("start") => {
                let (Some(id), Some(start), Some(planned_until), Some(label)) = (
                    v["id"].as_str(),
                    v["start"].as_u64(),
                    v["planned_until"].as_u64(),
                    v["label"].as_str(),
                ) else {
                    continue;
                };
                if id.is_empty() || label.trim().is_empty() {
                    continue;
                }
                for prev in &mut list {
                    if prev.active {
                        prev.active = false;
                        prev.end = Some(start.max(prev.start));
                        prev.outcome.get_or_insert(Outcome::Replaced);
                    }
                }
                let planned_minutes = v["planned_minutes"]
                    .as_u64()
                    .filter(|&m| m > 0)
                    .unwrap_or_else(|| {
                        clamp_minutes(planned_until.saturating_sub(start).div_ceil(MINUTE_MS).max(1))
                    });
                let parse_source = serde_json::from_value(v["parse_source"].clone())
                    .unwrap_or(ParseSource::Local);
                let done_when = v["done_when"]
                    .as_str()
                    .map(|s| clean_text(s, MAX_DONE_WHEN_CHARS))
                    .filter(|s| !s.is_empty());
                list.push(IntentionRec {
                    id: id.to_owned(),
                    start,
                    end: None,
                    planned_until: planned_until.max(start),
                    planned_minutes,
                    label: clean_text(label, MAX_LABEL_CHARS),
                    done_when,
                    raw: clean_text(v["raw"].as_str().unwrap_or(label), MAX_RAW_CHARS),
                    parse_source,
                    outcome: None,
                    extensions: 0,
                    active: true,
                });
            }
            Some("edit") => {
                let Some(id) = v["id"].as_str() else { continue };
                let Some(rec) = list.iter_mut().rev().find(|r| r.id == id && r.active) else {
                    continue;
                };
                if let Some(label) = v["label"].as_str().map(|s| clean_text(s, MAX_LABEL_CHARS)).filter(|s| !s.is_empty()) {
                    rec.label = label;
                }
                if v.get("done_when").is_some() {
                    rec.done_when = v["done_when"]
                        .as_str()
                        .map(|s| clean_text(s, MAX_DONE_WHEN_CHARS))
                        .filter(|s| !s.is_empty());
                }
                if let Some(minutes) = v["planned_minutes"].as_u64().filter(|&m| m > 0) {
                    rec.planned_minutes = clamp_minutes(minutes);
                }
                if let Some(until) = v["planned_until"].as_u64() {
                    rec.planned_until = until.max(rec.start);
                }
                if let Some(raw) = v["raw"].as_str() {
                    rec.raw = clean_text(raw, MAX_RAW_CHARS);
                }
                if let Ok(source) = serde_json::from_value(v["parse_source"].clone()) {
                    rec.parse_source = source;
                }
            }
            Some("extend") => {
                let Some(id) = v["id"].as_str() else { continue };
                let Some(rec) = list.iter_mut().rev().find(|r| r.id == id && r.active) else {
                    continue;
                };
                let added = v["added_minutes"].as_u64().unwrap_or(EXTEND_MINUTES);
                if let Some(until) = v["planned_until"].as_u64() {
                    rec.planned_until = until.max(rec.planned_until);
                } else {
                    rec.planned_until = rec.planned_until.saturating_add(added.saturating_mul(MINUTE_MS));
                }
                rec.extensions = rec.extensions.saturating_add(1);
            }
            Some("end") => {
                let (Some(id), Some(end)) = (v["id"].as_str(), v["end"].as_u64()) else {
                    continue;
                };
                let Some(rec) = list.iter_mut().rev().find(|r| r.id == id && r.active) else {
                    continue;
                };
                rec.end = Some(end.max(rec.start));
                rec.outcome = serde_json::from_value(v["outcome"].clone())
                    .ok()
                    .or(Some(Outcome::Done));
                rec.active = false;
            }
            _ => {}
        }
    }
    list
}

/// Active screen time (ms) within `[since, now]` with no intention, ignoring away gaps and `FocusDNS` itself.
pub fn unintended_active_ms(spans: &[Span], since: u64, now: u64) -> u64 {
    if now <= since {
        return 0;
    }
    let mut intervals: Vec<(u64, u64)> = spans
        .iter()
        .filter(|s| s.label != "FocusDNS" && s.app != "FocusDNS")
        .filter_map(|s| {
            let a = s.s.max(since);
            let b = s.e.min(now);
            (b > a).then_some((a, b))
        })
        .collect();
    if intervals.is_empty() {
        return 0;
    }
    intervals.sort_unstable();
    let mut total = 0u64;
    let (mut cur_s, mut cur_e) = intervals[0];
    for (s, e) in intervals.into_iter().skip(1) {
        if s <= cur_e {
            cur_e = cur_e.max(e);
        } else {
            total = total.saturating_add(cur_e - cur_s);
            cur_s = s;
            cur_e = e;
        }
    }
    total.saturating_add(cur_e - cur_s)
}

#[derive(Default)]
struct StoreInner {
    active: Option<IntentionRec>,
    last_ended_at: Option<u64>,
    nag_logged_since: Option<u64>,
    seq: u64,
}

pub struct Store {
    path: PathBuf,
    inner: Mutex<StoreInner>,
}

impl Store {
    /// Opens `intentions.jsonl` and restores any unclosed active intention.
    pub fn open(dir: &Path) -> Self {
        let path = dir.join(FILENAME);
        let records = fs::read_to_string(&path)
            .map(|text| read_intentions(&text))
            .unwrap_or_default();
        let active = records.iter().rev().find(|r| r.active).cloned();
        let last_ended_at = records.iter().filter_map(|r| r.end).max();
        Self {
            path,
            inner: Mutex::new(StoreInner {
                active,
                last_ended_at,
                nag_logged_since: None,
                seq: 0,
            }),
        }
    }

    pub fn active(&self) -> Option<IntentionRec> {
        self.inner.lock().unwrap().active.clone()
    }

    fn append_line(&self, entry: Value) -> Result<(), String> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("Could not create intentions folder: {e}"))?;
        }
        let mut file = OpenOptions::new()
            .create(true)
            .read(true)
            .append(true)
            .open(&self.path)
            .map_err(|e| format!("Could not open {FILENAME}: {e}"))?;
        let mut last = [b'\n'];
        if file.metadata().map_err(|e| e.to_string())?.len() > 0 {
            file.seek(SeekFrom::End(-1)).map_err(|e| e.to_string())?;
            file.read_exact(&mut last).map_err(|e| e.to_string())?;
        }
        let sep = if last[0] == b'\n' { "" } else { "\n" };
        file.write_all(format!("{sep}{entry}\n").as_bytes())
            .map_err(|e| format!("Could not write {FILENAME}: {e}"))
    }

    /// Starts a new intention. If another intention is active, closes it first as `replaced`.
    pub fn start(
        &self,
        now: u64,
        label: String,
        planned_minutes: u64,
        done_when: Option<String>,
        raw: String,
        parse_source: ParseSource,
    ) -> Result<(IntentionRec, Option<IntentionRec>), String> {
        let label = clean_text(&label, MAX_LABEL_CHARS);
        if label.is_empty() {
            return Err("Enter what you are working on".into());
        }
        let planned_minutes = clamp_minutes(planned_minutes);
        let done_when = done_when
            .map(|s| clean_text(&s, MAX_DONE_WHEN_CHARS))
            .filter(|s| !s.is_empty());
        let raw = {
            let r = clean_text(&raw, MAX_RAW_CHARS);
            if r.is_empty() { label.clone() } else { r }
        };

        let mut inner = self.inner.lock().unwrap();
        let mut replaced = None;
        if let Some(mut prev) = inner.active.take() {
            let end_ts = now.max(prev.start);
            self.append_line(json!({
                "type": "end",
                "id": prev.id,
                "end": end_ts,
                "outcome": Outcome::Replaced,
            }))?;
            prev.end = Some(end_ts);
            prev.outcome = Some(Outcome::Replaced);
            prev.active = false;
            inner.last_ended_at = Some(inner.last_ended_at.map_or(end_ts, |t| t.max(end_ts)));
            replaced = Some(prev);
        }

        inner.seq = inner.seq.saturating_add(1);
        let id = format!("int-{now}-{}", inner.seq);
        let planned_until = now.saturating_add(planned_minutes.saturating_mul(MINUTE_MS));
        let rec = IntentionRec {
            id: id.clone(),
            start: now,
            end: None,
            planned_until,
            planned_minutes,
            label: label.clone(),
            done_when: done_when.clone(),
            raw: raw.clone(),
            parse_source,
            outcome: None,
            extensions: 0,
            active: true,
        };
        let at = Local
            .timestamp_millis_opt(now as i64)
            .single()
            .unwrap_or_else(Local::now)
            .to_rfc3339();
        self.append_line(json!({
            "type": "start",
            "id": id,
            "start": now,
            "planned_until": planned_until,
            "planned_minutes": planned_minutes,
            "label": label,
            "done_when": done_when,
            "raw": raw,
            "parse_source": parse_source,
            "at": at,
        }))?;
        inner.active = Some(rec.clone());
        inner.nag_logged_since = None;
        Ok((rec, replaced))
    }

    /// Updates the currently active intention in place.
    pub fn edit(
        &self,
        now: u64,
        label: String,
        planned_minutes: u64,
        done_when: Option<String>,
        raw: String,
        parse_source: ParseSource,
    ) -> Result<IntentionRec, String> {
        let label = clean_text(&label, MAX_LABEL_CHARS);
        if label.is_empty() {
            return Err("Enter what you are working on".into());
        }
        let planned_minutes = clamp_minutes(planned_minutes);
        let done_when = done_when
            .map(|s| clean_text(&s, MAX_DONE_WHEN_CHARS))
            .filter(|s| !s.is_empty());
        let raw = {
            let r = clean_text(&raw, MAX_RAW_CHARS);
            if r.is_empty() { label.clone() } else { r }
        };

        let mut inner = self.inner.lock().unwrap();
        let Some(rec) = inner.active.as_mut() else {
            return Err("No active intention to edit".into());
        };
        let planned_until = now.saturating_add(planned_minutes.saturating_mul(MINUTE_MS)).max(rec.start);
        self.append_line(json!({
            "type": "edit",
            "id": rec.id,
            "t": now,
            "label": label,
            "done_when": done_when,
            "planned_minutes": planned_minutes,
            "planned_until": planned_until,
            "raw": raw,
            "parse_source": parse_source,
        }))?;
        rec.label = label;
        rec.done_when = done_when;
        rec.planned_minutes = planned_minutes;
        rec.planned_until = planned_until;
        rec.raw = raw;
        rec.parse_source = parse_source;
        Ok(rec.clone())
    }

    /// Extends the active intention by `added_minutes` (default 10) from max(now, planned_until).
    pub fn extend(&self, now: u64, added_minutes: u64) -> Result<IntentionRec, String> {
        let added = added_minutes.clamp(1, 60);
        let mut inner = self.inner.lock().unwrap();
        let Some(rec) = inner.active.as_mut() else {
            return Err("No active intention to extend".into());
        };
        let next_until = now.max(rec.planned_until).saturating_add(added.saturating_mul(MINUTE_MS));
        self.append_line(json!({
            "type": "extend",
            "id": rec.id,
            "t": now,
            "added_minutes": added,
            "planned_until": next_until,
        }))?;
        rec.planned_until = next_until;
        rec.extensions = rec.extensions.saturating_add(1);
        Ok(rec.clone())
    }

    /// Ends the active intention and records its outcome (`done`, `partly`, `no`, or `cleared`).
    pub fn end(&self, now: u64, outcome: Outcome) -> Result<IntentionRec, String> {
        let mut inner = self.inner.lock().unwrap();
        let Some(mut rec) = inner.active.take() else {
            return Err("No active intention to end".into());
        };
        let end_ts = now.max(rec.start);
        if let Err(error) = self.append_line(json!({
            "type": "end",
            "id": rec.id,
            "end": end_ts,
            "outcome": outcome,
        })) {
            inner.active = Some(rec);
            return Err(error);
        }
        rec.end = Some(end_ts);
        rec.outcome = Some(outcome);
        rec.active = false;
        inner.last_ended_at = Some(inner.last_ended_at.map_or(end_ts, |t| t.max(end_ts)));
        inner.nag_logged_since = None;
        Ok(rec)
    }

    /// Returns all intentions touching `[from, to]`.
    pub fn history(&self, from: u64, to: u64, now: u64) -> Vec<IntentionRec> {
        let _guard = self.inner.lock().unwrap();
        let text = fs::read_to_string(&self.path).unwrap_or_default();
        read_intentions(&text)
            .into_iter()
            .filter(|r| r.end.unwrap_or(now) > from && r.start < to)
            .collect()
    }

    fn read_notes(&self) -> Result<Vec<IntentionNote>, String> {
        match fs::read(self.path.with_file_name(NOTES_FILENAME)) {
            Ok(bytes) => serde_json::from_slice(&bytes).map_err(|e| e.to_string()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Vec::new()),
            Err(e) => Err(e.to_string()),
        }
    }

    pub fn notes(&self, from: u64, to: u64, intentions: &[IntentionRec]) -> Vec<IntentionNote> {
        self.read_notes()
            .unwrap_or_default()
            .into_iter()
            .filter(|note| match &note.intention_id {
                Some(id) => intentions.iter().any(|r| &r.id == id),
                None => note.start < to && note.end > from,
            })
            .collect()
    }

    /// Replaces a block's note. Empty text removes it from the local history file.
    pub fn save_note(
        &self,
        mut note: IntentionNote,
        now: u64,
    ) -> Result<Option<IntentionNote>, String> {
        note.text = note.text.trim().to_owned();
        if note.text.encode_utf16().count() > MAX_NOTE_CHARS {
            return Err("Use 500 characters or fewer".into());
        }
        let _guard = self.inner.lock().unwrap();
        if let Some(id) = &note.intention_id {
            let text = fs::read_to_string(&self.path).map_err(|e| e.to_string())?;
            let rec = read_intentions(&text)
                .into_iter()
                .find(|r| &r.id == id)
                .ok_or("Intention no longer exists")?;
            note.start = rec.start;
            note.end = rec.end.unwrap_or(now);
        }
        if note.end < note.start
            || note.end > now
            || (note.intention_id.is_none() && note.start == note.end)
        {
            return Err("Invalid block time".into());
        }
        let mut notes = self.read_notes()?;
        notes.retain(|saved| !saved.same_block(&note));
        let saved = (!note.text.is_empty()).then_some(note);
        notes.extend(saved.iter().cloned());
        notes.sort_by_key(|note| note.start);
        let path = self.path.with_file_name(NOTES_FILENAME);
        let bytes = serde_json::to_vec_pretty(&notes).map_err(|e| e.to_string())?;
        focus_browser_host::durable_atomic_write(&path, &bytes).map_err(|e| e.to_string())?;
        Ok(saved)
    }

    /// Returns the timestamp from which active screen time without an intention should be counted.
    pub fn no_intention_since(&self, day_start_ms: u64) -> Option<u64> {
        let inner = self.inner.lock().unwrap();
        if inner.active.is_some() {
            return None;
        }
        Some(inner.last_ended_at.unwrap_or(0).max(day_start_ms))
    }

    /// Builds the live dock view and reports whether the 15-minute nag newly transitioned on.
    pub fn view(&self, spans: &[Span], day_start_ms: u64, now: u64) -> (IntentionView, bool) {
        let mut inner = self.inner.lock().unwrap();
        if let Some(active) = inner.active.clone() {
            let time_up = now >= active.planned_until;
            return (
                IntentionView {
                    active: Some(active),
                    time_up,
                    no_intention_active_ms: 0,
                    nag_active: false,
                    now,
                },
                false,
            );
        }
        let since = inner.last_ended_at.unwrap_or(0).max(day_start_ms);
        let active_ms = unintended_active_ms(spans, since, now);
        let nag_active = active_ms >= NAG_THRESHOLD_MS;
        let nag_just_triggered = nag_active && inner.nag_logged_since != Some(since);
        if nag_just_triggered {
            inner.nag_logged_since = Some(since);
        }
        (
            IntentionView {
                active: None,
                time_up: false,
                no_intention_active_ms: active_ms,
                nag_active,
                now,
            },
            nag_just_triggered,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch_dir(tag: &str) -> PathBuf {
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let dir = std::env::temp_dir().join(format!(
            "focus-intentions-{tag}-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn span(s: u64, e: u64, app: &str) -> Span {
        Span {
            s,
            e,
            app: app.into(),
            label: app.into(),
            source: "window_title".into(),
            browser: None,
        }
    }

    #[test]
    fn notes_persist_by_intention_id_or_unplanned_start_and_can_be_removed() {
        let dir = scratch_dir("notes");
        let store = Store::open(&dir);
        let (planned, _) = store
            .start(100, "Write the guide".into(), 20, None, String::new(), ParseSource::Local)
            .unwrap();
        let note = IntentionNote {
            intention_id: Some(planned.id),
            start: 100,
            end: 130,
            text: "  Draft is ready for review.  ".into(),
        };
        let saved = store.save_note(note, 140).unwrap().unwrap();
        assert_eq!(saved.text, "Draft is ready for review.");
        store.end(150, Outcome::Done).unwrap();

        let mut unplanned = IntentionNote {
            intention_id: None,
            start: 200,
            end: 260,
            text: "Recorded a demo.".into(),
        };
        store.save_note(unplanned.clone(), 260).unwrap();
        unplanned.end = 290;
        unplanned.text = "Recorded a demo and credited the original post.".into();
        store.save_note(unplanned.clone(), 290).unwrap();

        let reopened = Store::open(&dir);
        let intentions = reopened.history(145, 300, 300);
        assert_eq!(reopened.notes(145, 300, &intentions), [saved.clone(), unplanned.clone()]);
        assert_eq!(reopened.notes(250, 300, &[]), [unplanned.clone()]);
        assert!(reopened.notes(290, 300, &[]).is_empty());
        unplanned.text.clear();
        reopened.save_note(unplanned, 300).unwrap();

        let disk: Vec<IntentionNote> =
            serde_json::from_slice(&fs::read(dir.join(NOTES_FILENAME)).unwrap()).unwrap();
        assert_eq!(disk, [saved]);
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn local_parser_extracts_what_duration_and_done_when() {
        let now = 1_800_000_000_000u64;
        let parsed = parse_local_at(
            "cleaning the house, 45 min, done when the trash is out",
            now,
            11 * 60,
        );
        assert_eq!(parsed.label, "Cleaning the house");
        assert_eq!(parsed.planned_minutes, 45);
        assert_eq!(parsed.until, now + 45 * MINUTE_MS);
        assert_eq!(parsed.done_when.as_deref(), Some("the trash is out"));
        assert_eq!(parsed.parse_source, ParseSource::Local);
    }

    #[test]
    fn local_parser_handles_hours_compound_until_clock_default_and_cap() {
        let now = 1_800_000_000_000u64;

        let one_h = parse_local_at("FairGrade rubric PR 1h", now, 9 * 60);
        assert_eq!(one_h.label, "FairGrade rubric PR");
        assert_eq!(one_h.planned_minutes, 60);
        assert_eq!(one_h.done_when, None);

        let two_h = parse_local_at("write docs for 2 hours, done when draft is sent", now, 9 * 60);
        assert_eq!(two_h.label, "Write docs");
        assert_eq!(two_h.planned_minutes, 120);
        assert_eq!(two_h.done_when.as_deref(), Some("draft is sent"));

        let compound = parse_local_at("refactor parser for 1h 30m", now, 9 * 60);
        assert_eq!(compound.label, "Refactor parser");
        assert_eq!(compound.planned_minutes, 90);

        let until_pm = parse_local_at("Gepeto launch post until 3pm", now, 13 * 60 + 30);
        assert_eq!(until_pm.label, "Gepeto launch post");
        assert_eq!(until_pm.planned_minutes, 90);

        let until_24h = parse_local_at("budget review until 14:15", now, 13 * 60 + 30);
        assert_eq!(until_24h.label, "Budget review");
        assert_eq!(until_24h.planned_minutes, 45);

        let default_dur = parse_local_at("triage inbox", now, 10 * 60);
        assert_eq!(default_dur.label, "Triage inbox");
        assert_eq!(default_dur.planned_minutes, DEFAULT_MINUTES);

        let capped = parse_local_at("deep research for 8 hours", now, 10 * 60);
        assert_eq!(capped.label, "Deep research");
        assert_eq!(capped.planned_minutes, MAX_MINUTES);
    }

    #[test]
    fn nag_counts_only_active_screen_time_and_triggers_at_15_minutes() {
        let dir = scratch_dir("nag");
        let store = Store::open(&dir);
        let day_start = 1_800_000_000_000u64;

        // 10m active, 25m away, 4m active -> 14m active (below 15m threshold).
        let spans_14m = vec![
            span(day_start + 5 * MINUTE_MS, day_start + 15 * MINUTE_MS, "VS Code"),
            // FocusDNS spans never count toward active screen time.
            span(day_start + 15 * MINUTE_MS, day_start + 25 * MINUTE_MS, "FocusDNS"),
            // 25 minutes away (no spans), then 4m active.
            span(day_start + 40 * MINUTE_MS, day_start + 44 * MINUTE_MS, "Chrome"),
        ];
        let (view, triggered) = store.view(&spans_14m, day_start, day_start + 44 * MINUTE_MS);
        assert_eq!(view.no_intention_active_ms, 14 * MINUTE_MS);
        assert!(!view.nag_active);
        assert!(!triggered);

        // Add 2 more active minutes -> 16m active (>= 15m threshold).
        let mut spans_16m = spans_14m;
        spans_16m.push(span(day_start + 44 * MINUTE_MS, day_start + 46 * MINUTE_MS, "Chrome"));
        let (view, triggered) = store.view(&spans_16m, day_start, day_start + 46 * MINUTE_MS);
        assert_eq!(view.no_intention_active_ms, 16 * MINUTE_MS);
        assert!(view.nag_active);
        assert!(triggered);

        // Second check in same stretch does not re-trigger the log transition.
        let (view2, triggered2) = store.view(&spans_16m, day_start, day_start + 47 * MINUTE_MS);
        assert!(view2.nag_active);
        assert!(!triggered2);

        // Starting an intention clears the nag; ending it resets the counter to the end time.
        store
            .start(
                day_start + 47 * MINUTE_MS,
                "Write tests".into(),
                30,
                None,
                "Write tests 30m".into(),
                ParseSource::Local,
            )
            .unwrap();
        let (active_view, _) = store.view(&spans_16m, day_start, day_start + 50 * MINUTE_MS);
        assert!(!active_view.nag_active);
        assert_eq!(active_view.no_intention_active_ms, 0);

        store.end(day_start + 77 * MINUTE_MS, Outcome::Done).unwrap();
        let mut after_end = spans_16m;
        after_end.push(span(day_start + 77 * MINUTE_MS, day_start + 87 * MINUTE_MS, "Chrome"));
        let (post_view, post_triggered) =
            store.view(&after_end, day_start, day_start + 87 * MINUTE_MS);
        assert_eq!(post_view.no_intention_active_ms, 10 * MINUTE_MS);
        assert!(!post_view.nag_active);
        assert!(!post_triggered);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn outcome_extension_edit_and_restore_on_launch_round_trip() {
        let dir = scratch_dir("restore");
        let t0 = 1_800_000_000_000u64;
        {
            let store = Store::open(&dir);
            let (first, replaced) = store
                .start(
                    t0,
                    "Cleaning house".into(),
                    45,
                    Some("trash is out".into()),
                    "cleaning the house, 45 min, done when the trash is out".into(),
                    ParseSource::Gateway,
                )
                .unwrap();
            assert!(replaced.is_none());
            assert_eq!(first.planned_until, t0 + 45 * MINUTE_MS);

            // Simulate app restart while active.
            let restored = Store::open(&dir);
            let active = restored.active().expect("active intention should restore on launch");
            assert_eq!(active.id, first.id);
            assert_eq!(active.label, "Cleaning house");
            assert_eq!(active.done_when.as_deref(), Some("trash is out"));
            assert_eq!(active.parse_source, ParseSource::Gateway);

            // Time runs out, user presses "10 more min".
            let (view_up, _) = restored.view(&[], t0, t0 + 46 * MINUTE_MS);
            assert!(view_up.time_up);
            let extended = restored.extend(t0 + 46 * MINUTE_MS, EXTEND_MINUTES).unwrap();
            assert_eq!(extended.extensions, 1);
            assert_eq!(extended.planned_until, t0 + 56 * MINUTE_MS);

            // User finishes and records "partly".
            let ended = restored.end(t0 + 55 * MINUTE_MS, Outcome::Partly).unwrap();
            assert_eq!(ended.outcome, Some(Outcome::Partly));
            assert_eq!(ended.end, Some(t0 + 55 * MINUTE_MS));
            assert!(!ended.active);
            assert!(restored.active().is_none());

            // Start a second intention, then start a third — second is auto-closed as replaced.
            let (second, _) = restored
                .start(
                    t0 + 60 * MINUTE_MS,
                    "Draft post".into(),
                    30,
                    None,
                    "Draft post 30m".into(),
                    ParseSource::Local,
                )
                .unwrap();
            let edited = restored
                .edit(
                    t0 + 62 * MINUTE_MS,
                    "Gepeto launch post".into(),
                    60,
                    Some("draft sent to Ana".into()),
                    "Gepeto launch post 1h, done when draft sent to Ana".into(),
                    ParseSource::Local,
                )
                .unwrap();
            assert_eq!(edited.id, second.id);
            assert_eq!(edited.label, "Gepeto launch post");

            let (third, prev) = restored
                .start(
                    t0 + 90 * MINUTE_MS,
                    "Review PR".into(),
                    45,
                    None,
                    "Review PR 45m".into(),
                    ParseSource::Local,
                )
                .unwrap();
            assert_eq!(prev.as_ref().map(|p| p.outcome), Some(Some(Outcome::Replaced)));
            assert_eq!(third.label, "Review PR");
        }

        // Re-open after restart: only the third intention is active; all three appear in history.
        let reloaded = Store::open(&dir);
        assert_eq!(reloaded.active().unwrap().label, "Review PR");
        let hist = reloaded.history(t0, t0 + 200 * MINUTE_MS, t0 + 100 * MINUTE_MS);
        assert_eq!(hist.len(), 3);
        assert_eq!(hist[0].outcome, Some(Outcome::Partly));
        assert_eq!(hist[0].extensions, 1);
        assert_eq!(hist[1].label, "Gepeto launch post");
        assert_eq!(hist[1].outcome, Some(Outcome::Replaced));
        assert!(hist[2].active);

        let _ = fs::remove_dir_all(&dir);
    }
}
