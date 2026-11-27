use super::{
    away_minutes, CreditCap, DesktopLimit, Event, Habit, Ledger, PendingLimit, Revision, Screen,
    Store,
};

const MON: i32 = 20_000 - (20_000 + 3) % 7;

fn revision(timestamp: u64, id: &str) -> Revision {
    Revision {
        timestamp,
        id: id.into(),
    }
}

fn habit(id: &str, interval: i32, weekdays: u8) -> Habit {
    Habit {
        id: id.into(),
        rev: revision(10, "a"),
        name: id.into(),
        interval,
        weekdays,
        time: Some(720),
        reward: 15,
        start: MON,
        deleted: false,
        schedules: Vec::new(),
    }
}

fn event(id: &str, habit: &str, day: i32, period: i32) -> Event {
    Event {
        id: id.into(),
        habit: habit.into(),
        at: day as u64 * 86_400_000,
        day,
        period,
        reward: 15,
        away: Some(11),
        undo: None,
        wallet_reward: false,
    }
}

fn put(ledger: &mut Ledger, event: Event) {
    ledger.events.insert(event.id.clone(), event);
}

#[test]
fn missed_periods_follow_cadence_and_late_completion_uses_current_period() {
    for (interval, weekdays, periods) in [
        (1, 0, [0, 1, 2, 3]),
        (3, 0, [0, 3, 6, 9]),
        (1, 5, [0, 2, 7, 9]),
    ] {
        let h = habit("Dishes", interval, weekdays);
        let mut ledger = Ledger::default();
        ledger.definitions.insert(h.id.clone(), h);
        put(&mut ledger, event("first", "Dishes", MON, MON));
        let rows = ledger.status(MON + periods[1], 719);
        assert!(rows[0].due && !rows[0].overdue && !rows[0].grace);
        assert!(ledger.status(MON + periods[1], 720)[0].overdue);
        let rows = ledger.status(MON + periods[2], 0);
        assert!(rows[0].grace);
        assert_eq!(rows[0].streak, 1);
        assert_eq!(ledger.status(MON + periods[3], 0)[0].streak, 0);
        put(
            &mut ledger,
            event("recovered", "Dishes", MON + periods[2], MON + periods[2]),
        );
        assert_eq!(ledger.status(MON + periods[2], 0)[0].streak, 2);
    }
    let mut ledger = Ledger::default();
    ledger
        .definitions
        .insert("Sweep".into(), habit("Sweep", 3, 0));
    assert!(ledger.status(MON + 1, 0)[0].overdue);
    put(&mut ledger, event("late", "Sweep", MON + 1, MON));
    assert!(!ledger.status(MON + 2, 0)[0].due);
    assert_eq!(ledger.credit(MON + 1), 15);
    assert_eq!(ledger.credit(MON), 0);
}

#[test]
fn concurrent_edits_and_undo_converge_and_credit_is_period_deduplicated() {
    let mut left = Ledger::default();
    let mut right = Ledger::default();
    let mut h = habit("Laundry", 1, 0);
    left.definitions.insert(h.id.clone(), h.clone());
    h.rev = revision(10, "b");
    h.name = "Laundry towels".into();
    right.definitions.insert(h.id.clone(), h);
    put(&mut left, event("check-a", "Laundry", MON, MON));
    put(&mut right, event("check-b", "Laundry", MON, MON));
    let mut undo = event("undo-a", "Laundry", MON, MON);
    undo.undo = Some("check-a".into());
    put(&mut right, undo);
    let initial = left.clone();
    left.merge(right.clone());
    right.merge(initial);
    assert_eq!(left, right);
    assert_eq!(left.status(MON, 0)[0].completion.unwrap().id, "check-b");
    assert_eq!(left.status(MON, 0)[0].habit.name, "Laundry towels");
    assert_eq!(left.credit(MON), 15);
    assert_eq!(left.events.len(), 3);
    let mut undo = event("undo-b", "Laundry", MON, MON);
    undo.undo = Some("check-b".into());
    put(&mut left, undo);
    assert_eq!(left.credit(MON), 0);
    assert!(left.status(MON, 0)[0].due);
}

