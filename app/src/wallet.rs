use crate::{
    device_commands::{sync_habits, Devices, LaptopHost},
    now_ms, Core, Tracker,
};
use focus_dns::habits::{local_clock, Purchase, TIME_OFF_PRICE_PER_MINUTE, WALLET_SYNC_VERSION};
use serde_json::{json, Value};
use std::sync::Arc;
use tauri::{AppHandle, Emitter, State};

/// Activate Store on first desktop launch. Clean Streak has its own explicit enrollment path.
pub fn activate_at_startup(core: &Core, today: i32) -> Result<bool, String> {
    core.habits.activate_wallet(today)
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

    #[test]
    fn production_startup_is_wired_to_store_activation() {
        let startup = include_str!("main.rs");
        assert!(startup.contains("wallet::activate_at_startup(&setup_core, today)"));
    }

    #[test]
    fn startup_keeps_an_existing_wallet_balance() {
        let fixture = crate::dns_tests::Fixture::new(|_| panic!("unexpected DNS operation"));
        let day = 20_496;
        fixture
            .core
            .habits
            .update(|ledger| {
                ledger.earn("legacy-grant", "focus", 12, "existing", 1, day)?;
                Ok(())
            })
            .unwrap();

        assert_eq!(activate_at_startup(&fixture.core, day), Ok(true));
        assert!(fixture.core.habits.read().wallet_active(day));
        assert_eq!(activate_at_startup(&fixture.core, day), Ok(false));
        assert_eq!(fixture.core.habits.read().balance(), 12);
    }
}
