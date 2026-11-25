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
