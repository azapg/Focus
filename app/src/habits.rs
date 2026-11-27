use chrono::{Datelike, Local, TimeZone, Timelike};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub mod focus_rewards;
mod wallet;
pub use wallet::{
    Purchase, WalletEntry, SYNC_VERSION as WALLET_SYNC_VERSION, TIME_OFF_PRICE_PER_MINUTE,
};

const EPOCH_DAY: i32 = 719_163;
const WINDOW_MS: u64 = 30 * 60_000;
const CHECKOFF_MS: u64 = 30_000;
const PAGE_SIZE: usize = 512;
const START_DAY: i32 = 18_262; // 2020-01-01

pub fn local_clock(now: u64) -> (i32, u16) {
    let date = Local
        .timestamp_millis_opt(now as i64)
        .single()
        .unwrap_or_else(Local::now);
    (
        date.num_days_from_ce() - EPOCH_DAY,
        (date.hour() * 60 + date.minute()) as u16,
    )
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq, PartialOrd, Ord)]
pub struct Revision {
    pub timestamp: u64,
    pub id: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct Habit {
    pub id: String,
    pub rev: Revision,
    pub name: String,
    pub interval: i32,
    pub weekdays: u8, // Monday = bit 0. Zero selects an interval instead.
    pub time: Option<u16>,
    pub reward: u16,
    pub start: i32,
    #[serde(default)]
    pub deleted: bool,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub schedules: Vec<Schedule>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct Schedule {
    pub day: i32,
    pub interval: i32,
    pub weekdays: u8,
    pub rev: Revision,
}

impl Schedule {
    fn period(&self, day: i32) -> Option<i32> {
        if day < self.day {
            return None;
        }
        if self.weekdays == 0 {
            return Some(day - (day - self.day) % self.interval);
        }
        (0..7)
            .map(|d| day - d)
            .find(|&d| d >= self.day && self.weekdays & (1 << (d + 3).rem_euclid(7)) != 0)
    }
}

fn merge_schedules(left: &[Schedule], right: &[Schedule]) -> Vec<Schedule> {
    let mut history: BTreeMap<i32, &Schedule> = BTreeMap::new();
    for schedule in left.iter().chain(right) {
        if history
            .get(&schedule.day)
            .is_none_or(|prior| prior.rev < schedule.rev)
        {
            history.insert(schedule.day, schedule);
        }
    }
    history.into_values().cloned().collect()
}

impl Habit {
    pub fn valid(&self) -> bool {
        !self.id.is_empty()
            && self.id.len() <= 80
            && !self.rev.id.is_empty()
            && self.rev.id.len() <= 80
            && !self.name.trim().is_empty()
            && self.name.chars().count() <= 80
            && (1..=365).contains(&self.interval)
            && self.weekdays <= 127
            && self.time.is_none_or(|t| t < 1440)
            && self.reward <= 15
            && (START_DAY..=100_000).contains(&self.start)
            && self.schedules.iter().all(|s| {
                (self.start..=100_000).contains(&s.day)
                    && (1..=365).contains(&s.interval)
                    && s.weekdays <= 127
                    && !s.rev.id.is_empty()
                    && s.rev.id.len() <= 80
            })
    }

    fn schedule(&self, day: i32) -> Schedule {
        Schedule {
            day,
            interval: self.interval,
            weekdays: self.weekdays,
            rev: self.rev.clone(),
        }
    }

    pub fn keep_schedule_history(&mut self, prior: &Habit, day: i32) {
        // Each segment starts on its own calendar day, including a fresh anchor for every-N-days.
        self.schedules = prior.schedules.clone();
        if (self.interval, self.weekdays) == (prior.interval, prior.weekdays) {
            return;
        }
        if self.schedules.is_empty() {
            let mut initial = prior.schedule(prior.start);
            initial.rev = Revision {
                timestamp: 0,
                id: prior.id.clone(),
            };
            self.schedules.push(initial);
        }
        self.schedules = merge_schedules(&self.schedules, &[self.schedule(day.max(self.start))]);
    }

    pub fn scheduled(&self, day: i32) -> bool {
        self.period(day) == Some(day)
    }

    fn period(&self, day: i32) -> Option<i32> {
        if self.schedules.is_empty() {
            return self.schedule(self.start).period(day);
        }
        self.schedule_at(day)?.period(day)
    }

    fn schedule_at(&self, day: i32) -> Option<&Schedule> {
        self.schedules
            .iter()
            .filter(|s| s.day <= day)
            .max_by(|a, b| a.rev.cmp(&b.rev))
    }

    fn previous_period(&self, mut day: i32) -> Option<i32> {
        if self.schedules.is_empty() {
            return self.period(day);
        }
        while let Some(schedule) = self.schedule_at(day) {
            if let Some(period) = schedule.period(day) {
                return Some(period);
            }
            day = schedule.day - 1;
        }
        None
    }
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq, PartialOrd, Ord)]
pub struct Event {
    pub id: String,
    pub habit: String,
    pub at: u64,
    pub day: i32,
    pub period: i32,
    pub reward: u16,
    pub away: Option<u16>,
    pub undo: Option<String>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub wallet_reward: bool,
}

fn is_false(value: &bool) -> bool {
    !*value
}

impl Event {
    fn valid(&self) -> bool {
        !self.id.is_empty()
            && self.id.len() <= 80
            && !self.habit.is_empty()
            && self.habit.len() <= 80
            && (START_DAY..=100_000).contains(&self.day)
            && (START_DAY..=self.day).contains(&self.period)
            && self.reward <= 15
            && self.away.is_none_or(|a| a <= 30)
            && self
                .undo
                .as_ref()
                .is_none_or(|u| !u.is_empty() && u.len() <= 80)
    }
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct CreditCap {
    pub rev: Revision,
    pub minutes: u16,
    pub previous: u16,
    pub effective: i32,
}

impl CreditCap {
    fn valid(&self) -> bool {
        self.minutes <= 60
            && self.previous <= 60
            && !self.rev.id.is_empty()
            && self.rev.id.len() <= 80
            && (START_DAY..=100_001).contains(&self.effective)
    }
}

#[derive(Clone, Debug, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(default)]
pub struct Ledger {
    pub definitions: BTreeMap<String, Habit>,
    pub events: BTreeMap<String, Event>,
    pub cap: Option<CreditCap>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub cap_edits: BTreeMap<String, CreditCap>,
    #[serde(deserialize_with = "wallet::decode_start", skip_serializing_if = "Option::is_none")]
    pub wallet_start: Option<i32>,
    #[serde(deserialize_with = "wallet::decode_entries", skip_serializing_if = "BTreeMap::is_empty")]
    wallet_entries: BTreeMap<String, WalletEntry>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) streak_journal: Option<crate::streak_journal::Journal>,
}

#[derive(Serialize)]
pub struct HabitStatus<'a> {
    pub habit: &'a Habit,
    pub due: bool,
    pub overdue: bool,
    pub grace: bool,
    pub streak: u32,
    pub completion: Option<&'a Event>,
    pub week: Vec<&'static str>,
}

