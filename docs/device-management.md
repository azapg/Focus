# Manage devices from Windows

The dock keeps its compact layout. **All devices** is a text dropdown with
checkmarks: uncheck a device to exclude its statistics. This does not disconnect it
or change its protection. The gear opens a separate settings page.

## Connect a TV

1. Install the updated Windows app and TV APK. Keep both on the same network.
2. In Windows, open the dock's statistics, then **Settings → Connect TV**.
3. Choose the TV from **Nearby TVs**. No IP address is needed.
4. Check that both screens show the same six-digit number, then choose **Allow**
   on the TV. If no prompt appears, open Focus on the TV.

Approval expires after two minutes. It grants this laptop access only after the
local TV action. Cancel, Decline and Back leave it unpaired. The TV accepts up to
eight managers. After approval, Windows reconnects automatically across app restarts
and TV address changes, provided the saved device ID and certificate still match.

Discovery uses standard mDNS/DNS-SD on the local network. Focus advertises while
running and device management is enabled. A new install enables discovery by
default; an existing explicit opt-out stays off. Re-enable **Allow nearby connections**
in the TV's **Settings → Connected devices**. Guest Wi-Fi or router isolation can
prevent devices from finding each other. Sleeping TVs may be unreachable.

Select the TV on the settings page to change its NextDNS profile, pause or resume
filtering, or verify the resolver. Expand an app to block
it, set a daily limit or overnight schedule, or grant/end a reason-required timed
exception. Rules and timers run on the TV even when the laptop disconnects.
The existing one-time TV settings grant is still required for DNS controls;
Usage Access and Accessibility are still required for app restrictions.

**Forget device** deletes that laptop's connection and cached TV data. It does not
clear the TV's rules or revoke other managers. **Disconnect all managers** on the
TV revokes all credentials and turns off the listener. Enable nearby connections and approve again to reconnect.

## What the statistics mean

Today and Week include the selected devices' app time, recorded DNS-off pause
time and pause reasons. TV app exceptions also appear in history, separately from
DNS pauses. DNS-off shading is calculated per device before combining totals, so a
TV pause never marks laptop activity as unprotected.

Screen time is the sum of device time, including simultaneous use. TV activity is
household foreground app use, not personal attention; it does not identify viewers
or HDMI content. A gap in TV tracking is not reported as proven screen-off time.
Statistics use the laptop's calendar; daily rules use the TV's calendar/timezone.

The laptop requests a recent history snapshot every 30 seconds. The TV supplies
nine calendar days so the Windows Today/Week ranges remain covered across timezone
differences; the TV still retains 90 days locally. Adjacent tracking samples are
coalesced on the wire, without changing their total duration. The desktop's cached
snapshot is replaced atomically, rather than appended, so repeated refreshes do not
double-count time. The laptop does not archive older TV snapshots indefinitely.

When the TV is unreachable, Windows labels the last successful update, keeps the
saved statistics and disables remote controls. A saved open pause stops accumulating
at that snapshot's timestamp. DNS audit rows stay open until restoration succeeds,
even if the deadline has passed or the settings permission is missing. Missing snapshots and missing usage permissions are
shown explicitly. Mutations are never automatically retried: if an acknowledgement
was lost, refresh to see the TV's actual state before submitting again.

## Connection and API

`devices/` contains the UI-independent manager. A device has a stable identity and
a tagged connection configuration. Today the only connection kind is `lan_https`.
The API and device ownership are independent of the Windows dock, allowing a future
phone client to pair in the same way. A Windows server, Tailscale connection kind,
cloud relay, phone app, shared allowance and conflict-resolving offline writes are
not implemented.

The TV advertises `_focus-tv._tcp.local.` and serves TLS 1.2/1.3 on TCP 47832.
Its identity key lives in Android Keystore. Advertisements are untrusted discovery
hints: Windows probes the public hello without credentials, then pins that observed
certificate for the approval exchange. No credential is issued before TV approval.

Windows commits to a random 256-bit nonce before receiving the TV nonce, then
reveals it. Both sides derive the comparison number from the full certificate
fingerprint and both nonces. The user must compare that number before approving;
this is a six-digit comparison, not a certificate-authority identity guarantee.
The TV allows one waiting request, expires it after two minutes, and limits starts
to five per five minutes. Polling/cancellation require an unguessable request secret.
An approved credential remains retrievable for two minutes; cancelling an unclaimed
approval revokes it. These protocol guards are tested but have no external security
audit. Once paired, a discovered address is adopted only when both the full saved
certificate fingerprint and device ID match. Handshake signatures are verified by
rustls. No proxy, redirect, browser origin, arbitrary file access or remote command
execution is supported.

The Windows installer adds an app-specific inbound UDP 5353 firewall rule restricted
to the local subnet. It opens no Windows management server. The legacy code-pairing
API remains for compatibility; the current desktop and TV flows use TV approval.

