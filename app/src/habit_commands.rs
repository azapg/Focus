use crate::device_commands::{sync_habits, Devices, LaptopHost};
use crate::{now_ms, Core, Tracker};
use focus_dns::habits::{
    away_minutes, local_clock, CreditCap, DesktopLimit, Habit, Ledger, Purchase, Revision, Screen,
    TIME_OFF_PRICE_PER_MINUTE, WALLET_SYNC_VERSION,
};
use serde::Deserialize;
use serde_json::{json, Map, Value};
use std::collections::BTreeMap;
use std::sync::Arc;
use tauri::{AppHandle, Emitter, State};

fn id() -> Result<String, String> {
    use ring::rand::{SecureRandom, SystemRandom};
    let mut bytes = [0; 16];
    SystemRandom::new()
        .fill(&mut bytes)
        .map_err(|_| "Could not generate habit ID")?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

fn revision(prior: Option<&Revision>, now: u64) -> Result<Revision, String> {
    Ok(Revision {
        timestamp: prior.map_or(now, |p| p.timestamp.saturating_add(1).max(now)),
        id: id()?,
    })
}

pub fn live_screen(tracker: &Tracker, now: u64) -> Screen {
    let from = tracker
        .evidence_start()
        .max(now.saturating_sub(32 * 60_000));
    Screen {
        from,
        to: now,
        spans: tracker
            .spans(from, now)
            .iter()
            .map(|s| [s.s, s.e])
            .collect(),
    }
}

pub fn exchange(core: &Core, input: Value) -> Result<Value, String> {
    let wallet = input["wallet_sync"] == WALLET_SYNC_VERSION;
    if let Some(payload) = input.get("habits").filter(|v| !v.is_null()) {
        let mut ledger: Ledger =
            serde_json::from_value(payload.clone()).map_err(|_| "Invalid habits")?;
        if !wallet {
            ledger.discard_wallet();
        }
        core.habits.update(|local| {
            local.merge(ledger);
            Ok(())
        })?;
    }
    if let Some(raw) = input.get("screens").filter(|v| !v.is_null()) {
        let screens =
            serde_json::from_value(raw.clone()).map_err(|_| "Invalid screen observations")?;
        core.habits.merge_screens(screens);
    }
    let mut response = match input["after"].as_str() {
        Some(after) => {
            let (page, next) = core.habits.read().page(after, wallet);
            json!({ "habits": page, "next": next })
        }
        None => json!({}),
    };
    if wallet {
        response["wallet_sync"] = json!(WALLET_SYNC_VERSION);
    }
    Ok(response)
}

fn known_screens(
    core: &Core,
    peers: &[focus_devices::DeviceView],
) -> BTreeMap<String, Option<Screen>> {
    let mut screens = core.habits.screens();
    for peer in peers {
        let direct = peer.snapshot.as_ref().and_then(Screen::from_snapshot);
        let newer = screens.entry(peer.id.clone()).or_default();
        if let Some(direct) = direct {
            if newer.as_ref().is_none_or(|relayed| relayed.to <= direct.to) {
                *newer = Some(direct);
            }
        }
    }
    screens
}

pub fn screen_payload(
    core: &Core,
    tracker: &Tracker,
    laptop_id: &str,
    peers: &[focus_devices::DeviceView],
    target: &str,
) -> BTreeMap<String, Option<Screen>> {
    let mut screens = known_screens(core, peers);
    screens.remove(target);
    screens.insert(laptop_id.to_owned(), Some(live_screen(tracker, now_ms())));
    screens
}

pub fn allowance_payload(
    own: &Value,
    peers: &[focus_devices::DeviceView],
    target: &str,
    laptop_id: &str,
) -> Map<String, Value> {
    use chrono::{Local, TimeZone};
    let now = now_ms();
    let clock = Local
        .timestamp_millis_opt(now as i64)
        .single()
        .unwrap_or_else(Local::now);
    let midnight = clock.date_naive().and_hms_opt(0, 0, 0).unwrap();
    let from = Local
        .from_local_datetime(&midnight)
        .earliest()
        .map_or(now.saturating_sub(86_400_000), |t| {
            t.timestamp_millis() as u64
        });
    let day = local_clock(now).0;
    std::iter::once((laptop_id, own, true))
        .chain(
            peers
                .iter()
                .filter(|p| p.id != target)
                .filter_map(|p| Some((p.id.as_str(), p.snapshot.as_ref()?, false))),
        )
        .map(|(id, data, direct)| {
            let used: u64 = data["pauses"]
                .as_array()
                .into_iter()
                .flatten()
                .filter(|p| p["kind"] != "app")
                .filter_map(|p| {
                    Some(
                        p["end"]
                            .as_u64()?
                            .min(now)
                            .saturating_sub(p["t"].as_u64()?.max(from)),
                    )
                })
                .sum();
            let mut stats = json!({
                "day": day,
                "now": data["now"],
                "off": used,
                "revision": data["snapshot_revision"],
            });
            if let Some(base) = data["pause_daily_base"].as_u64() {
                stats["base"] = json!(base);
                stats["kind"] = data["kind"].clone();
                stats["limit"] = data["pause_daily_limit"].clone();
                stats["direct"] = json!(direct);
            }
            (id.to_owned(), stats)
        })
        .collect()
}

#[tauri::command]
pub fn habits_pause_limit(limit: DesktopLimit, core: State<'_, Arc<Core>>) -> Result<(), String> {
    core.habits.set_desktop_limit(limit)
}

#[tauri::command]
pub fn habits_view(core: State<'_, Arc<Core>>) -> Value {
    view(&core.habits.read(), now_ms())
}

fn view(ledger: &Ledger, now: u64) -> Value {
    let (day, minute) = local_clock(now);
    json!({ "rows": ledger.status(day, minute), "credit": ledger.extra_pause_minutes(day),
        "cap": ledger.cap_on(day), "pending_cap": ledger.cap.as_ref()
            .filter(|cap| cap.effective > day).map(|cap| cap.minutes) })
}

#[tauri::command]
pub fn wallet_view(core: State<'_, Arc<Core>>, devices: State<'_, Arc<Devices>>) -> Value {
    let day = local_clock(now_ms()).0;
    let ledger = core.habits.read();
    let peers = devices
        .0
        .as_ref()
        .map(|manager| manager.list())
        .unwrap_or_default();
    let needs_upgrade: Vec<_> = peers
        .iter()
        .filter(|peer| {
            peer.snapshot
                .as_ref()
                .is_some_and(|snapshot| snapshot["wallet_sync"] != WALLET_SYNC_VERSION)
        })
        .map(|peer| &peer.id)
        .collect();
    json!({
        "balance": ledger.balance(),
        "active": ledger.wallet_active(day),
        "start_day": ledger.wallet_start,
        "day": day,
        "time_off_bought": ledger.time_off_bought(day),
        "time_off_limit": ledger.cap_on(day),
        "time_off_price_per_minute": TIME_OFF_PRICE_PER_MINUTE,
        "needs_upgrade": needs_upgrade
    })
}

#[tauri::command]
pub fn wallet_buy_time(
    request_id: String,
    minutes: u16,
    app: AppHandle,
    core: State<'_, Arc<Core>>,
    devices: State<'_, Arc<Devices>>,
    tracker: State<'_, Arc<Tracker>>,
    host: State<'_, Arc<LaptopHost>>,
) -> Result<Purchase, String> {
    let outcome = core.habits.update(|ledger| {
        let now = now_ms();
        ledger.buy_time_off(&request_id, minutes, now, local_clock(now).0)
    })?;
    if outcome == Purchase::Bought {
        let _ = app.emit("habits-updated", ());
        let _ = app.emit("wallet-updated", ());
        std::thread::spawn({
            let (core, devices, tracker, host) = (
                core.inner().clone(),
                devices.inner().clone(),
                tracker.inner().clone(),
                host.inner().clone(),
            );
            move || {
                if let Ok(manager) = &devices.0 {
                    for peer in manager.list() {
                        let _ = sync_habits(manager, &peer, &core, &tracker, host.server());
                    }
                    let _ = app.emit("wallet-updated", ());
                }
            }
        });
    }
    Ok(outcome)
}

#[derive(Deserialize)]
#[serde(tag = "action", rename_all = "snake_case")]
pub enum Change {
    Save {
        id: Option<String>,
        name: String,
        interval: i32,
        weekdays: u8,
        time: Option<u16>,
        reward: u16,
    },
    Remove {
        id: String,
    },
    Complete {
        id: String,
    },
    Undo {
        id: String,
    },
    Cap {
        minutes: u16,
    },
}

#[tauri::command]
pub fn habits_change(
    input: Change,
    app: AppHandle,
    core: State<'_, Arc<Core>>,
    devices: State<'_, Arc<Devices>>,
    tracker: State<'_, Arc<Tracker>>,
    host: State<'_, Arc<LaptopHost>>,
) -> Result<Value, String> {
    let now = now_ms();
    let (day, _) = local_clock(now);
    let away = if matches!(&input, Change::Complete { .. }) {
        let peers = devices.0.as_ref().map(|m| m.list()).unwrap_or_default();
        let mut screens = known_screens(&core, &peers);
        if let Some(server) = host.server() {
            screens.remove(&server.device_id());
        }
        screens.remove("windows-laptop");
        away_minutes(
            now,
            &live_screen(&tracker, now),
            &screens.into_values().collect::<Vec<_>>(),
        )
    } else {
        None
    };
    let event = core.habits.update(|ledger| {
        match input {
            Change::Save {
                id: existing,
                name,
                interval,
                weekdays,
                time,
                reward,
            } => {
                let prior = existing
                    .as_ref()
                    .and_then(|id| ledger.definitions.get(id))
                    .filter(|h| !h.deleted);
                if existing.is_some() && prior.is_none() {
                    return Err("Habit was removed".into());
                }
                let mut habit = Habit {
                    id: existing.map_or_else(id, Ok)?,
                    rev: revision(prior.map(|h| &h.rev), now)?,
                    name: name.trim().to_owned(),
                    interval,
                    weekdays,
                    time,
                    reward,
                    start: prior.map_or(day, |h| h.start),
                    deleted: false,
                    schedules: Vec::new(),
                };
                if let Some(prior) = prior {
                    let already_done = ledger
                        .status(day, 0)
                        .iter()
                        .any(|row| row.habit.id == prior.id && row.completion.is_some());
                    habit.keep_schedule_history(prior, day + i32::from(already_done));
                }
                if !habit.valid() {
                    return Err("Invalid habit".into());
                }
                ledger.definitions.insert(habit.id.clone(), habit);
            }
            Change::Remove { id } => {
                if let Some(habit) = ledger.definitions.get_mut(&id) {
                    habit.rev = revision(Some(&habit.rev), now)?;
                    habit.deleted = true;
                }
            }
            Change::Complete { id: habit } => return Ok(ledger.complete(&habit, id()?, now, away)),
            Change::Undo { id: target } => ledger.undo(&target, id()?, now),
            Change::Cap { minutes } => {
                if minutes > 60 {
                    return Err("Invalid credit cap".into());
                }
                let current = ledger.cap_on(day);
                ledger.put_cap(CreditCap {
                    rev: revision(ledger.cap.as_ref().map(|c| &c.rev), now)?,
                    minutes,
                    previous: current,
                    effective: if minutes > current { day + 1 } else { day },
                });
            }
        }
        Ok(None)
    })?;
    let state = view(&core.habits.read(), now);
    let _ = app.emit("habits", &state);
    let _ = app.emit("wallet-updated", ());
    let (core, devices, tracker, host) = (
        core.inner().clone(),
        devices.inner().clone(),
        tracker.inner().clone(),
        host.inner().clone(),
    );
    std::thread::spawn(move || {
        if let Ok(manager) = &devices.0 {
            for peer in manager.list() {
                let _ = sync_habits(manager, &peer, &core, &tracker, host.server());
            }
            let _ = app.emit("habits", view(&core.habits.read(), now_ms()));
            let _ = app.emit("wallet-updated", ());
            let _ = app.emit("devices", manager.list());
        }
    });
    Ok(json!({ "state": state, "event": event }))
}
