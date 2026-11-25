<img src="../assets/icon.svg" width="48" height="48" alt="Focus">

# Focus for TV

A standalone Android TV companion: local app usage, app restrictions, native
NextDNS filtering and timed exceptions. It runs on the TV without a Windows connection. The interface uses
the remote's arrows, OK and Back.

## Use

Open **Focus** in the TV's apps.

- **Today / Week:** Screen time, the category breakdown, a viewing timeline, app
  usage, and pauses. Select a bar to inspect that time or day. Select an app to
  block or unblock it, or use **All apps** to manage apps outside the top three.
- **Restrictions:** choose an app, toggle blocking, set a daily allowance, or
  configure a daily schedule. Overnight schedules are supported. Equal start/end
  times mean all day. New installs have no rules.
- **Allow temporarily:** choose 1, 5, 10 or 30 minutes and enter a reason using
  the TV keyboard. The app reopens and a small bottom-right countdown appears.
  At expiry, its configured restriction applies again. An app without a blocking
  rule stays allowed. **End pause** in the app's controls ends the exception early.
- **Settings:** permissions, pause history, manual export and diagnostics.
- **NextDNS:** open Settings for profile setup, connection verification,
  reason-required 1/5/10/30-minute DNS pauses and early resume. The same corner
  countdown appears while a DNS pause is active. **Restore previous DNS** restores
  the exact settings saved on first connection, including absent values.

The Windows dock can now manage this TV over the same local network. Open
**Settings → Connect TV** in Windows, choose its nearby name, then approve the matching number on the TV. See [connection setup and desktop controls](../docs/device-management.md).
On Android, open **Focus → Settings → Devices → Add a device** and approve the
connection on the TV. Select the paired TV and open **App restrictions** to block
or unblock an app. The TV must be reachable on the local network.
The exported format marks records as household TV usage, not personal attention time.

## What it measures and controls

Usage Access supplies foreground activity and screen events. Focus reconstructs
one nonoverlapping foreground stream, excludes its own app, recovery/system apps
and screen-off periods, and records only TV-launchable apps. It starts at initial
activation; it does not import historical durations from before installation.
Each query overlaps the previous minute so delayed Android app-switch events can
correct the current state without adding old time again.
There is no remote-input idle timeout, so a long video is not discarded simply
because nobody pressed a button. Foreground use does not prove a person watched.
HDMI content and individual viewers are not identified.

Accessibility observes window package changes and shows the blocking surface or
the corner countdown. It never requests window contents, nodes, screenshots,
keystrokes or gestures. A fresh Usage Access snapshot corrects temporary system
window events. Settings, launchers, keyboards, permission controllers and Focus
cannot be selected for blocking.

Blocking moves to Focus's blocking screen with a temporary overlay covering the
transition. Home/Back remain recovery paths. This prevents normal foreground use;
it does not suspend the package, kill its background processes or guarantee that
audio/background playback stops. Disabling Accessibility or force-stopping Focus
can bypass enforcement. Missing access is shown as unavailable, not working
protection. This is a voluntary focus tool, not device-owner management.

DNS and app restrictions are separate controls. Allowing a blocked app does not
pause NextDNS; pausing NextDNS does not remove a local app block. NextDNS is
configured directly through Android's native Private DNS (DNS-over-TLS), without
a Focus VPN. No router, Windows, phone or Tailscale settings are changed. A TV
VPN is detected and refused for DNS setup/pause because its resolver may differ.

**One-time computer setup is required for DNS controls.** The optional
`WRITE_SECURE_SETTINGS` development grant is broader than DNS; Focus's production
code writes only `private_dns_mode` and `private_dns_specifier`. It requests
Internet/network-state access to verify Android's validated provider hostname
and a fresh HTTPS response from `*.test.nextdns.io`. Verification runs on opening
Focus and roughly every minute while managed. Results expire after five minutes
or a network/link change; configured-but-unverified or unavailable states do not show
as On. No DNS request history, client IPs or raw verification responses are saved.

During a DNS pause Android uses the network's ordinary DNS. That resolver can
still impose its own filtering. Cached addresses, existing streams, downloaded
content and apps with their own resolvers are outside this guarantee. The check
proves the system resolver and the tested services, not universal traffic capture.

## Time and persistence

- SQLite persists usage intervals, rules and pause reasons in app-private storage.
  The timeline and cursor commit in one transaction; adjacent samples coalesce.
  Gaps longer than 30 seconds restart from an unknown foreground. At startup
  and after a gap, Focus fills missing time from today's available Android app
  events, after installation. It leaves existing intervals and the unconfirmed
  end of an ongoing session alone.
- The monitor samples roughly every two seconds while interactive, less often
  while asleep. The restriction service checks deadlines every second while the
  process is scheduled. It does not hold a wake lock.
- Exceptions use `elapsedRealtime` plus the boot count. Wall-clock changes cannot
  extend them. The same boot can restore an unexpired exception after a process
  restart; a reboot invalidates it. On wake, expired exceptions are rejected.
- DNS pauses arm an exact elapsed-time wakeup alarm **before** changing DNS. The
  monitor also retries restoration. Persisted pending state survives process death;
  startup/boot/update resumes NextDNS after an expired pause or reboot. A missing
  settings grant is shown, not treated as protected. Android 12+ additionally
  requires exact-alarm access before a DNS pause; this TCL runs Android 11.
- **Do not force-stop/uninstall Focus or revoke settings access during a DNS
  pause.** Android can cancel alarms and stop the app; automatic DNS restoration
  cannot run until Focus is allowed to start again. This is voluntary control,
  not tamper resistance. Hardware reboot/standby recovery remains untested.
