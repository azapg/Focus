import './habits.css';

const escapeHtml = (value) => String(value).replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const weekdays = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const holdMs = 650;
const stopKeys = ['Enter', ' '];

function cadence(habit) {
  const days = weekdays.filter((_, index) => habit.weekdays & (1 << index));
  const interval = days.length ? days.join(', ') : habit.interval === 1 ? 'Daily' : `Every ${habit.interval} days`;
  return habit.time === null ? interval : `${interval} · ${timeInput(habit.time)}`;
}

function timeInput(time) {
  return time == null ? '' : [Math.floor(time / 60), time % 60].map((n) => String(n).padStart(2, '0')).join(':');
}

function checkoff({ habit, overdue, grace, streak }, compact) {
  const status = overdue ? 'Overdue' : grace ? 'Missed last time' : 'Due';
  const unit = habit.interval === 1 && !habit.weekdays ? 'd' : '×';
  return `<div class="habit-hold" data-habit-row>
    <button type="button" class="habit-hold-target" data-habit-hold="${escapeHtml(habit.id)}" aria-label="Hold to complete ${escapeHtml(habit.name)}">
      <span><strong>${escapeHtml(habit.name)}</strong><small><b>${status}</b>${compact ? '' : ` · ${escapeHtml(cadence(habit))}`}</small></span>
      <span class="habit-reward">+${habit.reward}m</span>
    </button>
    ${compact ? '' : `<button type="button" class="habit-action habit-streak" aria-label="Streak for ${escapeHtml(habit.name)} ${streak}" data-habit-detail="${escapeHtml(habit.id)}">${streak}${unit}</button>`}
  </div>`;
}

function detail(row) {
  const today = new Date();
  return `<div class="habit-history">${row.week.map((status, index) => {
    const date = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 6 + index);
    return `<span>${date.toLocaleDateString(undefined, { weekday: 'short' })}<i class="${status}" aria-label="${status}"></i></span>`;
  }).join('')}</div>`;
}

function editor(habit) {
  const h = habit ?? { name: '', interval: 1, weekdays: 0, time: null, reward: 10 };
  const mode = h.weekdays ? 'weekly' : h.interval > 1 ? 'interval' : 'daily';
  return `<form class="habit-editor" data-habit-editor aria-label="Habit editor">
    <div class="habit-heading"><input name="name" required maxlength="80" aria-label="Habit name" placeholder="Habit name" value="${escapeHtml(h.name)}">
      <button type="submit" class="habit-action">${habit ? 'Save' : 'Add'}</button></div>
    <label>Cadence <select name="mode">${[['daily','Daily'],['interval','Every N days'],['weekly','Weekly']].map(([value, label]) =>
      `<option value="${value}" ${value === mode ? 'selected' : ''}>${label}</option>`).join('')}</select></label>
    <label data-habit-interval ${mode === 'interval' ? '' : 'hidden'}>Interval <input name="interval" type="number" min="2" max="365" value="${Math.max(2, h.interval)}"></label>
    <fieldset data-habit-days ${mode === 'weekly' ? '' : 'hidden'}><legend>Days</legend>${weekdays.map((day, index) =>
      `<label><input type="checkbox" name="weekday" value="${1 << index}" ${h.weekdays & (1 << index) ? 'checked' : ''} title="${day}">${day}</label>`).join('')}</fieldset>
    <label>Time <input type="time" name="time" value="${timeInput(h.time)}"></label>
    <label>Credit <select name="reward">${[5, 10, 15].map((n) => `<option value="${n}" ${n === h.reward ? 'selected' : ''}>+${n}m</option>`).join('')}</select></label>
    ${habit ? '<button type="button" class="habit-action" data-habit-remove>Remove</button>' : ''}
    <div class="habit-error" role="status" hidden></div>
  </form>`;
}

