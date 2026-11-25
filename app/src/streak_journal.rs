//! Native authority-local streak journal. Peers receive wallet entries only.
//! Only trusted collectors and device identity plumbing may invoke these operations.

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

use crate::clean_streak::{Evidence, FinalizeError, FinalizedDay, FreezePurchase, Streak};
use crate::habits::{Ledger, Purchase, Store};

const FREEZE_KIND: &str = "streak_freeze";
const MAX_HISTORY: usize = 90;

#[derive(Clone, Debug, PartialEq, Eq, Deserialize, Serialize)]
pub struct Profile {
    pub authority: String,
    /// IANA timezone used by the trusted producer to resolve day boundaries.
    pub timezone: String,
    pub devices: BTreeSet<String>,
    pub sync_grace_ms: u64,
    pub price_coins: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, Deserialize, Serialize)]
pub(crate) struct Journal {
    profile: Profile,
    streak: Streak,
    days: BTreeMap<i32, FinalizedDay>,
}

/// Covers activity and idle time. A device attests only intervals it actually
/// observed. Unresolved classifications and collector outages leave gaps.
pub struct DeviceEvidence {
    pub device: String,
    /// Receipt time assigned by the authority as the collector report arrives.
    pub received_at_ms: u64,
    pub covered: Vec<(u64, u64)>,
    pub has_verified_activity: bool,
    pub confirmed_drift: bool,
    pub verified_focus: Vec<(u64, u64)>,
}

pub struct DayEvidence {
    pub timezone: String,
    pub day: i32,
    pub start_ms: u64,
    pub end_ms: u64,
    pub devices: Vec<DeviceEvidence>,
}

/// Native desktop observation. Strict Drift is derived only from verified
/// routes with an explicit streak rule, independently of the reward detector.
pub fn desktop_evidence(
    device: &str,
    tracker: &crate::activity::Tracker,
    extractor: &mut crate::focus_extract::FocusExtractor,
    pauses: &[crate::pause_history::PauseRec],
    start_ms: u64,
    end_ms: u64,
    received_at_ms: u64,
) -> Result<DeviceEvidence, String> {
    let spans = tracker.spans(start_ms, end_ms);
    let extracted = extractor.extract_desktop_spans(device, &spans, pauses, start_ms, end_ms)?;
    let mut confirmed_drift = false;
    let mut classified = BTreeSet::new();
    let mut cuts = Vec::new();
    for span in &spans {
        let bounds = (span.s.max(start_ms), span.e.min(end_ms));
        if bounds.1 > bounds.0 {
            if let Some(drift) = strict_browser_disposition(span) {
                classified.insert(bounds);
                confirmed_drift |= drift;
            } else if needs_verified_place(span) {
                cuts.push(bounds);
            }
        }
    }

    // Coverage samples record the tracker’s visibility. Unresolved Waste
    // activity still leaves a gap in streak classification.
    cuts.extend(
        extracted
            .stream
            .spans
            .iter()
            .filter(|span| span.category.is_waste())
            .map(|span| (span.start_ms, span.end_ms))
            .filter(|bounds| !classified.contains(bounds)),
    );
    cuts.sort_unstable();
    let covered = subtract_intervals(tracker.coverage_intervals(start_ms, end_ms), &cuts);
    Ok(DeviceEvidence {
        device: extracted.device_id,
        received_at_ms,
        covered,
        has_verified_activity: extracted.verified_active_ms > 0,
        confirmed_drift,
        verified_focus: extracted.verified_focus_intervals,
    })
}

fn subtract_intervals(mut intervals: Vec<(u64, u64)>, cuts: &[(u64, u64)]) -> Vec<(u64, u64)> {
    intervals.sort_unstable();
    let mut next_cut = 0;
    let mut result = Vec::new();
    let mut previous_end = 0;
    for (start, end) in intervals {
        let mut start = start.max(previous_end);
        previous_end = previous_end.max(end);
        if start >= end {
            continue;
        }
        while next_cut < cuts.len() {
            let (left, right) = cuts[next_cut];
            if left >= end {
                break;
            }
            if left > start {
                result.push((start, left));
            }
            start = start.max(right);
            if start >= end {
                break;
            }
            next_cut += 1;
        }
        if start < end {
            result.push((start, end));
        }
    }
    result
}