impl Ledger {
    pub fn page(&self, after: &str, wallet: bool) -> (Self, Option<String>) {
        let mut result = Self {
            cap: self.cap.clone(),
            wallet_start: self.wallet_start.filter(|_| wallet),
            ..Self::default()
        };
        let mut cursor = None;
        let mut count = 0;
        for (prefix, id) in self
            .cap_edits
            .keys()
            .map(|id| ("c:", id))
            .chain(self.definitions.keys().map(|id| ("d:", id)))
            .chain(self.events.keys().map(|id| ("e:", id)))
            .chain(self.wallet_entries.keys().filter(|_| wallet).map(|id| ("w:", id)))
        {
            let key = format!("{prefix}{id}");
            if key.as_str() <= after {
                continue;
            }
            if count == PAGE_SIZE {
                return (result, cursor);
            }
            match prefix {
                "c:" => {
                    result
                        .cap_edits
                        .insert(id.clone(), self.cap_edits[id].clone());
                }
                "d:" => {
                    result
                        .definitions
                        .insert(id.clone(), self.definitions[id].clone());
                }
                "e:" => {
                    let mut event = self.events[id].clone();
                    event.wallet_reward &= wallet;
                    result.events.insert(id.clone(), event);
                }
                _ => {
                    result.wallet_entries.insert(id.clone(), self.wallet_entries[id].clone());
                }
            }
            count += 1;
            cursor = Some(key);
        }
        (result, None)
    }

    pub fn discard_wallet(&mut self) {
        self.wallet_start = None;
        self.wallet_entries.clear();
        for event in self.events.values_mut() {
            event.wallet_reward = false;
        }
    }