Each manager receives a random 256-bit bearer credential. The TV persists only its
hash. Windows protects its credential with user-scoped DPAPI in
`%LOCALAPPDATA%\FocusDNS\devices.json`; the UI never receives it. Cached usage and
pause reasons in that file are plaintext local data, like the existing Windows
history. The dry-run app uses its separate `FocusDNS-dry` directory. Portable tests
can explicitly enable `portable-test-harness` for disposable directories; production
non-Windows credential storage is intentionally unsupported.

The initial LAN connection accepts numeric private/loopback IPv4 and unique-local
IPv6 endpoints. Each request has bounded headers and a maximum 16 KiB body; responses
are limited to 8 MiB. The TV has a bounded worker pool and socket timeouts. The TV
listener shares the existing monitor's lifecycle, requires no router changes, and
does not keep a sleeping TV awake. Wake/standby reachability depends on the TV.

| Request | Access | Result |
| --- | --- | --- |
| `GET /v1/hello` | Public TLS probe | Protocol version, stable ID, kind and name |
| `POST /v2/pair/request` | Observed pinned TLS, nonce commitment | Request ID, poll secret and TV nonce |
| `POST /v2/pair/reveal` | Request secret, committed nonce | Matching number and on-TV approval prompt |
| `POST /v2/pair/status` | Pinned TLS + request secret | Waiting/declined/expired; credential only after local approval |
| `POST /v2/pair/cancel` | Pinned TLS + request secret | Cancel approval, revoke an unclaimed credential |
| `POST /v1/pair` | Legacy active on-TV code, confirmed TLS pin | Legacy manager credential |
| `GET /v1/snapshot` | Pinned TLS + credential | Recent spans/pauses, app rules, DNS and permission state |
| `POST /v1/command` | Pinned TLS + credential | Apply one control and return a confirmed snapshot |

Commands are `dns_connect`, `dns_pause`, `dns_resume`, `dns_verify`,
`set_rule`, `app_pause` and `app_resume`. Pauses require 1, 5, 10 or 30
minutes and a 3–200-character reason. Partial rule updates preserve other fields.
The server rejects protected packages, nonintegral/out-of-range values and missing
permissions. The versioned snapshot is validated before replacing the desktop cache.

Diagnostics record listener startup/failure, pairing/revocation, sync transitions and
command confirmation. Codes, bearer credentials, request bodies and pause reasons
are not included in connection logs. Existing local pause history still records
reasons by design.

## Checks

```bash
npm ci
npm run build
node --test tests/extension.test.mjs tests/stats.test.mjs tests/device-stats.test.mjs
uv run --with playwright python tests/panel-browser.py
uv run --with playwright python tests/device-panel.py
cargo test --manifest-path devices/Cargo.toml --features portable-test-harness
# Explicit multicast integration check on the host network:
cargo test --manifest-path devices/Cargo.toml discovery_finds_tls_device_without_an_address -- --ignored
bash tv/test.sh
bash tv/build.sh
bash tv/build-tests.sh
# Only on an explicitly chosen disposable emulator, never a physical TV:
python3 tv/lan-emulator-check.py --serial emulator-5580
bash scripts/build.sh
```

Windows regression checks (from the Windows build directory) also exercise DPAPI:

```powershell
cargo test --release --manifest-path devices/Cargo.toml
cargo test --release --manifest-path app/Cargo.toml
```

The [screenshot command](screenshots.md) runs the production frontend with fictional Tauri device responses.
Portable TLS tests use an in-process TLS fixture. The emulator checks execute the
real Android Keystore, TLS listener, authenticated API, SQLite rule mutations,
exception lifecycle, input validation and permission refusals. They do not change
DNS settings and do not prove physical-TV standby reliability or live filtering.


The frontend now builds with Vite and React. The dock's original styling remains;
its device menu, settings controls and fields use locally installed shadcn/ui
(`radix-nova`) components. All interface icons use `lucide-react`. Add components
with `npx shadcn@latest add <component>` using the checked-in `components.json`.
Generated source imports use `@/lib/utils`; the small local `shadcn.css` contains
the upstream Tailwind state variants, so the CLI is not a build dependency.
`npm run build` produces `ui-dist`, which the Windows executable embeds. The build
script performs this step before compiling Rust.


The multicast integration check was also run natively on Windows. It advertises a
disposable TLS fixture, then finds it through the real mDNS browser; it never issues
a credential or touches the user registry. For read-only discovery diagnostics, use
`cargo run --release --manifest-path devices/Cargo.toml --example nearby`.

The Android API 36.1 emulator run verified the API/security checks and cleanup.
A new authorized test run writes its report under the ignored `tv/evidence/private/` directory.
That includes the real Android Keystore TLS handshake and persistent app-rule writes;
it does not replace the separate physical-TV/DNS verification described above.