fn needs_verified_place(span: &crate::activity::Span) -> bool {
    if ["X", "WhatsApp", "WhatsApp Business", "YouTube"]
        .iter()
        .any(|app| span.app.eq_ignore_ascii_case(app))
    {
        return true;
    }
    span.browser
        .as_ref()
        .and_then(|context| context.url.as_deref())
        .and_then(|url| url::Url::parse(url).ok())
        .is_some_and(|url| {
            matches!(
                url.host_str(),
                Some(
                    "x.com"
                        | "www.x.com"
                        | "twitter.com"
                        | "www.twitter.com"
                        | "youtube.com"
                        | "www.youtube.com"
                        | "m.youtube.com"
                        | "youtu.be"
                        | "web.whatsapp.com"
                )
            )
        })
}

/// Some(true) is confirmed Drift; Some(false) is an explicitly clean place.
/// None means the browser route cannot settle streak classification.
fn strict_browser_disposition(span: &crate::activity::Span) -> Option<bool> {
    if span.source != "chromium_extension" {
        return None;
    }
    let context = span.browser.as_ref()?;
    let url = url::Url::parse(context.url.as_deref()?).ok()?;
    if !matches!(url.scheme(), "https" | "http") {
        return None;
    }
    let path = url.path().trim_end_matches('/');
    match url.host_str()? {
        "x.com" | "www.x.com" | "twitter.com" | "www.twitter.com" => {
            if path == "/home" {
                return Some(true);
            }
            if path == "/search"
                && url.query_pairs().any(|(key, value)| {
                    key == "q" && !value.trim().is_empty() && value != "REDACTED"
                })
            {
                return Some(false);
            }
            let handle = path.strip_prefix('/')?;
            if !handle.is_empty()
                && handle
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'_')
            {
                let title = context.title.as_deref()?.trim().to_ascii_lowercase();
                let suffix = format!("(@{})", handle.to_ascii_lowercase());
                let title = title
                    .strip_suffix(" / x")
                    .or_else(|| title.strip_suffix(" / twitter"))
                    .unwrap_or(&title);
                if title.ends_with(&suffix) {
                    return Some(false);
                }
            }
            None
        }
        "youtube.com" | "www.youtube.com" | "m.youtube.com"
            if path == "/shorts" || path.starts_with("/shorts/") =>
        {
            Some(true)
        }
        _ => None,
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StreakView {
    pub count: u32,
    pub days: Vec<FinalizedDay>,
    pub freeze_ready: bool,
    pub freeze_eligible_from_day: Option<i32>,
    pub pending_day: Option<i32>,
    pub can_buy: bool,
    pub price_coins: u32,
    pub balance_coins: i64,
    pub unavailable_reason: Option<&'static str>,
}

/// Bootstrap once, on the designated device. A native setup flow must supply
/// this configuration and verified collector enrollment.
pub fn configure(
    store: &Store,
    local_device: &str,
    profile: Profile,
    first_day: i32,
    first_start_ms: u64,
) -> Result<(), String> {
    if local_device.is_empty()
        || profile.authority != local_device
        || !profile.devices.contains(local_device)
        || profile.devices.iter().any(String::is_empty)
        || profile.timezone.trim().is_empty()
        || !(1..=10_000).contains(&profile.price_coins)
    {
        return Err("Invalid streak authority or profile".into());
    }
    store.update(|ledger| {
        if ledger.streak_journal.is_some() {
            return Err("Streak authority is already configured".into());
        }
        ledger.streak_journal = Some(Journal {
            profile,
            streak: Streak::new(first_day, first_start_ms),
            days: BTreeMap::new(),
        });
        Ok(())
    })
}

pub fn streak_view(store: &Store, local_device: &str, today: i32) -> Option<StreakView> {
    let ledger = store.read();
    let journal = ledger.streak_journal.as_ref()?;
    let balance = ledger.balance();
    let reason = if journal.profile.authority != local_device {
        Some("authority_required")
    } else if !ledger.wallet_active(today) {
        Some("wallet_dormant")
    } else if journal.streak.freeze_ready() {
        Some("slot_full")
    } else if balance < i64::from(journal.profile.price_coins) {
        Some("insufficient_coins")
    } else {
        None
    };
    Some(StreakView {
        count: journal.streak.count(),
        days: journal.days.values().rev().copied().collect(),
        freeze_ready: journal.streak.freeze_ready(),
        freeze_eligible_from_day: journal.streak.freeze_eligible_from_day(),
        pending_day: (journal.streak.next_day() < today).then(|| journal.streak.next_day()),
        can_buy: reason.is_none(),
        price_coins: journal.profile.price_coins,
        balance_coins: balance,
        unavailable_reason: reason,
    })
}

pub fn streak_buy_freeze(
    store: &Store,
    local_device: &str,
    request_id: &str,
    purchase_day: i32,
    now_ms: u64,
) -> Result<FreezePurchase, String> {
    if request_id.is_empty()
        || request_id.len() > 65
        || !request_id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"-_".contains(&b))
    {
        return Err("Invalid freeze request ID".into());
    }
    let key = format!("streak:freeze:{request_id}");
    store.update(|ledger| {
        let active = ledger.wallet_active(purchase_day);
        let Some(mut journal) = ledger.streak_journal.take() else {
            return Ok(FreezePurchase::Unavailable);
        };
        if !active || journal.profile.authority != local_device {
            ledger.streak_journal = Some(journal);
            return Ok(FreezePurchase::Unavailable);
        }
        let receipt_exists = ledger
            .entries(FREEZE_KIND, i32::MIN, i32::MAX)
            .iter()
            .any(|entry| entry.id == key);
        let mut debit_error = None;
        let outcome = journal.streak.buy_freeze(
            receipt_exists,
            purchase_day,
            journal.profile.price_coins,
            |amount| match ledger.spend(&key, FREEZE_KIND, amount as i32, "", now_ms, purchase_day)
            {
                Ok(Purchase::Bought) => FreezePurchase::Bought,
                Ok(Purchase::AlreadyBought) => FreezePurchase::AlreadyBought,
                Ok(Purchase::NotEnough { .. }) => FreezePurchase::InsufficientCoins,
                Ok(Purchase::LimitReached) => FreezePurchase::Unavailable,
                Err(error) => {
                    debit_error = Some(error);
                    FreezePurchase::Unavailable
                }
            },
        );
        if let Some(error) = debit_error {
            return Err(error);
        }
        ledger.streak_journal = Some(journal);
        Ok(outcome)
    })
}

