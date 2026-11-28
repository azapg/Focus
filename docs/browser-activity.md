# Browser activity in Focus

The unpacked Chromium extension observes the selected page and sends metadata to
an unprivileged native host on this PC. The existing Windows tracker adds that
context to its **single** foreground time stream. The extension also covers
blocked sites when DNS protection resumes and shows static snapshots with Peek.

## Build and install on Windows

In the desktop app, open **Settings → Laptop → Tracking → Set up Chrome**.
Focus prepares the extension folder and registers its local helper. Copy the
folder path, open `chrome://extensions`, enable **Developer mode**, and choose
**Load unpacked**. Paste the path in the folder picker. If **Focus Browser
Activity** is already listed, click its reload button.

For Edge or a manual installation:

1. From WSL, run `./scripts/build.sh`. It builds **both** executables using the
   existing Windows Rust toolchain and copies the unpacked extension and scripts
   to `%USERPROFILE%\.focusdns-build`. `FOCUS_BUILD_DIR` can isolate another build.
2. Update the Windows app with the existing `scripts/install.ps1 -Exe ...` flow.
   Its DNS task, pause choices, required reasons, watchdog and resume logic are
   unchanged. The optional `-SelfTest` checks NextDNS and is unnecessary
   for the browser bridge.
3. In **normal, unelevated PowerShell as the browser user**, run:

   ```powershell
   cd "$env:USERPROFILE\.focusdns-build"
   .\scripts\install-browser.ps1 -HostExe .\browser-host\target\release\focus-browser-host.exe -Browser Chrome,Edge
   ```

   This copies the host and extension to
   `%LOCALAPPDATA%\FocusDNS\browser-bridge`. It creates a UTF-8 native-host manifest
   and HKCU NativeMessagingHosts registrations for the selected browsers. It
   does not start the app, install into a browser profile, or change DNS.
4. Open `chrome://extensions` or `edge://extensions`, enable Developer mode,
   choose **Load unpacked**, and select
   `%LOCALAPPDATA%\FocusDNS\browser-bridge\extension`.
   The extension ID must be `mfafgohdpfnknfgedjiicnmgomjpdock` (its committed public
   key makes the unpacked ID stable). Focus updates managed extension files at
   app startup. Reload the extension on the browser's extensions page after
   installing or updating Focus.
5. Keep the updated Focus app running. Open a normal HTTP(S) page and use it.
   `history` returns enriched spans as soon as the next one-second sample sees
   the browser in front. Existing stats totals use the same spans and do not
   add browser observations as a second timer.

The browser and elevated app must run as the **same Windows account**. Elevating
with a different administrator account uses a different data directory.

Chrome and Edge are the primary Windows targets. The installer also has Brave
and Chromium registry paths; those browsers need their own runtime verification.
Other Chromium derivatives need correct vendor host registration and a recognized
browser executable. Firefox is not supported by this extension and retains the
existing coarse Windows tracking.

To unregister (does not delete history or disable the extension):

```powershell
.\scripts\install-browser.ps1 -Remove -Browser Chrome,Edge
```

Remove the unpacked extension in each browser profile and delete
`%LOCALAPPDATA%\FocusDNS\browser-bridge` when no native host is running. To delete
captured history, close Focus and the extension first, then delete browser
journals **and** any activity files containing enriched spans. No retention
limit is applied automatically, matching the existing local history behavior.

## Everyday use

Usage stays in the existing Focus panel: hover the dock, click today's totals,
then click a site to see its page titles, URLs and durations. Only observed sites
with **more than five minutes** in the selected Today/Week range get separate
rows. Shorter visits and time without page details remain under the expandable
browser row (Chrome, Edge, etc.). Promoted site time is removed from that browser
row, so durations are counted once. There is no extension
popup. Right-click its toolbar icon (or its entry under the browser's Extensions
menu) and choose **Pause browser details** / **Resume browser details**. The menu
changes only rich browser capture; DNS and coarse Windows tracking continue.

## When a DNS pause ends

After the app successfully restores NextDNS, the native host forwards the new
state and the actual pause end time. This applies to both timer expiry and
**Turn on**. The extension covers each matching page in place, including tabs in
the background. It pauses HTML video and audio, including in embedded frames
where Chromium allows script injection, including players inside shadow roots,
and mutes other audio from the blocked
tab. The cover and the extension's mute lift when a new DNS pause starts or you
navigate to an allowed site. The document, scroll position, and typed input
stay in the tab, and HTML playback stays paused until you resume it.

