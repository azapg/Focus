use serde::{Deserialize, Serialize};
use std::collections::HashMap;

pub const RATING_WINDOW_MS: u64 = 10 * 60_000;
pub const RATING_NOTE_LIMIT: usize = 200;

#[derive(Clone, Copy, Debug, PartialEq, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum CheckIn {
    Tired,
    Bored,
    Stuck,
    Anxious,
    Specific,
}

#[derive(Serialize, Debug)]
pub struct PauseRec {
    pub t: u64,
    pub pause_id: Option<u64>,
    pub end: u64,
    pub minutes: u64,
    pub reason: String,
    pub check_in: Option<CheckIn>,
    pub rating: Option<u8>,
    pub rating_note: Option<String>,
    // Only an explicitly linked completion can recover a saved live pause.
    #[serde(skip)]
    pub completed_at: Option<u64>,
    #[serde(skip)]
    pub active: bool,
}

/// New resumes identify their pause. Old resumes are matched only until the next
/// pause, so an incomplete historical entry cannot steal a later pause's end.
pub fn read_pauses(text: &str, active_pause: Option<(u64, Option<u64>)>, now: u64) -> Vec<PauseRec> {
    read(text, active_pause, None, now, false)
}

pub fn read_access_pauses(
    text: &str,
    active_pause: Option<(u64, Option<u64>)>,
    active_scope: Option<u64>,
    now: u64,
) -> Vec<PauseRec> {
    read(text, active_pause, active_scope, now, true)
}

