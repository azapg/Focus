//! Gated native desktop authority. No public setting turns this trial on.
use std::{collections::BTreeSet, fs, sync::Arc};

use chrono::{Days, Local, NaiveDate, TimeZone};
use focus_devices::{DeviceManager, DeviceView, LaptopServer};
use focus_dns::{
    activity::Tracker,
    clean_streak::{FinalizeError, FreezePurchase, TRIAL_FREEZE_COINS, TRIAL_SYNC_GRACE_MS},
    focus_extract::FocusExtractor,
    habits::focus_rewards::FocusRewardRate,
    pause_history::read_pauses,
    streak_journal::{self, DayEvidence, Profile, StreakView},
};
use serde::Deserialize;
use tauri::{AppHandle, Emitter, State};

use crate::{device_commands::LaptopHost, now_ms, Core};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Trial {
    reporters: BTreeSet<String>,
}

fn trial(core: &Core) -> Result<Option<Trial>, String> {
    let raw = match fs::read(core.dir.join("streak-trial.json")) {
        Ok(raw) if raw.len() <= 4096 => raw,
        Ok(_) => return Err("Streak trial config is too large".into()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.to_string()),
    };
    let config: Trial =
        serde_json::from_slice(&raw).map_err(|_| "Invalid streak trial config".to_string())?;
    if config.reporters.len() > 8
        || config
            .reporters
            .iter()
            .any(|id| id.is_empty() || id.len() > 100)
    {
        return Err("Invalid streak trial reporters".into());
    }
    Ok(Some(config))
}

fn epoch() -> NaiveDate {
    NaiveDate::from_ymd_opt(1970, 1, 1).expect("valid epoch")
}

fn date(day: i32) -> Result<NaiveDate, String> {
    epoch()
        .checked_add_signed(chrono::Duration::days(i64::from(day)))
        .ok_or_else(|| "Invalid streak date".into())
}

fn today(now: u64) -> Result<(NaiveDate, i32), String> {
    let local = Local
        .timestamp_millis_opt(i64::try_from(now).map_err(|_| "Invalid clock")?)
        .single()
        .ok_or("Invalid clock")?
        .date_naive();
    let day = i32::try_from((local - epoch()).num_days()).map_err(|_| "Invalid profile day")?;
    Ok((local, day))
}

fn trial_profile(core: &Core, authority: &str, config: Trial) -> Result<Option<Profile>, String> {
    let Some((profile, _, _)) = streak_journal::authority_cursor(&core.habits) else {
        return Ok(None);
    };
    let expected: BTreeSet<_> = config
        .reporters
        .into_iter()
        .chain([authority.to_owned()])
        .collect();
    let timezone =
        iana_time_zone::get_timezone().map_err(|_| "Could not resolve profile timezone")?;
    if profile.authority != authority || profile.devices != expected || profile.timezone != timezone
    {
        return Err("Streak trial authority, timezone, or reporter enrollment changed".into());
    }
    Ok(Some(profile))
}

fn opted_in(peer: &DeviceView) -> bool {
    peer.kind == "phone"
        && peer.online
        && peer
            .snapshot
            .as_ref()
            .is_some_and(|snapshot| snapshot["streak_coverage"]["version"] == 1)
}

