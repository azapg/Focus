//! Desktop adapter for live input, transition logging, and event delivery.

use crate::{now_ms, Core};
use focus_browser_host::FeedLock;
use focus_dns::{
    activity::{BrowserContext, Span, Tracker},
    activity_rules,
    browser_extension::{BrowserSession, BrowserSessions},
    focus_state::{
        classifier::Classifier, history, Activity, Config, Detector, Signals, State, Transition,
        MAX_ESCALATION_IDLE_MS, TICK_MS,
    },
    habit_nudge::{Notice, Nudges, Sample},
};
use focus_telemetry as telemetry;
use std::{
    collections::VecDeque,
    fs::OpenOptions,
    io::Write,
    path::Path,
    sync::{mpsc, Arc},
    thread,
    time::Instant,
};
use tauri::{AppHandle, Emitter};

const LOCK_SETTING: &str = "habit-feed-lock-enabled";
const LOCK_LEASE: &str = "habit-feed-lock.json";
const COVER_REQUEST_MS: u64 = 8_000;
const COVER_LOCK_MS: u64 = 35_000;
const COVER_RENEW_BEFORE_MS: u64 = 20_000;
const LAST_NUDGE: &str = "habit-last-nudge";

fn lock_enabled(core: &Core) -> bool {
    std::fs::read(core.dir.join(LOCK_SETTING)).is_ok_and(|value| value == b"1")
}

#[tauri::command]
pub fn habits_feed_lock(enabled: Option<bool>, core: tauri::State<'_, Arc<Core>>) -> Result<serde_json::Value, String> {
    if let Some(enabled) = enabled {
        focus_browser_host::atomic_write(&core.dir.join(LOCK_SETTING), if enabled { b"1" } else { b"0" })
            .map_err(|error| error.to_string())?;
    }
    let enabled = lock_enabled(&core);
    Ok(serde_json::json!({
        "enabled": enabled,
        "browser_ready": enabled && BrowserSessions::load(&core.dir).ready(),
        "installed": core.dir.join("browser-bridge/extension/manifest.json").is_file(),
    }))
}

#[derive(Default)]
struct HabitNudges {
    policy: Nudges,
    unfocused: bool,
    notice: Option<Notice>,
    lock: Option<FeedLock>,
}

impl HabitNudges {
    fn new(core: &Core) -> Self {
        let mut runtime = Self::default();
        runtime.policy.last_sent = std::fs::read_to_string(core.dir.join(LAST_NUDGE))
            .ok().and_then(|text| text.parse().ok()).unwrap_or(0);
        let _ = focus_browser_host::atomic_write(&core.dir.join(LOCK_LEASE), b"null");
        runtime
    }

    fn update(&mut self, app: &AppHandle, core: &Core, now: u64, transition: Option<&Transition>,
        consuming_browser: Option<&BrowserContext>) {
        let entered = transition.is_some_and(|e| e.to == Some(State::Unfocused));
        if let Some(event) = transition {
            self.unfocused = event.to == Some(State::Unfocused);
        }
        if !entered && !self.policy.active() && self.notice.is_none() && self.lock.is_none() {
            return;
        }
        let (day, minute) = focus_dns::habits::local_clock(now);
        let ledger = core.habits.read();
        let rows = ledger.status(day, minute);
        let completed = rows.iter().filter_map(|row| row.completion.map(|e| e.id.clone())).collect();
        let old_sent = self.policy.last_sent;
        let sessions = lock_enabled(core).then(|| BrowserSessions::load(&core.dir));
        let candidate = consuming_browser.zip(sessions.as_ref()).and_then(|(browser, sessions)| {
            let session = BrowserSession { browser_pid: browser.browser_pid, host_pid: browser.host_pid };
            if !sessions.can_cover(session, browser.tab_id, browser.window_id, browser.url.as_deref()) {
                return None;
            }
            Some(FeedLock {
                id: now, until: now + COVER_REQUEST_MS, browser_pid: browser.browser_pid, host_pid: browser.host_pid,
                tab_id: browser.tab_id?, window_id: browser.window_id?, url: browser.url.clone()?,
            })
        });
        let same_request = self.lock.as_ref().zip(candidate.as_ref())
            .is_some_and(|(lock, candidate)| now < lock.until && lock.same_page(candidate));
        let acknowledged = self.lock.as_ref().zip(sessions.as_ref())
            .is_some_and(|(lock, sessions)| sessions.covered(lock));
        let enabled = sessions.as_ref().is_some_and(|sessions| {
            self.lock.as_ref().is_none_or(|lock| now < lock.until && sessions.has_cover(lock))
        });
        let next = self.policy.step(Sample {
            now, day, entered_unfocused: entered, still_unfocused: self.unfocused,
            cover_confirmed: same_request && acknowledged,
            lock_enabled: enabled,
            due: rows.iter().filter(|row| row.due).count(),
            completed: &completed,
        });
        if old_sent != self.policy.last_sent {
            let _ = focus_browser_host::atomic_write(&core.dir.join(LAST_NUDGE), now.to_string().as_bytes());
        }
        // Only the browser's confirmation turns a short request into a renewable cover.
        let lease = if next == Some(Notice::Locked) {
            self.lock.clone().map(|mut lock| {
                if lock.until.saturating_sub(now) <= COVER_RENEW_BEFORE_MS {
                    lock.until = now + COVER_LOCK_MS;
                }
                lock
            })
        } else if self.policy.active() && same_request {
            self.lock.clone()
        } else {
            candidate.filter(|_| self.policy.request_cover(now))
        };
        if lease != self.lock && serde_json::to_vec(&lease).is_ok_and(|bytes| {
            focus_browser_host::atomic_write(&core.dir.join(LOCK_LEASE), &bytes).is_ok()
        }) {
            self.lock = lease;
        }
        let visible = next.filter(|notice| *notice != Notice::Locked || self.lock.as_ref().is_some_and(|lock| {
            now < lock.until && acknowledged
        }));
        if visible != self.notice {
            self.notice = visible;
            let _ = app.emit("habits-nudge", visible.map(Notice::text));
        }
    }
}

