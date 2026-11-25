import test from 'node:test';
import assert from 'node:assert/strict';

const mem = new Map();
globalThis.localStorage = { getItem: (k) => mem.get(k) ?? null, setItem: (k, v) => mem.set(k, String(v)) };
const { default: FocusLimits } = await import('../ui/limits.js');

test('defaults: 1h limit, 15s wait, 30m max pause', () => {
  mem.clear();
  assert.deepEqual({ ...FocusLimits.get(), pending: undefined }, { dnsLimitMin: 60, waitSeconds: 15, maxPauseMin: 30, pending: undefined });
});

test('stricter now, looser tomorrow', () => {
  mem.clear();
  const now = new Date(2026, 10, 22, 10, 0).getTime();
  assert.equal(FocusLimits.set('dnsLimitMin', 45, now).dnsLimitMin, 45, 'tighter applies now');
  const l = FocusLimits.set('dnsLimitMin', 90, now);
  assert.equal(l.dnsLimitMin, 45, 'looser waits');
  assert.equal(l.pending.dnsLimitMin.value, 90);
  const nextDay = new Date(2026, 10, 23, 0, 1).getTime();
  assert.equal(FocusLimits.get(nextDay).dnsLimitMin, 90, 'applies the next day');
  assert.equal(FocusLimits.set('waitSeconds', 30, now).waitSeconds, 30, 'longer wait is stricter');
  assert.equal(FocusLimits.set('waitSeconds', 10, now).waitSeconds, 30);
  FocusLimits.undo('waitSeconds');
  assert.equal(FocusLimits.get(now).pending.waitSeconds, undefined);
});

test('pause choices respect max pause and what is left today; 1m always allowed', () => {
  mem.clear();
  const ok = (offMin) => FocusLimits.pauseChoices(offMin * 60000).filter((c) => c.ok).map((c) => c.m);
  assert.deepEqual(ok(0), [1, 5, 10, 30]);
  assert.deepEqual(ok(47), [1, 5, 10]);
  assert.deepEqual(ok(70), [1]);
  FocusLimits.set('maxPauseMin', 10);
  assert.deepEqual(FocusLimits.pauseChoices(0).map((c) => c.m), [1, 5, 10]);
});

test('the wait starts with the 3rd pause of the day', () => {
  mem.clear();
  assert.equal(FocusLimits.waitFor(0), 0);
  assert.equal(FocusLimits.waitFor(1), 0);
  assert.equal(FocusLimits.waitFor(2), 15);
});

test('earned credit extends choices without changing the base ratchet', () => {
  const now = new Date(2026, 10, 24, 12).getTime();
  const { dnsLimitMin } = FocusLimits.get(now);
  const usage = (dnsLimitMin - 2) * 60_000;
  assert.equal(FocusLimits.pauseChoices(usage, now).find(c => c.m === 10).ok, false);
  assert.equal(FocusLimits.pauseChoices(usage, now, 10).find(c => c.m === 10).ok, true);
  assert.equal(FocusLimits.get(now).dnsLimitMin, dnsLimitMin);
});
