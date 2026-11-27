//! Production state transitions with a fake DNS boundary. No Tauri window,
//! PowerShell process, personal data or machine DNS settings are touched.
use super::*;
use std::sync::mpsc;

pub(super) struct Fixture {
    pub(super) core: Arc<Core>,
    calls: Arc<Mutex<Vec<String>>>,
}

impl Fixture {
    pub(super) fn new(fake: impl Fn(&str) -> Result<String, String> + Send + Sync + 'static) -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let dir = std::env::temp_dir().join(format!("focus-dns-test-{}-{}-{}",
            std::process::id(), now_ms(), NEXT.fetch_add(1, Ordering::Relaxed)));
        fs::create_dir_all(&dir).unwrap();
        let calls = Arc::new(Mutex::new(Vec::new()));
        let recorded = calls.clone();
        let core = Arc::new(Core {
            habits: focus_dns::habits::Store::new(&dir), intentions: focus_dns::intentions::Store::open(&dir), dir, dry: true, dns_config: OnceLock::new(), saved: Mutex::new(Saved::default()), state_write_pending: AtomicBool::new(false),
            judge_tickets: pause_judge::Tickets::default(),
            history_write: Mutex::new(()),
            event_sequence: Mutex::new(None),
            hit: Mutex::new([0.0; 4]), dns_lock: Mutex::new(()),
            dns_sequence: AtomicU64::new(1), prev_fg: Mutex::new(0),
            dns_override: Box::new(move |mode| {
                recorded.lock().unwrap().push(mode.to_string());
                fake(mode)
            }),
        });
        Self { core, calls }
    }

    fn rate(&self, started: u64, rating: Option<u8>, note: &str) -> Result<(), String> {
        let id = self.core.view().rating_prompt.and_then(|prompt| prompt.pause_id);
        self.core.rate_pause(started, id, rating, note)
    }

    fn events(&self) -> Vec<serde_json::Value> {
        fs::read_to_string(self.core.dir.join("pauses.jsonl")).unwrap_or_default()
            .lines().map(|l| serde_json::from_str(l).unwrap()).collect()
    }
}

#[test]
fn watchdog_retries_failed_browser_state_writes_without_switching_dns_again() {
    let f = Fixture::new(ok);
    let state = f.core.dir.join("state.json");
    let temporary = state.with_extension(format!("{}.tmp", std::process::id()));
    fs::create_dir(&temporary).unwrap();
    f.core.start_pause(5, "Synthetic break".into(), None).unwrap();
    assert!(!state.exists());
    fs::remove_dir(&temporary).unwrap();
    assert!(!f.core.reconcile(false).unwrap());
    let paused: Saved = serde_json::from_slice(&fs::read(&state).unwrap()).unwrap();
    assert!(paused.paused_until > now_ms());

    fs::create_dir(&temporary).unwrap();
    f.core.enable_locked(&mut f.core.saved.lock().unwrap(), "turned on early").unwrap();
    fs::remove_dir(&temporary).unwrap();
    assert!(!f.core.reconcile(false).unwrap());
    let resumed: Saved = serde_json::from_slice(&fs::read(state).unwrap()).unwrap();
    assert_eq!(resumed.paused_until, 0);
    assert_eq!(resumed.pause_ended_at, f.events()[1]["t"].as_u64());
    assert_eq!(*f.calls.lock().unwrap(), ["off", "on"]);
}

impl Drop for Fixture {
    fn drop(&mut self) { let _ = fs::remove_dir_all(&self.core.dir); }
}

fn ok(_: &str) -> Result<String, String> { Ok("STATUS off".into()) }

#[test]
fn long_pause_reason_is_preserved_in_history() {
    let f = Fixture::new(ok);
    let reason = "I want to look up an example, then return to the draft. ".repeat(100);
    f.core.start_pause(5, reason.clone(), None).unwrap();
    let history = fs::read_to_string(f.core.dir.join("pauses.jsonl")).unwrap();
    assert_eq!(read_pauses(&history, None, now_ms())[0].reason, reason);
}