#[test]
fn cap_and_append_only_pages_survive_round_trip() {
    let mut ledger = Ledger::default();
    for i in 0..515 {
        let id = format!("habit-{i:03}");
        ledger.definitions.insert(id.clone(), habit(&id, 1, 0));
        put(&mut ledger, event(&id, &id, MON, MON));
        put(
            &mut ledger,
            event(&format!("tomorrow-{id}"), &id, MON + 1, MON + 1),
        );
    }
    ledger.put_cap(CreditCap {
        rev: revision(10, "a"),
        minutes: 60,
        previous: 30,
        effective: MON + 1,
    });
    let mut remote = Ledger::default();
    let mut cursor = Some(String::new());
    let mut served = 0;
    while let Some(after) = cursor {
        let (page, next) = ledger.page(&after, false);
        assert!(page.cap_edits.len() + page.definitions.len() + page.events.len() <= 512);
        remote.merge(serde_json::from_str(&serde_json::to_string(&page).unwrap()).unwrap());
        cursor = next;
        served += 1;
        assert!(served <= 4);
    }
    assert_eq!(remote, ledger);
    assert_eq!(remote.credit(MON), 30);
    assert_eq!(remote.credit(MON + 1), 60);
}

#[test]
fn a_cadence_edit_does_not_offer_or_reward_a_second_completion_on_the_same_day() {
    let mut ledger = Ledger::default();
    let mut habit = habit("Laundry", 1, 0);
    let day = MON + 2;
    put(&mut ledger, event("older-period", "Laundry", day, MON + 1));
    habit.weekdays = 1 << 2;
    ledger.definitions.insert(habit.id.clone(), habit);
    let status = ledger.status(day, 0);
    assert!(!status[0].due);
    assert_eq!(status[0].completion.unwrap().id, "older-period");
    let mut competing = event("concurrent-period", "Laundry", day, day);
    competing.at += 1;
    let mut offline = Ledger::default();
    put(&mut offline, competing);
    ledger.merge(offline.clone());
    offline.merge(ledger.clone());
    for replica in [&ledger, &offline] {
        let rows = replica.status(day, 0);
        assert_eq!(rows[0].streak, 1);
        assert_eq!(&rows[0].week[5..], &["done", "miss"]);
        assert_eq!(rows[0].completion.unwrap().id, "older-period");
    }
    assert_eq!(ledger.credit(day), 15);
    ledger.undo("older-period", "undo".into(), day as u64 * 86_400_000 + 2);
    let status = ledger.status(day, 0);
    assert_eq!(status[0].streak, 1);
    assert_eq!(&status[0].week[5..], &["off", "done"]);
    assert_eq!(status[0].completion.unwrap().id, "concurrent-period");
    assert_eq!(ledger.credit(day), 15);
}

#[test]
fn cadence_change_keeps_the_previous_weeks_occurrences() {
    let daily = habit("Laundry", 1, 0);
    let edit_day = MON + 7;
    let mut left = Ledger::default();
    left.definitions.insert(daily.id.clone(), daily.clone());
    for day in MON..edit_day {
        put(
            &mut left,
            event(&format!("check-{day}"), "Laundry", day, day),
        );
    }
    let mut weekly = daily.clone();
    weekly.rev = revision(11, "weekly");
    weekly.weekdays = 1;
    weekly.keep_schedule_history(&daily, edit_day);
    let mut right = Ledger::default();
    right.definitions.insert(weekly.id.clone(), weekly);
    left.merge(right.clone());
    right.merge(left.clone());
    for replica in [&left, &right] {
        let rows = replica.status(edit_day, 0);
        assert!(rows[0].due);
        assert_eq!(rows[0].streak, 7);
        assert_eq!(
            rows[0].week,
            ["done", "done", "done", "done", "done", "done", "due"]
        );
        assert_eq!(replica.status(edit_day + 7, 0)[0].streak, 7);
        assert_eq!(replica.status(edit_day + 14, 0)[0].streak, 0);
    }
}

#[test]
fn offline_cadence_edits_retain_intervening_schedule_and_completions() {
    let original = habit("Laundry", 1, 1);
    let mut left = Ledger::default();
    let mut right = Ledger::default();
    let mut daily = original.clone();
    daily.rev = revision(11, "daily");
    daily.weekdays = 0;
    daily.keep_schedule_history(&original, MON + 1);
    left.definitions.insert(daily.id.clone(), daily);
    for day in MON + 1..=MON + 2 {
        put(
            &mut left,
            event(&format!("check-{day}"), "Laundry", day, day),
        );
    }
    let mut friday = original.clone();
    friday.rev = revision(12, "friday");
    friday.weekdays = 1 << 4;
    friday.keep_schedule_history(&original, MON + 3);
    right.definitions.insert(friday.id.clone(), friday);
    let original_left = left.clone();
    left.merge(right.clone());
    right.merge(original_left);
    assert_eq!(left, right);
    let row = &left.status(MON + 4, 0)[0];
    assert_eq!(row.habit.weekdays, 1 << 4);
    assert_eq!(row.streak, 2);
    assert_eq!(&row.week[3..], ["done", "done", "off", "due"]);
}

