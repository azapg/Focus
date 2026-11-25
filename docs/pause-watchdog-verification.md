# Pause/watchdog fix verification

Verified on Windows on 2026-11-16. All new checks use fake DNS commands and
temporary data. No personal activity, pause reasons, or logs are included here.
The installed application and the machine's DNS settings were not changed.

## What changes

Previously, the watchdog read pause state, released the state lock, and then
waited for a DNS status command. A simultaneous pause could turn DNS off before
that command ran. The watchdog treated the intentional change as external
interference and immediately turned DNS back on.

Pause, startup, timer expiry, manual resume, and watchdog repairs now hold the
same state lock through the decision, DNS command, and state/history commit.
Read-only DNS verification takes only the DNS command lock. Lock order is
state then DNS, with no reverse acquisition.

## Evidence

- `./scripts/build.sh`: native Windows release builds succeed for the app and
  browser host. No install command is run.
- `cargo test --release --locked --manifest-path app/Cargo.toml`, run from the
  Windows build directory: 17 tests pass, including 11 new DNS/history regressions.
- `node --test tests/extension.test.mjs tests/stats.test.mjs`: 28 tests pass.
- `bash -n scripts/build.sh` and `git diff --check`: pass.

The concurrency regressions block a fake DNS command with channels and start
the competing operation on another thread. No five- or ten-minute sleeps are
needed. When DNS off runs first, the queued watchdog makes no status or repair
call and preserves the full 300,000 ms pause. When a status check runs first,
its repair finishes before the queued pause starts, preserving the full
600,000 ms pause. Startup and repeated watchdog checks also leave active
five- and ten-minute breaks intact.

As a negative control, the state lock was deliberately released before DNS off
in the disposable Windows build copy, reproducing the old lock boundary. The
new concurrency regression failed with:

```text
pause decision and DNS off must share the state lock
test result: FAILED. 0 passed; 1 failed
```

That mutation was removed and the full suite rerun against the fixed source.

The restore/retry regression simulates a deadline already overdue by 26 seconds.
A failed DNS-on attempt keeps the pause open and adds no resume record. A
successful retry records the real completion timestamp and the original pause
and deadline; history retains the extra 26 seconds. Another pause cannot
overwrite a restore still awaiting retry. Manual early resume, expired startup,
failed DNS off, old timestamp-only records, missing old resume records and
invalid trailing JSONL are covered too.

## Data and limits

State replacements use the existing atomic file writer. State-save failures
are logged. DNS commands log a process/operation ID, start, queue wait, duration
and outcome; startup logs a build ID. Resume events link to `paused_at` and store
`scheduled_until`, `requested_at`, and actual completion `t` separately.

Older history remains readable. Actual delays that old versions already capped
at the deadline cannot be reconstructed from that history. An incomplete old
pause falls back to its requested duration rather than borrowing a later pause's
resume. The currently active pause counts through the current time until restore
succeeds.

This fixes the watchdog's early cancellation and inaccurate end-time accounting.
It does not add browser request blocking or disconnect existing browser sessions.
NextDNS verification shows the resolver used by PowerShell, not that a specific
browser request was blocked. Cached content and connection reuse can still leave
an already-open site usable after DNS restoration; the existing browser activity
stream does not prove whether content came from the network.

The full elevated GUI was not run against live DNS as part of these checks.
State/history reads can wait while a DNS transition completes; this is intentional
so they cannot observe or act on an intermediate state.
