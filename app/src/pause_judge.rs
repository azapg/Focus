use crate::{
    activity::Tracker,
    device_commands::Devices,
    judge::{
        self, Action, Catalog, Decision, Failure, Friction, Gateway, Policy, Request, MINUTE_MS,
    },
    judge_context,
    nextdns::{self, Lease, NextDns, PauseTrace},
    now_ms,
    pause_history::read_access_pauses,
    Core, Saved, View,
};
use ring::rand::{SecureRandom, SystemRandom};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, HashSet},
    fs,
    io::{ErrorKind, Write},
    path::Path,
    sync::{Arc, Condvar, Mutex, MutexGuard},
    time::{Duration, Instant},
};
use tauri::{AppHandle, Emitter, State};

const SETTINGS: &str = "pause-judge.json";
const LIMITS: &str = "pause-judge-limits.json";
const TICKET_AGE: Duration = Duration::from_secs(300);
const MAX_TICKETS: usize = 48;

#[derive(Deserialize, Serialize)]
#[serde(default)]
struct Settings {
    model: String,
    enabled: bool,
    gateway_dpapi: Option<String>,
    nextdns_dpapi: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    cleanup_dpapi: Vec<String>,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            model: judge::DEFAULT_MODEL.into(),
            enabled: false,
            gateway_dpapi: None,
            nextdns_dpapi: None,
            cleanup_dpapi: Vec::new(),
        }
    }
}

#[derive(Deserialize)]
pub struct SettingsUpdate {
    model: Option<String>,
    gateway_key: Option<String>,
    nextdns_key: Option<String>,
}

#[derive(Serialize)]
pub struct SettingsView {
    model: String,
    default_model: &'static str,
    luna_model: &'static str,
    enabled: bool,
    has_gateway_key: bool,
    has_nextdns_key: bool,
}

impl Settings {
    fn enabled(&self) -> bool {
        self.enabled || self.gateway_dpapi.is_some()
    }

    fn set_gateway_key(&mut self, key: Option<String>) {
        self.enabled = self.enabled() || key.is_some();
        self.gateway_dpapi = key;
    }

    fn view(&self) -> SettingsView {
        SettingsView {
            model: self.model.clone(),
            default_model: judge::DEFAULT_MODEL,
            luna_model: judge::LUNA_MODEL,
            enabled: self.enabled(),
            has_gateway_key: self.gateway_dpapi.is_some(),
            has_nextdns_key: self.nextdns_dpapi.is_some(),
        }
    }

    fn load(dir: &Path) -> Result<Self, String> {
        match fs::read(dir.join(SETTINGS)) {
            Ok(bytes) => {
                serde_json::from_slice(&bytes).map_err(|_| "Invalid pause judge settings".into())
            }
            Err(e) if e.kind() == ErrorKind::NotFound => Ok(Self::default()),
            Err(_) => Err("Could not read pause judge settings".into()),
        }
    }

    fn gateway_key(&self) -> Result<String, Failure> {
        let encrypted = self
            .gateway_dpapi
            .as_deref()
            .ok_or(Failure::Configuration)?;
        focus_devices::secrets::unprotect(encrypted).map_err(|_| Failure::Configuration)
    }

    fn nextdns_key(&self) -> Result<String, String> {
        let encrypted = self
            .nextdns_dpapi
            .as_deref()
            .ok_or("Set the NextDNS API key in Settings")?;
        focus_devices::secrets::unprotect(encrypted)
            .map_err(|_| "Could not decrypt the NextDNS API key".into())
    }
}

pub(super) fn cleanup_profile<'a>(core: &'a Core, recorded: Option<&'a str>) -> Result<&'a str, String> {
    recorded.or_else(|| {
        core.dns_config.get().filter(|config| config.legacy_confirmed).map(|config| config.profile.as_str())
    }).ok_or_else(|| "Confirm the original NextDNS profile to clean up the earlier grant".into())
}

fn with_cleanup_api<T>(
    core: &Core,
    profile: Option<&str>,
    trace: Option<&PauseTrace>,
    action: impl FnMut(&NextDns) -> Result<T, String>,
) -> Result<T, String> {
    with_cleanup_api_outcome(core, profile, trace, action).map_err(|(message, _)| message)
}

fn with_cleanup_api_outcome<T>(
    core: &Core,
    profile: Option<&str>,
    trace: Option<&PauseTrace>,
    mut action: impl FnMut(&NextDns) -> Result<T, String>,
) -> Result<T, (String, &'static str)> {
    let profile = cleanup_profile(core, profile).map_err(|e| (e, "scope_profile_mismatch"))?;
    let settings = Settings::load(&core.dir).map_err(|e| (e, "dns_apply_failed"))?;
    let mut failure = ("Set a NextDNS API key with write access in Settings".to_owned(), "scope_key_missing");
    for encrypted in settings.nextdns_dpapi.iter().chain(settings.cleanup_dpapi.iter().rev()) {
        let result = focus_devices::secrets::unprotect(encrypted)
            .map_err(|_| ("Could not decrypt a NextDNS cleanup key".to_owned(), "nextdns_auth"))
            .and_then(|key| NextDns::new(profile, key).map_err(|message| (message, "nextdns_unreachable")))
            .and_then(|api| {
                let api = api.observed(core.event_log(), trace);
                action(&api).map_err(|message| (message, api.failure_cause()))
            });
        match result {
            Ok(value) => return Ok(value),
            Err(error) => failure = error,
        }
    }
    Err(failure)
}

fn discard_cleanup_keys(core: &Core, saved: &Saved) {
    if saved.scoped_pause.is_some() || !saved.pending_grants.is_empty() {
        return;
    }
    let result = (|| {
        let mut settings = Settings::load(&core.dir)?;
        if !settings.cleanup_dpapi.is_empty() {
            settings.cleanup_dpapi.clear();
            write_json(&core.dir.join(SETTINGS), &settings)?;
        }
        Ok::<(), String>(())
    })();
    if let Err(error) = result {
        core.log(&format!("Could not discard retired NextDNS keys: {error}"));
    }
}

pub fn uses_judge(core: &Core) -> Result<bool, String> {
    Ok(Settings::load(&core.dir)?.enabled())
}

fn protect_key(key: &str) -> Result<Option<String>, String> {
    let key = key.trim();
    if key.is_empty() {
        return Ok(None);
    }
    if key.len() > 1024 || !key.bytes().all(|b| b.is_ascii_graphic()) {
        return Err("Invalid API key format".into());
    }
    focus_devices::secrets::protect(key)
        .map(Some)
        .map_err(|_| "Could not encrypt the API key with Windows DPAPI".into())
}

