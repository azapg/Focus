<img src="../assets/icon.svg" width="48" height="48" alt="Focus">

# Focus for Android

Native Android phone app for Focus. Tracks phone apps and per-site mobile browsing, protects Private DNS against quick toggles in Android Settings, syncs with Focus TV and Windows over Wi-Fi, and lets you pause one device at a time with rising friction.

## What it includes

- **Today and Week:** One sentence ("7h 21m on screens, 4h 20m building, 43m drifting."), automatic categories, and your top apps with real icons. Open an app to see its category, devices and minutes used with DNS off. Week adds 7 day bars with DNS-off time in amber. Filter by device, or open **See all** to search every app.
- **Pauses card:** A ring for today's DNS-off time against a daily limit (1h by default, shared by all devices), the 3 biggest pauses with a running or just-ended pause first, and what you opened during each one.
- **Pause:** Hold the bottom pill to open the Pause sheet. Pick one device and 1, 5, 10 or 30 minutes, capped by your longest pause and by what's left of today's limit (1 minute is always allowed). From the 3rd pause of the day there's a 15s wait, and every pause needs a typed reason. While paused, the pill reads `Turn on <device> · m:ss`.
- **Phone pause timer:** A phone DNS pause shows a countdown and **Turn on now** in Android notifications. For the Now Bar, allow **Live notifications** for Focus under **Settings → Notifications → App notifications → Focus**. If the switch is missing on your One UI build, check **Developer options → Live notifications for all apps**. On some builds this is under **More settings → Apps**.
- **Settings with strictness ratchet:** Daily DNS-off limit, wait before pausing, auto-resume DNS, devices, and toggles. Tightening a rule starts right away; loosening waits until midnight and can be undone. Categories are sorted automatically.
- **Private DNS guard (Option 1):** Uses `WRITE_SECURE_SETTINGS` plus a `Settings.Global` observer to restore the configured NextDNS hostname within milliseconds if changed outside a pause, and Accessibility closes the Private DNS dialog in Settings. Keeps Android's VPN slot free so Tailscale keeps working.
  See [NextDNS setup and upgrades](../docs/nextdns.md) for profile setup and recovery.
- **Per-site mobile browser tracking:** Reads the active tab domain from Chrome, Samsung Internet, Brave, Edge, Firefox, Opera, Vivaldi, and DuckDuckGo via Accessibility. Private tabs are supported when Android exposes their address bar. Tracking continues during DNS pauses.
- **Four home screen widgets:** The score (A), The sentence (B), The week (C), and The ring (E).
- **Built-in nightly updater:** Checks the rolling `nightly` GitHub Release, verifies SHA-256 and matching APK signing certificate, and installs updates in place via Android's `PackageInstaller` while preserving all permissions and data. The update icon opens an "Update ready" sheet with the build's release notes.

## Build and test

Install Android SDK Platform 36 to build the phone APK. For Windows tools under
WSL, set `FOCUS_PHONE_SDK` to the mounted SDK directory.

```bash
bash android/test.sh
bash android/build.sh
```

The signed APK is written to `android/build/focus-phone.apk` and its manifest to `android/build/android-nightly.json`. If `android/build/notes.json` exists (or `NOTES_FILE` points to one), its release notes are added to the manifest as `notes`:

```json
{"items": [["Title", "One sentence."]], "more": "Plus 3 more fixes."}
```

The [screenshot command](../docs/screenshots.md) builds Focus and captures fictional phone activity in a fresh emulator. For separate integration checks, run `python3 android/emulator-check.py --serial emulator-5554` on an explicitly selected disposable emulator.

Run `python3 android/emulator-check.py --serial emulator-5554 --ask-only` to exercise Ask and its scoped and full-pause notifications on a disposable emulator.

## Nightly updates on your phone

1. On GitHub, store your signing keystore in repository secrets `FOCUS_ANDROID_KEYSTORE_BASE64` and `FOCUS_ANDROID_KEYSTORE_PASS` so CI signs with the same key already on your phone.
2. In Focus on your phone, open **Settings -> Nightly updates**, add your read-only GitHub token (`Contents: Read`), and allow **Install unknown apps** for Focus once.
3. Every 6 hours, if `main` changed, or on a manual run on `main`, CI publishes a new `focus-phone.apk` and `android-nightly.json` to the `nightly` release. Focus notifies you when a newer build is ready and installs it in 1 tap.

## Install on your phone via Wi-Fi debugging

1. On your phone: **Settings -> Developer options -> Wireless debugging -> Pair device with pairing code**.
2. Pair and connect over ADB, then run the installer:

```bash
adb pair <phone-ip>:<pair-port>
adb connect <phone-ip>:<connect-port>
python3 android/install.py --serial <phone-ip>:<connect-port> --enable-access --enable-dns-controls
```

### Peek builds

The APK bundles the same Rust renderer as the Windows app. Install the Linux
Android NDK r28c (SDK package `ndk;28.2.13676358`) and Rust before running
`android/build.sh`. On WSL use the Linux NDK as well. Set `FOCUS_PHONE_NDK`
if it is outside your Android SDK. Install the Rust targets with:

```sh
rustup target add aarch64-linux-android
```

The nightly APK supports 64-bit ARM phones. To build for an x86_64 emulator,
install the `x86_64-linux-android` Rust target and run the build with
`FOCUS_PEEK_ABIS=x86_64`.

Turn on the Focus NextDNS profile, open **Ask**, and submit a single post
link to open the Peek drawer. You can also share a public X or Facebook
link to Focus and tap **Peek** on the prefilled screen. Android may offer
the same screen in its link chooser. The phone fetches the static post
directly.
