import { mountHabits } from "./habits.js";

const T = window.__TAURI__;
const invoke = (c, a) => T.core.invoke(c, a);
const w = document.querySelector('.w'), inp = w.querySelector('.reason input'), drain = w.querySelector('.drain'),
      count = w.querySelector('.count'), goBtn = w.querySelector('.go'), chips = [...w.querySelectorAll('.chips .btn')];
const SIZE = { idle: [44, 6], hover: [0, 36], intent: [0, 36], form: [380, 70], paused: [176, 36] };
const pick = w.querySelector('.pick');
const intentBtn = document.getElementById('intentBtn');
const intentPill = document.getElementById('intentPill');
const intentOffPlan = document.getElementById('intentOffPlan');
const intentInput = document.getElementById('intentInput');
const intentGo = document.getElementById('intentGo');
const intentCard = document.getElementById('intentCard');
const escHtml = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmtClock = (ms) => new Date(ms).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false });
let intentionView = null, lastHistorySpans = [], offPlan = null;
let intentCardMode = null, intentDraft = null, intentEditing = false;
let intentParsing = false, intentParseSeq = 0, intentCardBusy = false, intentCardError = '', lastIntentCardKey = '';
let checkIn = null, keyboardPauseForm = false;
const checkInButtons = Object.entries(Stats.checkIns).map(([value, label]) => {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'btn';
  button.textContent = label;
  button.setAttribute('aria-pressed', 'false');
  button.onclick = () => {
    checkIn = value;
    checkInButtons.forEach(b => b.setAttribute('aria-pressed', String(b === button)));
  };
  document.getElementById('dockCheckIn').append(button);
  return button;
});
const ratingBox = document.getElementById('pauseRating');
const ratingNote = ratingBox.querySelector('input');
const ratingError = document.getElementById('pauseRatingError');
let ratingBusy = false, answeredPrompt;
let view = { paused_at: 0, paused_until: 0 }, inside = false, leaveT, busy = false;
const panel = document.getElementById('panel');
const judgeSheet = document.getElementById('dockPauseJudge');
panel.style.setProperty('--maxh', `${Math.min(innerHeight, screen.availHeight) - 66}px`);
let earnedCredit = 0;
let habitDue = 0, habitOverdue = 0, liveFocusState = null, activeDeepFocus = false;
const habitOverlays = ["habitQuick", "habitEditor", "habitFloat"].map((id) => document.getElementById(id));
let panelOpen = false, range = 'today', panelTimer, panelOpening = 0;
const nudgeButton = document.getElementById('habitNudge');
let nudgeText = null, nudgePointer = null;

