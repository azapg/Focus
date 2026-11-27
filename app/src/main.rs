#![cfg(windows)]
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

//! FocusDNS: a tiny always-on-top pill that keeps NextDNS on.
//! Pauses expire automatically. The judge can grant scoped access while DNS stays on.
//! It also keeps screen time (see activity.rs) for the stats panel under the pill.
//! Everything that happens is written to %LOCALAPPDATA%\FocusDNS\focusdns.log.

mod device_commands;
mod emergency_shield;
mod habit_commands;
mod intention_commands;
mod streak_runtime;
mod focus_state_runtime;
mod judge;
mod judge_context;
mod nextdns;
mod overlay;
mod pause_judge;
mod peek_panel;
mod resource_telemetry;
mod updater;
mod wallet_runtime;
mod wallet;
#[cfg(test)]
mod dns_tests;
#[cfg(test)]
mod mock_http;

use focus_telemetry as telemetry;
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::PathBuf,
    process::Command,
    sync::{atomic::{AtomicBool, AtomicU64, Ordering}, Arc, Mutex, OnceLock},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use activity::{Span, Tracker};
use focus_dns::{activity, activity_rules, pause_history};
use pause_history::{read_pauses, CheckIn, PauseRec, RATING_NOTE_LIMIT, RATING_WINDOW_MS};
use tauri::{AppHandle, Emitter, Manager, State, WebviewWindow};

#[cfg(not(test))]
const DNS_SCRIPT: &str = include_str!("../../scripts/dns.ps1");
const ALLOWED_MINUTES: [u64; 4] = [1, 5, 10, 30];
const NO_WINDOW: u32 = 0x0800_0000;
const APP_IDENTIFIER: &str = "org.nightingalelab.focusdns";
const SHELL_WAIT_TIMEOUT: Duration = Duration::from_secs(60);
const SHELL_WAIT_POLL: Duration = Duration::from_millis(250);
const OVERLAY_HEALTH_INTERVAL: Duration = Duration::from_secs(5);

/// PowerShell -EncodedCommand wants base64 of UTF-16LE.
#[cfg(not(test))]
fn encode_ps(script: &str) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let bytes: Vec<u8> = script.encode_utf16().flat_map(|u| u.to_le_bytes()).collect();
    let mut out = String::with_capacity(bytes.len() * 4 / 3 + 4);
    for c in bytes.chunks(3) {
        let n = (c[0] as u32) << 16 | (*c.get(1).unwrap_or(&0) as u32) << 8 | *c.get(2).unwrap_or(&0) as u32;
        for i in 0..4 {
            out.push(if i <= c.len() { T[(n >> (18 - 6 * i) & 63) as usize] as char } else { '=' });
        }
    }
    out
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as u64
}

#[derive(Default, Serialize, Deserialize, Clone)]
struct Saved {
    paused_at: u64,
    paused_until: u64,
    reason: String,
    #[serde(default)]
    profile: Option<String>,
    #[serde(default)]
    pause_id: Option<u64>,
    #[serde(default)]
    pause_ended_at: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    scoped_pause: Option<nextdns::Lease>,
    #[serde(default)]
    scope_revision: u64,
    #[serde(default, skip_serializing_if = "std::collections::BTreeMap::is_empty")]
    pending_grants: std::collections::BTreeMap<String, nextdns::PendingGrant>,
    #[serde(default)]
    rating_prompt: Option<RatingPrompt>,
    #[serde(default)]
    free_time: bool,
    #[serde(default)]
    emergency_shield: bool,
}

#[derive(Clone, Serialize, Deserialize)]
struct RatingPrompt {
    paused_at: u64,
    #[serde(default)]
    pause_id: Option<u64>,
    ended_at: u64,
    expires_at: u64,
}

#[derive(Serialize, Clone)]
struct View {
    paused_at: u64,
    paused_until: u64,
    now: u64,
    dry_run: bool,
    dns_configured: bool,
    scoped_until: Option<u64>,
    scoped_services: Vec<String>,
    rating_prompt: Option<RatingPrompt>,
    free_time: bool,
    emergency_shield: bool,
}

struct LaptopPauseSnapshot {
    now: u64,
    revision: u64,
    active_pause: Option<(u64, Option<u64>)>,
    paused_until: u64,
    scoped_until: u64,
    scoped_grant: Option<nextdns::GrantTimer>,
    scoped_services: Vec<String>,
    history: String,
    desktop_limit: focus_dns::habits::DesktopLimit,
}

struct Core {
    dir: PathBuf,
    habits: focus_dns::habits::Store,
    intentions: focus_dns::intentions::Store,
    dry: bool,
    dns_config: OnceLock<focus_browser_host::NextDnsConfig>,
    // Hold this lock from the pause decision through DNS changes and persistence.
    // Lock order is saved -> dns_lock; DNS probes never acquire saved.
    saved: Mutex<Saved>,
    state_write_pending: AtomicBool,
    judge_tickets: pause_judge::Tickets,
    history_write: Mutex<()>,
    event_sequence: Mutex<Option<u64>>,
    hit: Mutex<[f64; 4]>,
    dns_lock: Mutex<()>,
    dns_sequence: AtomicU64,
    #[cfg(test)]
    dns_override: Box<dyn Fn(&str) -> Result<String, String> + Send + Sync>,
    /// The window you were in before clicking a time button, so focus can go back to it.
    prev_fg: Mutex<isize>,
}

