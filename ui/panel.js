// Stats panel (Today / Week / See all explorer, all devices):
// One-sentence total, thin proportional category bar + clickable legend,
// balanced #060607 OLED bento cards for Screen time and Pauses, stacked 7-day Week chart
// with amber DNS-off caps and clickable day filter, and searchable "See all" explorer.
// Uses Stats (stats.js), DeviceStats (device-stats.js), ActivityClassifier (activity-classifier.js),
// and FocusLimits (limits.js).
const Panel = (() => {
  const $ = (id) => document.getElementById(id);
  const esc = (s) =>
    String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const time = (t) => new Date(t).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false });
  const wday = (t) => new Date(t).toLocaleDateString('en-US', { weekday: 'short' });
  const shortDevice = (src) => (src.kind === 'phone' ? 'Phone' : src.kind === 'tv' ? 'TV' : 'Laptop');
  const fmtBar = (ms) => {
    if (ms < 60e3) return '';
    const m = Math.round(ms / 60e3);
    if (m < 60) return `${m}m`;
    return `${Math.round(m / 60)}h`;
  };

  let rulesDoc = { version: 1, rules: [] };
  let rulesLoaded = false;
  let lastInboxSig = '';
  let lastRenderArgs = null;
  let listenersBound = false;
  let fineCache = { version: 1, items: {} };
  let iconMap = {};
  let fineLoaded = false;
  let showAllPauses = false;
  let showMoreCats = false;
  let catFilter = null;
  let selectedDayIdx = null; // null or 0..6 on Week
  let explorerOpen = false;
  let expFilter = 'all'; // all | create | drift | off
  let expSearch = '';
  let openPause = null;
  let lastRange = 'today';

  const TOP_APPS = 5;
  const TOP_PAUSES = 3;
  const TOP_CATS = 4;
  const limitMin = () => (typeof FocusLimits !== 'undefined' ? FocusLimits.get().dnsLimitMin : 60);

  const SRC_SVG = {
    Phone: '<svg viewBox="0 0 24 24"><rect x="7" y="2.5" width="10" height="19" rx="2.2"/><path d="M11 18.5h2"/></svg>',
    Laptop: '<svg viewBox="0 0 24 24"><rect x="4" y="5" width="16" height="10.5" rx="1.5"/><path d="M2 19h20"/></svg>',
    TV: '<svg viewBox="0 0 24 24"><rect x="2.5" y="3.5" width="19" height="12.5" rx="1.8"/><path d="M8 21l2.5-5M16 21l-2.5-5"/></svg>',
    web: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.6 2.6 2.6 15.4 0 18M12 3c-2.6 2.6-2.6 15.4 0 18"/></svg>',
  };

  function srcIcon(a, multiDevice) {
    const k = a.web ? 'web' : multiDevice && a.devices.length === 1 ? a.devices[0].name : null;
    if (!k || !SRC_SVG[k]) return '';
    return `<span class="src-ico" data-src="${k}" title="${a.web ? 'Website' : `Only on ${k}`}">${SRC_SVG[k]}</span>`;
  }

  function dnsLimitChart(offMs, limitMs, days = 1, scoped = false) {
    const perDay = offMs / days;
    const frac = limitMs ? perDay / limitMs : 0;
    const over = perDay > limitMs;
    const left = Math.max(0, limitMs - perDay);
    const tone = over || frac >= 1 ? 'bad' : frac >= 0.75 ? 'warn' : 'ok';
    const scopeNote = scoped ? ' · all devices' : '';
    const sub = days > 1
      ? `${Stats.fmt(perDay)}/day · limit ${Stats.fmt(limitMs)}/day${scopeNote}`
      : `Limit ${Stats.fmt(limitMs)} · ${over ? `${Stats.fmt(perDay - limitMs)} over` : left < 30e3 ? 'limit reached' : `${Stats.fmt(left)} left`}${scopeNote}`;
    const r = 17, C = 2 * Math.PI * r, f = Math.min(1, frac);
    return `<div class="ring-box dl dl-ring ${tone}" data-dns-limit>
      <svg class="ring-svg${over ? ' bad' : ''}" viewBox="0 0 42 42" aria-hidden="true">
        <circle cx="21" cy="21" r="${r}" class="trk"/>
        ${f > 0 ? `<circle cx="21" cy="21" r="${r}" class="fill" stroke-dasharray="${(f * C).toFixed(1)} ${C.toFixed(1)}" transform="rotate(-90 21 21)"/>` : ''}
      </svg>
      <div class="ring-txt"><b>${Stats.fmt(offMs)} DNS off</b><span>${sub}</span></div>
    </div>`;
  }

  async function ensureFineData(invoke) {
    if (fineLoaded) return;
    fineLoaded = true;
    try {
      const c = await invoke('activity_categories_load');
      if (c && typeof c === 'object' && c.items && typeof c.items === 'object') fineCache = c;
    } catch { /* categories.json is optional */ }
    try {
      const i = await invoke('activity_icons_load');
      if (i && typeof i === 'object') iconMap = i.icons && typeof i.icons === 'object' ? i.icons : i;
    } catch { /* icons.json is optional */ }
  }

  function appIcon(name) {
    const src = iconMap[String(name).toLowerCase()];
    if (typeof src === 'string' && /^data:image\/(png|x-icon|vnd\.microsoft\.icon|svg\+xml|jpeg|webp|gif);base64,[A-Za-z0-9+/=]+$/.test(src)) {
      return `<img class="app-ico" src="${src}" alt="">`;
    }
    const letter = String(name).trim().charAt(0).toUpperCase() || '?';
    return `<span class="app-ico letter" aria-hidden="true">${esc(letter)}</span>`;
  }

  function renderHero(fine, range, selectedDay) {
    const statsEl = $('pStats');
    statsEl.classList.add('ratio-mode');
    const total = fine.total;
    if (!total) {
      const when = selectedDay != null ? `on ${wday(selectedDay)}` : range === 'today' ? 'today' : 'this week';
      statsEl.innerHTML = `<div class="hero-line"><span class="hero-total v" data-total>0m</span><span class="hero-sent">on screens ${when}.</span></div>`;
      return;
    }
    const building = fine.groups.create || 0;
    const drift = fine.groups.drift || 0;
    const topCats = fine.categories.slice(0, TOP_CATS);
    const restCats = fine.categories.slice(TOP_CATS);
    const major = fine.categories.filter((c) => c.ms / total >= 0.03);
    const minor = fine.categories.filter((c) => c.ms / total < 0.03);
    const minorSeg = (list, bg, label) => {
      const ms = list.reduce((n, c) => n + c.ms, 0);
      if (!list.length) return '';
      const dim = catFilter && !list.some((c) => c.id === catFilter) ? ' dim' : '';
      return `<span class="thin-seg${dim}" style="flex:${ms};background:${bg}" data-tip="${esc(label)} <span class='k'>${Stats.fmt(ms)}</span>"></span>`;
    };
    statsEl.innerHTML = `
      <div class="hero-line">
        <span class="hero-total v" data-total>${Stats.fmt(total)}</span>
        <span class="hero-sent">on screens, <b data-build-pct>${Stats.fmt(building)} building</b>${drift >= 60e3 ? `, <span class="hero-drift">${Stats.fmt(drift)} drifting</span>` : ''}.</span>
      </div>
      <div class="thin-bar">
        ${major.map((c) => {
          const share = Math.round((c.ms / total) * 100);
          const dim = catFilter && catFilter !== c.id ? ' dim' : '';
          return `<button type="button" class="thin-seg${dim}" data-dcat="${esc(c.id)}" style="flex:${c.ms};background:${c.color}" data-tip="${esc(esc(c.label))} <span class='k'>${Stats.fmt(c.ms)} · ${share}%</span>" aria-label="${esc(c.label)} ${Stats.fmt(c.ms)}"></button>`;
        }).join('')}
        ${minorSeg(minor.filter((c) => c.group !== 'drift'), '#27272a', 'Other categories')}
        ${minorSeg(minor.filter((c) => c.group === 'drift'), '#7c7c85', 'Other drifting')}
      </div>
      <div class="cat-leg">
        ${topCats.map((c) => `<button type="button" class="${catFilter === c.id ? 'on' : catFilter ? 'dim' : ''}" data-dcat="${esc(c.id)}"><i style="background:${c.color}"></i>${esc(c.label)} <span class="m">${Stats.fmt(c.ms)}</span></button>`).join('')}
        ${restCats.length ? `<button type="button" class="cat-more" data-fine-more>${showMoreCats ? 'Less' : `+${restCats.length} more`}</button>` : ''}
      </div>
      ${restCats.length && showMoreCats ? `<div class="cat-leg rest">${restCats.map((c) => `<button type="button" class="${catFilter === c.id ? 'on' : catFilter ? 'dim' : ''}" data-dcat="${esc(c.id)}"><i style="background:${c.color}"></i>${esc(c.label)} <span class="m">${Stats.fmt(c.ms)}</span></button>`).join('')}</div>` : ''}`;
  }

  function renderAppRow(a, multiDevice, openSet, showOffOnly = false) {
    const devNames = multiDevice ? a.devices.map((d) => d.name) : [];
    const dev = devNames.filter((n, i, all) => all.length > 1 || n !== 'Laptop').join(' + ');
    const pages = a.items.filter((p) => p.ms >= 30e3 && p.title !== a.app);
    const mostlyOff = showOffOnly || (a.off >= 60e3 && a.off >= a.ms * 0.5);
    const offText = a.off >= 30e3 ? `${Stats.fmt(a.off)} with DNS off` : '';
    const tipParts = [
      `<span class='tip-dot' style='background:${a.color}'></span>${esc(esc(a.categoryLabel))}`,
      dev ? `<span class='k'>· ${esc(esc(dev))}</span>` : '',
      offText ? `<span class='tip-off'>· ${esc(offText)}</span>` : '',
    ].filter(Boolean).join(' ');
    const detailMeta = `
      <div class="st-meta">
        <span><i style="background:${a.color}"></i>${esc(a.categoryLabel)}</span>
        ${dev ? `<span class="st-meta-sep">·</span><span>${esc(dev)}</span>` : ''}
        ${offText ? `<span class="st-meta-sep">·</span><span class="st-meta-off">${esc(offText)}</span>` : ''}
      </div>`;
    const pagesHtml = pages.length >= 2
      ? `<div class="cat-pages">${pages.slice(0, 6).map((p) =>
          `<div class="cat-page"><span class="cat-page-title">${esc(p.title)}</span><span class="n">${Stats.fmt(p.ms)}</span></div>`
        ).join('')}</div>`
      : '';
    return `<details class="site st d-app-item" data-fit data-site="${esc(a.app)}"${openSet.has(a.app) ? ' open' : ''}>
      <summary class="st-row d-app-row" data-tip="${tipParts}">
        <span class="st-left">${appIcon(a.app)}<span class="st-name">${esc(a.app)}</span>${srcIcon(a, multiDevice)}</span>
        <span class="n${mostlyOff ? ' dns-off' : ''}" data-dns-off="${Math.round(a.off)}">${Stats.fmt(showOffOnly ? a.off : a.ms)}</span>
      </summary>
      <div class="st-detail">${detailMeta}${pagesHtml}</div>
    </details>`;
  }

  function renderScreenTime(fine, multiDevice) {
    const appsEl = $('pApps');
    const lblEl = $('pAppsLbl');
    const footEl = $('pAppsFoot');
    const allList = fine.apps.filter((a) => a.ms >= 30e3);
    const activeCat = catFilter ? fine.categories.find((c) => c.id === catFilter) : null;
    if (catFilter && !activeCat) catFilter = null;
    const list = catFilter ? allList.filter((a) => a.category === catFilter) : allList;

    lblEl.innerHTML = `<span class="bento-title">Screen time${activeCat ? `<button type="button" class="filter-chip" data-clear-cat>${esc(activeCat.label)} ×</button>` : ''}</span>`;
    if (!list.length) {
      appsEl.innerHTML = '<div class="bento-empty">Nothing recorded yet.</div>';
      if (footEl) footEl.innerHTML = '';
      if (footEl) footEl.hidden = true;
      return;
    }
    const open = new Set([...appsEl.querySelectorAll('details[open]')].map((d) => d.dataset.site));
    const shown = list.slice(0, 12);
    appsEl.innerHTML = shown.map((a) => renderAppRow(a, multiDevice, open)).join('');
    if (footEl) {
      if (allList.length > TOP_APPS) {
        footEl.hidden = false;
        footEl.innerHTML = `<button type="button" class="foot-btn" data-see-all>See all ${allList.length} apps</button>`;
      } else {
        footEl.hidden = true;
        footEl.innerHTML = '';
      }
    }
  }

  function renderExplorer(fine, multiDevice, range, days, selectedDay) {
    const expEl = $('explorerView');
    const statsMain = $('statsMain');
    if (!expEl || !statsMain) return;
    expEl.hidden = !explorerOpen;
    statsMain.hidden = explorerOpen;
    if (!explorerOpen) return;

    const allList = fine.apps.filter((a) => a.ms >= 30e3);
    const q = expSearch.trim().toLowerCase();
    let list = allList.filter((a) => {
      if (expFilter === 'create' && a.group !== 'create') return false;
      if (expFilter === 'drift' && a.group !== 'drift') return false;
      if (expFilter === 'off' && a.off < 30e3) return false;
      if (q && !a.app.toLowerCase().includes(q) && !a.categoryLabel.toLowerCase().includes(q)) return false;
      return true;
    });
    if (expFilter === 'off') list = [...list].sort((a, b) => b.off - a.off);

    const open = new Set([...expEl.querySelectorAll('details[open]')].map((d) => d.dataset.site));
    const scopeText = selectedDay != null ? wday(selectedDay) : range === 'week' ? 'This week' : 'Today';
    const wasFocused = document.activeElement?.id === 'expSearchIn';
    const caret = wasFocused ? document.activeElement.selectionStart : null;

    expEl.innerHTML = `
      <div class="exp-head">
        <button type="button" class="exp-back" data-exp-back>
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 18-6-6 6-6"/></svg>
          Screen time <span class="exp-sub">${esc(scopeText)} · ${list.length}</span>
        </button>
        <div class="exp-search">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#71717a" stroke-width="2"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>
          <input id="expSearchIn" placeholder="Search apps…" value="${esc(expSearch)}">
        </div>
      </div>
      <div class="exp-filters">
        <div class="exp-chips">
          <button type="button" class="exp-chip${expFilter === 'all' ? ' on' : ''}" data-ef="all">All (${allList.length})</button>
          <button type="button" class="exp-chip${expFilter === 'create' ? ' on' : ''}" data-ef="create">Building</button>
          <button type="button" class="exp-chip${expFilter === 'drift' ? ' on' : ''}" data-ef="drift">Drifting</button>
          <button type="button" class="exp-chip${expFilter === 'off' ? ' on' : ''}" data-ef="off">DNS off</button>
        </div>
      </div>
      <div class="exp-list">
        <div class="bento-card">
          ${list.length
            ? list.map((a) => renderAppRow(a, multiDevice, open, expFilter === 'off')).join('')
            : `<div class="bento-empty" style="text-align:center;padding:24px">No apps matching "${esc(expSearch)}".</div>`}
        </div>
      </div>`;

    const inp = $('expSearchIn');
    if (inp) {
      if (wasFocused) {
        inp.focus();
        const pos = caret != null ? caret : inp.value.length;
        inp.setSelectionRange(pos, pos);
      }
      inp.addEventListener('input', (e) => {
        expSearch = e.target.value;
        if (lastRenderArgs) renderFromSnapshot(lastRenderArgs);
      });
    }
  }

  // Fit Screen time rows to the height of the Pauses card (at least 5 rows, never hiding an open row).
  function fitScreenTime() {
    if (explorerOpen) return;
    const appsCard = $('pAppsCard'), pausesCard = $('pPausesCard'), appsEl = $('pApps');
    if (!appsCard || !pausesCard || !appsEl) return;
    const grid = appsCard.parentElement;
    const rows = [...appsEl.querySelectorAll('[data-fit]')];
    rows.forEach((r) => { r.hidden = false; });
    if (grid) grid.style.alignItems = 'start';
    const target = pausesCard.getBoundingClientRect().height;
    let visible = rows.length;
    for (let i = rows.length - 1; i >= 0 && visible > TOP_APPS; i--) {
      if (appsCard.getBoundingClientRect().height <= target + 1) break;
      if (rows[i].open) continue;
      rows[i].hidden = true;
      visible--;
    }
    rows.slice(0, TOP_APPS).forEach((r) => { r.hidden = false; });
    if (grid) grid.style.alignItems = '';
  }

  function bindDelegatedEvents() {
    if (listenersBound) return;
    listenersBound = true;

    document.addEventListener('click', (e) => {
      if (e.target.closest('[data-fine-more]')) {
        showMoreCats = !showMoreCats;
        if (lastRenderArgs) renderFromSnapshot(lastRenderArgs);
      } else if (e.target.closest('[data-dcat]')) {
        const id = e.target.closest('[data-dcat]').dataset.dcat;
        catFilter = catFilter === id ? null : id;
        if (lastRenderArgs) renderFromSnapshot(lastRenderArgs);
      } else if (e.target.closest('[data-clear-cat]')) {
        catFilter = null;
        if (lastRenderArgs) renderFromSnapshot(lastRenderArgs);
      } else if (e.target.closest('[data-dday]')) {
        const idx = Number(e.target.closest('[data-dday]').dataset.dday);
        selectedDayIdx = selectedDayIdx === idx ? null : idx;
        if (lastRenderArgs) renderFromSnapshot(lastRenderArgs);
      } else if (e.target.closest('[data-clear-day]')) {
        selectedDayIdx = null;
        if (lastRenderArgs) renderFromSnapshot(lastRenderArgs);
      } else if (e.target.closest('[data-see-all]')) {
        explorerOpen = true;
        expFilter = 'all';
        expSearch = '';
        if (lastRenderArgs) renderFromSnapshot(lastRenderArgs);
      } else if (e.target.closest('[data-exp-back]')) {
        explorerOpen = false;
        if (lastRenderArgs) renderFromSnapshot(lastRenderArgs);
      } else if (e.target.closest('[data-ef]')) {
        expFilter = e.target.closest('[data-ef]').dataset.ef;
        if (lastRenderArgs) renderFromSnapshot(lastRenderArgs);
      } else if (e.target.closest('[data-edit-limit]')) {
        window.DevicePanel?.openSettings?.('protection');
      } else if (e.target.closest('[data-see-all-pauses]')) {
        showAllPauses = !showAllPauses;
        if (lastRenderArgs) renderFromSnapshot(lastRenderArgs);
      } else if (e.target.closest('[data-pz]')) {
        const k = e.target.closest('[data-pz]').dataset.pz;
        openPause = openPause === k ? null : k;
        if (lastRenderArgs) renderFromSnapshot(lastRenderArgs);
      }
    });
    document.addEventListener('keydown', (e) => {
      const row = e.target.closest?.('[data-pz]');
      if (row && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); row.click(); }
    });
  }

  async function ensureRules(invoke) {
    if (rulesLoaded) return rulesDoc;
    try {
      const loaded = await invoke('activity_rules_load');
      if (loaded && typeof loaded === 'object' && Array.isArray(loaded.rules)) {
        rulesDoc = loaded;
      }
    } catch { /* optional */ }
    rulesLoaded = true;
    return rulesDoc;
  }

  function syncUncategorizedInbox(invoke, sum, now) {
    const inbox = ActivityClassifier.buildUncategorizedInbox(sum, now);
    const sig = JSON.stringify(inbox.items);
    if (sig === lastInboxSig) return;
    lastInboxSig = sig;
    invoke('activity_uncategorized_save', { inbox }).catch(() => {});
  }

  function renderTimeline(sources, range, today, days) {
    const sec = $('pTimelineSec') || $('pChart')?.parentElement;
    const legendEl = $('pLegend');
    if (legendEl) {
      legendEl.innerHTML = '';
      legendEl.hidden = true;
    }

    if (range === 'today') {
      $('pChart').innerHTML = '';
      if (sec) sec.hidden = true;
      return;
    }

    if (sec) sec.hidden = false;
    const daySums = days.map((d) => {
      const end = new Date(d);
      end.setDate(end.getDate() + 1);
      return ActivityClassifier.summarizeClassified(sources, d, end.getTime(), { userRules: rulesDoc });
    });
    const max = Math.max(...daySums.map((s) => s.active), 60 * Stats.MIN);
    const avgMs = daySums.reduce((n, s) => n + s.active, 0) / 7;
    const H = 72;
    const selChip = selectedDayIdx != null
      ? `<button type="button" class="filter-chip" data-clear-day>${wday(days[selectedDayIdx])} · Back to 7 days ×</button>`
      : '';

    $('pChart').innerHTML = `
      <div class="bento-card wk-card">
        <div class="bento-head">
          <span class="bento-title">Last 7 days <span class="wk-key"><i></i>DNS off</span>${selChip}</span>
          <span class="wk-avg">Avg ${Stats.fmt(avgMs)}/day</span>
        </div>
        <div class="wk-bars simple-week">${days.map((d, i) => {
          const s = daySums[i];
          const offH = s.off >= 30e3 ? Math.max(3, (H * Math.min(s.active || s.off, s.off)) / max) : 0;
          const onMs = Math.max(0, s.active - Math.min(s.active, s.off));
          const onH = s.active >= 30e3 ? Math.max(3, (H * onMs) / max) : 0;
          const tip = `${wday(d)} <span class='k'>${Stats.fmt(s.active)}</span>${s.off >= 30e3 ? ` <span class='tip-off'>· ${Stats.fmt(s.off)} DNS off</span>` : ''}`;
          return `<button type="button" class="col wk-col${selectedDayIdx === i ? ' sel' : ''}" data-dday="${i}" data-tip="${tip}">
            <span class="wk-v">${s.active >= 60e3 ? fmtBar(s.active) : '—'}</span>
            <div class="wk-stack">
              ${offH ? `<i class="wk-off" style="height:${offH.toFixed(1)}px"></i>` : ''}
              ${onH ? `<i class="wk-on" style="height:${onH.toFixed(1)}px"></i>` : ''}
            </div>
            <span class="wk-d${d === today ? ' today' : ''}">${wday(d)}</span>
          </button>`;
        }).join('')}</div>
      </div>`;
  }

  function renderPausesColumn(sources, allSources, from, to, withDay, multiDevice, scoped) {
    const collectPauses = (srcList) => {
      const out = [];
      for (const src of srcList) {
        const h = src.history;
        const boundTo = Math.min(to, h.now || to);
        const list = src.allPauses || h.pauses || [];
        for (const p of list) {
          if (p.end <= from || p.t >= boundTo) continue;
          const audit = ActivityClassifier.classifyPause(h, p, src.kind, rulesDoc);
          const appLabel = p.kind === 'app'
            ? (src.apps?.find((a) => a.package === p.package)?.label || p.package)
            : '';
          out.push({
            ...p,
            sourceNow: h.now || boundTo,
            sourceHistory: h,
            sourceId: src.id,
            device_name: multiDevice ? shortDevice(src) : '',
            app_label: appLabel,
            audit,
          });
        }
      }
      return out;
    };

    const pauses = collectPauses(sources);
    pauses.sort((a, b) => b.t - a.t);
    const allPauses = scoped && allSources ? collectPauses(allSources) : pauses;
    const offOf = (p) => Math.max(0, Math.min(p.end, p.sourceNow) - p.t);
    const offAllMs = allPauses.filter((p) => p.kind !== 'app').reduce((t, p) => t + offOf(p), 0);
    const driftCount = pauses.filter((p) => p.audit.hasDrift).length;

    const pLbl = $('pPausesLbl');
    if (pLbl) {
      pLbl.innerHTML = `<span class="bento-title">Pauses</span><button type="button" class="bento-action" data-edit-limit>Edit limit</button>`;
    }
    const pausesEl = $('pPauses');
    const footEl = $('pPausesFoot');
    pausesEl.className = `pz${withDay ? ' with-day' : ''}`;

    const days = withDay ? 7 : 1;
    const today = from === Stats.dayStart(Date.now()) && days === 1;
    const credit = today ? window.focusHabitCredit || 0 : 0;
    const peekCount = sources.reduce((count, src) => count + (src.history.peeks ?? []).filter((t) => t >= from && t < to).length, 0);
    const dnsPauseCount = pauses.filter((p) => p.kind !== 'app').length;
    const peeks = peekCount
      ? `<div class="pz-sum"><b>${peekCount} ${peekCount === 1 ? 'peek' : 'peeks'}</b> · ${dnsPauseCount} DNS ${dnsPauseCount === 1 ? 'pause' : 'pauses'}</div>`
      : '';
    const chart = dnsLimitChart(offAllMs, (limitMin() + credit) * Stats.MIN, days, scoped)
      + (credit ? `<div class="habit-credit">${limitMin()}m base + ${credit}m earned</div>` : '')
      + peeks;

    if (!pauses.length) {
      const empty = `<div class="bento-empty" style="border-top:1px solid var(--d-div)">No pauses ${withDay ? 'this week' : 'today'}.</div>`;
      pausesEl.innerHTML = chart + (peekCount ? '' : empty);
      if (footEl) { footEl.hidden = true; footEl.innerHTML = ''; }
      return 0;
    }

    const running = pauses.filter((p) => p.end > p.sourceNow);
    const shown = showAllPauses
      ? pauses
      : [...running, ...pauses.filter((p) => !running.includes(p)).sort((a, b) => offOf(b) - offOf(a))].slice(0, TOP_PAUSES);

    const rows = shown.map((p) => {
      const did = p.audit.items.length
        ? p.audit.items.map((x) => `<span class="${x.status === 'drift' && x.ms >= 2 * Stats.MIN ? 'drift' : ''}">${esc(x.app)} ${Stats.fmt(x.ms)}</span>`).join(', ')
        : '';
      const context = [p.device_name, p.kind === 'app' ? p.app_label : ''].filter(Boolean).map(esc).join(' · ');
      const key = `${p.sourceId}:${p.t}:${p.pause_id ?? ''}`;
      const isOpen = openPause === key;
      const live = p.end > p.sourceNow;
      const trail = live ? 'Paused now' : did ? `→ ${did}` : 'No recorded use';
      const feedback = [Stats.checkInLabel(p.check_in), Stats.validRating(p.rating) ? `${p.rating}/5` : '', Stats.validRating(p.rating) ? p.rating_note : ''].filter(Boolean).join(' · ');
      return `<div class="pz-row d-pz-row${p.audit.hasDrift ? ' drift' : ''}${withDay ? ' with-day' : ''}${isOpen ? ' open' : ''}" data-pz="${esc(key)}" role="button" tabindex="0" aria-expanded="${isOpen}">
        <span class="t d-pz-t">${withDay ? wday(p.t) + ' ' : ''}${time(p.t)}</span>
        <div class="pz-body">
          <div class="why d-pz-why" title="${esc(p.reason)}">${esc(p.reason)}</div>
          <div class="did d-pz-did did-line"><span class="did-apps">${trail}</span>${context ? `<span class="did-dev">&nbsp;· ${context}</span>` : ''}</div>
          ${feedback ? `<div class="pz-more pz-feedback">${esc(feedback)}</div>` : ''}
          ${isOpen ? `<div class="pz-more">Asked for ${p.minutes}m · DNS off ${Stats.fmt(offOf(p))}${p.audit.hasDrift ? ' · drifted from the reason' : ''}</div>` : ''}
        </div>
        <span class="m d-pz-m" title="Asked for ${p.minutes}m">${Stats.fmt(offOf(p))} <span class="pz-chev">${isOpen ? '▾' : '▸'}</span></span>
      </div>`;
    }).join('');

    const { hours, worth } = Stats.pauseFeedback(pauses, from, to);
    const worthText = worth && `Last ${worth.count} rated ${worth.count === 1 ? 'pause' : 'pauses'}${worth.domain ? ` ending on ${esc(worth.domain)}` : ''} · <b>${worth.average.toFixed(1)}/5</b> average`;
    const checkHours = hours.map(([hour, counts]) => `<span><b>${String(hour).padStart(2, '0')}:00</b> ${Object.entries(counts).map(([value, n]) => `${Stats.checkInLabel(value)} ×${n}`).join(', ')}</span>`).join('');
    pausesEl.innerHTML = `${chart}${driftCount ? `<div class="pz-sum"><b>${driftCount} drifted</b> from the reason you gave</div>` : ''}
      ${worthText ? `<div class="pz-sum">${worthText}</div>` : ''}
      ${checkHours ? `<div class="pz-sum"><b>Check-ins by hour</b><div class="pz-check-hours">${checkHours}</div></div>` : ''}
      <div class="d-pz-list">${rows}</div>`;
    if (footEl) {
      if (pauses.length > TOP_PAUSES) {
        footEl.hidden = false;
        footEl.innerHTML = `<button type="button" class="foot-btn" data-see-all-pauses>${showAllPauses ? 'Show biggest 3' : `See all ${pauses.length} pauses · newest first`}</button>`;
      } else {
        footEl.hidden = true;
        footEl.innerHTML = '';
      }
    }
    return pauses.length;
  }

  function renderFromSnapshot({ invoke, range, sources, allSources, scoped, from, to, today, days, now }) {
    bindDelegatedEvents();
    if (range !== lastRange) {
      lastRange = range;
      selectedDayIdx = null;
      explorerOpen = false;
    }

    if (!sources.length) {
      explorerOpen = false;
      const expEl = $('explorerView'), statsMain = $('statsMain');
      if (expEl) expEl.hidden = true;
      if (statsMain) statsMain.hidden = false;
      $('pStats').classList.add('ratio-mode');
      $('pStats').innerHTML = '<div class="hero-line"><span class="hero-total v" data-total>0m</span><span class="hero-sent">No devices selected.</span></div>';
      const sec = $('pTimelineSec') || $('pChart')?.parentElement;
      if (sec) sec.hidden = true;
      $('pChart').innerHTML = '';
      $('pAppsLbl').innerHTML = '<span class="bento-title">Screen time</span>';
      $('pApps').innerHTML = '<div class="bento-empty">No device data selected.</div>';
      const af = $('pAppsFoot'); if (af) { af.hidden = true; af.innerHTML = ''; }
      const pLbl = $('pPausesLbl'); if (pLbl) pLbl.innerHTML = '<span class="bento-title">Pauses</span>';
      $('pPauses').innerHTML = '<div class="bento-empty">No device data selected.</div>';
      const pf = $('pPausesFoot'); if (pf) { pf.hidden = true; pf.innerHTML = ''; }
      return;
    }

    const activeDay = range === 'week' && selectedDayIdx != null ? days[selectedDayIdx] : null;
    const viewFrom = activeDay != null ? activeDay : from;
    const viewTo = activeDay != null ? (() => { const d = new Date(activeDay); d.setDate(d.getDate() + 1); return d.getTime(); })() : to;
    const isMultiDay = range !== 'today' && activeDay == null;
    const multi = sources.length > 1;

    const sum = ActivityClassifier.summarizeClassified(sources, viewFrom, viewTo, { userRules: rulesDoc });
    const fine = ActivityClassifier.summarizeFine(sources, viewFrom, viewTo, { userRules: rulesDoc, cache: fineCache });

    renderHero(fine, range, activeDay);
    renderTimeline(sources, range, today, days);
    renderPausesColumn(sources, allSources, viewFrom, viewTo, isMultiDay, multi, scoped);
    renderScreenTime(fine, multi);
    renderExplorer(fine, multi, range, days, activeDay);
    requestAnimationFrame(fitScreenTime);
    syncUncategorizedInbox(invoke, sum, now);
  }

  async function renderSources(invoke, range, sources, allSources = null, scoped = false) {
    await ensureRules(invoke);
    await ensureFineData(invoke);
    const now = Date.now();
    const today = Stats.dayStart(now);
    const days = Array.from({ length: 7 }, (_, i) => {
      const d = new Date(today);
      d.setDate(d.getDate() - 6 + i);
      return d.getTime();
    });
    const from = range === 'today' ? today : days[0];
    const to = today + Stats.DAY;
    lastRenderArgs = { invoke, range, sources, allSources, scoped, from, to, today, days, now };
    renderFromSnapshot(lastRenderArgs);
  }

  async function render(invoke, range) {
    const now = Date.now();
    const today = Stats.dayStart(now);
    const days = Array.from({ length: 7 }, (_, i) => {
      const d = new Date(today);
      d.setDate(d.getDate() - 6 + i);
      return d.getTime();
    });
    const from = range === 'today' ? today : days[0];
    const to = today + Stats.DAY;
    const h = await invoke('history', { from, to });
    const sources = DeviceStats.sources(h, [], []);
    await renderSources(invoke, range, sources, sources, false);
  }

  async function renderDevices(invoke, range, devices, excluded) {
    const now = Date.now();
    const today = Stats.dayStart(now);
    const days = Array.from({ length: 7 }, (_, i) => {
      const d = new Date(today);
      d.setDate(d.getDate() - 6 + i);
      return d.getTime();
    });
    const from = range === 'today' ? today : days[0];
    const to = today + Stats.DAY;
    const laptop = await invoke('history', { from, to });
    const allSources = DeviceStats.sources(laptop, devices, []);
    const sources = DeviceStats.sources(excluded.includes('laptop') ? null : laptop, devices, excluded);
    const scoped = excluded.length > 0;
    await renderSources(invoke, range, sources, allSources, scoped);
  }

  function exitExplorer() {
    if (!explorerOpen) return false;
    explorerOpen = false;
    if (lastRenderArgs) renderFromSnapshot(lastRenderArgs);
    return true;
  }

  return { render, renderDevices, exitExplorer };
})();

if (typeof window !== 'undefined') window.Panel = Panel;
