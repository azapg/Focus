//! Rolling laptop signals and the focus state machine. Live tracking and replay use the same inputs.

pub mod classifier;
pub mod history;

use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, fs, path::Path};

pub const TICK_MS: u64 = 5_000;
const SWITCH_GAP_MS: u64 = 5_000;
const ACTIVE_GAP_MS: u64 = 15_000;
pub const MAX_ESCALATION_IDLE_MS: u64 = 15_000;
const MAX_CONSUMING_GAP_MS: u64 = 15_000;
const SECOND: u64 = 1_000;

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(default, deny_unknown_fields)]
pub struct Config {
    pub short_window_seconds: u64,
    pub long_window_seconds: u64,
    pub pause_window_seconds: u64,
    pub pause_restart_seconds: u64,
    pub minimum_active_seconds: u64,
    pub switches: usize,
    pub short_dwell_seconds: u64,
    pub panel_opens: usize,
    pub phone_pickups: usize,
    pub pause_restarts: usize,
    pub drift_consuming_share: f64,
    pub drift_feed_share: f64,
    pub unfocused_consuming_share: f64,
    pub unfocused_feed_share: f64,
    pub recovery_consuming_share: f64,
    pub drift_hold_seconds: u64,
    pub unfocused_hold_seconds: u64,
    pub recovery_hold_seconds: u64,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            short_window_seconds: 180,
            long_window_seconds: 600,
            pause_window_seconds: 2700,
            pause_restart_seconds: 120,
            minimum_active_seconds: 120,
            switches: 12,
            short_dwell_seconds: 20,
            panel_opens: 4,
            phone_pickups: 2,
            pause_restarts: 1,
            drift_consuming_share: 0.45,
            drift_feed_share: 0.20,
            unfocused_consuming_share: 0.65,
            unfocused_feed_share: 0.35,
            recovery_consuming_share: 0.30,
            drift_hold_seconds: 45,
            unfocused_hold_seconds: 75,
            recovery_hold_seconds: 90,
        }
    }
}

impl Config {
    pub fn load(dir: &Path) -> Result<Self, String> {
        let text = match fs::read_to_string(dir.join("focus-state.json")) {
            Ok(text) => text,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Self::default()),
            Err(e) => return Err(format!("could not read focus-state.json: {e}")),
        };
        let config: Self =
            serde_json::from_str(&text).map_err(|e| format!("invalid focus-state.json: {e}"))?;
        config.validate()?;
        Ok(config)
    }

    pub fn lookback_ms(&self) -> u64 {
        self.long_window_seconds * SECOND
    }

    fn validate(&self) -> Result<(), String> {
        let valid = (10..=900).contains(&self.short_window_seconds)
            && (self.short_window_seconds..=1800).contains(&self.long_window_seconds)
            && (5..=7200).contains(&self.pause_window_seconds)
            && (1..=self.pause_window_seconds).contains(&self.pause_restart_seconds)
            && (5..=self.short_window_seconds).contains(&self.minimum_active_seconds)
            && (1..=self.short_window_seconds).contains(&self.short_dwell_seconds)
            && [
                self.switches,
                self.panel_opens,
                self.phone_pickups,
                self.pause_restarts,
            ]
            .iter()
            .all(|x| *x > 0)
            && [
                self.drift_hold_seconds,
                self.unfocused_hold_seconds,
                self.recovery_hold_seconds,
            ]
            .iter()
            .all(|x| (5..=1800).contains(x))
            && (0.0..self.drift_consuming_share).contains(&self.recovery_consuming_share)
            && (self.drift_consuming_share..=1.0).contains(&self.unfocused_consuming_share)
            && (0.0..self.unfocused_feed_share).contains(&self.drift_feed_share)
            && self.drift_feed_share > 0.0
            && self.unfocused_feed_share <= 1.0;
        valid
            .then_some(())
            .ok_or_else(|| "focus-state.json has incompatible or out-of-range thresholds".into())
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Creating,
    Consuming,
    Browsing,
    Feed,
    Neutral,
    Ignored,
}

impl Kind {
    fn confirmed_consumption(self) -> bool {
        matches!(self, Self::Consuming | Self::Feed)
    }
}

#[derive(Clone, Debug)]
pub struct Activity {
    pub start: u64,
    pub end: u64,
    pub identity: String,
    pub kind: Kind,
}