impl Core {
    /// Open a Focus profile exactly as desktop startup does, including Store activation.
    fn open_profile(dir: PathBuf, dry: bool, opened_at: u64) -> Self {
        let _ = fs::create_dir_all(&dir);
        let core = Self {
            habits: focus_dns::habits::Store::new(&dir),
            intentions: focus_dns::intentions::Store::open(&dir),
            dir,
            dns_config: OnceLock::new(),
            dry,
            saved: Mutex::new(Saved::default()),
            state_write_pending: AtomicBool::new(false),
            hit: Mutex::new([0.0; 4]),
            judge_tickets: pause_judge::Tickets::default(),
            history_write: Mutex::new(()),
            event_sequence: Mutex::new(None),
            dns_lock: Mutex::new(()),
            dns_sequence: AtomicU64::new(1),
            prev_fg: Mutex::new(0),
            #[cfg(test)]
            dns_override: Box::new(|_| panic!("tests must supply a DNS fake")),
        };
        let today = focus_dns::habits::local_clock(opened_at).0;
        match core.habits.activate_wallet(today) {
            Ok(true) => core.log("Store wallet activated"),
            Ok(false) => {}
            Err(error) => core.log(&format!("Could not activate Store wallet: {error}")),
        }
        core
    }

    fn event_log(&self) -> focus_devices::events::EventLog {
        focus_devices::events::EventLog::new(&self.dir, env!("FOCUS_BUILD_ID"))
    }

    fn event(&self, event: &str, stage: &str, outcome: &str, cause: &str, duration_ms: u64, fields: serde_json::Value) {
        self.event_log().record(event, stage, outcome, cause, duration_ms, fields);
    }


    // Pause IDs and LAN snapshot revisions share a sequence that survives clock changes and restarts.
    fn next_event_sequence(&self, now: u64) -> Result<u64, String> {
        let path = self.dir.join("lan-snapshot-revision");
        let mut previous = self.event_sequence.lock().unwrap();
        let last = previous.unwrap_or_else(|| {
            fs::read_to_string(&path).ok().and_then(|text| text.trim().parse::<u64>().ok()).unwrap_or(0)
        });
        let revision = now.max(last.saturating_add(1));
        focus_browser_host::atomic_write(&path, revision.to_string().as_bytes())
            .map_err(|e| format!("Could not save event sequence: {e}"))?;
        *previous = Some(revision);
        Ok(revision)
    }

    fn laptop_pause_snapshot(&self) -> Result<LaptopPauseSnapshot, String> {
        let saved = self.saved.lock().unwrap();
        let history = fs::read_to_string(self.dir.join("pauses.jsonl")).unwrap_or_default();
        let now = now_ms();
        // Sample the settings while snapshots are still serialized by the saved-state lock.
        let desktop_limit = self.habits.desktop_limit();
        // Access can still be open if an early End is waiting for NextDNS cleanup.
        let scope = saved.scoped_pause.as_ref().filter(|lease| lease.started.is_some());
        Ok(LaptopPauseSnapshot {
            now,
            revision: self.next_event_sequence(now)?,
            active_pause: (saved.paused_at != 0).then_some((saved.paused_at, saved.pause_id)),
            paused_until: saved.paused_until,
            scoped_until: scope.map_or(0, |lease| lease.until),
            scoped_grant: scope.and_then(|lease| lease.timer.clone()),
            scoped_services: scope.map(|lease| lease.services.clone()).unwrap_or_default(),
            history,
            desktop_limit,
        })
    }

    fn log(&self, msg: &str) {
        overlay::append_log(&self.dir, msg);
    }

    fn read_state_for_startup(&self) -> std::io::Result<Saved> {
        load_saved(&self.dir).map_err(|error| {
            self.log(&format!("Could not load DNS state. Preserving state.json: {error}"));
            if let Err(restore) = self.dns("on") {
                self.log(&format!("Could not restore local DNS with unreadable state: {restore}"));
            }
            error
        })
    }

    fn persist_state(&self, state: &Saved) -> std::io::Result<()> {
        focus_browser_host::durable_atomic_write(&self.dir.join("state.json"), &serde_json::to_vec_pretty(state)?)
    }

    fn save(&self, s: &Saved) {
        let result = self.persist_state(s);
        self.state_write_pending.store(result.is_err(), Ordering::Relaxed);
        if let Err(e) = result {
            self.log(&format!("state save failed: {e}"));
        }
    }

    fn view(&self) -> View {
        self.view_locked(&self.saved.lock().unwrap())
    }

    fn view_locked(&self, s: &Saved) -> View {
        let scoped_until = s.scoped_pause.as_ref().map(|lease| {
            if lease.closing || lease.started.is_none() { 0 } else { lease.until }
        });
        let now = now_ms();
        View {
            paused_at: s.paused_at, paused_until: s.paused_until, now, dry_run: self.dry, scoped_until,
            dns_configured: self.dry || self.dns_profile().is_some(),
            scoped_services: s.scoped_pause.as_ref().map(|lease| lease.services.clone()).unwrap_or_default(),
            rating_prompt: s.rating_prompt.as_ref().filter(|p| now < p.expires_at).cloned(),
            free_time: s.free_time && s.paused_until > now,
            emergency_shield: s.emergency_shield && s.paused_until > now,
        }
    }

    fn peek_protected(&self) -> bool {
        let saved = self.saved.lock().unwrap();
        !self.dry
            && saved.paused_until == 0
            && self.dns("peek-status").is_ok_and(|status| status == "STATUS on")
    }

    fn dns_profile(&self) -> Option<&str> {
        self.dns_config.get().map(|config| config.profile.as_str())
    }

    fn require_dns_profile(&self) -> Result<&str, String> {
        self.dns_profile().ok_or_else(|| "Set up NextDNS using the Focus installation guide".into())
    }

