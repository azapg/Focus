import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const context = vm.createContext({ Date, URL });
vm.runInContext(fs.readFileSync(new URL('../ui/stats.js', import.meta.url), 'utf8') + '\nglobalThis.stats = Stats;', context);
const S=context.stats, MIN=60000;
const span=(s,e,title='Page',url='https://github.com/example-org/sample-project')=>({s,e,app:'Chrome',label:'github.com',source:'chromium_extension',browser:{url,title}});

test('pause feedback averages the last eight rated endings on the same observed site', () => {
  const start = new Date(2026, 10, 22, 19).getTime();
  const pauses = Array.from({ length: 10 }, (_, i) => {
    const t = start + i * MIN, end = t + MIN;
    return { t, end, check_in: 'tired', rating: i < 2 ? 5 : 2,
      sourceHistory: { now: end + MIN, spans: [span(t, end, 'X', 'https://www.x.com/latest')] } };
  });
  const t = start + 12 * MIN, end = t + MIN;
  const appEnding = { t, end, rating: 5, sourceHistory: { now: end + MIN, spans: [
    span(t, end - 1000, 'X', 'https://x.com/latest'),
    { s: end - 1000, e: end, app: 'editor', label: 'Editor' },
  ] } };
  const old = { t, end, minutes: 1, reason: 'Old pause' };
  const active = { ...pauses[9], end_kind: null, rating: 5 };
  const result = S.pauseFeedback([...pauses, appEnding, old, active]);
  assert.equal(result.worth.domain, 'x.com');
  assert.equal(result.worth.count, 8);
  assert.equal(result.worth.average, 2);
  assert.equal(result.hours[0][0], 19);
  assert.equal(result.hours[0][1].tired, 11);
  assert.equal(S.pauseFeedback([old]).worth, null);
  assert.equal(S.pauseFeedback([appEnding]).worth.domain, '');
});
test('a cross-midnight pause contributes its check-in only to its start day', () => {
  const midnight = new Date(2026, 10, 23).getTime();
  const pause = { t: midnight - MIN, end: midnight + MIN, check_in: 'tired' };
  assert.equal(S.pauseFeedback([pause], midnight - S.DAY, midnight).hours[0][0], 23);
  assert.equal(S.pauseFeedback([pause], midnight, midnight + S.DAY).hours.length, 0);
});
test('page totals are subdivisions of foreground time, clipped to the selected range',()=>{
  const h={now:15*MIN,pauses:[],spans:[span(0,4*MIN),span(4*MIN,8*MIN,'Latest title'),span(8*MIN,10*MIN,'Issues','https://github.com/example-org/sample-project/issues'),{s:10*MIN,e:11*MIN,app:'Chrome',label:'Chrome',source:'window_title'}]};
  const result=S.summarize(h,MIN,11*MIN);
  assert.equal(result.active,10*MIN);
  const site=result.labels.find(l=>l.label==='github.com');
  assert.equal(site.ms,9*MIN);assert.equal(site.pages.length,2);assert.equal(site.pages[0].ms,7*MIN);assert.equal(site.pages[0].title,'Latest title');
  assert.equal(site.pages.reduce((n,p)=>n+p.ms,0),site.ms);
  assert.equal(result.labels.find(l=>l.label==='Chrome').ms,MIN);
});
test('inferred window titles do not become actual page entries',()=>{
  const s=span(0,MIN);s.source='window_title';
  assert.equal(S.summarize({now:MIN,pauses:[],spans:[s]},0,MIN).labels[0].pages.length,0);
});
test('DNS pause totals and reason aliases remain usable with hostname labels',()=>{
  const h={now:5*MIN,pauses:[{t:MIN,end:3*MIN,minutes:2,reason:'watch a video'}],spans:[span(0,5*MIN)]};
  const s=S.summarize(h,0,5*MIN);assert.equal(s.off,2*MIN);assert.equal(s.activeOff,2*MIN);assert.equal(s.active,5*MIN);
  assert.equal(S.mentioned('watch a video','www.youtube.com'),true);
  assert.equal(S.mentioned('check github','github.com'),true);
  assert.equal(S.mentioned('write code','github.com'),false);
});
test('a label named __proto__ cannot alter aggregation',()=>{
  const s=span(0,MIN);s.label='__proto__';assert.equal(S.summarize({now:MIN,pauses:[],spans:[s]},0,MIN).active,MIN);
});

