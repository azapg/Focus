import { sanitizeUrl } from './capture.js';

// Only explicit feed routes are covered. A host by itself is never enough to block it.
export function feedRoute(raw) {
  let url;
  try { url = new URL(raw); } catch { return ''; }
  if (!['http:', 'https:'].includes(url.protocol)) return '';
  const host = url.hostname.replace(/^(www|m|mobile)\./, '');
  const path = url.pathname.replace(/\/$/, '');
  const isFeed =
    (host === 'youtube.com' && /^\/(shorts(?:\/[^/]+)?|feed\/(subscriptions|trending|explore))$/.test(path)) ||
    ((host === 'x.com' || host === 'twitter.com') && path === '/home') ||
    (host === 'instagram.com' && /^\/(reels|reel\/[^/]+)$/.test(path)) ||
    (host === 'reddit.com' && ['/popular', '/all', '/r/all', '/r/popular'].includes(path)) ||
    (host === 'tiktok.com' && path === '/foryou');
  return isFeed ? url.origin + url.pathname : '';
}

// Runs in the extension's isolated world in the top frame.
async function cover(url, until, id) {
  const key = '__focusHabitFeed';
  if (!url) {
    globalThis[key]?.();
    return false;
  }
  if (until <= Date.now() || location.href !== url) return false;
  // Chromium supplies the originating tab and window when the document asks for permission.
  if (!await chrome.runtime.sendMessage({ type: 'habit-feed-cover', id })
      || until <= Date.now() || location.href !== url) return false;
  globalThis[key]?.();
  const dialog = document.createElement('dialog');
  dialog.setAttribute('aria-label', 'Feeds locked until a habit is done');
  dialog.style.cssText = 'border:1px solid rgba(239,68,68,0.55);background:#060607;color:#f87171;padding:20px;border-radius:12px;font:14px system-ui';
  dialog.textContent = 'Feeds locked until a habit is done';
  dialog.addEventListener('cancel', event => event.preventDefault());
  document.documentElement.append(dialog);
  dialog.showModal();
  function remove() {
    clearInterval(timer);
    globalThis.removeEventListener('pagehide', remove);
    dialog.remove();
    delete globalThis[key];
  }
  const timer = setInterval(() => {
    if (Date.now() >= until || location.href !== url) remove();
  }, 250);
  globalThis.addEventListener('pagehide', remove, { once: true });
  globalThis[key] = remove;
  return true;
}

export function habitFeeds(api, acknowledged) {
  let lease = null;
  let revoked = null;
  const covered = new Map();
  const pending = new Map();
  const current = candidate => candidate && candidate.id === lease?.id && candidate.id !== revoked
    && Date.now() < candidate.until;
  function report() {
    const cover = lease && covered.get(lease.tab_id);
    acknowledged(current(lease) && cover?.confirmed && cover.id === lease.id ? lease.id : null);
  }
  function matchesTab(candidate, tab, url = tab?.url) {
    return current(candidate) && tab && !tab.incognito && tab.id === candidate.tab_id
      && tab.windowId === candidate.window_id && feedRoute(url) && sanitizeUrl(url) === candidate.url;
  }
  function claims(tab, url) {
    return Boolean(matchesTab(lease, tab, url));
  }
  function authorize(id, sender) {
    return id === lease?.id && sender.frameId === 0 && claims(sender.tab, sender.url);
  }
  async function clear(id) {
    const record = covered.get(id);
    if (!record) return;
    record.confirmed = false;
    try {
      await api.scripting.executeScript({ target: { tabId: id }, func: cover, args: ['', 0, null] });
      covered.delete(id);
    } catch {
      // Keep the removal pending if Chromium is still moving or navigating the tab.
      if (Date.now() >= record.until) covered.delete(id);
      else if (!record.retrying) {
        record.retrying = true;
        setTimeout(() => {
          record.retrying = false;
          if (covered.get(id) === record) void check(id);
        }, 250);
      }
    }
  }
  async function apply(id) {
    if (id !== lease?.tab_id && !covered.has(id)) return;
    const candidate = lease;
    try {
      const tab = await api.tabs.get(id);
      if (matchesTab(candidate, tab)) {
        // Injection may show the dialog even if its response or the later tab lookup fails.
        const previous = covered.get(id);
        const record = { id: candidate.id, until: candidate.until, confirmed: false };
        covered.set(id, record);
        const [result] = await api.scripting.executeScript({
          target: { tabId: id }, func: cover, args: [tab.url, candidate.until, candidate.id],
        });
        if (result?.result === true && matchesTab(candidate, await api.tabs.get(id))) {
          record.confirmed = true;
          report();
          return;
        }
        if (result?.result === false && !previous && covered.get(id) === record) covered.delete(id);
      }
    } catch {
      // The tab can close or finish navigating during the request.
    }
    if (candidate && id === candidate.tab_id && candidate.id === lease?.id) revoked = candidate.id;
    report();
    await clear(id);
  }
  function check(id) {
    if (!Number.isInteger(id) || id < 0) return;
    const work = (pending.get(id) ?? Promise.resolve()).then(() => apply(id));
    pending.set(id, work);
    return work.finally(() => { if (pending.get(id) === work) pending.delete(id); });
  }
  function update(next) {
    const previous = lease;
    const now = Date.now();
    lease = next && Number.isSafeInteger(next.id) && Number.isSafeInteger(next.until)
      && next.until > now && next.until <= now + 40_000 && feedRoute(next.url) ? next : null;
    if (previous && previous.tab_id !== lease?.tab_id) void check(previous.tab_id);
    if (lease) void check(lease.tab_id);
    report();
  }
  function leave(id) {
    if (lease && lease.tab_id === id) revoked = lease.id;
    report();
    void check(id);
  }
  function navigate(id, url) {
    if (lease && lease.tab_id === id && sanitizeUrl(url) !== lease.url) leave(id);
    else void check(id);
  }
  function forget(id) {
    covered.delete(id);
    leave(id);
  }
  return { authorize, claims, check, update, leave, navigate, forget };
}