const paused = () => view.paused_until > Date.now();
// Hover width depends on today's numbers, so measure the content instead of hardcoding it.
// The time buttons animate their width when leaving the reason form, so their natural
// width is remembered once (while idle) instead of measured mid-animation.
const chipsEl = w.querySelector('.chips');
const dnsSetup = w.querySelector('.dns-setup');
const judgeBtn = w.querySelector('.judge'), scopeBtn = w.querySelector('.scope');
const scopeReadout = w.querySelector(".scope-readout");
const scopeTime = scopeReadout.querySelector(".judge-time");
let judgeEnabled = false;
function syncJudgeButtons() {
  const scoped = view.scoped_until != null;
  const needsSetup = view.dns_configured === false;
  dnsSetup.hidden = !needsSetup;
  judgeBtn.hidden = scoped || needsSetup;
  scopeBtn.hidden = !scoped;
  w.toggleAttribute('data-scope', scoped);
  scopeReadout.hidden = !scoped;
  DevicePanel.scopedServices(scopeReadout.querySelector('.judge-services'), view.scoped_services ?? []);
  syncScopeClock();
  chipsEl.hidden = judgeEnabled || scoped || needsSetup;
  if (scoped || w.dataset.state === 'hover') setState('hover');
}
function syncScopeClock() {
  if (view.scoped_until == null) return;
  const left = Math.max(0, Math.ceil((view.scoped_until - Date.now()) / 1000));
  scopeTime.textContent = left ? `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}` : 'Restoring';
  const label = left ? 'End' : 'Retry cleanup';
  if (scopeBtn.textContent !== label) {
    scopeBtn.textContent = label;
    if (w.dataset.state === 'hover') setState('hover');
  }
}
let chipsW = 0;
document.fonts.ready.then(() => { chipsW = chipsEl.offsetWidth; });
function hoverWidth() {
  const kids = [...pick.children].filter((k) => getComputedStyle(k).display !== 'none');
  return kids.reduce((n, k) => n + (k === chipsEl && chipsW ? chipsW : k.offsetWidth), 0) + 6 * (kids.length - 1) + 6 + 4 + 2;
}
function setState(s) {
  if (view.scoped_until != null && !paused()) s = "hover";
  w.dataset.state = s;
  const bw = s === 'hover'
    ? hoverWidth()
    : s === 'intent'
      ? Math.min(580, Math.max(360, innerWidth - 24))
      : SIZE[s][0];
  w.style.width = s === 'hover' || s === 'form' || s === 'intent' ? bw + 'px' : '';
  syncRatingPrompt();
  syncIntentionCard();
  reportHit();
}
// Tell Rust which part of the window is "solid"; clicks anywhere else pass through.
function reportHit() {
  const s = w.dataset.state, bw = s === 'hover' || s === 'intent' ? parseFloat(w.style.width) || 0 : SIZE[s][0];
  const bh = SIZE[s][1], pad = s === 'idle' ? 18 : 8;
  let hw = bw + pad * 2, h = 8 + bh + pad;
  nudgeButton.hidden = !nudgeText || panelOpen || !judgeSheet.hidden || !intentCard.hidden
    || s === 'form' || s === 'intent' || paused() || view.scoped_until != null;
  if (!nudgeButton.hidden) {
    hw = Math.max(hw, nudgeButton.offsetWidth + 16);
    h = Math.max(h, nudgeButton.offsetTop + nudgeButton.offsetHeight + 8);
  }
  const [quick, , toast] = habitOverlays;
  const visibleHabits = habitOverlays.filter(el => !el.hidden);
  const stackRating = !ratingBox.hidden && (panelOpen || visibleHabits.length > 0);
  const ratingSpace = stackRating ? ratingBox.offsetHeight + 16 : 0;
  const toastSpace = stackRating && !toast.hidden && (!panelOpen || !quick.hidden) ? toast.offsetHeight + 8 : 0;
  document.body.style.setProperty('--pause-rating-space', (ratingSpace + toastSpace) + 'px');
  let overlayBottom = 0;
  if (panelOpen) {
    hw = Math.max(hw, panel.offsetWidth + 16);
    overlayBottom = Math.max(overlayBottom, panel.offsetTop + panel.offsetHeight);
    document.querySelectorAll('[data-slot=dropdown-menu-content],[data-slot=select-content]').forEach(el => { const b = el.getBoundingClientRect(); hw = Math.max(hw,2 * Math.max(innerWidth / 2 - b.left,b.right - innerWidth / 2) + 16); h = Math.max(h,b.bottom + 8); });
  }
  if (!toast.hidden) {
    const anchor = !quick.hidden ? quick : panelOpen ? panel : w;
    const bottom = anchor.getBoundingClientRect().bottom;
    const top = panelOpen && quick.hidden ? bottom - toast.offsetHeight - 14 : bottom + 8;
    toast.style.top = Math.min(top, innerHeight - toast.offsetHeight - 12 - ratingSpace) + 'px';
  }
  for (const el of visibleHabits) {
    const bounds = el.getBoundingClientRect();
    hw = Math.max(hw, bounds.width + 16);
    overlayBottom = Math.max(overlayBottom, bounds.bottom);
  }
  h = Math.max(h, overlayBottom + 8);
  ratingBox.style.top = stackRating
    ? Math.min(overlayBottom + 8, innerHeight - ratingBox.offsetHeight - 8) + 'px' : '';
  if (!judgeSheet.hidden) {
    hw = Math.max(hw, judgeSheet.offsetWidth + 16);
    h = Math.max(h, judgeSheet.offsetTop + judgeSheet.offsetHeight + 8);
  }
  if (!intentCard.hidden) {
    hw = Math.max(hw, intentCard.offsetWidth + 16);
    h = Math.max(h, intentCard.offsetTop + intentCard.offsetHeight + 8);
  }
  if (!ratingBox.hidden) {
    hw = Math.max(hw, ratingBox.offsetWidth + 16);
    h = Math.max(h, ratingBox.offsetTop + ratingBox.offsetHeight + 8);
  }
  invoke('set_hit', { x: (innerWidth - hw) / 2, y: 0, w: hw, h });
}
function reset() {
  keyboardPauseForm = false;
  chips.forEach(c => c.classList.remove('on'));
  checkIn = null;
  checkInButtons.forEach(b => b.setAttribute('aria-pressed', 'false'));
  inp.value = ''; inp.placeholder = 'Why?';
  clearInterval(waitTimer); goBtn.disabled = false;
}

