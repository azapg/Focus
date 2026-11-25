<img src="assets/icon.svg" width="64" height="64" alt="Focus">

# Focus

My personal guardrail system for focusing on what matters.

**Android Phone:** [Focus for Android](android/README.md) tracks phone apps and per-site mobile browsing via Accessibility, guards your configured Private DNS against Settings toggles, syncs with Focus TV and Windows over Wi-Fi, offers hold-to-pause with rising friction, and ships four home screen widgets.

**Android TV:** [Focus for TV](tv/README.md) records local TV app usage and adds
app limits, schedules, NextDNS filtering and timed exceptions. It runs independently and can now be managed from the Windows dock over the local network.
See [device setup and controls](docs/device-management.md).

A tiny bar at the top of the screen keeps Windows on your chosen NextDNS profile using encrypted DNS-over-HTTPS.

- **Hover the bar** to see the pause controls. After saving a Gateway key, choose **Ask**. The duration buttons are available on Windows before setup.
- **Describe what you need** in Ask. The duration buttons still ask for a reason and check-in.
- **It turns back on by itself** when the time is up. "Turn on" ends a pause early.
- While paused, the bar goes dark and fills back up as the time runs out.
- Every minute it checks that DNS is still NextDNS and puts it back if something changed it.

With [Ask](docs/pause-judge.md), you describe what you need on
Windows or the Android phone. Focus can open only the relevant services
while NextDNS stays on. Temporary allowlist changes are shared by every
device using that profile.

## Screen time and stats

- **Hovering shows today's totals** at the left of the pill: time at the screen and time with DNS off.
- **Click those numbers** and a wide stats panel drops down from the pill (Today / Week): a 24-hour timeline, where your time went, and every pause with its reason and what was actually on screen during it. Apps or sites your reason didn't mention are highlighted. Esc or clicking anywhere else closes it.
- **The lock icon** locks Windows (same as Win+L). Agents keep running; screen time stops counting until you unlock.
- **Only time you're actually there counts.** No mouse/keyboard for 2 minutes or a locked screen counts as away. The older title-based tracker allows 30 minutes for known video sites; extension-backed Chromium uses 2 minutes.
- Windows-only tracking stores app/site labels. The optional [Chromium extension](docs/browser-activity.md) adds actual active-page URLs and titles, navigation and focus/idle state, stored locally. It captures no screenshots, page contents or keystrokes.

## Devices

The statistics header has an **All devices** text filter. Use the gear for a separate
settings page with TV pairing, NextDNS controls, app blocking, daily limits and
schedules. The TV keeps enforcing its settings when the laptop disconnects.
[Connect a TV and understand combined statistics](docs/device-management.md).

## Browser details

The [unpacked Chromium extension and local Windows bridge](docs/browser-activity.md) enrich the existing screen-time stream with active-page URLs and titles. Sites over five minutes get their own expandable rows. Shorter visits and time without page details stay under Chrome or the corresponding browser.

When a Windows DNS pause expires or you click **Turn on**, the extension covers open tabs on sites blocked by the NextDNS profile configured in Focus, including background tabs. It keeps the page and any unsaved work in place, pauses playback, and shows when the pause ended. The cover also applies to fresh and restored pages while protection is on, and lifts on the next DNS pause. This works even when browser details capture is paused. Only hostnames are sent to NextDNS for the block check.

See [NextDNS setup and upgrades](docs/nextdns.md) to configure Focus or recover an existing profile.

## Install

From WSL:

```bash
./scripts/build.sh            # builds FocusDNS.exe with the Windows Rust toolchain
```

Then in PowerShell (asks for admin once):

```powershell
.\scripts\install.ps1 -Exe "$env:USERPROFILE\.focusdns-build\app\target\release\focus-dns.exe" -SelfTest
```

It installs to `C:\Program Files\FocusDNS` and starts at every login with admin rights, so changing DNS never shows a UAC prompt.
To remove it: `.\scripts\uninstall.ps1` (turns DNS back to automatic).

## Nightly updates

Every 6 hours, GitHub Actions (`.github/workflows/nightly.yml`) publishes `main` if it has changed since the last release. To publish right away, run the workflow on `main` from the Actions tab or with `gh workflow run nightly.yml`. If checks pass, it builds `FocusDNS.exe` and `focus-browser-host.exe`, signs `nightly.json` with an Ed25519 key in GitHub Secrets (`FOCUS_UPDATER_SIGNING_KEY`), and updates the rolling `nightly` GitHub Release.

Focus checks GitHub periodically against its embedded commit SHA. When a newer build exists, an **Update** chip appears in the hover bar and stats panel. Clicking **Update & restart** verifies the Ed25519 signature and SHA-256 hashes, replaces the binary in `C:\Program Files\FocusDNS`, and restarts Focus without a UAC prompt.

- **Public repo:** works with no setup.
- **Private repo:** open **Settings → Laptop → Nightly updates → GitHub token** and save a fine-grained token with `Contents: Read-only` for the build’s release repository. The token is encrypted with Windows DPAPI in `%LOCALAPPDATA%\FocusDNS\updater.json`.

## What happened and why

