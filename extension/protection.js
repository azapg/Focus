import { installGate } from './overlay.js';
import { publicTlds } from './public-tlds.js';

const MAX_CACHE = 512;

function dnsHost(url) {
  try {
    const { protocol, hostname } = new URL(url);
    if (protocol !== 'https:' && protocol !== 'http:') return null;
    const host = hostname.replace(/\.$/, '');
    // Exclude IPs, single-label names and special-use local names from external queries.
    if (!host.includes('.') || !publicTlds.has(host.split('.').at(-1)) || host.length > 253 ||
        /\.(?:local|localhost|internal|home\.arpa)$/.test(host) ||
        !host.split('.').every((label) => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label))) return null;
    return host;
  } catch {
    return null;
  }
}

export function classifyAnswer(body, host, type) {
  if (body?.TC || ![0, 3].includes(body?.Status) ||
      body.Question?.length !== 1 || body.Question[0]?.name?.toLowerCase() !== host + '.' ||
      body.Question[0].type !== type) return null;

  const answers = Array.isArray(body.Answer) ? body.Answer : [];
  const addresses = answers.filter((record) => record.type === type);
  const filtered = Array.isArray(body.Additional) && body.Additional.some((record) =>
    record.type === 41 && /\bEDE: (?:15|16|17|18) \([^)]+\): \(Blocked by NextDNS\b/i.test(record.data));
  const blockPage = answers.some((record) => record.type === 5 &&
    /^blockpage\.nextdns\.io\.?$/i.test(record.data));
  const sinkhole = addresses.length > 0 && addresses.every((record) =>
    type === 1 ? record.data === '0.0.0.0' : /^(?=.*:)[0:]+$/.test(record.data));
  const ttl = Math.min(60, ...answers.map(({ TTL }) => Number.isFinite(TTL) ? Math.max(1, TTL) : 60));
  return {
    blocked: filtered || blockPage || sinkhole,
    noAddress: body.Status === 0 && addresses.length === 0,
    ttl,
  };
}

async function query(fetch, profile, host, type) {
  const url = new URL(`https://dns.nextdns.io/${profile}`);
  url.searchParams.set('name', host);
  url.searchParams.set('type', type === 1 ? 'A' : 'AAAA');
  const response = await fetch(url, {
    headers: { Accept: 'application/dns-json' },
    credentials: 'omit',
    redirect: 'error',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
    signal: AbortSignal.timeout(3000),
  });
  if (!response.ok) return null;
  return classifyAnswer(await response.json(), host, type);
}