struct Inputs {
    rules: history::CachedInput,
    pauses: history::CachedInput,
    devices: history::CachedInput,
    panels: history::CachedInput,
    pause_restarts: Vec<u64>,
    pickups: Option<Vec<u64>>,
    panel_times: Vec<u64>,
}

impl Inputs {
    fn new() -> Self {
        Self {
            rules: Default::default(),
            pauses: Default::default(),
            devices: Default::default(),
            panels: Default::default(),
            pause_restarts: Vec::new(),
            pickups: None,
            panel_times: Vec::new(),
        }
    }

    fn refresh(&mut self, dir: &Path, config: &Config, classifier: &mut Classifier) -> bool {
        if let Ok(Some(text)) = self.pauses.refresh(&dir.join("pauses.jsonl")) {
            self.pause_restarts = history::pause_restarts(&text, config);
        }
        if let Ok(Some(text)) = self.devices.refresh(&dir.join("devices.json")) {
            self.pickups = serde_json::from_str(&text)
                .ok()
                .as_ref()
                .and_then(history::inferred_pickups);
        }
        if let Ok(Some(text)) = self.panels.refresh(&dir.join(history::PANEL_LOG)) {
            self.panel_times = history::panel_timestamps(&text);
        }
        if matches!(self.rules.refresh(&dir.join("rules.json")), Ok(Some(_))) {
            if let Ok(rules) =
                activity_rules::load_rules(dir).and_then(|rules| Classifier::new(&rules))
            {
                *classifier = rules;
                return true;
            }
        }
        false
    }
}

pub fn record_panel_open(core: &Core) {
    let result = OpenOptions::new()
        .create(true)
        .append(true)
        .open(core.dir.join(history::PANEL_LOG))
        .and_then(|mut file| file.write_all(format!("{}\n", now_ms()).as_bytes()));
    if let Err(error) = result {
        core.log(&format!(
            "focus detector could not record panel open: {error}"
        ));
    }
}

struct Observation {
    now: u64,
    closed: Option<Span>,
    current: Option<Span>,
    idle_ms: Option<u64>,
    missed: bool,
}

struct ClosedSpan {
    span: Span,
    classified: Option<Result<Activity, String>>,
}

pub fn run(app: AppHandle, core: Arc<Core>, tracker: Arc<Tracker>) {
    let (sender, receiver) = mpsc::sync_channel(16);
    thread::spawn(move || detect(app, core, receiver));
    let mut missed = false;
    tracker.run(now_ms, |now, closed, current, idle_ms| {
        // Keep foreground sampling and the browser heartbeat independent of
        // classifier startup, local history reads, and detector evaluation.
        missed = sender
            .try_send(Observation {
                now,
                closed,
                current,
                idle_ms,
                missed,
            })
            .is_err();
    });
}