#[test]
fn watchdog_queued_behind_dns_off_preserves_the_full_break() {
    let (entered, waiting) = mpsc::channel();
    let (release, released) = mpsc::channel();
    let released = Mutex::new(released);
    let f = Fixture::new(move |mode| {
        if mode == "off" {
            entered.send(()).unwrap();
            released.lock().unwrap().recv_timeout(Duration::from_secs(5)).unwrap();
        }
        ok(mode)
    });
    let c = f.core.clone();
    let pause = thread::spawn(move || c.start_pause(5, "Synthetic break".into(), None));
    waiting.recv_timeout(Duration::from_secs(5)).unwrap();
    // Before the fix this lock was free while DNS off ran, allowing the
    // watchdog to decide protection should be on using the old saved state.
    let transition_is_locked = f.core.saved.try_lock().is_err();
    let c = f.core.clone();
    let watchdog = thread::spawn(move || c.reconcile(true));
    release.send(()).unwrap();
    pause.join().unwrap().unwrap();
    assert!(!watchdog.join().unwrap().unwrap());
    assert!(transition_is_locked, "pause decision and DNS off must share the state lock");
    let s = f.core.saved.lock().unwrap();
    assert_eq!(s.paused_until - s.paused_at, 300_000);
    assert_eq!(*f.calls.lock().unwrap(), ["off"]);
    assert_eq!(f.events().len(), 1);
}

#[test]
fn pause_queued_behind_status_check_runs_after_the_repair() {
    let (entered, waiting) = mpsc::channel();
    let (release, released) = mpsc::channel();
    let released = Mutex::new(released);
    let f = Fixture::new(move |mode| {
        if mode == "status" {
            entered.send(()).unwrap();
            released.lock().unwrap().recv_timeout(Duration::from_secs(5)).unwrap();
        }
        ok(mode)
    });
    let c = f.core.clone();
    let watchdog = thread::spawn(move || c.reconcile(true));
    waiting.recv_timeout(Duration::from_secs(5)).unwrap();
    let transition_is_locked = f.core.saved.try_lock().is_err();
    let c = f.core.clone();
    let pause = thread::spawn(move || c.start_pause(10, "Synthetic break".into(), None));
    release.send(()).unwrap();
    assert!(watchdog.join().unwrap().unwrap());
    pause.join().unwrap().unwrap();
    assert!(transition_is_locked, "watchdog must lock its decision through any repair");
    assert_eq!(*f.calls.lock().unwrap(), ["status", "on", "off"]);
    let s = f.core.saved.lock().unwrap();
    assert_eq!(s.paused_until - s.paused_at, 600_000);
    assert_eq!(f.events().len(), 1);
}

#[test]
fn startup_and_repeated_watchdog_checks_preserve_five_and_ten_minute_breaks() {
    for minutes in [5, 10] {
        let f = Fixture::new(ok);
        f.core.start_pause(minutes, "Synthetic break".into(), None).unwrap();
        assert!(!f.core.startup().unwrap());
        for _ in 0..3 { assert!(!f.core.reconcile(true).unwrap()); }
        let s = f.core.saved.lock().unwrap();
        assert_eq!(s.paused_until - s.paused_at, minutes * 60_000);
        assert_eq!(*f.calls.lock().unwrap(), ["off"]);
    }
}

#[test]
fn a_second_pause_cannot_overwrite_an_active_break() {
    let f = Fixture::new(ok);
    f.core.start_pause(5, "First synthetic break".into(), None).unwrap();
    assert_eq!(f.core.start_pause(10, "Second synthetic break".into(), None), Err("Already paused".into()));
    assert_eq!(*f.calls.lock().unwrap(), ["off"]);
    assert_eq!(f.events().len(), 1);
}

#[test]
fn failed_dns_off_does_not_commit_a_pause() {
    let f = Fixture::new(|_| Err("synthetic DNS failure".into()));
    assert!(f.core.start_pause(5, "Synthetic break".into(), None).is_err());
    assert_eq!(f.core.saved.lock().unwrap().paused_until, 0);
    assert!(f.events().is_empty());
}