    /// Runs scripts/dns.ps1 with the given mode and logs what it said.
    fn dns(&self, mode: &str) -> Result<String, String> {
        let queued = Instant::now();
        let _guard = self.dns_lock.lock().unwrap();
        let op = self.dns_sequence.fetch_add(1, Ordering::Relaxed);
        self.log(&format!("dns {mode}: start op={}-{op} queued_ms={}", std::process::id(), queued.elapsed().as_millis()));
        #[cfg(test)]
        return (self.dns_override)(mode);
        #[cfg(not(test))]
        {
            if self.dry {
                thread::sleep(Duration::from_millis(300));
                self.log(&format!("dns {mode}: dry run, nothing changed"));
                return Ok("STATUS on".into());
            }
            // The script is baked into the exe and passed inline, so nothing a normal
            // (non-admin) program can edit ever runs with admin rights.
            let profile_arg = if mode == "recover" {
                String::new()
            } else {
                // Only a validated six-character hex ID enters the command.
                format!(" -Profile {}", self.require_dns_profile()?)
            };
            let script = format!("$ProgressPreference = 'SilentlyContinue'\n& {{\n{DNS_SCRIPT}\n}} -Mode {mode}{profile_arg} -ResourceTelemetry");
            let mut cmd = Command::new("powershell.exe");
            cmd.args(["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", &encode_ps(&script)]);
            #[cfg(windows)]
            {
                use std::os::windows::process::CommandExt;
                cmd.creation_flags(NO_WINDOW);
            }
            let t = Instant::now();
            telemetry::DNS_SPAWN.count(1);
            let output = cmd.output();
            telemetry::DNS_COMMAND_MS.count(t.elapsed().as_millis() as u64);
            let result = match output {
                Ok(o) => {
                    let flat = |b: &[u8]| String::from_utf8_lossy(b).trim().replace("\r\n", " | ").replace('\n', " | ");
                    let out = if mode == "test" {
                        resource_telemetry::dns_probe_output(&String::from_utf8_lossy(&o.stdout))
                    } else {
                        flat(&o.stdout)
                    };
                    let mut err = flat(&o.stderr);
                    if err.starts_with("#< CLIXML") && !err.contains("S=\"Error\"") {
                        err.clear(); // only PowerShell progress chatter, not a real error
                    }
                    self.log(&format!(
                        "dns {mode}: exit={} {}ms op={}-{op} | {}{}",
                        o.status.code().unwrap_or(-1),
                        t.elapsed().as_millis(),
                        std::process::id(),
                        out,
                        if err.is_empty() { String::new() } else { format!(" | stderr: {err}") }
                    ));
                    if o.status.success() { Ok(out) } else { Err(if err.is_empty() { out } else { err }) }
                }
                Err(e) => {
                    self.log(&format!("dns {mode}: could not start powershell: {e}"));
                    Err(e.to_string())
                }
            };
            if let Some(stage) = match mode {
                "on" => Some("resume"),
                "off" => Some("pause"),
                "test" => Some("verify"),
                _ => None,
            } {
                let ok = result.is_ok();
                self.event("dns", stage, if ok { "ok" } else { "failed" },
                    if ok { "ok" } else { "dns_apply_failed" }, focus_devices::events::elapsed(t),
                    serde_json::json!({"origin": "laptop"}));
            }
            result
        }
    }

    fn history_line(&self, entry: serde_json::Value) -> std::io::Result<()> {
        let _history = self.history_write.lock().unwrap();
        let mut file = OpenOptions::new().create(true).read(true).append(true)
            .open(self.dir.join("pauses.jsonl"))?;
        let mut last = [b'\n'];
        if file.metadata()?.len() > 0 {
            file.seek(SeekFrom::End(-1))?;
            file.read_exact(&mut last)?;
        }
        let separator = if last[0] == b'\n' { "" } else { "\n" };
        file.write_all(format!("{separator}{entry}\n").as_bytes())
    }

    /// Caller holds saved through the DNS operation and the state/history commit.
    fn enable_locked(&self, s: &mut Saved, why: &str) -> Result<(), String> {
        let requested_at = now_ms();
        self.dns("on")?;
        let mut next = Saved {
            pause_ended_at: s.pause_ended_at,
            profile: s.profile.take(),
            scope_revision: s.scope_revision,
            rating_prompt: s.rating_prompt.take(),
            ..Saved::default()
        };
        if s.paused_until > 0 {
            let ended_at = now_ms();
            next.pause_ended_at = Some(ended_at);
            let recorded = self.history_line(serde_json::json!({
                "type": "resume", "t": ended_at, "why": why,
                "paused_at": s.paused_at, "scheduled_until": s.paused_until,
                "pause_id": s.pause_id,
                "requested_at": requested_at,
            }));
            next.rating_prompt = if s.free_time {
                None
            } else {
                recorded.ok().map(|_| RatingPrompt {
                    paused_at: s.paused_at, pause_id: s.pause_id, ended_at, expires_at: ended_at + RATING_WINDOW_MS,
                })
            };
        }
        next.scoped_pause = s.scoped_pause.take();
        next.pending_grants = std::mem::take(&mut s.pending_grants);
        *s = next;
        self.save(s);
        self.log(&format!("protection ON ({why})"));
        Ok(())
    }

    fn publish(self: &Arc<Self>, app: &AppHandle) {
        let _ = app.emit("state", self.view());
        self.test_later();
    }

    fn enable(self: &Arc<Self>, app: &AppHandle, why: &str) -> Result<(), String> {
        {
            let mut saved = self.saved.lock().unwrap();
            self.enable_locked(&mut saved, why)?;
            pause_judge::sweep_scope(self, &mut saved, true)?;
        }
        self.publish(app);
        Ok(())
    }

    fn start_free_time(&self, minutes: u64, request_id: &str) -> Result<(), String> {
        let mut s = self.saved.lock().unwrap();
        let extra = serde_json::json!({
            "free_time": true,
            "kind": "free_time",
            "request_id": request_id,
        });
        self.start_pause_locked(
            &mut s,
            minutes,
            format!("Store free time ({minutes}m)"),
            None,
            Some(extra),
        )?;
        s.free_time = true;
        s.emergency_shield = false;
        self.save(&s);
        Ok(())
    }

    fn start_pause(&self, minutes: u64, reason: String, check_in: Option<CheckIn>) -> Result<(), String> {
        let mut s = self.saved.lock().unwrap();
        if pause_judge::uses_judge(self)? {
            return Err("Use the pause judge or its hold-to-pause override".into());
        }
        self.start_pause_locked(&mut s, minutes, reason, check_in, None)
    }

