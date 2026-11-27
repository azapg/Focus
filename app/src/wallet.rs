use crate::{
    device_commands::{sync_habits, Devices, LaptopHost},
    now_ms, Core, Tracker,
};
use focus_dns::habits::{local_clock, Purchase, TIME_OFF_PRICE_PER_MINUTE, WALLET_SYNC_VERSION};
use serde_json::{json, Value};
use std::sync::Arc;
use tauri::{AppHandle, Emitter, State};

#[tauri::command]
pub fn wallet_view(core: State<'_, Arc<Core>>, devices: State<'_, Arc<Devices>>) -> Value {
    let day = local_clock(now_ms()).0;
    let ledger = core.habits.read();
    let peers = devices
        .0
        .as_ref()
        .map(|manager| manager.list())
        .unwrap_or_default();
    wallet_value(&ledger, day, &peers)
}

fn wallet_value(
    ledger: &focus_dns::habits::Ledger,
    day: i32,
    peers: &[focus_devices::DeviceView],
) -> Value {
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
        "time_off_limit": ledger.time_off_limit(day),
        "time_off_price_per_minute": TIME_OFF_PRICE_PER_MINUTE,
        "focus_coins_today": focus_dns::habits::focus_rewards::focus_coins_for_day(&ledger, day),
        "focus_coins_week": focus_dns::habits::focus_rewards::focus_coins_in_range(&ledger, day - 6, day),
        "needs_upgrade": needs_upgrade
    })
}

#[tauri::command]
pub fn wallet_buy_time(
    request_id: String,
    minutes: u16,
    expected_day: Option<i32>,
    target_device: Option<String>,
    app: AppHandle,
    core: State<'_, Arc<Core>>,
    devices: State<'_, Arc<Devices>>,
    tracker: State<'_, Arc<Tracker>>,
    host: State<'_, Arc<LaptopHost>>,
) -> Result<Purchase, String> {
    let outcome = core.habits.update(|ledger| {
        let now = now_ms();
        let day = local_clock(now).0;
        ledger.buy_time_off_for_day(&request_id, minutes, now, day, expected_day)
    })?;
    if outcome == Purchase::Bought {
        let target = target_device.as_deref().unwrap_or("laptop").to_owned();
        if target == "laptop" {
            if let Err(err) = core.start_free_time(u64::from(minutes), &request_id) {
                core.log(&format!("Could not start local free time pass: {err}"));
            } else {
                core.publish(&app);
            }
        }
        let _ = app.emit("habits-updated", ());
        let _ = app.emit("wallet-updated", ());
        std::thread::spawn({
            let (core, devices, tracker, host, app) = (
                core.inner().clone(),
                devices.inner().clone(),
                tracker.inner().clone(),
                host.inner().clone(),
                app.clone(),
            );
            let req_id = request_id.clone();
            move || {
                if let Ok(manager) = &devices.0 {
                    if target != "laptop" {
                        let cmd = json!({
                            "action": "start_free_time",
                            "minutes": minutes,
                            "request_id": req_id,
                        });
                        if let Err(err) = manager.command(&target, cmd) {
                            core.log(&format!(
                                "Could not start free time on peer {target}: {err}"
                            ));
                        }
                    }
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn test_profile(name: &str) -> PathBuf {
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        std::env::temp_dir().join(format!(
            "focus-wallet-profile-{name}-{}-{}-{}",
            std::process::id(),
            now_ms(),
            NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ))
    }

    fn wallet_view(core: &Core) -> Value {
        let day = local_clock(now_ms()).0;
        wallet_value(&core.habits.read(), day, &[])
    }

    #[test]
    fn fresh_profile_open_activates_empty_store_and_persists_it() {
        let dir = test_profile("fresh");
        let first = Core::open_profile(dir.clone(), true);
        let today = local_clock(now_ms()).0;
        let view = wallet_view(&first);
        assert_eq!(view["active"], true);
        assert_eq!(view["balance"], 0);
        assert!(first.habits.read().wallet_active(today));
        assert!(focus_dns::streak_journal::authority_cursor(&first.habits).is_none());
        drop(first);

        let reopened = Core::open_profile(dir.clone(), true);
        let view = wallet_view(&reopened);
        assert_eq!(view["active"], true);
        assert_eq!(view["balance"], 0);
        assert!(focus_dns::streak_journal::authority_cursor(&reopened.habits).is_none());
        drop(reopened);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn existing_profile_reopen_preserves_wallet_balance() {
        let dir = test_profile("upgrade");
        std::fs::create_dir_all(&dir).unwrap();
        let day = local_clock(now_ms()).0;
        focus_dns::habits::Store::new(&dir)
            .update(|ledger| {
                ledger.wallet_start = Some(day);
                ledger.earn("legacy-grant", "focus", 12, "existing", 1, day)?;
                Ok(())
            })
            .unwrap();

        let first = Core::open_profile(dir.clone(), true);
        let view = wallet_view(&first);
        assert_eq!(view["active"], true);
        assert_eq!(view["balance"], 12);
        drop(first);

        let reopened = Core::open_profile(dir.clone(), true);
        let view = wallet_view(&reopened);
        assert_eq!(view["active"], true);
        assert_eq!(view["balance"], 12);
        drop(reopened);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
