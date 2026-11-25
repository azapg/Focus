# TCL verification

## Native NextDNS integration — 2026-11-17

This authorized hardware run configured a test NextDNS profile through Android
Private DNS and exercised the native filtering controls.
See [NextDNS setup and upgrades](../docs/nextdns.md) for current setup.

The timed-DNS matrix passed. A subsequent UI correction prevented a background
verification callback from rebuilding the activity under an open dialog. The
follow-up verified reopening after background use, provider validation,
blocked/allowed controls, installed build and cleanup.

| Real integration check | Final result |
| --- | --- |
| Provider verification | Validated native DoT server addresses and fresh NextDNS test response |
| Real system DNS | YouTube A/AAAA blocked; allowed control resolves |
| Native remote form | Empty reason rejected; one-minute duration and supplied reason saved |
| Full one-minute DNS pause | YouTube resolves during pause; allowed control remains reachable |
| Background countdown and expiry | Corner countdown over fixture; filtering returns and countdown disappears |
| Early resume | Filtering returns before deadline |
| Process restart | Different process IDs confirmed; deadline survives and real filtering returns |
| Missing grant | Pause refused without changing DNS |
| Actual installed YouTube | Fresh launch shows a network error with no local app-block rule |
| Disconnect/reconnect | Exact absent original settings restored, then NextDNS reconnected |
| Final installation/cleanup | Installed hash matches build, both services running, test packages/history removed |

The process-restart check exercises normal app startup plus alarm/monitor recovery;
it does not isolate which restoration trigger won. Force-stop during a DNS pause,
hardware reboot, standby/endurance and newer-Android alarm onboarding remain
untested. The saved strict-mode/active flag alone is not validation; final success
also requires actual validated servers, the provider response and allowed control.

The physical display showed all five buttons with their labels. Some ADB captures
omitted static text even when the controls rendered correctly on the set.

The first integration run proved real A/AAAA filtering, an allowed control,
native one-minute pause/expiry, early resume and exact absent-setting restoration,
but failed its last UI-navigation step. Screenshot coordination was corrected
using a permission-protected, per-stage test acknowledgement. One later run lost
NextDNS TLS validation and timed out; a saved strict-mode hostname alone was not
accepted as proof. Redundant same-value writes were removed as a precaution, not a proven root-cause
fix. Final cleanup waits for real validated servers and an allowed control rather
than assuming three seconds is enough. A TV-only Wi-Fi reconnect restored native validation. Direct authenticated TLS queries to both NextDNS servers
from the TV had worked even while native validation was stuck. The exact cause
of that native resolver failure remains unconfirmed. The status now
invalidates cached verification when the network/link changes and rechecks in
the background. Transient provider/network availability is not guaranteed.

## Original local tracking and app restrictions — 2026-11-16

The original hardware run used a TCL Smart TV with Android 11 (API 30).
The signed build passed the following checks.

| Check | Result | Evidence |
| --- | --- | --- |
| Policy and timeline unit checks | PASS | 56 assertions: exact expiry, reboot invalidation, schedule endpoints/overnight, allowance bounds, screen-off exclusion, activity replacement, late-event replay, no overlapping spans. |
| Ordinary app Usage Access | PASS | Scoped fixture foreground time persisted through the app's own query and SQLite path. No historical pre-install activity imported. |
| SQLite interval clipping | PASS | Controlled stored spans were clipped to a selected range with the exact expected sum. |
| Native remote / pause form | PASS | A remote OK key opened the real form. Empty reason was rejected; a supplied reason saved the selected five-minute deadline. |
| Corner countdown | PASS | Actual overlay appeared over the foreground test fixture and was removed at expiry. |
| Expiry with Focus backgrounded | PASS | A separate five-second fixture exception expired and returned to the blocking screen. This shortens the test; the UI-selected deadline was checked independently. |
| Daily allowance and schedule | PASS | Both restricted the fixture through the real service. |
| Real YouTube launch | PASS | A temporary rule redirected the real installed YouTube app to Focus's blocking screen. The original rule was restored. |
| Recovery paths | PASS | Home returned from blocking. Disabled accessibility stopped enforcement; enabling it restored enforcement. Focus/Settings were excluded from restrictable apps. |
| Process restart | PASS | Usage history and an unexpired exception survived force-stopping and relaunching only Focus. No TV reboot. |
| Final cleanup | PASS with bounded recovery | Both temporary test APKs and their fixture history were removed. Test rules restored, both companion services active, accessibility enabled. |
| Private DNS | PASS | Mode and hostname compared equal in memory across the checks. Only the result boolean was retained. |

The final run recovered an accessibility binding after instrumentation restarted
the target. The first rebind attempt failed and the second succeeded.

The run also verified the collector was ready before the fixture opened. A
one-minute overlapping metadata query handles Android app-switch events that
arrive late, without importing old durations or counting them twice. The pure
timeline tests cover that recovery as well.

`--finish-cleanup` can separately recover an interrupted cleanup, but requires a
matching APK and completed runtime, restart and rule-cleanup results. The final
run above completed cleanup through its normal automatic recovery path.

## Reproducing visual evidence

Use the [Focus screenshot command](../docs/screenshots.md) for a native app capture
with invented activity. Authorized hardware checks write private local output
under the ignored `tv/evidence/private/` directory.

## Limits

This verifies normal app-launch restriction, fixture expiry, persistence and
recovery on this TCL. It does not prove all streaming players, picture-in-picture,
background audio termination, long-duration standby/endurance, or hardware reboot
recovery. Blocking is not OS package suspension and can be bypassed by revoking
accessibility or force-stopping Focus. Remote-only permission onboarding remains
unverified; the installed permissions were configured through the computer.

Phone handoff, automatic desktop synchronization and shared cross-device limits
are future work. TV history and controls work locally. Allowing an app does not
pause DNS filtering.
