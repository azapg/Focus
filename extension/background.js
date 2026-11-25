import { observation } from './capture.js';
import { createProtection } from './protection.js';
import { feedRoute, habitFeeds } from './habit-feeds.js';
import { installPeekNavigation } from './peek-navigation.js';
import * as telemetry from './telemetry.js';

telemetry.start();
const HOST = 'org.nightingalelab.focusdns';
const protection = createProtection(chrome, (...args) => fetch(...args));
let feedCovered = null;
const feeds = habitFeeds(chrome, id => {
  if (feedCovered !== id) {
    feedCovered = id;
    if (feedHost) refresh('heartbeat');
  }
});
installPeekNavigation(chrome, HOST, protection.blockedHost, feeds.claims);
let enabled = true, port = null, seq = 0, generation = 0, last = '', reconnect = null, backoff = 2000;
let feedHost = false;
let status = { connected: false, app_online: false, received_at: null, enabled: true };
const ready = chrome.storage.local.get({ enabled: true }).then((s) => { enabled = s.enabled !== false; });
chrome.idle.setDetectionInterval(120);
chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({ id: 'toggle-details', title: 'Pause browser details', contexts: ['action'] }, () => {
    void chrome.runtime.lastError; ready.then(publishStatus);
  });
});
chrome.contextMenus.onClicked.addListener((info) => {
  if (info.menuItemId === 'toggle-details') ready.then(() => chrome.storage.local.set({ enabled: !enabled }));
});