fn write_json(path: &Path, value: &impl Serialize) -> Result<(), String> {
    let bytes = serde_json::to_vec_pretty(value).map_err(|e| e.to_string())?;
    focus_browser_host::durable_atomic_write(path, &bytes).map_err(|e| e.to_string())
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Limits {
    max_pause_min: u64,
    wait_seconds: u64,
    #[serde(default)]
    pending: BTreeMap<String, PendingLimit>,
}

#[derive(Clone, Deserialize, Serialize)]
struct PendingLimit {
    value: u64,
    from: u64,
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            max_pause_min: 30,
            wait_seconds: 15,
            pending: BTreeMap::new(),
        }
    }
}

impl Limits {
    fn policy(&self, now: u64, count: usize, used_ms: u64, daily_limit_minutes: u64) -> Policy {
        let value = |key, current| {
            self.pending
                .get(key)
                .filter(|p| p.from <= now)
                .map_or(current, |p| p.value)
        };
        Policy {
            daily_limit_minutes,
            max_pause_minutes: value("maxPauseMin", self.max_pause_min).clamp(1, 30),
            wait_seconds: value("waitSeconds", self.wait_seconds).min(120),
            used_ms,
            count,
        }
    }
}

struct Recent {
    now: u64,
    restored_ago: Duration,
    pauses: Vec<crate::pause_history::PauseRec>,
    reasons: Vec<String>,
    policy: Policy,
}

impl Recent {
    fn read(core: &Core, saved: &Saved, devices: &Devices) -> Self {
        let now = now_ms();
        let events = fs::read_to_string(core.dir.join("pauses.jsonl")).unwrap_or_default();
        let restored_ago = events
            .lines()
            .rev()
            .filter_map(|line| serde_json::from_str::<Value>(line).ok())
            .find_map(|event| {
                matches!(event["type"].as_str(), Some("resume" | "scoped_resume"))
                    .then(|| event["t"].as_u64())
                    .flatten()
            })
            .map_or(Duration::MAX, |then| {
                Duration::from_millis(now.saturating_sub(then))
            });
        let active_scope = saved.scoped_pause.as_ref().and_then(|scope| scope.started);
        let active_pause = (saved.paused_at != 0).then_some((saved.paused_at, saved.pause_id));
        let pauses = read_access_pauses(&events, active_pause, active_scope, now);
        let limits: Limits = fs::read(core.dir.join(LIMITS))
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
        let snapshots = devices
            .0
            .as_ref()
            .map(|manager| {
                manager
                    .list()
                    .into_iter()
                    .filter_map(|view| view.snapshot)
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        let (count, used_ms, reasons) =
            judge_context::usage(judge_context::day_start(now), now, &pauses, &snapshots);
        let day = focus_dns::habits::local_clock(now).0;
        let daily_limit = u64::from(core.habits.desktop_limit().effective(now))
            + u64::from(core.habits.read().credit(day));
        let policy = limits.policy(now, count, used_ms, daily_limit);
        Self {
            now,
            restored_ago,
            pauses,
            reasons,
            policy,
        }
    }
}

#[derive(Default)]
pub struct Tickets(
    Mutex<BTreeMap<String, Ticket>>,
    Mutex<RemoteAsks>,
);

#[derive(Default)]
struct RemoteAsks {
    active: BTreeMap<String, Arc<RemoteAsk>>,
    seen: Option<HashSet<String>>,
}

impl RemoteAsks {
    // Persist only the Ask IDs. Once a reply leaves the in-memory cache, a
    // resend must expire even after the laptop restarts or the ticket is applied.
    fn remember(&mut self, dir: &Path, correlation: &str, deadline: Instant) -> Result<(), String> {
        const FILE_ERROR: &str = "Could not save the Ask ID";
        let path = dir.join("pause-ask-ids.log");
        if self.seen.is_none() {
            let ids = match fs::read_to_string(&path) {
                Ok(ids) => ids,
                Err(error) if error.kind() == ErrorKind::NotFound => String::new(),
                Err(_) => return Err(FILE_ERROR.into()),
            };
            self.seen = Some(ids.lines().filter(|id| valid_correlation(id)).map(str::to_owned).collect());
        }
        let seen = self.seen.as_mut().unwrap();
        if seen.contains(correlation) {
            return Err("Pause decision expired or was already used. Ask again.".into());
        }
        if Instant::now() >= deadline {
            return Err(ASK_TIMEOUT.into());
        }
        let mut file = fs::OpenOptions::new().create(true).append(true).open(&path)
            .map_err(|_| FILE_ERROR.to_owned())?;
        // A separator also isolates a partial line left by an interrupted append.
        file.write_all(format!("\n{correlation}\n").as_bytes()).and_then(|()| file.sync_data())
            .map_err(|_| FILE_ERROR.to_owned())?;
        seen.insert(correlation.into());
        Ok(())
    }
}

struct RemoteAsk {
    expires: Instant,
    request: Request,
    result: Mutex<Option<Result<DecisionView, String>>>,
    ready: Condvar,
}

const ASK_TIMEOUT: &str = "Pause assistant timed out. Ask again.";

fn lock_before<T>(lock: &Mutex<T>, deadline: Instant) -> Result<MutexGuard<'_, T>, String> {
    while Instant::now() < deadline {
        match lock.try_lock() {
            Ok(guard) => return Ok(guard),
            Err(std::sync::TryLockError::Poisoned(error)) => return Ok(error.into_inner()),
            Err(std::sync::TryLockError::WouldBlock) => {
                std::thread::sleep(Duration::from_millis(5).min(deadline.saturating_duration_since(Instant::now())));
            }
        }
    }
    Err(ASK_TIMEOUT.into())
}

fn valid_correlation(value: &str) -> bool {
    value.len() == 36 && value.bytes().enumerate().all(|(index, byte)| {
        match index {
            8 | 13 | 18 | 23 => byte == b'-',
            14 => byte == b'4',
            19 => matches!(byte, b'8' | b'9' | b'a' | b'b'),
            _ => byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase(),
        }
    })
}

fn once_remote(
    core: &Core,
    request: Request,
    deadline: Instant,
    correlation: Option<&str>,
    run: impl FnOnce(Request, Option<&str>) -> Result<DecisionView, String>,
) -> Result<DecisionView, String> {
    let Some(correlation) = correlation.filter(|id| valid_correlation(id)) else {
        return run(request, None);
    };
    let (entry, first) = {
        let mut asks = lock_before(&core.judge_tickets.1, deadline)?;
        asks.active.retain(|_, ask| ask.expires > Instant::now());
        if let Some(entry) = asks.active.get(correlation) {
            if entry.request.text != request.text || entry.request.check_in != request.check_in {
                return Err("Ask ID already used for a different request".into());
            }
            (entry.clone(), false)
        } else {
            if asks.active.len() >= 2 * MAX_TICKETS {
                return Err("Pause assistant is busy. Ask again shortly.".into());
            }
            asks.remember(&core.dir, correlation, deadline)?;
            let entry = Arc::new(RemoteAsk {
                expires: deadline + TICKET_AGE,
                request: request.clone(),
                result: Mutex::new(None),
                ready: Condvar::new(),
            });
            asks.active.insert(correlation.into(), entry.clone());
            (entry, true)
        }
    };
    if first {
        let result = run(request, Some(correlation));
        *entry.result.lock().unwrap() = Some(result.clone());
        entry.ready.notify_all();
        return result;
    }
    let result = lock_before(&entry.result, deadline)?;
    let (result, _) = entry.ready.wait_timeout_while(
        result, deadline.saturating_duration_since(Instant::now()), |result| result.is_none(),
    ).unwrap();
    if Instant::now() >= deadline {
        return Err(ASK_TIMEOUT.into());
    }
    result.clone().unwrap_or_else(|| Err(ASK_TIMEOUT.into()))
}

struct Ticket {
    created: Instant,
    request: Request,
    decision: Option<Decision>,
    approved_domains: Vec<String>,
    peek_target: Option<String>,
    hold: Option<Hold>,
    trace: PauseTrace,
}

struct Hold {
    id: String,
    started: Instant,
    override_full: bool,
}

impl Ticket {
    fn fallback(&self) -> bool {
        self.decision.is_none()
    }