export function createProtection(chrome, fetch) {
  let state;
  let warmed = false;
  let scopeRechecks = [];
  const cache = new Map();
  const changes = new Map();
  const retries = new Set();
  const mutedDocuments = new Map();
  const queue = [];
  let requests = 0;

  function pump() {
    while (requests < 8 && queue.length) {
      requests++;
      queue.shift()().finally(() => { requests--; pump(); });
    }
  }

  function verdict(host, profile) {
    const previous = cache.get(host);
    if (previous?.expires > Date.now()) return previous.promise;
    const entry = { expires: Infinity };
    entry.promise = new Promise((resolve) => {
      queue.push(async () => {
        let result;
        try {
          if (profile === state?.profile) result = await query(fetch, profile, host, 1);
          if (profile === state?.profile && result?.noAddress && !result.blocked) result = await query(fetch, profile, host, 28);
        } catch {
          // Transient resolver/transport failures cannot establish a block.
          result = null;
        }
        entry.expires = Date.now() + (result?.ttl ?? 5) * 1000;
        resolve(result?.blocked ?? null);
      });
    });
    cache.delete(host);
    cache.set(host, entry);
    if (cache.size > MAX_CACHE) cache.delete(cache.keys().next().value);
    pump();
    return entry.promise;
  }

  function change(tabId, op) {
    const next = (changes.get(tabId) ?? Promise.resolve()).then(op).catch(() => {
      // Chrome can discard/navigate a tab or refuse injection into a protected page.
    });
    changes.set(tabId, next);
    return next.finally(() => {
      if (changes.get(tabId) === next) changes.delete(tabId);
    });
  }

  const activeFrames = async (tabId) => (await chrome.webNavigation.getAllFrames({ tabId }) ?? [])
    .filter((frame) => frame.documentLifecycle === 'active' && !frame.errorOccurred);

  async function setMute(tabId, documentId) {
    const { mutedInfo } = await chrome.tabs.get(tabId);
    const ours = mutedInfo?.extensionId === chrome.runtime.id;
    if (documentId) {
      if (mutedInfo?.muted && !ours) return; // Preserve an existing user or extension mute.
      if (!mutedInfo?.muted) await chrome.tabs.update(tabId, { muted: true });
      mutedDocuments.set(tabId, documentId);
    } else {
      mutedDocuments.delete(tabId);
      if (mutedInfo?.muted && ours) await chrome.tabs.update(tabId, { muted: false });
    }
  }

  async function check(tabId) {
    const desired = state;
    if (!desired?.profile) return;
    retries.delete(tabId);
    try {
      const frames = await activeFrames(tabId);
      const top = frames.find((frame) => frame.frameId === 0);
      const host = dnsHost(top?.url);
      // A tab mute survives navigation. Release ours before waiting on a new host.
      if (!host || mutedDocuments.get(tabId) !== top.documentId) {
        await change(tabId, async () => {
          if (desired !== state) return;
          const current = (await activeFrames(tabId)).find((frame) => frame.frameId === 0);
          if (desired === state && current?.documentId === top?.documentId &&
              (!host || mutedDocuments.get(tabId) !== top.documentId)) {
            await setMute(tabId, null);
          }
        });
      }
      if (!host || desired !== state || !top.documentId) return;
      // Warm the cache during the pause so the sweep at restoration is usually local.
      if (desired.paused_until) void verdict(host, desired.profile);
      const blocked = desired.paused_until ? false : await verdict(host, desired.profile);
      if (desired !== state) return;
      if (blocked === null) {
        retries.add(tabId);
        return;
      }
      await change(tabId, async () => {
        if (desired !== state) return;
        const current = await activeFrames(tabId);
        if (desired !== state || !current.some((frame) => frame.frameId === 0 && frame.documentId === top.documentId)) return;
        await Promise.allSettled(current.map(({ frameId, documentId }) => blocked
          ? chrome.scripting.executeScript({
            target: { tabId, documentIds: [documentId] },
            func: installGate, args: [frameId === 0, desired.ended_at], injectImmediately: true,
          })
          : chrome.tabs.sendMessage(tabId, { type: 'clear-dns-gate' }, { documentId })));
        if (desired !== state) return;
        const latest = (await activeFrames(tabId)).find((frame) => frame.frameId === 0);
        if (desired === state && latest?.documentId === top.documentId) {
          await setMute(tabId, blocked ? top.documentId : null);
        }
      });
    } catch {
      // The tab may have disappeared.
    }
  }

  async function scan() {
    if (!state) return;
    const tabs = await chrome.tabs.query({}).catch(() => []);
    for (const { id, incognito } of tabs) if (!incognito && id !== undefined) void check(id);
  }

  return {
    update(message) {
      const next = message.state;
      if (message.version !== 1 || !Number.isSafeInteger(next?.paused_until) || next.paused_until < 0 ||
          !(next.ended_at === null || (Number.isSafeInteger(next.ended_at) && next.ended_at > 0))) return;
      const scopeRevision = next.scope_revision ?? 0;
      if (!Number.isSafeInteger(scopeRevision) || scopeRevision < 0) return;
      const profile = typeof next.profile === 'string' && /^[a-fA-F0-9]{6}$/.test(next.profile)
        ? next.profile.toLowerCase() : null;
      const profileChanged = state?.profile !== profile;
      const scopeChanged = (state?.scope_revision ?? 0) !== scopeRevision;
      if (state?.paused_until === next.paused_until && state.ended_at === next.ended_at && !scopeChanged && !profileChanged) return;
      if (scopeChanged || profileChanged) cache.clear();
      if (scopeChanged) {
        const now = Date.now();
        scopeRechecks = [now + 5_000, now + 65_000];
      }
      state = { ...next, profile, scope_revision: scopeRevision };
      warmed = false;
      retries.clear();
      void scan();
    },
    check,
    async blockedHost(host) {
      const desired = state;
      if (!desired?.profile) return null;
      if (desired.paused_until) return false;
      const blocked = await verdict(host, desired.profile);
      return desired === state ? blocked : null;
    },
    heartbeat() {
      // An elapsed deadline only prewarms the cache. Wait for the native
      // confirmation before covering tabs, including when DNS-on has failed.
      if (!warmed && state?.paused_until && state.paused_until - Date.now() <= 10_000) {
        warmed = true;
        void scan();
      }
      if (Date.now() >= scopeRechecks[0]) {
        scopeRechecks = scopeRechecks.filter((at) => at > Date.now());
        cache.clear();
        retries.clear();
        void scan();
      }
      for (const tabId of [...retries]) void check(tabId);
    },
    forget(tabId) { retries.delete(tabId); mutedDocuments.delete(tabId); },
  };
}