const ratingKey = prompt => prompt ? `${prompt.paused_at}/${prompt.pause_id ?? ''}` : null;

function syncRatingPrompt() {
  const prompt = view.rating_prompt;
  const expired = !prompt || ratingKey(prompt) === answeredPrompt || Date.now() >= prompt.expires_at;
  const hidden = expired || !judgeSheet.hidden;
  if (ratingBox.hidden !== hidden) {
    const restoreFocus = expired && ratingBox.contains(document.activeElement);
    ratingBox.hidden = hidden;
    reportHit();
    if (restoreFocus && !panelOpen && w.dataset.state !== 'form' && document.hasFocus()) {
      invoke('return_focus');
    }
  }
}

async function respondToRating(rating) {
  const prompt = view.rating_prompt;
  if (!prompt || ratingBusy) return;
  ratingBusy = true;
  ratingError.hidden = true;
  try {
    const next = await invoke('rate_pause', { pausedAt: prompt.paused_at, pauseId: prompt.pause_id, rating, note: ratingNote.value });
    answeredPrompt = ratingKey(prompt);
    apply(next);
  } catch (error) {
    ratingError.textContent = String(error);
    ratingError.hidden = false;
  } finally {
    ratingBusy = false;
    reportHit();
  }
}

for (let score = 1; score <= 5; score++) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'btn';
  button.textContent = score;
  button.setAttribute('aria-label', `${score} out of 5`);
  button.onclick = () => respondToRating(score);
  document.getElementById('pauseRatingScores').append(button);
}
document.getElementById('dismissPauseRating').onclick = () => respondToRating(null);
ratingNote.addEventListener('pointerdown', () => invoke('grab_focus').then(() => {
  if (!ratingBox.hidden) ratingNote.focus();
}));
ratingBox.addEventListener('keydown', e => {
  if (e.key === 'Escape') {
    e.preventDefault();
    respondToRating(null);
  }
});

