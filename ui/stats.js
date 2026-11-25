// Shared math for the pill and its stats panel. Input is what the `history` command returns:
// { now, spans: [{s, e, app, label}], pauses: [{t, end, minutes, reason}] } (all times in ms).
const Stats = (() => {
  const MIN = 60e3, DAY = 864e5;
  const dayStart = (t) => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); };
  const overlap = (a, b, from, to) => Math.max(0, Math.min(b, to) - Math.max(a, from));
  const checkIns = Object.freeze({
    tired: 'Tired', bored: 'Bored', stuck: 'Stuck', anxious: 'Anxious', specific: 'Need something specific',
  });
  const validRating = value => Number.isInteger(value) && value >= 1 && value <= 5;
  const checkInLabel = value => Object.hasOwn(checkIns, value) ? checkIns[value] : '';

  function endingDomain(spans, pause) {
    let last = null;
    for (const span of spans) {
      if (span.label === 'FocusDNS' || span.app === 'org.nightingalelab.focus.phone'
          || !overlap(span.s, span.e, pause.t, pause.end)) continue;
      if (!last || Math.min(span.e, pause.end) > Math.min(last.e, pause.end)
          || (Math.min(span.e, pause.end) === Math.min(last.e, pause.end) && span.s > last.s)) last = span;
    }
    if (!last) return '';
    let host = last.domain || '';
    if (!host && last.browser?.url) {
      try {
        const url = new URL(last.browser.url);
        if (url.protocol === 'https:' || url.protocol === 'http:') host = url.hostname;
      } catch { /* no usable browser URL */ }
    }
    host = host.toLowerCase().replace(/^www\./, '');
    return /^[a-z0-9-]+(?:\.[a-z0-9-]+)+$/.test(host) ? host : '';
  }

  function pauseFeedback(pauses, from = -Infinity, to = Infinity) {
    const hours = new Map();
    for (const pause of pauses) {
      if (pause.kind === 'app' || pause.t < from || pause.t >= to || !checkInLabel(pause.check_in)) continue;
      const hour = new Date(pause.t).getHours();
      if (!hours.has(hour)) hours.set(hour, {});
      const counts = hours.get(hour);
      counts[pause.check_in] = (counts[pause.check_in] || 0) + 1;
    }
    const rated = pauses.filter(p => p.kind !== 'app' && validRating(p.rating) && p.end_kind !== null && p.end <= p.sourceHistory.now)
      .sort((a, b) => b.end - a.end)
      .map(p => ({ score: p.rating, domain: endingDomain(p.sourceHistory.spans, p) }));
    const domain = rated.find(p => p.domain)?.domain || '';
    const recent = (domain ? rated.filter(p => p.domain === domain) : rated).slice(0, 8);
    const worth = recent.length
      ? { domain, count: recent.length, average: recent.reduce((sum, p) => sum + p.score, 0) / recent.length }
      : null;
    return { hours: [...hours.entries()].sort(([a], [b]) => a - b), worth };
  }

  function fmt(ms) {
    const m = Math.round(ms / MIN);
    if (m === 0 && ms > 0) return '<1m';
    if (m < 60) return `${m}m`;
    return `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m`;
  }

  /** Everything the views need for one time range. */
  function summarize(h, from, to) {
    to = Math.min(to, h.now);
    const offs = h.pauses.map((p) => [p.t, Math.min(p.end, h.now)]);
    const offDuring = (a, b) => offs.reduce((n, [x, y]) => n + overlap(a, b, x, y), 0);
    let active = 0, activeOff = 0;
    const labels = Object.create(null), siteTime = Object.create(null);
    const browserApps = new Set(['chrome', 'edge', 'brave', 'firefox', 'opera', 'vivaldi', 'arc', 'zen', 'thorium', 'chromium']);
    const observed = (s) => s.source === 'chromium_extension' && Boolean(s.browser?.url);
    for (const s of h.spans) {
      if (observed(s)) siteTime[s.label] = (siteTime[s.label] || 0) + overlap(s.s, s.e, from, to);
    }
    for (const s of h.spans) {
      const a = Math.max(s.s, from), b = Math.min(s.e, to);
      if (b <= a || s.label === 'FocusDNS') continue;
      const d = b - a, o = offDuring(a, b), page = observed(s);
      const browser = page || browserApps.has(s.app.toLowerCase());
      const separateSite = page && siteTime[s.label] > 5 * MIN;
      const label = browser && !separateSite ? s.app : s.label;
      active += d; activeOff += o;
      const L = (labels[label] ??= { label, ms: 0, off: 0, pages: Object.create(null), untracked: 0, expandable: false, browserBucket: false });
      L.ms += d; L.off += o; L.expandable ||= browser; L.browserBucket ||= browser && !separateSite;
      if (page) {
        const b = s.browser, entry = (L.pages[b.url] ??= { url: b.url, title: b.title || '', ms: 0, last: 0 });
        entry.ms += d;
        if (s.e >= entry.last) { entry.title = b.title || ''; entry.last = s.e; }
      } else if (browser) {
        L.untracked += d;
      }
    }
    const off = offs.reduce((n, [x, y]) => n + overlap(x, y, from, to), 0);
    return {
      active, activeOff, off,
      away: Math.max(0, to - from - active),
      pauses: h.pauses.filter((p) => p.t >= from && p.t < to).sort((a, b) => a.t - b.t),
      labels: Object.values(labels).map((l) => ({ ...l, pages: Object.values(l.pages).sort((a, b) => b.ms - a.ms) })).sort((a, b) => b.ms - a.ms),
    };
  }

  /** What was actually on screen during a pause: [[label, ms], ...], biggest first. */
  function did(h, p) {
    const end = Math.min(p.end, h.now), m = {};
    for (const s of h.spans) {
      const d = overlap(s.s, s.e, p.t, end);
      if (d && s.label !== 'FocusDNS') m[s.label] = (m[s.label] || 0) + d;
    }
    return Object.entries(m).filter(([, ms]) => ms >= 20e3).sort((a, b) => b[1] - a[1]).slice(0, 3);
  }

  /** Does the reason you typed mention this site/app? Unmentioned ones get highlighted. */
  const ALIASES = { X: ['twitter', 'tweet', ' x ', 'x.com', 'post'], YouTube: ['youtube', 'video', 'yt', 'talk', 'watch'] };
  function mentioned(reason, label) {
    const r = ` ${reason.toLowerCase()} `;
    const name = label.toLowerCase().replace(/^www\./, '');
    const site = name.split('.')[0];
    const aliases = ALIASES[label] || (name === 'youtube.com' ? ALIASES.YouTube : name === 'x.com' ? ALIASES.X : []);
    return r.includes(name) || (name.includes('.') && site.length > 2 && r.includes(site)) || aliases.some((a) => r.includes(a));
  }

  /** 5-minute buckets across a day: 'off' (DNS paused), 'on' (at the screen), 'away', or 'future'. */
  function dayStrip(h, day) {
    const B = 5 * MIN, out = [];
    for (let t = day; t < day + DAY; t += B) {
      let st = 'away';
      if (t >= h.now) st = 'future';
      else if (h.pauses.some((p) => overlap(p.t, Math.min(p.end, h.now), t, t + B) > 0)) st = 'off';
      else if (h.spans.reduce((n, s) => n + (s.label === 'FocusDNS' ? 0 : overlap(s.s, s.e, t, t + B)), 0) >= B / 2) st = 'on';
      const last = out[out.length - 1];
      if (last && last.st === st) last.n++; else out.push({ st, n: 1, t });
    }
    return out;
  }

  return { MIN, DAY, dayStart, fmt, summarize, did, mentioned, dayStrip, checkIns, checkInLabel, validRating, pauseFeedback };
})();

if (typeof window !== "undefined") window.Stats = Stats;
