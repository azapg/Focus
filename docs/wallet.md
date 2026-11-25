# Wallet integration

The wallet is a backend feature. The store UI can read `wallet_view` and call
`wallet_buy_time` on desktop. The phone and TV use `HabitStore.read()` and
`HabitStore.buyTimeOff`. The desktop purchase response is
`{status: "bought" | "already_bought" | "limit_reached"}` or
`{status: "not_enough", missing: number}`. Generate a fresh request ID for
each confirmation and reuse it for retries, even after midnight. Use the latest
view after `wallet-updated`. Native callers use the existing habit-store listener.

## Backend callers

Write under the existing store lock with Rust `Store::update(|ledger| ...)`
or Java `HabitStore.updateWallet(wallet -> ...)`. The operation receives
the transaction's current ledger, checks its preconditions and returns after
persistence. Do any app notifications and peer refreshes after it returns.
Use the transaction for multi-entry awards so validation and persistence
fail together if the caller propagates a validation error. Keep `earn`, `spend`, `reverse` and activation native.

- `balance` returns signed coins. `entries(kind, fromDay, toDay)` uses inclusive
  epoch days and excludes reversed entries. Habit rewards come from the active
  check-offs in the habit ledger.
- Repair eligibility and pricing belong to the streak backend. Within the
  transaction, use `spend("streak:clean:YYYY-MM-DD", "streak_repair", price,
  "clean:YYYY-MM-DD", now, purchaseDay)`. A retry uses the same key. To recover
  repaired dates, query purchases of kind `streak_repair` and read their
  references. The entry day is the **purchase** day. The reference is the
  repaired day. Repair preserves the previous streak count.
- Only a finalized focus aggregator should call `earn` with kind `focus`.
  Use stable milestone keys shared by all devices, such as
  `focus:YYYY-MM-DD:1`, without device IDs. The aggregator owns the rate, daily
  cap, profile timezone and finalization, including late evidence. Count the
  union of verified focus intervals, excluding drift, breaks and non-focus
  screen time. The wallet ships without a focus award producer.

Keys, kinds and references use ASCII letters, digits and `-_.:`. The
limits are 80, 32 and 120 characters respectively. A reference may be empty.
The reserved key prefixes are `time:` and `reverse:`. Generic entry amounts
range from 1 to 10,000 coins per operation.

## Activation and time off

The foundation ships dormant. When the store is ready to launch, the app
schedules activation for the following local calendar day through
`Ledger::start_wallet(today)` or `Wallet.start(today)`. The earliest scheduled
start converges across wallet-capable peers. Roll out the store to paired
devices and sync the scheduled start before that midnight. Existing
check-offs still give legacy time off through the setup day. Only check-offs
recorded with the wallet active earn coins, so records from older devices,
including late arrivals, never turn past free time into coins. A device
learning of an elapsed start switches immediately. Recorded pause use
and already-running pauses stay in effect. Older devices still enforce the
legacy allowance until updated and synced. The desktop wallet view reports
paired snapshots missing the supported version as `needs_upgrade`.

The existing base pause allowance and pause rules apply. Purchased time adds
to today's allowance and expires at local midnight. The existing daily habit
credit cap becomes the purchase cap. The staged time-off rate is one coin per
minute. Entries store the original cost and minute count separately so a
future price change can preserve earlier purchases.

## Sync and offline purchases

The `/v1/habits` exchange advertises `wallet_sync: 1` in snapshots. Wallet
pages and check-off flags are sent when both clients use version 1. The
`w:` cursor follows the existing habit pages. Legacy clients can continue
exchanging habits without wallet data.

A device checks balance and the purchase cap against its local copy. Two
offline devices can spend the same balance or buy more than the shared cap.
Sync keeps their accepted purchases, even when the balance goes negative or
the day exceeds its cap. New purchases must meet both checks using the
merged totals. An undone check-off can likewise put the wallet in debt.
Each entry key counts once. Conflicting payloads for one key keep the earliest
timestamp, then the lowest day, signed amount, kind and reference in that
order. A reversal is a single tombstone for its target, independent of arrival
order. Spent coins and completed time-off entitlement are independent from
later purchase prices.
