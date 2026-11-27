const escHtml = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmtClock = (ms) => new Date(ms).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false });

export function createIntentionsDock(deps) {
  const { invoke, w, intentBtn, intentPill, intentOffPlan, intentInput, intentGo, intentCard,
    setState, reportHit, syncPillTone, refreshToday, refreshPanel, closePanel, habits, DevicePanel } = deps;
  const Stats = globalThis.Stats;
  const judgeSheet = deps.judgeSheet;
  const getPanelOpen = deps.getPanelOpen;
  const getView = deps.getView;
  const isPaused = deps.isPaused;
  const getInside = deps.getInside;
  const getLiveFocusState = deps.getLiveFocusState;
  const getWidgetRules = deps.getWidgetRules;
  let intentionView = null, lastHistorySpans = [], lastHistoryGrants = [], offPlan = null;
  let intentCardMode = null, intentDraft = null, intentEditing = false;
  let intentParsing = false, intentParseSeq = 0, intentCardBusy = false, intentCardError = '', lastIntentCardKey = '';

  function activeIntentionTimeUp() {
    const active = intentionView?.active;
    return Boolean(active && (intentionView?.time_up || Date.now() >= active.planned_until));
  }

  function fmtLeft(ms) {
    const mins = Math.max(1, Math.ceil(ms / Stats.MIN));
    return Stats.fmt(mins * Stats.MIN);
  }

  function syncIntentionCard() {
    const active = intentionView?.active || null;
    const timeUp = activeIntentionTimeUp();
    const blocked = getPanelOpen() || !judgeSheet.hidden || w.dataset.state === 'form' || w.dataset.state === 'intent' || isPaused() || getView().scoped_until != null;
    let kind = null;
    if (!blocked) {
      if (intentCardMode === 'readback' && intentDraft) kind = 'readback';
      else if (intentCardMode === 'manage' && active) kind = 'manage';
      else if (timeUp && active) kind = 'timeup';
    }
    if (!kind) {
      if (!intentCard.hidden) {
        intentCard.hidden = true;
        intentCard.innerHTML = '';
        lastIntentCardKey = '';
      }
      return;
    }
    let html = '', key = '';
    if (kind === 'readback') {
      const d = intentDraft;
      const until = fmtClock(d.until || (Date.now() + (d.planned_minutes || 60) * Stats.MIN));
      key = `rb:${d.label}:${until}:${d.done_when || ''}:${intentEditing}:${intentCardBusy}:${intentCardError}`;
      html = `<div class="intent-lbl">${intentEditing ? 'Update intention' : 'Got it'}</div>
        <div class="intent-chips">
          <span class="intent-chip">What <b>${escHtml(d.label)}</b></span>
          <span class="intent-chip">Until <b>${escHtml(until)}</b></span>
          ${d.done_when ? `<span class="intent-chip">Done when <b>${escHtml(d.done_when)}</b></span>` : ''}
        </div>
        ${intentCardError ? `<div class="intent-err" role="status">${escHtml(intentCardError)}</div>` : ''}
        <div class="intent-acts">
          <button type="button" class="btn ghost" data-intent-edit ${intentCardBusy ? 'disabled' : ''}>Edit</button>
          <button type="button" class="btn primary" data-intent-start ${intentCardBusy ? 'disabled' : ''}>${intentEditing ? 'Save' : 'Start'}</button>
        </div>`;
      intentCard.classList.remove('compact');
    } else if (kind === 'manage') {
      const leftText = timeUp ? "time's up" : `${fmtLeft(active.planned_until - Date.now())} left`;
      key = `mg:${active.id}:${active.label}:${leftText}:${active.done_when || ''}:${intentCardBusy}:${intentCardError}`;
      html = `<div class="intent-lbl">${escHtml(active.label)} · ${escHtml(leftText)}</div>
        ${active.done_when ? `<p class="intent-sub">Done when: ${escHtml(active.done_when)}</p>` : ''}
        ${intentCardError ? `<div class="intent-err" role="status">${escHtml(intentCardError)}</div>` : ''}
        <div class="intent-acts left">
          <button type="button" class="btn primary" data-intent-outcome="done" ${intentCardBusy ? 'disabled' : ''}>Done</button>
          <button type="button" class="btn" data-intent-edit ${intentCardBusy ? 'disabled' : ''}>Edit</button>
          <button type="button" class="btn ghost" data-intent-outcome="cleared" ${intentCardBusy ? 'disabled' : ''}>Clear</button>
        </div>`;
      intentCard.classList.add('compact');
    } else {
      const totalPlan = ((active.planned_minutes || 60) + (active.extensions || 0) * 10) * Stats.MIN;
      const question = Stats.doneWhenQuestion(active.done_when);
      key = `up:${active.id}:${active.extensions || 0}:${active.label}:${question}:${intentCardBusy}:${intentCardError}`;
      html = `<div class="intent-lbl">${escHtml(active.label)} · ${escHtml(Stats.fmt(totalPlan))}</div>
        <p class="intent-q">${escHtml(question)}</p>
        ${intentCardError ? `<div class="intent-err" role="status">${escHtml(intentCardError)}</div>` : ''}
        <div class="intent-acts left">
          <button type="button" class="btn primary" data-intent-outcome="done" ${intentCardBusy ? 'disabled' : ''}>Done</button>
          <button type="button" class="btn" data-intent-outcome="partly" ${intentCardBusy ? 'disabled' : ''}>Partly</button>
          <button type="button" class="btn" data-intent-extend ${intentCardBusy ? 'disabled' : ''}>10 more min</button>
          <button type="button" class="btn ghost" data-intent-outcome="no" ${intentCardBusy ? 'disabled' : ''}>No</button>
        </div>`;
      intentCard.classList.add('compact');
    }
    const wasHidden = intentCard.hidden;
    if (wasHidden || key !== lastIntentCardKey) {
      intentCard.innerHTML = html;
      intentCard.hidden = false;
      lastIntentCardKey = key;
    }
  }

  function syncIntentionUI(resize = true) {
    const active = intentionView?.active || null;
    const timeUp = activeIntentionTimeUp();
    const nagActive = Boolean(!active && intentionView?.nag_active);
    offPlan = active
      ? Stats.detectOffPlan(active, lastHistorySpans, Date.now(), {
          classifier: globalThis.ActivityClassifier,
          userRules: getWidgetRules(),
          liveFocusState: getLiveFocusState(),
          grants: lastHistoryGrants,
        })
      : null;

    let widthChanged = false;
    if (active) {
      if (!intentBtn.hidden) { intentBtn.hidden = true; widthChanged = true; }
      if (intentPill.hidden) { intentPill.hidden = false; widthChanged = true; }
      const labelEl = intentPill.querySelector('.intent-t');
      const leftEl = intentPill.querySelector('.intent-left');
      const nextLeft = timeUp ? "time's up" : fmtLeft(active.planned_until - Date.now());
      if (labelEl.textContent !== active.label) { labelEl.textContent = active.label; widthChanged = true; }
      if (leftEl.textContent !== nextLeft) { leftEl.textContent = nextLeft; widthChanged = true; }
      const nextOff = offPlan?.text || '';
      if (intentOffPlan.hidden !== !nextOff || intentOffPlan.textContent !== nextOff) {
        intentOffPlan.textContent = nextOff;
        intentOffPlan.hidden = !nextOff;
        widthChanged = true;
      }
    } else {
      if (!intentPill.hidden) { intentPill.hidden = true; widthChanged = true; }
      if (!intentOffPlan.hidden) { intentOffPlan.hidden = true; intentOffPlan.textContent = ''; widthChanged = true; }
      if (intentBtn.hidden) { intentBtn.hidden = false; widthChanged = true; }
      if (nagActive) {
        const mins = Math.max(15, Math.floor((intentionView.no_intention_active_ms || 0) / Stats.MIN));
        const html = `<span class="intent-dot" aria-hidden="true"></span>No intention · ${mins}m`;
        if (!intentBtn.classList.contains('warn') || intentBtn.innerHTML !== html) {
          intentBtn.classList.add('warn');
          intentBtn.innerHTML = html;
          widthChanged = true;
        }
      } else if (intentBtn.classList.contains('warn') || intentBtn.textContent !== 'What are you on?') {
        intentBtn.classList.remove('warn');
        intentBtn.textContent = 'What are you on?';
        widthChanged = true;
      }
    }
    syncIntentionCard();
    syncPillTone();
    if ((resize || widthChanged) && w.dataset.state === 'hover') setState('hover');
    else reportHit();
  }

  function applyIntention(next) {
    if (!next || typeof next !== 'object') return;
    intentionView = next;
    if (!next.active && intentCardMode === 'manage') intentCardMode = null;
    syncIntentionUI(true);
  }

  function openIntentionInput(prefill = '', editing = false) {
    habits.setQuick(false);
    habits.setEditor(undefined);
    DevicePanel.closePauseJudge(false);
    closePanel();
    intentEditing = editing;
    intentCardMode = null;
    intentDraft = null;
    intentCardError = '';
    intentParsing = false;
    intentInput.value = prefill;
    intentInput.disabled = false;
    intentGo.disabled = false;
    setState('intent');
    invoke('grab_focus').finally(() => setTimeout(() => {
      if (w.dataset.state === 'intent') {
        intentInput.focus();
        if (prefill) intentInput.select();
      }
    }, 60));
  }

  function cancelIntentionInput(restoreFocus = true) {
    ++intentParseSeq;
    intentParsing = false;
    intentEditing = false;
    intentInput.value = '';
    intentInput.disabled = false;
    intentGo.disabled = false;
    if (w.dataset.state === 'intent') setState(getInside() ? 'hover' : 'idle');
    if (restoreFocus) invoke('return_focus');
  }

  function fallbackLocalParse(raw) {
    const cleaned = String(raw || '').trim();
    const now = Date.now();
    return { label: cleaned.slice(0, 80), planned_minutes: 60, until: now + 60 * Stats.MIN, done_when: null, raw: cleaned, parse_source: 'local' };
  }

  async function submitIntentionInput() {
    if (intentParsing) return;
    const text = intentInput.value.trim();
    if (!text) {
      intentInput.classList.remove('err'); void intentInput.offsetWidth; intentInput.classList.add('err');
      return;
    }
    const seq = ++intentParseSeq;
    intentParsing = true;
    intentGo.disabled = true;
    try {
      let parsed = await invoke('intention_parse', { text });
      if (!parsed || typeof parsed !== 'object' || !parsed.label) {
        parsed = (await invoke('intention_parse_local', { text }).catch(() => null)) || fallbackLocalParse(text);
      }
      if (seq !== intentParseSeq) return;
      intentDraft = parsed;
      intentCardMode = 'readback';
      intentCardError = '';
      intentParsing = false;
      intentGo.disabled = false;
      setState('hover');
      requestAnimationFrame(() => intentCard.querySelector('[data-intent-start]')?.focus());
    } catch {
      if (seq !== intentParseSeq) return;
      const parsed = (await invoke('intention_parse_local', { text }).catch(() => null)) || fallbackLocalParse(text);
      intentDraft = parsed;
      intentCardMode = 'readback';
      intentCardError = '';
      intentParsing = false;
      intentGo.disabled = false;
      setState('hover');
      requestAnimationFrame(() => intentCard.querySelector('[data-intent-start]')?.focus());
    }
  }

  intentBtn.onclick = () => openIntentionInput('', false);
  intentPill.onclick = () => {
    if (!intentionView?.active) return;
    if (activeIntentionTimeUp()) {
      intentCardMode = null;
      syncIntentionUI(false);
      return;
    }
    intentCardMode = intentCardMode === 'manage' ? null : 'manage';
    intentCardError = '';
    syncIntentionUI(false);
    if (intentCardMode === 'manage') invoke('grab_focus');
  };
  intentGo.onclick = submitIntentionInput;
  intentInput.onkeydown = (e) => {
    if (e.key === 'Enter') { e.preventDefault(); submitIntentionInput(); }
    else if (e.key === 'Escape') { e.preventDefault(); cancelIntentionInput(true); }
  };
  intentCard.addEventListener('click', async (e) => {
    const btn = e.target.closest('button');
    if (!btn || intentCardBusy) return;
    if ('intentEdit' in btn.dataset) {
      const raw = intentDraft?.raw || intentionView?.active?.raw || intentionView?.active?.label || '';
      const editing = intentEditing || (intentCardMode === 'manage' && Boolean(intentionView?.active));
      openIntentionInput(raw, editing);
      return;
    }
    if ('intentStart' in btn.dataset && intentDraft) {
      intentCardBusy = true;
      intentCardError = '';
      syncIntentionCard();
      try {
        const cmd = intentEditing ? 'intention_edit' : 'intention_start';
        const next = await invoke(cmd, {
          label: intentDraft.label,
          plannedMinutes: intentDraft.planned_minutes,
          doneWhen: intentDraft.done_when ?? null,
          raw: intentDraft.raw,
          parseSource: intentDraft.parse_source ?? 'local',
        });
        intentCardMode = null;
        intentDraft = null;
        intentEditing = false;
        intentCardBusy = false;
        if (next) applyIntention(next);
        else syncIntentionUI(true);
        refreshToday().catch(() => {});
        if (getPanelOpen()) refreshPanel();
        invoke('return_focus');
      } catch (err) {
        intentCardBusy = false;
        intentCardError = String(err);
        syncIntentionCard();
        reportHit();
      }
      return;
    }
    if ('intentExtend' in btn.dataset) {
      intentCardBusy = true;
      intentCardError = '';
      syncIntentionCard();
      try {
        const next = await invoke('intention_extend', { minutes: 10 });
        intentCardMode = null;
        intentCardBusy = false;
        if (next) applyIntention(next);
        else syncIntentionUI(true);
        if (getPanelOpen()) refreshPanel();
        invoke('return_focus');
      } catch (err) {
        intentCardBusy = false;
        intentCardError = String(err);
        syncIntentionCard();
        reportHit();
      }
      return;
    }
    if (btn.dataset.intentOutcome) {
      const outcome = btn.dataset.intentOutcome;
      intentCardBusy = true;
      intentCardError = '';
      syncIntentionCard();
      try {
        const next = await invoke('intention_end', { outcome });
        intentCardMode = null;
        intentDraft = null;
        intentEditing = false;
        intentCardBusy = false;
        if (next) applyIntention(next);
        else syncIntentionUI(true);
        refreshToday().catch(() => {});
        if (getPanelOpen()) refreshPanel();
        invoke('return_focus');
      } catch (err) {
        intentCardBusy = false;
        intentCardError = String(err);
        syncIntentionCard();
        reportHit();
      }
    }
  });
  intentCard.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && (intentCardMode === 'readback' || intentCardMode === 'manage')) {
      e.preventDefault();
      intentCardMode = null;
      intentDraft = null;
      intentEditing = false;
      syncIntentionUI(false);
      setState(getInside() ? 'hover' : 'idle');
      invoke('return_focus');
    }
  });
  window.addEventListener('focus-open-intention', () => openIntentionInput('', false));
  const api = {
    syncCard: syncIntentionCard, syncUI: syncIntentionUI, apply: applyIntention,
    openInput: openIntentionInput, cancelInput: cancelIntentionInput,
    setHistory(spans, grants) { lastHistorySpans = spans || []; lastHistoryGrants = grants || []; },
    clearDraft() { intentDraft = null; intentEditing = false; },
    get view() { return intentionView; },
    get offPlan() { return offPlan; },
    get activeTimeUp() { return activeIntentionTimeUp(); },
    get hasActive() { return Boolean(intentionView?.active); },
    get cardMode() { return intentCardMode; },
    set cardMode(value) { intentCardMode = value; },
    get parsing() { return intentParsing; },
  };
  return api;
}
