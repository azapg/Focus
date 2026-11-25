// Only the foreground top-level navigation is eligible. The native host owns
// the permalink allowlist and checks the active NextDNS profile again on fetch.
const socialHosts = new Set([
  'x.com', 'www.x.com', 'mobile.x.com', 'twitter.com', 'www.twitter.com', 'mobile.twitter.com',
  'facebook.com', 'www.facebook.com', 'm.facebook.com', 'l.facebook.com', 'lm.facebook.com',
  't.co', 'fb.me', 'instagram.com', 'www.instagram.com', 'threads.com', 'www.threads.com',
  'threads.net', 'www.threads.net', 'reddit.com', 'www.reddit.com', 'old.reddit.com',
  'redd.it', 'tiktok.com', 'www.tiktok.com',
]);
const shortHosts = new Set(['t.co', 'fb.me']);
const googleHosts = new Set([
  'google.com', 'google.cl', 'google.com.pa', 'google.co.uk', 'google.com.au', 'google.ca',
  'google.co.in', 'google.de', 'google.fr', 'google.es', 'google.com.mx', 'google.com.br',
]);

export function unwrapPeekUrl(raw) {
  for (let depth = 0; depth <= 4; depth++) {
    if (typeof raw !== 'string' || raw.length > 4096 || /[\u0000-\u001f\u007f\\]/.test(raw)) return null;
    let url;
    try { url = new URL(raw); } catch { return null; }
    if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password || url.port) return null;
    if (url.pathname === '/url' && googleHosts.has(url.hostname.replace(/^www\./, ''))) {
      raw = url.searchParams.get('url') || url.searchParams.get('q');
    } else if (url.pathname === '/l.php' && ['l.facebook.com', 'lm.facebook.com'].includes(url.hostname)) {
      raw = url.searchParams.get('u');
    } else if (socialHosts.has(url.hostname)) {
      url.hash = '';
      url.search = '';
      return url;
    } else {
      return null;
    }
  }
  return null;
}

function requestNative(chrome, host, request, timeout) {
  return new Promise((resolve, reject) => {
    let port;
    try { port = chrome.runtime.connectNative(host); } catch (error) { reject(error); return; }
    const end = (result) => {
      clearTimeout(timer);
      resolve(result);
      port.disconnect();
    };
    const timer = setTimeout(() => end(null), timeout);
    port.onDisconnect.addListener(() => {
      void chrome.runtime.lastError;
      clearTimeout(timer);
      resolve(null);
    });
    port.onMessage.addListener((message) => {
      if (message?.type === 'peek_reply' && message.version === 1) end(message.result);
    });
    try { port.postMessage({ version: 1, ...request }); } catch { end(null); }
  });
}