#[test]
fn newer_cadence_can_supersede_an_older_change_due_tomorrow() {
    let original = habit("Laundry", 1, 0);
    let mut left = Ledger::default();
    let mut right = Ledger::default();
    let mut delayed = original.clone();
    delayed.rev = revision(11, "delayed");
    delayed.interval = 2;
    delayed.keep_schedule_history(&original, MON + 2);
    left.definitions.insert(delayed.id.clone(), delayed);
    let mut newer = original.clone();
    newer.rev = revision(12, "newer");
    newer.weekdays = 1 << 4;
    newer.keep_schedule_history(&original, MON + 1);
    right.definitions.insert(newer.id.clone(), newer);
    left.merge(right);
    assert!(!left.status(MON + 2, 0)[0].due);
    assert!(left.status(MON + 4, 0)[0].due);
}

#[test]
fn merging_an_offline_cap_increase_cannot_restore_credit_today() {
    let mut left = Ledger::default();
    let mut right = Ledger::default();
    left.put_cap(CreditCap {
        rev: revision(10, "lower"),
        minutes: 30,
        previous: 45,
        effective: MON,
    });
    right.put_cap(CreditCap {
        rev: revision(11, "raise"),
        minutes: 60,
        previous: 45,
        effective: MON + 1,
    });
    left.merge(right.clone());
    right.merge(left.clone());
    assert_eq!(left, right);
    assert_eq!(left.cap_on(MON), 30);
    assert_eq!(left.cap_on(MON + 1), 60);
}

#[test]
fn persisted_desktop_pause_limit_uses_the_current_base_until_the_pending_change() {
    let path = std::env::temp_dir().join(format!("focus-desktop-limit-{}", std::process::id()));
    std::fs::create_dir_all(&path).unwrap();
    let store = Store::new(&path);
    store
        .set_desktop_limit(DesktopLimit {
            minutes: 30,
            pending: Some(PendingLimit {
                value: 60,
                from: 200,
            }),
        })
        .unwrap();
    assert_eq!(Store::new(&path).desktop_limit().effective(199), 30);
    assert_eq!(Store::new(&path).desktop_limit().effective(200), 60);
    std::fs::remove_dir_all(&path).unwrap();
}

#[test]
fn an_old_peers_empty_snapshot_is_not_evidence() {
    let mut snapshot = serde_json::json!({
        "permissions": {"usage":true}, "tracking_age_ms": 0,
        "now": 480_000, "range_from": 120_000, "spans": [],
    });
    assert!(Screen::from_snapshot(&snapshot).is_none());
    snapshot["activity_observed_from"] = serde_json::json!(420_000);
    let peer = Screen::from_snapshot(&snapshot).unwrap();
    assert_eq!(peer.from, 420_000);
    let local = Screen {
        from: 120_000,
        to: 480_000,
        spans: vec![],
    };
    assert_eq!(away_minutes(480_000, &local, &[Some(peer)]), Some(1));
}

#[test]
fn away_time_stops_at_newest_activity_and_requires_coverage() {
    let now = 120 * 60_000;
    let own = Screen {
        from: now - 32 * 60_000,
        to: now,
        spans: vec![[now - 20 * 60_000, now - 15 * 60_000], [now - 10_000, now]],
    };
    let peer = Screen {
        from: own.from,
        to: now - 25_000,
        spans: vec![[now - 18 * 60_000, now - 12 * 60_000]],
    };
    assert_eq!(away_minutes(now, &own, &[Some(peer.clone())]), Some(11));
    assert_eq!(away_minutes(now, &own, &[None]), None);
    let stale = Screen {
        to: now - 120_000,
        ..peer.clone()
    };
    assert_eq!(away_minutes(now, &own, &[Some(stale.clone())]), None);
    let older = Screen {
        to: now - 70_000,
        ..peer.clone()
    };
    assert_eq!(away_minutes(now, &own, &[Some(older), Some(stale)]), None);
    let active = Screen {
        spans: vec![[now - 50_000, peer.to]],
        ..peer
    };
    assert_eq!(away_minutes(now, &own, &[Some(active)]), Some(0));
}

