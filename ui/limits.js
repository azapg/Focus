// Soft daily limits shared by the pause pill, the stats panel and Settings.
// Nothing is blocked by DNS: the pill just won't offer a pause longer than what's left today
// (1 minute is always allowed). Stricter values apply now; looser ones start tomorrow.
const FocusLimits = (() => {
  const KEY = 'focus.limits.v1';
  const DEF = { dnsLimitMin: 60, waitSeconds: 15, maxPauseMin: 30 };
  // true when `next` is stricter than `cur`
  const STRICTER = {
    dnsLimitMin: (cur, next) => next < cur,
    waitSeconds: (cur, next) => next > cur,
    maxPauseMin: (cur, next) => next < cur,
  };
  const WAIT_FROM_PAUSE = 3; // the wait starts with the 3rd pause of the day

  function store() {
    try {
      return typeof localStorage !== 'undefined' ? localStorage : null;
    } catch {
      return null;
    }
  }
  function load() {
    try {
      return JSON.parse(store()?.getItem(KEY) || '{}') || {};
    } catch {
      return {};
    }
  }
  function save(doc) {
    try {
      store()?.setItem(KEY, JSON.stringify(doc));
    } catch {
      /* storage can be unavailable; defaults still work */
    }
  }
  function tomorrow(now) {
    const t = new Date(now);
    t.setHours(24, 0, 0, 0);
    return t.getTime();
  }

  function get(now = Date.now()) {
    const doc = load();
    const values = { ...DEF, ...(doc.values || {}) };
    const pending = { ...(doc.pending || {}) };
    let changed = false;
    for (const [k, p] of Object.entries(pending)) {
      if (p && now >= p.from) {
        values[k] = p.value;
        delete pending[k];
        changed = true;
      }
    }
    if (changed) save({ values, pending });
    return { ...values, pending };
  }

  function set(key, value, now = Date.now()) {
    if (!(key in DEF)) throw new Error(`unknown limit ${key}`);
    const cur = get(now);
    const doc = load();
    const values = { ...DEF, ...(doc.values || {}) };
    const pending = { ...(doc.pending || {}) };
    if (value === cur[key]) delete pending[key];
    else if (STRICTER[key](cur[key], value)) {
      values[key] = value;
      delete pending[key];
    } else pending[key] = { value, from: tomorrow(now) };
    save({ values, pending });
    if (key === 'dnsLimitMin' && typeof window !== 'undefined') window.dispatchEvent(new Event('focus-pause-limit'));
    return get(now);
  }

  function undo(key) {
    const doc = load();
    if (doc.pending) delete doc.pending[key];
    save(doc);
    if (key === 'dnsLimitMin' && typeof window !== 'undefined') window.dispatchEvent(new Event('focus-pause-limit'));
  }

  /** Pause lengths to offer: capped by max pause, and by what's left of today's DNS-off limit. */
  function pauseChoices(offTodayMs, now = Date.now(), credit = 0) {
    const L = get(now);
    const leftMin = Math.max(0, L.dnsLimitMin + credit - offTodayMs / 60000);
    return [1, 5, 10, 30]
      .filter((m) => m <= L.maxPauseMin)
      .map((m) => ({ m, ok: m === 1 || m <= Math.floor(leftMin) }));
  }

  /** Seconds to wait before the next pause can start (0 for the first two pauses of the day). */
  function waitFor(pausesToday, now = Date.now()) {
    return pausesToday + 1 >= WAIT_FROM_PAUSE ? get(now).waitSeconds : 0;
  }

  return { DEF, get, set, undo, pauseChoices, waitFor, WAIT_FROM_PAUSE };
})();

if (typeof window !== 'undefined') window.FocusLimits = FocusLimits;
export default FocusLimits;
