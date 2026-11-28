const Stats = globalThis.Stats;

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const time = (t) => new Date(t).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false });

export function renderIntentions(sources, fine, range, from, to, { rulesDoc, fineCache, classifier = globalThis.ActivityClassifier } = {}) {

    const sec = $('pIntentionsSec');
    if (!sec) return null;
    if (range !== 'today') {
      sec.hidden = true;
      sec.innerHTML = '';
      return null;
    }
    const laptop = sources.find((s) => s.id === 'laptop') || sources[0];
    if (!laptop || !Array.isArray(laptop.history?.intentions)) {
      sec.hidden = true;
      sec.innerHTML = '';
      return null;
    }
    const sum = Stats.summarizeIntentions(laptop.history, from, to, {
      classifier,
      userRules: rulesDoc,
      cache: fineCache,
      focusRuns: fine?.focus?.runs,
    });
    const segClass = (kind) => ({ ice: ' class="ice"', muted: ' class="g"', drift: ' class="o"', away: ' class="h"' }[kind] || '');
    const renderDetail = (d) => {
      if (d.kind === 'focus') return `<span class="ice">${esc(d.text)}</span>`;
      if (d.kind === 'drift') return `<span class="o">${esc(d.text)}</span>`;
      return esc(d.text);
    };
    const rowsHtml = sum.rows.map((r) => {
      if (r.kind === 'none') {
        const bars = r.segments.map((seg) => `<i class="o" style="flex:${seg.ms}"></i>`).join('');
        const det = r.details.length ? `<div class="det"><span class="o">${r.details.map((d) => esc(d.text)).join(' · ')}</span></div>` : '';
        return `<div class="irow" data-intent-row="none">
          <span class="tm">${time(r.start)}</span>
          <div>
            <div class="what o">No intention</div>
            <div class="mini">${bars}</div>
            ${det}
          </div>
          <div class="num o">${Stats.fmt(r.actualMs)}</div>
        </div>`;
      }
      const bars = r.segments.map((seg) => `<i${segClass(seg.kind)} style="flex:${seg.ms}"></i>`).join('');
      const detHtml = r.details.map(renderDetail).join(' · ');
      const ext = r.extensions ? ` (+${r.extensions * 10}m)` : '';
      const sub = `planned ${Stats.fmt(r.plannedMs)}${ext} · ${r.statusLabel}`;
      return `<div class="irow" data-intent-row="${esc(r.outcome || (r.running ? 'running' : 'done'))}">
        <span class="tm">${time(r.start)}</span>
        <div>
          <div class="what">${esc(r.label)}</div>
          ${r.doneWhen ? `<div class="det">Done when: ${esc(r.doneWhen)}</div>` : ''}
          <div class="mini">${bars}</div>
          ${detHtml ? `<div class="det">${detHtml}</div>` : ''}
        </div>
        <div class="num">${Stats.fmt(r.actualMs)}<small>${esc(sub)}</small></div>
      </div>`;
    }).join('');

    sec.hidden = false;
    sec.innerHTML = `<div class="bento-card">
      <div class="bento-head">
        <span class="bento-title">Intentions</span>
        <button type="button" class="bento-action" data-intent-new>New</button>
      </div>
      ${sum.rows.length ? `<div class="intent-list">${rowsHtml}</div>` : '<div class="bento-empty">No intention set today.</div>'}
    </div>`;
    return sum;
}