export function installPeekNavigation(chrome, host, blockedHost = async () => null, feedClaims = () => false) {
  const page = chrome.runtime.getURL('peek.html');
  // A new query string loads a new document even when a browser navigation
  // fails before leaving an existing Peek. A fragment change reuses its DOM.
  const tokenPrefix = page + '?id=';
  const pending = new Map();
  const jobs = new Map();
  const failedTabs = new Set();
  let checksInFlight = 0;

  function forget(tabId) {
    jobs.delete(tabId);
    pending.delete(tabId);
    failedTabs.delete(tabId);
  }

  async function consider(details) {
    if (details.frameId !== 0 || details.error === 'net::ERR_ABORTED'
        || (details.documentLifecycle && details.documentLifecycle !== 'active')) return;
    const url = unwrapPeekUrl(details.url);
    if (!url || checksInFlight >= 4 || jobs.get(details.tabId)?.url === url.href) return;
    if (shortHosts.has(url.hostname) && !/^\/[a-z0-9_-]{1,32}$/i.test(url.pathname)) return;
    const { tabId } = details;
    const ticket = { url: url.href };
    jobs.set(tabId, ticket);
    const currentTab = async () => {
      const tab = await chrome.tabs.get(tabId);
      const window = tab.active && !tab.incognito && await chrome.windows.get(tab.windowId);
      const target = tab.pendingUrl ?? tab.url;
      return window?.focused && jobs.get(tabId) === ticket
        && (target === details.url || unwrapPeekUrl(target)?.href === url.href) ? tab : null;
    };
    const claimed = (tab) => feedClaims(tab, tab.pendingUrl ?? tab.url);
    try {
      const initial = await currentTab();
      if (!initial || claimed(initial) || checksInFlight >= 4) return;
      checksInFlight++;
      const short = shortHosts.has(url.hostname);
      const request = short
        ? { type: 'peek_follow', url: url.href }
        : { type: 'peek_check', host: url.hostname };
      let result;
      try {
        const blocked = short ? null : await blockedHost(url.hostname);
        result = blocked === null ? await requestNative(chrome, host, request, 8500) : { blocked };
      } finally {
        checksInFlight--;
      }
      const destination = short ? result?.url : result?.blocked && url.href;
      if (!destination) return;
      const latest = await currentTab();
      // A feed lock can arrive while the blocked-host check is in flight.
      if (!latest || jobs.get(tabId) !== ticket || claimed(latest) || pending.has(tabId)) return;
      const token = crypto.randomUUID();
      const entry = { token, url: destination, candidate: url.href, expires: Date.now() + 15_000 };
      pending.set(tabId, entry);
      setTimeout(() => {
        if (pending.get(tabId) === entry && !entry.started) pending.delete(tabId);
      }, 15_000);
      await chrome.tabs.update(tabId, { url: tokenPrefix + token });
    } catch {
      // Navigation, window focus or the native host can change during the check.
    } finally {
      if (jobs.get(tabId) === ticket) jobs.delete(tabId);
    }
  }

  chrome.webNavigation.onBeforeNavigate.addListener((details) => {
    if (details.frameId !== 0) return;
    jobs.delete(details.tabId);
    failedTabs.delete(details.tabId);
    const entry = pending.get(details.tabId);
    if (entry && details.url !== page && details.url !== tokenPrefix + entry.token
        && (!entry.candidate || unwrapPeekUrl(details.url)?.href !== entry.candidate)) {
      pending.delete(details.tabId);
    }
    void consider(details);
  });
  chrome.webNavigation.onCommitted.addListener(consider);
  chrome.webNavigation.onErrorOccurred.addListener((details) => {
    if (details.frameId === 0 && details.error !== 'net::ERR_ABORTED' && unwrapPeekUrl(details.url)) {
      failedTabs.add(details.tabId);
      void consider(details);
    }
  });
  chrome.tabs.onActivated.addListener(async ({ tabId }) => {
    if (!failedTabs.has(tabId)) return;
    try {
      const tab = await chrome.tabs.get(tabId);
      if (failedTabs.has(tabId)) await consider({ tabId, frameId: 0, url: tab.url });
    } catch {
      // The tab can close before activation is handled.
    }
  });
  chrome.tabs.onRemoved.addListener(forget);

  chrome.runtime.onMessage.addListener((msg, sender, respond) => {
    if (sender.id !== chrome.runtime.id || sender.frameId !== 0
        || (sender.documentLifecycle && sender.documentLifecycle !== 'active')) return false;
    const tabId = sender.tab?.id;
    if (!Number.isInteger(tabId)) return false;
    if (msg?.type === 'peek_open_pause' && (sender.url === page || sender.url?.startsWith(tokenPrefix))) {
      // Opening the ordinary pause controls only needs the current Peek
      // document. Its one-time post-fetch ticket can expire or be lost.
      chrome.tabs.get(tabId).then((tab) => (tab.pendingUrl ?? tab.url) === sender.url
        ? requestNative(chrome, host, { type: 'peek_pause' }, 8500)
        : null).then(respond, () => respond({ opened: false }));
      return true;
    }
    const entry = pending.get(tabId);
    if (!entry || sender.url !== tokenPrefix + entry.token) return false;
    if (msg?.type === 'peek_load' && !entry.started && Date.now() <= entry.expires) {
      entry.started = true;
      const url = entry.url;
      delete entry.url;
      delete entry.candidate;
      requestNative(chrome, host, { type: 'peek_post', url }, 18_000)
        .then((result) => respond(result ?? { refusal: 'unavailable' }), () => respond({ refusal: 'unavailable' }));
      return true;
    }
    return false;
  });
}
