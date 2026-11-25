// Generate fictional Focus histories with the same on-disk schema as the app.
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [out, scenario = 'week'] = process.argv.slice(2);
if (!out || !['week', 'pauses'].includes(scenario)) {
  throw new Error('Usage: node tests/fixtures/focus-replay.mjs <output-directory> [week|pauses]');
}
const day = 86_400_000;
const minute = 60_000;
const base = Date.parse('2026-02-12T00:00:00Z');
const at = (days, minutes) => base + days * day + minutes * minute;
const histories = new Map();
const pauses = [];

function span(start, duration, app, label = app, url = null) {
  // Split at both the minute checkpoint and midnight, as the live tracker does.
  const end = start + duration * minute;
  for (let s = start; s < end;) {
    const e = Math.min(s + minute, end, (Math.floor(s / day) + 1) * day);
    const date = new Date(s).toISOString().slice(0, 10);
    if (!histories.has(date)) histories.set(date, []);
    const entry = { s, e, app, label };
    if (url) {
      entry.source = 'chromium_extension';
      entry.browser = { url, title: 'Seed catalog', visibility: 'visible', audible: false,
        tab_id: 1, window_id: 1, browser_pid: 2, host_pid: 3 };
    }
    histories.get(date).push(entry);
    s = e;
  }
}

span(at(-6, 600), 37, 'VS Code');
span(at(-5, 630), 18, 'Chrome', 'catalog.example', 'https://catalog.example/seeds');
span(at(-3, 1432), 17, 'Terminal');
span(at(-1, 648), 26, 'VS Code');
span(at(0, 540), 41, 'VS Code');

if (scenario === 'pauses') {
  const t = at(0, 671);
  const minutes = 10;
  const until = t + minutes * minute;
  pauses.push({ type: 'pause', t, until, minutes, reason: 'Find the seed swap time on Discord' });
  pauses.push({ type: 'resume', t: until, paused_at: t });
  span(t, 4, 'Chrome', 'discord.com', 'https://discord.com/channels/123/456');
  span(t + 4 * minute, 6, 'Chrome', 'youtube.com', 'https://www.youtube.com/watch?v=demo');
}

const jsonl = (entries) => entries.map((entry) => JSON.stringify(entry) + '\n').join('');
mkdirSync(join(out, 'activity'), { recursive: true });
for (const [date, spans] of histories) {
  writeFileSync(join(out, 'activity', `${date}.jsonl`), jsonl(spans), { flag: 'wx' });
}
writeFileSync(join(out, 'pauses.jsonl'), jsonl(pauses), { flag: 'wx' });
