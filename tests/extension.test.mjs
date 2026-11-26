import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { observation, sanitizeUrl } from '../extension/capture.js';
import { classifyAnswer, createProtection } from '../extension/protection.js';
import { feedRoute, habitFeeds } from '../extension/habit-feeds.js';
import { installPeekNavigation, unwrapPeekUrl } from '../extension/peek-navigation.js';
const fixtures = JSON.parse(fs.readFileSync(new URL('./url-fixtures.json', import.meta.url)));
for (const f of fixtures) test(`URL policy: ${f.input.split('?')[0]}`, () => assert.equal(sanitizeUrl(f.input), f.output));
const tab = (id, url = 'https://example.org/project', title = 'Project') => ({ id, active: true, url, title, audible: false });
const win = (t = tab(1)) => ({ id: 1, focused: true, type: 'normal', incognito: false, tabs: [t] });
test('habit feed lock is limited to known feed routes on their real hosts', () => {
  assert.equal(feedRoute('https://m.youtube.com/shorts/example?search=test'), 'https://m.youtube.com/shorts/example');
  assert.equal(feedRoute('https://x.com/home'), 'https://x.com/home');
  for (const open of ['https://x.com/messages', 'https://instagram.com/direct/inbox', 'https://reddit.com/message/inbox',
    'https://youtube.com/watch?v=course', 'https://youtube.com/', 'https://notion.so/work', 'https://slack.com',
    'https://www.youtube.com.example.net/shorts/example', 'chrome://settings']) assert.equal(feedRoute(open), '', open);
});
test('a habit feed lock leaves other tabs open and clears on navigation or unlock', async () => {
  const feed = 'https://x.com/home';
  const message = 'https://x.com/messages';
  const otherFeed = 'https://www.tiktok.com/foryou';
  const tabs = new Map([[1, { ...tab(1, feed), windowId: 3 }], [2, { ...tab(2, feed), windowId: 3 }]]);
  const calls = [], reports = [];
  let changeBeforeCover = null;
  let shown = 0;
  const dialog = { setAttribute() {}, style: {}, addEventListener() {}, showModal() { shown++; }, remove() {} };
  const feeds = habitFeeds({
    tabs: { get: async id => tabs.get(id) },
    scripting: { executeScript: async request => {
      const id = request.target.tabId;
      calls.push([id, request.args[0]]);
      const current = { ...tabs.get(id), ...changeBeforeCover };
      const args = request.args;
      const result = await vm.runInNewContext(`(${request.func})(...args)`, {
        args, Date, location: { href: current.url },
        chrome: { runtime: { sendMessage: async ({ id }) =>
          feeds.authorize(id, { frameId: 0, tab: current, url: current.url }) } },
        document: { createElement: () => dialog, documentElement: { append() {} } },
        setInterval: () => 1, clearInterval() {},
        addEventListener() {}, removeEventListener() {},
      });
      return [{ result }];
    } },
  }, id => reports.push(id));
  const settle = () => new Promise(resolve => setImmediate(resolve));
  const request = id => feeds.update({ id, tab_id: 1, window_id: 3, url: feed, until: Date.now() + 20_000 });
  for (const [id, change] of [[4, { url: otherFeed }], [5, { windowId: 6 }]]) {
    changeBeforeCover = change;
    request(id);
    await settle();
    assert.deepEqual(calls.splice(0), [[1, feed]]);
    assert.equal(shown, 0);
    assert.equal(reports.at(-1), null);
    changeBeforeCover = null;
    await feeds.check(1);
    assert.deepEqual(calls, []);
  }
  request(6);
  await settle();
  await feeds.check(2);
  assert.deepEqual(calls.splice(0), [[1, feed]]);
  assert.equal(shown, 1);
  assert.equal(reports.at(-1), 6);
  tabs.set(1, { ...tab(1, message), windowId: 3 });
  feeds.navigate(1, message);
  await settle();
  assert.deepEqual(calls.splice(0), [[1, '']]);
  assert.equal(reports.at(-1), null);
  tabs.set(1, { ...tab(1, feed), windowId: 3 });
  await feeds.check(1);
  assert.deepEqual(calls, []);
  request(7);
  await settle();
  assert.deepEqual(calls.splice(0), [[1, feed]]);
  feeds.update(null);
  await settle();
  assert.deepEqual(calls, [[1, '']]);
});
test('habit cover removal survives a temporary browser failure', { timeout: 3_000 }, async () => {
  const url = 'https://x.com/home';
  for (const failure of ['window move', 'lookup after injection']) {
    let windowId = 3, visible = false, failLookup = false, removals = 0;
    const reports = [];
    let removed;
    const clear = new Promise(resolve => { removed = resolve; });
    const feeds = habitFeeds({
      tabs: { get: async id => {
        if (failLookup) {
          failLookup = false;
          throw new Error('Chromium could not read the tab');
        }
        return { id, url, windowId };
      } },
      scripting: { executeScript: async ({ args }) => {
        if (args[0]) {
          visible = true;
          failLookup = failure === 'lookup after injection';
          return [{ result: true }];
        }
        if (++removals === 1) throw new Error('Chromium could not reach the tab');
        visible = false;
        removed();
        return [{ result: false }];
      } },
    }, id => reports.push(id));

    feeds.update({ id: 1, tab_id: 1, window_id: windowId, url, until: Date.now() + 10_000 });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(visible, true);
    if (failure === 'window move') {
      assert.equal(reports.at(-1), 1);
      windowId = 4;
      feeds.leave(1);
    }
    await clear;
    assert.equal(removals, 2);
    assert.equal(visible, false);
    assert.equal(reports.at(-1), null);
  }
});
test('only the selected foreground tab is described', () => {
  const w = win(); w.tabs.push({ ...tab(2, 'https://background.example/secret'), active: false });
  assert.equal(observation(w, 'active', true).url, 'https://example.org/project');
  w.focused = false; assert.equal(observation(w, 'active', true).url, null);
});
test('incognito/restricted/disabled states contain no page identity', () => {
  for (const w of [{ ...win(), incognito: true }, win({ ...tab(1), incognito: true }), win(tab(1, 'chrome://settings'))]) {
    const s = observation(w, 'active', true); assert.equal(s.url, null); assert.equal(s.title, null);
  }
  assert.equal(observation(win(), 'active', false).title, null);
});
function event() { const listeners = []; return { addListener: (f) => listeners.push(f), fire: (...args) => listeners.map((f) => f(...args)) }; }
async function worker(initialWindow = win(), initialEnabled = true) {
  let current = initialWindow, idle = 'active', enabled = initialEnabled;
  const ports = [], timers = [], stored = [], local = { enabled };
  const injected = [], cleared = [], frames = new Map(), lookups = [], mutes = new Map();
  let resolveHost = () => false;
  const chrome = {
    runtime: { id: 'test', getURL: (path) => 'chrome-extension://test/' + path, lastError: null, onMessage: event(), onInstalled: event(), connectNative() {
      const p = { messages: [], onMessage: event(), onDisconnect: event(), postMessage(m) { this.messages.push(m); } }; ports.push(p); return p;
    } },
    storage: { local: { get: async () => ({ ...local }), set: async (v) => { Object.assign(local, v); chrome.storage.onChanged.fire({ enabled: { newValue: v.enabled } }, 'local'); } }, session: { set: async (x) => stored.push(x) }, onChanged: event() },
    idle: { setDetectionInterval() {}, queryState: async () => idle, onStateChanged: event() },
    windows: { getLastFocused: async () => structuredClone(current), onFocusChanged: event(), onRemoved: event() },
    tabs: {
      onActivated: event(), onUpdated: event(), onRemoved: event(), onReplaced: event(), onDetached: event(), onAttached: event(),
      query: async () => [...frames.keys()].map((id) => ({ id })),
      get: async (id) => ({ mutedInfo: mutes.get(id) }),
      update: async (id, { muted }) => mutes.set(id, { muted, extensionId: 'test' }),
      sendMessage: async (id, msg, target) => cleared.push({ id, msg, target }),
    },
    scripting: { executeScript: async (work) => injected.push(work) },
    webNavigation: { onBeforeNavigate: event(), onErrorOccurred: event(), onCommitted: event(), onHistoryStateUpdated: event(), onReferenceFragmentUpdated: event(), getAllFrames: async ({ tabId }) => frames.get(tabId) },
    action: { setBadgeText() {}, setBadgeBackgroundColor() {}, setTitle() {} }, contextMenus: { update(_id, _options, cb) { cb?.(); }, create() {}, onClicked: event() }, alarms: { create() {}, onAlarm: event() },
  };
  const source = fs.readFileSync(new URL('../extension/background.js', import.meta.url), 'utf8').replace(/^import .*;$/gm, '');
  const fetch = async (url, options) => {
    lookups.push({ url, options });
    const name = url.searchParams.get('name') + '.';
    const type = url.searchParams.get('type') === 'AAAA' ? 28 : 1;
    const blocked = await resolveHost(name, type);
    return { ok: blocked !== null, json: async () => ({
      Status: 0, Question: [{ name, type }],
      Answer: blocked === undefined ? [] : [{ name, type, TTL: 300, data: blocked ? '0.0.0.0' : '192.0.2.1' }],
    }) };
  };
  const telemetry = { start() {}, count() {} };
  vm.runInNewContext(source, { chrome, observation, createProtection, habitFeeds, feedRoute, installPeekNavigation, telemetry, fetch, setInterval: (f) => timers.push(f), setTimeout: (f) => { timers.push(f); return timers.length; }, Date, console });
  const settle = () => new Promise((r) => setImmediate(r));
  await settle();
  return { chrome, ports, timers, stored, settle, injected, cleared, frames, lookups, mutes, setResolver: (f) => resolveHost = f, setWindow: (v) => current = v, setIdle: (v) => idle = v, setEnabled: (v) => { enabled = v; local.enabled = v; chrome.storage.onChanged.fire({ enabled: { newValue: v } }, 'local'); }, get messages() { return ports.at(-1).messages; } };
}
test('SPA and fragment navigations send actual selected URL', async () => {
  const w = await worker();
  w.setWindow(win(tab(1, 'https://example.org/new-route', 'New route')));
  w.chrome.webNavigation.onHistoryStateUpdated.fire({ frameId: 0 }); await w.settle();
  assert.equal(w.messages.at(-1).reason, 'spa'); assert.equal(w.messages.at(-1).url, 'https://example.org/new-route');
  const count = w.messages.length;
  w.chrome.webNavigation.onHistoryStateUpdated.fire({ frameId: 4 }); await w.settle(); assert.equal(w.messages.length, count);
});
test('the browser reports feed lock support only to a compatible helper', async () => {
  const w = await worker();
  assert.equal(w.messages.at(-1).habit_feeds, undefined);
  w.ports.at(-1).onMessage.fire({ type: 'habits-feed-lock', lease: null });
  await w.settle();
  assert.equal(w.messages.at(-1).habit_feeds, true);
  w.setWindow(win(tab(1, 'https://www.reddit.com/r/rust/')));
  w.chrome.webNavigation.onHistoryStateUpdated.fire({ frameId: 0, tabId: 1 });
  await w.settle();
  assert.equal(w.messages.at(-1).habit_feed, false);
  w.setWindow(win(tab(1, 'https://x.com/home')));
  w.chrome.webNavigation.onHistoryStateUpdated.fire({ frameId: 0, tabId: 1 });
  await w.settle();
  assert.equal(w.messages.at(-1).habit_feed, true);
});
test('background title/navigation churn does not create activity events', async () => {
  const w = await worker(); const count = w.messages.length;
  w.chrome.tabs.onUpdated.fire(2, { title: 'Secret background title' }, { ...tab(2), active: false }); await w.settle();
  w.chrome.webNavigation.onCommitted.fire({ frameId: 0, tabId: 2 }); await w.settle();
  assert.equal(w.messages.length, count);
});
test('blur, idle, closed windows and resumed focus report lifecycle', async () => {
  const w = await worker();
  w.setWindow({ ...win(), focused: false }); w.chrome.windows.onFocusChanged.fire(-1); await w.settle();
  assert.equal(w.messages.at(-1).focused, false); assert.equal(w.messages.at(-1).title, null);
  w.setWindow(win()); w.setIdle('idle'); w.chrome.idle.onStateChanged.fire('idle'); await w.settle(); assert.equal(w.messages.at(-1).idle, 'idle');
  w.setWindow(null); w.chrome.windows.onRemoved.fire(1); await w.settle(); assert.equal(w.messages.at(-1).visibility, 'none');
});
test('pause clears details immediately and heartbeat cannot leak them', async () => {
  const w = await worker(); w.setEnabled(false); await w.settle();
  assert.equal(w.messages.at(-1).enabled, false); assert.equal(w.messages.at(-1).url, null); assert.equal(w.messages.at(-1).title, null);
  w.chrome.alarms.onAlarm.fire(); await w.settle(); assert.equal(w.messages.at(-1).url, null);
  w.setEnabled(true); await w.settle(); assert.equal(w.messages.at(-1).url, 'https://example.org/project');
  assert.ok(w.stored.every((s) => !JSON.stringify(s).includes('Project')));
});
test('disconnect reconstructs latest state, and worker restart respects persisted pause', async () => {
  const w = await worker(); w.ports[0].onDisconnect.fire(); w.setWindow(win(tab(3, 'https://example.org/latest')));
  w.timers.at(-1)(); await w.settle(); assert.equal(w.messages.at(-1).url, 'https://example.org/latest');
  assert.ok(w.messages[0].seq > 1);
  const restarted = await worker(win(), false); assert.equal(restarted.messages[0].url, null); assert.equal(restarted.messages[0].enabled, false);
});
test('stale async queries cannot overwrite a newer selected tab', async () => {
  const w = await worker(); let resolveOld;
  w.chrome.windows.getLastFocused = () => new Promise((r) => { resolveOld = r; });
  w.chrome.tabs.onActivated.fire({ tabId: 1, windowId: 1 }); await w.settle();
  w.chrome.windows.getLastFocused = async () => win(tab(2, 'https://example.org/current'));
  w.chrome.tabs.onActivated.fire({ tabId: 2, windowId: 1 }); await w.settle();
  resolveOld(win(tab(1, 'https://example.org/old'))); await w.settle();
  assert.equal(w.messages.at(-1).url, 'https://example.org/current');
});
test('committed extension key matches the native host origin', async () => {
  const { createHash } = await import('node:crypto');
  const manifest = JSON.parse(fs.readFileSync(new URL('../extension/manifest.json', import.meta.url)));
  const id = createHash('sha256').update(Buffer.from(manifest.key, 'base64')).digest('hex').slice(0, 32).replace(/[0-9a-f]/g, (c) => String.fromCharCode(97 + parseInt(c, 16)));
  assert.equal(id, 'mfafgohdpfnknfgedjiicnmgomjpdock');
  assert.equal(manifest.incognito, 'not_allowed');
  assert.equal(manifest.content_scripts, undefined);
  assert.deepEqual(manifest.host_permissions, ['http://*/*', 'https://*/*']);
});

