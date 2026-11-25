# Explain a Focus failure

Reproduce the failure and note when it happened. On Windows, open PowerShell in a checkout of this repository and run:

```powershell
py -3 scripts/explain-failures.py --hours 6
```

The report joins phone and laptop events for the same Ask or pause by a random request ID. It shows the time in UTC, device, build, step, outcome, cause and any facts recorded for that step. A download can have its own attempt ID if it starts from an earlier update check. The report summarizes the recorded causes on one line per attempt and keeps the details in the timeline. An attempt shown in the report can include correlated events from before the `--hours` cutoff.

| Where | How to get the events |
| --- | --- |
| Windows laptop | Use the command above. It reads `%LOCALAPPDATA%\FocusDNS\events\`. |
| Paired Android phone | Leave Focus running on the laptop. Its existing Wi-Fi sync pulls up to 32 events per successful sync. Older builds can keep using regular device sync. |
| Android phone when sync is broken | Open **Settings → Permissions & logs → Share diagnostics**. Save or transfer `focus-events.jsonl` using the system share sheet. |
| Android TV | TV activity and control continue to use the existing diagnostics. This event log covers Android phones and Windows laptops. |

Pass the Windows event directory and an exported phone file together to join them and remove duplicate events:

```powershell
py -3 scripts/explain-failures.py --hours 6 "$env:LOCALAPPDATA\FocusDNS\events" "$env:USERPROFILE\Downloads\focus-events.jsonl"
```

The report says **inbound blocked, outbound fine** when the phone times out or cannot reach the laptop, the same phone has a successful laptop-to-phone sync within five minutes, and the current listener on a Public network has a successful Domain and Private firewall rule for its port. An authenticated inbound request within five minutes or a laptop HTTP success for the same Ask rules out that diagnosis. Missing or conflicting evidence leaves inbound reachability unknown. The app does not inspect packet drops or other firewall rules. For multiple active Windows firewall profiles, the event says `mixed`.

The summary prioritizes a recorded cause over a generic fallback such as `no_laptop_answer`. If the same Ask records both a laptop-side failure and a Gateway failure, the summary shows both.

## What gets recorded

The phone keeps up to 1,024 events in its private database. Windows keeps at most 512 KiB per file and up to 16 phone files. Size limits can remove older events sooner than 14 days. Old events are removed during normal recording and sync, or during phone export. The report window is at most 14 days. Gaps can result from an unavailable device, older builds, or local storage failures.

The event log has no cloud upload. It records times, durations, build and request IDs, the paired phone's generated ID, and a fixed set of numbers, flags and enum values. The event log and export reject request text, reasons, URLs, hostnames, titles, app names, keys, tokens and fingerprints. The existing activity and pause history files serve other features and are outside this report.

Phone events and synced Windows copies survive app restarts. A dropped connection can delay delivery until another normal sync. Progress between two devices depends on their wall clocks. Repeated LAN results with the same status are sampled at most once every five minutes per result. Requests with an Ask correlation ID retain their separate laptop request entries.

## Event names and stages

| Event | Stages |
| --- | --- |
| `ask` | `start`, `laptop`, `laptop_abandoned`, `gateway`, `eligibility`, `decision` |
| `pause` | `apply`, `pause`, `resume` |
| `update` | `check`, `download`, `verify`, `install` |
| `lan` | `bind`, `firewall`, `network`, `address`, `connect`, `tls`, `auth`, `request`, `reachability` |
| `dns` | `pause`, `resume`, `verify` |
| `nextdns` | `check`, `apply`, `cleanup`, `reconcile` |

The phone generates one ID for each Ask and carries it into Apply. Events store it as `correlation_id` with `origin=phone` and the route used. Laptop commands send it as an optional top-level field beside `action`. The `ask/eligibility` step captures `scope_available`, `dns_enabled`, `dns_paused`, `vpn_default`, `strict_mode` and `private_dns_active` when observed. The `lan/reachability` step records `direction=phone_to_laptop` or `direction=laptop_to_phone`.

## Cause codes

| Code | Meaning |
| --- | --- |
| `ok` | Completed successfully |
| `laptop_unreachable` | Phone could not reach the laptop |
| `laptop_timeout` | Laptop did not respond in time |
| `laptop_tls_mismatch` | Laptop identity did not match the paired certificate |
| `laptop_unauthorized` | Laptop refused the phone's pairing credential |
| `laptop_judge_disabled` | Ask is disabled on the laptop |
| `laptop_unsupported` | Laptop build does not support Ask |
| `laptop_rejected` | Laptop refused the operation |
| `laptop_invalid_reply` | Laptop returned an invalid reply |
| `gateway_no_key` | Gateway API key is missing |
| `gateway_keystore` | Gateway API key could not be read |
| `gateway_http_4xx` | Gateway returned a client error |
| `gateway_http_5xx` | Gateway returned a server error |
| `gateway_cancelled` | A Gateway retry was cancelled |
| `gateway_transport` | The Gateway connection failed |
| `gateway_timeout` | Gateway did not respond in time |
| `gateway_unreachable` | Gateway could not be reached |
| `gateway_invalid_reply` | Gateway returned an invalid reply |
| `gateway_refused` | Gateway model refused the request |
| `no_laptop_answer` | No laptop decision was available to open services |
| `scope_key_missing` | Laptop has no NextDNS API key for scoped access |
| `scope_profile_mismatch` | Phone and laptop use different NextDNS profiles |
| `dns_disabled` | NextDNS is disabled |
| `dns_paused` | NextDNS is already paused |
| `private_dns_inactive` | Private DNS is inactive on the phone's active network |
| `phone_pause_unavailable` | The phone could not offer a pause |
| `cap_exceeded` | Pause limit was reached |
| `decision_expired` | Pause decision expired or was already used |
| `apply_timeout` | Applying scoped access timed out |
| `nextdns_auth` | NextDNS credential was unreadable or refused |
| `nextdns_rejected` | NextDNS returned an error or invalid reply |
| `nextdns_unreachable` | NextDNS could not be reached or timed out |
| `apk_too_large` | APK exceeds maximum allowed size |
| `update_auth` | Release access requires a valid read credential |
| `update_http` | Release or asset request failed |
| `update_no_asset` | Release or required asset was missing |
| `update_invalid_manifest` | Android build manifest was invalid |
| `update_download` | APK download failed |
| `update_verify` | APK size, hash, package, version or signing check failed |
| `update_no_space` | Not enough free space to stage or install the APK |
| `update_install` | Android could not install the APK |
| `update_cancelled` | Update was cancelled |
| `lan_unreachable` | Local device could not be reached |
| `lan_timeout` | Local connection timed out |
| `lan_tls_mismatch` | Local device identity did not match the paired certificate |
| `lan_unauthorized` | Local device refused the pairing credential |
| `lan_rejected` | Local connection or request was refused |
| `lan_invalid_reply` | Local device returned an invalid reply |
| `lan_bind_failed` | Local listener failed to start |
| `firewall_failed` | Windows could not install the LAN firewall rule |
| `dns_apply_failed` | DNS change or verification failed |