#[test]
fn failed_restore_keeps_pause_and_retry_records_the_actual_completion() {
    let attempts = AtomicU64::new(0);
    let f = Fixture::new(move |mode| {
        if mode == "on" && attempts.fetch_add(1, Ordering::Relaxed) == 0 {
            return Err("synthetic restore failure".into());
        }
        ok(mode)
    });
    let start = now_ms() - 326_000;
    let deadline = start + 300_000;
    *f.core.saved.lock().unwrap() = Saved {
        paused_at: start, paused_until: deadline, reason: "Synthetic break".into(),
        ..Saved::default()
    };
    f.core.history_line(serde_json::json!({
        "type": "pause", "t": start, "until": deadline, "minutes": 5,
    })).unwrap();
    assert!(f.core.reconcile(false).is_err());
    assert_eq!(f.core.saved.lock().unwrap().paused_until, deadline);
    assert!(f.core.view().rating_prompt.is_none());
    assert_eq!(f.events().len(), 1);
    assert!(f.core.start_pause(10, "Another synthetic break".into(), None).is_err());
    assert!(f.core.reconcile(false).unwrap());
    let events = f.events();
    let resume = &events[1];
    assert_eq!(resume["paused_at"], start);
    assert_eq!(resume["scheduled_until"], deadline);
    assert_eq!(resume["why"], "timer ended");
    assert!(resume["t"].as_u64().unwrap() >= deadline + 26_000);
    assert!(resume["requested_at"].as_u64().unwrap() <= resume["t"].as_u64().unwrap());
    let text = fs::read_to_string(f.core.dir.join("pauses.jsonl")).unwrap();
    assert_eq!(read_pauses(&text, None, now_ms())[0].end, resume["t"].as_u64().unwrap());
    let saved: Saved = serde_json::from_slice(&fs::read(f.core.dir.join("state.json")).unwrap()).unwrap();
    assert_eq!(saved.paused_until, 0);
    assert_eq!(saved.pause_ended_at, resume["t"].as_u64());
    assert_eq!(saved.rating_prompt.unwrap().ended_at, resume["t"].as_u64().unwrap());
    assert_eq!(*f.calls.lock().unwrap(), ["on", "on"]);
}

#[test]
fn rating_requires_a_completed_pause_and_rejects_a_stale_or_duplicate_response() {
    let f = Fixture::new(ok);
    f.core.start_pause(5, "Read the thread".into(), Some(CheckIn::Stuck)).unwrap();
    let id = f.core.view().paused_at;
    assert!(f.rate(id, Some(1), "").is_err());
    f.core.enable_locked(&mut f.core.saved.lock().unwrap(), "turned on early").unwrap();
    assert!(f.rate(id + 1, Some(1), "").is_err());
    assert!(f.rate(id, Some(6), "").is_err());
    let path = f.core.dir.join("pauses.jsonl");
    let text = fs::read_to_string(&path).unwrap();
    fs::write(&path, text.trim_end_matches('\n')).unwrap();
    f.rate(id, Some(2), " No relevant posts ").unwrap();
    assert!(f.rate(id, Some(5), "").is_err());
    let event = &f.events()[2];
    assert!(event["t"].is_null() && event["at"].is_null());
    assert!(event["rated_at"].is_u64());
    let text = fs::read_to_string(path).unwrap();
    let pauses = read_pauses(&text, None, now_ms());
    assert_eq!(pauses.len(), 1);
    assert_eq!(pauses[0].check_in, Some(CheckIn::Stuck));
    assert_eq!(pauses[0].rating, Some(2));
    assert_eq!(pauses[0].rating_note.as_deref(), Some("No relevant posts"));
}

#[test]
fn startup_recovers_the_original_resume_after_a_stale_state_write() {
    let f = Fixture::new(ok);
    f.core.start_pause(5, "Read the thread".into(), Some(CheckIn::Stuck)).unwrap();
    let stale = f.core.saved.lock().unwrap().clone();
    f.core.enable_locked(&mut f.core.saved.lock().unwrap(), "turned on early").unwrap();
    let ended = f.events()[1]["t"].as_u64().unwrap();
    *f.core.saved.lock().unwrap() = stale.clone();
    assert!(f.core.startup().unwrap());
    assert_eq!(f.events().len(), 2);
    assert_eq!(f.core.saved.lock().unwrap().pause_ended_at, Some(ended));
    assert_eq!(f.core.view().rating_prompt.unwrap().ended_at, ended);
    f.rate(stale.paused_at, Some(2), "").unwrap();
    *f.core.saved.lock().unwrap() = stale;
    assert!(f.core.startup().unwrap());
    assert_eq!(f.events().len(), 3);
    assert!(f.core.view().rating_prompt.is_none());
}