#[derive(Default, Debug)]
pub struct Window {
    pub active_ms: u64,
    pub switches: usize,
    pub switches_per_minute: f64,
    pub median_dwell_ms: u64,
    pub median_dwell_by_activity_ms: BTreeMap<String, u64>,
    pub creating_share: f64,
    pub consuming_share: f64,
    pub confirmed_consuming_share: f64,
    pub feed_share: f64,
}

fn median(samples: &mut [u64]) -> u64 {
    samples.sort_unstable();
    let mid = samples.len() / 2;
    match samples.len() {
        0 => 0,
        len if len % 2 == 0 => (samples[mid - 1] + samples[mid]) / 2,
        _ => samples[mid],
    }
}

impl Window {
    fn measure(spans: &[Activity], from: u64, to: u64) -> Self {
        let mut metrics = Self::default();
        let mut consuming_ms = 0;
        let mut browsing_ms = 0;
        let mut creating_ms = 0;
        let mut feed_ms = 0;
        let mut visits: Vec<(&str, u64)> = Vec::new();
        let mut previous_end = 0;
        for span in spans.iter().filter(|span| span.kind != Kind::Ignored) {
            let start = span.start.max(from).max(previous_end);
            let end = span.end.min(to);
            if end <= start {
                continue;
            }
            let duration = end - start;
            let continuous = start.saturating_sub(previous_end) <= SWITCH_GAP_MS;
            if let Some((identity, dwell)) = visits.last_mut().filter(|_| continuous) {
                if *identity == span.identity {
                    *dwell += duration;
                } else {
                    metrics.switches += 1;
                    visits.push((&span.identity, duration));
                }
            } else {
                visits.push((&span.identity, duration));
            }
            previous_end = end;
            metrics.active_ms += duration;
            if span.kind.confirmed_consumption() {
                consuming_ms += duration;
            }
            if span.kind == Kind::Browsing {
                browsing_ms += duration;
            }
            if span.kind == Kind::Creating {
                creating_ms += duration;
            }
            if span.kind == Kind::Feed {
                feed_ms += duration;
            }
        }

        let mut by_activity: BTreeMap<&str, Vec<u64>> = BTreeMap::new();
        for (activity, dwell) in &visits {
            by_activity.entry(*activity).or_default().push(*dwell);
        }
        metrics.median_dwell_by_activity_ms = by_activity
            .into_iter()
            .map(|(activity, mut dwells)| (activity.to_owned(), median(&mut dwells)))
            .collect();
        metrics.median_dwell_ms = median(
            &mut visits
                .into_iter()
                .map(|(_, dwell)| dwell)
                .collect::<Vec<_>>(),
        );
        metrics.switches_per_minute =
            metrics.switches as f64 * 60_000.0 / (to - from).max(1) as f64;
        if metrics.active_ms > 0 {
            metrics.creating_share = creating_ms as f64 / metrics.active_ms as f64;
            metrics.consuming_share =
                (consuming_ms + browsing_ms) as f64 / metrics.active_ms as f64;
            metrics.confirmed_consuming_share = consuming_ms as f64 / metrics.active_ms as f64;
            metrics.feed_share = feed_ms as f64 / metrics.active_ms as f64;
        }
        metrics
    }
}

#[derive(Default, Debug)]
pub struct Signals {
    pub short: Window,
    pub long: Window,
    pub panel_opens: usize,
    pub phone_pickups: Option<usize>,
    pub pause_restarts: usize,
    pub active: bool,
    pub input_idle: bool,
    pub session_start: u64,
    pub current_consuming: bool,
    pub recent_consuming: bool,
}

