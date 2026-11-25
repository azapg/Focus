// Generate synthetic on-disk Focus histories with the same schema as the live app.
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [out, scenario = 'signature'] = process.argv.slice(2);
if (!out || !['signature', 'work'].includes(scenario)) {
  throw new Error('Usage: node tests/fixtures/focus-detector-replay.mjs <output-directory> [signature|work]');
}
const base = Date.parse('2000-01-01T09:00:00Z');
const spans = [];
const phone = [];
const panels = [];
const pauses = [];
const stamp = (seconds) => base + seconds * 1000;

function span(at, duration, app, label = app, url = null) {
  // The tracker writes minute checkpoints even within the same foreground visit.
  for (let offset = 0; offset < duration; offset += 60) {
    const entry = { s: stamp(at + offset), e: stamp(at + Math.min(duration, offset + 60)), app, label };
    if (url) {
      entry.source = 'chromium_extension';
      entry.browser = { url, title: 'Example', visibility: 'visible', audible: false, tab_id: 1, window_id: 1, browser_pid: 2, host_pid: 3 };
    }
    spans.push(entry);
  }
}

for (let session = 0; session < 8; session++) {
  const start = session * 50 * 60;
  const work = session % 2 ? 'T3 Code' : 'VS Code';
  const workEnd = start + 18 * 60;
  span(start, workEnd - start, work);
  for (let i = 0; i < 30; i++) {
    const at = workEnd + i * 12;
    if (i % 3 === 0) span(at, 12, 'Terminal');
    else if (i % 3 === 1) span(at, 12, 'Chrome', 'docs.rs', 'https://docs.rs/example');
    else span(at, 12, work);
  }
  for (let i = 0; i < 8; i++) panels.push(stamp(workEnd + 60 + i * 22));
  if ([0, 4, 7].includes(session)) {
    for (const t of [workEnd + 60, workEnd + 4 * 60]) {
      phone.push({ s: stamp(t), e: stamp(t + 20), app: 'example.messages', label: 'Messages' });
    }
  }
}

if (scenario === 'signature') {
  const start = 7 * 50 * 60 + 24 * 60;
  // Quick context checks and general consumption before the first X visit.
  for (let i = 0; i < 20; i++) {
    const at = start + i * 9;
    if (i % 5 === 0) span(at, 9, 'T3 Code');
    else if (i % 2 === 0) span(at, 9, 'Slack');
    else span(at, 9, 'Chrome', 'youtube.com', 'https://www.youtube.com/watch?v=synthetic');
  }
  let pauseAt = start + 180;
  for (const minutes of [5, 10, 30]) {
    const end = pauseAt + minutes * 60;
    pauses.push({ type: 'pause', t: stamp(pauseAt), until: stamp(end), minutes });
    pauses.push({ type: 'resume', t: stamp(end), paused_at: stamp(pauseAt) });
    if (minutes === 30) {
      span(pauseAt, 16 * 60, 'Chrome', 'x.com', 'https://x.com/home');
      span(pauseAt + 16 * 60, 14 * 60, 'T3 Code');
    } else {
      span(pauseAt, minutes * 60, 'Chrome', 'x.com', 'https://x.com/home');
      span(end, 35, 'Terminal');
    }
    pauseAt = end + 35;
  }
}

const jsonl = (entries) => entries.map((entry) => JSON.stringify(entry)).join('\n') + '\n';
mkdirSync(join(out, 'activity'), { recursive: true });
writeFileSync(join(out, 'activity', '2000-01-01.jsonl'), jsonl(spans));
writeFileSync(join(out, 'pauses.jsonl'), jsonl(pauses));
writeFileSync(join(out, 'focus-panel-opens.jsonl'), jsonl(panels));
writeFileSync(join(out, 'devices.json'), JSON.stringify({ devices: [{ kind: 'phone', snapshot: { spans: phone } }] }));