/// Finalize with the wallet reward adapter. The authority provides the
/// resolved day and its verified device reports, never a UI-supplied duration.
pub fn finalize_with_focus_rewards(
    store: &Store,
    local_device: &str,
    input: &DayEvidence,
    now_ms: u64,
    rate: &crate::habits::focus_rewards::FocusRewardRate,
) -> Result<Result<FinalizedDay, FinalizeError>, String> {
    use crate::habits::focus_rewards::{award_finalized_day, FinalizedFocusDay};
    let date = chrono::NaiveDate::from_ymd_opt(1970, 1, 1)
        .and_then(|epoch| epoch.checked_add_signed(chrono::Duration::days(i64::from(input.day))))
        .ok_or("Invalid streak profile date")?
        .to_string();
    finalize_day(store, local_device, input, now_ms, |ledger, day| {
        award_finalized_day(
            ledger,
            &FinalizedFocusDay {
                date,
                day: day.day,
                day_start_ms: input.start_ms,
                day_end_ms: input.end_ms,
                complete: true,
                union_focus_ms: day.eligible_focus_ms,
                at_ms: now_ms,
            },
            rate,
        )?;
        Ok(())
    })
}

/// A rejected or failed award aborts the same transaction as day closure and
/// freeze consumption. The trusted detector callback writes milestone receipts.
pub fn finalize_day(
    store: &Store,
    local_device: &str,
    input: &DayEvidence,
    now_ms: u64,
    award: impl FnOnce(&mut Ledger, &FinalizedDay) -> Result<(), String>,
) -> Result<Result<FinalizedDay, FinalizeError>, String> {
    store.update(|ledger| {
        let mut journal = ledger
            .streak_journal
            .take()
            .ok_or("Streak is unavailable")?;
        if journal.profile.authority != local_device || journal.profile.timezone != input.timezone {
            return Err("Streak authority or timezone mismatch".into());
        }
        let evidence = collect_evidence(&journal.profile, input, now_ms)?;
        let outcome = journal
            .streak
            .finalize(evidence, now_ms, journal.profile.sync_grace_ms);
        if let Ok(day) = outcome {
            if day.eligible_focus_ms > 0 && ledger.wallet_active(day.day) {
                award(ledger, &day)?;
            }
            journal.days.insert(day.day, day);
            if journal.days.len() > MAX_HISTORY {
                journal.days.pop_first();
            }
        }
        ledger.streak_journal = Some(journal);
        Ok(outcome)
    })
}