    fn start_pause_locked(&self, s: &mut Saved, minutes: u64, reason: String, check_in: Option<CheckIn>, extra: Option<serde_json::Value>) -> Result<(), String> {
        if s.scoped_pause.is_some() {
            return Err("End the scoped pause and wait for NextDNS cleanup first".into());
        }
        if s.paused_until > now_ms() {
            return Err("Already paused".into());
        }
        // An expired pause may still be awaiting a successful DNS-on retry.
        if s.paused_until > 0 {
            return Err("Protection is still being restored; try again shortly".into());
        }
        if !self.dry { self.require_dns_profile()?; }
        let requested_at = now_ms();
        let pause_id = Some(self.next_event_sequence(requested_at)?);
        self.dns("off").map_err(|e| {
            self.log(&format!("pause failed: {e}"));
            "Couldn't turn DNS off, see log".to_string()
        })?;
        let now = now_ms();
        s.paused_at = now;
        s.paused_until = now + minutes * 60_000;
        s.reason = reason.clone();
        s.pause_id = pause_id;
        s.pause_ended_at = None;
        self.save(s);
        self.log(&format!("protection OFF for {minutes}m pause={now}, reason: {reason}"));
        let mut event = serde_json::json!({
            "type": "pause", "t": now, "until": s.paused_until,
            "at": chrono::Local::now().to_rfc3339(), "minutes": minutes, "reason": reason,
            "requested_at": requested_at, "check_in": check_in, "pause_id": pause_id,
        });
        if let Some(serde_json::Value::Object(fields)) = extra {
            event.as_object_mut().unwrap().extend(fields);
        }
        if let Err(error) = self.history_line(event) {
            self.log(&format!("pause history save failed: {error}"));
        }
        Ok(())
    }

    fn rate_pause(&self, paused_at: u64, pause_id: Option<u64>, rating: Option<u8>, note: &str) -> Result<(), String> {
        let mut s = self.saved.lock().unwrap();
        let now = now_ms();
        if !s.rating_prompt.as_ref().is_some_and(|p| {
            p.paused_at == paused_at && p.pause_id == pause_id && now >= p.ended_at && now < p.expires_at
        }) {
            return Err("This rating prompt expired".into());
        }
        if let Some(score) = rating {
            let pause = self.recorded_pause(paused_at, pause_id).ok_or("Could not find this pause")?;
            if pause.rating.is_some() {
                s.rating_prompt = None;
                self.save(&s);
                return Err("Rating already saved".into());
            }
            let note = note.trim();
            if !(1..=5).contains(&score) || note.chars().count() > RATING_NOTE_LIMIT {
                return Err("Pick a rating from 1 to 5 and keep the note short".into());
            }
            self.history_line(serde_json::json!({
                // Older readers use t/at to find pauses and resume events.
                "type": "rating", "rated_at": now, "paused_at": paused_at, "pause_id": pause_id,
                "rating": score, "rating_note": note,
            })).map_err(|_| "Could not save your rating".to_owned())?;
        }
        s.rating_prompt = None;
        self.save(&s);
        Ok(())
    }

    fn recorded_pause(&self, paused_at: u64, pause_id: Option<u64>) -> Option<PauseRec> {
        let text = fs::read_to_string(self.dir.join("pauses.jsonl")).ok()?;
        read_pauses(&text, None, now_ms()).into_iter()
            .rfind(|p| p.t == paused_at && p.pause_id == pause_id)
            .filter(|p| p.completed_at.is_some())
    }

    fn startup(&self) -> Result<bool, String> {
        let mut s = self.saved.lock().unwrap();
        let now = now_ms();
        if s.rating_prompt.as_ref().is_some_and(|prompt| {
            self.recorded_pause(prompt.paused_at, prompt.pause_id).is_some_and(|p| p.rating.is_some())
        }) {
            // A previous pause's score can reach the journal before clearing
            // its prompt on disk, even while a newer pause is running.
            s.rating_prompt = None;
            self.save(&s);
        }
        if s.paused_until > 0 {
            // A resume can reach the journal before its state replacement reaches disk.
            if let Some((p, ended)) = self.recorded_pause(s.paused_at, s.pause_id)
                .and_then(|p| p.completed_at.map(|ended| (p, ended)))
            {
                let expires_at = ended.saturating_add(RATING_WINDOW_MS);
                *s = Saved {
                    pause_ended_at: Some(ended),
                    profile: s.profile.take(),
                    rating_prompt: (p.rating.is_none() && now >= ended && now < expires_at)
                        .then_some(RatingPrompt { paused_at: p.t, pause_id: p.pause_id, ended_at: ended, expires_at }),
                    scoped_pause: s.scoped_pause.take(),
                    scope_revision: s.scope_revision,
                    pending_grants: std::mem::take(&mut s.pending_grants),
                    ..Saved::default()
                };
                self.save(&s);
            }
        }
        if s.paused_until > now && s.scoped_pause.is_none() {
            self.log(&format!("still paused, {}s left", (s.paused_until - now) / 1000));
            return Ok(false);
        }
        if !self.dry && s.paused_until == 0 && self.dns("status").as_deref() == Ok("STATUS on") {
            self.save(&s);
            return Ok(false);
        }
        self.enable_locked(&mut s, "startup")?;
        Ok(true)
    }

    fn reconcile(&self, check_dns: bool) -> Result<bool, String> {
        // A queued status check must read pause state only after taking the lock.
        // Keep it until any repair completes: no pause can slip between these steps.
        let mut s = self.saved.lock().unwrap();
        if self.state_write_pending.load(Ordering::Relaxed) {
            self.save(&s);
        }
        if s.paused_until > 0 {
            if now_ms() < s.paused_until && s.scoped_pause.is_none() { return Ok(false); }
            self.enable_locked(&mut s, "timer ended")?;
            return Ok(true);
        }
        if check_dns && (self.dry || self.dns_profile().is_some())
            && !matches!(self.dns("status").as_deref(), Ok("STATUS on" | "STATUS offline"))
        {
            self.log("DNS was changed outside FocusDNS, putting NextDNS back");
            self.enable_locked(&mut s, "watchdog")?;
            return Ok(true);
        }
        Ok(false)
    }

