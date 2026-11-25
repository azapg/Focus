// Each device is summarized independently. A TV pause must never shade laptop usage.
const DeviceStats = (() => {
  function sources(laptop, devices, excluded = []) {
    const skip = new Set(excluded);
    const out = [];
    if (laptop && !skip.has('laptop')) out.push({ id: 'laptop', name: 'Laptop', kind: 'windows', history: laptop });
    for (const device of devices) {
      if (skip.has(device.id) || !device.snapshot) continue;
      const snapshot = device.snapshot;
      out.push({ id: device.id, name: device.name, kind: snapshot.kind || device.kind || 'tv', history: {
        now: Math.min(snapshot.now, Date.now()), spans: snapshot.spans,
        // App exceptions are history, but never DNS-off intervals.
        pauses: snapshot.pauses.filter(p => p.kind === 'dns'),
      }, allPauses: snapshot.pauses, apps: snapshot.apps });
    }
    return out;
  }

  function summarize(sources, from, to) {
    const multi = sources.length > 1;
    const total = { active: 0, activeOff: 0, off: 0, away: 0, pauses: [], labels: [] };
    for (const source of sources) {
      const h = source.history, sum = Stats.summarize(h, from, to);
      for (const key of ['active', 'activeOff', 'off', 'away']) total[key] += sum[key];
      total.labels.push(...sum.labels.map(l => ({ ...l, device_name: multi ? source.name : '', device_id: source.id })));
      const pauses = source.allPauses || h.pauses;
      total.pauses.push(...pauses.filter(p => p.end > from && p.t < Math.min(to, h.now)).map(p => ({
        ...p, sourceHistory: h, device_name: multi ? source.name : '',
        app_label: p.kind === 'app' ? (source.apps?.find(a => a.package === p.package)?.label || p.package) : '',
      })));
    }
    total.labels.sort((a, b) => b.ms - a.ms);
    total.pauses.sort((a, b) => a.t - b.t);
    return total;
  }

  function dayStrip(sources, day) {
    const strips = sources.map(source => Stats.dayStrip(source.history, day).flatMap(b => Array.from({ length: b.n }, (_, i) => ({ st: b.st, t: b.t + i * 5 * Stats.MIN }))));
    const out = [];
    for (let i = 0; i < 288; i++) {
      const states = strips.map(s => s[i]?.st);
      const st = states.includes('off') ? 'off' : states.includes('on') ? 'on' : states.includes('away') ? 'away' : 'future';
      const t = day + i * 5 * Stats.MIN;
      const last = out[out.length - 1];
      if (last?.st === st) last.n++; else out.push({ st, n: 1, t });
    }
    return out;
  }

  return { sources, summarize, dayStrip };
})();

if (typeof window !== "undefined") window.DeviceStats = DeviceStats;