#[test]
fn a_recorded_rating_closes_a_prompt_left_behind_by_a_failed_state_write() {
    let f = Fixture::new(ok);
    f.core.start_pause(5, "Read the thread".into(), Some(CheckIn::Stuck)).unwrap();
    let id = f.core.view().paused_at;
    f.core.enable_locked(&mut f.core.saved.lock().unwrap(), "turned on early").unwrap();
    let stale = f.core.saved.lock().unwrap().clone();
    f.rate(id, Some(2), "").unwrap();

    *f.core.saved.lock().unwrap() = stale.clone();
    assert!(f.rate(id, Some(3), "").is_err());
    assert!(f.core.view().rating_prompt.is_none());
    *f.core.saved.lock().unwrap() = stale.clone();
    assert!(f.core.startup().unwrap());
    assert!(f.core.view().rating_prompt.is_none());

    *f.core.saved.lock().unwrap() = Saved {
        paused_at: id + 1, paused_until: now_ms() + 300_000,
        ..stale
    };
    assert!(!f.core.startup().unwrap());
    assert_eq!(f.core.view().paused_at, id + 1);
    assert!(f.core.view().rating_prompt.is_none());
    assert_eq!(f.events().len(), 3);
}

#[test]
fn startup_recovers_a_linked_resume_before_the_recorded_start_after_clock_rollback() {
    let f = Fixture::new(ok);
    let ended = now_ms() - 1_000;
    let id = ended + 60_000;
    *f.core.saved.lock().unwrap() = Saved {
        paused_at: id, paused_until: id + 300_000, reason: "Read the thread".into(),
        ..Saved::default()
    };
    f.core.history_line(serde_json::json!({"type": "pause", "t": id, "minutes": 5})).unwrap();
    f.core.history_line(serde_json::json!({"type": "resume", "t": ended, "paused_at": id})).unwrap();
    assert!(f.core.startup().unwrap());
    assert_eq!(f.core.saved.lock().unwrap().pause_ended_at, Some(ended));
    assert_eq!(f.core.view().paused_until, 0);
    assert_eq!(f.core.view().rating_prompt.unwrap().ended_at, ended);
    f.rate(id, Some(4), "").unwrap();
    let pause = f.core.recorded_pause(id, None).unwrap();
    assert_eq!(pause.end, id);
    assert_eq!(pause.rating, Some(4));
    assert_eq!(f.events().len(), 3);
}

#[test]
fn an_older_unlinked_resume_does_not_end_an_active_pause_after_clock_rollback() {
    let f = Fixture::new(ok);
    let now = now_ms();
    let id = now + 10_000;
    f.core.history_line(serde_json::json!({"type": "pause", "t": now - 60_000, "minutes": 5})).unwrap();
    f.core.history_line(serde_json::json!({"type": "resume", "t": now + 30_000})).unwrap();
    f.core.history_line(serde_json::json!({"type": "pause", "t": id, "minutes": 5})).unwrap();
    *f.core.saved.lock().unwrap() = Saved {
        paused_at: id, paused_until: id + 300_000, reason: "Read the thread".into(),
        ..Saved::default()
    };

    assert!(!f.core.startup().unwrap());
    assert_eq!(f.core.view().paused_at, id);
    assert!(f.core.view().rating_prompt.is_none());
    assert!(f.calls.lock().unwrap().is_empty());
    let text = fs::read_to_string(f.core.dir.join("pauses.jsonl")).unwrap();
    let pauses = read_pauses(&text, Some((id, None)), now);
    assert_eq!(pauses[0].end, now + 30_000);
    assert_eq!(pauses[1].end, id);
}

#[test]
fn lan_snapshot_revision_survives_a_clock_rollback_and_restart() {
    let f = Fixture::new(ok);
    assert_eq!(f.core.next_event_sequence(1000).unwrap(), 1000);
    assert_eq!(f.core.next_event_sequence(900).unwrap(), 1001);
    *f.core.event_sequence.lock().unwrap() = None;
    assert_eq!(f.core.next_event_sequence(800).unwrap(), 1002);
}