    /// Asks test.nextdns.io a few seconds later, so the log shows what really happened.
    fn test_later(self: &Arc<Self>) {
        if self.dry {
            return;
        }
        let me = self.clone();
        thread::spawn(move || {
            thread::sleep(Duration::from_secs(3));
            let _ = me.dns("test");
        });
    }
}

#[tauri::command(async)]
fn get_state(core: State<Arc<Core>>) -> View {
    core.view()
}

#[tauri::command(async)]
fn pause(
    minutes: u64,
    reason: String,
    check_in: Option<CheckIn>,
    app: AppHandle,
    core: State<Arc<Core>>,
    devices: State<Arc<device_commands::Devices>>,
    tracker: State<Arc<Tracker>>,
    host: State<Arc<device_commands::LaptopHost>>,
) -> Result<View, String> {
    let reason = reason.trim().to_string();
    if !ALLOWED_MINUTES.contains(&minutes) {
        return Err("Pick 1, 5, 10 or 30 minutes".into());
    }
    if reason.chars().count() < 3 {
        return Err("A reason is required".into());
    }
    core.start_pause(minutes, reason, check_in)?;
    core.publish(&app);
    device_commands::trigger_phone_sync_async(
        app,
        devices.inner().clone(),
        core.inner().clone(),
        tracker.inner().clone(),
        host.inner().clone(),
    );
    let v = core.view();
    Ok(v)
}

#[tauri::command(async)]
fn rate_pause(
    paused_at: u64,
    pause_id: Option<u64>,
    rating: Option<u8>,
    note: String,
    app: AppHandle,
    core: State<Arc<Core>>,
    devices: State<Arc<device_commands::Devices>>,
    tracker: State<Arc<Tracker>>,
    host: State<Arc<device_commands::LaptopHost>>,
) -> Result<View, String> {
    if let Err(error) = core.rate_pause(paused_at, pause_id, rating, &note) {
        let _ = app.emit("state", core.view());
        return Err(error);
    }
    if rating.is_some() {
        device_commands::trigger_phone_sync_async(
            app, devices.inner().clone(), core.inner().clone(), tracker.inner().clone(), host.inner().clone(),
        );
    }
    Ok(core.view())
}

#[tauri::command]
async fn resume(
    app: AppHandle,
    core: State<'_, Arc<Core>>,
    devices: State<'_, Arc<device_commands::Devices>>,
    tracker: State<'_, Arc<Tracker>>,
    host: State<'_, Arc<device_commands::LaptopHost>>,
) -> Result<View, String> {
    let (core, devices, tracker, host) = (
        core.inner().clone(), devices.inner().clone(), tracker.inner().clone(), host.inner().clone(),
    );
    tauri::async_runtime::spawn_blocking(move || {
        core.enable(&app, "turned on early").map_err(|error| {
            core.log(&format!("Early restore failed: {error}"));
            let _ = app.emit("state", core.view());
            "Couldn't restore protection yet. The watchdog will retry.".to_string()
        })?;
        device_commands::trigger_phone_sync_async(app, devices, core.clone(), tracker, host);
        Ok(core.view())
    }).await.map_err(|_| "Restore task stopped")?
}

#[derive(Serialize)]
struct History {
    now: u64,
    spans: Vec<Span>,
    pauses: Vec<PauseRec>,
    peeks: Vec<u64>,
    intentions: Vec<focus_dns::intentions::IntentionRec>,
    /// Ask-approved service windows, so approved use is not counted as drift.
    grants: Vec<pause_history::ScopedGrant>,
}

/// Raw screen time + pauses between two times (ms). The UI does the math.
#[tauri::command(async)]
fn history(from: u64, to: u64, core: State<Arc<Core>>, tracker: State<Arc<Tracker>>) -> History {
    let s = core.saved.lock().unwrap();
    let text = fs::read_to_string(core.dir.join("pauses.jsonl")).unwrap_or_default();
    let now = now_ms();
    let active_pause = (s.paused_at != 0).then_some((s.paused_at, s.pause_id));
    let mut pauses = read_pauses(&text, active_pause, now);
    drop(s);
    pauses.retain(|p| p.end > from && p.t < to);
    let mut peeks = focus_browser_host::peek_history::timestamps(&core.dir, from, to);
    peeks.extend(peek_panel::timestamps(&core.dir, from, to));
    let intentions = core.intentions.history(from, to, now);
    let mut grants = pause_history::scoped_grants(&text, now);
    grants.retain(|g| g.end > from && g.t < to);
    History { now, spans: tracker.spans(from, to), pauses, peeks, intentions, grants }
}

/// Same as Win+L: agents keep running, screen time stops counting until you unlock.
#[tauri::command]
fn lock(core: State<Arc<Core>>) {
    core.log("locked Windows from the widget");
    unsafe { windows_sys::Win32::System::Shutdown::LockWorkStation() };
}

/// Lets the UI write to the same log (clicks, errors), so problems are easy to trace.
#[tauri::command]
fn ui_log(msg: String, core: State<Arc<Core>>) {
    if focus_dns::focus_state::history::is_panel_open(&msg) {
        focus_state_runtime::record_panel_open(&core);
    }
    core.log(&format!("ui: {}", msg.chars().take(300).collect::<String>()));
}

/// Reads user/agent activity rules from %LOCALAPPDATA%\FocusDNS\rules.json.
#[tauri::command(async)]
fn activity_rules_load(core: State<Arc<Core>>) -> Result<serde_json::Value, String> {
    activity_rules::load_rules(&core.dir).map_err(|e| {
        core.log(&format!("activity rules load error: {e}"));
        e
    })
}