function swipe(element, dismiss, ignore) {
  let pointer = null, start = 0, distance = 0;
  element.onpointerdown = (event) => {
    if (event.button !== 0 || event.target.closest(ignore)) return;
    pointer = event.pointerId; start = event.clientX; distance = 0;
    element.setPointerCapture(pointer);
  };
  element.onpointermove = (event) => {
    if (event.pointerId !== pointer) return;
    distance = event.clientX - start;
    element.style.translate = `${distance}px`;
    element.style.opacity = Math.max(.35, 1 - Math.abs(distance) / 300);
  };
  element.onpointerup = (event) => {
    if (event.pointerId !== pointer) return;
    pointer = null;
    if (Math.abs(distance) >= 64) dismiss();
    else reset();
  };
  element.onpointercancel = reset;
  function reset() {
    pointer = null;
    element.style.translate = '';
    element.style.opacity = '';
  }
}

export function mountHabits(invoke, onChange) {
  const quick = document.getElementById('habitQuick');
  const trigger = document.getElementById('habitTrigger');
  const float = document.getElementById('habitFloat');
  let state = { rows: [], credit: 0, cap: 45 };
  let lockFeeds = false;
  let lockSetup = '';
  let showDone = true, expanded, editId, active, toast, toastTimer, pendingState;
  let sectionSlot, settingsSlot;
  let settling = false, request = 0;

  function refresh(next) {
    if (next) {
      if (settling || active) { pendingState = next; return; }
      if (JSON.stringify(state) === JSON.stringify(next)) return;
      state = next;
    }
    const rows = state.rows;
    const due = rows.filter((r) => r.due);
    const done = rows.filter((r) => r.completion);
    trigger.textContent = due.length ? `${due.length} due` : rows.length ? 'Done' : 'Habits';
    trigger.classList.toggle('habit-attention', !!due.length);
    quick.innerHTML = `<div class="habit-heading"><span>Habits</span><b class="${due.length ? 'habit-attention' : ''}">${due.length ? `${due.length} due` : 'Done'}</b></div>
      ${due.length ? due.map((row) => checkoff(row, true)).join('') : `<div class="habit-quiet">${rows.length ? 'All habits done today.' : 'No habits'}</div>`}`;
    if (sectionSlot) sectionSlot.innerHTML = `<section class="habit-section">
      <div class="habit-heading"><span>Habits <b class="${due.length ? 'habit-attention' : ''}">${due.length ? `${due.length} due` : `${done.length}/${rows.length}`}</b></span>
      <button class="habit-action" data-habit-new>New</button></div>
      ${due.length ? due.map((row) => checkoff(row, false) + (row.habit.id === expanded ? detail(row) : '')).join('') : rows.length ? '<div class="habit-quiet">All habits done today.</div>' : ''}
      ${done.length ? `<div class="habit-done"><button class="habit-action" type="button" data-habit-done aria-expanded="${showDone}">Done · ${done.length}</button>
        ${showDone ? done.map((row) => `<button class="habit-done-row" type="button" data-habit-detail="${escapeHtml(row.habit.id)}">
          <span>${escapeHtml(row.habit.name)}</span><small>${row.completion.away == null ? '' : `<span aria-label="Away from all screens ${row.completion.away} min">Away ${row.completion.away}m</span>`}
          <span>+${row.completion.reward}m</span></small></button>${row.habit.id === expanded ? detail(row) : ''}`).join('') : ''}</div>` : ''}
    </section>`;
    if (settingsSlot) settingsSlot.innerHTML = `<section class="habit-section">
      <div class="habit-heading"><b>Habits</b><button class="habit-action" data-habit-new>New habit</button></div>
      ${rows.map(({ habit }) => `<button class="habit-settings-row" data-habit-edit="${escapeHtml(habit.id)}"><span>${escapeHtml(habit.name)}
        <small>${escapeHtml(cadence(habit))}</small></span><small>+${habit.reward}m</small></button>`).join('')}
      <div class="habit-cap"><span>Daily credit cap${state.pending_cap ? `<small>+${state.pending_cap}m tomorrow</small>` : ''}</span>
        ${[30, 45, 60].map((n) => `<button type="button" class="habit-action" data-habit-cap="${n}" aria-pressed="${n === state.cap}">+${n}m</button>`).join('')}</div>
      <div class="habit-cap"><span>Lock feeds after a nudge${lockSetup ? `<small>${lockSetup}</small>` : ''}</span>
        <button type="button" class="habit-action" data-habit-feed-lock aria-pressed="${lockFeeds}">${lockFeeds ? 'On' : 'Off'}</button></div>
    </section>`;
    onChange(state.credit, due.length);
  }

  function setQuick(open) {
    quick.hidden = !open;
    trigger.setAttribute('aria-expanded', String(open));
    onChange(state.credit);
  }
  function setEditor(id) {
    editId = id;
    const host = document.getElementById('habitEditor');
    host.innerHTML = id === undefined ? '' : editor(state.rows.find((r) => r.habit.id === id)?.habit);
    host.hidden = id === undefined;
    if (!host.hidden) {
      const form = host.querySelector('form');
      swipe(form, () => setEditor(undefined), 'input,button,select,label');
      form.elements.name.focus();
    }
    onChange(state.credit);
  }
  function clearToast() {
    clearTimeout(toastTimer);
    toast = null;
    float.hidden = true;
    onChange(state.credit);
  }
  function showToast(name, credit, id) {
    clearTimeout(toastTimer);
    toast = id;
    float.innerHTML = `<div class="habit-toast"><span>${escapeHtml(name)} <small>+${credit}m</small></span>
      <button type="button" class="habit-action" data-habit-undo>Undo</button><i></i></div>`;
    float.hidden = false;
    swipe(float.firstElementChild, clearToast, 'button');
    toastTimer = setTimeout(clearToast, 5000);
    onChange(state.credit);
  }
  async function change(input) {
    const version = ++request;
    const result = await invoke('habits_change', { input });
    if (version === request) refresh(result.state);
    return result;
  }
  async function load() {
    const version = ++request;
    try {
      const next = await invoke('habits_view');
      if (next?.rows && version === request) refresh(next);
    } catch (error) { console.warn(error); }
  }
  function settle() {
    if (pendingState) {
      const next = pendingState;
      pendingState = undefined;
      refresh(next);
    }
  }
  function cancel() {
    if (!active) return;
    clearTimeout(active.timer);
    active.row.classList.remove('holding');
    active = null;
    settle();
  }
  function start(button) {
    if (active || settling) return;
    const row = button.closest('[data-habit-row]');
    row.classList.add('holding');
    const id = button.dataset.habitHold;
    const name = state.rows.find((r) => r.habit.id === id)?.habit.name;
    const prior = state.credit;
    active = { button, row, timer: setTimeout(async () => {
      active = null;
      settling = true;
      row.classList.replace('holding', 'leaving');
      try {
        const [result] = await Promise.all([change({ action: 'complete', id }), new Promise((resolve) => setTimeout(resolve, 280))]);
        settling = false;
        settle();
        if (result.event) showToast(name, Math.max(0, state.credit - prior), result.event.id);
        else row.classList.remove('leaving');
      } catch (error) {
        settling = false;
        row.classList.remove('leaving');
        settle();
        console.warn(error);
      }
    }, holdMs) };
  }
  document.addEventListener('pointerdown', (e) => {
    const button = e.target.closest('[data-habit-hold]');
    if (button && e.button === 0) start(button);
  });
  document.addEventListener('pointerout', (e) => {
    if (active && active.button.contains(e.target) && !active.button.contains(e.relatedTarget)) cancel();
  });
  document.addEventListener('pointerup', cancel);
  document.addEventListener('pointercancel', cancel);
  window.addEventListener('blur', cancel);
  document.addEventListener('keydown', (e) => {
    const button = e.target.closest('[data-habit-hold]');
    if (button && stopKeys.includes(e.key)) {
      e.preventDefault();
      if (!e.repeat) start(button);
    } else if (e.key === 'Escape' && editId !== undefined) {
      e.preventDefault();
      setEditor(undefined);
    } else if (e.key === 'Escape' && !quick.hidden) {
      e.preventDefault();
      setQuick(false);
    }
  });
  document.addEventListener('keyup', (e) => { if (stopKeys.includes(e.key)) cancel(); });
  document.addEventListener('focusout', (e) => { if (active?.button === e.target) cancel(); });
  trigger.onclick = () => setQuick(quick.hidden);
  swipe(quick, () => setQuick(false), 'button');
  document.addEventListener('click', (e) => {
    const button = e.target.closest('button');
    if (!button) return;
    if ('habitNew' in button.dataset) setEditor(null);
    if ('habitEdit' in button.dataset) setEditor(button.dataset.habitEdit);
    if ('habitDetail' in button.dataset) {
      const id = button.dataset.habitDetail;
      expanded = expanded === id ? null : id;
      refresh();
    }
    if ('habitDone' in button.dataset) { showDone = !showDone; refresh(); }
    if ('habitCap' in button.dataset) change({ action: 'cap', minutes: +button.dataset.habitCap }).catch(console.warn);
    if ('habitFeedLock' in button.dataset) {
      invoke('habits_feed_lock', { enabled: !lockFeeds }).then(updateLockSetting).catch(console.warn);
    }
    if ('habitRemove' in button.dataset) change({ action: 'remove', id: editId }).then(() => setEditor(undefined)).catch(console.warn);
    if ('habitUndo' in button.dataset && toast) {
      change({ action: 'undo', id: toast }).then(clearToast).catch(console.warn);
    }
  });
  document.addEventListener('change', (e) => {
    const form = e.target.closest('[data-habit-editor]');
    if (!form || e.target.name !== 'mode') return;
    form.querySelector('[data-habit-interval]').hidden = e.target.value !== 'interval';
    form.querySelector('[data-habit-days]').hidden = e.target.value !== 'weekly';
  });
  document.addEventListener('submit', async (e) => {
    if (!e.target.matches('[data-habit-editor]')) return;
    e.preventDefault();
    const form = new FormData(e.target);
    const mode = form.get('mode');
    const days = mode === 'weekly' ? form.getAll('weekday').reduce((mask, day) => mask | Number(day), 0) : 0;
    const time = form.get('time').split(':').map(Number);
    const error = e.target.querySelector('[role=status]');
    try {
      if (mode === 'weekly' && !days) throw new Error('Select at least one day');
      await change({ action: 'save', id: editId, name: form.get('name'), time: time.length === 2 ? time[0] * 60 + time[1] : null,
        weekdays: days, interval: mode === 'interval' ? +form.get('interval') : 1, reward: +form.get('reward') });
      setEditor(undefined);
    } catch (e) {
      error.hidden = false;
      error.textContent = e.message ?? String(e);
    }
  });

  new MutationObserver(() => {
    const nextSection = document.getElementById('habitSection');
    const nextSettings = document.getElementById('habitSettings');
    if (sectionSlot !== nextSection || settingsSlot !== nextSettings) {
      sectionSlot = nextSection;
      settingsSlot = nextSettings;
      refresh();
    }
  }).observe(document.getElementById('devicePanelRoot'), { childList: true, subtree: true });
  refresh();
  function updateLockSetting(setting) {
    const enabled = setting?.enabled === true;
    const setup = !enabled || setting.browser_ready ? '' : setting.installed
      ? 'Reload Focus on each browser’s Extensions page' : 'Connect the Focus browser extension';
    if (enabled !== lockFeeds || setup !== lockSetup) {
      lockFeeds = enabled;
      lockSetup = setup;
      refresh();
    }
  }
  const loadLockSetting = () => invoke('habits_feed_lock').then(updateLockSetting).catch(console.warn);
  loadLockSetting();
  setInterval(() => { if (lockFeeds && settingsSlot) loadLockSetting(); }, 5000);
  load();
  return { load, refresh, setQuick, setEditor };
}
