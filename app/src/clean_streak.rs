//! Pure rules for a profile authority. The caller persists each accepted transition
//! together with its wallet effects before publishing any result.

pub const TRIAL_SYNC_GRACE_MS: u64 = 24 * 60 * 60 * 1_000;
pub const TRIAL_FREEZE_COINS: u32 = 60;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Evidence {
    /// Calendar epoch day in the profile timezone. A resolved day may be 23 or 25 hours.
    pub day: i32,
    pub start_ms: u64,
    pub end_ms: u64,
    pub coverage_complete: bool,
    pub has_verified_activity: bool,
    pub confirmed_drift: bool,
    /// Union of qualifying intervals, clipped to this day and excluding pauses.
    pub verified_focus_ms: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DayStatus {
    Clean,
    Frozen,
    Drift,
    Unverified,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Deserialize, serde::Serialize)]
pub struct FinalizedDay {
    pub day: i32,
    pub status: DayStatus,
    pub count: u32,
    pub consumed_freeze: bool,
    /// Passed to the focus reward adapter. Incomplete days award nothing.
    pub eligible_focus_ms: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FinalizeError {
    InvalidEvidence,
    OutOfOrder,
    Pending,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum FreezePurchase {
    Bought,
    AlreadyBought,
    SlotFull,
    InsufficientCoins,
    Unavailable,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Deserialize, serde::Serialize)]
pub struct Streak {
    next_day: i32,
    next_start_ms: u64,
    count: u32,
    freeze_from_day: Option<i32>,
    needs_clean_day: bool,
}

impl Streak {
    pub fn new(first_day: i32, first_start_ms: u64) -> Self {
        Self {
            next_day: first_day,
            next_start_ms: first_start_ms,
            count: 0,
            freeze_from_day: None,
            needs_clean_day: false,
        }
    }

    pub fn count(&self) -> u32 {
        self.count
    }

    pub fn next_start_ms(&self) -> u64 {
        self.next_start_ms
    }

    pub fn next_day(&self) -> i32 {
        self.next_day
    }

    pub fn freeze_eligible_from_day(&self) -> Option<i32> {
        self.freeze_from_day
    }

    pub fn freeze_ready(&self) -> bool {
        self.freeze_from_day.is_some()
    }

    /// The caller checks the authoritative wallet for this request's receipt first.
    /// Charge and persist the resulting state under the same transaction lock.
    pub fn buy_freeze(
        &mut self,
        receipt_exists: bool,
        purchase_day: i32,
        price_coins: u32,
        mut debit: impl FnMut(u32) -> FreezePurchase,
    ) -> FreezePurchase {
        if receipt_exists {
            return FreezePurchase::AlreadyBought;
        }
        if self.freeze_ready() {
            return FreezePurchase::SlotFull;
        }
        if price_coins == 0 {
            return FreezePurchase::Unavailable;
        }
        let Some(eligible_from) = purchase_day.checked_add(1) else {
            return FreezePurchase::Unavailable;
        };
        let result = debit(price_coins);
        if result == FreezePurchase::Bought {
            self.freeze_from_day = Some(eligible_from);
        }
        result
    }