#[test]
fn each_lan_revision_carries_the_pause_limit_captured_for_it() {
    use focus_dns::habits::DesktopLimit;

    let f = Fixture::new(ok);
    f.core.habits.set_desktop_limit(DesktopLimit { minutes: 90, pending: None }).unwrap();
    let older = f.core.laptop_pause_snapshot().unwrap();
    f.core.habits.set_desktop_limit(DesktopLimit { minutes: 30, pending: None }).unwrap();
    let newer = f.core.laptop_pause_snapshot().unwrap();

    // The newer reply can finish first. Encoding either response must use its own captured policy.
    assert!(newer.revision > older.revision);
    assert_eq!(newer.desktop_limit.effective(newer.now), 30);
    assert_eq!(older.desktop_limit.effective(older.now), 90);
}

#[test]
fn journal_ids_keep_pauses_and_prompts_separate_at_the_same_wall_time() {
    let f = Fixture::new(ok);
    let now = now_ms();
    let started = now - 5_000;
    f.core.history_line(serde_json::json!({"type": "pause", "t": started, "pause_id": 1, "minutes": 5})).unwrap();
    f.core.history_line(serde_json::json!({"type": "resume", "t": now - 3_000, "paused_at": started, "pause_id": 1})).unwrap();
    f.core.history_line(serde_json::json!({"type": "pause", "t": started, "pause_id": 2, "minutes": 5})).unwrap();
    *f.core.saved.lock().unwrap() = Saved {
        paused_at: started, paused_until: now + 300_000, pause_id: Some(2),
        ..Saved::default()
    };
    assert!(!f.core.startup().unwrap());
    assert!(f.calls.lock().unwrap().is_empty());
    f.core.enable_locked(&mut f.core.saved.lock().unwrap(), "turned on early").unwrap();
    assert!(f.core.rate_pause(started, Some(1), Some(5), "").is_err());
    f.core.rate_pause(started, Some(2), Some(3), "").unwrap();
    assert_eq!(f.core.recorded_pause(started, Some(1)).unwrap().rating, None);
    assert_eq!(f.core.recorded_pause(started, Some(2)).unwrap().rating, Some(3));
}

#[test]
fn an_expired_or_dismissed_prompt_does_not_record_a_rating() {
    let f = Fixture::new(ok);
    f.core.start_pause(5, "Read the thread".into(), Some(CheckIn::Bored)).unwrap();
    let id = f.core.view().paused_at;
    f.core.enable_locked(&mut f.core.saved.lock().unwrap(), "turned on early").unwrap();
    f.core.saved.lock().unwrap().rating_prompt.as_mut().unwrap().expires_at = now_ms();
    assert!(f.core.view().rating_prompt.is_none());
    assert!(f.rate(id, Some(1), "").is_err());

    f.core.saved.lock().unwrap().rating_prompt.as_mut().unwrap().expires_at = now_ms() + RATING_WINDOW_MS;
    f.rate(id, None, "").unwrap();
    assert!(f.core.view().rating_prompt.is_none());
    assert_eq!(f.events().len(), 2);
}

#[test]
fn manual_early_resume_and_expired_startup_keep_their_causes() {
    for (cause, until) in [("turned on early", now_ms() + 300_000), ("startup", now_ms() - 1000)] {
        let f = Fixture::new(ok);
        *f.core.saved.lock().unwrap() = Saved {
            paused_at: now_ms() - 2000, paused_until: until, reason: "Synthetic break".into(),
            ..Saved::default()
        };
        if cause == "startup" { assert!(f.core.startup().unwrap()); }
        else { f.core.enable_locked(&mut f.core.saved.lock().unwrap(), cause).unwrap(); }
        assert_eq!(f.events()[0]["why"], cause);
        assert_eq!(f.core.saved.lock().unwrap().paused_until, 0);
        let ended_at = f.events()[0]["t"].as_u64();
        assert_eq!(f.core.saved.lock().unwrap().pause_ended_at, ended_at);
        f.core.startup().unwrap();
        assert_eq!(f.core.saved.lock().unwrap().pause_ended_at, ended_at);
    }
}