    fn manual(&self, override_full: bool) -> bool {
        override_full || self.fallback()
    }

    fn can_hold(&self, override_full: bool) -> bool {
        self.manual(override_full)
            || self
                .decision
                .as_ref()
                .is_some_and(|d| d.action == Action::FullPause)
    }
}

#[derive(Clone, Serialize)]
pub struct DecisionView {
    id: String,
    decision: Option<Decision>,
    failure: Option<Failure>,
    scope_available: bool,
    refused: bool,
    manual_choices: Vec<u64>,
    friction: Friction,
}

#[derive(Serialize)]
pub struct HoldView {
    hold_id: String,
    hold_ms: u64,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ApplyInput {
    id: String,
    #[serde(default)]
    override_full: bool,
    manual_minutes: Option<u64>,
    hold_id: Option<String>,
    after_plan: Option<String>,
}

#[derive(Serialize)]
#[serde(tag = "action", rename_all = "snake_case")]
pub enum Applied {
    AllowServices { scoped_until: u64 },
    FullPause { state: View },
    Peek { url: String },
    CounterOffer,
    Deny,
}

fn record_history(core: &Core, event: Value) {
    if let Err(error) = core.history_line(event) {
        core.log(&format!("Pause judge history save failed: {error}"));
    }
}

fn random_id() -> Result<String, String> {
    let mut bytes = [0u8; 16];
    SystemRandom::new()
        .fill(&mut bytes)
        .map_err(|_| "Could not make a pause decision ID")?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

fn ticket<'a>(
    tickets: &'a mut BTreeMap<String, Ticket>,
    id: &str,
) -> Result<&'a mut Ticket, String> {
    tickets
        .get_mut(id)
        .filter(|t| t.created.elapsed() < TICKET_AGE)
        .ok_or_else(|| "Pause decision expired or was already used. Ask again.".into())
}

#[cfg(test)]
pub fn judge(
    core: &Arc<Core>,
    tracker: &Tracker,
    devices: &Devices,
    request: Request,
    budget: Duration,
) -> Result<DecisionView, String> {
    judge_using_gateway(core, tracker, devices, request, Instant::now() + budget,
        PauseTrace::new(false, None), Gateway::shared)
}

pub fn judge_remote(
    core: &Arc<Core>,
    tracker: &Tracker,
    devices: &Devices,
    request: Request,
    deadline: Instant,
    correlation: Option<&str>,
) -> Result<DecisionView, String> {
    once_remote(core, request, deadline, correlation, |request, correlation| {
        judge_using_gateway(core, tracker, devices, request, deadline,
            PauseTrace::new(true, correlation), Gateway::shared)
    })
}

fn judge_using_gateway<'a>(
    core: &Arc<Core>,
    tracker: &Tracker,
    devices: &Devices,
    mut request: Request,
    deadline: Instant,
    trace: PauseTrace,
    gateway: impl FnOnce() -> Result<&'a Gateway, Failure>,
) -> Result<DecisionView, String> {
    let elapsed = Instant::now();
    request.text = request.text.trim().into();
    if !(judge::MIN_REASON_CHARS..=2000).contains(&request.text.chars().count()) {
        core.event("ask", "decision", "failed", "laptop_rejected", focus_devices::events::elapsed(elapsed), trace.fields());
        return Err("Enter a reason of 3 to 2,000 characters".into());
    }
    let id = random_id()?;
    let peek_target = judge::peek_target(&request.text);
    let saved = lock_before(&core.saved, deadline)?.clone();
    let recent = Recent::read(core, &saved, devices);

    let config = Settings::load(&core.dir);
    let model = config
        .as_ref()
        .map_or(judge::DEFAULT_MODEL, |config| config.model.as_str());
    let mut attempts = Vec::new();
    let mut cause = "gateway_no_key";
    let result = (|| {
        let settings = config.as_ref().map_err(|_| Failure::Configuration)?;
        if settings.gateway_dpapi.is_some() { cause = "gateway_keystore"; }
        let key = settings.gateway_key()?;
        cause = "gateway_invalid_reply";
        let catalog = Catalog::load(&core.dir).map_err(|_| Failure::Configuration)?;
        let context = judge_context::build(
            &request,
            recent.now,
            recent.policy,
            recent.reasons,
            &recent.pauses,
            tracker,
            &catalog,
            peek_target.is_some(),
        );
        let decision = gateway()?.decide(
            &key,
            model,
            &context,
            &catalog,
            recent.policy,
            peek_target.is_some(),
            deadline.saturating_duration_since(Instant::now())
                .saturating_sub(judge::decision_commit_margin()),
            &mut attempts,
        )?;
        let domains = catalog
            .domains(&decision.services)
            .map_err(|_| Failure::Invalid)?;
        Ok((decision, domains))
    })();
    let (decision, approved_domains, failure) = match result {
        Ok((decision, domains)) => (Some(decision), domains, None),
        Err(failure) => (None, vec![], Some(failure)),
    };
    let fields = trace.fields();
    let mut gateway_fields = fields.clone();
    gateway_fields["route"] = json!("gateway");
    let (cause, status_class) = judge::gateway_diagnostic(&attempts, failure)
        .unwrap_or((match failure {
            None => "ok",
            Some(Failure::Timeout) => "gateway_timeout",
            Some(Failure::Unavailable) => "gateway_unreachable",
            _ => cause,
        }, None));
    if let Some(status_class) = status_class { gateway_fields["status_class"] = json!(status_class); }
    let refused = attempts.iter().any(judge::Attempt::refused);
    let event = json!({
        "type": "judge_decision", "t": now_ms(), "decision_id": id,
        "correlation_id": fields["correlation_id"], "origin": fields["origin"],
        "request": request.text, "check_in": request.check_in,
        "action": decision.as_ref().map_or(json!("fallback"), |d| json!(d.action)),
        "services": decision.as_ref().map_or(&[][..], |d| d.services.as_slice()),
        "minutes": decision.as_ref().map_or(0, |d| d.minutes), "model": model,
        "latency_ms": elapsed.elapsed().as_millis() as u64, "overridden": false, "failure": failure,
        "attempts_count": attempts.len(), "attempts": attempts,
    });
    let mut tickets = lock_before(&core.judge_tickets.0, deadline)?;
    tickets.retain(|_, t| t.created.elapsed() < TICKET_AGE);
    if tickets.len() >= MAX_TICKETS {
        if let Some(id) = tickets
            .iter()
            .min_by_key(|(_, t)| t.created)
            .map(|(id, _)| id.clone())
        {
            tickets.remove(&id);
        }
    }
    tickets.insert(
        id.clone(),
        Ticket {
            created: Instant::now(),
            request,
            decision: decision.clone(),
            approved_domains,
            peek_target,
            hold: None,
            trace,
        },
    );
    drop(tickets);
    let scope_available = core.dry || (core.dns_profile().is_some()
        && config.is_ok_and(|settings| settings.nextdns_dpapi.is_some()));
    let mut fields = fields;
    fields["scope_available"] = json!(scope_available);
    let missing_scope = !scope_available
        && decision.as_ref().is_some_and(|d| d.action == Action::AllowServices);
    let duration = focus_devices::events::elapsed(elapsed);
    let history_core = Arc::clone(core);
    let _ = std::thread::Builder::new()
        .name("focus-ask-history".into())
        .spawn(move || {
            history_core.event("ask", "gateway", if failure.is_none() { "ok" } else { "failed" },
                cause, duration, gateway_fields);
            history_core.event("ask", "eligibility", if missing_scope { "blocked" } else { "ok" },
                if missing_scope { "scope_key_missing" } else { "ok" }, duration, fields);
            record_history(&history_core, event);
        });
    Ok(DecisionView {
        id,
        decision,
        failure,
        refused,
        scope_available,
        manual_choices: if failure.is_some() {
            vec![1]
        } else {
            recent.policy.manual_choices()
        },
        friction: recent.policy.friction(failure.is_some()),
    })
}