#[test]
fn ledger_is_persisted_after_each_mutation() {
    let path = std::env::temp_dir().join(format!("focus-habits-{}", std::process::id()));
    std::fs::create_dir_all(&path).unwrap();
    let store = Store::new(&path);
    store
        .update(|ledger| {
            ledger
                .definitions
                .insert("Dishes".into(), habit("Dishes", 1, 0));
            Ok(())
        })
        .unwrap();
    let prior = store.read();
    let result = store.update::<()>(|ledger| {
        ledger.definitions.clear();
        Err("failed write".into())
    });
    assert!(result.is_err());
    assert_eq!(Store::new(&path).read(), prior);
    std::fs::remove_dir_all(&path).unwrap();
}

#[test]
fn default_wallet_opens_and_persists_without_streak_enrollment() {
    let path = std::env::temp_dir().join(format!("focus-default-wallet-{}", std::process::id()));
    std::fs::create_dir_all(&path).unwrap();
    let store = Store::new(&path);
    store.update(|ledger| {
        put(ledger, event("before-upgrade", "read", MON, MON));
        Ok(())
    }).unwrap();
    assert!(store.activate_wallet(MON).unwrap());
    let reopened = Store::new(&path);
    assert!(!reopened.activate_wallet(MON + 1).unwrap());
    let ledger = reopened.read();
    assert!(ledger.streak_journal.is_none());
    assert!(ledger.wallet_active(MON));
    assert_eq!(ledger.balance(), 0);
    assert_eq!(ledger.extra_pause_minutes(MON), 15);
    assert_eq!(ledger.extra_pause_minutes(MON + 1), 0);
    let invalid = "unreadable ledger";
    std::fs::write(path.join("habits.json"), invalid).unwrap();
    assert!(Store::new(&path).activate_wallet(MON).is_err());
    assert_eq!(std::fs::read_to_string(path.join("habits.json")).unwrap(), invalid);
    std::fs::remove_dir_all(&path).unwrap();
}

#[test]
fn wallet_cutover_uses_qualified_habits_and_undo_can_create_debt() {
    let mut wallet = Ledger::default();
    wallet.start_wallet(MON).unwrap();
    let at = MON as u64 * 86_400_000;
    let day = MON + 1;
    put(&mut wallet, event("old", "walk", MON, MON));
    put(&mut wallet, event("legacy-offline", "read", day, day));
    let mut check = event("new", "walk", day, day);
    check.wallet_reward = true;
    put(&mut wallet, check.clone());
    let mut duplicate = check.clone();
    duplicate.id = "new-duplicate".into();
    duplicate.at += 1;
    put(&mut wallet, duplicate);

    let (legacy_page, _) = wallet.page("", false);
    assert_eq!(legacy_page.wallet_start, None);
    assert!(!legacy_page.events["new"].wallet_reward);
    assert_eq!(wallet.extra_pause_minutes(MON), 15);
    assert_eq!(wallet.extra_pause_minutes(day), 15);
    assert_eq!(wallet.balance(), 15);

    let mut relayed = legacy_page;
    relayed.merge(wallet.clone());
    assert_eq!(relayed.balance(), 15);
    assert_eq!(
        relayed.buy_time_off("first", 5, at, day),
        Ok(super::Purchase::Bought)
    );
    assert_eq!(
        relayed.buy_time_off_for_day("first", 5, at, day + 1, Some(day)),
        Ok(super::Purchase::AlreadyBought)
    );
    assert!(relayed
        .buy_time_off_for_day("second", 1, at, day + 1, Some(day))
        .is_err());
    assert_eq!(relayed.extra_pause_minutes(day), 20);
    assert_eq!(relayed.extra_pause_minutes(day + 1), 0);

    let mut undo = event("undo", "walk", day, day);
    undo.undo = Some(check.id);
    undo.reward = 0;
    put(&mut relayed, undo.clone());
    assert_eq!(relayed.balance(), 0);
    undo.id = "undo-duplicate".into();
    undo.undo = Some("new-duplicate".into());
    put(&mut relayed, undo);
    assert_eq!(relayed.balance(), -15);
    assert_eq!(
        relayed.buy_time_off("second", 5, at, day),
        Ok(super::Purchase::NotEnough { missing: 30 })
    );
    assert_eq!(relayed.time_off_bought(day), 5);
}

