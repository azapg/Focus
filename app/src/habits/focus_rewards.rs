//! Native focus-session reward projection and atomic wallet earning adapter.
//!
//! Pure detector projection computes the cross-device unioned duration of verified
//! single-app deep-focus runs (25m+ coding/writing/business/design, 15m+ reading/learning),
//! excluding DNS-off pauses and wasting/Drift activity.
//!
//! Only the single profile finalization authority invokes `award_finalized_day` inside its
//! `Store::update` transaction. No scheduling or raw minting happens here or in the UI.

use super::Ledger;
use chrono::{Datelike, NaiveDate};

pub const FOCUS_KIND: &str = "focus";
pub const FOCUS_GLANCE_MS: u64 = 45_000;
pub const FOCUS_AWAY_MS: u64 = 2 * 60_000;
pub const TRIAL_COINS_PER_MILESTONE: i32 = 5;
pub const TRIAL_MILESTONE_MS: u64 = 30 * 60_000;
pub const TRIAL_MAX_MILESTONES: u32 = 4;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FocusCategory {
    Coding,
    Business,
    Writing,
    Design,
    Reading,
    Learning,
    Waste,
    Other,
}

impl FocusCategory {
    pub fn min_run_ms(self) -> u64 {
        match self {
            Self::Coding | Self::Business | Self::Writing | Self::Design => 25 * 60_000,
            Self::Reading | Self::Learning => 15 * 60_000,
            Self::Waste | Self::Other => 0,
        }
    }

