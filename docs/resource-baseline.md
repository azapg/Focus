# Focus local resource telemetry

The collectors use existing work to sample roughly every five minutes. The Windows app samples on the activity tracker, the native host samples as it acknowledges events, Android samples on the foreground monitor worker, and Chromium rolls buckets as callbacks arrive. The native host also records a final window of at least one second on a clean input close. Idle or abruptly terminated components can leave gaps. There are no telemetry timers, alarms, LAN fields, or uploads.

Each row contains the component, time, measured interval, fixed metric names and numeric values. The native and Android collectors keep the current UTC day and the six previous days, with a 2 MiB limit per component per day. They remove older files on subsequent writes. Chromium stores up to seven UTC days of five-minute buckets in its extension-local IndexedDB. The start count is persisted immediately. Other counts in the active bucket can be lost if Chromium stops the worker before the next bucket. Rates for Chromium use the full five-minute bucket, so partial buckets understate the active rate.

## Collect and report

- Windows app and native host write to `%LOCALAPPDATA%\FocusDNS\telemetry`, in `desktop` and `browser-host` respectively. Desktop dry runs use `FocusDNS-dry`.
- The phone and TV write to the private, backup-excluded `no_backup/telemetry` directory in each app. Use `adb run-as` with an isolated debuggable install. A production package on an unrooted device cannot be read through `run-as`.
- In Chromium, open the extension's service-worker inspector from `chrome://extensions`, then run `copy(JSON.stringify(await exportFocusResourceTelemetry()))` in its console. Save the clipboard as `extension.json`.

```sh
adb exec-out "run-as org.nightingalelab.focus.phone sh -c 'cat no_backup/telemetry/*.jsonl'" > phone.jsonl
adb exec-out "run-as org.nightingalelab.focus.tv sh -c 'cat no_backup/telemetry/*.jsonl'" > tv.jsonl
python3 scripts/resource-report.py /path/to/FocusDNS/telemetry phone.jsonl tv.jsonl extension.json --hours 24
```

To build local debuggable APKs, use a scratch copy and separate signing keys. Use a test device or emulator. Android requires a matching signature when updating an installed package.

```sh
scratch=$(mktemp -d)
git archive HEAD | tar -x -C "$scratch"
sed -i 's/<application/<application android:debuggable="true"/' \
  "$scratch/android/AndroidManifest.xml" "$scratch/tv/AndroidManifest.xml"
env -u FOCUS_ANDROID_KEYSTORE_BASE64 -u FOCUS_ANDROID_KEYSTORE_PASS \
  FOCUS_PHONE_SIGNING="$scratch/phone-signing" bash "$scratch/android/build.sh"
FOCUS_TV_SIGNING="$scratch/tv-signing" bash "$scratch/tv/build.sh"
```

The APKs are in `$scratch/android/build` and `$scratch/tv/build`.

Pass only the inputs you have. The report calculates elapsed-time-weighted averages and p95 for rates and gives each gauge reading equal weight. CPU is a percentage of one core. File and network rates are in KiB per minute. Each native-host process window contributes independently. If several browsers run simultaneously, the host table reflects the typical host process. A metric absent from a sample contributes no data to that metric's summary.

Windows process CPU and memory are for Focus and the native host themselves. WebView2 and PowerShell run in separate processes. The watchdog records PowerShell attempts, child wall time and the NextDNS diagnostic probe's HTTP response body bytes. Device sync and updater bytes count application payloads. Windows process I/O includes non-disk I/O such as pipes. File-write counters for activity tracking and the native bridge count successful logical writes. Callback counts show work dispatched by the application. To measure OS wakeups and child-process CPU, capture a Windows Performance Recorder trace alongside these files.