test('native toolbar context menu pauses and resumes without a popup', async () => {
  const w = await worker();
  w.chrome.contextMenus.onClicked.fire({ menuItemId: 'toggle-details' }); await w.settle();
  assert.equal(w.messages.at(-1).enabled, false); assert.equal(w.messages.at(-1).url, null);
  w.chrome.contextMenus.onClicked.fire({ menuItemId: 'toggle-details' }); await w.settle();
  assert.equal(w.messages.at(-1).enabled, true); assert.equal(w.messages.at(-1).url, 'https://example.org/project');
});

test('long Unicode titles remain valid JSON for the native host', () => {
  const state = observation(win(tab(1, 'https://example.org/', 'x' + '😀'.repeat(512))), 'active', true);
  assert.equal(Array.from(state.title).length, 512);
  assert.equal(state.title.isWellFormed(), true);
});

test('same-URL reloads of the active page remain navigation observations', async () => {
  const w = await worker(), count = w.messages.length;
  w.chrome.webNavigation.onCommitted.fire({ frameId: 0, tabId: 1 }); await w.settle();
  assert.equal(w.messages.length, count + 1); assert.equal(w.messages.at(-1).reason, 'navigation');
  assert.equal(w.messages.at(-1).url, 'https://example.org/project');
});

test('HTTP popup tabs are browser usage; DevTools windows remain coarse', () => {
  assert.equal(observation({ ...win(), type: 'popup' }, 'active', true).url, 'https://example.org/project');
  assert.equal(observation({ ...win(), type: 'devtools' }, 'active', true).url, null);
});

