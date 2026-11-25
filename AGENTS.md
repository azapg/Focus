# Working on Focus

Keep personal data private. Access to a device or its diagnostics is permission to investigate the reported issue. Ask before sharing any real device data.

Use invented examples for screenshots, tests, logs, and PR descriptions. Capture the actual app in a clean profile or emulator with fictional activity and test peers. If an issue only reproduces on personal hardware, describe the outcome and keep the raw evidence private.

Open a PR for the owner's review before publishing changes. Inspect the diff and any attached images for personal details or credentials, and say what you tested.

## Color means bad

Focus is monochrome by default. Normal, completed, and within-limit states stay black, white, and gray. Do not use decorative colors, category rainbows, badges, or green accents for good states.

Reserve color strictly as a psychological signal:

- **Ice (`#cbe2ff`, text and bar/pill signal only, no badges):** Earned long single-app deep focus sessions (25m+ unbroken in Coding, Writing, or Design; 15m+ unbroken in Reading or Learning). Never applies to video, social feeds, scrolling, or time during DNS-off pauses. Any warning or breach color overrides Ice.
- **Orange (warning or slip):** Approaching the DNS-off limit, due habits, drifting or time-wasting activity (social feeds, videos, streams, unjustified X or Twitch), pausing and drifting off the stated reason, or low pause ratings.
- **Red (breach or guilt):** Hard violations that must stand out and stay visible for the rest of the day, including going over the daily DNS-off limit, overdue or missed habits, heavy doomscrolling, repeated unjustified overrides, or broken protection.

Never mute warning or breach tokens into neutral gray, and never use orange or red on neutral labels (such as timestamps) or positive actions (such as hold-to-complete fills).
