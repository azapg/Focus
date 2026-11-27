use std::{sync::Arc, thread, time::Duration};

use chrono::{Days, Local};
use focus_devices::DeviceView;
use focus_dns::{
    activity::Tracker,
    focus_extract::FocusExtractor,
    habits::{
        focus_rewards::{
            award_finalized_day, union_verified_intervals_ms, FinalizedFocusDay, FocusRewardRate,
        },
        local_clock,
    },
    pause_history::read_pauses,
    streak_journal::local_day_start,
};
use tauri::{AppHandle, Emitter};

use crate::{device_commands::Devices, Core};

pub fn run(app: AppHandle, core: Arc<Core>, tracker: Arc<Tracker>, devices: Arc<Devices>) {
    let mut last_scan_day = None;
    let mut last_error = None;
    loop {
        let now = Local::now();
        let date = now.date_naive();
        // Scan the available week after startup or a date change. Recheck
        // recent days for phone history that arrives after the first scan.
        let days = if last_scan_day == Some(date) { 2 } else { 7 };
        let peers = devices
            .0
            .as_ref()
            .map(|manager| manager.list())
            .unwrap_or_default();
        match award_completed_days(&core, &tracker, &peers, now, days) {
            Ok(coins) => {
                last_error = None;
                last_scan_day = Some(date);
                if coins > 0 {
                    core.log(&format!("Store wallet awarded {coins} focus coins"));
                    let _ = app.emit("wallet-updated", ());
                }
            }
            Err(error) => {
                if last_error.as_ref() != Some(&error) {
                    core.log(&format!("Store wallet focus rewards: {error}"));
                    last_error = Some(error);
                }
            }
        }
        thread::sleep(Duration::from_secs(10 * 60));
    }
}

fn award_completed_days(
    core: &Core,
    tracker: &Tracker,
    peers: &[DeviceView],
    now: chrono::DateTime<Local>,
    days: i32,
) -> Result<i32, String> {
    let now_ms = now.timestamp_millis() as u64;
    let today = local_clock(now_ms).0;
    let Some(start_day) = core.habits.read().wallet_start.filter(|&day| day < today) else {
        return Ok(0);
    };
    let date = now.date_naive();
    let snapshot = core.laptop_pause_snapshot()?;
    let pauses = read_pauses(&snapshot.history, snapshot.active_pause, now_ms);
    let mut extractor = FocusExtractor::from_data_dir(&core.dir)?;
    let mut awards = Vec::new();
    for day in start_day.max(today - days)..today {
        let date = date - Days::new((today - day) as u64);
        let from = local_day_start(&Local, date)?;
        let to = local_day_start(&Local, date + Days::new(1))?;
        let desktop = extractor.extract_desktop_spans(
            "laptop",
            &tracker.spans(from, to),
            &pauses,
            from,
            to,
        )?;
        let mut focus = desktop.verified_focus_intervals;
        for peer in peers.iter().filter(|peer| peer.kind == "phone") {
            let Some(snapshot) = peer.snapshot.as_ref().filter(|snapshot| {
                snapshot["kind"] == "phone"
                    && snapshot["device_id"].as_str() == Some(peer.id.as_str())
            }) else {
                continue;
            };
            let phone = extractor.extract_peer_snapshot(&peer.id, "phone", snapshot, from, to)?;
            focus.extend(phone.verified_focus_intervals);
        }
        awards.push(FinalizedFocusDay {
            date: date.to_string(),
            day,
            day_start_ms: from,
            day_end_ms: to,
            complete: true,
            union_focus_ms: union_verified_intervals_ms(&focus, &[], from, to),
            at_ms: now_ms,
        });
    }
    core.habits.update(|ledger| {
        let rate = FocusRewardRate::default();
        awards.iter().try_fold(0, |coins, day| {
            Ok(coins + award_finalized_day(ledger, day, &rate)?.coins_awarded)
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;
    use serde_json::json;
    use std::fs;

    #[test]
    fn verified_focus_pays_without_clean_streak_and_cannot_pay_twice() {
        let fixture = crate::dns_tests::Fixture::new(|_| panic!("unexpected DNS operation"));
        let core = &fixture.core;
        let tracker = Tracker::new(&core.dir);
        let now = Local.with_ymd_and_hms(2026, 2, 12, 9, 0, 0).unwrap();
        let date = now.date_naive() - Days::new(1);
        let day = local_clock(now.timestamp_millis() as u64).0 - 1;
        let start = local_day_start(&Local, date).unwrap();
        let minute = 60_000;
        let spans = [
            json!({ "s": start, "e": start + 35 * minute, "app": "T3 Code", "label": "T3 Code" }),
            json!({ "s": start + 40 * minute, "e": start + 90 * minute, "app": "T3 Code", "label": "T3 Code" }),
        ];
        fs::write(
            core.dir.join("activity").join(format!("{date}.jsonl")),
            format!("{}\n{}\n", spans[0], spans[1]),
        )
        .unwrap();
        let pause =
            json!({ "type": "pause", "t": start + 60 * minute, "until": start + 90 * minute });
        let resume =
            json!({ "type": "resume", "t": start + 90 * minute, "paused_at": start + 60 * minute });
        fs::write(
            core.dir.join("pauses.jsonl"),
            format!("{pause}\n{resume}\n"),
        )
        .unwrap();

        assert_eq!(crate::wallet::activate_at_startup(core, day), Ok(true));
        assert_eq!(core.habits.read().balance(), 0);
        assert!(core.habits.read().wallet_active(day));
        assert!(focus_dns::streak_journal::authority_cursor(&core.habits).is_none());
        assert_eq!(award_completed_days(core, &tracker, &[], now, 2), Ok(5));
        assert_eq!(award_completed_days(core, &tracker, &[], now, 2), Ok(0));
        assert_eq!(core.habits.read().balance(), 5);
        assert_eq!(crate::wallet::activate_at_startup(core, day), Ok(false));
        assert_eq!(core.habits.read().balance(), 5);
    }
}