test('a title containing a URL cannot retain its URL credentials', () => {
  const state = observation(win(tab(1, 'https://example.org/', 'Project https://user:pass@example.org/?token=secret details')), 'active', true);
  assert.equal(state.title, 'Project https://example.org/?token=REDACTED details');
});
test('Chromium’s provisional title redacts the same URL as the address bar', () => {
  const t = tab(1, 'https://www.example.org/project%20brief?token=synthetic-secret', 'example.org/project brief?token=synthetic-secret');
  assert.equal(observation(win(t), 'active', true).title, 'www.example.org/project%20brief?token=REDACTED');
  assert.equal(observation(win({ ...t, status: 'loading' }), 'active', true).title, null);
  const unusualPort = tab(2, 'http://example.org:443/?token=secret', 'example.org:443/?token=secret');
  assert.equal(observation(win(unusualPort), 'active', true).title, 'example.org:443/?token=REDACTED');
});

const frame = (documentId, url, frameId = 0) => ({ documentId, url, frameId, documentLifecycle: 'active' });
const protection = (w, paused, ended_at = null, profile = "a1b2c3") =>
  w.ports.at(-1).onMessage.fire({ type: 'protection', version: 1, state: { paused_until: paused ? Date.now() + 60_000 : 0, ended_at, profile } });