fn emit(app: &AppHandle, core: &Core, event: Transition) {
    if let Ok(payload) = serde_json::to_string(&event) {
        core.log(&format!("focus-state-transition {payload}"));
    }
    let _ = app.emit("focus-state-transition", event);
}

fn detect(app: AppHandle, core: Arc<Core>, receiver: mpsc::Receiver<Observation>) {
    let config = Config::load(&core.dir).unwrap_or_else(|error| {
        core.log(&format!("focus detector using default thresholds: {error}"));
        Config::default()
    });
    let initial_rules = activity_rules::load_rules(&core.dir).unwrap_or_else(|error| {
        core.log(&format!(
            "focus detector using default activity rules: {error}"
        ));
        activity_rules::default_rules()
    });
    let mut classifier = match Classifier::new(&initial_rules) {
        Ok(classifier) => classifier,
        Err(error) => {
            core.log(&format!(
                "focus detector could not initialize activity classification: {error}"
            ));
            return;
        }
    };
    let mut inputs = Inputs::new();
    let _ = inputs.rules.refresh(&core.dir.join("rules.json"));
    let mut detector = Detector::new(config.clone());
    let mut habit_nudges = HabitNudges::new(&core);
    let mut closed_spans: VecDeque<ClosedSpan> = VecDeque::new();
    let mut last_eval = 0;
    let mut last_observation = 0;
    let mut evidence_start = 0;
    let mut logged_classification_error = false;

    for Observation {
        now,
        closed,
        current,
        idle_ms,
        missed,
    } in receiver
    {
        if missed || now < last_observation || now.saturating_sub(last_observation) > 2 * TICK_MS {
            closed_spans.clear();
            evidence_start = now;
            if let Some(event) = detector.interrupt() {
                habit_nudges.update(&app, &core, now, Some(&event), None);
                emit(&app, &core, event);
            }
            last_eval = 0;
        } else if let Some(span) = closed.filter(|span| span.e > span.s) {
            closed_spans.push_back(ClosedSpan {
                span,
                classified: None,
            });
        }
        last_observation = now;
        let cutoff = now.saturating_sub(config.lookback_ms());
        while closed_spans
            .front()
            .is_some_and(|entry| entry.span.e <= cutoff)
        {
            closed_spans.pop_front();
        }
        // Catch up on queued foreground changes without emitting stale state.
        if now.saturating_sub(last_eval) < TICK_MS || now_ms().saturating_sub(now) > TICK_MS {
            continue;
        }
        last_eval = now;
        let evaluation = Instant::now();
        telemetry::FOCUS_EVAL.count(1);
        if inputs.refresh(&core.dir, &config, &mut classifier) {
            for entry in &mut closed_spans {
                entry.classified = None;
            }
        }

        let mut spans = Vec::with_capacity(closed_spans.len() + 1);
        let mut add = |result: &Result<Activity, String>| match result {
            Ok(activity) if activity.end >= evidence_start => {
                let mut activity = activity.clone();
                activity.start = activity.start.max(evidence_start);
                spans.push(activity);
            }
            Ok(_) => {}
            Err(error) if !logged_classification_error => {
                core.log(&format!(
                    "focus detector could not classify activity: {error}"
                ));
                logged_classification_error = true;
            }
            Err(_) => {}
        };
        for entry in &mut closed_spans {
            add(entry
                .classified
                .get_or_insert_with(|| classifier.classify(&entry.span)));
        }
        let input_idle =
            current.is_none() || idle_ms.is_some_and(|idle| idle > MAX_ESCALATION_IDLE_MS);
        let browser = current.as_ref().and_then(|span| span.browser.as_ref());
        if let Some(current) = &current {
            add(&classifier.classify(current));
        }
        let mut signals = Signals::measure(
            &config,
            now,
            &spans,
            &inputs.panel_times,
            inputs.pickups.as_deref(),
            &inputs.pause_restarts,
        );
        signals.input_idle = input_idle;
        if now_ms().saturating_sub(now) <= TICK_MS {
            let transition = detector.step(now, &signals);
            let consuming_browser = browser.filter(|_| signals.current_consuming && !input_idle);
            habit_nudges.update(&app, &core, now, transition.as_ref(), consuming_browser);
            if let Some(event) = transition {
                emit(&app, &core, event);
            }
        }
        telemetry::FOCUS_EVAL_US.count(evaluation.elapsed().as_micros() as u64);
    }
}
