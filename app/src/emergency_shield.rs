//! Settings-only Emergency Shield gated behind a rotating accountability phrase.
//! Never exposed in Ask or on TV.
//!
//! Online: temporarily allowlists one non-entertainment domain confirmed in recent
//! NextDNS block logs (DNS stays on for feeds and video).
//! Offline: pauses local DNS for 10 minutes while signaling the browser extension
//! to keep entertainment domains blocked locally (`emergency_shield: true`), capped
//! at once per 24 hours.

use crate::{judge, nextdns::BlockedQuery, now_ms, pause_judge, Core};
use ring::rand::{SecureRandom, SystemRandom};
use serde::{Deserialize, Serialize};
use std::{fs, io::ErrorKind, path::Path, sync::Arc};
use tauri::{AppHandle, State};

const FILE: &str = "emergency-shield.json";
const EMERGENCY_MINUTES: u64 = 10;
const OFFLINE_COOLDOWN_MS: u64 = 24 * 60 * 60 * 1000;
const ONLINE_DAILY_LIMIT: u32 = 3;

pub const ACCOUNTABILITY_PHRASES: &[&str] = &[
    "I know I have to be focused to achieve my goals",
    "This bypass is for a real block, not for a feed",
    "My attention is worth more than a quick scroll",
    "I build things that matter when I stay on task",
    "Short distractions quietly steal whole afternoons",
    "I will fix this block and get straight back to work",
    "Free time belongs in the Store, not in emergencies",
    "Protecting my focus today makes tomorrow easier",
    "I do not need a feed to handle a blocked tool",
    "Staying deliberate is how hard projects get shipped",
    "One honest minute beats ten minutes of drifting",
    "I am opening this only to unblock real work",
    "My future self depends on what I finish today",
    "Deep work is my real advantage and I protect it",
    "If this is just boredom, I should close this screen",
    "Real progress comes from finishing what I started",
    "I will not trade a strong day for a cheap impulse",
    "Unblock the tool, ignore the noise, keep moving",
    "Clarity comes from doing the work in front of me",
    "I choose deliberate breaks over sneaky workarounds",
    "Every clean hour compounds into something real",
    "This shield keeps distractions out while I fix a block",
    "Small compromises turn into lost days if I let them",
    "Focus first, earn free time cleanly after the work",
];

#[derive(Default, Deserialize, Serialize)]
#[serde(default)]
struct Config {
    current_phrase_idx: Option<usize>,
    recent_indices: Vec<usize>,
    last_offline_at: u64,
    online_day: i32,
    online_count: u32,
}

#[derive(Serialize)]
pub struct EmergencyStatus {
    pub phrase: String,
    pub active_until: u64,
    pub offline_cooldown_until: u64,
    pub online_remaining_today: u32,
}

#[derive(Serialize)]
#[serde(tag = "mode", rename_all = "snake_case")]
pub enum EmergencyActivated {
    ScopedDomain { domain: String, until: u64 },
    OfflineShield { until: u64 },
}

fn random_index(exclude: &[usize]) -> usize {
    let mut bytes = [0u8; 4];
    let _ = SystemRandom::new().fill(&mut bytes);
    let seed = u32::from_le_bytes(bytes) as usize;
    let total = ACCOUNTABILITY_PHRASES.len();
    let eligible: Vec<usize> = (0..total).filter(|i| !exclude.contains(i)).collect();
    if eligible.is_empty() {
        seed % total
    } else {
        eligible[seed % eligible.len()]
    }
}

fn normalize_phrase(text: &str) -> String {
    text.split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_ascii_lowercase()
}

impl Config {
    fn load(dir: &Path) -> Result<Self, String> {
        let mut cfg = match fs::read(dir.join(FILE)) {
            Ok(bytes) => serde_json::from_slice::<Self>(&bytes)
                .map_err(|_| "Invalid emergency shield settings".to_string())?,
            Err(e) if e.kind() == ErrorKind::NotFound => Self::default(),
            Err(_) => return Err("Could not read emergency shield settings".into()),
        };
        let today = focus_dns::habits::local_clock(now_ms()).0;
        if cfg.online_day != today {
            cfg.online_day = today;
            cfg.online_count = 0;
        }
        if cfg
            .current_phrase_idx
            .is_none_or(|idx| idx >= ACCOUNTABILITY_PHRASES.len())
        {
            cfg.rotate_phrase();
            let _ = cfg.save(dir);
        }
        Ok(cfg)
    }

    fn save(&self, dir: &Path) -> Result<(), String> {
        let bytes = serde_json::to_vec_pretty(self).map_err(|e| e.to_string())?;
        focus_browser_host::durable_atomic_write(&dir.join(FILE), &bytes)
            .map_err(|e| format!("Could not save emergency shield settings: {e}"))
    }

    fn current_phrase(&self) -> &'static str {
        let idx = self
            .current_phrase_idx
            .filter(|&i| i < ACCOUNTABILITY_PHRASES.len())
            .unwrap_or(0);
        ACCOUNTABILITY_PHRASES[idx]
    }

    fn rotate_phrase(&mut self) {
        let next = random_index(&self.recent_indices);
        self.current_phrase_idx = Some(next);
        self.recent_indices.push(next);
        while self.recent_indices.len() > 8 {
            self.recent_indices.remove(0);
        }
    }

    fn verify_phrase(&self, typed: &str) -> bool {
        normalize_phrase(typed) == normalize_phrase(self.current_phrase())
    }
}