test('NextDNS evidence distinguishes filtered, block-page, sinkhole, and ordinary answers', () => {
  const host = 'test.example';
  const base = { Status: 0, Question: [{ name: host + '.', type: 1 }] };
  assert.equal(classifyAnswer({ ...base, Answer: [{ type: 1, data: '0.0.0.0', TTL: 300 }] }, host, 1).blocked, true);
  assert.equal(classifyAnswer({ ...base, Answer: [{ type: 5, data: 'blockpage.nextdns.io.' }] }, host, 1).blocked, true);
  assert.equal(classifyAnswer({ ...base, Additional: [{ type: 41, data: '; EDE: 17 (Filtered): (Blocked by NextDNS: category:social-networks)' }] }, host, 1).blocked, true);
  assert.equal(classifyAnswer({ ...base, Status: 3 }, host, 1).blocked, false);
  assert.equal(classifyAnswer({ ...base, Answer: [{ type: 1, data: '192.0.2.1', TTL: 20 }] }, host, 1).ttl, 20);
  assert.equal(classifyAnswer({ ...base, Status: 2 }, host, 1), null);
  assert.equal(classifyAnswer(base, 'different.example', 1), null);
});

test('DNS resume covers all matching documents with one cached hostname lookup, independent of capture', async () => {
  const w = await worker(win(), false);
  w.frames.set(1, [frame('front', 'https://allowed.example.org/')]);
  w.frames.set(2, [
    frame('background', 'https://blocked.example.org/private?token=secret'),
    frame('player', 'https://media.example.org/player', 1),
  ]);
  w.frames.set(3, [frame('another', 'http://blocked.example.org:8080/another')]);
  w.frames.set(4, [frame('local', 'http://box.local/private')]);
  w.frames.set(5, [frame('intranet', 'http://payroll.finance.corp/private')]);
  w.mutes.set(3, { muted: true, reason: 'user' });
  w.setResolver((name) => name === 'blocked.example.org.');
  protection(w, true);
  await w.settle();
  assert.equal(w.injected.length, 0);
  protection(w, false, 1234);
  await w.settle();
  assert.deepEqual(w.lookups.map(({ url }) => url.searchParams.get('name')).sort(), ['allowed.example.org', 'blocked.example.org']);
  assert.ok(w.lookups.every(({ url, options }) =>
    url.origin === 'https://dns.nextdns.io' && url.pathname === '/a1b2c3' &&
    url.searchParams.size === 2 && options.credentials === 'omit' && !url.href.includes('secret')));
  assert.deepEqual(w.injected.map(({ target }) => target.documentIds[0]).sort(), ['another', 'background', 'player']);
  assert.deepEqual(w.injected.find(({ target }) => target.documentIds[0] === 'background').args, [true, 1234]);
  assert.deepEqual(w.injected.find(({ target }) => target.documentIds[0] === 'player').args, [false, 1234]);
  assert.equal(w.mutes.get(2).muted, true);
  assert.equal(w.mutes.has(1), false);
  w.frames.set(2, [frame('reopened', 'https://blocked.example.org/')]);
  w.chrome.webNavigation.onCommitted.fire({ ...w.frames.get(2)[0], tabId: 2 });
  await w.settle();
  assert.deepEqual(w.injected.at(-1).target.documentIds, ['reopened']);
  assert.equal(w.lookups.length, 2);
  w.cleared.length = 0;
  protection(w, true);
  await w.settle();
  assert.ok(w.cleared.some(({ id, target }) => id === 2 && target.documentId === 'reopened'));
  assert.equal(w.mutes.get(2).muted, false);
  assert.deepEqual(w.mutes.get(3), { muted: true, reason: 'user' });
});