fn read(
    text: &str,
    active_pause: Option<(u64, Option<u64>)>,
    active_scope: Option<u64>,
    now: u64,
    include_scopes: bool,
) -> Vec<PauseRec> {
    let mut pauses = Vec::new();
    let mut resumes = Vec::new();
    let mut ratings = Vec::new();
    for (line, v) in text.lines().enumerate().filter_map(|(line, raw)| {
        serde_json::from_str::<serde_json::Value>(raw).ok().map(|v| (line, v))
    }) {
        if v["type"].as_str() == Some("rating") {
            if let (Some(id), Some(score @ 1..=5), Some(rated_at)) = (
                v["paused_at"].as_u64(), v["rating"].as_u64(),
                v["rated_at"].as_u64().or_else(|| v["t"].as_u64()),
            ) {
                let note = v["rating_note"].as_str().map(str::trim)
                    .filter(|s| !s.is_empty()).map(|s| s.chars().take(RATING_NOTE_LIMIT).collect());
                ratings.push(((id, v["pause_id"].as_u64()), line, rated_at, score as u8, note));
            }
            continue;
        }
        let t = v["t"].as_u64().or_else(|| {
            chrono::DateTime::parse_from_rfc3339(v["at"].as_str()?).ok().map(|d| d.timestamp_millis() as u64)
        });
        let Some(t) = t else { continue };
        let kind = v["type"].as_str();
        let scoped = matches!(kind, Some("scoped_pause" | "scoped_resume"));
        if scoped && !include_scopes {
            continue;
        }
        match kind {
            Some("resume" | "scoped_resume") => {
                resumes.push((line, t, v["paused_at"].as_u64(), v["pause_id"].as_u64(), scoped));
            }
            Some("pause" | "scoped_pause") | None => {
                let minutes = v["minutes"].as_u64().unwrap_or(0);
                let Some(end) = v["until"].as_u64().or_else(|| t.checked_add(minutes.checked_mul(60_000)?)) else {
                    continue;
                };
                if end < t {
                    continue;
                }
                pauses.push((line, PauseRec {
                    t, end, minutes, reason: v["reason"].as_str().unwrap_or("").into(),
                    pause_id: v["pause_id"].as_u64(),
                    check_in: serde_json::from_value(v["check_in"].clone()).ok(),
                    rating: None,
                    rating_note: None,
                    completed_at: None,
                    active: false,
                }, scoped));
            }
            _ => {}
        }
    }
    let active_index = pauses.iter().rposition(|(_, p, scoped)| !scoped && active_pause == Some((p.t, p.pause_id)));
    let active_scope_index = pauses.iter().rposition(|(_, p, scoped)| *scoped && active_scope == Some(p.t));
    let mut completions = HashMap::new();
    for i in 0..pauses.len() {
        let scoped = pauses[i].2;
        let next_line = pauses[i + 1..].iter().find(|(_, _, scope)| *scope == scoped)
            .map(|(line, _, _)| *line).unwrap_or(usize::MAX);
        let (pause_line, p, _) = &mut pauses[i];
        let linked = resumes.iter().find(|&&(line, _, start, id, scope)| {
            scope == scoped && line > *pause_line && start == Some(p.t) && id == p.pause_id
                && (id.is_some() || line < next_line)
        });
        let completion = linked.or_else(|| {
            resumes.iter().filter(|&&(line, t, start, id, scope)| {
                !scoped && !scope && id.is_none() && line > *pause_line && line < next_line
                    && (start == Some(p.t) || (start.is_none() && t >= p.t))
            }).min_by_key(|&&(_, t, _, _, _)| t)
        });
        if let Some(&(line, ended, _, _, _)) = completion {
            // The actual completion time can fall either side of the recorded
            // start after a wall-clock correction. Keep usage intervals nonnegative.
            p.end = ended.max(p.t);
            p.completed_at = linked.map(|_| ended);
            if !scoped {
                completions.entry((p.t, p.pause_id)).or_insert_with(Vec::new).push((line, ended, i));
            }
        } else if active_index == Some(i) || active_scope_index == Some(i) {
            // A failed/slow restore still counts as off after the deadline.
            p.end = now.max(p.t);
            p.active = true;
        }
    }
    for (key, line, rated_at, rating, note) in ratings {
        // Legacy pauses can share a wall-clock start. Match the most recent
        // completion already in the journal before checking its rating window.
        let Some(&(_, ended, i)) = completions.get(&key)
            .and_then(|entries| entries.iter().rev().find(|&&(completed_line, _, _)| completed_line < line))
        else { continue };
        let pause = &mut pauses[i].1;
        if pause.rating.is_none() && rated_at >= ended && rated_at < ended.saturating_add(RATING_WINDOW_MS) {
            pause.rating = Some(rating);
            pause.rating_note = note;
        }
    }
    pauses.into_iter().map(|(_, pause, _)| pause).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scoped_history_keeps_the_full_pause_rating_and_usage_separate() {
        let journal = concat!(
            "{\"type\":\"pause\",\"t\":1000,\"pause_id\":1,\"check_in\":\"stuck\",\"minutes\":5}\n",
            "{\"type\":\"scoped_pause\",\"t\":1000,\"minutes\":5}\n",
            "{\"type\":\"resume\",\"t\":3000,\"paused_at\":1000,\"pause_id\":1}\n",
            "{\"type\":\"rating\",\"rated_at\":3100,\"paused_at\":1000,\"pause_id\":1,\"rating\":4}\n",
            "{\"type\":\"scoped_resume\",\"t\":4000,\"paused_at\":1000}\n",
        );
        let full = read_pauses(journal, None, 5000);
        assert_eq!(full.len(), 1);
        assert_eq!(full[0].end, 3000);
        assert_eq!(full[0].rating, Some(4));
        let access = read_access_pauses(journal, None, None, 5000);
        assert_eq!(access.len(), 2);
        assert_eq!(access[0].rating, Some(4));
        assert_eq!(access[1].end, 4000);
        assert_eq!(access[1].rating, None);
    }

    #[test]
    fn malformed_deadlines_do_not_abort_history() {
        let text = concat!(
            "{\"t\":18446744073709551615,\"until\":1000,\"minutes\":1}\n",
            "{\"t\":1,\"minutes\":18446744073709551615}\n",
            "{\"t\":1000,\"until\":301000,\"minutes\":5}\n",
        );
        let pauses = read_pauses(text, None, 400000);
        assert_eq!(pauses.len(), 1);
        assert_eq!(pauses[0].end, 301000);
    }

    #[test]
    fn preserves_actual_late_completion_and_links_it_to_its_pause() {
        let text = concat!(
            "{\"type\":\"pause\",\"t\":1000,\"until\":301000,\"minutes\":5}\n",
            "{\"type\":\"resume\",\"paused_at\":1000,\"t\":327000}\n",
        );
        let p = read_pauses(text, None, 400000);
        assert_eq!(p[0].end, 327000);
    }

    #[test]
    fn active_pause_counts_a_restore_that_is_still_pending() {
        let text = "{\"type\":\"pause\",\"t\":1000,\"until\":301000,\"minutes\":5}";
        assert_eq!(read_pauses(text, Some((1000, None)), 327000)[0].end, 327000);
    }

    #[test]
    fn only_the_saved_pause_id_is_active_when_start_times_match() {
        let text = concat!(
            "{\"type\":\"pause\",\"t\":1000,\"minutes\":5,\"pause_id\":1}\n",
            "{\"type\":\"resume\",\"t\":2000,\"paused_at\":1000,\"pause_id\":1}\n",
            "{\"type\":\"rating\",\"rated_at\":2100,\"paused_at\":1000,\"pause_id\":1,\"rating\":2}\n",
            "{\"type\":\"pause\",\"t\":1000,\"minutes\":5,\"pause_id\":2}\n",
        );
        let pauses = read_pauses(text, Some((1000, Some(2))), 2200);
        assert!(!pauses[0].active);
        assert_eq!(pauses[0].rating, Some(2));
        assert_eq!(pauses[0].end, 2000);
        assert!(pauses[1].active);
        assert_eq!(pauses[1].end, 2200);
    }

    #[test]
    fn legacy_rating_belongs_to_the_latest_matching_completion_in_the_journal() {
        let text = concat!(
            "{\"type\":\"pause\",\"t\":1000,\"minutes\":5}\n",
            "{\"type\":\"resume\",\"t\":2000,\"paused_at\":1000}\n",
            "{\"type\":\"pause\",\"t\":1000,\"minutes\":5}\n",
            "{\"type\":\"resume\",\"t\":3000,\"paused_at\":1000}\n",
            "{\"type\":\"rating\",\"rated_at\":3100,\"paused_at\":1000,\"rating\":4}\n",
        );
        let pauses = read_pauses(text, None, 3200);
        assert_eq!(pauses[0].rating, None);
        assert_eq!(pauses[1].rating, Some(4));
    }

    #[test]
    fn old_resumes_preserve_early_and_on_time_endings() {
        let text = concat!(
            "{\"type\":\"pause\",\"t\":1000,\"until\":301000,\"minutes\":5}\n",
            "{\"type\":\"resume\",\"t\":16458,\"why\":\"watchdog\"}\n",
            "{\"type\":\"pause\",\"t\":400000,\"until\":700000,\"minutes\":5}\n",
            "{\"type\":\"resume\",\"t\":700000,\"why\":\"timer ended\"}\n",
        );
        let p = read_pauses(text, None, 800000);
        assert_eq!(p[0].end, 16458);
        assert_eq!(p[1].end, 700000);
    }

    #[test]
    fn missing_old_resume_does_not_borrow_a_later_pause_end() {
        let text = concat!(
            "{\"at\":\"2026-11-14T23:54:00-05:00\",\"minutes\":1}\n",
            "{\"type\":\"pause\",\"t\":1794804680932,\"until\":1794805280932,\"minutes\":10}\n",
            "{\"type\":\"resume\",\"t\":1794805280932}\n",
            "invalid trailing line",
        );
        let p = read_pauses(text, None, 1794805280932);
        assert_eq!(p[0].end - p[0].t, 60000);
        assert_eq!(p[1].end, 1794805280932);
    }

    #[test]
    fn feedback_round_trips_alongside_legacy_and_unanswered_pauses() {
        let text = concat!(
            "{\"minutes\":1,\"at\":\"2020-01-01T00:00:00Z\"}\n",
            "{\"type\":\"pause\",\"t\":1577836920000,\"minutes\":1,\"check_in\":\"tired\"}\n",
            "{\"type\":\"resume\",\"t\":1577836930000,\"paused_at\":1577836920000}\n",
            "{\"type\":\"pause\",\"t\":1577837040000,\"minutes\":1,\"check_in\":\"specific\"}\n",
            "{\"type\":\"rating\",\"rated_at\":1577837060000,\"paused_at\":1577836920000,\"rating\":1,\"rating_note\":\"Nothing relevant today\"}\n",
        );
        let pauses = read_pauses(text, Some((1577837040000, None)), 1577837060000);
        assert_eq!(pauses.len(), 3);
        let old = serde_json::to_value(&pauses[0]).unwrap();
        assert!(old["check_in"].is_null());
        assert!(old["rating"].is_null());
        let rated = serde_json::to_value(&pauses[1]).unwrap();
        assert_eq!(rated["check_in"], "tired");
        assert_eq!(rated["rating"], 1);
        assert_eq!(rated["rating_note"], "Nothing relevant today");
        assert_eq!(pauses[2].check_in, Some(CheckIn::Specific));
        assert_eq!(pauses[2].rating, None);
    }

    #[test]
    fn invalid_and_expired_feedback_does_not_hide_pause_records() {
        let text = concat!(
            "{\"type\":\"pause\",\"t\":1000,\"minutes\":1,\"check_in\":\"other\"}\n",
            "{\"type\":\"resume\",\"t\":2000,\"paused_at\":1000}\n",
            "{\"type\":\"rating\",\"t\":3000,\"paused_at\":1000,\"rating\":0}\n",
            "{\"type\":\"rating\",\"t\":602000,\"paused_at\":1000,\"rating\":5}\n",
            "{\"type\":\"rating\",\"t\":3000,\"paused_at\":999,\"rating\":4}\n",
        );
        let pauses = read_pauses(text, None, 700000);
        assert_eq!(pauses.len(), 1);
        assert_eq!(pauses[0].end, 2000);
        assert_eq!(pauses[0].check_in, None);
        assert_eq!(pauses[0].rating, None);
    }

    #[test]
    fn keeps_the_first_rating_in_the_completed_pause_window() {
        let text = concat!(
            "{\"type\":\"pause\",\"t\":1000,\"minutes\":1}\n",
            "{\"type\":\"resume\",\"t\":2000,\"paused_at\":1000}\n",
            "{\"type\":\"rating\",\"t\":1500,\"paused_at\":1000,\"rating\":5}\n",
            "{\"type\":\"rating\",\"rated_at\":3000,\"paused_at\":1000,\"rating\":2}\n",
            "{\"type\":\"rating\",\"rated_at\":3500,\"paused_at\":1000,\"rating\":3}\n",
        );
        assert_eq!(read_pauses(text, None, 4000)[0].rating, Some(2));
    }
}