pub fn begin_hold(
    core: &Core,
    devices: &Devices,
    id: &str,
    override_full: bool,
) -> Result<HoldView, String> {
    let saved = core.saved.lock().unwrap();
    if saved.paused_until > 0 || saved.scoped_pause.is_some() {
        return Err("Wait until protection is restored before starting the hold".into());
    }
    let recent = Recent::read(core, &saved, devices);
    let policy = recent.policy;
    let mut tickets = core.judge_tickets.0.lock().unwrap();
    let ticket = ticket(&mut tickets, id)?;
    if !ticket.can_hold(override_full) {
        return Err("Select the full pause override before holding".into());
    }
    let friction = ticket.friction(policy);
    ticket.check_wait(friction, recent.restored_ago)?;
    let hold_id = random_id()?;
    ticket.hold = Some(Hold {
        id: hold_id.clone(),
        started: Instant::now(),
        override_full,
    });
    Ok(HoldView {
        hold_id,
        hold_ms: friction.hold_ms,
    })
}

pub fn cancel_hold(core: &Core, id: &str, hold_id: &str) {
    let mut tickets = core.judge_tickets.0.lock().unwrap();
    if let Ok(ticket) = ticket(&mut tickets, id) {
        if ticket.hold.as_ref().is_some_and(|hold| hold.id == hold_id) {
            ticket.hold = None;
        }
    }
}

impl Ticket {
    fn friction(&self, policy: Policy) -> Friction {
        policy.friction(self.fallback())
    }

    fn check_wait(&self, friction: Friction, restored_ago: Duration) -> Result<(), String> {
        let elapsed = self.created.elapsed().min(restored_ago);
        let left = Duration::from_millis(friction.wait_ms).saturating_sub(elapsed);
        if left.is_zero() {
            Ok(())
        } else {
            Err(format!(
                "Wait {} seconds before holding",
                left.as_millis().div_ceil(1000)
            ))
        }
    }

    fn full_pause(
        &self,
        input: &ApplyInput,
        policy: Policy,
        restored_ago: Duration,
    ) -> Result<u64, String> {
        let friction = self.friction(policy);
        self.check_wait(friction, restored_ago)?;
        let hold = self.hold.as_ref().filter(|hold| {
            Some(hold.id.as_str()) == input.hold_id.as_deref()
                && hold.override_full == input.override_full
                && hold.started.elapsed() <= restored_ago
                && hold.started.elapsed() >= Duration::from_millis(friction.hold_ms)
                && hold.started.elapsed() < Duration::from_millis(friction.hold_ms + 10_000)
        });
        if hold.is_none() {
            return Err("Hold to confirm a full pause".into());
        }
        let plan_len = input
            .after_plan
            .as_deref()
            .map_or(0, |plan| plan.trim().chars().count());
        if plan_len > 300 || (friction.requires_plan && plan_len < 3) {
            return Err("Enter your plan for after the pause (3 to 300 characters)".into());
        }
        if self.fallback() {
            return Ok(1);
        }
        if self.manual(input.override_full) {
            let choice = input.manual_minutes.ok_or("Choose a pause duration")?;
            if !crate::ALLOWED_MINUTES.contains(&choice) {
                return Err("Pick 1, 5, 10 or 30 minutes".into());
            }
            policy
                .manual_choices()
                .into_iter()
                .filter(|&m| m <= choice)
                .max()
                .ok_or_else(|| "No pause duration is available".into())
        } else {
            Ok(self
                .decision
                .as_ref()
                .ok_or("Missing pause decision")?
                .minutes
                .min(policy.cap()))
        }
    }
}

pub fn apply(core: &Core, devices: &Devices, input: ApplyInput) -> Result<Applied, String> {
    apply_with_trace(core, devices, input, PauseTrace::new(false, None))
}