test('scoped NextDNS changes release and cover the same tab without opening unrelated sites', async () => {
  const w = await worker();
  w.frames.set(1, [frame('discord', 'https://discord.com/channels/private')]);
  w.frames.set(2, [frame('other', 'https://x.com/home')]);
  w.frames.set(3, [frame('another-service', 'https://telegram.org/')]);
  const publish = (scope_revision) => w.ports.at(-1).onMessage.fire({
    type: 'protection', version: 1, state: { paused_until: 0, ended_at: null, scope_revision, profile: "a1b2c3" },
  });
  w.setResolver(() => true);
  publish(0);
  await w.settle();
  assert.equal(w.mutes.get(1).muted, true);
  assert.equal(w.mutes.get(2).muted, true);

  w.setResolver((host) => host === 'x.com.');
  publish(1);
  await w.settle();
  assert.ok(w.cleared.some(({ id, target }) => id === 1 && target.documentId === 'discord'));
  assert.equal(w.mutes.get(1).muted, false);
  assert.equal(w.mutes.get(2).muted, true);

  assert.equal(w.mutes.get(3).muted, false);

  w.setResolver((host) => host !== 'telegram.org.');
  publish(2);
  await w.settle();
  assert.equal(w.mutes.get(1).muted, true);
  assert.equal(w.mutes.get(2).muted, true);
  assert.equal(w.mutes.get(3).muted, false);

  w.setResolver(() => true);
  publish(3);
  await w.settle();
  assert.equal(w.mutes.get(3).muted, true);
});