function publishStatus() {
  status.enabled = enabled;
  // Connection diagnostics only; never retain page URLs/titles in extension storage.
  chrome.storage.session.set({ status }).catch(() => {});
  chrome.action.setBadgeText({ text: !enabled ? 'OFF' : '' });
  chrome.action.setTitle({ title: enabled ? 'Focus' : 'Focus — browser details paused' });
  chrome.contextMenus.update('toggle-details', { title: enabled ? 'Pause browser details' : 'Resume browser details' }, () => { void chrome.runtime.lastError; });
  chrome.action.setBadgeBackgroundColor({ color: '#262626' });
}
function connect() {
  if (port || reconnect) return;
  try {
    port = chrome.runtime.connectNative(HOST);
    const p = port;
    p.onMessage.addListener((ack) => {
      telemetry.count('messagesIn');
      if (p !== port) return;
      if (ack.type === 'protection') {
        protection.update(ack);
        return;
      }
      if (ack.type === 'habits-feed-lock') {
        feeds.update(ack.lease);
        if (!feedHost) {
          feedHost = true;
          refresh('heartbeat');
        }
        return;
      }
      if (ack.ok !== true || !Number.isSafeInteger(ack.seq)) return;
      status = { connected: true, app_online: ack.app_online === true, received_at: ack.received_at, enabled };
      backoff = 2000; publishStatus();
    });
    p.onDisconnect.addListener(() => {
      void chrome.runtime.lastError; // Do not log potentially sensitive error payloads.
      if (p !== port) return;
      port = null; last = ''; status.connected = false; status.app_online = false; publishStatus();
      feedHost = false;
      feeds.update(null);
      reconnect = setTimeout(() => { reconnect = null; connect(); refresh('startup'); }, backoff);
      backoff = Math.min(backoff * 2, 30000);
    });
  } catch {
    port = null;
    status.connected = false; publishStatus();
  }
}
async function refresh(reason, navigationTabId = null) {
  telemetry.count('refreshes');
  const ticket = ++generation;
  await ready;
  let win = null;
  const [windowResult, idleResult] = await Promise.allSettled([
    chrome.windows.getLastFocused({ populate: true }), chrome.idle.queryState(120),
  ]);
  if (ticket !== generation) return;
  if (windowResult.status === 'fulfilled') win = windowResult.value;
  const idle = idleResult.status === 'fulfilled' ? idleResult.value : 'locked';
  const state = observation(win, idle, enabled);
  const activeTab = state.visibility === 'page' ? win.tabs?.find(tab => tab.id === state.tab_id) : null;
  const coverable = Boolean(activeTab && feedRoute(activeTab.url));
  const capabilities = feedHost ? { habit_feeds: true, habit_feed: coverable,
    ...(feedCovered === null ? {} : { habit_feed_lock: feedCovered }) } : {};
  const fingerprint = JSON.stringify({ ...state, ...capabilities });
  connect();
  const foregroundNavigation = navigationTabId !== null && navigationTabId === state.tab_id;
  if (!port || (fingerprint === last && reason !== 'heartbeat' && reason !== 'startup' && !foregroundNavigation)) return;
  try {
    port.postMessage({ version: 1, seq: ++seq, at: Date.now(), reason, ...state, ...capabilities });
    telemetry.count('messagesOut');
    last = fingerprint;
  } catch {
    // Disconnect handler reconnects. Never replay old page state after reconnect.
    last = '';
  }
}
chrome.tabs.onActivated.addListener(({ tabId }) => { void feeds.check(tabId); refresh('tab'); });
chrome.tabs.onUpdated.addListener((id, change, tab) => {
  if (change.status === 'complete' || change.mutedInfo?.muted === false) void protection.check(id);
  if (change.url !== undefined) feeds.navigate(id, change.url);
  else if (change.status === 'complete') void feeds.check(id);
  if (tab.active && (change.url !== undefined || change.title !== undefined || change.audible !== undefined || change.status === 'complete')) refresh(change.url !== undefined ? 'navigation' : 'title');
});
chrome.tabs.onRemoved.addListener((id) => { protection.forget(id); feeds.forget(id); refresh('closed'); });
chrome.tabs.onDetached.addListener(id => { feeds.leave(id); refresh('tab'); });
chrome.tabs.onAttached.addListener(id => { feeds.leave(id); refresh('tab'); });
chrome.tabs.onReplaced.addListener((id, removed) => {
  protection.forget(removed);
  feeds.forget(removed);
  void protection.check(id);
  void feeds.check(id);
  refresh('tab');
});
chrome.windows.onFocusChanged.addListener(() => refresh('focus'));
chrome.windows.onRemoved.addListener(() => refresh('closed'));
chrome.idle.onStateChanged.addListener(() => refresh('idle'));
for (const [event, reason] of [[chrome.webNavigation.onCommitted, 'navigation'], [chrome.webNavigation.onHistoryStateUpdated, 'spa'], [chrome.webNavigation.onReferenceFragmentUpdated, 'fragment']]) {
  event.addListener((details) => {
    if (event === chrome.webNavigation.onCommitted && details.documentLifecycle === 'active') {
      void protection.check(details.tabId);
    }
    if (details.frameId === 0) {
      feeds.navigate(details.tabId, details.url);
      refresh(reason, details.tabId ?? null);
    }
  });
}
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && changes.enabled) {
    enabled = changes.enabled.newValue !== false; publishStatus(); refresh('setting');
  }
});
chrome.runtime.onMessage.addListener((msg, sender, respond) => {
  if (sender.id !== chrome.runtime.id) return false;
  if (msg?.type === 'habit-feed-cover') {
    respond(Boolean(feeds.authorize(msg.id, sender)));
    return false;
  }
  if (msg?.type !== 'status') return false;
  telemetry.count('messagesIn');
  refresh('heartbeat').then(() => { respond(status); telemetry.count('messagesOut'); }); return true;
});
function heartbeat() {
  refresh('heartbeat');
  protection.heartbeat();
}
chrome.alarms.onAlarm.addListener(heartbeat);
chrome.alarms.create('recovery', { periodInMinutes: 0.5 });
// A connected native port keeps MV3 alive. Alarm/startup reconstruct state if it restarts.
setInterval(heartbeat, 5000);
publishStatus(); refresh('startup');