pub(crate) fn apply_with_trace(
    core: &Core, devices: &Devices, input: ApplyInput, mut trace: PauseTrace,
) -> Result<Applied, String> {
    let started = Instant::now();
    let mut cause = "decision_expired";
    let result = (|| {
        let mut saved = core.saved.lock().unwrap();
        let recent = Recent::read(core, &saved, devices);
        let policy = recent.policy;
        let mut tickets = core.judge_tickets.0.lock().unwrap();
        let ticket = ticket(&mut tickets, &input.id)?;
        trace = ticket.trace.clone();
        cause = "laptop_rejected";
        let model_action = ticket.decision.as_ref().map(|d| d.action);
        let action = if ticket.manual(input.override_full) {
            Action::FullPause
        } else {
            model_action.unwrap()
        };
        let mut applied_minutes = 0;
        let mut services = &[][..];
        let result = match action {
            Action::FullPause => {
                if policy.cap() == 0 && !ticket.fallback() { cause = "cap_exceeded"; }
                applied_minutes = ticket.full_pause(&input, policy, recent.restored_ago)?;
                cause = "dns_apply_failed";
                ticket.hold = None;
                let extra = json!({
                    "decision_id": input.id, "overridden": input.override_full, "fallback": ticket.fallback(),
                    "origin": trace.fields()["origin"],
                    "after_plan": input.after_plan.as_deref().map(str::trim),
                });
                core.start_pause_locked(
                    &mut saved,
                    applied_minutes,
                    ticket.request.text.clone(),
                    ticket.request.check_in,
                    Some(extra),
                )?;
                Applied::FullPause {
                    state: core.view_locked(&saved),
                }
            }
            Action::AllowServices => {
                let decision = ticket.decision.as_ref().unwrap();
                applied_minutes = decision.minutes.min(policy.cap());
                services = decision.services.as_slice();
                let domains = Catalog::load(&core.dir)?.domains(services)?;
                if domains != ticket.approved_domains {
                    return Err("The services catalog changed. Ask for a new decision.".into());
                }
                cause = "dns_apply_failed";
                let until = start_scope(
                    core,
                    &mut saved,
                    domains,
                    applied_minutes,
                    &ticket.request,
                    &input.id,
                    services,
                    &trace,
                    &mut cause,
                )?;
                Applied::AllowServices {
                    scoped_until: until,
                }
            }
            Action::Peek => {
                let url = ticket
                    .peek_target
                    .clone()
                    .ok_or("A post link is required for Peek")?;
                Applied::Peek { url }
            }
            Action::CounterOffer => Applied::CounterOffer,
            Action::Deny => Applied::Deny,
        };
        record_history(
            core,
            json!({
                "type": "judge_apply", "t": now_ms(), "decision_id": input.id,
                "origin": trace.fields()["origin"],
                "action": action, "services": services, "minutes": applied_minutes, "overridden": input.override_full,
            }),
        );
        tickets.remove(&input.id);
        Ok(result)
    })();
    core.event("pause", "apply", if result.is_ok() { "ok" } else { "failed" },
        if result.is_ok() { "ok" } else { cause }, focus_devices::events::elapsed(started), trace.fields());
    result
}

#[cfg(windows)]
fn flush_cache(core: &Core) -> Result<(), String> {
    use std::{
        ffi::OsString,
        os::windows::{ffi::OsStringExt, process::CommandExt},
        path::PathBuf,
        process::Command,
    };
    use windows_sys::Win32::System::SystemInformation::GetSystemDirectoryW;
    if core.dry {
        return Ok(());
    }
    let mut path = vec![0u16; 32_768];
    let len = unsafe { GetSystemDirectoryW(path.as_mut_ptr(), path.len() as u32) } as usize;
    if len == 0 || len >= path.len() {
        return Err("Could not find the Windows system directory".into());
    }
    let ipconfig = PathBuf::from(OsString::from_wide(&path[..len])).join("ipconfig.exe");
    let status = Command::new(ipconfig)
        .creation_flags(crate::NO_WINDOW)
        .arg("/flushdns")
        .output()
        .map_err(|_| "Could not run Windows DNS cache flush")?;
    if status.status.success() {
        Ok(())
    } else {
        Err("Windows DNS cache flush failed".into())
    }
}

#[cfg(not(windows))]
fn flush_cache(core: &Core) -> Result<(), String> {
    if core.dry {
        Ok(())
    } else {
        Err("DNS cache flush requires Windows".into())
    }
}

fn commit_state(core: &Core, saved: &mut Saved, mut new: Saved) -> Result<(), String> {
    new.scope_revision = saved.scope_revision.saturating_add(1);
    core.persist_state(&new)
        .map_err(|e| format!("Could not save scoped pause state: {e}"))?;
    *saved = new;
    Ok(())
}

fn persist_lease(core: &Core, saved: &mut Saved, value: Option<Lease>) -> Result<(), String> {
    let new = Saved {
        scoped_pause: value,
        ..saved.clone()
    };
    commit_state(core, saved, new)
}

#[allow(clippy::too_many_arguments)]
fn start_scope(
    core: &Core,
    saved: &mut Saved,
    domains: Vec<String>,
    minutes: u64,
    request: &Request,
    id: &str,
    services: &[String],
    trace: &PauseTrace,
    cause: &mut &'static str,
) -> Result<u64, String> {
    if saved.paused_until > 0 || saved.scoped_pause.is_some() {
        *cause = "dns_paused";
        return Err("End the current pause and wait for protection to restore first".into());
    }
    if domains
        .iter()
        .any(|domain| saved.pending_grants.contains_key(domain))
    {
        return Err(
            "NextDNS is still reconciling a previous grant for one of these domains".into(),
        );
    }
    if !core.dry { core.require_dns_profile()?; }
    if !matches!(core.dns("status"), Ok(ref out) if out.contains("STATUS on")) {
        core.dns("on").map_err(|_| "Could not turn NextDNS on")?;
    }
    let mut lease = Lease {
        profile: core.dns_profile().map(str::to_owned),
        started: None,
        timer: Some(nextdns::GrantTimer { id: id.to_owned(), duration_ms: minutes * MINUTE_MS }),
        services: services.to_vec(),
        until: now_ms() + minutes * MINUTE_MS,
        changes: BTreeMap::new(),
        closing: false,
        in_flight: None,
        trace: Some(trace.clone()),
    };
    let grant = (|| {
        if !core.dry {
            *cause = "scope_key_missing";
            let settings = Settings::load(&core.dir)?;
            if settings.nextdns_dpapi.is_some() { *cause = "nextdns_auth"; }
            let key = settings.nextdns_key()?;
            *cause = "dns_apply_failed";
            let dns = NextDns::new(core.require_dns_profile()?, key)?.observed(core.event_log(), Some(trace));
            let plan = dns.plan(&domains, lease.until)?;
            for (domain, prior) in plan.changes {
                if now_ms() >= lease.until {
                    *cause = "apply_timeout";
                    return Err("The scoped pause expired during setup".into());
                }
                lease.changes.insert(domain.clone(), prior);
                lease.in_flight = Some(domain.clone());
                persist_lease(core, saved, Some(lease.clone()))?;
                if let Err(error) = dns.grant(&domain, prior) {
                    if error.rejected {
                        lease.in_flight = None;
                        lease.changes.remove(&domain);
                        persist_lease(core, saved, Some(lease.clone()))?;
                    }
                    return Err(error.message);
                }
                lease.in_flight = None;
                persist_lease(core, saved, Some(lease.clone()))?;
            }
        }
        flush_cache(core)?;
        let start = now_ms();
        if start >= lease.until {
            *cause = "apply_timeout";
            return Err("The scoped pause expired during setup".into());
        }
        lease.started = Some(start);
        persist_lease(core, saved, Some(lease.clone()))?;
        Ok(start)
    })();
    match grant {
        Ok(start) => {
            record_history(
                core,
                json!({
                    "type": "scoped_pause", "t": start, "until": lease.until, "reason": request.text,
                    "check_in": request.check_in, "minutes": minutes, "services": services, "decision_id": id,
                }),
            );
            Ok(lease.until)
        }
        Err(error) => {
            if let Err(cleanup) = sweep_scope(core, saved, true) {
                core.log(&format!("Scoped pause cleanup pending: {cleanup}"));
            }
            Err(error)
        }
    }
}

