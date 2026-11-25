use super::{Ledger, START_DAY};
use serde::{Deserialize, Deserializer, Serialize};
use std::collections::BTreeMap;

pub const SYNC_VERSION: u32 = 1;
const MAX_AMOUNT: i32 = 10_000;
const TIME_OFF: &str = "time_off";
const REVERSAL: &str = "reversal";
const REVERSE_PREFIX: &str = "reverse:";
const TIME_PREFIX: &str = "time:";
const MAX_TIME_OFF: u16 = 60;
pub const TIME_OFF_PRICE_PER_MINUTE: i32 = 1;

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq, PartialOrd, Ord)]
pub struct WalletEntry {
    pub id: String,
    pub at: u64,
    pub day: i32,
    pub amount: i32,
    pub kind: String,
    #[serde(rename = "ref")]
    pub reference: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reverses: Option<String>,
}

#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum Purchase {
    Bought,
    AlreadyBought,
    NotEnough { missing: u64 },
    LimitReached,
}

fn valid_token(value: &str, max: usize) -> bool {
    !value.is_empty()
        && value.len() <= max
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"-_.:".contains(&b))
}

fn valid_key(key: &str) -> bool {
    valid_token(key, 80) && !key.starts_with(REVERSE_PREFIX)
}

fn valid_day(day: i32) -> bool {
    (START_DAY..=100_000).contains(&day)
}

impl WalletEntry {
    pub(super) fn valid(&self) -> bool {
        if self.at == 0
            || self.at > 253_402_300_799_999
            || !valid_day(self.day)
            || !valid_token(&self.kind, 32)
            || (!self.reference.is_empty() && !valid_token(&self.reference, 120))
        {
            return false;
        }
        if let Some(target) = &self.reverses {
            return valid_key(target)
                && self.id.strip_prefix(REVERSE_PREFIX) == Some(target)
                && self.kind == REVERSAL
                && self.reference.is_empty()
                && self.amount == 0;
        }
        if !valid_key(&self.id)
            || self.kind == REVERSAL
            || self.amount == 0
            || !(-MAX_AMOUNT..=MAX_AMOUNT).contains(&self.amount)
        {
            return false;
        }
        if self.kind == TIME_OFF {
            return self
                .id
                .strip_prefix(TIME_PREFIX)
                .is_some_and(|key| valid_token(key, 75))
                && self.amount < 0
                && self.purchased_minutes().is_some();
        }
        !self.id.starts_with(TIME_PREFIX)
    }

    fn purchased_minutes(&self) -> Option<u16> {
        let value = self.reference.strip_prefix("minutes:")?;
        let minutes: u16 = value.parse().ok()?;
        ((1..=MAX_TIME_OFF).contains(&minutes) && minutes.to_string() == value).then_some(minutes)
    }
}

pub(super) fn decode_start<'de, D: Deserializer<'de>>(
    deserializer: D,
) -> Result<Option<i32>, D::Error> {
    let raw = serde_json::Value::deserialize(deserializer)?;
    Ok(raw
        .as_i64()
        .and_then(|value| i32::try_from(value).ok())
        .filter(|day| valid_day(*day)))
}

pub(super) fn decode_entries<'de, D: Deserializer<'de>>(
    deserializer: D,
) -> Result<BTreeMap<String, WalletEntry>, D::Error> {
    let raw = serde_json::Value::deserialize(deserializer)?;
    let Some(entries) = raw.as_object() else {
        return Ok(BTreeMap::new());
    };
    Ok(entries
        .iter()
        .filter_map(|(key, value)| {
            let entry: WalletEntry = serde_json::from_value(value.clone()).ok()?;
            (key == &entry.id && entry.valid()).then(|| (key.clone(), entry))
        })
        .collect())
}

impl Ledger {
    /// Schedule the cutover for the next local calendar day. Take the earliest
    /// scheduled start seen across peers, including when a device upgrades late.
    pub fn start_wallet(&mut self, today: i32) -> Result<(), String> {
        let start = today
            .checked_add(1)
            .filter(|day| valid_day(*day))
            .ok_or("Invalid wallet day")?;
        self.merge_wallet_start(Some(start));
        Ok(())
    }

    pub(super) fn merge_wallet_start(&mut self, incoming: Option<i32>) {
        if let Some(day) = incoming.filter(|day| valid_day(*day)) {
            self.wallet_start = Some(self.wallet_start.map_or(day, |old| old.min(day)));
        }
    }

    pub(super) fn merge_wallet_entries(&mut self, incoming: BTreeMap<String, WalletEntry>) {
        for (key, entry) in incoming {
            if key == entry.id && entry.valid() {
                let prior = self
                    .wallet_entries
                    .entry(key)
                    .or_insert_with(|| entry.clone());
                // Earliest writer wins, with a full tie-break for clock equality.
                if entry < *prior {
                    *prior = entry;
                }
            }
        }
    }

    pub fn wallet_active(&self, day: i32) -> bool {
        self.wallet_start.is_some_and(|start| day >= start)
    }

    pub fn habit_coins(&self) -> i64 {
        self.completions()
            .into_values()
            .filter(|event| event.wallet_reward && self.wallet_active(event.day))
            .map(|event| i64::from(event.reward))
            .sum()
    }