fn collect_evidence(
    profile: &Profile,
    input: &DayEvidence,
    now_ms: u64,
) -> Result<Evidence, String> {
    let mut reported = BTreeSet::new();
    let mut complete = true;
    let mut accepted = 0;
    let mut active = false;
    let mut drift = false;
    let mut focus = Vec::new();
    let start = input.start_ms;
    let end = input.end_ms;
    if end <= start {
        return Err("Invalid streak day bounds".into());
    }
    for device in &input.devices {
        if !profile.devices.contains(&device.device) || !reported.insert(&device.device) {
            return Err("Unexpected or duplicate coverage source".into());
        }
        if device.received_at_ms > now_ms {
            return Err("Coverage report receipt is in the future".into());
        }
        if device.received_at_ms > end.saturating_add(profile.sync_grace_ms) {
            continue;
        }
        accepted += 1;
        complete &= union_ms(device.covered.clone(), start, end) == end - start;
        active |= device.has_verified_activity;
        drift |= device.confirmed_drift;
        focus.extend_from_slice(&device.verified_focus);
    }
    Ok(Evidence {
        day: input.day,
        start_ms: start,
        end_ms: end,
        coverage_complete: complete && accepted == profile.devices.len(),
        has_verified_activity: active,
        confirmed_drift: drift,
        verified_focus_ms: union_ms(focus, start, end),
    })
}