To determine which tabs to cover, the extension sends only the top-level
hostname and DNS query type to NextDNS over HTTPS using the profile configured
in Focus. Unknown or missing configuration prevents new resolver lookups. The
extension checks on navigation and pause transitions and warms a short
in-memory cache near the pause deadline. Names without a delegated public DNS
suffix are kept local. Private subdomains under public suffixes can still be
queried. Fresh lookups can take a moment, and a
failed or ambiguous lookup leaves the current page unchanged while the extension
retries. NextDNS can log these DNS queries according to the profile's settings,
including queries for background tabs and during a pause. The lookup sends no
URL paths, query strings, titles, page contents, or keystrokes.

Windows clears its DNS cache when it switches resolvers. Chrome can still reuse
connections or DNS answers, restore a document from its back/forward cache, or
load a navigation from a site's service worker. The extension checks a page
when it commits or is restored, including when it loaded from cache. Chromium
restricts injection on some pages, such as browser stores and built-in error
pages. Those pages rely on DNS and Chromium's own handling.
[Chrome navigation lifecycle](https://developer.chrome.com/blog/extension-instantnav) ·
[Service worker navigation handling](https://web.dev/articles/handling-navigation-requests)

## Peek at a blocked post

When a foreground tab navigates to a recognized social network that this
NextDNS profile blocks, Focus can replace the tab with **Peek**. A failed
background tab becomes eligible when you switch to it. Known Google
search redirects and Facebook link shims are unwrapped locally. Short links
such as `t.co` may show a browser interstitial. The host can read their redirect
headers through the filtered profile and offer Peek when the destination is a
blocked post. A blocked shortener requires a direct post link.

On Windows and Android, paste a single post link into **Ask** and submit.
A link by itself goes directly to the local Peek worker, including when no
AI Gateway key is configured. A feed or unsupported link gets a local
refusal. Written requests continue through Ask. Windows shows the post in
a compact panel below the dock, in the same position as Stats. Press Escape
or click away to dismiss. Android shows a bottom drawer. Swipe its handle
down, tap outside, or use Back to dismiss.

Sharing a link to Focus on Android fills a confirmation field. Android may
also offer Focus as a link handler for X and Facebook. Tap **Peek** to
confirm before the Android drawer opens.

If the Google app shares a short link, Peek follows its redirect through
NextDNS and opens a card only if it leads to a supported post. If Google
only offers to reopen its app, copy the full post link instead.

Android fetches and renders on the phone even when the laptop is off. Neither
app changes DNS for a Peek. An active Focus NextDNS profile and an internet
connection capable of reaching the public IPv4 addresses are required.
Windows Ask also checks that the effective system resolver blocks the post
hostname, including when a VPN supplies DNS. It currently accepts the usual
empty or zero-address blocks. A profile with a custom block page may refuse
the snapshot.

- **X** supports direct status links and their photo/video link variants. Xeet
  renders the public post to a static image. Available photos, video posters,
  quotes, polls, and counts use the same rendering path. Images are fetched
  from X's media CDN. Outbound link cards are not crawled.
- **Facebook** supports public `/<page>/posts/<id>` and `/reel/<id>` links,
  including the slugged post form used by Google results. Peek uses the
  title, description, and single image publicly advertised by the post.
  Share links that resolve to login, group feeds, and pages with generic or
  missing post metadata can’t be rendered.
- **Instagram, Threads, Reddit, and TikTok** are recognized for a polite
  refusal. Their anonymous metadata can be missing, generic, or served behind
  a login or crawler challenge. This release doesn’t fetch those sites through
  the unfiltered resolver.

The Peek tab contains a fixed card with **Done** and **Pause**.
Done closes the tab. Pause reveals the existing Focus duration controls, where
you must pick a time, wait if required, and enter your reason.
Reopen the original link after you start a pause. A successful snapshot
counts as a peek in the stats panel and contributes no pause time.

### Peek privacy and network boundaries

- Peek shares the tab cover's cached NextDNS check for recognized social hosts.
  The browser sends only the hostname to NextDNS and passes the selected URL
  locally to the host. The host confirms the block before fetching the post. Neither path collects open-page HTML, form contents, keystrokes,
  cookies, credentials, or referrers. Pausing activity details still stops
  activity capture and leaves this separate feature available.
- The host strips tracking queries and fragments from permalinks. It queries
  Cloudflare DNS over HTTPS for the allowlisted post or media hostname, then
  opens a direct, certificate-validated HTTPS connection to a checked public
  IPv4 address. Browser and OS DNS settings stay on the existing profile.
  The selected public platform receives the post request and its CDN receives
  the associated image request. No third-party snapshot or rendering service
  receives the link.
- Every post redirect must keep the same platform and post identifier. Short
  links are bounded to known redirect hosts and must resolve to an allowed
  post before Peek queries the unfiltered resolver. Private addresses,
  nondefault ports, external card URLs, feeds, profiles, search, and explore
  URLs are refused. Requests have per-response
  caps and a 16-second networking deadline. The extension page accepts only
  inline raster data and displays remote text as plain text.
- The browser uses a temporary opaque token for the Peek tab. Snapshots and
  source URLs are held in memory for the request. The Peek journal records
  only successful-render timestamps. On Windows, pasted Peek timestamps are
  kept in the protected installation directory for that account. On Android,
  successful Peek counts stay with the phone. The screen holds its card in
  memory. Ordinary browser activity still follows
  the capture rules below.

## What is actually observed

- Selected tab's HTTP(S) URL and title; tab/window IDs (local browser identifiers).
- Browser window focus, idle/locked state, enable state, and a tab's `audible`
  flag. Audible is context, **not** proof of video playback or attention.
- Top-level navigation, History API SPA changes, fragment route changes, title
  changes, tab switches/replacement/close, window focus/close, and a 5s heartbeat.
- Metadata comes from Chromium's tabs, windows, webNavigation and idle APIs.
  The cover uses the DOM only to display the message and stop media. The
  extension captures no visible page text, form input, keystrokes, passwords,
  cookies, browser history database or screenshots.

Navigation events are observations, not an independent source of counted time.
Only the browser whose OS process is currently in front can enrich the timer.
Background tabs never receive their own time. Focus must be running to count
screen time; the extension can still journal observations while it is closed.
These journals are not backfilled as active time when the app restarts.

Time is sampled at **1s**, not exact human attention measurement. Fast switches
between samples can appear in the browser journal without a corresponding time
span. Idle is 2 minutes for extension-backed Chromium, with no speculative
30-minute video exception. OS lock/away always stops counting. If metadata is
missing/disconnected or more than 15s old, Windows app/title inference resumes;
its provenance is `window_title`, never presented as observed browser data.
There can be up to 15s of stale rich context after an abnormal host failure.

Pausing browser details clears live URL/title and subsequent journal identities.
The current enriched activity span closes on the next app sample (normally ≤1s).
Windows coarse app tracking continues. Previous history remains. This setting
has no effect on DNS filtering, DNS pause timers, or their required reasons.

The manifest permissions have specific uses: `tabs` and `webNavigation` for page
metadata/navigation, `idle` for presence, `nativeMessaging` for the local host,
`storage` for capture preference and connection diagnostics, `alarms` for recovery,
and `contextMenus` for pause/resume. `scripting` and HTTP(S) host permissions
allow the extension to inject the cover only after a hostname is confirmed
blocked. The HTTPS permission also allows the NextDNS lookup. Reload the updated
extension and grant site access on all sites for coverage across the profile.

## Visibility boundaries

- **Incognito is disabled in the manifest.** No incognito URL/title is captured.
  Windows can still record coarse browser app time. Browser profiles are separate:
  load the extension into each profile intended for capture.
- `chrome://`, `edge://`, extension pages, DevTools, file URLs, and other non-HTTP(S)
  URLs have no stored URL/title. A focused restricted tab is recorded as browser
  app time with `visibility: restricted` where Chromium exposes that state.
- HTTP(S) tab metadata can be available for pages where content scripts are
  prohibited (for example a browser store or PDF). This extension reads no page
  contents, even on ordinary pages.
- Authentication pages, private documents, health/banking sites, search queries,
  and personal page titles can still be captured if they are ordinary HTTP(S)
  tabs. The requested richer capture is sensitive local history; there is no
  hardcoded sensitive-site exclusion list.
- URL sanitization removes username/password, ordinary hash fragments, long
  token-like path segments, and values of unknown query parameters. Common
  navigation keys (`q`, `query`, `search`, `page`, `v`, `list`, `t`, `index`, `lang`,
  `sort`, `tab`, `view`) remain when short and not token-like. Hash routes such as
  `#/projects/1` remain if they have no query/assignment. Unknown values become
  `REDACTED`. Both extension and native host apply this policy, including URLs embedded in
  titles. Titles are capped at 512 characters.
- **This is not a universal secret detector.** Ordinary paths, allowed queries
  and titles can contain sensitive information. No raw payloads are written to
  diagnostic logs. Use synthetic pages in screenshots and demonstrations.

## Data and transport

Everything is under `%LOCALAPPDATA%\FocusDNS`:

| File | Meaning |
| --- | --- |
| `browser/events/<local-day>.jsonl` | Validated navigation/state transitions with local receive timestamp, browser PID/exe, host PID and event envelope |
| `browser/live/<host-pid>.json` | Atomic latest snapshot, refreshed by heartbeat; removed on clean host exit |
| `browser/app-seen` | App liveness timestamp only |
| `browser/peeks/<local-day>.jsonl` | Timestamp of each successful Peek render |
| `browser/peek-pause` | Short-lived timestamp requesting that the app reveal its regular pause controls |
| `state.json` | Saved DNS pause and last successful pause end time. The native host forwards only these timestamps, omitting the pause reason. |
| `activity/<local-day>.jsonl` | One foreground stream, with optional `browser` context and `source` |

Example enriched span (synthetic):

```json
{"s":1800000000000,"e":1800000030000,"app":"Chrome","label":"github.com","source":"chromium_extension","browser":{"url":"https://github.com/example-org/sample-project","title":"example-org/sample-project · GitHub","visibility":"page","audible":false,"tab_id":1,"window_id":1,"browser_pid":123,"host_pid":456}}
```

Old `{s,e,app,label}` files remain readable with `source: window_title` and no
browser context. The Tauri `history` response includes the extra fields.
The host accepts versioned `peek_check` (hostname), `peek_follow` (short URL), `peek_post` (URL), and
`peek_pause` (reveal the standard controls) messages over one-shot native
ports. The shared Rust action is `focus_browser_host::peek::post`.
The host accepts no DNS commands or shell instructions. Chromium only
allows the committed extension origin to launch it; the executable checks caller
origin and browser process ancestry, bounds frames to 32KiB, validates versions,
timestamps, states and increasing sequence numbers, then acknowledges only after
persistence. It never requests elevation. The elevated app treats snapshots as
data; local same-user programs can tamper with these files, so this is local
activity evidence, not an anti-tampering or security audit system.

Reference protocols: [Chromium native messaging](https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging),
[Edge native messaging](https://learn.microsoft.com/en-us/microsoft-edge/extensions/developer-guide/native-messaging),
[Chromium navigation events](https://developer.chrome.com/docs/extensions/reference/api/webNavigation).

## Diagnostics

Routine connection state is intentionally absent from the daily UI. For an
intentional diagnostic check, open the extension's service-worker inspector from
`chrome://extensions` / `edge://extensions`, then evaluate
`chrome.storage.session.get('status')`. The object reports native connection,
last receive time and whether the Windows app was recently seen, without URLs or
titles. If native connection is false, rerun the browser installer as the same
Windows user and reload the extension. If only `app_online` is false, start/update
Focus. A different admin account or a dry-run app uses a separate data directory.

## Repeat verification

```bash
node --test tests/extension.test.mjs
cargo test --manifest-path browser-host/Cargo.toml
cargo build --manifest-path browser-host/Cargo.toml
uv run --with playwright python tests/browser-e2e.py
./scripts/build.sh
```

The Chromium test uses its own profile, native manifest and data directory,
intercepts synthetic HTTPS pages and DNS answers, and deletes its temporary files. Override
`FOCUS_TEST_CHROME` if your Chromium executable is elsewhere. No daily browser
profile is opened. See [browser verification](browser-verification.md) for
measured outcomes and what remains unverified.

The opt-in live Peek test uses the same isolated profile approach and two public
posts. It checks the native fetch, inline images, feed refusal, local history,
and the browser-to-dock pause request.

```bash
FOCUS_TEST_CHROME=/usr/bin/chromium uv run --with playwright python tests/peek-browser.py \
  --x-post "$X_POST" --x-short "$X_SHORT" --facebook-post "$FACEBOOK_POST"
```

### Xeet assets

Build from the repository root or one of its subdirectories so Cargo picks
up `.cargo/config.toml`. The Windows build script copies this configuration
alongside the source. It selects the checked-in Noto Sans (SIL OFL 1.1),
Focus action icons, and layout tokens from the pinned Apache-2.0 xeet release.
Rendering uses the Noto Sans glyph coverage available in this bundle.

The packaged Windows app bundles a copy of the renderer as a separate
worker for its paste box. The WSL build script supplies that worker to the
Windows Cargo build. If running a manual Windows build, build the browser
host first and set `FOCUS_PEEK_WORKER` to that executable's absolute path
before compiling Focus.

Android packages the same renderer using the Android NDK. See the Android
build instructions for the native toolchain.