    pub fn merge(&mut self, incoming: Self) {
        self.merge_wallet_start(incoming.wallet_start);
        self.merge_wallet_entries(incoming.wallet_entries);
        for (id, mut habit) in incoming.definitions {
            if id != habit.id || !habit.valid() {
                continue;
            }
            if let Some(old) = self.definitions.get(&id) {
                let schedules = merge_schedules(&old.schedules, &habit.schedules);
                if old.rev >= habit.rev {
                    habit = old.clone();
                }
                habit.schedules = schedules;
            } else {
                habit.schedules = merge_schedules(&[], &habit.schedules);
            }
            if let Some(latest) = habit.schedule_at(i32::MAX) {
                let (interval, weekdays) = (latest.interval, latest.weekdays);
                habit.interval = interval;
                habit.weekdays = weekdays;
            }
            self.definitions.insert(id, habit);
        }
        for (id, event) in incoming.events {
            if id == event.id && event.valid() {
                self.events.entry(id).and_modify(|old| {
                    // Old peers can relay the completion without the wallet flag.
                    let mut without_flag = event.clone();
                    without_flag.wallet_reward = false;
                    if *old == without_flag {
                        old.wallet_reward = event.wallet_reward;
                    }
                }).or_insert(event);
            }
        }
        for cap in incoming.cap_edits.into_values().chain(incoming.cap) {
            self.put_cap(cap);
        }
    }

    pub fn put_cap(&mut self, cap: CreditCap) {
        if !cap.valid() {
            return;
        }
        if let Some(old) = &self.cap {
            self.cap_edits
                .entry(old.rev.id.clone())
                .or_insert_with(|| old.clone());
        }
        self.cap_edits
            .entry(cap.rev.id.clone())
            .or_insert_with(|| cap.clone());
        if self.cap.as_ref().is_none_or(|old| old.rev < cap.rev) {
            self.cap = Some(cap);
        }
    }

    fn completions(&self) -> BTreeMap<(&str, i32), &Event> {
        let undone: BTreeSet<_> = self
            .events
            .values()
            .filter_map(|e| e.undo.as_deref().map(|id| (e.habit.as_str(), id)))
            .collect();
        let mut candidates: Vec<_> = self.events.values()
            .filter(|e| e.undo.is_none() && !undone.contains(&(e.habit.as_str(), e.id.as_str())))
            .collect();
        candidates.sort_unstable_by(|a, b| (a.at, &a.id).cmp(&(b.at, &b.id)));
        let mut complete = BTreeMap::new();
        let mut checked_days = BTreeSet::new();
        for event in candidates {
            let habit = event.habit.as_str();
            // Competing offline events can name different periods for the same check-off day.
            if let std::collections::btree_map::Entry::Vacant(period) = complete.entry((habit, event.period)) {
                if checked_days.insert((habit, event.day)) {
                    period.insert(event);
                }
            }
        }
        complete
    }

    pub fn cap_on(&self, day: i32) -> u16 {
        // A pending increase carries the older base the author saw. A tighter same-day edit
        // remains in effect until midnight even after a device learns of that offline increase.
        let edits = self.cap_edits.values().chain(self.cap.iter());
        let effective = edits
            .clone()
            .filter(|c| c.effective <= day)
            .max_by(|a, b| a.rev.cmp(&b.rev))
            .map_or(45, |c| c.minutes);
        edits
            .filter_map(|c| {
                if c.minutes > c.previous {
                    (c.effective - 1 == day).then_some(c.previous)
                } else {
                    (c.effective == day).then_some(c.minutes)
                }
            })
            .fold(effective, u16::min)
    }

    pub fn credit(&self, day: i32) -> u16 {
        self.credit_for(day, false)
    }

    fn legacy_credit(&self, day: i32) -> u16 {
        self.credit_for(day, true)
    }

    fn credit_for(&self, day: i32, legacy_only: bool) -> u16 {
        let total: u32 = self.completions().into_values()
            .filter(|event| event.day == day && (!legacy_only || !event.wallet_reward))
            .map(|event| u32::from(event.reward)).sum();
        total.min(u32::from(self.cap_on(day))) as u16
    }