impl Signals {
    /// Spans are ordered by start time and point events by timestamp. Sync can backfill point events.
    pub fn measure(
        config: &Config,
        now: u64,
        spans: &[Activity],
        panel_opens: &[u64],
        phone_pickups: Option<&[u64]>,
        pause_restarts: &[u64],
    ) -> Self {
        let short_from = now.saturating_sub(config.short_window_seconds * SECOND);
        let long_from = now.saturating_sub(config.lookback_ms());
        let pause_from = now.saturating_sub(config.pause_window_seconds * SECOND);
        let mut session_start = 0;
        let mut last_end = 0;
        let mut last_consuming_end = 0;
        let mut current_consuming = false;
        for span in spans.iter().filter(|span| span.start <= now) {
            if span.start.saturating_sub(last_end) >= ACTIVE_GAP_MS {
                session_start = span.start;
                last_consuming_end = 0;
            }
            last_end = last_end.max(span.end);
            let consuming = span.kind.confirmed_consumption();
            if span.end >= now {
                current_consuming = consuming;
            }
            if consuming {
                last_consuming_end = last_consuming_end.max(span.end);
            }
        }
        let active = last_end > now.saturating_sub(ACTIVE_GAP_MS);
        Self {
            short: Window::measure(spans, short_from.max(session_start), now),
            long: Window::measure(spans, long_from, now),
            active,
            input_idle: false,
            session_start,
            current_consuming,
            recent_consuming: current_consuming
                || last_consuming_end > now.saturating_sub(MAX_CONSUMING_GAP_MS),
            panel_opens: recent_points(panel_opens, short_from.max(session_start), now).len(),
            phone_pickups: phone_pickups.map(|pickups| {
                recent_points(pickups, long_from, now)
                    .iter()
                    .filter(|&&t| spans.iter().any(|s| s.start <= t && t < s.end))
                    .count()
            }),
            pause_restarts: recent_points(pause_restarts, pause_from, now).len(),
        }
    }
}