fn union_ms(mut intervals: Vec<(u64, u64)>, start: u64, end: u64) -> u64 {
    intervals.sort_unstable();
    let mut cursor = start;
    let mut total = 0;
    for (left, right) in intervals {
        let left = left.max(cursor);
        let right = right.min(end);
        if right > left {
            total += right - left;
            cursor = right;
        }
    }
    total
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clean_streak::{DayStatus, TRIAL_FREEZE_COINS, TRIAL_SYNC_GRACE_MS};

    const DAY: i32 = 20_000;
    const START: u64 = 1_728_000_000_000;
    const LENGTH: u64 = 86_400_000;

    fn report(id: &str, start: u64, focus: (u64, u64)) -> DeviceEvidence {
        DeviceEvidence {
            device: id.into(),
            received_at_ms: start + LENGTH,
            covered: vec![(start, start + LENGTH)],
            has_verified_activity: true,
            confirmed_drift: false,
            verified_focus: vec![focus],
        }
    }

    #[test]
    fn strict_route_rules_ignore_generic_video_and_coarse_social_labels() {
        use crate::activity::{BrowserContext, Span};
        let mut span = Span {
            s: 0,
            e: 1,
            app: "Chrome".into(),
            label: "X".into(),
            source: "chromium_extension".into(),
            browser: Some(BrowserContext {
                url: None,
                title: None,
                visibility: "page".into(),
                audible: false,
                habit_feed: false,
                tab_id: None,
                window_id: None,
                browser_pid: 1,
                host_pid: 2,
            }),
        };
        for (url, expected) in [
            ("https://x.com/home", Some(true)),
            ("https://x.com/search?q=fictional", Some(false)),
            ("https://x.com/search?q=REDACTED", None),
            ("https://youtube.com/shorts/example", Some(true)),
            ("https://youtube.com/watch?v=example", None),
            ("https://youtube.com.example.invalid/shorts/example", None),
        ] {
            span.browser.as_mut().unwrap().url = Some(url.into());
            assert_eq!(strict_browser_disposition(&span), expected, "{url}");
        }
        span.browser.as_mut().unwrap().url = Some("https://x.com/home".into());
        span.source = "window_title".into();
        assert_eq!(strict_browser_disposition(&span), None);
        assert_eq!(
            subtract_intervals(vec![(0, 10), (12, 20)], &[(5, 14)]),
            vec![(0, 5), (14, 20)]
        );
    }

    #[test]
    fn coverage_requires_every_enrolled_source_and_focus_is_deduplicated() {
        let profile = Profile {
            authority: "desk".into(),
            timezone: "America/Punta_Arenas".into(),
            devices: BTreeSet::from(["desk".into(), "phone".into()]),
            sync_grace_ms: TRIAL_SYNC_GRACE_MS,
            price_coins: TRIAL_FREEZE_COINS,
        };
        let mut input = DayEvidence {
            timezone: profile.timezone.clone(),
            day: DAY,
            start_ms: START,
            end_ms: START + LENGTH,
            devices: vec![report("desk", START, (START, START + 100))],
        };
        assert!(
            !collect_evidence(&profile, &input, START + LENGTH + TRIAL_SYNC_GRACE_MS + 1)
                .unwrap()
                .coverage_complete
        );
        input
            .devices
            .push(report("phone", START, (START + 50, START + 200)));
        let evidence =
            collect_evidence(&profile, &input, START + LENGTH + TRIAL_SYNC_GRACE_MS + 1).unwrap();
        assert!(evidence.coverage_complete);
        assert_eq!(evidence.verified_focus_ms, 200);
        input.devices[1].covered = vec![(START, START + 400), (START + 401, START + LENGTH)];
        assert!(
            !collect_evidence(&profile, &input, START + LENGTH + TRIAL_SYNC_GRACE_MS + 1)
                .unwrap()
                .coverage_complete
        );
        input
            .devices
            .push(report("desk", START, (START, START + 100)));
        assert!(
            collect_evidence(&profile, &input, START + LENGTH + TRIAL_SYNC_GRACE_MS + 1).is_err()
        );
    }

    #[test]
    fn journal_wallet_and_freezes_survive_restart_and_failed_awards_roll_back() {
        let path =
            std::env::temp_dir().join(format!("focus-streak-journal-{}", std::process::id()));
        std::fs::create_dir_all(&path).unwrap();
        let store = Store::new(&path);
        store
            .update(|ledger| {
                ledger.start_wallet(DAY - 1)?;
                ledger.earn("trial-grant", "test", 100, "", START, DAY)?;
                Ok(())
            })
            .unwrap();
        configure(
            &store,
            "desk",
            Profile {
                authority: "desk".into(),
                timezone: "UTC".into(),
                devices: BTreeSet::from(["desk".into()]),
                sync_grace_ms: TRIAL_SYNC_GRACE_MS,
                price_coins: TRIAL_FREEZE_COINS,
            },
            DAY,
            START,
        )
        .unwrap();
        assert_eq!(
            streak_buy_freeze(&store, "phone", "test", DAY, START).unwrap(),
            FreezePurchase::Unavailable
        );
        assert_eq!(
            streak_buy_freeze(&store, "desk", "test", DAY, START).unwrap(),
            FreezePurchase::Bought
        );
        let mut input = DayEvidence {
            timezone: "UTC".into(),
            day: DAY,
            start_ms: START,
            end_ms: START + LENGTH,
            devices: vec![report("desk", START, (START, START + 100))],
        };
        assert!(
            finalize_day(&store, "desk", &input, input.end_ms, |ledger, _| {
                ledger.earn("partial-grant", "test", 5, "", START, DAY)?;
                Err("fixture award failure".into())
            })
            .is_err()
        );
        let store = Store::new(&path);
        let view = streak_view(&store, "desk", DAY).unwrap();
        assert_eq!(view.balance_coins, 40);
        assert!(view.days.is_empty() && view.freeze_ready);
        assert!(store.read().page("", true).0.streak_journal.is_none());
        assert_eq!(
            finalize_day(&store, "desk", &input, input.end_ms, |_, _| Ok(()))
                .unwrap()
                .unwrap()
                .status,
            DayStatus::Clean
        );
        input.day += 1;
        input.start_ms += LENGTH;
        input.end_ms += LENGTH;
        input.devices = vec![report(
            "desk",
            input.start_ms,
            (input.start_ms, input.start_ms + 100),
        )];
        input.devices[0].confirmed_drift = true;
        input.devices[0].verified_focus = vec![(input.start_ms, input.start_ms + 60 * 60 * 1_000)];
        assert_eq!(
            finalize_with_focus_rewards(
                &store,
                "desk",
                &input,
                input.end_ms,
                &crate::habits::focus_rewards::FocusRewardRate::default()
            )
            .unwrap()
            .unwrap()
            .status,
            DayStatus::Frozen
        );
        let store = Store::new(&path);
        let view = streak_view(&store, "desk", DAY + 1).unwrap();
        assert!(!view.freeze_ready);
        assert_eq!(view.count, 1);
        assert_eq!(view.days[0].status, DayStatus::Frozen);
        assert_eq!(
            streak_buy_freeze(&store, "desk", "test", DAY + 1, input.end_ms).unwrap(),
            FreezePurchase::AlreadyBought
        );
        assert_eq!(store.read().balance(), 50);
        std::fs::remove_dir_all(&path).unwrap();
    }
}