test('only sites over five minutes separate from the residual browser bucket',()=>{
  const large=span(0,6*MIN),small=span(6*MIN,10*MIN,'Short visit','https://short.example/page');small.label='short.example';
  const exact=span(10*MIN,15*MIN,'Exactly five minutes','https://exact.example/page');exact.label='exact.example';
  const coarse={s:15*MIN,e:17*MIN,app:'Chrome',label:'Guessed website',source:'window_title'};
  const h={now:17*MIN,pauses:[],spans:[large,small,exact,coarse]};
  const result=S.summarize(h,0,h.now);
  assert.equal(result.labels.length,2);
  const chrome=result.labels.find(l=>l.label==='Chrome'),site=result.labels.find(l=>l.label==='github.com');
  assert.equal(site.ms,6*MIN);assert.equal(chrome.ms,11*MIN);assert.equal(chrome.untracked,2*MIN);assert.equal(chrome.expandable,true);
  assert.equal(chrome.pages.length,2);assert.ok(chrome.pages.every(p=>!p.url.includes('github.com')));
  assert.equal(result.labels.reduce((n,l)=>n+l.ms,0),result.active);
  const shorter=S.summarize(h,0,5*MIN);assert.equal(shorter.labels.length,1);assert.equal(shorter.labels[0].label,'Chrome');
});
test('browser time without URLs is still expandable',()=>{
  const result=S.summarize({now:MIN,pauses:[],spans:[{s:0,e:MIN,app:'Chrome',label:'Guessed site'}]},0,MIN);
  assert.equal(result.labels[0].label,'Chrome');assert.equal(result.labels[0].untracked,MIN);assert.equal(result.labels[0].expandable,true);
});