#[test]
fn missing_gateway_issues_a_manual_fallback_and_never_pauses_without_a_hold() {
    let f = Fixture::new(ok);
    let tracker = Tracker::new(&f.core.dir);
    let devices = device_commands::Devices(Err("in-memory fixture".into()));
    let fallback = pause_judge::judge(&f.core, &tracker, &devices, judge::Request {
        text: "Check Discord".into(), check_in: Some(judge::CheckIn::Stuck),
    }, judge::ask_budget(None, false)).unwrap();
    let decision = serde_json::to_value(fallback).unwrap();
    assert_eq!(decision["failure"], "configuration");
    assert_eq!(decision["friction"]["requires_plan"], true);
    let id = decision["id"].as_str().unwrap();
    assert!(pause_judge::begin_hold(&f.core, &devices, id, false).is_err());
    let application = serde_json::from_value(serde_json::json!({"id": id, "manual_minutes": 30})).unwrap();
    assert!(pause_judge::apply(&f.core, &devices, application).is_err());
    assert!(f.calls.lock().unwrap().is_empty());
    let log_deadline = Instant::now() + Duration::from_secs(2);
    let history = loop {
        if let Ok(history) = fs::read_to_string(f.core.dir.join("pauses.jsonl")) {
            if history.ends_with('\n') {
                break history;
            }
        }
        assert!(Instant::now() < log_deadline, "Judge history write did not finish");
        thread::sleep(Duration::from_millis(10));
    };
    let event: serde_json::Value = serde_json::from_str(&history).unwrap();
    assert_eq!(event["decision_id"], id);
    assert_eq!(event["action"], "fallback");
    assert!(read_pauses(&history, None, now_ms()).is_empty());
}

#[test]
fn scoped_cleanup_keeps_the_recorded_profile_and_requires_confirmation_for_old_grants() {
    for legacy_confirmed in [false, true] {
        let f = Fixture::new(ok);
        fs::write(f.core.dir.join("nextdns.json"), serde_json::json!({
            "profile": "a1b2c3",
            "legacyConfirmed": legacy_confirmed,
            "servers": ["192.0.2.10", "198.51.100.20", "2001:db8::10", "2001:db8::20"]
        }).to_string()).unwrap();
        assert!(f.core.dns_config.set(focus_browser_host::NextDnsConfig::read(&f.core.dir).unwrap()).is_ok());
        assert_eq!(pause_judge::cleanup_profile(&f.core, Some("d4e5f6")).unwrap(), "d4e5f6");
        assert_eq!(pause_judge::cleanup_profile(&f.core, None).ok(), legacy_confirmed.then_some("a1b2c3"));
    }
}

#[test]
fn scoped_startup_cleanup_does_not_create_a_dns_off_interval() {
    let f = Fixture::new(ok);
    let start = now_ms() - 100_000;
    let mut saved = f.core.saved.lock().unwrap();
    saved.scoped_pause = Some(nextdns::Lease {
            profile: None,
        until: now_ms() - 1, started: Some(start), timer: None, services: Vec::new(), changes: Default::default(), closing: false, in_flight: None, trace: None,
    });
    f.core.save(&saved);
    let mut reloaded: Saved = serde_json::from_slice(&fs::read(f.core.dir.join("state.json")).unwrap()).unwrap();
    f.core.history_line(serde_json::json!({ "type":"scoped_pause", "t":start, "until":now_ms() - 1, "minutes":1 }));
    assert!(pause_judge::sweep_scope(&f.core, &mut reloaded, false).unwrap());
    assert!(reloaded.scoped_pause.is_none());
    assert_eq!(f.events()[1]["type"], "scoped_resume");
    let text = fs::read_to_string(f.core.dir.join("pauses.jsonl")).unwrap();
    assert!(read_pauses(&text, None, now_ms()).is_empty());
    assert_eq!(pause_history::read_access_pauses(&text, None, None, now_ms()).len(), 1);
    assert!(f.calls.lock().unwrap().is_empty());
}

