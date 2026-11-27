# Wallet integration

On Windows and Android phone, the coin balance in Today opens the Store.
Free Time is also available from the TV block screen. The desktop Store
reads `wallet_view` and calls `wallet_buy_time`. The phone and TV read and
update the same ledger through `HabitStore`. The desktop purchase response is
`{status: "bought" | "already_bought" | "limit_reached"}` or
`{status: "not_enough", missing: number}`. Generate a fresh request ID for
each confirmation and reuse it for retries, even after midnight. Pass the
quoted `expectedDay` so a new purchase cannot go through after its day ends.
Use the latest view after `wallet-updated`. Native callers use the existing
habit-store listener.

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
- The streak backend owns purchase eligibility, price and consumption for a
  prepaid Streak Freeze. The Store needs an authoritative read and purchase
  interface before it can offer freezes. A purchase and its inventory change
  belong in the same transaction. A freeze preserves the previous streak count
  on a qualifying future day.
- Windows awards 5 coins per 30 minutes of verified deep focus, up to 60
  coins per completed local day. It unions laptop and paired phone activity,
  excluding drift, breaks and non-focus screen time. It checks recent days
  again when evidence can arrive late. Stable milestone keys such as
  `focus:YYYY-MM-DD:1` keep retries and any active streak trial from paying
  twice.

Keys, kinds and references use ASCII letters, digits and `-_.:`. The
limits are 80, 32 and 120 characters respectively. A reference may be empty.
The reserved key prefixes are `time:` and `reverse:`. Generic entry amounts
range from 1 to 10,000 coins per operation.

## Activation and time off

Windows, Android phone and Android TV open the wallet by default on the local
day they first run this version, preserving any existing wallet start. This
works without Clean Streak enrollment. The earliest start converges across
wallet-capable peers. Legacy habit minutes still count through the cutover
day. Check-offs recorded with the wallet active earn coins. Recorded pause
use and already-running pauses stay in effect. The desktop wallet view reports
paired snapshots missing the supported sync version as `needs_upgrade`.

Free Time costs 3 coins per minute and starts immediately on the chosen
device. The default daily purchase limit is 15 minutes, with existing
configured caps preserved. Entries store the original cost and minute count
separately so a future price change can preserve earlier purchases.

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
