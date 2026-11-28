const Stats = globalThis.Stats;

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const time = (t) => new Date(t).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false });
const keyFor = (id, start) => id ? `int:${id}` : `none:${start}`;
const rowKey = (row) => keyFor(row.id, row.start);
const noteKey = (note) => keyFor(note.intention_id, note.start);

let latest = null;
let editor = null;

function showEditor(view, row, button, text) {
  if (editor) {
    editor.form.querySelector('textarea').focus();
    return;
  }
  const rowEl = button.closest('.irow');
  const saved = rowEl.querySelector('.intent-note-copy');
  const form = document.createElement('form');
  form.className = 'intent-note intent-note-editor';
  form.innerHTML = `
    <textarea rows="2" maxlength="500" aria-label="Note for ${time(row.start)}" placeholder="What happened?"></textarea>
    <div class="intent-note-actions">
      <span class="intent-note-error" role="alert"></span>
      ${text ? '<button type="button" class="bento-action" data-note-remove>Remove</button>' : ''}
      <button type="button" class="bento-action" data-note-cancel>Cancel</button>
      <button type="submit" class="bento-action intent-note-save">Save note</button>
    </div>`;
  rowEl.append(form);
  if (saved) saved.hidden = true;
  button.setAttribute('aria-expanded', 'true');
  const input = form.querySelector('textarea');
  input.value = text;
  input.focus();
  input.setSelectionRange(text.length, text.length);

  const active = editor = { form, key: rowKey(row) };
  let busy = false;
  function close() {
    if (editor !== active) return;
    editor = null;
    if (latest) draw(latest, active.key);
  }

  async function save(text) {
    if (busy) return;
    const error = form.querySelector('[role="alert"]');
    const controls = form.querySelectorAll('button');
    busy = true;
    error.textContent = '';
    input.readOnly = true;
    controls.forEach((control) => { control.disabled = true; });
    try {
      const note = await view.invoke('intention_save_note', {
        note: { intention_id: row.id ?? null, start: row.start, end: row.end ?? view.boundTo, text },
      });
      const histories = new Set([view.history]);
      if (latest?.rows.has(active.key)) histories.add(latest.history);
      for (const history of histories) {
        history.intention_notes = (history.intention_notes ?? []).filter((item) => noteKey(item) !== active.key);
        if (note) history.intention_notes.push(note);
      }
      close();
    } catch {
      busy = false;
      input.readOnly = false;
      controls.forEach((control) => { control.disabled = false; });
      error.textContent = 'Couldn’t save. Try again.';
      if (editor === active) input.focus();
    }
  }

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    save(input.value.trim());
  });
  form.querySelector('[data-note-remove]')?.addEventListener('click', () => save(''));
  form.querySelector('[data-note-cancel]').addEventListener('click', close);
  form.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      if (!busy) close();
    } else if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      form.requestSubmit();
    }
  });
}

function draw(view, focusKey = document.activeElement?.dataset?.intentNote) {
  const { sec, rows, history, invoke } = view;
  const notes = new Map((history.intention_notes ?? []).map((note) => [noteKey(note), note.text]));
  const segClass = (kind) => ({ ice: ' class="ice"', muted: ' class="g"', drift: ' class="o"', away: ' class="h"' }[kind] || '');
  const renderDetail = (d) => {
    if (d.kind === 'focus') return `<span class="ice">${esc(d.text)}</span>`;
    if (d.kind === 'drift') return `<span class="o">${esc(d.text)}</span>`;
    return esc(d.text);
  };
  const rowsHtml = Array.from(rows, ([key, row]) => {
    const unplanned = row.kind === 'none';
    const tone = unplanned ? ' o' : '';
    const bars = row.segments.map((seg) => `<i${unplanned ? ' class="o"' : segClass(seg.kind)} style="flex:${seg.ms}"></i>`).join('');
    const details = row.details.map(renderDetail).join(' · ');
    const ext = row.extensions ? ` (+${row.extensions * 10}m)` : '';
    const sub = unplanned ? '' : `planned ${Stats.fmt(row.plannedMs)}${ext} · ${row.statusLabel}`;
    const rowType = unplanned ? 'none' : row.outcome || (row.running ? 'running' : 'done');
    const note = notes.get(key) || '';
    const label = note ? 'Edit note' : 'Add note';
    return `<div class="irow" data-intent-row="${esc(rowType)}">
      <span class="tm">${time(row.start)}</span>
      <div>
        <div class="what${tone}">${esc(row.label)}</div>
        ${row.doneWhen ? `<div class="det">Done when: ${esc(row.doneWhen)}</div>` : ''}
        <div class="mini">${bars}</div>
        ${details ? `<div class="det">${details}</div>` : ''}
      </div>
      <div class="num${tone}">
        ${Stats.fmt(row.actualMs)}
        ${sub ? `<small>${esc(sub)}</small>` : ''}
        <button type="button" class="bento-action intent-note-trigger" data-intent-note="${esc(key)}" aria-label="${label} for ${time(row.start)}" aria-expanded="false">${label}</button>
      </div>
      ${note ? `<p class="intent-note intent-note-copy">${esc(note)}</p>` : ''}
    </div>`;
  }).join('');

  sec.innerHTML = `<div class="bento-card">
    <div class="bento-head">
      <span class="bento-title">Intentions</span>
      <button type="button" class="bento-action" data-intent-new>New</button>
    </div>
    ${rows.size ? `<div class="intent-list">${rowsHtml}</div>` : '<div class="bento-empty">No intention set today.</div>'}
  </div>`;
  sec.onclick = (event) => {
    const button = event.target.closest('[data-intent-note]');
    if (!button || !invoke) return;
    const key = button.dataset.intentNote;
    showEditor(view, rows.get(key), button, notes.get(key) || '');
  };
  if (focusKey) {
    Array.from(sec.querySelectorAll('[data-intent-note]')).find((button) => button.dataset.intentNote === focusKey)?.focus();
  }
}

export function renderIntentions(sources, fine, range, from, to, { rulesDoc, fineCache, invoke, classifier = globalThis.ActivityClassifier } = {}) {
  const sec = document.getElementById('pIntentionsSec');
  if (!sec) return null;
  const laptop = sources.find((s) => s.id === 'laptop') || sources[0];
  if (range !== 'today' || !Array.isArray(laptop?.history?.intentions)) {
    sec.hidden = true;
    sec.innerHTML = '';
    latest = editor = null;
    return null;
  }

  const history = laptop.history;
  const sum = Stats.summarizeIntentions(history, from, to, {
    classifier,
    userRules: rulesDoc,
    cache: fineCache,
    focusRuns: fine?.focus?.runs,
  });
  const rows = new Map(sum.rows.map((row) => [rowKey(row), row]));
  latest = { sec, rows, history, invoke, boundTo: Math.min(to, history.now || to) };
  sec.hidden = false;
  // The panel refreshes while open. Keep the input and its focus until editing ends.
  if (!editor?.form.isConnected || !rows.has(editor.key)) {
    editor = null;
    draw(latest);
  }
  return sum;
}