    fn wallet_entries_active(&self) -> impl Iterator<Item = &WalletEntry> {
        self.wallet_entries.values().filter(|entry| {
            entry.reverses.is_none()
                && !self
                    .wallet_entries
                    .contains_key(&format!("{REVERSE_PREFIX}{}", entry.id))
        })
    }

    pub fn balance(&self) -> i64 {
        self.habit_coins()
            + self
                .wallet_entries_active()
                .map(|entry| i64::from(entry.amount))
                .sum::<i64>()
    }

    pub fn entries(&self, kind: &str, from_day: i32, to_day: i32) -> Vec<&WalletEntry> {
        self.wallet_entries_active()
            .filter(|entry| entry.kind == kind && (from_day..=to_day).contains(&entry.day))
            .collect()
    }

    pub fn time_off_bought(&self, day: i32) -> i64 {
        self.entries(TIME_OFF, day, day)
            .into_iter()
            .filter_map(WalletEntry::purchased_minutes)
            .map(i64::from)
            .sum()
    }

    /// Offline peers can exceed the cap together. Keep every accepted purchase.
    pub fn time_off_limit_remaining(&self, day: i32) -> u16 {
        (i64::from(self.cap_on(day)) - self.time_off_bought(day)).max(0) as u16
    }

    pub fn extra_pause_minutes(&self, day: i32) -> i32 {
        if self.wallet_active(day) {
            self.time_off_bought(day).min(i64::from(i32::MAX - 300)) as i32
        } else {
            i32::from(self.credit(day))
        }
    }

    pub fn earn(
        &mut self,
        key: &str,
        kind: &str,
        amount: i32,
        reference: &str,
        at: u64,
        day: i32,
    ) -> Result<bool, String> {
        let entry = self.new_entry(key, kind, amount, reference, at, day)?;
        if amount <= 0 || kind == TIME_OFF {
            return Err("Invalid wallet earning".into());
        }
        if self.wallet_entries.contains_key(key) {
            return Ok(false);
        }
        self.wallet_entries.insert(entry.id.clone(), entry);
        Ok(true)
    }

    pub fn spend(
        &mut self,
        key: &str,
        kind: &str,
        amount: i32,
        reference: &str,
        at: u64,
        day: i32,
    ) -> Result<Purchase, String> {
        if kind == TIME_OFF || amount <= 0 {
            return Err("Invalid wallet spend".into());
        }
        let entry = self.new_entry(key, kind, -amount, reference, at, day)?;
        self.purchase(entry, None)
    }

    pub fn buy_time_off(
        &mut self,
        request_id: &str,
        minutes: u16,
        at: u64,
        day: i32,
    ) -> Result<Purchase, String> {
        if !(1..=MAX_TIME_OFF).contains(&minutes) || !valid_token(request_id, 75) {
            return Err("Invalid time off purchase".into());
        }
        let entry = self.new_entry(
            &format!("{TIME_PREFIX}{request_id}"),
            TIME_OFF,
            -i32::from(minutes) * TIME_OFF_PRICE_PER_MINUTE,
            &format!("minutes:{minutes}"),
            at,
            day,
        )?;
        self.purchase(entry, Some(minutes))
    }

    fn purchase(&mut self, entry: WalletEntry, minutes: Option<u16>) -> Result<Purchase, String> {
        if self.wallet_entries.contains_key(&entry.id) {
            return Ok(Purchase::AlreadyBought);
        }
        if !self.wallet_active(entry.day) {
            return Err("Wallet has not opened yet".into());
        }
        if minutes.is_some_and(|minutes| self.time_off_limit_remaining(entry.day) < minutes) {
            return Ok(Purchase::LimitReached);
        }
        let missing = i64::from(-entry.amount).saturating_sub(self.balance());
        if missing > 0 {
            return Ok(Purchase::NotEnough {
                missing: missing as u64,
            });
        }
        self.wallet_entries.insert(entry.id.clone(), entry);
        Ok(Purchase::Bought)
    }

    pub fn reverse(&mut self, key: &str, at: u64, day: i32) -> Result<bool, String> {
        let id = format!("{REVERSE_PREFIX}{key}");
        if !self.wallet_entries.contains_key(key) || self.wallet_entries.contains_key(&id) {
            return Ok(false);
        }
        let entry = WalletEntry {
            id,
            at,
            day,
            amount: 0,
            kind: REVERSAL.into(),
            reference: String::new(),
            reverses: Some(key.into()),
        };
        if !entry.valid() {
            return Err("Invalid wallet reversal".into());
        }
        self.wallet_entries.insert(entry.id.clone(), entry);
        Ok(true)
    }

    fn new_entry(
        &self,
        key: &str,
        kind: &str,
        amount: i32,
        reference: &str,
        at: u64,
        day: i32,
    ) -> Result<WalletEntry, String> {
        let entry = WalletEntry {
            id: key.into(),
            at,
            day,
            amount,
            kind: kind.into(),
            reference: reference.into(),
            reverses: None,
        };
        if entry.valid() {
            Ok(entry)
        } else {
            Err("Invalid wallet entry".into())
        }
    }
}