pub fn sweep_scope(core: &Core, saved: &mut Saved, early: bool) -> Result<bool, String> {
    let Some(lease) = saved.scoped_pause.clone() else {
        return Ok(false);
    };
    let started = Instant::now();
    let cleaned = nextdns::sweep(
        &lease,
        now_ms(),
        early,
        |updated| match updated {
            nextdns::JournalUpdate::Lease(lease) => persist_lease(core, saved, lease),
            nextdns::JournalUpdate::Park { domain, prior } => {
                let mut new = Saved {
                    scoped_pause: None,
                    ..saved.clone()
                };
                new.pending_grants.insert(
                    domain,
                    nextdns::PendingGrant {
                        profile: lease.profile.clone(),
                        prior,
                        observed: false,
                        restored: false,
                        trace: lease.trace.clone(),
                    },
                );
                commit_state(core, saved, new)
            }
        },
        |domain| {
            if core.dry {
                Ok(false)
            } else {
                with_cleanup_api(core, lease.profile.as_deref(), lease.trace.as_ref(), |api| api.is_active(domain))
            }
        },
        |domain, prior| {
            if core.dry {
                return Ok(());
            }
            with_cleanup_api(core, lease.profile.as_deref(), lease.trace.as_ref(), |api| api.restore_entry(domain, prior))
        },
        || flush_cache(core),
    );
    if !matches!(cleaned, Ok(false)) {
        core.event("nextdns", "cleanup", if cleaned.is_ok() { "ok" } else { "failed" },
            if cleaned.is_ok() { "ok" } else { "dns_apply_failed" },
            focus_devices::events::elapsed(started),
            lease.trace.as_ref().map_or_else(|| json!({}), PauseTrace::fields));
    }
    let cleaned = cleaned?;
    if cleaned {
        discard_cleanup_keys(core, saved);
        if let Some(start) = lease.started {
            record_history(
                core,
                json!({ "type": "scoped_resume", "t": now_ms(), "paused_at": start, "scheduled_until": lease.until }),
            );
        }
    }
    Ok(cleaned)
}

pub fn sweep_pending(core: &Core) {
    if core.dry {
        return;
    }
    let pending_grants = core.saved.lock().unwrap().pending_grants.clone();
    if pending_grants.is_empty() {
        return;
    }
    let mut current_by_profile = BTreeMap::new();
    for (domain, pending) in pending_grants {
        let started = Instant::now();
        let trace = pending.trace.clone();
        let profile = pending.profile.clone();
        let current = (!pending.observed).then(|| {
            current_by_profile.entry(profile.clone()).or_insert_with(|| {
                with_cleanup_api_outcome(core, profile.as_deref(), None, NextDns::current)
            })
        });
        let mut cause = "dns_apply_failed";
        let result = nextdns::reconcile_pending(
            &domain,
            pending,
            |domain| match &current {
                Some(Ok(entries)) => Ok(entries.get(domain) == Some(&true)),
                Some(Err((error, recorded_cause))) => {
                    cause = recorded_cause;
                    Err(error.clone())
                }
                None => Ok(false),
            },
            |updated| {
                let mut saved = core.saved.lock().unwrap();
                let mut new = saved.clone();
                match updated {
                    Some(pending) => {
                        new.pending_grants.insert(domain.clone(), pending);
                    }
                    None => {
                        new.pending_grants.remove(&domain);
                    }
                }
                commit_state(core, &mut saved, new)
            },
            |domain, prior| with_cleanup_api(core, profile.as_deref(), trace.as_ref(), |api| api.restore_entry(domain, prior)),
            || flush_cache(core),
        );
        if !matches!(result, Ok(false)) {
            core.event("nextdns", "reconcile", if result.is_ok() { "ok" } else { "failed" },
                if result.is_ok() { "ok" } else { cause },
                focus_devices::events::elapsed(started),
                trace.as_ref().map_or_else(|| json!({}), PauseTrace::fields));
        }
        if let Err(error) = result {
            core.log(&format!("NextDNS grant reconciliation pending: {error}"));
        }
    }
    discard_cleanup_keys(core, &core.saved.lock().unwrap());
}

#[tauri::command(async)]
pub fn pause_judge_settings(core: State<Arc<Core>>) -> Result<SettingsView, String> {
    Settings::load(&core.dir).map(|settings| settings.view())
}

#[tauri::command]
pub async fn pause_judge_settings_save(
    update: SettingsUpdate,
    core: State<'_, Arc<Core>>,
) -> Result<SettingsView, String> {
    let core = core.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        if let Some(key) = update
            .nextdns_key
            .as_deref()
            .map(str::trim)
            .filter(|key| !key.is_empty())
        {
            if !core.dry {
                NextDns::new(core.require_dns_profile()?, key.into())?.observed(core.event_log(), None).verify_key()?;
            }
        }
        let saved = core.saved.lock().unwrap();
        let mut settings = Settings::load(&core.dir)?;
        if let Some(model) = update.model {
            let model = model.trim();
            let (provider, id) = model
                .split_once('/')
                .ok_or("Enter a Vercel Gateway model as provider/model")?;
            let valid = |part: &str| {
                !part.is_empty()
                    && part
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'))
            };
            if model.len() > 100 || !valid(provider) || !valid(id) {
                return Err("Enter a Vercel Gateway model as provider/model".into());
            }
            settings.model = model.into();
        }
        if let Some(key) = update.gateway_key {
            settings.set_gateway_key(protect_key(&key)?);
        }
        if let Some(key) = update.nextdns_key {
            let protected = protect_key(&key)?;
            let cleaning = saved.scoped_pause.is_some() || !saved.pending_grants.is_empty();
            if cleaning {
                if protected.is_none() {
                    return Err("The NextDNS key is required until scoped cleanup finishes".into());
                }
                if let Some(old) = settings.nextdns_dpapi.take() {
                    settings.cleanup_dpapi.push(old);
                }
            } else {
                settings.cleanup_dpapi.clear();
            }
            settings.nextdns_dpapi = protected;
        }
        write_json(&core.dir.join(SETTINGS), &settings)
            .map_err(|e| format!("Could not save pause judge settings: {e}"))?;
        Ok(settings.view())
    })
    .await
    .map_err(|_| "Pause judge settings task stopped")?
}