    pub fn status(&self, day: i32, minute: u16) -> Vec<HabitStatus<'_>> {
        let all = self.completions();
        let mut rows = Vec::new();
        for habit in self.definitions.values().filter(|h| !h.deleted) {
            let id = habit.id.as_str();
            let at = |d| all.get(&(id, d)).copied().filter(|e| e.day <= day);
            let period = habit.period(day);
            let today = all
                .range((id, i32::MIN)..=(id, day))
                .map(|(_, event)| *event)
                .find(|event| event.day == day);
            let completion = period.and_then(at).or(today);
            let due = period.is_some() && completion.is_none();
            let overdue = due && (period != Some(day) || habit.time.is_some_and(|t| minute >= t));
            let previous_occurrence = |through| {
                let scheduled = habit.previous_period(through);
                let completed = all
                    .range((id, i32::MIN)..=(id, through))
                    .rev()
                    .find(|(_, event)| event.day <= day)
                    .map(|((_, period), _)| *period);
                scheduled.into_iter().chain(completed).max()
            };
            let previous = previous_occurrence(period.map_or(day, |p| p - 1));
            let mut grace = false;
            let mut streak = 0;
            let mut misses = 0;
            let mut cursor = previous_occurrence(day);
            while let Some(p) = cursor {
                if at(p).is_some() {
                    streak += 1;
                    misses = 0;
                } else if Some(p) != period {
                    misses += 1;
                    if misses == 2 {
                        break;
                    }
                }
                cursor = previous_occurrence(p - 1);
            }
            if due && streak > 0 {
                grace = previous.is_some_and(|p| at(p).is_none());
            }

            let week = (day - 6..=day)
                .map(|d| {
                    if at(d).is_some() {
                        "done"
                    } else if !habit.scheduled(d) {
                        "off"
                    } else if due && period == Some(d) {
                        "due"
                    } else {
                        "miss"
                    }
                })
                .collect();
            rows.push(HabitStatus {
                habit,
                due,
                overdue,
                grace,
                streak,
                completion: today,
                week,
            });
        }
        rows.sort_by(|a, b| {
            b.overdue
                .cmp(&a.overdue)
                .then(b.grace.cmp(&a.grace))
                .then(a.habit.time.cmp(&b.habit.time))
                .then(a.habit.name.cmp(&b.habit.name))
                .then(a.habit.id.cmp(&b.habit.id))
        });
        rows
    }

    pub fn complete(
        &mut self,
        habit: &str,
        id: String,
        at: u64,
        away: Option<u16>,
    ) -> Option<Event> {
        let (day, minute) = local_clock(at);
        let row = self
            .status(day, minute)
            .into_iter()
            .find(|r| r.due && r.habit.id == habit)?;
        let event = Event {
            id,
            habit: habit.into(),
            at,
            day,
            period: row.habit.period(day)?,
            reward: row.habit.reward,
            away,
            undo: None,
            wallet_reward: self.wallet_active(day),
        };
        self.events.insert(event.id.clone(), event.clone());
        Some(event)
    }

    pub fn undo(&mut self, target: &str, id: String, at: u64) {
        let Some(prior) = self.events.get(target).filter(|e| e.undo.is_none()) else {
            return;
        };
        let event = Event {
            id,
            habit: prior.habit.clone(),
            at,
            day: local_clock(at).0,
            period: prior.period,
            reward: 0,
            away: None,
            undo: Some(target.into()),
            wallet_reward: false,
        };
        self.events.insert(event.id.clone(), event);
    }
}

// A check-off can open Focus itself. Attribute at most the immediately preceding idle interval,
// ending at the start of a short local check-off session. All observed screens bound that interval.
#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Screen {
    pub from: u64,
    pub to: u64,
    pub spans: Vec<[u64; 2]>,
}

impl Screen {
    pub fn from_snapshot(value: &serde_json::Value) -> Option<Self> {
        if value["permissions"]["usage"] != true || value["tracking_age_ms"].as_u64()? > 30_000 {
            return None;
        }
        let to = value["now"].as_u64()?;
        let from = value["range_from"]
            .as_u64()?
            .max(to.saturating_sub(32 * 60_000))
            .max(
                value["activity_observed_from"]
                    .as_u64()
                    .filter(|&start| start > 0 && start < to)?,
            );
        let spans = value["spans"]
            .as_array()?
            .iter()
            .filter_map(|s| Some([s["s"].as_u64()?, s["e"].as_u64()?]))
            .filter(|s| s[1] > from)
            .collect();
        Some(Self { from, to, spans })
    }
}