pub fn tick(
    core: &Core,
    tracker: &Tracker,
    server: &LaptopServer,
    manager: &DeviceManager,
) -> Result<bool, String> {
    if core.dry {
        return Ok(false);
    }
    let Some(config) = trial(core)? else {
        tracker.disable_coverage();
        return Ok(false);
    };
    let authority = server.device_id();
    if config.reporters.contains(&authority) {
        return Err("Streak reporter list must contain phone IDs only".into());
    }
    let peers = manager.list();
    let now = now_ms();
    let (today_date, today_day) = today(now)?;
    if streak_journal::authority_cursor(&core.habits).is_none() {
        if !config
            .reporters
            .iter()
            .all(|id| peers.iter().any(|peer| &peer.id == id && opted_in(peer)))
        {
            return Ok(false);
        }
        let timezone =
            iana_time_zone::get_timezone().map_err(|_| "Could not resolve profile timezone")?;
        let tomorrow = today_date
            .checked_add_days(Days::new(1))
            .ok_or("Invalid profile day")?;
        streak_journal::configure(
            &core.habits,
            &authority,
            Profile {
                authority: authority.clone(),
                timezone,
                devices: config
                    .reporters
                    .into_iter()
                    .chain([authority.clone()])
                    .collect(),
                sync_grace_ms: TRIAL_SYNC_GRACE_MS,
                price_coins: TRIAL_FREEZE_COINS,
            },
            today_day + 1,
            streak_journal::local_day_start(&Local, tomorrow)?,
        )?;
        tracker.enable_coverage();
        return Ok(true);
    }
    let Some(profile) = trial_profile(core, &authority, config)? else {
        return Ok(false);
    };
    if !profile
        .devices
        .iter()
        .filter(|id| **id != authority)
        .all(|id| {
            peers
                .iter()
                .any(|peer| &peer.id == id && peer.kind == "phone")
        })
    {
        return Err("An enrolled phone is no longer paired".into());
    }

    tracker.enable_coverage();
    let wallet_scheduled = streak_journal::schedule_enrolled_wallet(&core.habits, today_day)?;
    let Some((_, day, start_ms)) = streak_journal::authority_cursor(&core.habits) else {
        return Ok(false);
    };
    if day >= today_day {
        return Ok(wallet_scheduled);
    }
    let end_ms = streak_journal::local_day_start(&Local, date(day + 1)?)?;
    let snapshot = core.laptop_pause_snapshot()?;
    let pauses = read_pauses(&snapshot.history, snapshot.active_pause, now);
    let mut extractor = FocusExtractor::from_data_dir(&core.dir)?;
    let mut reports = vec![streak_journal::desktop_evidence(
        &authority,
        tracker,
        &mut extractor,
        &pauses,
        start_ms,
        end_ms,
        now,
    )?];
    for peer in peers
        .iter()
        .filter(|peer| profile.devices.contains(&peer.id))
    {
        if let Some(snapshot) = peer.snapshot.as_ref() {
            // A bad or unsupported report supplies no coverage. It must not
            // prevent the authority from closing the day after the grace window.
            if let Ok(Some(report)) = streak_journal::paired_phone_evidence(
                &peer.id,
                snapshot,
                &mut extractor,
                start_ms,
                end_ms,
                peer.last_seen,
            ) {
                reports.push(report);
            }
        }
    }
    let mut tv_drift = false;
    for peer in &peers {
        if peer.kind == "tv" {
            if let Some(snapshot) = peer.snapshot.as_ref() {
                if let Ok(true) =
                    streak_journal::paired_tv_drift(snapshot, &mut extractor, start_ms, end_ms)
                {
                    tv_drift = true;
                }
            }
        }
    }
    if tv_drift {
        if let Some(desktop) = reports.first_mut() {
            desktop.confirmed_drift = true;
        }
    }
    let outcome = streak_journal::finalize_with_focus_rewards(
        &core.habits,
        &authority,
        &DayEvidence {
            timezone: profile.timezone,
            day,
            start_ms,
            end_ms,
            devices: reports,
        },
        now,
        &FocusRewardRate::default(),
    )?;
    match outcome {
        Ok(_) => Ok(true),
        Err(FinalizeError::Pending | FinalizeError::OutOfOrder) => Ok(wallet_scheduled),
        Err(FinalizeError::InvalidEvidence) => Err("Invalid streak day evidence".into()),
    }
}

#[tauri::command]
pub fn streak_view(
    core: State<'_, Arc<Core>>,
    host: State<'_, Arc<LaptopHost>>,
) -> Option<StreakView> {
    if core.dry {
        return None;
    }
    let server = host.server()?;
    trial_profile(&core, &server.device_id(), trial(&core).ok()??).ok()??;
    let (_, day) = today(now_ms()).ok()?;
    streak_journal::streak_view(&core.habits, &server.device_id(), day)
}

#[tauri::command]
pub fn streak_buy_freeze(
    app: AppHandle,
    core: State<'_, Arc<Core>>,
    host: State<'_, Arc<LaptopHost>>,
    request_id: String,
) -> Result<FreezePurchase, String> {
    let (Some(server), Some(config)) = (host.server(), trial(&core)?) else {
        return Ok(FreezePurchase::Unavailable);
    };
    if core.dry || trial_profile(&core, &server.device_id(), config)?.is_none() {
        return Ok(FreezePurchase::Unavailable);
    }
    let now = now_ms();
    let (_, day) = today(now)?;
    let result = streak_journal::streak_buy_freeze(
        &core.habits,
        &server.device_id(),
        &request_id,
        day,
        now,
    )?;
    if result == FreezePurchase::Bought {
        let _ = app.emit("wallet-updated", ());
    }
    Ok(result)
}

/// Only the paired transport may supply the authenticated reporter and authority IDs.
pub fn remote(
    app: &AppHandle,
    core: &Core,
    input: &serde_json::Value,
) -> Result<serde_json::Value, String> {
    let reporter = input["_streak_peer"]
        .as_str()
        .ok_or("An enrolled phone is required")?;
    let authority = input["_streak_authority"]
        .as_str()
        .ok_or("A streak authority is required")?;
    let Some(config) = trial(core)? else {
        return Err("Streak trial is unavailable".into());
    };
    if core.dry
        || !config.reporters.contains(reporter)
        || trial_profile(core, authority, config)?.is_none()
    {
        return Err("Streak trial is unavailable for this device".into());
    }
    let now = now_ms();
    let (_, day) = today(now)?;
    if input["action"] == "streak_view" {
        return Ok(
            serde_json::json!({"streak": streak_journal::streak_view(&core.habits, authority, day)}),
        );
    }
    let request = input["request_id"]
        .as_str()
        .ok_or("Missing freeze request ID")?;
    let result = streak_journal::streak_buy_freeze(&core.habits, authority, request, day, now)?;
    if result == FreezePurchase::Bought {
        let _ = app.emit("wallet-updated", ());
    }
    Ok(serde_json::json!(result))
}
