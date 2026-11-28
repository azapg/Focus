# Working on Focus

Keep personal data private. Access to a device or its diagnostics is permission to investigate the reported issue. Ask before sharing any real device data.

Use invented examples for screenshots, tests, logs, and PR descriptions. Capture the actual app in a clean profile or emulator with fictional activity and test peers. If an issue only reproduces on personal hardware, describe the outcome and keep the raw evidence private.

Open a PR for the owner's review before publishing changes. Inspect the diff and any attached images for personal details or credentials, and say what you tested.

## Start from the screen

Before changing a flow, open it on the intended device or inspect a current capture from the [screenshot guide](docs/screenshots.md). If the capture is missing or stale, run the flow with fictional data. Show design options in that same flow using T3 HTML Preview. Keep proposals in the active worktree and label them as proposals.

For cross-device work, one agent owns the whole user task. Record the expected result on each intended device and try it from the user's normal starting state. In the PR, give the entry point, the result you observed and any coverage gaps. Identify the build you checked when reporting that a change reached an installed app.

## Screen entry

Start desktop screen work in the [offline Focus product entry](docs/product/index.html). It embeds the checked-out Vite frontend, starts with a fictional zero-coin wallet, and links to a funded 65-coin sample. Refresh it with `npm ci && npm run build && npm run product:entry`; `npm run product:entry -- --check` detects stale output. The PR/main workflow builds and checks the entry, then smoke-tests Today → Store and the sample purchase. The preview reports its source and asset fingerprints; shipped app status is separate.

## Color means bad

Focus is monochrome by default. Normal, completed, and within-limit states stay black, white, and gray. Do not use decorative colors, category rainbows, badges, or green accents for good states.

Reserve color strictly as a psychological signal:

- **Ice (`#cbe2ff`, text and bar/pill signal only, no badges):** Earned long single-app deep focus sessions (25m+ unbroken in Coding, Writing, or Design; 15m+ unbroken in Reading or Learning). Never applies to video, social feeds, scrolling, or time during DNS-off pauses. Any warning or breach color overrides Ice.
- **Orange (warning or slip):** Approaching the DNS-off limit, due habits, drifting or time-wasting activity (social feeds, videos, streams, unjustified X or Twitch), pausing and drifting off the stated reason, or low pause ratings.
- **Red (breach or guilt):** Hard violations that must stand out and stay visible for the rest of the day, including going over the daily DNS-off limit, overdue or missed habits, heavy doomscrolling, repeated unjustified overrides, or broken protection.

Never mute warning or breach tokens into neutral gray, and never use orange or red on neutral labels (such as timestamps) or positive actions (such as hold-to-complete fills).