Android uses process CPU and PSS, UID network totals, foreground service elapsed time, delivered accessibility events, and counters at the app's existing wakeup-alarm and callback sites. The charging gauge supplies power context. On physical Android devices, collect the OS's UID energy and wakeup attribution with `adb shell dumpsys batterystats --charged PACKAGE`. Work during deep sleep, actual energy, and hardware-dependent PSS require device measurements. [Android battery diagnostics](https://developer.android.com/tools/dumpsys#battery)

## Baseline

Measured on Linux on 2026-11-24. The browser run used a fresh headless Chromium profile, one synthetic page, and the debug native host. The release-host run sent 12 fixture frames at five-second intervals and closed the pipe cleanly. Both runs verified that the fixture URL or title stayed out of the telemetry records.

| Component and workload | Metric | Average | p95 | Sample windows |
|---|---|---:|---:|---:|
| Native host, connected Chromium | CPU, % of one core | 0.037 | 0.037 | 2 |
| Native host, connected Chromium | Working set, MiB | 3.69 | 3.73 | 2 |
| Native host, connected Chromium | Protection-file callbacks/min | 239.84 | 239.88 | 2 |
| Native host, connected Chromium | Logical file writes/min | 12.89 | 14.76 | 2 |
| Native host, release fixture | CPU, % of one core | 0.026 | 0.026 | 1 |
| Native host, release fixture | Working set, MiB | 2.80 | 2.80 | 1 |
| Native host, release fixture | Logical file writes/min | 12.98 | 12.98 | 1 |
| Chromium extension | Refresh callbacks/min | 7.80 | 8.60 | 2 |
| Chromium extension | Messages/min in each direction | 6.50 | 7.00 | 2 |

The connected host reported two threads. Its windows were 305 s and 16 s. Chromium recorded one worker start, 78 refresh callbacks and 65 messages in each direction during the same roughly five-minute run. Its two partial UTC buckets contribute five minutes each to the report, while 65 messages over 321 s is about 12 messages/minute in each direction. These p95 values describe the short runs above.

| Component | Local verification | Still needed |
|---|---|---|
| Windows app and native host | Rust native-host tests and Linux host runtime exercise | Real Windows working set, private bytes, handles, PowerShell/WebView2 CPU, OS wakeups, file and LAN costs during normal use |
| Android phone | JVM tests passed and APK packaged | CPU, PSS, screen-off callbacks, accessibility and widget load, UID energy and network during normal use |
| Android TV | JVM tests passed and APK packaged | CPU, PSS, service uptime, restriction and alarm load on the actual TV |

The local API 35 emulator had no KVM support. Software emulation started but remained offline in ADB during the run, so there are no Android runtime samples here. Collect a screen-on and a screen-off trace on each physical Android device. On Windows, collect matching idle windows before and after enabling the T3 poller and focus-loss detector. The rebased Windows app counts detector evaluations and measures their elapsed work. The three-second T3 poller was still pending.

## Follow-ups to measure

1. Compare the Windows LAN listener's 25 ms accept retry and overlay's 30 ms cursor poll against blocking or event-driven alternatives. Their configured cadences imply ceilings near 2,400 and 2,000 callbacks per minute, respectively, before work time or timer delays.
2. Measure the native host’s protection-file poll every 250 ms, the activity tracker's liveness write each second, and the extension's five-second native heartbeat plus 30-second recovery alarm. Quantify how often these writes and callbacks carry useful changes before adjusting either cadence.
3. On the phone, compare the widget refresh cost with zero and with several widget instances. The current refresh computes the summaries before the per-widget methods check whether any instances are installed.
4. Compare matched idle and active Windows sessions with the T3 poller and focus-loss detector separately enabled. A 3-second timer permits 20 attempts per minute. The detector's evaluation time covers input refresh, classification, signal computation and transition delivery.

## Add counters to a subsystem

Declare fixed names in the `counters!` list in `telemetry/src/lib.rs`. Use separate counters for attempts, bytes, or elapsed work, such as `agent_status.requests`, `agent_status.rx_bytes` and `agent_status.poll_ms`. Call the corresponding static counter at the existing work site, for example `focus_telemetry::AGENT_STATUS_REQUEST.count(1)`. The Android equivalent is a fixed entry in `LocalTelemetry.Metric`. Always pass numbers such as a body length or elapsed time. Keep request content, URLs, device identities, and user activity out of these records.