    pub fn finalize(
        &mut self,
        evidence: Evidence,
        now_ms: u64,
        sync_grace_ms: u64,
    ) -> Result<FinalizedDay, FinalizeError> {
        if evidence.day != self.next_day || evidence.start_ms != self.next_start_ms {
            return Err(FinalizeError::OutOfOrder);
        }
        let next_day = evidence
            .day
            .checked_add(1)
            .ok_or(FinalizeError::InvalidEvidence)?;
        let day_ms = evidence
            .end_ms
            .checked_sub(evidence.start_ms)
            .filter(|len| *len > 0)
            .ok_or(FinalizeError::InvalidEvidence)?;
        if evidence.verified_focus_ms > day_ms
            || (evidence.verified_focus_ms > 0 && !evidence.has_verified_activity)
        {
            return Err(FinalizeError::InvalidEvidence);
        }
        if now_ms < evidence.end_ms
            || (!evidence.coverage_complete
                && now_ms < evidence.end_ms.saturating_add(sync_grace_ms))
        {
            return Err(FinalizeError::Pending);
        }

        let status = if evidence.confirmed_drift {
            if self.count > 0
                && !self.needs_clean_day
                && self.freeze_from_day.is_some_and(|day| day <= evidence.day)
            {
                self.freeze_from_day = None;
                self.needs_clean_day = true;
                DayStatus::Frozen
            } else {
                self.count = 0;
                DayStatus::Drift
            }
        } else if evidence.coverage_complete && evidence.has_verified_activity {
            self.count = self.count.saturating_add(1);
            self.needs_clean_day = false;
            DayStatus::Clean
        } else {
            self.count = 0;
            DayStatus::Unverified
        };

        self.next_day = next_day;
        self.next_start_ms = evidence.end_ms;
        Ok(FinalizedDay {
            day: evidence.day,
            status,
            count: self.count,
            consumed_freeze: status == DayStatus::Frozen,
            eligible_focus_ms: if evidence.coverage_complete {
                evidence.verified_focus_ms
            } else {
                0
            },
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn day(n: i32) -> Evidence {
        Evidence {
            day: n,
            start_ms: n as u64 * 100,
            end_ms: (n as u64 + 1) * 100,
            coverage_complete: true,
            has_verified_activity: true,
            confirmed_drift: false,
            verified_focus_ms: 30,
        }
    }

    #[test]
    fn closes_in_order_and_waits_for_pending_evidence_through_deadline() {
        let mut streak = Streak::new(1, 100);
        let mut first = day(1);
        assert_eq!(
            streak.finalize(day(2), 500, 50),
            Err(FinalizeError::OutOfOrder)
        );
        assert_eq!(streak.finalize(first, 199, 50), Err(FinalizeError::Pending));
        first.coverage_complete = false;
        assert_eq!(streak.finalize(first, 249, 50), Err(FinalizeError::Pending));
        let closed = streak.finalize(first, 250, 50).unwrap();
        assert_eq!(closed.status, DayStatus::Unverified);
        assert_eq!(closed.eligible_focus_ms, 0);
        assert_eq!(
            streak.finalize(first, 300, 50),
            Err(FinalizeError::OutOfOrder)
        );
    }

    #[test]
    fn freeze_holds_one_miss_and_cannot_be_chained_by_rebuying() {
        let mut streak = Streak::new(1, 100);
        assert_eq!(streak.finalize(day(1), 200, 50).unwrap().count, 1);
        assert_eq!(
            streak.buy_freeze(false, 1, 60, |_| FreezePurchase::Bought),
            FreezePurchase::Bought
        );
        let missing = |n| Evidence {
            confirmed_drift: true,
            ..day(n)
        };
        let first = streak.finalize(missing(2), 300, 50).unwrap();
        assert_eq!(first.status, DayStatus::Frozen);
        assert_eq!(first.count, 1);
        assert!(first.consumed_freeze);
        assert_eq!(first.eligible_focus_ms, 30);
        assert_eq!(
            streak.buy_freeze(false, 2, 60, |_| FreezePurchase::Bought),
            FreezePurchase::Bought
        );
        let second = streak.finalize(missing(3), 400, 50).unwrap();
        assert_eq!(second.status, DayStatus::Drift);
        assert_eq!(second.count, 0);
        assert!(streak.freeze_ready());
        assert_eq!(streak.finalize(day(4), 500, 50).unwrap().count, 1);
        assert_eq!(
            streak.finalize(missing(5), 600, 50).unwrap().status,
            DayStatus::Frozen
        );
    }

    #[test]
    fn missing_coverage_preserves_inventory_but_confirmed_drift_can_use_it() {
        let mut streak = Streak::new(1, 100);
        streak.buy_freeze(false, 1, 60, |_| FreezePurchase::Bought);
        streak.finalize(day(1), 200, 50).unwrap();
        let gap = Evidence {
            coverage_complete: false,
            ..day(2)
        };
        assert_eq!(
            streak.finalize(gap, 350, 50).unwrap().status,
            DayStatus::Unverified
        );
        assert!(streak.freeze_ready());
        streak.finalize(day(3), 400, 50).unwrap();
        let slip = Evidence {
            confirmed_drift: true,
            coverage_complete: false,
            ..day(4)
        };
        let closed = streak.finalize(slip, 550, 50).unwrap();
        assert_eq!(closed.status, DayStatus::Frozen);
        assert_eq!(closed.eligible_focus_ms, 0);
    }

    #[test]
    fn retry_or_full_slot_never_charges_and_failed_purchase_does_not_arm() {
        let mut streak = Streak::new(1, 100);
        assert_eq!(
            streak.buy_freeze(false, 1, 60, |_| FreezePurchase::InsufficientCoins),
            FreezePurchase::InsufficientCoins
        );
        assert!(!streak.freeze_ready());
        assert_eq!(
            streak.buy_freeze(false, 1, 60, |_| FreezePurchase::Bought),
            FreezePurchase::Bought
        );
        assert_eq!(
            streak.buy_freeze(true, 1, 60, |_| panic!("retry charged")),
            FreezePurchase::AlreadyBought
        );
        assert_eq!(
            streak.buy_freeze(false, 1, 60, |_| panic!("full slot charged")),
            FreezePurchase::SlotFull
        );
    }

    #[test]
    fn buying_while_yesterday_syncs_cannot_retroactively_protect_it() {
        let mut streak = Streak::new(1, 100);
        streak.finalize(day(1), 200, 50).unwrap();
        assert_eq!(
            streak.buy_freeze(false, 3, 60, |_| FreezePurchase::Bought),
            FreezePurchase::Bought
        );
        let missed = Evidence {
            confirmed_drift: true,
            coverage_complete: false,
            ..day(2)
        };
        assert_eq!(
            streak.finalize(missed, 350, 50).unwrap().status,
            DayStatus::Drift
        );
        assert!(streak.freeze_ready());
    }

    #[test]
    fn empty_or_invalid_evidence_cannot_certify_a_clean_day() {
        let mut streak = Streak::new(1, 100);
        let mut empty = Evidence {
            has_verified_activity: false,
            ..day(1)
        };
        assert_eq!(
            streak.finalize(empty, 200, 50),
            Err(FinalizeError::InvalidEvidence)
        );
        empty.verified_focus_ms = 0;
        assert_eq!(
            streak.finalize(empty, 200, 50).unwrap().status,
            DayStatus::Unverified
        );
    }
}