fn recent_points(points: &[u64], after: u64, through: u64) -> &[u64] {
    let end = points.partition_point(|&t| t <= through);
    let start = points[..end].partition_point(|&t| t <= after);
    &points[start..end]
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum State {
    Focused,
    Drifting,
    Unfocused,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Transition {
    pub at: u64,
    pub from: Option<State>,
    pub to: Option<State>,
    pub reason: String,
}

pub struct Detector {
    config: Config,
    state: Option<State>,
    pending: Option<(State, u64)>,
    last_tick: Option<u64>,
}

impl Detector {
    pub fn new(config: Config) -> Self {
        Self {
            config,
            state: None,
            pending: None,
            last_tick: None,
        }
    }

    fn transition(&mut self, at: u64, to: Option<State>, reason: String) -> Transition {
        let from = self.state;
        self.state = to;
        self.pending = None;
        Transition {
            at,
            from,
            to,
            reason,
        }
    }

    pub fn interrupt(&mut self) -> Option<Transition> {
        self.pending = None;
        let end = self.last_tick.take()?;
        self.state
            .map(|_| self.transition(end, None, "Laptop observation interrupted".into()))
    }

    pub fn step(&mut self, now: u64, signals: &Signals) -> Option<Transition> {
        if self
            .last_tick
            .is_some_and(|t| now <= t || now - t > 2 * TICK_MS || signals.session_start > t)
        {
            if let Some(event) = self.interrupt() {
                self.last_tick = Some(now);
                return Some(event);
            }
        }
        self.last_tick = Some(now);
        if !signals.active {
            return self
                .state
                .map(|_| self.transition(now, None, "Laptop activity ended".into()));
        }
        let Some(state) = self.state else {
            return Some(self.transition(
                now,
                Some(State::Focused),
                "Laptop activity detected".into(),
            ));
        };

        let config = &self.config;
        let short = &signals.short;
        let enough_activity = short.active_ms >= config.minimum_active_seconds * SECOND;
        let churn = short.switches >= config.switches
            && short.median_dwell_ms <= config.short_dwell_seconds * SECOND;
        let panel = signals.panel_opens >= config.panel_opens;
        let pickup = signals
            .phone_pickups
            .is_some_and(|n| n >= config.phone_pickups);
        let pause = signals.pause_restarts >= config.pause_restarts;
        let support = usize::from(panel) + usize::from(pickup) + usize::from(pause);
        // Generic browsing includes search and documentation. Count its time
        // in the totals, but require more specific activity to infer drift.
        let consuming = short.confirmed_consuming_share;
        let drift = enough_activity
            && ((churn && short.feed_share >= config.drift_feed_share)
                || ((churn || support > 0)
                    && consuming >= config.drift_consuming_share
                    && consuming > short.creating_share)
                || short.feed_share >= config.unfocused_feed_share);
        let strong = drift
            && consuming >= config.unfocused_consuming_share
            && (short.feed_share >= config.unfocused_feed_share
                || support >= 2
                || (churn && support > 0));
        let holding = drift
            || (enough_activity
                && consuming >= config.recovery_consuming_share
                && (short.feed_share >= config.drift_feed_share
                    || ((churn || support > 0) && consuming > short.creating_share)));

        let can_escalate = !signals.input_idle && signals.recent_consuming;
        let (target, hold, cause) = match state {
            State::Focused if drift && can_escalate => (
                State::Drifting,
                config.drift_hold_seconds,
                "Drift signals persisted",
            ),
            State::Drifting if strong && can_escalate => (
                State::Unfocused,
                config.unfocused_hold_seconds,
                "Strong drift signals persisted",
            ),
            State::Drifting if !holding => (
                State::Focused,
                config.recovery_hold_seconds,
                "Drift signals stayed low",
            ),
            State::Unfocused if !strong => (
                State::Drifting,
                config.recovery_hold_seconds,
                "Strong drift signals subsided",
            ),
            _ => {
                self.pending = None;
                return None;
            }
        };

        // A short work visit can bridge an upward hold, but neither starts
        // the hold nor emits an escalation when work is in the foreground.
        if matches!(
            (state, target),
            (State::Focused, State::Drifting) | (State::Drifting, State::Unfocused)
        ) && !signals.current_consuming
        {
            self.pending = self.pending.filter(|(pending, _)| *pending == target);
            return None;
        }

        let since = match self.pending {
            Some((pending, since)) if pending == target => since,
            _ => now,
        };
        self.pending = Some((target, since));
        if now.saturating_sub(since) < hold * SECOND {
            return None;
        }

        let mut reason = format!(
            "{cause} for {}s · {} switches and {:.0}s median dwell in {}s · {:.0}% consuming, {:.0}% feeds",
            (now - since) / SECOND, short.switches, short.median_dwell_ms as f64 / 1000.0,
            config.short_window_seconds, short.consuming_share * 100.0, short.feed_share * 100.0,
        );
        if panel {
            reason.push_str(&format!(" · {} panel opens", signals.panel_opens));
        }
        if short.consuming_share > consuming {
            reason.push_str(&format!(
                " · {:.0}% consuming outside generic browsing",
                consuming * 100.0
            ));
        }
        if pickup {
            reason.push_str(&format!(
                " · {} inferred phone pickups",
                signals.phone_pickups.unwrap_or(0)
            ));
        }
        if pause {
            reason.push_str(&format!(
                " · {} quick pause restarts",
                signals.pause_restarts
            ));
        }
        Some(self.transition(now, Some(target), reason))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::activity::Span;
    use classifier::Classifier;
    use serde_json::json;

    fn strong() -> Signals {
        Signals {
            active: true,
            current_consuming: true,
            recent_consuming: true,
            short: Window {
                active_ms: 180_000,
                switches: 14,
                median_dwell_ms: 12_000,
                consuming_share: 0.70,
                confirmed_consuming_share: 0.70,
                feed_share: 0.40,
                ..Window::default()
            },
            ..Signals::default()
        }
    }

    #[test]
    fn transitions_require_sustained_evidence_in_both_directions() {
        let mut detector = Detector::new(Config::default());
        let strong = strong();
        let work = Signals {
            active: true,
            ..Signals::default()
        };
        let mut events = Vec::new();
        for tick in 0..=83 {
            let signals = match tick {
                1..=8 | 10..=35 | 52 | 72 => &strong,
                _ => &work,
            };
            if let Some(event) = detector.step(1_000_000 + tick * TICK_MS, signals) {
                events.push((tick, event));
            }
        }
        assert_eq!(
            events
                .iter()
                .map(|(tick, e)| (*tick, e.to))
                .collect::<Vec<_>>(),
            [
                (0, Some(State::Focused)),
                (19, Some(State::Drifting)),
                (35, Some(State::Unfocused)),
                (71, Some(State::Drifting)),
            ]
        );
        assert!(events[2].1.reason.contains("14 switches"));
        assert!(events[2].1.reason.contains("70% consuming"));
        for tick in 84..=91 {
            let event = detector.step(1_000_000 + tick * TICK_MS, &work);
            assert_eq!(
                event.and_then(|e| e.to),
                (tick == 91).then_some(State::Focused)
            );
        }
    }

    #[test]
    fn idle_input_cannot_complete_a_drift_or_escalation_hold() {
        let mut detector = Detector::new(Config::default());
        let mut evidence = strong();
        for tick in 0..=100 {
            // Only this interval has sustained recent desktop input.
            evidence.input_idle = !(51..=60).contains(&tick);
            let event = detector
                .step(1_000_000 + tick * TICK_MS, &evidence)
                .and_then(|event| event.to);
            assert_eq!(
                event,
                match tick {
                    0 => Some(State::Focused),
                    60 => Some(State::Drifting),
                    _ => None,
                }
            );
        }
    }

    #[test]
    fn old_feed_evidence_cannot_start_a_hold_while_creating() {
        let config = Config::default();
        let mut detector = Detector::new(config.clone());
        let start = 1_000_000;
        let spans = [
            Activity {
                start,
                end: start + 180_000,
                identity: "x".into(),
                kind: Kind::Feed,
            },
            Activity {
                start: start + 180_000,
                end: start + 360_000,
                identity: "code".into(),
                kind: Kind::Creating,
            },
        ];
        for tick in 0..=72 {
            let now = start + tick * TICK_MS;
            let mut signals = Signals::measure(&config, now, &spans, &[], None, &[]);
            signals.input_idle = tick < 36;
            let event = detector.step(now, &signals).and_then(|event| event.to);
            assert_eq!(event, (tick == 0).then_some(State::Focused));
        }
    }

    #[test]
    fn short_work_visit_can_delay_but_never_complete_an_escalation() {
        let mut detector = Detector::new(Config::default());
        let mut evidence = strong();
        for tick in 0..=28 {
            evidence.current_consuming = tick != 10 && tick != 27;
            let event = detector
                .step(1_000_000 + tick * TICK_MS, &evidence)
                .and_then(|event| event.to);
            assert_eq!(
                event,
                match tick {
                    0 => Some(State::Focused),
                    11 => Some(State::Drifting),
                    28 => Some(State::Unfocused),
                    _ => None,
                }
            );
        }
    }

    #[test]
    fn session_gap_discards_pending_evidence() {
        let mut detector = Detector::new(Config::default());
        let strong = strong();
        assert_eq!(
            detector.step(1_000_000, &strong).unwrap().to,
            Some(State::Focused)
        );
        for tick in 1..=8 {
            assert!(detector.step(1_000_000 + tick * TICK_MS, &strong).is_none());
        }
        let end = detector.step(1_200_000, &strong).unwrap();
        assert_eq!((end.at, end.to), (1_040_000, None));
        assert_eq!(
            detector.step(1_205_000, &strong).unwrap().to,
            Some(State::Focused)
        );
        assert!(detector.step(1_210_000, &strong).is_none());
    }

    #[test]
    fn gap_between_evaluations_starts_a_fresh_session() {
        let config = Config::default();
        let mut detector = Detector::new(config.clone());
        let start = 1_000_000;
        let spans = [
            Activity {
                start,
                end: start + 300_000,
                identity: "x".into(),
                kind: Kind::Feed,
            },
            Activity {
                start: start + 315_000,
                end: start + 550_000,
                identity: "code".into(),
                kind: Kind::Creating,
            },
        ];
        let mut saw_unfocused = false;
        for tick in 0..=110 {
            let now = start + tick * TICK_MS;
            let signals = Signals::measure(&config, now, &spans, &[], None, &[]);
            if let Some(event) = detector.step(now, &signals) {
                if tick < 63 {
                    saw_unfocused |= event.to == Some(State::Unfocused);
                } else if tick == 63 {
                    assert_eq!((event.from, event.to), (Some(State::Unfocused), None));
                } else {
                    assert_eq!((event.from, event.to), (None, Some(State::Focused)));
                }
            } else if tick == 63 || tick == 64 {
                panic!("missing session transition at tick {tick}");
            }
        }
        assert!(saw_unfocused);
    }

    #[test]
    fn thresholds_can_be_overridden_with_partial_json() {
        let config: Config =
            serde_json::from_str(r#"{"drift_hold_seconds":5,"unfocused_consuming_share":0.95}"#)
                .unwrap();
        config.validate().unwrap();
        assert_eq!(config.short_window_seconds, 180);
        let mut detector = Detector::new(config);
        let strong = strong();
        detector.step(1_000_000, &strong);
        assert!(detector.step(1_005_000, &strong).is_none());
        assert_eq!(
            detector.step(1_010_000, &strong).unwrap().to,
            Some(State::Drifting)
        );
        for tick in 3..=50 {
            assert!(detector.step(1_000_000 + tick * TICK_MS, &strong).is_none());
        }
        let invalid: Config = serde_json::from_str(r#"{"recovery_consuming_share":0.90}"#).unwrap();
        assert!(invalid.validate().is_err());
    }

    fn classify(
        classifier: &mut Classifier,
        start: u64,
        end: u64,
        app: &str,
        label: &str,
        url: Option<&str>,
    ) -> Activity {
        let mut span = json!({"s": start, "e": end, "app": app, "label": label});
        if let Some(url) = url {
            span["source"] = json!("chromium_extension");
            span["browser"] = json!({
                "url": url, "title": "Example", "audible": false,
                "visibility": "visible", "browser_pid": 1, "host_pid": 1,
            });
        }
        classifier
            .classify(&serde_json::from_value::<Span>(span).unwrap())
            .unwrap()
    }

    #[test]
    fn ide_terminal_docs_and_t3_loops_remain_focused() {
        let mut classifier = Classifier::new(&json!({})).unwrap();
        for loop_apps in [
            [
                ("VS Code", "VS Code", None),
                ("Terminal", "Terminal", None),
                ("Chrome", "docs.rs", Some("https://docs.rs/example")),
            ],
            [
                ("T3 Code", "T3 Code", None),
                ("Chrome", "Chrome", None),
                (
                    "Chrome",
                    "github.com",
                    Some("https://github.com/example/repo"),
                ),
            ],
        ] {
            let config = Config::default();
            let spans: Vec<_> = (0..90)
                .map(|i| {
                    let (app, label, url) = loop_apps[i as usize % loop_apps.len()];
                    let start = 1_000_000 + i * 10_000;
                    classify(&mut classifier, start, start + 10_000, app, label, url)
                })
                .collect();
            let mut detector = Detector::new(config.clone());
            for tick in 0..=180 {
                let now = 1_000_000 + tick * TICK_MS;
                let panels = [now - 4000, now - 3000, now - 2000, now - 1000];
                let signals = Signals::measure(&config, now, &spans, &panels, None, &[]);
                if let Some(event) = detector.step(now, &signals) {
                    assert_eq!(event.to, Some(State::Focused));
                }
            }
        }
    }

    #[test]
    fn debugging_search_and_support_articles_do_not_trigger_drift() {
        let config = Config::default();
        let mut classifier = Classifier::new(&json!({})).unwrap();
        let mut detector = Detector::new(config.clone());
        let mut spans = Vec::new();
        let mut start = 1_000_000;
        for _ in 0..10 {
            for (app, duration, url) in [
                ("VS Code", 8, None),
                ("Terminal", 8, None),
                ("T3 Code", 8, None),
                (
                    "Chrome",
                    8,
                    Some("https://www.google.com/search?q=debugging"),
                ),
                (
                    "Chrome",
                    12,
                    Some("https://support.google.com/chrome/answer/123"),
                ),
                (
                    "Chrome",
                    16,
                    Some("https://www.reddit.com/r/rust/comments/test/example"),
                ),
            ] {
                let end = start + duration * 1000;
                spans.push(classify(&mut classifier, start, end, app, app, url));
                start = end;
            }
        }
        let mut now = 1_000_000;
        while now <= start {
            let panels = [now - 4000, now - 3000, now - 2000, now - 1000];
            let signals = Signals::measure(&config, now, &spans, &panels, None, &[]);
            if let Some(event) = detector.step(now, &signals) {
                assert_eq!(event.to, Some(State::Focused));
            }
            if now == start {
                assert_eq!(signals.short.consuming_share, 0.60);
                assert_eq!(signals.short.confirmed_consuming_share, 0.0);
            }
            now += TICK_MS;
        }
    }

    #[test]
    fn unfocused_recovers_after_strong_evidence_subsides() {
        let mut detector = Detector::new(Config::default());
        let strong = strong();
        let mixed = Signals {
            active: true,
            short: Window {
                active_ms: 180_000,
                switches: 3,
                median_dwell_ms: 54_000,
                creating_share: 0.40,
                consuming_share: 0.60,
                confirmed_consuming_share: 0.60,
                feed_share: 0.30,
                ..Window::default()
            },
            ..Signals::default()
        };
        let events: Vec<_> = (0..=65)
            .filter_map(|tick| {
                detector
                    .step(
                        1_000_000 + tick * TICK_MS,
                        if tick <= 26 { &strong } else { &mixed },
                    )
                    .map(|event| (tick, event.to))
            })
            .collect();
        assert_eq!(
            events,
            [
                (0, Some(State::Focused)),
                (10, Some(State::Drifting)),
                (26, Some(State::Unfocused)),
                (45, Some(State::Drifting)),
            ]
        );
    }

    #[test]
    fn classifier_preserves_consuming_verbs_and_user_rules() {
        let mut classifier = Classifier::new(&json!({"rules": [
            {"match": "host", "value": "unlisted.example", "category": "building"},
            {"match": "app", "value": "Spotify", "category": "ignore"},
            {"match": "host", "value": "support.google.com", "category": "browsing"},
        ]}))
        .unwrap();
        for (app, label, url, expected) in [
            (
                "Chrome",
                "youtu.be",
                Some("https://www.youtube.com/watch?v=one"),
                Kind::Consuming,
            ),
            ("Chrome", "x.com", Some("https://x.com/home"), Kind::Feed),
            ("Firefox", "Reddit", None, Kind::Browsing),
            ("Firefox", "X", None, Kind::Browsing),
            ("Firefox", "YouTube", None, Kind::Browsing),
            ("Firefox", "TikTok", None, Kind::Browsing),
            (
                "Chrome",
                "twitch.tv",
                Some("https://www.twitch.tv/search?term=rust"),
                Kind::Browsing,
            ),
            (
                "Chrome",
                "twitch.tv",
                Some("https://www.twitch.tv/rustlang"),
                Kind::Consuming,
            ),
            (
                "Chrome",
                "support.google.com",
                Some("https://support.google.com/chrome/answer/123"),
                Kind::Browsing,
            ),
            ("Firefox", "constructor", None, Kind::Neutral),
            (
                "Chrome",
                "youtube.com",
                Some("https://www.youtube.com/results?search_query=rust"),
                Kind::Browsing,
            ),
            (
                "Chrome",
                "youtube.com",
                Some("https://www.youtube.com/feed/subscriptions"),
                Kind::Feed,
            ),
            (
                "Chrome",
                "x.com",
                Some("https://x.com/example/status/123"),
                Kind::Browsing,
            ),
            (
                "Chrome",
                "reddit.com",
                Some("https://www.reddit.com/r/rust/"),
                Kind::Feed,
            ),
            (
                "Chrome",
                "docs.rs",
                Some("https://docs.rs/example"),
                Kind::Creating,
            ),
            (
                "Chrome",
                "docs.unlisted.example",
                Some("https://docs.unlisted.example/guide"),
                Kind::Creating,
            ),
            ("Spotify", "Spotify", None, Kind::Ignored),
        ] {
            assert_eq!(
                classify(&mut classifier, 1, 2, app, label, url).kind,
                expected,
                "{app} with {label} and {url:?}"
            );
        }
    }

    #[test]
    fn verified_feeds_do_not_need_audio_to_count_as_consuming() {
        let mut classifier = Classifier::new(&json!({})).unwrap();
        let mut work_classifier = Classifier::new(&json!({"rules": [
            {"match": "host", "value": "instagram.com", "category": "writing"},
        ]})).unwrap();
        for url in ["https://www.instagram.com/reels/", "https://www.tiktok.com/foryou"] {
            let mut data = json!({
                "s": 1, "e": 2, "app": "Chrome", "label": "Example feed", "source": "chromium_extension",
                "browser": { "url": url, "title": "Example feed", "audible": false,
                    "visibility": "page", "browser_pid": 1, "host_pid": 1, "habit_feed": true }
            });
            let verified: Span = serde_json::from_value(data.clone()).unwrap();
            assert_eq!(classifier.classify(&verified).unwrap().kind, Kind::Feed);
            if url.contains("instagram.com") {
                assert_eq!(work_classifier.classify(&verified).unwrap().kind, Kind::Creating);
            }
            data["browser"]["habit_feed"] = false.into();
            let unverified: Span = serde_json::from_value(data).unwrap();
            assert_ne!(classifier.classify(&unverified).unwrap().kind, Kind::Feed);
        }
    }

    #[test]
    fn custom_app_names_cannot_change_feed_detection() {
        let mut classifier = Classifier::new(&json!({"rules": [
            {"match": "host", "value": "youtube.com", "app": "Tutorials", "category": "browsing"},
            {"match": "host", "value": "reddit.com", "app": "Programming forums", "category": "browsing"},
        ]}))
        .unwrap();
        for (url, expected) in [
            (
                "https://www.youtube.com/results?search_query=rust",
                Kind::Browsing,
            ),
            ("https://www.youtube.com/playlist?list=rust", Kind::Browsing),
            ("https://www.youtube.com/feed/subscriptions", Kind::Feed),
            ("https://www.reddit.com/r/rust/", Kind::Feed),
            (
                "https://www.reddit.com/r/rust/comments/example/",
                Kind::Browsing,
            ),
        ] {
            assert_eq!(
                classify(&mut classifier, 1, 2, "Chrome", "Chrome", Some(url)).kind,
                expected,
                "{url}"
            );
        }
    }

    #[test]
    fn preserving_watching_while_renaming_keeps_browse_routes_ambiguous() {
        let mut classifier = Classifier::new(&json!({"rules": [
            {"match": "host", "value": "youtube.com", "app": "Tutorials", "category": "watching"},
            {"match": "app", "value": "YouTube", "app": "Tutorials", "category": "watching"},
            {"match": "host", "value": "twitch.tv", "app": "Streams", "category": "watching"},
        ]}))
        .unwrap();
        let cases = [
            (
                "YouTube",
                Some("https://www.youtube.com/results?search_query=rust"),
                Kind::Browsing,
            ),
            (
                "YouTube",
                Some("https://www.youtube.com/playlist?list=rust"),
                Kind::Browsing,
            ),
            (
                "YouTube",
                Some("https://www.youtube.com/watch?v=rust"),
                Kind::Consuming,
            ),
            ("YouTube", None, Kind::Browsing),
            (
                "Twitch",
                Some("https://www.twitch.tv/directory/category/software"),
                Kind::Browsing,
            ),
            (
                "Twitch",
                Some("https://www.twitch.tv/settings/profile"),
                Kind::Browsing,
            ),
            (
                "Twitch",
                Some("https://www.twitch.tv/rustlang"),
                Kind::Consuming,
            ),
        ];
        for (label, url, expected) in cases {
            assert_eq!(
                classify(&mut classifier, 1, 2, "Firefox", label, url).kind,
                expected,
                "{label} {url:?}"
            );
        }
    }

    #[test]
    fn measures_merged_visits_switches_and_pickups_during_laptop_use() {
        let spans = [
            (0, 60, "terminal", Kind::Creating),
            (60, 61, "focusdns", Kind::Ignored),
            (61, 80, "terminal", Kind::Creating),
            (80, 100, "x", Kind::Feed),
            (110, 130, "terminal", Kind::Creating),
        ]
        .map(|(start, end, identity, kind)| Activity {
            start: 1_000_000 + start * 1000,
            end: 1_000_000 + end * 1000,
            identity: identity.into(),
            kind,
        });
        let signals = Signals::measure(
            &Config::default(),
            1_130_000,
            &spans,
            &[1_060_000],
            Some(&[1_082_000, 1_105_000, 1_140_000]),
            &[],
        );
        assert_eq!(signals.short.active_ms, 119_000);
        assert_eq!(signals.short.switches, 1);
        assert_eq!(signals.short.median_dwell_ms, 20_000);
        assert_eq!(
            signals.short.median_dwell_by_activity_ms["terminal"],
            49_500
        );
        assert_eq!(signals.short.consuming_share, 20.0 / 119.0);
        assert_eq!(signals.short.feed_share, 20.0 / 119.0);
        assert_eq!(signals.phone_pickups, Some(1));
        assert_eq!(signals.panel_opens, 1);
        assert_eq!(signals.long.switches_per_minute, 0.1);
    }
}