/// Reads fine-grained categories (`categories.json`) written by a Jev/agent classification job.
#[tauri::command(async)]
fn activity_categories_load(core: State<Arc<Core>>) -> Result<serde_json::Value, String> {
    activity_rules::load_optional_object(&core.dir, "categories.json", 2 * 1024 * 1024).map_err(|e| {
        core.log(&format!("activity categories load error: {e}"));
        e
    })
}

/// Reads cached app/site icons (`icons.json`: lowercase app name -> data URL).
#[tauri::command(async)]
fn activity_icons_load(core: State<Arc<Core>>) -> Result<serde_json::Value, String> {
    activity_rules::load_optional_object(&core.dir, "icons.json", 4 * 1024 * 1024).map_err(|e| {
        core.log(&format!("activity icons load error: {e}"));
        e
    })
}

/// Saves user/agent activity rules atomically to %LOCALAPPDATA%\FocusDNS\rules.json.
#[tauri::command(async)]
fn activity_rules_save(rules: serde_json::Value, core: State<Arc<Core>>) -> Result<(), String> {
    let count = rules.get("rules").and_then(|v| v.as_array()).map(|a| a.len()).unwrap_or(0);
    activity_rules::save_rules(&core.dir, &rules).map_err(|e| {
        core.log(&format!("activity rules save error: {e}"));
        e
    })?;
    core.log(&format!("activity rules saved count={count}"));
    Ok(())
}

/// Updates %LOCALAPPDATA%\FocusDNS\uncategorized.json so agents can review unmatched apps/sites.
#[tauri::command(async)]
fn activity_uncategorized_save(inbox: serde_json::Value, core: State<Arc<Core>>) -> Result<(), String> {
    activity_rules::save_uncategorized(&core.dir, &inbox).map_err(|e| {
        core.log(&format!("uncategorized inbox save error: {e}"));
        e
    })
}

/// `--panel` or `--panel week` on the command line opens the stats panel at startup.
#[tauri::command]
fn startup_panel(panel: State<StartupPanel>) -> Option<String> {
    panel.0.clone()
}
struct StartupPanel(Option<String>);

/// The UI tells us where the pill is (CSS px, relative to the window) so clicks
/// everywhere else go straight through to whatever is underneath.
#[tauri::command]
fn set_hit(x: f64, y: f64, w: f64, h: f64, core: State<Arc<Core>>) {
    *core.hit.lock().unwrap() = [x, y, w, h];
}

/// Clicking a time button must also pull keyboard focus to the widget,
/// otherwise typing would go to whatever app was in front before.
#[tauri::command]
fn grab_focus(window: WebviewWindow) {
    let _ = window.set_focus();
}

/// After pausing (or Esc), put keyboard focus back where you were, e.g. the browser.
#[tauri::command]
fn return_focus(core: State<Arc<Core>>) {
    use windows_sys::Win32::UI::WindowsAndMessaging::SetForegroundWindow;
    let prev = std::mem::take(&mut *core.prev_fg.lock().unwrap());
    if prev != 0 {
        let ok = unsafe { SetForegroundWindow(prev as _) } != 0;
        core.log(&format!("focus back to previous window: {ok}"));
    }
}

#[cfg(windows)]
fn is_elevated() -> bool {
    unsafe { windows_sys::Win32::UI::Shell::IsUserAnAdmin() != 0 }
}

#[cfg(windows)]
fn cursor() -> Option<(f64, f64)> {
    let mut p = windows_sys::Win32::Foundation::POINT { x: 0, y: 0 };
    let ok = unsafe { windows_sys::Win32::UI::WindowsAndMessaging::GetCursorPos(&mut p) } != 0;
    ok.then_some((p.x as f64, p.y as f64))
}

fn hover_loop(win: WebviewWindow, core: Arc<Core>) {
    let mut inside_before = false;
    let mut last_health = Instant::now();
    let mut last_peek_request = Instant::now();
    loop {
        thread::sleep(Duration::from_millis(30));
        telemetry::OVERLAY_TICK.count(1);
        if last_peek_request.elapsed() >= Duration::from_millis(250) {
            last_peek_request = Instant::now();
            if focus_browser_host::peek_history::take_pause_request(&core.dir, now_ms()) {
                let _ = win.emit("peek-open-pause", ());
            }
        }
        if last_health.elapsed() >= OVERLAY_HEALTH_INTERVAL {
            last_health = Instant::now();
            overlay::check_overlay_health(&win, core.dry);
        }
        let (Some((cx, cy)), Ok(pos), Ok(sf)) = (cursor(), win.outer_position(), win.scale_factor()) else { continue };
        let [x, y, w, h] = *core.hit.lock().unwrap();
        let (l, t) = (pos.x as f64 + x * sf, pos.y as f64 + y * sf);
        let inside = cx >= l && cx < l + w * sf && cy >= t && cy < t + h * sf;
        if inside != inside_before {
            inside_before = inside;
            if inside {
                // Remember where you were *before* touching the pill (a click makes the pill
                // the active window), so focus can go back there after pausing.
                use windows_sys::Win32::UI::WindowsAndMessaging::GetForegroundWindow;
                let fg = unsafe { GetForegroundWindow() } as isize;
                let ours = win.hwnd().map(|h| h.0 as isize).unwrap_or(0);
                if fg != 0 && fg != ours {
                    *core.prev_fg.lock().unwrap() = fg;
                }
            }
            if let Err(e) = overlay::set_click_through(&win, !inside) {
                core.log(&format!("overlay: set_click_through({}) failed: {e}", !inside));
            }
            let _ = win.emit(if inside { "cursor-in" } else { "cursor-out" }, ());
        }
    }
}

