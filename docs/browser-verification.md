# Browser activity verification

Verified on 2026-11-15 in the extension worktree. All pages and captured examples
were synthetic. No daily browser profile was opened or installed, no DNS settings
were changed, and no ADB/emulator/SDK operations were performed.

## Results

- **JavaScript:** 28 tests pass (`node --test tests/extension.test.mjs tests/stats.test.mjs`). These cover
  shared URL redaction fixtures, selected foreground tab only, incognito/restricted
  boundaries, SPA and fragment events, background churn, focus/idle/close, pause
  and resume with no retained identity, native reconnect, persisted pause after
  worker initialization, asynchronous stale-query races, the committed ID, native
  pause menu, page-time subdivision, escaped aggregation keys, DNS reason aliases,
  strict >5-minute promotion, same-URL reload observations and expandable residual
  browser time.
- **Rust:** 16 tests pass (`cargo test --manifest-path browser-host/Cargo.toml`).
  Existing title classification and old history compatibility still pass. New
  tests cover frame limits/truncation, event validation, replay rejection,
  acknowledge-after-persist, redaction, atomic live snapshots, TTL/PID/exe matching,
  multiple browser profiles, enriched history, and one continuous time stream.
- **Real Chromium:** `tests/browser-e2e.py` launches Chromium 158 with the real
  unpacked extension and real native binary in an isolated Linux profile. Active
  URL/title, SPA routes, background exclusion, redaction, capture pause/resume,
  native host kill/reconnect and full browser restart are checked. The test
  intercepts only a synthetic HTTPS origin; no external browsing is needed.
  The test prints its result as JSON.
- **Windows release:** both `focus-dns.exe` and `focus-browser-host.exe` compile
  using the Windows Rust toolchain with an isolated build directory.
- **Windows executable transport:** `cargo run --release --example chrome --
  target/release/focus-browser-host.exe` passes real Windows process ancestry,
  framed pipes, URL/title persistence, redaction, pause tombstone, normal cleanup,
  rejected caller and oversized-frame checks. This is a **synthetic browser-parent
  probe named chrome.exe**, not an actual Chrome runtime test.
- **Windows installer:** `tests/install-browser.test.ps1` executes the real
  installer with temporary file destinations. Registry operations are mapped to
  an isolated HKCU test prefix. It verifies binary and unpacked files, manifest
  origin/path/encoding, Chrome/Edge registrations and unregister. Temporary keys
  and files are removed; daily browser registrations remain untouched.

- **Real Windows Edge:** `tests/windows-browser.mjs` passes real Edge 157,
  unpacked extension, and native-host registry discovery. URL/title, SPA,
  credential redaction and pause/journal suppression/resume all pass. It uses a
  disposable headless profile, temporary data directory and a uniquely named
  native host registry key under Edge; it removes them afterward. The daily
  extension registration and browser profile are untouched. The test prints its result as JSON.
- **Actual panel rendering:** `tests/panel-browser.py` loads the actual app HTML,
  CSS and JS with synthetic Tauri history. Keyboard site expansion, URL/title
  rows, no leading chevrons, short visits/untracked time nested under Chrome,
  expansion preserved on refresh, escaping of page markup, Today/Week and
  Esc pass. The [screenshot command](screenshots.md) reruns the flow with the compiled frontend and fictional activity.

## Before / after

Before, a Chrome window whose title mentioned “YouTube” was labeled YouTube even
if its actual URL was an unrelated document. The tracker now uses the observed
hostname when a fresh extension snapshot matches the Windows foreground process.
The regression test uses `https://docs.example.org/project` with a title mentioning
YouTube: the result is `docs.example.org`, with the real URL/title and
`source: chromium_extension`. Without extension data, the old inference remains
available with `source: window_title`.

The persistence-to-history regression records 4 seconds of foreground use:
2 seconds enriched + 2 seconds coarse after pausing. The total is **4 seconds**,
with contiguous, nonoverlapping spans. Browser events are not added to that total.

A real browser pause test navigates to `/paused-private` and sets its title while
details are paused. Neither identity appears in the journal. Resuming captures
the current page. The app's current rich span closes at its next 1-second sample.

## Limits of this evidence

Actual Windows Edge load-unpacked → registered native host is verified. The
foreground tracker and Tauri history use the same tested persistence-to-history
code, but a full elevated GUI foreground session with an existing browser profile remains
unverified until coordinated install. Actual Windows Chrome, Brave and other
derivatives have not been runtime-tested. No claim is made about phone/TV browsers.

A forced DevTools service-worker stop/reload did not produce a reliable new-worker
event in this automation environment. Worker startup/recovery is covered with
lifecycle tests and a real full browser restart; forced worker termination is not
claimed as a successful browser test.

Active time is sampled at 1 second; fast navigation is journaled but may get no
counted span. The idle threshold is 2 minutes for extension-backed Chromium.
Audible does not prove viewing. Native snapshots expire after 15 seconds; a hard
crash may leave stale context until then. URLs/titles can themselves be sensitive;
redaction covers defined credential patterns, not every possible secret. Rich
history is local plaintext under the user's Windows account, not encrypted storage.