test('DNS lookups require a valid profile and discard cached results when it changes', async () => {
  const w = await worker();
  w.frames.set(1, [frame('active', 'https://blocked.example.org/')]);
  w.setResolver(() => true);
  protection(w, false, null, 'abc123/extra');
  await w.settle();
  assert.equal(w.lookups.length, 0);
  protection(w, false, null, 'A1B2C3');
  await w.settle();
  assert.equal(w.mutes.get(1).muted, true);
  protection(w, false, null, null);
  await w.settle();
  assert.equal(w.lookups.length, 1);
  assert.equal(w.mutes.get(1).muted, true);
  w.setResolver(() => false);
  protection(w, false, null, 'd4e5f6');
  await w.settle();
  assert.deepEqual(w.lookups.map(({ url }) => url.pathname), ['/a1b2c3', '/d4e5f6']);
  assert.equal(w.mutes.get(1).muted, false);
});

test('a slow block result cannot cover a newly paused or different document', async () => {
  const w = await worker();
  let resolve;
  w.setResolver(() => new Promise((r) => { resolve = r; }));
  w.frames.set(2, [frame('first', 'https://blocked.example.org/')]);
  protection(w, false);
  await w.settle();
  w.frames.set(2, [frame('new', 'https://allowed.example.org/')]);
  resolve(true);
  await w.settle();
  assert.deepEqual(w.injected, []);
  w.frames.set(2, [frame('temporary', 'https://other.example.org/')]);
  w.chrome.webNavigation.onCommitted.fire({ ...w.frames.get(2)[0], tabId: 2 });
  await w.settle();
  protection(w, true);
  resolve(true);
  await w.settle();
  assert.deepEqual(w.injected, []);
});