fn watchdog_loop(app: AppHandle, core: Arc<Core>, tracker: Arc<Tracker>) {
    let mut last_scope_failure = None::<Instant>;
    let (mut last_try, mut last_check) = (0u64, now_ms());
    let mut prev_time_up = None::<String>;
    let (mut last_nag_check, mut last_nag_emit) = (0u64, 0u64);
    loop {
        thread::sleep(Duration::from_secs(1));
        telemetry::DNS_TICK.count(1);
        intention_commands::tick(
            &app,
            &core,
            &tracker,
            &mut prev_time_up,
            &mut last_nag_check,
            &mut last_nag_emit,
        );
        let n = now_ms();
        if n.saturating_sub(last_try) >= 15_000 {
            let check_dns = n.saturating_sub(last_check) >= 60_000;
            if check_dns { last_check = n; }
            match core.reconcile(check_dns) {
                Ok(true) => core.publish(&app),
                Err(_) => last_try = n,
                Ok(false) => {}
            }
        }

        // A full pause has a local restore deadline. Never hold its state lock
        // for a remote NextDNS call while Windows protection is paused.
        let mut saved = core.saved.lock().unwrap();
        if saved.paused_until > 0 {
            continue;
        }
        let mut notify = false;
        if last_scope_failure.is_none_or(|failed| failed.elapsed() >= Duration::from_secs(15)) {
            match pause_judge::sweep_scope(&core, &mut saved, false) {
                Ok(changed) => notify = changed,
                Err(error) => {
                    core.log(&format!("Scoped pause cleanup pending: {error}"));
                    last_scope_failure = Some(Instant::now());
                }
            }
        }
        drop(saved);
        if notify {
            let _ = app.emit("state", core.view());
        }
    }
}