#[tauri::command(async)]
pub fn emergency_status(core: State<Arc<Core>>) -> Result<EmergencyStatus, String> {
    let cfg = Config::load(&core.dir)?;
    let now = now_ms();
    let saved = core.saved.lock().unwrap();
    let active_until = if saved.emergency_shield && saved.paused_until > now {
        saved.paused_until
    } else if saved.scoped_pause.as_ref().is_some_and(|s| s.until > now) {
        saved.scoped_pause.as_ref().map_or(0, |s| s.until)
    } else {
        0
    };
    let offline_cooldown_until = if cfg.last_offline_at > 0 {
        let end = cfg.last_offline_at.saturating_add(OFFLINE_COOLDOWN_MS);
        if end > now { end } else { 0 }
    } else {
        0
    };
    Ok(EmergencyStatus {
        phrase: cfg.current_phrase().to_owned(),
        active_until,
        offline_cooldown_until,
        online_remaining_today: ONLINE_DAILY_LIMIT.saturating_sub(cfg.online_count),
    })
}

#[tauri::command]
pub async fn emergency_recent_blocks(
    core: State<'_, Arc<Core>>,
) -> Result<Vec<BlockedQuery>, String> {
    let core = core.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        if core.dry {
            return Ok(Vec::new());
        }
        let api = pause_judge::load_nextdns_api(&core, None)?;
        let blocks = api.recent_blocks(5)?;
        Ok(blocks
            .into_iter()
            .filter(|q| !q.is_security_threat && !judge::is_entertainment_domain(&q.domain))
            .take(8)
            .collect())
    })
    .await
    .map_err(|_| "Could not check recent NextDNS blocks".to_string())?
}

#[tauri::command]
pub async fn emergency_activate(
    phrase: String,
    domain: Option<String>,
    reason: String,
    app: AppHandle,
    core: State<'_, Arc<Core>>,
) -> Result<EmergencyActivated, String> {
    let core = core.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let mut cfg = Config::load(&core.dir)?;
        if !cfg.verify_phrase(&phrase) {
            return Err("Type the accountability phrase exactly as shown".into());
        }
        let reason = reason.trim().to_owned();
        if reason.chars().count() < 5 {
            return Err("Describe what is broken (at least 5 characters)".into());
        }
        let now = now_ms();
        let requested_domain = domain
            .as_deref()
            .map(|d| d.trim().trim_end_matches('.').to_ascii_lowercase())
            .filter(|d| !d.is_empty());

        if let Some(host) = requested_domain {
            if !judge::valid_domain(&host) {
                return Err("Enter a valid domain name (for example, portal.example.com)".into());
            }
            if judge::is_entertainment_domain(&host) {
                return Err("Entertainment domains cannot be opened through Emergency Shield".into());
            }
            if cfg.online_count >= ONLINE_DAILY_LIMIT {
                return Err("Daily Emergency Shield limit reached (3 per day)".into());
            }
            let mut saved = core.saved.lock().unwrap();
            let until = pause_judge::start_emergency_domain_scope(
                &core,
                &mut saved,
                host.clone(),
                EMERGENCY_MINUTES,
                format!("[Emergency Shield] {reason}"),
            )?;
            cfg.online_count += 1;
            cfg.rotate_phrase();
            cfg.save(&core.dir)?;
            drop(saved);
            core.publish(&app);
            return Ok(EmergencyActivated::ScopedDomain {
                domain: host,
                until,
            });
        }

        // Offline / full local shield fallback: DNS off for 10m while the browser extension
        // enforces the local distraction shield.
        if cfg.last_offline_at > 0 && now < cfg.last_offline_at.saturating_add(OFFLINE_COOLDOWN_MS)
        {
            return Err("Offline Emergency Shield is on a 24-hour cooldown".into());
        }
        let mut saved = core.saved.lock().unwrap();
        let extra = serde_json::json!({
            "emergency_shield": true,
            "kind": "emergency_shield",
        });
        core.start_pause_locked(
            &mut saved,
            EMERGENCY_MINUTES,
            format!("[Emergency Shield] {reason}"),
            None,
            Some(extra),
        )?;
        saved.emergency_shield = true;
        let until = saved.paused_until;
        core.save(&saved);
        drop(saved);
        cfg.last_offline_at = now;
        cfg.rotate_phrase();
        cfg.save(&core.dir)?;
        core.publish(&app);
        Ok(EmergencyActivated::OfflineShield { until })
    })
    .await
    .map_err(|_| "Emergency Shield task stopped".to_string())?
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rotating_phrase_verifies_and_avoids_immediate_repeats() {
        let mut cfg = Config::default();
        cfg.rotate_phrase();
        let first = cfg.current_phrase();
        assert!(cfg.verify_phrase(first));
        assert!(cfg.verify_phrase(&format!("  {}  ", first.to_lowercase())));
        assert!(!cfg.verify_phrase("wrong phrase"));
        cfg.rotate_phrase();
        assert_ne!(cfg.current_phrase(), first);
    }
}