#[test]
fn laptop_snapshot_keeps_a_started_grant_until_cleanup_completes() {
    let fail_first_enable = AtomicBool::new(true);
    let f = Fixture::new(move |mode| {
        if mode == "on" && fail_first_enable.swap(false, Ordering::Relaxed) {
            return Err("Could not reapply adapter DNS".into());
        }
        ok(mode)
    });
    let start = now_ms();
    let until = start + 180_000;
    f.core.saved.lock().unwrap().scoped_pause = Some(nextdns::Lease {
            profile: None,
        until,
        started: Some(start),
        timer: Some(nextdns::GrantTimer { id: "grant".into(), duration_ms: 180_000 }),
        services: vec!["x".into()],
        changes: Default::default(),
        closing: true,
        in_flight: None,
        trace: None,
    });
    let pending = f.core.laptop_pause_snapshot().unwrap();
    assert_eq!(pending.scoped_until, until);
    assert_eq!(pending.scoped_grant.unwrap().id, "grant");

    let sample = device_commands::scoped_apply_sample(&f.core, "grant", until, Instant::now()).unwrap().unwrap();
    assert_eq!(sample["dns"]["scoped_until"], until);
    assert_eq!(sample["dns"]["scoped_grant"]["duration_ms"], 180_000);
    assert_eq!(sample["dns"]["scoped_services"][0], "x");
    assert!(sample["now"].as_u64().unwrap() >= start);
    assert!(sample["snapshot_revision"].as_u64().unwrap() > pending.revision);
    assert!(device_commands::scoped_apply_sample(&f.core, "old-grant", until, Instant::now()).unwrap().is_none());
    assert!(!device_commands::end_scoped_access(&f.core, "old-grant").unwrap());
    assert!(f.calls.lock().unwrap().is_empty());
    assert_eq!(device_commands::end_scoped_access(&f.core, "grant").unwrap_err(), "Could not reapply adapter DNS");
    assert_eq!(f.core.laptop_pause_snapshot().unwrap().scoped_until, until);
    assert!(f.events().is_empty());
    assert!(device_commands::end_scoped_access(&f.core, "grant").unwrap());
    assert_eq!(f.core.laptop_pause_snapshot().unwrap().scoped_until, 0);
    assert_eq!(*f.calls.lock().unwrap(), ["on", "on"]);
    assert_eq!(f.events()[0]["type"], "scoped_resume");
}

#[test]
fn startup_keeps_existing_state_when_it_cannot_be_decoded() {
    let f = Fixture::new(ok);
    let path = f.core.dir.join("state.json");
    let incomplete = br#"{"scoped_pause": {"until": 123"#;
    fs::write(&path, incomplete).unwrap();
    assert_eq!(f.core.read_state_for_startup().err().unwrap().kind(), std::io::ErrorKind::Other);
    assert_eq!(fs::read(&path).unwrap(), incomplete);
    assert_eq!(*f.calls.lock().unwrap(), ["on"]);
}

#[test]
fn startup_preserves_the_last_unsettled_grant_from_the_first_preview() {
    let f = Fixture::new(ok);
    let document = serde_json::json!({
        "paused_at":0, "paused_until":0, "reason":"",
        "scoped_pause":{
            "until":1, "started":null, "settle_until":100, "closing":true,
            "changes":{"a.example":"inactive","b.example":"missing"}
        }
    });
    fs::write(f.core.dir.join("state.json"), serde_json::to_vec(&document).unwrap()).unwrap();
    let saved = f.core.read_state_for_startup().unwrap();
    assert_eq!(saved.scoped_pause.unwrap().in_flight.as_deref(), Some("b.example"));
}

#[test]
fn startup_restores_local_dns_before_cleaning_up_a_legacy_overlapping_scope() {
    let fail_once = AtomicBool::new(true);
    let f = Fixture::new(move |mode| {
        if mode == "on" && fail_once.swap(false, Ordering::Relaxed) {
            return Err("temporary restore error".into());
        }
        ok(mode)
    });
    let started = now_ms() - 1000;
    {
        let mut saved = f.core.saved.lock().unwrap();
        saved.paused_at = started;
        saved.paused_until = started + 300_000;
        saved.scoped_pause = Some(nextdns::Lease {
            profile: None,
            until: started + 900_000, started: Some(started), timer: None, services: Vec::new(), changes: Default::default(),
            closing: true, in_flight: None, trace: None,
        });
    }
    assert!(f.core.startup().is_err());
    assert!(f.core.reconcile(false).unwrap());
    assert_eq!(*f.calls.lock().unwrap(), ["on", "on"]);
    let mut saved = f.core.saved.lock().unwrap();
    assert_eq!(saved.paused_until, 0);
    assert!(saved.pause_ended_at.is_some());
    assert!(pause_judge::sweep_scope(&f.core, &mut saved, false).unwrap());
    assert!(saved.scoped_pause.is_none());
}