pub fn away_minutes(now: u64, local: &Screen, peers: &[Option<Screen>]) -> Option<u16> {
    let mut end = now;
    for &[start, stop] in &local.spans {
        if stop > now.saturating_sub(3_000) && start < end {
            end = end.min(start);
        }
    }
    if now - end > CHECKOFF_MS {
        end = now;
    }
    if local.to < now.saturating_sub(3_000) {
        return None;
    }
    let oldest_sample = end.saturating_sub(90_000);
    for peer in peers {
        let peer = peer.as_ref()?;
        if peer.to < oldest_sample {
            return None;
        }
        end = end.min(peer.to);
    }
    if local.from >= end {
        return None;
    }
    let mut start = end.saturating_sub(WINDOW_MS).max(local.from);
    let mut active = false;
    for (screen, own) in
        std::iter::once((Some(local), true)).chain(peers.iter().map(|p| (p.as_ref(), false)))
    {
        let screen = screen?;
        if screen.to < end.saturating_sub(90_000) || screen.from >= end {
            return None;
        }
        start = start.max(screen.from);
        for &[s, e] in &screen.spans {
            if s >= end || e <= s {
                continue;
            }
            start = start.max(e.min(end));
            if !own && e >= screen.to.saturating_sub(3_000) {
                active = true;
            }
        }
    }
    if active {
        return Some(0);
    }
    Some(((end - start) / 60_000) as u16)
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct DesktopLimit {
    pub minutes: u16,
    pub pending: Option<PendingLimit>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct PendingLimit {
    pub value: u16,
    pub from: u64,
}

impl Default for DesktopLimit {
    fn default() -> Self {
        Self {
            minutes: 60,
            pending: None,
        }
    }
}

impl DesktopLimit {
    fn valid(&self) -> bool {
        (1..=300).contains(&self.minutes)
            && self
                .pending
                .as_ref()
                .is_none_or(|pending| (1..=300).contains(&pending.value))
    }

    pub fn effective(&self, now: u64) -> u16 {
        self.pending
            .as_ref()
            .filter(|pending| pending.from <= now)
            .map_or(self.minutes, |pending| pending.value)
    }
}

pub struct Store {
    path: PathBuf,
    ledger: Mutex<Ledger>,
    desktop_limit: Mutex<DesktopLimit>,
    screens: Mutex<BTreeMap<String, Option<Screen>>>,
}

impl Store {
    pub fn new(dir: &Path) -> Self {
        let path = dir.join("habits.json");
        let ledger = std::fs::read(&path)
            .ok()
            .and_then(|raw| serde_json::from_slice(&raw).ok())
            .unwrap_or_default();
        Self {
            path,
            ledger: Mutex::new(ledger),
            desktop_limit: Mutex::new(
                std::fs::read(dir.join("habit-pause-limit.json"))
                    .ok()
                    .and_then(|raw| serde_json::from_slice::<DesktopLimit>(&raw).ok())
                    .filter(DesktopLimit::valid)
                    .unwrap_or_default(),
            ),
            screens: Mutex::new(BTreeMap::new()),
        }
    }

    pub fn desktop_limit(&self) -> DesktopLimit {
        self.desktop_limit.lock().unwrap().clone()
    }

    pub fn set_desktop_limit(&self, limit: DesktopLimit) -> Result<(), String> {
        if !limit.valid() {
            return Err("Invalid desktop pause limit".into());
        }
        let mut current = self.desktop_limit.lock().unwrap();
        if limit != *current {
            let raw = serde_json::to_vec(&limit).map_err(|e| e.to_string())?;
            focus_browser_host::atomic_write(
                &self.path.with_file_name("habit-pause-limit.json"),
                &raw,
            )
            .map_err(|e| e.to_string())?;
            *current = limit;
        }
        Ok(())
    }

    pub fn merge_screens(&self, screens: BTreeMap<String, Option<Screen>>) {
        let mut local = self.screens.lock().unwrap();
        for (id, screen) in screens {
            if id.is_empty() || id.len() > 128 {
                continue;
            }
            let prior = local.entry(id).or_default();
            if screen
                .as_ref()
                .is_none_or(|candidate| prior.as_ref().is_none_or(|old| old.to < candidate.to))
            {
                *prior = screen;
            }
        }
    }

    pub fn screens(&self) -> BTreeMap<String, Option<Screen>> {
        self.screens.lock().unwrap().clone()
    }

    pub fn read(&self) -> Ledger {
        self.ledger.lock().unwrap().clone()
    }

    pub fn activate_wallet(&self, today: i32) -> Result<bool, String> {
        self.update(|ledger| {
            if ledger.wallet_start.is_some() {
                return Ok(false);
            }
            // Startup must leave an unreadable ledger available for recovery.
            match std::fs::read(&self.path) {
                Ok(raw) => {
                    serde_json::from_slice::<Ledger>(&raw)
                        .map_err(|error| format!("Could not read the existing habit ledger: {error}"))?;
                }
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => return Err(error.to_string()),
            }
            ledger.activate_wallet(today)
        })
    }

    pub fn update<T>(
        &self,
        change: impl FnOnce(&mut Ledger) -> Result<T, String>,
    ) -> Result<T, String> {
        let mut current = self.ledger.lock().unwrap();
        let mut next = current.clone();
        let result = change(&mut next)?;
        if next != *current {
            let raw = serde_json::to_vec(&next).map_err(|e| e.to_string())?;
            focus_browser_host::atomic_write(&self.path, &raw).map_err(|e| e.to_string())?;
            *current = next;
        }
        Ok(result)
    }
}

#[cfg(test)]
mod tests;