#[tauri::command(async)]
pub fn pause_judge_limits_sync(limits: Limits, core: State<Arc<Core>>) -> Result<(), String> {
    // The file is replaced atomically. Serialize writers without waiting for DNS transitions.
    static LIMIT_WRITERS: Mutex<()> = Mutex::new(());
    let _writer = LIMIT_WRITERS.lock().unwrap();
    write_json(&core.dir.join(LIMITS), &limits)
}

#[tauri::command]
pub async fn judge_pause(
    request: Request,
    core: State<'_, Arc<Core>>,
    tracker: State<'_, Arc<Tracker>>,
    devices: State<'_, Arc<Devices>>,
) -> Result<DecisionView, String> {
    let (core, tracker, devices) = (
        core.inner().clone(),
        tracker.inner().clone(),
        devices.inner().clone(),
    );
    let budget = judge::ask_budget(None, false);
    let deadline = Instant::now() + budget;
    let task = tauri::async_runtime::spawn_blocking(move || {
        judge_using_gateway(&core, &tracker, &devices, request, deadline,
            PauseTrace::new(false, None), Gateway::shared)
    });
    tokio::time::timeout(budget, task).await
        .map_err(|_| ASK_TIMEOUT.to_owned())?
        .map_err(|_| "Pause judge task stopped")?
}

#[tauri::command(async)]
pub fn begin_pause_hold(
    id: String,
    override_full: bool,
    core: State<Arc<Core>>,
    devices: State<Arc<Devices>>,
) -> Result<HoldView, String> {
    begin_hold(&core, &devices, &id, override_full)
}

#[tauri::command(async)]
pub fn cancel_pause_hold(id: String, hold_id: String, core: State<Arc<Core>>) {
    cancel_hold(&core, &id, &hold_id);
}

#[tauri::command]
pub async fn apply_decision(
    input: ApplyInput,
    app: AppHandle,
    core: State<'_, Arc<Core>>,
    devices: State<'_, Arc<Devices>>,
    tracker: State<'_, Arc<Tracker>>,
    host: State<'_, Arc<crate::device_commands::LaptopHost>>,
) -> Result<Applied, String> {
    let (core, devices) = (core.inner().clone(), devices.inner().clone());
    let (tracker, host) = (tracker.inner().clone(), host.inner().clone());
    tauri::async_runtime::spawn_blocking(move || {
        let result = apply(&core, &devices, input);
        if matches!(result, Ok(Applied::FullPause { .. })) {
            core.publish(&app);
            crate::device_commands::trigger_phone_sync_async(app, devices, core, tracker, host);
        } else {
            let _ = app.emit("state", core.view());
        }
        result
    })
    .await
    .map_err(|_| "Applying the pause decision stopped")?
}

#[cfg(test)]
mod tests {
    use super::*;

    fn policy(count: usize, used: u64) -> Policy {
        Limits::default().policy(1000, count, used * MINUTE_MS, 60)
    }

    #[test]
    fn ask_times_out_while_dns_state_is_locked() {
        let fixture = crate::dns_tests::Fixture::new(|_| Ok(String::new()));
        let core = &fixture.core;
        let tracker = Tracker::new(&core.dir);
        let devices = Devices(Err("test".into()));
        let _dns_transition = core.saved.lock().unwrap();
        let started = Instant::now();
        let error = judge_using_gateway(
            core, &tracker, &devices,
            Request { text: "Check a post".into(), check_in: None },
            started + Duration::from_millis(45),
            PauseTrace::new(false, None),
            || panic!("Request should expire before calling the Gateway"),
        ).err();
        assert_eq!(error.as_deref(), Some(ASK_TIMEOUT));
        assert!(started.elapsed() < Duration::from_secs(1));
        assert!(core.judge_tickets.0.lock().unwrap().is_empty());
    }