test('intentions summary splits deep focus, on-screen, away, drift, and 15m+ no-intention rows', () => {
  const classifierCtx = vm.createContext({ Date, URL });
  const classifierSrc = fs.readFileSync(new URL('../ui/activity-classifier.js', import.meta.url), 'utf8')
    .replace(/export default ActivityClassifier;\s*$/, '\nglobalThis.ActivityClassifier = ActivityClassifier;');
  vm.runInContext(classifierSrc, classifierCtx);
  const AC = classifierCtx.ActivityClassifier;

  const day = new Date(2026, 10, 27, 0, 0, 0, 0).getTime();
  const at = (h, m = 0) => day + (h * 60 + m) * MIN;
  const now = at(13, 34);
  const web = (s, e, label, url, title) => ({
    s, e, app: 'Chrome', label, source: 'chromium_extension',
    browser: { url, title, visibility: 'page', audible: false, tab_id: 1, window_id: 1, browser_pid: 1, host_pid: 2 },
  });

  const history = {
    now,
    pauses: [],
    spans: [
      // Short 10m active period before first intention: must NOT create a "No intention" row (< 15m).
      { s: at(8, 30), e: at(8, 40), app: 'VS Code', label: 'VS Code', source: 'window_title' },
      // Intention 1: FairGrade rubric PR (09:10 - 10:42) with 41m unbroken VS Code deep focus + GitHub + Slack.
      { s: at(9, 10), e: at(9, 51), app: 'VS Code', label: 'VS Code', source: 'window_title' },
      web(at(9, 51), at(10, 5), 'github.com', 'https://github.com/example-org/fairgrade/pull/12', 'Rubric PR · GitHub'),
      { s: at(10, 5), e: at(10, 34), app: 'VS Code', label: 'VS Code', source: 'window_title' },
      { s: at(10, 34), e: at(10, 42), app: 'Slack', label: 'Slack', source: 'window_title' },
      // Intention 2: Cleaning house (11:00 - 11:45), mostly away (39m away + 6m on laptop).
      web(at(11, 20), at(11, 26), 'www.youtube.com', 'https://www.youtube.com/watch?v=box', 'Fold pizza box - YouTube'),
      // Unplanned 22m active period (12:10 - 12:32): MUST appear as "No intention" row.
      web(at(12, 10), at(12, 24), 'x.com', 'https://x.com/home', 'Home / X'),
      web(at(12, 24), at(12, 32), 'twitch.tv', 'https://twitch.tv/demo', 'demo - Twitch'),
      // Intention 3: Gepeto launch post (13:00 - running at 13:34), with 9m off-plan X.
      web(at(13, 0), at(13, 21), 'docs.google.com', 'https://docs.google.com/document/d/1', 'Launch post - Google Docs'),
      web(at(13, 21), at(13, 30), 'x.com', 'https://x.com/home', 'Home / X'),
      { s: at(13, 30), e: at(13, 34), app: 'Slack', label: 'Slack', source: 'window_title' },
    ],
    intentions: [
      {
        id: 'i1', start: at(9, 10), end: at(10, 42), planned_until: at(10, 40), planned_minutes: 90,
        label: 'FairGrade rubric PR', done_when: 'PR open', raw: 'FairGrade rubric PR 1h30m',
        parse_source: 'gateway', outcome: 'done', extensions: 0, active: false,
      },
      {
        id: 'i2', start: at(11, 0), end: at(11, 45), planned_until: at(11, 45), planned_minutes: 45,
        label: 'Cleaning house', done_when: 'trash is out', raw: 'cleaning the house 45m',
        parse_source: 'local', outcome: 'done', extensions: 0, active: false,
      },
      {
        id: 'i3', start: at(13, 0), end: null, planned_until: at(14, 0), planned_minutes: 60,
        label: 'Gepeto launch post', done_when: 'draft sent to Ana', raw: 'Gepeto launch post 1h',
        parse_source: 'gateway', outcome: null, extensions: 0, active: true,
      },
    ],
  };

  const summary = S.summarizeIntentions(history, day, day + S.DAY, { classifier: AC });
  assert.equal(summary.doneCount, 2);
  assert.equal(summary.runningCount, 1);
  assert.equal(summary.unclearMs, 22 * MIN);
  assert.equal(summary.rows.length, 4);

  const [rubric, clean, none, post] = summary.rows;
  assert.equal(rubric.label, 'FairGrade rubric PR');
  assert.equal(rubric.deepFocusMs, 70 * MIN); // 41m + 29m unbroken VS Code runs
  assert.ok(rubric.segments.some((seg) => seg.kind === 'ice'));
  assert.equal(rubric.statusLabel, 'Done');

  assert.equal(clean.label, 'Cleaning house');
  assert.equal(clean.segments[0].kind, 'away');
  assert.equal(clean.awayMs, 39 * MIN);

  assert.equal(none.kind, 'none');
  assert.equal(none.label, 'No intention');
  assert.equal(none.start, at(12, 10));
  assert.equal(none.actualMs, 22 * MIN);
  assert.ok(none.details.some((d) => d.text.includes('X 14m')));
  assert.ok(none.details.some((d) => d.text.includes('Twitch 8m')));

  assert.equal(post.label, 'Gepeto launch post');
  assert.equal(post.statusLabel, 'Running');
  assert.equal(post.driftMs, 9 * MIN);
  assert.ok(post.segments.some((seg) => seg.kind === 'drift'));
  assert.ok(post.details.some((d) => d.kind === 'drift' && d.text === 'X 9m off-plan'));

  // Off-plan detection & done-when question phrasing.
  const activePost = history.intentions[2];
  const offPlan = S.detectOffPlan(activePost, history.spans, now, { classifier: AC, liveFocusState: 'focused' });
  assert.equal(offPlan.text, 'X 9m off-plan');
  const videoPlan = { ...activePost, label: 'Watch YouTube lecture', raw: 'Watch YouTube lecture 45m', done_when: null };
  const videoSpans = [web(now - 10 * MIN, now, 'www.youtube.com', 'https://www.youtube.com/watch?v=lec', 'Lecture - YouTube')];
  assert.equal(S.detectOffPlan(videoPlan, videoSpans, now, { classifier: AC, liveFocusState: 'focused' }), null);

  assert.equal(S.doneWhenQuestion('the trash is out'), 'Is the trash out?');
  assert.equal(S.doneWhenQuestion('PR open'), 'PR open?');
  assert.equal(S.doneWhenQuestion(null), 'Done?');

  // Without an Ask grant, the pizza-box video during cleaning counts as drift.
  assert.equal(clean.driftMs, 6 * MIN);
  assert.ok(clean.details.some((d) => d.kind === 'drift' && d.text === 'YouTube 6m off-plan'));

  // With an Ask-approved YouTube window, it is on-plan: no orange, no off-plan label.
  const granted = { ...history, grants: [{ t: at(11, 19), end: at(11, 26), services: ['youtube'] }] };
  const approved = S.summarizeIntentions(granted, day, day + S.DAY, { classifier: AC }).rows[1];
  assert.equal(approved.driftMs, 0);
  assert.ok(!approved.segments.some((seg) => seg.kind === 'drift'));
  assert.ok(approved.details.some((d) => d.kind === 'app' && d.text === 'YouTube 6m'));

  // A grant only excuses its own service and window.
  const partial = { ...history, grants: [{ t: at(11, 20), end: at(11, 23), services: ['youtube'] }, { t: at(11, 0), end: at(11, 45), services: ['discord'] }] };
  assert.equal(S.summarizeIntentions(partial, day, day + S.DAY, { classifier: AC }).rows[1].driftMs, 3 * MIN);

  // The live bar ignores approved time too.
  const watching = [web(now - 4 * MIN, now, 'www.youtube.com', 'https://www.youtube.com/watch?v=box', 'Fold pizza box - YouTube')];
  const cleaning = { ...history.intentions[1], end: null, active: true, start: now - 20 * MIN };
  assert.ok(S.detectOffPlan(cleaning, watching, now, { classifier: AC, liveFocusState: 'drifting' }));
  assert.equal(S.detectOffPlan(cleaning, watching, now, {
    classifier: AC, liveFocusState: 'drifting', grants: [{ t: now - 5 * MIN, end: now + MIN, services: ['youtube'] }],
  }), null);
});