test('an unsuccessful AAAA fallback preserves the active cover and is retried', async (t) => {
  t.mock.timers.enable({ apis: ['Date'] });
  const w = await worker();
  w.frames.set(1, [frame('protected', 'https://blocked.example.org/')]);
  w.setResolver(() => true);
  protection(w, false, 1234);
  await w.settle();
  assert.equal(w.injected.length, 1);
  assert.equal(w.mutes.get(1).muted, true);

  t.mock.timers.tick(61_000);
  w.setResolver((_name, type) => {
    if (type === 28) throw new Error('synthetic DNS failure');
  });
  w.chrome.tabs.onUpdated.fire(1, { status: 'complete' }, { active: false });
  await w.settle();
  assert.equal(w.cleared.length, 0);
  assert.equal(w.mutes.get(1).muted, true);

  t.mock.timers.tick(6000);
  w.setResolver(() => false);
  w.chrome.alarms.onAlarm.fire();
  await w.settle();
  assert.deepEqual(w.lookups.map(({url}) => url.searchParams.get('type')), ['A', 'A', 'AAAA', 'A']);
  assert.equal(w.cleared.length, 1);
  assert.equal(w.mutes.get(1).muted, false);
});

test('Peek unwraps a Google and Facebook redirect locally and strips tracking', () => {
  const dest = 'https://www.facebook.com/store/posts/menu/1234567890123456/?tracking=secret#photo';
  const redirect = 'https://l.facebook.com/l.php?u=' + encodeURIComponent(dest);
  const google = 'https://www.google.com/url?ved=search-tracker&url=' + encodeURIComponent(redirect);
  assert.equal(unwrapPeekUrl(google)?.href, 'https://www.facebook.com/store/posts/menu/1234567890123456/');
  assert.equal(unwrapPeekUrl('https://t.co/Abc12?tracking=123')?.href, 'https://t.co/Abc12');
  assert.equal(unwrapPeekUrl('https://www.google.com.example/url?url=' + encodeURIComponent(dest)), null);
  assert.equal(unwrapPeekUrl('https://www.google.com/url?url=http://127.0.0.1/'), null);
  assert.equal(unwrapPeekUrl('https://username:pass@x.com/user/status/1'), null);
});

test('Peek yields to a habit feed lock that arrives during a blocked-host check', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const feed = 'https://x.com/home';
  const permalink = 'https://x.com/author/status/123';
  let tabUrl = feed;
  const updates = [], covers = [], reports = [], lookups = [];
  const chrome = {
    runtime: { id: 'test', getURL: (path) => 'chrome-extension://test/' + path, onMessage: event() },
    tabs: {
      onActivated: event(), onRemoved: event(),
      get: async (id) => ({ ...tab(id, tabUrl), windowId: 1 }),
      update: async (_id, { url }) => updates.push(url),
    },
    scripting: { executeScript: async ({ args }) => { covers.push(args[0]); return [{ result: true }]; } },
    windows: { get: async () => ({ focused: true }) },
    webNavigation: { onBeforeNavigate: event(), onCommitted: event(), onErrorOccurred: event() },
  };
  const feeds = habitFeeds(chrome, (id) => reports.push(id));
  installPeekNavigation(chrome, 'test', () => new Promise((resolve) => lookups.push(resolve)), feeds.claims);
  const tick = () => new Promise((resolve) => setImmediate(resolve));
  const navigate = (url) => chrome.webNavigation.onBeforeNavigate.fire({ tabId: 1, frameId: 0, url });

  navigate(feed);
  await tick();
  assert.equal(lookups.length, 1);
  feeds.update({ id: 7, tab_id: 1, window_id: 1, url: feed, until: Date.now() + 20_000 });
  lookups[0](true);
  await tick();
  assert.deepEqual(updates, []);
  assert.deepEqual(covers, [feed]);
  assert.equal(reports.at(-1), 7);

  navigate(feed);
  await tick();
  assert.equal(lookups.length, 1);

  tabUrl = permalink;
  navigate(permalink);
  feeds.navigate(1, permalink);
  await tick();
  assert.equal(lookups.length, 2);
  lookups[1](true);
  await tick();
  assert.match(updates[0], /^chrome-extension:\/\/test\/peek\.html\?id=/);
  assert.deepEqual(covers, [feed, '']);
  assert.equal(reports.at(-1), null);
});