    pub fn is_waste(self) -> bool {
        matches!(self, Self::Waste)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FocusSpan {
    pub start_ms: u64,
    pub end_ms: u64,
    pub app: String,
    pub category: FocusCategory,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FocusDeviceStream {
    pub spans: Vec<FocusSpan>,
    pub pauses: Vec<(u64, u64)>,
}

/// Configurable reward rate (trial defaults: 5 coins per completed 30m, max 4/day = 20 coins).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FocusRewardRate {
    pub coins_per_milestone: i32,
    pub milestone_ms: u64,
    pub max_milestones: u32,
}

impl Default for FocusRewardRate {
    fn default() -> Self {
        Self {
            coins_per_milestone: TRIAL_COINS_PER_MILESTONE,
            milestone_ms: TRIAL_MILESTONE_MS,
            max_milestones: TRIAL_MAX_MILESTONES,
        }
    }
}

impl FocusRewardRate {
    pub fn valid(&self) -> bool {
        self.coins_per_milestone > 0
            && self.coins_per_milestone <= 10_000
            && self.milestone_ms > 0
            && self.max_milestones > 0
            && self.max_milestones <= 48
    }
}

/// Finalized profile-day input supplied by the single profile finalization authority.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FinalizedFocusDay {
    /// Resolved calendar date in the profile timezone (`YYYY-MM-DD`).
    pub date: String,
    /// Epoch day (days since 1970-01-01) matching `date`.
    pub day: i32,
    /// Inclusive UTC start of the resolved profile day in epoch ms.
    pub day_start_ms: u64,
    /// Exclusive UTC end of the resolved profile day in epoch ms.
    pub day_end_ms: u64,
    /// True only when the day is finalized and complete (Pending/Unverified/incomplete Drift = false).
    pub complete: bool,
    /// Verified cross-device unioned single-app focus duration inside `[day_start_ms, day_end_ms)`.
    pub union_focus_ms: u64,
    /// Timestamp (epoch ms) recorded on minted wallet entries.
    pub at_ms: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AwardStatus {
    Awarded,
    AlreadyAwarded,
    NoMilestones,
    WalletDormant,
    NotFinalized,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct AwardOutcome {
    pub status: AwardStatus,
    pub milestones_awarded: u32,
    pub already_present: u32,
    pub coins_awarded: i32,
}

fn merge_intervals(intervals: &[(u64, u64)]) -> Vec<(u64, u64)> {
    let mut valid: Vec<(u64, u64)> = intervals
        .iter()
        .copied()
        .filter(|(s, e)| e > s)
        .collect();
    valid.sort_unstable_by_key(|&(s, e)| (s, e));
    let mut merged: Vec<(u64, u64)> = Vec::with_capacity(valid.len());
    for (s, e) in valid {
        if let Some(last) = merged.last_mut() {
            if s <= last.1 {
                last.1 = last.1.max(e);
                continue;
            }
        }
        merged.push((s, e));
    }
    merged
}

fn subtract_intervals(a: u64, b: u64, cuts: &[(u64, u64)]) -> Vec<(u64, u64)> {
    if b <= a {
        return Vec::new();
    }
    let mut out = vec![(a, b)];
    for &(cx, cy) in cuts {
        if cy <= a {
            continue;
        }
        if cx >= b {
            break;
        }
        let mut next = Vec::with_capacity(out.len() + 1);
        for (x, y) in out {
            if cy <= x || cx >= y {
                next.push((x, y));
            } else {
                if cx > x {
                    next.push((x, cx));
                }
                if cy < y {
                    next.push((cy, y));
                }
            }
        }
        out = next;
        if out.is_empty() {
            break;
        }
    }
    out
}

/// Union pre-verified qualifying intervals across devices within `[day_start_ms, day_end_ms)`,
/// subtracting any pause/break intervals.
pub fn union_verified_intervals_ms(
    intervals: &[(u64, u64)],
    pauses: &[(u64, u64)],
    day_start_ms: u64,
    day_end_ms: u64,
) -> u64 {
    if day_end_ms <= day_start_ms {
        return 0;
    }
    let clipped_pauses: Vec<(u64, u64)> = pauses
        .iter()
        .map(|&(s, e)| (s.max(day_start_ms), e.min(day_end_ms)))
        .filter(|(s, e)| e > s)
        .collect();
    let merged_pauses = merge_intervals(&clipped_pauses);
    let mut kept = Vec::new();
    for &(s, e) in intervals {
        let a = s.max(day_start_ms);
        let b = e.min(day_end_ms);
        if b > a {
            kept.extend(subtract_intervals(a, b, &merged_pauses));
        }
    }
    merge_intervals(&kept)
        .into_iter()
        .map(|(s, e)| e - s)
        .sum()
}

#[derive(Clone)]
struct FocusEvent {
    s: u64,
    e: u64,
    app: String,
    min_ms: u64,
    waste: bool,
    is_break: bool,
}

struct OpenRun {
    app: String,
    min_ms: u64,
    end: u64,
    ms: u64,
    intervals: Vec<(u64, u64)>,
}

impl OpenRun {
    fn from_event(ev: &FocusEvent) -> Self {
        Self {
            app: ev.app.clone(),
            min_ms: ev.min_ms,
            end: ev.e,
            ms: ev.e - ev.s,
            intervals: vec![(ev.s, ev.e)],
        }
    }
}

fn process_event(
    ev: &FocusEvent,
    cur: &mut Option<OpenRun>,
    pending: &mut Vec<FocusEvent>,
    qualified: &mut Vec<(u64, u64)>,
) {
    let emit = |run: Option<OpenRun>, out: &mut Vec<(u64, u64)>| {
        if let Some(r) = run {
            if r.ms >= r.min_ms {
                out.extend(r.intervals);
            }
        }
    };

    if ev.is_break || ev.waste {
        emit(cur.take(), qualified);
        pending.clear();
        return;
    }
    let Some(open) = cur.as_mut() else {
        *cur = (ev.min_ms > 0).then(|| OpenRun::from_event(ev));
        pending.clear();
        return;
    };
    let gap = ev.s.saturating_sub(open.end);
    if ev.app == open.app && ev.min_ms > 0 {
        let ok = if pending.is_empty() {
            gap <= FOCUS_AWAY_MS
        } else {
            gap <= FOCUS_GLANCE_MS
        };
        if ok {
            let add_s = ev.s.max(open.end);
            if ev.e > add_s {
                open.ms += ev.e - add_s;
                open.end = ev.e;
                open.intervals.push((add_s, ev.e));
            }
            pending.clear();
            return;
        }
        let saved = std::mem::take(pending);
        emit(cur.take(), qualified);
        for p in &saved {
            process_event(p, cur, pending, qualified);
        }
        process_event(ev, cur, pending, qualified);
        return;
    }
    if ev.e.saturating_sub(open.end) <= FOCUS_GLANCE_MS && gap <= FOCUS_GLANCE_MS {
        pending.push(ev.clone());
        return;
    }
    let saved = std::mem::take(pending);
    emit(cur.take(), qualified);
    for p in &saved {
        process_event(p, cur, pending, qualified);
    }
    process_event(ev, cur, pending, qualified);
}

/// Pure single-device qualifying focus intervals clipped to `[day_start_ms, day_end_ms)`.
/// Subtracts this device's pauses, enforces unbroken single-app focus thresholds,
/// breaks runs on waste/pauses/idle gaps, and preserves unknown gaps untouched.
pub fn verified_focus_intervals(
    stream: &FocusDeviceStream,
    day_start_ms: u64,
    day_end_ms: u64,
) -> Vec<(u64, u64)> {
    if day_end_ms <= day_start_ms {
        return Vec::new();
    }
    let clipped_pauses: Vec<(u64, u64)> = stream
        .pauses
        .iter()
        .map(|&(s, e)| (s.max(day_start_ms), e.min(day_end_ms)))
        .filter(|(s, e)| e > s)
        .collect();
    let offs = merge_intervals(&clipped_pauses);
    let mut events: Vec<FocusEvent> = Vec::new();
    for &(px, py) in &offs {
        events.push(FocusEvent {
            s: px,
            e: py,
            app: String::new(),
            min_ms: 0,
            waste: false,
            is_break: true,
        });
    }
    for span in &stream.spans {
        let a = span.start_ms.max(day_start_ms);
        let b = span.end_ms.min(day_end_ms);
        if b <= a {
            continue;
        }
        let min_ms = span.category.min_run_ms();
        let waste = span.category.is_waste();
        for (pa, pb) in subtract_intervals(a, b, &offs) {
            events.push(FocusEvent {
                s: pa,
                e: pb,
                app: span.app.clone(),
                min_ms,
                waste,
                is_break: false,
            });
        }
    }
    events.sort_by(|x, y| x.s.cmp(&y.s).then_with(|| y.is_break.cmp(&x.is_break)));
    let mut qualified: Vec<(u64, u64)> = Vec::new();
    let mut cur: Option<OpenRun> = None;
    let mut pending: Vec<FocusEvent> = Vec::new();
    for ev in &events {
        process_event(ev, &mut cur, &mut pending, &mut qualified);
    }
    if let Some(run) = cur {
        if run.ms >= run.min_ms {
            qualified.extend(run.intervals);
        }
    }
    merge_intervals(&qualified)
}

/// Pure single-app qualifying focus projection across devices for `[day_start_ms, day_end_ms)`.
/// Excludes DNS-off pauses and wasting/Drift spans, then unions qualifying intervals across devices.
pub fn union_focus_ms(streams: &[FocusDeviceStream], day_start_ms: u64, day_end_ms: u64) -> u64 {
    if day_end_ms <= day_start_ms {
        return 0;
    }
    let mut all_qualified: Vec<(u64, u64)> = Vec::new();
    for stream in streams {
        all_qualified.extend(verified_focus_intervals(stream, day_start_ms, day_end_ms));
    }
    merge_intervals(&all_qualified)
        .into_iter()
        .map(|(s, e)| e - s)
        .sum()
}

fn validate_date_matches_day(date: &str, day: i32) -> Result<(), String> {
    if date.len() != 10 {
        return Err("Focus award date must be YYYY-MM-DD".into());
    }
    let parsed = NaiveDate::parse_from_str(date, "%Y-%m-%d")
        .map_err(|_| "Focus award date must be a valid YYYY-MM-DD calendar date".to_string())?;
    let expected_day = parsed.num_days_from_ce() - super::EPOCH_DAY;
    if expected_day != day {
        return Err(format!(
            "Focus award date {date} does not match epoch day {day} (expected {expected_day})"
        ));
    }
    Ok(())
}

/// Atomically mint finalized daily focus milestones (`focus:YYYY-MM-DD:1..N`) into the wallet.
/// Caller MUST run this inside the profile authority's `Store::update` transaction.
pub fn award_finalized_day(
    ledger: &mut Ledger,
    input: &FinalizedFocusDay,
    rate: &FocusRewardRate,
) -> Result<AwardOutcome, String> {
    if !rate.valid() {
        return Err("Invalid focus reward rate".into());
    }
    if input.day_end_ms <= input.day_start_ms {
        return Err("Invalid finalized focus day bounds".into());
    }
    validate_date_matches_day(&input.date, input.day)?;

    if !input.complete {
        return Ok(AwardOutcome {
            status: AwardStatus::NotFinalized,
            milestones_awarded: 0,
            already_present: 0,
            coins_awarded: 0,
        });
    }
    if !ledger.wallet_active(input.day) {
        return Ok(AwardOutcome {
            status: AwardStatus::WalletDormant,
            milestones_awarded: 0,
            already_present: 0,
            coins_awarded: 0,
        });
    }
    let milestones = (input.union_focus_ms / rate.milestone_ms)
        .min(u64::from(rate.max_milestones)) as u32;
    if milestones == 0 {
        return Ok(AwardOutcome {
            status: AwardStatus::NoMilestones,
            milestones_awarded: 0,
            already_present: 0,
            coins_awarded: 0,
        });
    }

    let reference = format!("{FOCUS_KIND}:{}", input.date);
    let mut awarded = 0u32;
    let mut already = 0u32;
    for n in 1..=milestones {
        let key = format!("{FOCUS_KIND}:{}:{n}", input.date);
        if ledger.earn(
            &key,
            FOCUS_KIND,
            rate.coins_per_milestone,
            &reference,
            input.at_ms,
            input.day,
        )? {
            awarded += 1;
        } else {
            already += 1;
        }
    }
    let status = if awarded > 0 {
        AwardStatus::Awarded
    } else {
        AwardStatus::AlreadyAwarded
    };
    Ok(AwardOutcome {
        status,
        milestones_awarded: awarded,
        already_present: already,
        coins_awarded: (awarded as i32) * rate.coins_per_milestone,
    })
}

/// Actual active focus coins awarded in the wallet for one epoch day (0 while dormant or unminted).
pub fn focus_coins_for_day(ledger: &Ledger, day: i32) -> i64 {
    focus_coins_in_range(ledger, day, day)
}

/// Actual active focus coins awarded in the wallet across an inclusive epoch day range.
pub fn focus_coins_in_range(ledger: &Ledger, from_day: i32, to_day: i32) -> i64 {
    ledger
        .entries(FOCUS_KIND, from_day, to_day)
        .into_iter()
        .map(|entry| i64::from(entry.amount))
        .sum()
}

#[cfg(test)]
mod tests {
    use super::*;

    const MIN: u64 = 60_000;
    const SEC: u64 = 1_000;
    const DAY_2026_11_25: i32 = 20_782; // 2026-11-25

    fn span(start_m: u64, end_m: u64, app: &str, category: FocusCategory) -> FocusSpan {
        FocusSpan {
            start_ms: start_m * MIN,
            end_ms: end_m * MIN,
            app: app.to_string(),
            category,
        }
    }

    #[test]
    fn pure_projection_enforces_thresholds_glance_grace_waste_breaks_and_union() {
        let laptop = FocusDeviceStream {
            spans: vec![
                // 15m + 30s glance to Other + 15m = 30m unbroken T3 Code
                FocusSpan { start_ms: 0, end_ms: 15 * MIN, app: "T3 Code".into(), category: FocusCategory::Coding },
                FocusSpan { start_ms: 15 * MIN, end_ms: 15 * MIN + 30 * SEC, app: "Terminal".into(), category: FocusCategory::Other },
                FocusSpan { start_ms: 15 * MIN + 30 * SEC, end_ms: 30 * MIN + 30 * SEC, app: "T3 Code".into(), category: FocusCategory::Coding },
                // 20m Notion broken by 10s Waste -> neither half qualifies
                FocusSpan { start_ms: 35 * MIN, end_ms: 55 * MIN, app: "Notion".into(), category: FocusCategory::Writing },
                FocusSpan { start_ms: 55 * MIN, end_ms: 55 * MIN + 10 * SEC, app: "X".into(), category: FocusCategory::Waste },
                FocusSpan { start_ms: 55 * MIN + 10 * SEC, end_ms: 65 * MIN + 10 * SEC, app: "Notion".into(), category: FocusCategory::Writing },
                // 60m YouTube -> never eligible
                span(70, 130, "YouTube", FocusCategory::Waste),
                // 40m T3 Code split by a 5m DNS-off pause -> 18m + 17m, neither hits 25m
                span(135, 175, "T3 Code", FocusCategory::Coding),
            ],
            pauses: vec![(153 * MIN, 158 * MIN)],
        };
        let phone = FocusDeviceStream {
            spans: vec![
                // 16m Duolingo overlapping Laptop T3 Code [10m..26m] -> qualifies (15m learning min)
                span(10, 26, "Duolingo", FocusCategory::Learning),
                // 14m Readest -> under 15m min
                span(40, 54, "Readest", FocusCategory::Reading),
                // 18m NotebookLM -> qualifies (15m learning min)
                span(162, 180, "NotebookLM", FocusCategory::Learning),
            ],
            pauses: vec![],
        };

        let laptop_ivs = verified_focus_intervals(&laptop, 0, 200 * MIN);
        assert_eq!(laptop_ivs, vec![(0, 15 * MIN), (15 * MIN + 30 * SEC, 30 * MIN + 30 * SEC)]);
        let phone_ivs = verified_focus_intervals(&phone, 0, 200 * MIN);
        assert_eq!(phone_ivs, vec![(10 * MIN, 26 * MIN), (162 * MIN, 180 * MIN)]);

        let union = union_focus_ms(&[laptop, phone], 0, 200 * MIN);
        // Union of [0..15m], [15m30s..30m30s], [10m..26m], [162m..180m] = 30.5m + 18m = 48.5m
        assert_eq!(union, 48 * MIN + 30 * SEC);
    }

    #[test]
    fn award_finalized_day_is_gated_atomic_idempotent_and_capped() {
        let mut ledger = Ledger::default();
        let rate = FocusRewardRate::default();
        let base_day = FinalizedFocusDay {
            date: "2026-11-25".into(),
            day: DAY_2026_11_25,
            day_start_ms: 1_795_564_800_000,
            day_end_ms: 1_795_651_200_000,
            complete: true,
            union_focus_ms: 135 * MIN, // 4.5 blocks -> capped at 4 milestones = 20 coins
            at_ms: 1_795_651_205_000,
        };

        // 1. Dormant wallet awards nothing.
        let dormant = award_finalized_day(&mut ledger, &base_day, &rate).unwrap();
        assert_eq!(dormant.status, AwardStatus::WalletDormant);
        assert_eq!(ledger.balance(), 0);

        // Activate wallet for DAY_2026_11_25.
        ledger.start_wallet(DAY_2026_11_25 - 1).unwrap();

        // 2. Incomplete / Pending day awards nothing.
        let mut pending = base_day.clone();
        pending.complete = false;
        let pending_res = award_finalized_day(&mut ledger, &pending, &rate).unwrap();
        assert_eq!(pending_res.status, AwardStatus::NotFinalized);
        assert_eq!(ledger.balance(), 0);

        // 3. Mismatched date/epoch day fails cleanly without writing anything.
        let mut bad_date = base_day.clone();
        bad_date.date = "2026-11-26".into();
        assert!(award_finalized_day(&mut ledger, &bad_date, &rate).is_err());
        assert_eq!(ledger.balance(), 0);

        // 4. Complete finalized day mints focus:2026-11-25:1..4 atomically (20 coins).
        let awarded = award_finalized_day(&mut ledger, &base_day, &rate).unwrap();
        assert_eq!(awarded.status, AwardStatus::Awarded);
        assert_eq!(awarded.milestones_awarded, 4);
        assert_eq!(awarded.coins_awarded, 20);
        assert_eq!(ledger.balance(), 20);
        assert_eq!(focus_coins_for_day(&ledger, DAY_2026_11_25), 20);

        let entries = ledger.entries(FOCUS_KIND, DAY_2026_11_25, DAY_2026_11_25);
        assert_eq!(entries.len(), 4);
        assert_eq!(entries[0].id, "focus:2026-11-25:1");
        assert_eq!(entries[3].id, "focus:2026-11-25:4");
        assert!(entries.iter().all(|e| e.reference == "focus:2026-11-25" && e.amount == 5));

        // 5. Retry is idempotent (AlreadyAwarded, 0 new coins).
        let retry = award_finalized_day(&mut ledger, &base_day, &rate).unwrap();
        assert_eq!(retry.status, AwardStatus::AlreadyAwarded);
        assert_eq!(retry.milestones_awarded, 0);
        assert_eq!(retry.already_present, 4);
        assert_eq!(ledger.balance(), 20);

        // 6. Sync merge with another peer awarding the same day does not double-count.
        let mut peer = Ledger::default();
        peer.start_wallet(DAY_2026_11_25 - 1).unwrap();
        award_finalized_day(&mut peer, &base_day, &rate).unwrap();
        ledger.merge(peer);
        assert_eq!(ledger.balance(), 20);
        assert_eq!(focus_coins_for_day(&ledger, DAY_2026_11_25), 20);

        // 7. Reversing one milestone reduces active focus coins for that day.
        assert!(ledger
            .reverse("focus:2026-11-25:4", base_day.at_ms + 1_000, DAY_2026_11_25)
            .unwrap());
        assert_eq!(focus_coins_for_day(&ledger, DAY_2026_11_25), 15);
    }
}