fn load_saved(dir: &std::path::Path) -> std::io::Result<Saved> {
    match fs::read(dir.join("state.json")) {
        Ok(bytes) => {
            let mut state: serde_json::Value = serde_json::from_slice(&bytes).map_err(std::io::Error::other)?;
            if let Some(scope) = state.get_mut("scoped_pause") {
                if scope.get("in_flight").is_none() && scope["started"].is_null()
                    && scope["settle_until"].as_u64().is_some_and(|deadline| deadline > 0)
                {
                    // The first preview journaled grants in domain order. Its
                    // last entry may still be in flight after an interrupted grant.
                    if let Some(domain) = scope["changes"].as_object().and_then(|changes| changes.keys().max().cloned()) {
                        scope["in_flight"] = serde_json::Value::String(domain);
                    }
                }
            }
            serde_json::from_value(state).map_err(std::io::Error::other)
        },
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(Saved::default()),
        Err(error) => Err(error),
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let dry = args.iter().any(|a| a == "--dry-run");
    let from_task = args.iter().any(|a| a == "--from-task");
    let open_panel = args.iter().position(|a| a == "--panel").map(|i| {
        if args.get(i + 1).is_some_and(|v| v == "week") { "week".to_string() } else { "today".to_string() }
    });

    let dir = PathBuf::from(std::env::var("LOCALAPPDATA").unwrap_or_else(|_| ".".into()))
        .join(if dry { "FocusDNS-dry" } else { "FocusDNS" });
    let _ = fs::create_dir_all(&dir);
    overlay::install_panic_hook(dir.clone());
    let elevated = is_elevated();
    overlay::append_log(&dir, &format!(
        "start v{} build={} pid={} dry_run={dry} elevated={} from_task={from_task}",
        env!("CARGO_PKG_VERSION"),
        env!("FOCUS_BUILD_ID"),
        std::process::id(),
        elevated
    ));

    if !dry && !elevated {
        if from_task {
            overlay::append_log(&dir, "started by the FocusDNS task but without admin rights; reinstall with scripts/install.ps1");
        } else {
            // Double-clicked: let the scheduled task (which has admin rights) start the real one.
            overlay::append_log(&dir, "not admin, asking the FocusDNS task to start the app instead");
            use std::os::windows::process::CommandExt;
            let _ = Command::new("schtasks").args(["/run", "/tn", "FocusDNS"]).creation_flags(NO_WINDOW).status();
        }
        return;
    }

    let already_running = !dry && overlay::is_single_instance_mutex_held(APP_IDENTIFIER);
    if already_running {
        overlay::append_log(&dir, &format!(
            "single-instance: another FocusDNS instance is already running (pid={}), handing off and exiting",
            std::process::id()
        ));
    } else {
        overlay::wait_for_desktop_shell(&dir, SHELL_WAIT_TIMEOUT, SHELL_WAIT_POLL);
    }

    // Each copy gets its own WebView2 engine folder. Otherwise the practice copy would
    // borrow the admin copy's engine (and Windows then drops clicks to it).
    let webview_dir = dir.join("webview");
    std::env::set_var("WEBVIEW2_USER_DATA_FOLDER", &webview_dir);
    if !already_running {
        overlay::append_log(&dir, &format!("starting tauri runtime (webview_dir={})", webview_dir.display()));
    }

    let mut builder = tauri::Builder::default();
    if !dry {
        let log_dir = dir.clone();
        builder = builder.plugin(tauri_plugin_single_instance::init(move |app, args, _cwd| {
            overlay::append_log(&log_dir, &format!(
                "single-instance: second launch detected (args={args:?}), asserting overlay"
            ));
            if let Some(win) = app.get_webview_window("main") {
                overlay::request_repair(&win, overlay::REPAIR_REASON_SECOND_INSTANCE);
            }
        }));
    }
    let setup_dir = dir.clone();
    let run_result = builder
        .manage(StartupPanel(open_panel))
        .invoke_handler(tauri::generate_handler![focus_state_runtime::habits_feed_lock, streak_runtime::streak_view, streak_runtime::streak_buy_freeze, wallet::wallet_view, wallet::wallet_buy_time, habit_commands::habits_view, habit_commands::habits_change, habit_commands::habits_pause_limit, get_state, pause, resume, rate_pause, set_hit, grab_focus, history, lock, ui_log, return_focus, startup_panel, peek_panel::peek_link, activity_rules_load, activity_rules_save, activity_uncategorized_save, activity_categories_load, activity_icons_load, device_commands::device_nearby, device_commands::device_request_approval, device_commands::device_approval_status, device_commands::device_cancel_approval, device_commands::device_list, device_commands::device_probe, device_commands::device_pair, device_commands::device_refresh, device_commands::device_command, device_commands::device_forget, device_commands::laptop_incoming_approval, device_commands::laptop_approve_pairing, device_commands::laptop_decline_pairing, updater::updater_status, updater::updater_check, updater::updater_install, updater::updater_dismiss, updater::updater_set_token, updater::updater_set_enabled,
            pause_judge::pause_judge_settings, pause_judge::pause_judge_settings_save, pause_judge::pause_judge_limits_sync,
            pause_judge::judge_pause, pause_judge::begin_pause_hold, pause_judge::cancel_pause_hold, pause_judge::apply_decision,
            emergency_shield::emergency_status, emergency_shield::emergency_recent_blocks,
            emergency_shield::emergency_activate,
            intention_commands::intention_state, intention_commands::intention_parse,
            intention_commands::intention_parse_local, intention_commands::intention_start,
            intention_commands::intention_edit, intention_commands::intention_extend,
            intention_commands::intention_end])
        .setup(move |app| {
            let setup_core = Arc::new(Core::open_profile(setup_dir.clone(), dry, now_ms()));
            app.manage(setup_core.clone());
            setup_core.log("setup: entered");
            let mut initial_state = setup_core.read_state_for_startup()?;
            if !setup_core.dry {
                if let Err(error) = setup_core.dns("recover") {
                    setup_core.log(&format!("Could not recover NextDNS configuration: {error}"));
                }
                if let Some(config) = focus_browser_host::NextDnsConfig::read(&setup_dir) {
                    if initial_state.profile.as_deref().is_none_or(|profile| profile == config.profile) {
                        let _ = setup_core.dns_config.set(config);
                    } else {
                        setup_core.log("Saved NextDNS profile differs from the previous configuration. DNS settings were preserved.");
                    }
                }
            }
            if let Some(profile) = setup_core.dns_profile() {
                initial_state.profile = Some(profile.to_owned());
            }
            *setup_core.saved.lock().unwrap() = initial_state;
            if let Err(error) = setup_core.startup() {
                setup_core.log(&format!("Local DNS startup restore pending: {error}"));
            }
            {
                let mut saved = setup_core.saved.lock().unwrap();
                if saved.paused_until == 0 {
                    if let Err(error) = pause_judge::sweep_scope(&setup_core, &mut saved, false) {
                        setup_core.log(&format!("Scoped pause startup cleanup pending: {error}"));
                    }
                }
            }
            let pending_core = setup_core.clone();
            thread::spawn(move || loop {
                pause_judge::sweep_pending(&pending_core);
                thread::sleep(Duration::from_secs(30));
            });
            updater::cleanup_old_binaries(&setup_core);
            if !setup_core.dry {
                if let Err(error) = focus_dns::browser_extension::update_installed(&setup_dir) {
                    setup_core.log(&format!("browser extension update failed: {error}"));
                }
            }
            if let Err(error) = judge::Catalog::seed(&setup_dir) {
                setup_core.log(&error);
            }
            let updater_state = Arc::new(updater::Updater::open(&setup_dir, setup_core.dry));
            let devices = Arc::new(device_commands::Devices::open(&setup_dir));
            let tracker = Arc::new(Tracker::new(&setup_dir));
            let laptop_host = Arc::new(device_commands::LaptopHost::start(
                &setup_dir,
                app.handle().clone(),
                setup_core.clone(),
                tracker.clone(),
                devices.clone(),
            ));
            app.manage(updater_state.clone());
            app.manage(devices.clone());
            app.manage(tracker.clone());
            app.manage(laptop_host.clone());

            let win = app
                .get_webview_window("main")
                .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::NotFound, "main window missing"))?;

            let pre = overlay::init_overlay_window(&win, setup_core.dry, &setup_dir)
                .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e))?;
            setup_core.log(&format!(
                "setup: overlay initialized pre-show style=0x{:08x} ex=0x{:08x} pos=({},{})",
                pre.style, pre.ex_style, pre.x, pre.y
            ));

            win.show()?;

            let post = overlay::finalize_overlay_after_show(&win, setup_core.dry, &setup_dir)
                .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e))?;
            setup_core.log(&format!(
                "setup: window shown visible={} minimized={} style=0x{:08x} ex=0x{:08x} pos=({},{})",
                post.visible, post.minimized, post.style, post.ex_style, post.x, post.y
            ));

            let (a, c, t) = (app.handle().clone(), setup_core.clone(), tracker.clone());
            thread::spawn(move || focus_state_runtime::run(a, c, t));
            setup_core.log("screen time tracking started");

            let (w, c) = (win.clone(), setup_core.clone());
            thread::spawn(move || hover_loop(w, c));
            let (a, c, t) = (app.handle().clone(), setup_core.clone(), tracker.clone());
            thread::spawn(move || watchdog_loop(a, c, t));
            let (a, d, c, t, h) = (
                app.handle().clone(),
                devices.clone(),
                setup_core.clone(),
                tracker.clone(),
                laptop_host.clone(),
            );
            thread::spawn(move || device_commands::sync_loop(a, d, c, t, h));
            if !setup_core.dry {
                let (a, c, t, d) = (app.handle().clone(), setup_core.clone(), tracker, devices);
                thread::spawn(move || wallet_runtime::run(a, c, t, d));
            }
            let (a, u, c) = (app.handle().clone(), updater_state.clone(), setup_core.clone());
            thread::spawn(move || updater::poll_loop(a, u, c));
            setup_core.log("setup: complete");
            Ok(())
        })
        .run(tauri::generate_context!());

    if let Err(e) = run_result {
        overlay::append_log(&dir, &format!("tauri run failed: {e:?}"));
        std::process::exit(1);
    }
}