#[test]
fn wallet_merge_keeps_offline_purchases_and_converges_with_reversals() {
    use super::Purchase::*;
    let day = MON + 1;
    let at = day as u64 * 86_400_000;
    let mut phone = Ledger::default();
    phone.start_wallet(MON).unwrap();
    assert_eq!(
        phone.earn("first", "future_kind", 40, "", at, day),
        Ok(true)
    );
    assert_eq!(
        phone.earn("first", "future_kind", 40, "", at, day),
        Ok(false)
    );
    let mut laptop = phone.clone();
    assert_eq!(phone.buy_time_off("phone", 10, at, day), Ok(Bought));
    assert_eq!(laptop.buy_time_off("laptop", 10, at, day), Ok(Bought));
    assert_eq!(
        laptop.buy_time_off("over-cap", 10, at, day),
        Ok(LimitReached)
    );

    let original_phone = phone.clone();
    phone.merge(laptop.clone());
    laptop.merge(original_phone);
    assert_eq!(phone, laptop);
    assert_eq!(phone.balance(), -20);
    assert_eq!(phone.time_off_bought(day), 20);
    assert_eq!(phone.buy_time_off("next", 1, at, day), Ok(LimitReached));
    assert_eq!(
        phone.buy_time_off("next", 1, at, day + 1),
        Ok(NotEnough { missing: 23 })
    );

    phone.earn("fund", "future_kind", 200, "", at, day).unwrap();
    laptop
        .earn("fund", "future_kind", 200, "", at, day)
        .unwrap();
    let key = "streak:clean:2024-10-03";
    assert_eq!(
        phone.spend(key, "streak_repair", 60, "clean:2024-10-03", at, day),
        Ok(Bought)
    );
    assert_eq!(
        laptop.spend(key, "streak_repair", 120, "clean:2024-10-03", at, day),
        Ok(Bought)
    );
    let original_phone = phone.clone();
    phone.merge(laptop.clone());
    laptop.merge(original_phone);
    assert_eq!(phone, laptop);
    assert_eq!(phone.balance(), 60);
    assert_eq!(phone.entries("streak_repair", day, day)[0].amount, -120); // Tie picks the lower signed amount.
    assert_eq!(phone.reverse(key, at, day), Ok(true));
    assert_eq!(phone.reverse(key, at, day), Ok(false));
    assert!(phone.entries("streak_repair", day, day).is_empty());
    assert_eq!(phone.balance(), 180);
    laptop.merge(phone);
    assert_eq!(laptop.balance(), 180);
}

#[test]
fn wallet_pages_validate_entries_and_restore_after_restart() {
    let mut source = Ledger::default();
    source.start_wallet(MON).unwrap();
    let day = MON + 1;
    let at = day as u64 * 86_400_000;
    for index in 0..515 {
        source
            .earn(&format!("a-{index:03}"), "example", 1, "", at, day)
            .unwrap();
    }
    source.reverse("a-514", at, day).unwrap();
    let (legacy, next) = source.page("", false);
    assert!(next.is_none());
    assert!(serde_json::to_value(legacy)
        .unwrap()
        .get("wallet_entries")
        .is_none());
    let (first, next) = source.page("", true);
    assert_eq!(next.as_deref(), Some("w:a-511"));
    let (second, end) = source.page(&next.unwrap(), true);
    assert!(end.is_none());

    let mut before_target: Ledger = serde_json::from_value(serde_json::json!({
        "wallet_entries": {
            "reverse:a-514": { "id": "reverse:a-514", "at": at, "day": day, "amount": 0,
                "kind": "reversal", "ref": "", "reverses": "a-514" },
            "bad": { "id": "bad", "at": at, "day": day, "amount": 4294967296_u64, "kind": "bad", "ref": "" }
        }
    })).unwrap();
    assert_eq!(before_target.balance(), 0);
    assert_eq!(before_target.wallet_entries.len(), 1);
    before_target.merge(second);
    before_target.merge(first);
    assert_eq!(before_target, source);
    assert_eq!(before_target.balance(), 514);

    let dir = std::env::temp_dir().join(format!("focus-wallet-test-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    Store::new(&dir)
        .update(|ledger| {
            *ledger = before_target.clone();
            Ok(())
        })
        .unwrap();
    assert_eq!(Store::new(&dir).read(), before_target);
    std::fs::remove_dir_all(dir).unwrap();
}