test('Peek checks the final blocked URL while the shortener lookup is pending', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const short = 'https://t.co/Abc12';
  const permalink = 'https://x.com/author/status/123';
  const ports = [], updates = [];
  let tabUrl = short, pendingUrl;
  const chrome = {
    runtime: {
      id: 'test', getURL: (path) => 'chrome-extension://test/' + path, onMessage: event(),
      connectNative() {
        const port = {
          onMessage: event(), onDisconnect: event(),
          postMessage(message) { this.request = message; },
          disconnect() {},
        };
        ports.push(port);
        return port;
      },
    },
    tabs: {
      onActivated: event(), onRemoved: event(),
      get: async () => ({ active: true, windowId: 1, url: tabUrl, pendingUrl }),
      update: async (tabId, details) => updates.push({ tabId, ...details }),
    },
    windows: { get: async () => ({ focused: true }) },
    webNavigation: { onBeforeNavigate: event(), onCommitted: event(), onErrorOccurred: event() },
  };

  installPeekNavigation(chrome, 'test');
  const events = chrome.webNavigation;
  const tick = () => new Promise((resolve) => setImmediate(resolve));
  const reply = (port, result) => port.onMessage.fire({ type: 'peek_reply', version: 1, result });
  const info = { frameId: 0, tabId: 1 };
  events.onBeforeNavigate.fire({ ...info, url: short });
  await tick();
  assert.equal(ports[0].request.type, 'peek_follow');

  tabUrl = permalink;
  events.onErrorOccurred.fire({ ...info, url: permalink, error: 'net::ERR_NAME_NOT_RESOLVED' });
  await tick();
  assert.equal(ports[1].request.host, 'x.com');
  reply(ports[0], { url: permalink });
  reply(ports[1], { blocked: true });
  await tick();
  assert.equal(updates.length, 1);
  assert.match(updates[0].url, /^chrome-extension:\/\/test\/peek\.html\?id=/);

  // If the worker restarted, the current Peek document can still open the
  // normal pause controls. The old ticket cannot fetch a post again.
  tabUrl = updates[0].url;
  chrome.tabs.onRemoved.fire(1);
  const sender = { id: 'test', frameId: 0, tab: { id: 1 }, url: tabUrl };
  assert.deepEqual(chrome.runtime.onMessage.fire({ type: 'peek_load' }, sender, () => {}), [false]);
  const openPause = { type: 'peek_open_pause' };
  const webSender = { ...sender, url: 'https://example.com/' };
  assert.deepEqual(chrome.runtime.onMessage.fire(openPause, webSender, () => {}), [false]);
  pendingUrl = webSender.url;
  const stale = await new Promise((resolve) => chrome.runtime.onMessage.fire(openPause, sender, resolve));
  assert.equal(stale, null);
  assert.equal(ports.length, 2);

  pendingUrl = undefined;
  const paused = new Promise((resolve) => {
    assert.deepEqual(chrome.runtime.onMessage.fire(openPause, sender, resolve), [true]);
  });
  await tick();
  assert.equal(ports[2].request.type, 'peek_pause');
  reply(ports[2], { opened: true });
  assert.deepEqual(await paused, { opened: true });
});

test('emergency shield blocks entertainment hosts locally even while DNS is paused', async () => {
  const w = await worker();
  w.frames.set(1, [frame('work', 'https://acme-portal.dev/dashboard')]);
  w.frames.set(2, [frame('social', 'https://x.com/home')]);
  w.frames.set(3, [frame('video', 'https://www.youtube.com/watch?v=abc')]);
  w.ports.at(-1).onMessage.fire({
    type: 'protection',
    version: 1,
    state: { paused_until: Date.now() + 600_000, ended_at: null, profile: 'a1b2c3', emergency_shield: true },
  });
  await w.settle();
  const covered = w.injected.map(({ target }) => target.documentIds[0]).sort();
  assert.deepEqual(covered, ['social', 'video']);
  assert.equal(w.mutes.get(2)?.muted, true);
  assert.equal(w.mutes.get(3)?.muted, true);
  assert.equal(w.mutes.has(1), false);
});