Everything lives in `%LOCALAPPDATA%\FocusDNS`:

| File | What's in it |
| --- | --- |
| `pauses.jsonl` | Every pause (when, how long, the reason you gave) and when it really ended |
| `activity\<day>.jsonl` | Screen time: which app or site was in front, and when |
| `services.json` | Service-to-domain catalog for the pause judge |
| `nextdns.json` | Local NextDNS profile and Windows DoH server addresses |
| `pause-judge.json` | Gateway model and Windows DPAPI-encrypted Gateway and NextDNS keys |
| `rules.json` | Custom app/domain categorization rules and approved finite alternatives, editable in JSON |
| `uncategorized.json` | Summary of unmatched apps and domains with total time and sample titles, ready for agent review |
| `focusdns.log` | Everything the app did, including whether the NextDNS probe matched the configured profile |
| `selftest.txt` | The off/on check from the last install |
| `state.json` | The current pause, so a restart doesn't lose it |

The optional `finiteAlternatives` array in `rules.json` lists approved activities.
An omitted array uses the [bundled defaults](app/finite-breaks.json). An explicit
array replaces the defaults, and an empty array disables all alternatives.

Pause endings record the actual completion time of the DNS-on command, alongside
the requested deadline and the pause's start time. A failed restore keeps the pause
open for retry, including in the stats. Old history remains readable; timestamps
that an older version already capped at the deadline cannot be recovered.
State replacements are atomic. The log includes the build ID, DNS operation IDs,
queue time and command duration to help distinguish timer delays from slow DNS changes.

Browsers can keep using existing connections and cached content after a DNS
change. Install the [Chromium extension and bridge](docs/browser-activity.md) to
cover those pages in supported browser profiles. The PowerShell NextDNS test
checks the resolver, while browser activity records foreground use.

## Laptop focus detector

The laptop focus detector evaluates activity every five seconds. It writes state
changes and reasons to `focusdns.log`. Internal consumers can subscribe to the Tauri event
`focus-state-transition`, with `at` (epoch milliseconds), `from`, `to`, and `reason`.
A null state marks the start or end of a laptop session.

The detector uses the same activity rules as the stats panel. Its starting thresholds
are 12 switches in three minutes, a median visit of 20 seconds or less, and evidence
from consuming activity. Four stats-panel opens in three minutes, two inferred phone
pickups in ten minutes, or one pause restarted within two minutes can corroborate
consuming activity. Generic browsing such as search and support pages counts in the
consuming total. The drift thresholds use more specific evidence such as known feeds,
video or messaging. A move to drifting requires 45 seconds of evidence. A move from
drifting to unfocused requires a further 75 seconds of stronger evidence. Recovery
requires 90 seconds per step. Escalation begins and completes while confirmed
consuming activity is in the foreground. Live escalation also requires desktop input
in the preceding 15 seconds when Windows provides idle data. Replay estimates state from the recorded
spans and optional signals. Phone pickups are inferred from synced foreground use
after a two-minute gap, so they depend on the phone being paired and syncing.

Save any threshold overrides in `%LOCALAPPDATA%\FocusDNS\focus-state.json` and restart
Focus to apply them. Use `--defaults` to print the accepted keys and defaults.
The replay command reads activity, pauses, and optional panel and device history from
the specified data directory. It prints timestamped state changes and their reasons.
For old panel-open logs, run replay in the Windows data's local timezone.

```bash
cargo run --manifest-path app/Cargo.toml --bin focus-replay -- --defaults
cargo run --manifest-path app/Cargo.toml --bin focus-replay -- /path/to/FocusDNS

fixture_dir=$(mktemp -d)
node tests/fixtures/focus-replay.mjs "$fixture_dir" week
cargo run --manifest-path app/Cargo.toml --bin focus-replay -- "$fixture_dir"
```

Use the [Focus screenshot command](docs/screenshots.md) to capture the app interface with fictional data.

## Regression checks

After `./scripts/build.sh`, run in PowerShell from the Windows build directory:

```powershell
cargo test --release --manifest-path app/Cargo.toml
```

The pause/watchdog tests use fake DNS commands and temporary data. They exercise
both orderings of an overlapping pause and watchdog check, startup recovery,
five- and ten-minute deadlines, failed DNS changes/retries, and actual end times.
They do not change machine DNS or open an app window.

The shared browser/stats tests run from the repository with
`node --test tests/extension.test.mjs tests/stats.test.mjs`.

## Trying the UI without touching DNS

```powershell
focus-dns.exe --dry-run
```

It doesn't need admin, sits a bit lower on screen than the real one, and keeps its own data in `%LOCALAPPDATA%\FocusDNS-dry`.

## How it works

- `app/` is a small Tauri (Rust + WebView2) app. `ui/index.html` is the widget, `ui/panel.js` the stats panel, `ui/stats.js` the shared math.
- `app/src/activity.rs` samples the window in front every second and names it (app, or site for browsers).
- `scripts/dns.ps1` does the DNS switching: it sets the NextDNS addresses on every physical adapter and turns on DNS-over-HTTPS for them, the same as doing it by hand in Settings.
- The window is click-through everywhere except the bar itself, so it never blocks whatever is underneath.
