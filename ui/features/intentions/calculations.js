// Pure intention calculations shared by the desktop dock and Today panel.
const IntentionsCalculations = (() => {
  function create({ MIN, fmt, overlap }) {
    const WASTE_CATS = new Set(['social', 'video', 'tv', 'games', 'adult']);
    const MUTED_CATS = new Set(['messaging', 'web', 'music', 'errands', 'unsorted', 'news', 'shopping']);
    const COARSE_WASTE = new Set(['watching']);
    const SOCIAL_NAMES = new Set(['x', 'twitter', 'reddit', 'instagram', 'facebook', 'tiktok', 'threads', 'bluesky', 'youtube', 'twitch', 'netflix', 'prime video', 'disney+']);

    function mergeIntervals(intervals) {
      const valid = intervals.filter(([a, b]) => b > a).sort((a, b) => a[0] - b[0]);
      if (!valid.length) return [];
      const out = [valid[0].slice()];
      for (let i = 1; i < valid.length; i++) {
        const [a, b] = valid[i];
        const last = out[out.length - 1];
        if (a <= last[1]) last[1] = Math.max(last[1], b);
        else out.push([a, b]);
      }
      return out;
    }

    function sumIntervals(intervals) {
      return intervals.reduce((n, [a, b]) => n + Math.max(0, b - a), 0);
    }

    function intersectWithRange(intervals, from, to) {
      const out = [];
      for (const [a, b] of intervals) {
        const x = Math.max(a, from), y = Math.min(b, to);
        if (y > x) out.push([x, y]);
      }
      return out;
    }

    // Ask-approved service windows (history.grants) are on-plan by definition:
    // the judge already decided the request served what the user was doing.
    const serviceKey = (s) => String(s || '').toLowerCase().replace(/\s*\(.+\)$/, '')
      .replace(/^www\./, '').replace(/\.(com|tv|gg)$/, '').replace(/[^a-z0-9]/g, '');

    function approvedIntervals(grants, app, a, b) {
      const key = serviceKey(app);
      if (!key || !Array.isArray(grants)) return [];
      const windows = grants
        .filter((g) => (g.services || []).some((svc) => serviceKey(svc) === key))
        .map((g) => [g.t, g.end]);
      return mergeIntervals(intersectWithRange(windows, a, b));
    }

    function subtractIntervals(a, b, cut) {
      const out = [];
      let cursor = a;
      for (const [x, y] of cut) {
        if (x > cursor) out.push([cursor, Math.min(x, b)]);
        cursor = Math.max(cursor, y);
      }
      if (b > cursor) out.push([cursor, b]);
      return out.filter(([x, y]) => y > x);
    }

    function doneWhenQuestion(doneWhen) {
      const raw = String(doneWhen || '').trim().replace(/[.!,;]+$/, '');
      if (!raw) return 'Done?';
      const cap = (s) => (s ? s[0].toUpperCase() + s.slice(1) : s);
      if (raw.endsWith('?')) return cap(raw);
      const isMatch = raw.match(/^((?:the\s+)?\S+(?:\s+\S+){0,2})\s+is\s+(.+)$/i);
      if (isMatch) return `Is ${isMatch[1]} ${isMatch[2]}?`;
      const areMatch = raw.match(/^((?:the\s+)?\S+(?:\s+\S+){0,2})\s+are\s+(.+)$/i);
      if (areMatch) return `Are ${areMatch[1]} ${areMatch[2]}?`;
      return `${cap(raw)}?`;
    }

    function classifyForIntention(span, classifier, userRules, cache) {
      if (span.label === 'FocusDNS' || span.app === 'FocusDNS') return null;
      if (classifier) {
        const c = classifier.classifySpan(span, 'windows', userRules);
        const explicit = cache?.items?.[`app:${c.app}`];
        if (c.noise && !explicit) return null;
        const f = classifier.fineCategory(span, c, 'windows', cache);
        return { app: f.app, fineId: f.id, coarse: c.category };
      }
      const app = span.label || span.app || 'Unknown';
      const low = app.toLowerCase().replace(/^www\./, '');
      const isSocialOrVideo = SOCIAL_NAMES.has(low) || SOCIAL_NAMES.has(low.split('.')[0]);
      const fineId = low.includes('youtube') || low.includes('twitch') || low.includes('netflix')
        ? 'video'
        : isSocialOrVideo ? 'social' : 'coding';
      const coarse = fineId === 'video' ? 'watching' : fineId === 'social' ? 'browsing' : 'building';
      return { app, fineId, coarse };
    }

    const INTENTION_HINTS = {
      x: [/\btwitter\b/, /\btweet\b/, /\bx\.com\b/, /\bx\s+post\b/, /\bpost\s+on\s+x\b/, /\bon\s+x\b/],
      youtube: [/\byoutube\b/, /\byt\b/, /\bvideo\b/, /\btutorial\b/, /\blecture\b/, /\bwatch\b/],
      twitch: [/\btwitch\b/, /\bstream\b/],
      reddit: [/\breddit\b/, /\bsubreddit\b/],
      instagram: [/\binstagram\b/, /\breel\b/, /\bon\s+ig\b/],
      tiktok: [/\btiktok\b/],
      netflix: [/\bnetflix\b/, /\bmovie\b/, /\bepisode\b/, /\bshow\b/],
      facebook: [/\bfacebook\b/],
      threads: [/\bthreads\b/],
      bluesky: [/\bbluesky\b/, /\bbsky\b/],
      linkedin: [/\blinkedin\b/],
    };

    function fitsIntention(intentionText, app) {
      const r = String(intentionText || '').toLowerCase();
      const clean = String(app || '').toLowerCase().replace(/\s*\(.+\)$/, '').trim();
      if (!r || !clean) return false;
      const hints = INTENTION_HINTS[clean];
      if (hints) return hints.some((rx) => rx.test(r));
      if (clean.length >= 3 && r.includes(clean)) return true;
      const base = clean.replace(/^www\./, '').split('.')[0];
      return clean.includes('.') && base.length > 2 && r.includes(base);
    }

    function detectOffPlan(intention, spans, now, opts = {}) {
      if (!intention || intention.end != null) return null;
      const classifier = opts.classifier ?? (globalThis.ActivityClassifier ?? null);
      const windowMs = opts.windowMs ?? 15 * MIN;
      const from = Math.max(intention.start || 0, now - windowMs);
      if (now <= from) return null;
      const intentionText = `${intention.label || ''} ${intention.done_when || ''} ${intention.raw || ''}`.trim();
      const byApp = Object.create(null);
      for (const s of spans || []) {
        let d = overlap(s.s, s.e, from, now);
        if (!d) continue;
        const info = classifyForIntention(s, classifier, opts.userRules, opts.cache);
        if (!info) continue;
        const isWaste = WASTE_CATS.has(info.fineId) || COARSE_WASTE.has(info.coarse);
        if (isWaste) {
          d -= sumIntervals(approvedIntervals(opts.grants, info.app, Math.max(s.s, from), Math.min(s.e, now)));
          if (d <= 0) continue;
        }
        const offPlan = isWaste && !fitsIntention(intentionText, info.app, info.coarse, classifier);
        const cur = (byApp[info.app] ??= { app: info.app, ms: 0, offPlan: false });
        cur.ms += d;
        cur.offPlan ||= offPlan;
      }
      const all = Object.values(byApp).sort((a, b) => b.ms - a.ms);
      const topApp = all[0] || null;
      const topOff = all.find((x) => x.offPlan) || null;
      if (topOff && ((topApp && topApp.app === topOff.app && topOff.ms >= 3 * MIN) || topOff.ms >= 5 * MIN)) {
        const minutes = Math.max(1, Math.round(topOff.ms / MIN));
        return { app: topOff.app, ms: topOff.ms, minutes, text: `${topOff.app} ${minutes}m off-plan` };
      }
      if (opts.liveFocusState === 'drifting' || opts.liveFocusState === 'unfocused') {
        if (topOff && topOff.ms >= MIN) {
          const minutes = Math.max(1, Math.round(topOff.ms / MIN));
          return { app: topOff.app, ms: topOff.ms, minutes, text: `${topOff.app} ${minutes}m off-plan` };
        }
        // Watching something Ask just approved reads as "consuming" to the focus classifier.
        if ((opts.grants || []).some((g) => g.t <= now && now < g.end)) return null;
        return { app: '', ms: 0, minutes: 0, text: 'Off-plan' };
      }
      return null;
    }

    function intentionOutcomeLabel(outcome, running) {
      if (running) return 'Running';
      return { done: 'Done', partly: 'Partly', no: 'No', cleared: 'Cleared', replaced: 'Ended' }[outcome] || 'Done';
    }

    /** Summarizes Today's intentions + 15m+ active periods without an intention. */
    function summarizeIntentions(h, from, to, opts = {}) {
      const boundTo = Math.min(to, h.now || to);
      const classifier = opts.classifier ?? (globalThis.ActivityClassifier ?? null);
      const minUnclearMs = opts.minUnclearMs ?? 15 * MIN;
      const awayBreakMs = opts.awayBreakMs ?? 30 * MIN;
      const spans = (h.spans || []).filter((s) => s.label !== 'FocusDNS' && s.app !== 'FocusDNS');
      const rawIntentions = (h.intentions || [])
        .filter((it) => {
          const end = it.end != null ? it.end : boundTo;
          return it.start < boundTo && (end > from || (it.active && it.end == null));
        })
        .sort((a, b) => a.start - b.start);

      let focusIntervals = [];
      if (Array.isArray(opts.focusRuns)) {
        for (const run of opts.focusRuns) {
          if (Array.isArray(run.intervals)) focusIntervals.push(...run.intervals);
          else if (run.start != null && run.end != null) focusIntervals.push([run.start, run.end]);
        }
      } else if (classifier?.detectFocusSessions) {
        const det = classifier.detectFocusSessions(
          [{ id: 'laptop', name: 'Laptop', kind: 'windows', history: h }],
          from,
          boundTo,
          { userRules: opts.userRules, cache: opts.cache },
        );
        for (const run of det.runs || []) focusIntervals.push(...(run.intervals || []));
      }
      focusIntervals = mergeIntervals(focusIntervals);

      const rows = [];
      const covered = [];
      let doneCount = 0, partlyCount = 0, runningCount = 0;

      for (const it of rawIntentions) {
        const running = Boolean(it.active && it.end == null);
        const start = Math.max(it.start, from);
        const end = Math.min(it.end != null ? it.end : boundTo, boundTo);
        const spanMs = Math.max(0, end - start);
        const actualMs = Math.max(0, (it.end != null ? it.end : boundTo) - it.start);
        if (end > start) covered.push([start, end]);

        const outcome = running ? 'running' : (it.outcome || 'done');
        if (running) runningCount++;
        else if (outcome === 'done') doneCount++;
        else if (outcome === 'partly') partlyCount++;

        const intentionText = `${it.label || ''} ${it.done_when || ''} ${it.raw || ''}`.trim();
        const activeIntervals = [];
        const driftIntervals = [];
        const mutedIntervals = [];
        const byApp = Object.create(null);

        for (const s of spans) {
          const a = Math.max(s.s, start), b = Math.min(s.e, end);
          if (b <= a) continue;
          const info = classifyForIntention(s, classifier, opts.userRules, opts.cache);
          if (!info) continue;
          const d = b - a;
          const isWaste = WASTE_CATS.has(info.fineId) || COARSE_WASTE.has(info.coarse);
          const unfit = isWaste && !fitsIntention(intentionText, info.app, info.coarse, classifier);
          const drift = unfit ? subtractIntervals(a, b, approvedIntervals(h.grants, info.app, a, b)) : [];
          const isDrift = drift.length > 0;
          const isMuted = !unfit && MUTED_CATS.has(info.fineId);
          activeIntervals.push([a, b]);
          driftIntervals.push(...drift);
          if (isMuted) mutedIntervals.push([a, b]);
          const entry = (byApp[info.app] ??= { app: info.app, ms: 0, driftMs: 0, isDrift: false });
          entry.ms += d;
          entry.driftMs += sumIntervals(drift);
          entry.isDrift ||= isDrift;
        }

        const onScreenMs = sumIntervals(mergeIntervals(activeIntervals));
        const deepFocusMs = Math.min(onScreenMs, sumIntervals(intersectWithRange(focusIntervals, start, end)));
        const driftMs = Math.min(Math.max(0, onScreenMs - deepFocusMs), sumIntervals(mergeIntervals(driftIntervals)));
        const rawMutedMs = sumIntervals(mergeIntervals(mutedIntervals));
        const remainingOn = Math.max(0, onScreenMs - deepFocusMs - driftMs);
        const mutedMs = Math.min(remainingOn, rawMutedMs);
        const regularOnMs = Math.max(0, remainingOn - mutedMs);
        const awayMs = Math.max(0, spanMs - onScreenMs);

        const segments = [];
        const mostlyAway = awayMs > onScreenMs && awayMs >= 2 * MIN;
        if (mostlyAway) {
          if (awayMs >= 30e3) segments.push({ kind: 'away', ms: awayMs });
          if (deepFocusMs >= 30e3) segments.push({ kind: 'ice', ms: deepFocusMs });
          if (regularOnMs >= 30e3) segments.push({ kind: 'on', ms: regularOnMs });
          if (mutedMs >= 30e3) segments.push({ kind: 'muted', ms: mutedMs });
          if (driftMs >= 30e3) segments.push({ kind: 'drift', ms: driftMs });
        } else {
          if (deepFocusMs >= 30e3) segments.push({ kind: 'ice', ms: deepFocusMs });
          if (regularOnMs >= 30e3) segments.push({ kind: 'on', ms: regularOnMs });
          if (mutedMs >= 30e3) segments.push({ kind: 'muted', ms: mutedMs });
          if (driftMs >= 30e3) segments.push({ kind: 'drift', ms: driftMs });
          if (awayMs >= 2 * MIN) segments.push({ kind: 'away', ms: awayMs });
        }
        if (!segments.length && spanMs > 0) {
          segments.push({ kind: onScreenMs > 0 ? 'on' : 'away', ms: spanMs });
        }

        const appList = Object.values(byApp)
          .filter((x) => x.ms >= MIN)
          .sort((a, b) => b.ms - a.ms)
          .slice(0, 4);
        const details = [];
        if (deepFocusMs >= MIN) details.push({ kind: 'focus', text: `${fmt(deepFocusMs)} deep focus` });
        if (mostlyAway && awayMs >= 2 * MIN) {
          details.push({ kind: 'away', text: `${fmt(awayMs)} away from screen` });
        }
        for (const a of appList) {
          if (a.isDrift && a.driftMs >= 2 * MIN) {
            details.push({ kind: 'drift', text: `${a.app} ${fmt(a.driftMs)} off-plan` });
          } else {
            details.push({ kind: 'app', text: `${a.app} ${fmt(a.ms)}` });
          }
        }
        if (!details.length && awayMs >= MIN) {
          details.push({ kind: 'away', text: `${fmt(awayMs)} away from screen` });
        }

        rows.push({
          kind: 'intention',
          id: it.id,
          start: it.start,
          end: it.end ?? null,
          actualMs,
          plannedMinutes: it.planned_minutes || 60,
          plannedMs: (it.planned_minutes || 60) * MIN,
          extensions: it.extensions || 0,
          label: it.label,
          doneWhen: it.done_when || null,
          outcome,
          statusLabel: intentionOutcomeLabel(outcome, running),
          running,
          segments,
          details,
          deepFocusMs,
          onScreenMs,
          driftMs,
          awayMs,
        });
      }

      // Build complement gaps outside covered intention intervals, split on long away breaks.
      const mergedCovered = mergeIntervals(covered);
      const gaps = [];
      let cursor = from;
      for (const [cs, ce] of mergedCovered) {
        if (cs > cursor) gaps.push([cursor, cs]);
        cursor = Math.max(cursor, ce);
      }
      if (boundTo > cursor) gaps.push([cursor, boundTo]);

      let unclearMs = 0;
      for (const [gStart, gEnd] of gaps) {
        const gapSpans = [];
        for (const s of spans) {
          const a = Math.max(s.s, gStart), b = Math.min(s.e, gEnd);
          if (b <= a) continue;
          const info = classifyForIntention(s, classifier, opts.userRules, opts.cache);
          if (!info) continue;
          gapSpans.push({ a, b, app: info.app });
        }
        gapSpans.sort((x, y) => x.a - y.a);
        const clusters = [];
        for (const sp of gapSpans) {
          const last = clusters[clusters.length - 1];
          if (last && sp.a - last.end < awayBreakMs) {
            last.end = Math.max(last.end, sp.b);
            last.items.push(sp);
          } else {
            clusters.push({ start: sp.a, end: sp.b, items: [sp] });
          }
        }
        for (const cluster of clusters) {
          const activeMs = sumIntervals(mergeIntervals(cluster.items.map((x) => [x.a, x.b])));
          if (activeMs < minUnclearMs) continue;
          unclearMs += activeMs;
          const byApp = Object.create(null);
          for (const sp of cluster.items) byApp[sp.app] = (byApp[sp.app] || 0) + (sp.b - sp.a);
          const topApps = Object.entries(byApp)
            .map(([app, ms]) => ({ app, ms }))
            .filter((x) => x.ms >= MIN)
            .sort((a, b) => b.ms - a.ms)
            .slice(0, 4);
          const segments = topApps.length
            ? topApps.map((x) => ({ kind: 'drift', ms: x.ms }))
            : [{ kind: 'drift', ms: activeMs }];
          const details = topApps.map((x) => ({ kind: 'drift', text: `${x.app} ${fmt(x.ms)}` }));
          rows.push({
            kind: 'none',
            start: cluster.start,
            end: cluster.end,
            actualMs: activeMs,
            label: 'No intention',
            doneWhen: null,
            outcome: null,
            statusLabel: '',
            running: false,
            segments,
            details,
          });
        }
      }

      rows.sort((a, b) => a.start - b.start);
      return {
        rows,
        doneCount,
        partlyCount,
        runningCount,
        totalIntentions: rawIntentions.length,
        unclearMs,
      };
    }

    return { doneWhenQuestion, detectOffPlan, summarizeIntentions };
  }

  return { create };
})();

if (typeof globalThis !== "undefined") globalThis.IntentionsCalculations = IntentionsCalculations;