function render() {
  syncScopeClock();
  const p = paused();
  w.classList.toggle('p', p);
  if (p) {
    const left = Math.max(0, Math.ceil((view.paused_until - Date.now()) / 1000));
    count.textContent = `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
    const total = view.paused_until - view.paused_at;
    drain.style.width = (100 * (1 - (view.paused_until - Date.now()) / total)) + '%';
  } else {
    drain.style.width = '0';
    if (w.dataset.state === 'paused') setState(inside ? 'hover' : 'idle');
  }
  syncPillTone();
  syncRatingPrompt();
  syncIntentionUI(false);
}
function apply(v) {
  if (ratingKey(v.rating_prompt) !== ratingKey(view.rating_prompt)) {
    ratingNote.value = '';
    ratingError.hidden = true;
  }
  const wasScoped = view.scoped_until != null;
  view = v;
  syncJudgeButtons();
  if (wasScoped && view.scoped_until == null && !panelOpen) setState(inside ? "hover" : "idle");
  render();
  window.dispatchEvent(new CustomEvent("focus-state", { detail: v }));
}
setInterval(render, 1000);

T.event.listen('state', e => apply(e.payload));
// Today's totals shown in the pill.
const aOn = w.querySelector('.a-on'), aOff = w.querySelector('.a-off');
// The soft DNS-off limit is shared by every device, so today's total includes paired phones and TVs.
let pairedDevices = [];
let today = { offMs: 0, pauses: 0 };
let widgetRules = null;
async function refreshToday() {
  const from = Stats.dayStart(Date.now());
  const h = await invoke('history', { from, to: from + Stats.DAY });
  if (!widgetRules) {
    try {
      const r = await invoke('activity_rules_load');
      if (r && Array.isArray(r.rules)) widgetRules = r;
    } catch { /* rules.json is optional */ }
  }
  const s = Stats.summarize(h, from, from + Stats.DAY);
  let offMs = s.off, pauses = (h.pauses || []).filter((p) => p.t >= from).length;
  let allSources = [];
  try {
    allSources = DeviceStats.sources(h, pairedDevices, []);
    for (const src of DeviceStats.sources(null, pairedDevices, [])) {
      offMs += Stats.summarize(src.history, from, from + Stats.DAY).off;
      pauses += (src.history.pauses || []).filter((p) => p.t >= from).length;
    }
  } catch { /* device snapshots are optional */ }
  try {
    const focusSources = allSources.length ? allSources : [{ id: 'laptop', name: 'Laptop', kind: 'windows', history: h }];
    activeDeepFocus = Boolean(ActivityClassifier.detectFocusSessions(focusSources, from, from + Stats.DAY, { userRules: widgetRules }).activeRun);
  } catch { activeDeepFocus = false; }
  today = { offMs, pauses };
  lastHistorySpans = h.spans || [];
  aOn.textContent = Stats.fmt(s.active);
  syncIntentionUI(false);
  updateAllowance();
}
function publishPauseLimit() {
  const limits = FocusLimits.get();
  return invoke('habits_pause_limit', {
    limit: { minutes: limits.dnsLimitMin, pending: limits.pending.dnsLimitMin ?? null },
  }).catch(e => log(`publish pause limit: ${e}`));
}

function limitState() {
  const limitMin = FocusLimits.get().dnsLimitMin + earnedCredit;
  const offMin = today.offMs / Stats.MIN;
  const leftMin = limitMin - offMin;
  const frac = limitMin > 0 ? offMin / limitMin : 0;
  const over = offMin > limitMin + 0.5;
  const reached = over || leftMin < 1;
  return { limitMin, offMin, leftMin, frac, over, reached };
}

function syncPillTone() {
  const { frac, reached } = limitState();
  const noIntentionNag = Boolean(!intentionView?.active && intentionView?.nag_active);
  const isOffPlan = Boolean(intentionView?.active && offPlan);
  const tone = reached || habitOverdue > 0 || liveFocusState === 'unfocused' || view.dns_configured === false
    ? 'bad'
    : frac >= 0.75 || paused() || view.scoped_until != null || habitDue > 0 || liveFocusState === 'drifting' || noIntentionNag || isOffPlan
      ? 'warn'
      : activeDeepFocus
        ? 'focus'
        : 'ok';
  if (w.dataset.tone !== tone) w.dataset.tone = tone;
  aOn.classList.toggle('focus-ice', tone === 'focus');
  const focus = liveFocusState === 'drifting' || liveFocusState === 'unfocused' ? liveFocusState : '';
  if ((w.dataset.focus || '') !== focus) {
    if (focus) w.dataset.focus = focus;
    else delete w.dataset.focus;
  }
}

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
  const blocked = panelOpen || !judgeSheet.hidden || w.dataset.state === 'form' || w.dataset.state === 'intent' || paused() || view.scoped_until != null;
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
        classifier: window.ActivityClassifier,
        userRules: widgetRules,
        liveFocusState,
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
  if (w.dataset.state === 'intent') setState(inside ? 'hover' : 'idle');
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
      if (panelOpen) refreshPanel();
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
      if (panelOpen) refreshPanel();
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
      if (panelOpen) refreshPanel();
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
    setState(inside ? 'hover' : 'idle');
    invoke('return_focus');
  }
});
window.addEventListener('focus-open-intention', () => openIntentionInput('', false));

function updateAllowance() {
  const { offMs } = today;
  const { limitMin, offMin, leftMin, frac, over, reached } = limitState();
  const tail = over
    ? `${Stats.fmt((offMin - limitMin) * Stats.MIN)} over`
    : leftMin < 1
      ? 'limit reached'
      : `${Stats.fmt(leftMin * Stats.MIN)} left`;
  aOff.textContent = `${Stats.fmt(offMs)} off · ${tail}`;
  aOff.classList.toggle('bad', reached);
  aOff.classList.toggle('warn', !reached && frac >= 0.75);
  syncPillTone();
  syncChips();
  if (w.dataset.state === 'hover') setState('hover');
}
// Offer only pause lengths that fit: max pause, and what's left of today's limit (1m always allowed).
function syncChips() {
  const choices = FocusLimits.pauseChoices(today.offMs, Date.now(), earnedCredit);
  chips.forEach((c) => {
    const o = choices.find((x) => x.m === +c.dataset.m);
    c.hidden = !o;
    c.disabled = Boolean(o && !o.ok);
    c.title = o && !o.ok ? 'Limit reached · 1m available' : '';
  });
  chipsW = 0;
  requestAnimationFrame(() => { if (w.dataset.state !== 'form') chipsW = chipsEl.offsetWidth; });
}
const log = (m) => invoke('ui_log', { msg: m }).catch(() => {});
window.addEventListener('error', (e) => log(`error: ${e.message}`));
window.addEventListener('unhandledrejection', (e) => log(`error: ${e.reason}`));
document.addEventListener('pointerdown', (e) => log(`press at ${e.clientX},${e.clientY} on ${e.target.className || e.target.tagName} (state ${w.dataset.state})`), true);
// ---- stats panel ----
const amb = w.querySelector('.amb');
async function refreshPanel() {
  try {
    await DevicePanel.render(invoke, range);
    refreshToday().catch(() => {});
    document.getElementById('pDate').textContent = range === 'today'
      ? new Date().toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' }) : 'Last 7 days';
    reportHit();
  } catch (e) { log(`panel failed: ${e}`); }
}
async function openPanel(r) {
  DevicePanel.closePauseJudge();
  const opening = ++panelOpening;
  if (r) setRange(r);
  panelOpen = true; amb.classList.add('open');
  setState('hover');
  await refreshPanel();
  if (opening !== panelOpening) return false;
  panel.classList.add('open'); panel.scrollTop = 0;
  reportHit();
  clearInterval(panelTimer); panelTimer = setInterval(refreshPanel, 30e3);
  invoke('grab_focus');  // so Esc works and clicking elsewhere closes it
  log(`stats panel opened (${range})`);
  return true;
}
function closePanel() {
  if (!panelOpen) return;
  ++panelOpening;
  DevicePanel.close();
  panelOpen = false; amb.classList.remove('open'); panel.classList.remove('open');
  clearInterval(panelTimer);
  setState(inside ? 'hover' : 'idle');
}
function setRange(r) {
  range = r;
  panel.querySelectorAll('.seg button').forEach((b) => b.classList.toggle('on', b.dataset.r === r));
}

amb.onclick = () => { log('stats clicked'); panelOpen ? closePanel() : openPanel(); };
judgeBtn.onclick = () => DevicePanel.openPauseJudge();
window.addEventListener("dock-pause-open", () => {
  habits.setQuick(false);
  habits.setEditor(undefined);
  if (w.dataset.state === 'intent') cancelIntentionInput(false);
  intentCardMode = null;
  intentDraft = null;
  closePanel();
  setState("hover");
  syncRatingPrompt();
  syncIntentionCard();
});
window.addEventListener("dock-pause-closed", ({ detail }) => {
  syncRatingPrompt();
  setState(!detail.restoreFocus || inside ? 'hover' : 'idle');
  if (detail.restoreFocus && document.hasFocus()) invoke("return_focus");
});
scopeBtn.onclick = async () => {
  try { apply(await invoke('resume')); } catch (e) { log(String(e)); }
};

const updBtn = document.getElementById('dockUpdateBtn');
function syncUpdatePill(u) {
  const show = Boolean(u && u.available && (u.status === 'available' || u.status === 'downloading' || u.status === 'applying'));
  w.classList.toggle('has-update', show);
  if (updBtn) {
    updBtn.hidden = !show;
    if (show && u.available) {
      updBtn.title = 'Update available';
    }
  }
  if (w.dataset.state === 'hover') setState('hover');
}
if (updBtn) updBtn.onclick = async () => {
  log('update pill clicked');
  if (!panelOpen && !await openPanel()) return;
  DevicePanel.openUpdatePopover();
};
window.addEventListener('blur', () => {
  habits.setQuick(false);
  habits.setEditor(undefined);
  closePanel();
  DevicePanel.closePauseJudge();
  if (intentCardMode === 'manage') {
    intentCardMode = null;
    syncIntentionCard();
    reportHit();
  }
  if (w.dataset.state === 'intent' && !intentParsing && !intentInput.value.trim()) {
    cancelIntentionInput(false);
  }
  if (keyboardPauseForm && w.dataset.state === 'form' && !busy && !inp.value.trim() && !checkIn) {
    reset();
    setState(inside ? 'hover' : 'idle');
  }
});
const peekOpen = () => !judgeSheet.hidden && judgeSheet.querySelector('.dock-peek') !== null;
w.addEventListener('click', (event) => {
  // Pointer and keyboard activation both replace the snapshot before the dock action runs.
  if (peekOpen() && event.target.closest('button')) DevicePanel.closePauseJudge(false);
}, { capture: true });
document.addEventListener('pointerdown', (event) => {
  if (peekOpen() && !w.contains(event.target) && !judgeSheet.contains(event.target)) {
    DevicePanel.closePauseJudge();
  }
  if (intentCardMode === 'manage' && !w.contains(event.target) && !intentCard.contains(event.target)) {
    intentCardMode = null;
    syncIntentionUI(false);
  }
});
document.addEventListener('keydown', (event) => {
  if (event.key !== 'Escape' || event.defaultPrevented) return;
  if (w.dataset.state === 'intent') {
    event.preventDefault();
    cancelIntentionInput(true);
  } else if (intentCardMode === 'readback' || intentCardMode === 'manage') {
    event.preventDefault();
    intentCardMode = null;
    intentDraft = null;
    intentEditing = false;
    syncIntentionUI(false);
    setState(inside ? 'hover' : 'idle');
    invoke('return_focus');
  } else if (peekOpen()) {
    event.preventDefault();
    DevicePanel.closePauseJudge();
  } else if (panelOpen && habitOverlays.slice(0, 2).every(el => el.hidden)) {
    if (DevicePanel.escape()) {
      event.preventDefault();
      return;
    }
    closePanel();
    invoke('return_focus');
  }
});
w.querySelector('.lockb').onclick = () => { log('lock clicked'); closePanel(); setState('idle'); invoke('lock'); };

// Hover tooltips for anything with data-tip (hidden once a details row is clicked/expanded).
const tip = document.getElementById('tip');
const hideTip = () => { tip.style.opacity = 0; };
document.addEventListener('pointerdown', hideTip, true);
document.addEventListener('mousemove', (e) => {
  const el = e.target.closest('[data-tip]');
  if (!el || el.closest('details[open]')) { hideTip(); return; }
  tip.innerHTML = el.dataset.tip;
  tip.style.left = Math.max(4, Math.min(e.clientX + 12, innerWidth - tip.offsetWidth - 4)) + 'px';
  tip.style.top = (e.clientY + 14) + 'px'; tip.style.opacity = 1;
});

T.event.listen('peek-open-pause', () => {
  if (paused() || busy || w.dataset.state === 'form') return;
  clearTimeout(leaveT);
  closePanel();
  setState('hover');
  refreshToday().catch(() => {});
});
T.event.listen('cursor-in', () => {
  inside = true; clearTimeout(leaveT); refreshToday();
  invoke('intention_state').then(applyIntention).catch(() => {});
  if (w.dataset.state === 'idle') setState(paused() ? 'paused' : 'hover');
});
T.event.listen('cursor-out', () => {
  inside = false; clearTimeout(leaveT);
  leaveT = setTimeout(() => {
    if (panelOpen || !judgeSheet.hidden || !intentCard.hidden || habitOverlays.some(el => !el.hidden)) return;  // the panel stays until Esc / click elsewhere
    if (w.dataset.state === 'intent') return;
    if (w.dataset.state === 'form' && (keyboardPauseForm || inp.value.trim() || checkIn)) return;
    if (busy) return;
    setState('idle'); reset();
  }, 400);
});

let waitTimer = null, waitLeft = 0;
function startWait() {
  clearInterval(waitTimer);
  waitLeft = FocusLimits.waitFor(today.pauses);
  const tick = () => {
    goBtn.disabled = waitLeft > 0;
    goBtn.title = waitLeft > 0 ? `Wait ${waitLeft}s` : 'Pause';
    inp.placeholder = waitLeft > 0 ? `Why? · ${waitLeft}s` : 'Why?';
    if (waitLeft <= 0) clearInterval(waitTimer);
    waitLeft--;
  };
  tick();
  if (waitLeft >= 0) waitTimer = setInterval(tick, 1000);
}
chips.forEach(c => c.onclick = (event) => {
  if (c.disabled) return;
  habits.setQuick(false);
  closePanel();
  chips.forEach(x => x.classList.toggle('on', x === c));
  // Shrinking Peek's native hit region can emit cursor-out even if the pointer never moved.
  keyboardPauseForm = event.detail === 0;
  setState('form');
  startWait();
  invoke('grab_focus').finally(() => setTimeout(() => {
    if (w.dataset.state === 'form') inp.focus();
  }, 200));
});

function shake(msg) {
  inp.classList.remove('err'); void inp.offsetWidth; inp.classList.add('err');
  if (msg) { inp.value = ''; inp.placeholder = msg; }
}
async function go() {
  if (busy) return;
  const on = chips.find(c => c.classList.contains('on'));
  if (!on) return;
  if (inp.value.trim().length < 3) return shake('A reason is required');
  if (waitLeft >= 0 && goBtn.disabled) return;
  if (!checkIn) {
    checkInButtons[0].focus();
    return;
  }
  if (!FocusLimits.pauseChoices(today.offMs, Date.now(), earnedCredit).find(c => c.m === +on.dataset.m)?.ok) return shake("Limit reached");
  busy = true; goBtn.disabled = true;
  try {
    apply(await invoke('pause', { minutes: +on.dataset.m, reason: inp.value, checkIn }));
    reset(); clearInterval(waitTimer); setState(inside ? 'paused' : 'idle');
    refreshToday();
    invoke('return_focus');
  } catch (e) { shake(String(e)); }
  busy = false; goBtn.disabled = false;
}
goBtn.onclick = go;
inp.onkeydown = e => {
  if (e.key === 'Enter') go();
};
w.addEventListener('keydown', e => {
  if (e.key === 'Escape' && w.dataset.state === 'form') {
    reset(); setState(inside ? 'hover' : 'idle'); invoke('return_focus');
  }
});
w.querySelector('.resume').onclick = async () => {
  try { apply(await invoke('resume')); } catch (e) { console.error(e); }
};
document.addEventListener('contextmenu', e => e.preventDefault());

const overlayObserver = new ResizeObserver(reportHit);
[panel, ratingBox, judgeSheet, intentCard, ...habitOverlays].forEach(el => overlayObserver.observe(el));
let portalFrame;
new MutationObserver(() => { cancelAnimationFrame(portalFrame); portalFrame = requestAnimationFrame(reportHit); }).observe(document.body, { childList: true, subtree: true });
DevicePanel.init(invoke, (nextRange) => { if (nextRange) setRange(nextRange); if (panelOpen) refreshPanel(); }, reportHit);
const habits = mountHabits(invoke, (credit, due, overdue, walletActive) => {
  if (due !== undefined) {
    habitDue = due;
    habitOverdue = overdue || 0;
  }
  if (due === 0) nudgeText = null;
  const changed = credit !== earnedCredit || (walletActive !== undefined && window.focusWalletActive !== walletActive);
  earnedCredit = credit;
  window.focusHabitCredit = credit;
  if (walletActive !== undefined) window.focusWalletActive = walletActive;
  updateAllowance();
  reportHit();
  if (panelOpen && changed) refreshPanel();
});
document.getElementById('habitTrigger').addEventListener('click', () => {
  DevicePanel.closePauseJudge();
}, { capture: true });
document.getElementById('habitTrigger').addEventListener('click', () => {
  if (!habitOverlays[0].hidden) invoke('grab_focus');
});
T.event.listen('habits', e => habits.refresh(e.payload));
T.event.listen('wallet-updated', () => window.dispatchEvent(new Event('focus-wallet-updated')));
T.event.listen('habits-updated', () => habits.load());
T.event.listen('focus-state-transition', e => {
  liveFocusState = e.payload?.to ?? null;
  syncIntentionUI(true);
  syncPillTone();
  if (w.dataset.state === 'hover') setState('hover');
});
T.event.listen('intention-state', e => applyIntention(e.payload));
T.event.listen('habits-nudge', e => {
  nudgeText = e.payload;
  nudgeButton.textContent = nudgeText || '';
  nudgeButton.classList.toggle('locked', Boolean(nudgeText && /locked/i.test(nudgeText)));
  reportHit();
});
function dismissNudge() {
  nudgeText = null;
  reportHit();
}
nudgeButton.onclick = async () => {
  if (!nudgeText) return;
  dismissNudge();
  DevicePanel.close();
  if (!await openPanel('today')) return;
  document.getElementById('habitSection')?.scrollIntoView({ block: 'start' });
};
nudgeButton.onpointerdown = e => {
  nudgePointer = { id: e.pointerId, x: e.clientX, y: e.clientY };
  nudgeButton.setPointerCapture(e.pointerId);
};
nudgeButton.onpointerup = e => {
  if (nudgePointer?.id === e.pointerId
      && Math.max(Math.abs(e.clientX - nudgePointer.x), Math.abs(e.clientY - nudgePointer.y)) > 42) {
    dismissNudge();
  }
  nudgePointer = null;
};
nudgeButton.onpointercancel = () => { nudgePointer = null; };
setInterval(() => habits.load(), 30_000);
T.event.listen('devices', e => { pairedDevices = Array.isArray(e.payload) ? e.payload : pairedDevices; DevicePanel.receive(e.payload); habits.load(); });
invoke('device_list').then((d) => { if (Array.isArray(d)) { pairedDevices = d; refreshToday(); } }).catch(() => {});
T.event.listen('update', e => { syncUpdatePill(e.payload); DevicePanel.receiveUpdate(e.payload); });
T.event.listen('incoming-pairing', e => {
  DevicePanel.receiveIncomingPairing(e.payload);
  if (e.payload && !panelOpen) openPanel();
});
window.addEventListener('focus-pause-limit', () => { publishPauseLimit(); updateAllowance(); });
publishPauseLimit();
setState('idle');
window.addEventListener('pause-judge-settings', ({ detail }) => {
  judgeEnabled = Boolean(detail?.enabled);
  syncJudgeButtons();
});
invoke('pause_judge_settings').then(detail => {
  window.dispatchEvent(new CustomEvent('pause-judge-settings', { detail }));
}).catch(e => log(String(e)));
invoke('pause_judge_limits_sync', { limits: FocusLimits.get() }).catch(e => log(String(e)));
invoke('get_state').then(apply);
invoke('intention_state').then(applyIntention).catch(() => {});
setInterval(() => { if (intentionView?.active) refreshToday().catch(() => {}); }, 30_000);
invoke('updater_status').then((u) => { if (u) { syncUpdatePill(u); DevicePanel.receiveUpdate(u); } }).catch(() => {});
invoke('laptop_incoming_approval').then((p) => { if (p) DevicePanel.receiveIncomingPairing(p); }).catch(() => {});
refreshToday();
// `--panel` / `--panel week` opens the stats right away (handy for checking it).
invoke('startup_panel').then((r) => { if (r) openPanel(r); });
