# Focus screenshots

Use the screenshot command to capture the actual Focus interface with invented
activity. The command runs on Linux with unprivileged user, network and PID
namespaces enabled. It refuses to capture if it cannot establish isolation.

## Desktop frontend

From the repository root, install the build dependencies and build the UI.
Install Playwright 1.65.2 and its Chromium build in your Python environment.

```sh
npm ci
npm run build
python -m pip install playwright==1.65.2
python -m playwright install chromium
python scripts/screenshot.py desktop --out build/screenshots-desktop
```

Choose `activity`, `devices`, `updates`, `store`, or `intentions` to capture one flow. Desktop runs
the same frontend bundle that Focus embeds on Windows. Each flow gets a fresh
headless browser context, a fixed UTC clock and in-memory Tauri responses. The
browser uses a temporary configuration and blocks external requests, WebSockets
and service workers. Discovery, pairing, updates and Ask responses come from the
fixtures. Browser notifications are disabled.

- **Activity** captures browser-site breakdowns, the searchable Explorer, a
  seven-day chart with empty days and a span that crosses midnight, a fictional
  Peek post, and Ask retry after a simulated Gateway error.
- **Devices** captures device filters, TV rules, discovery and a fictional
  pairing code. The fixture simulates approval, cancellation and offline errors.
- **Updates** captures protection settings and the update card and settings using
  invented build metadata.
- **Store** captures a 5-minute Free Time purchase with a fictional balance.
- **Intentions** captures the dock bar with no intention, the read-back card, an active intention pill, off-plan warning, the 18-minute orange nag, the time-up check-in card, and the Today stats Intentions section.

The regenerated device previews are [stats and settings](previews/implemented-devices.html)
and [nearby pairing](previews/nearby-pairing.html).

## Native phone and TV

Install a JDK, iproute2, Linux Android build-tools, platform-tools, Android Emulator, SDK
Platform 36 for phone and SDK Platform 35 for TV. Set `JAVA_HOME` if the JDK is
outside the system path. Use a locally installed debuggable x86_64 AOSP or Android
TV system image, and have space for a fresh emulator data disk. Cold boots with
software emulation can take tens of minutes. The image path is relative to the SDK. A landscape AOSP image can exercise the TV app UI.

```sh
python3 scripts/screenshot.py phone --sdk "$ANDROID_HOME" \
  --image system-images/android-35/default/x86_64 --out build/screenshots-phone
python3 scripts/screenshot.py tv --sdk "$ANDROID_HOME" \
  --image system-images/android-35/default/x86_64 --out build/screenshots-tv
```

The runner builds the native app and its instrumentation in a temporary tree
using a new signing key. It starts a fresh guest with an isolated ADB server,
masks host USB devices and keeps only loopback available on the host side of the
guest network. It installs only the freshly built APKs. The command does not
grant Private DNS, accessibility or notification permissions. The phone
screenshot build omits the Peek renderer and does not produce a release manifest.
The phone flow shows Today, Week and the update sheet. The TV flow shows Today
and Week, selected through the production remote-control navigation.
Use `phone-store` with the same SDK and image arguments to capture a time-off
purchase in the native Store with a fictional balance. `phone-store-zero`
captures Today with a zero coin balance, then opens Store. `tv-store` seeds a
fictional installed TV app as blocked, captures the production blocked-app
screen, then opens its Free Time store. The TV flow uses an AOSP landscape
image; it exercises the TV app layout and controls, but does not verify a
certified Android TV build.

For the Open Focus pilot, copy the selected PNGs into
`docs/product/native/{phone,tv}/` and add a sibling `manifest.json` with
`schema: 1`, the platform name, the source commit used for capture, and ordered
`steps` containing each visible title and relative PNG filename. Keep the
captured output directory disposable; the repo-local PNGs and manifest are the
reviewable refresh.

Choose a new output directory for each run. Successful runs publish a directory
of PNGs. The built APKs, emulator data and profiles are discarded. Fonts,
rendering and system UI can vary with the Chromium build or Android image.

Desktop captures cover the production frontend with simulated native
responses. Native captures cover the instrumented APK in the chosen guest.
Physical devices, Windows WebView2, real LAN pairing, OS-level DNS changes,
live Gateway calls, installer behavior, and notification delivery require
separate integration checks.