- Daily allowances and schedules follow local calendar time. Changing the clock
  or timezone can change those rules; this is not tamper resistance.
- A boot/update receiver starts monitoring when access is enabled. Real hardware
  reboot and standby reliability still need a separate validation run; the TCL's
  earlier standby test disconnected ADB, so these tests do not repeat it.
- Usage spans retain 90 days; diagnostics retain 14 days. Pause audit records are
  retained locally until app data is cleared/uninstalled. Exports contain reasons
  and package identities in plaintext; they are created only on request.

The data is `databases/focus.db` under the app's private directory. Manual export
writes `Android/data/org.nightingalelab.focus.tv/files/Documents/focus-tv-history.json`.
Exports have schema `version: 1`, `device: tv`, `scope: household`, millisecond
`s/e` spans and pause records. Desktop snapshots use a separate authenticated API; manual export remains available.

## Build and install

Requires the existing WSL Java/Python 3.11+ toolchain and Windows Android SDK 35 with
build-tools 35.0.0. No Gradle, dependency downloads or shared ADB restart.

```bash
bash tv/test.sh
bash tv/build.sh
python3 tv/install.py --serial '<authorized TV serial>' --enable-access
# Subsequent signed updates:
python3 tv/install.py --serial '<authorized TV serial>' --enable-access --update
# Optional one-time grant for native DNS controls, then open NextDNS → Connect:
python3 tv/install.py --serial '<authorized TV serial>' --update --enable-dns-controls
```

The installer requires an explicit Android TV target and refuses an unknown
existing package unless `--update` is given. `--enable-access` grants only Focus's
Usage Access and enables only its accessibility component, preserving other
enabled services. Without it, enable the two permissions through Settings. This
computer-assisted setup is verified; remote-only permission onboarding depends
on the TV firmware and is not guaranteed. DNS values are compared in memory and
only an unchanged boolean is saved by the installer. It grants DNS controls only
when explicitly requested; it does not connect a profile or write DNS settings.
The private accessibility snapshot stays in ignored `tv/build/`. The app keeps
its configured profile and exact previous DNS settings in private preferences.

The stable signing key is generated outside the checkout at
`${XDG_DATA_HOME:-$HOME/.local/share}/focus-tv/signing`. Keep that private directory
for future updates; losing the key requires uninstalling before a differently
signed install, which removes app data. `FOCUS_TV_SIGNING`, `FOCUS_TV_SDK` and
`FOCUS_TV_ADB` override local tool locations. APK output is `tv/build/focus-tv.apk`.
The release APK is not debuggable and backup is disabled.

## Verification

```bash
bash tv/test.sh
bash tv/build.sh
bash tv/build-tests.sh
python3 tv/device-check.py --serial '<authorized TV serial>'
```

The hardware runner installs a separate fixture and signed instrumentation APK.
It checks real SQLite clipping, Usage Access, a blocked fixture, reason validation
through native controls, a remote OK press, countdown, short synthetic expiry,
allowance/schedule enforcement, real YouTube launch blocking when installed,
Home recovery, accessibility disable/re-enable and process persistence. It takes
screenshots only while Focus or the fixture is confirmed foreground. It restores
test rules, removes fixture history/test APKs, and leaves Focus installed and
enabled. It does not sleep/reboot the TV, read unrelated history or change DNS.

Instrumentation force-stops the target, so the controller rebinds only Focus's
accessibility component when needed, with a bounded retry and explicit binding
verification. If runtime checks passed but final cleanup was interrupted, recover
the same checked APK with `python3 tv/device-check.py --serial '<authorized TV serial>'
--finish-cleanup`; the earlier failure remains in the report. A private rollback snapshot inside the app
allows the cleanup phase to restore rules even after an interrupted test. Device
results and captures stay under the ignored `evidence/private/` directory. See the
[historical hardware checks](VERIFICATION.md) and the [isolated screenshot command](../docs/screenshots.md).

### Real NextDNS checks

Use the signed companion and test APKs on an explicitly authorized Android TV
with the settings grant and NextDNS already connected. The test profile must block
YouTube and allow `example.com`. See [NextDNS setup and upgrades](../docs/nextdns.md).

```sh
python3 tv/dns-check.py --serial '<authorized TV serial>' --profile '<profile-id>' \
  --confirm-dns-changes --output /path/to/local-result.json
```

The runner exercises uncached A/AAAA responses, a timed pause, early resume,
process restart, permission denial, and recovery of the pre-Focus DNS settings.
It removes the test APKs and synthetic pause records and leaves the selected
profile connected. Use `--ui-only` to check dialog reopen and final DNS.
The local report contains the results and omits the profile and TV serial.
DNS pause audit rows use the reserved key `focus:dns`.

## Remove

First use **NextDNS → Restore previous DNS** if you want the pre-Focus resolver.
Then disable Focus under Accessibility and uninstall **Focus** in TV Settings.
Uninstalling alone does not reset system DNS. Usage access and private data are
removed by Android. Keep an export first if you want the history.

## Platform references

- [UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager)
- [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [TV remote navigation](https://developer.android.com/design/ui/tv/guides/foundations/navigation-on-tv)
- [Android Private DNS implementation](https://android.googlesource.com/platform/packages/apps/Settings/+/main/src/com/android/settings/network/PrivateDnsModeDialogPreference.java)
- [Validated Private DNS state](https://developer.android.com/reference/android/net/LinkProperties#isPrivateDnsActive())
- [Exact alarm lifecycle and restrictions](https://developer.android.com/reference/android/app/AlarmManager)