    #[test]
    fn invalid_then_valid_records_one_decision_and_creates_one_ticket() {
        use crate::mock_http::{MockHttp, Reply};
        let fixture = crate::dns_tests::Fixture::new(|_| Ok(String::new()));
        let core = &fixture.core;
        let key = focus_devices::secrets::protect("mock-key").unwrap();
        let settings = Settings { gateway_dpapi: Some(key), ..Settings::default() };
        write_json(&core.dir.join(SETTINGS), &settings).unwrap();
        let sample = json!({
            "action": "allow_services", "services": ["x"], "minutes": 3,
            "message": "Check the post.", "counter_offer": null,
        });
        let completion = |sample: &Value| json!({
            "choices": [{"finish_reason": "stop", "message": {"content": sample.to_string()}}]
        });
        let mut invalid = sample.clone();
        invalid["services"] = json!([]);
        let mock = MockHttp::start(vec![
            Reply::new(200, completion(&invalid)),
            Reply::new(200, completion(&sample)),
        ]);
        let gateway = Gateway::at(&mock.url, Duration::from_secs(2), Duration::from_secs(2)).unwrap();
        let tracker = Tracker::new(&core.dir);
        let devices = Devices(Err("test".into()));
        let request = Request { text: "Check a post".into(), check_in: None };
        let deadline = Instant::now() + Duration::from_secs(3);
        let correlation = "00000000-0000-4000-8000-000000000001";
        let ask = || once_remote(
            core, request.clone(), deadline, Some(correlation),
            |request, correlation| judge_using_gateway(
                core, &tracker, &devices, request, deadline, PauseTrace::new(true, correlation), || Ok(&gateway),
            ),
        ).unwrap();
        let reply = std::thread::scope(|scope| {
            let one = scope.spawn(&ask);
            let two = ask();
            assert_eq!(one.join().unwrap().id, two.id);
            two
        });
        assert_eq!(mock.requests().len(), 2);
        assert_eq!(reply.decision.unwrap().minutes, 3);
        let tickets = core.judge_tickets.0.lock().unwrap();
        assert_eq!(tickets.len(), 1);
        assert!(tickets.contains_key(&reply.id));
        let log_deadline = Instant::now() + Duration::from_secs(2);
        let history = loop {
            if let Ok(history) = fs::read_to_string(core.dir.join("pauses.jsonl")) {
                if history.contains(correlation) {
                    break history;
                }
            }
            assert!(Instant::now() < log_deadline, "Judge history write did not finish");
            std::thread::sleep(Duration::from_millis(10));
        };
        let events: Vec<Value> = history.lines().map(|line| serde_json::from_str(line).unwrap())
            .filter(|event: &Value| event["type"] == "judge_decision").collect();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0]["decision_id"], reply.id);
        assert_eq!(events[0]["correlation_id"], correlation);
        assert_eq!(events[0]["attempts_count"], 2);
        assert_eq!(events[0]["attempts"][0]["failure"], "services_empty");

        // Lose the in-memory cache as after expiry or a restart.
        *core.judge_tickets.1.lock().unwrap() = RemoteAsks::default();
        let replay = once_remote(core, request, Instant::now() + Duration::from_secs(2), Some(correlation),
            |_, _| panic!("An expired Ask ID must not mint another ticket"));
        assert_eq!(replay.err().as_deref(), Some("Pause decision expired or was already used. Ask again."));
    }

    #[test]
    fn judge_policy_uses_current_desktop_allowance_and_habit_credit() {
        use crate::dns_tests::Fixture;
        use focus_dns::habits::{local_clock, DesktopLimit, Event};

        let fixture = Fixture::new(|_| panic!("Reading the budget must not change DNS"));
        let core = &fixture.core;
        core.habits
            .set_desktop_limit(DesktopLimit {
                minutes: 1,
                pending: None,
            })
            .unwrap();
        let devices = Devices(Err("No paired devices".into()));
        let saved = Saved::default();
        assert_eq!(Recent::read(core, &saved, &devices).policy.cap(), 1);

        let now = now_ms();
        let day = local_clock(now).0;
        core.habits
            .update(|ledger| {
                ledger.events.insert(
                    "done".into(),
                    Event {
                        id: "done".into(),
                        habit: "walk".into(),
                        at: now,
                        day,
                        period: day,
                        reward: 5,
                        away: None,
                        undo: None,
                    },
                );
                Ok(())
            })
            .unwrap();
        let policy = Recent::read(core, &saved, &devices).policy;
        assert_eq!(policy.cap(), 6);
        assert_eq!(policy.manual_choices(), vec![1, 5]);

        core.habits
            .update(|ledger| {
                ledger.undo("done", "undone".into(), now + 1);
                Ok(())
            })
            .unwrap();
        assert_eq!(Recent::read(core, &saved, &devices).policy.cap(), 1);
    }

    fn ticket_with(decision: Option<Decision>, age_ms: u64) -> Ticket {
        Ticket {
            created: Instant::now() - Duration::from_millis(age_ms),
            request: Request {
                text: "A synthetic reason".into(),
                check_in: None,
            },
            decision,
            approved_domains: vec![],
            peek_target: None,
            hold: None,
            trace: PauseTrace::new(false, None),
        }
    }

    #[test]
    fn fallback_requires_wait_plan_and_full_hold_even_for_the_first_pause() {
        let mut ticket = ticket_with(None, 0);
        let mut input = ApplyInput {
            id: "ticket".into(),
            override_full: false,
            manual_minutes: Some(30),
            hold_id: Some("held".into()),
            after_plan: None,
        };
        let policy = policy(0, 54);
        assert_eq!(
            ticket.friction(policy),
            Friction {
                wait_ms: 15_000,
                hold_ms: 3000,
                requires_plan: true
            }
        );
        assert!(ticket
            .full_pause(&input, policy, Duration::MAX)
            .unwrap_err()
            .starts_with("Wait"));
        ticket.created -= Duration::from_secs(16);
        assert!(ticket
            .full_pause(&input, policy, Duration::MAX)
            .unwrap_err()
            .starts_with("Hold"));
        ticket.hold = Some(Hold {
            id: "held".into(),
            started: Instant::now(),
            override_full: false,
        });
        assert!(ticket
            .full_pause(&input, policy, Duration::MAX)
            .unwrap_err()
            .starts_with("Hold"));
        ticket.hold.as_mut().unwrap().started -= Duration::from_secs(3);
        assert!(ticket
            .full_pause(&input, policy, Duration::MAX)
            .unwrap_err()
            .starts_with("Enter your plan"));
        input.after_plan = Some("Return to the draft".into());
        assert_eq!(ticket.full_pause(&input, policy, Duration::MAX).unwrap(), 1);
        assert!(ticket
            .full_pause(&input, policy, Duration::from_secs(2))
            .unwrap_err()
            .starts_with("Wait"));
    }

    #[test]
    fn denying_still_allows_an_explicit_held_override() {
        let decision = Decision {
            action: Action::Deny,
            services: vec![],
            minutes: 0,
            message: "Try the next step".into(),
            counter_offer: None,
        };
        let mut ticket = ticket_with(Some(decision), 2500);
        assert!(!ticket.can_hold(false));
        assert!(ticket.can_hold(true));
        ticket.hold = Some(Hold {
            id: "override".into(),
            started: Instant::now() - Duration::from_millis(2200),
            override_full: true,
        });
        let input = ApplyInput {
            id: "ticket".into(),
            override_full: true,
            manual_minutes: Some(10),
            hold_id: Some("override".into()),
            after_plan: None,
        };
        assert_eq!(
            ticket
                .full_pause(&input, policy(0, 0), Duration::MAX)
                .unwrap(),
            10
        );
        assert!(ticket
            .full_pause(&input, policy(4, 0), Duration::MAX)
            .is_err());
    }

    #[test]
    fn removing_gateway_key_keeps_manual_fallback_enabled() {
        let mut settings: Settings =
            serde_json::from_value(json!({"gateway_dpapi":"fixture"})).unwrap();
        settings.set_gateway_key(None);
        let reloaded: Settings = serde_json::from_value(json!(settings)).unwrap();
        assert!(reloaded.enabled());
        assert_eq!(reloaded.gateway_key().unwrap_err(), Failure::Configuration);
        assert!(!Settings::default().enabled());
    }

    #[test]
    fn scheduled_limit_loosening_applies_only_when_due() {
        let mut limits = Limits::default();
        limits.max_pause_min = 5;
        limits.pending.insert(
            "maxPauseMin".into(),
            PendingLimit {
                value: 30,
                from: 5000,
            },
        );
        assert_eq!(limits.policy(4999, 0, 0, 60).cap(), 5);
        assert_eq!(limits.policy(5000, 0, 0, 60).cap(), 30);
    }
}
