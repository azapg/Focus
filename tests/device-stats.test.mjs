import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
const context = vm.createContext({ Date, Set });
vm.runInContext(fs.readFileSync(new URL('../ui/stats.js', import.meta.url), 'utf8') + '\n' + fs.readFileSync(new URL('../ui/device-stats.js', import.meta.url), 'utf8'), context);
const { Stats, DeviceStats } = vm.runInContext('({Stats,DeviceStats})', context);
const MIN = 60000, start = new Date(2025, 0, 10).getTime(), end = start + 60 * MIN;
const laptop = { now:end, spans:[{ s:start, e:end, app:'Chrome', label:'Chrome' }], pauses:[] };
const tv = { id:'tv-one', name:'Living room TV', snapshot:{ now:end, spans:[{ s:start, e:start+30*MIN, app:'tv.video', label:'YouTube' }], pauses:[{ t:start, end:start+10*MIN, minutes:10, reason:'Lecture', kind:'dns' }, { t:start+10*MIN, end:start+20*MIN, minutes:10, reason:'Finish', kind:'app', package:'tv.video' }], apps:[{ package:'tv.video', label:'YouTube' }] } };

test('device time adds, but TV pauses never mark laptop time as DNS-off', () => {
  const sources=DeviceStats.sources(laptop,[tv]);
  const sum=DeviceStats.summarize(sources,start,end);
  assert.equal(sum.active,90*MIN);
  assert.equal(sum.activeOff,10*MIN);
  assert.equal(sum.off,10*MIN,'app exception must not count as DNS off');
  assert.equal(sum.pauses.length,2,'both DNS and app exceptions stay in history');
  assert.equal(sum.pauses.find(p=>p.kind==='app').app_label,'YouTube');
  assert.equal(sum.labels.find(l=>l.label==='Chrome').off,0);
  assert.equal(sum.labels.find(l=>l.label==='YouTube').device_name,'Living room TV');
});
test('a TV pause uses only the TV activity when reporting what happened', () => {
  const sum=DeviceStats.summarize(DeviceStats.sources(laptop,[tv]),start,end);
  assert.equal(Stats.did(sum.pauses[0].sourceHistory,sum.pauses[0])[0][0],'YouTube');
  assert.equal(Stats.did(sum.pauses[0].sourceHistory,sum.pauses[0]).length,1);
});
test('excluding devices changes all totals without losing saved data', () => {
  assert.equal(DeviceStats.summarize(DeviceStats.sources(laptop,[tv],['tv-one']),start,end).active,60*MIN);
  assert.equal(DeviceStats.summarize(DeviceStats.sources(laptop,[tv],['laptop']),start,end).active,30*MIN);
  assert.equal(DeviceStats.summarize(DeviceStats.sources(laptop,[tv],['laptop','tv-one']),start,end).active,0);
  assert.equal(tv.snapshot.spans.length,1);
});
test('an offline open pause stops accumulating at the saved snapshot time', () => {
  const offline={...tv,snapshot:{...tv.snapshot,now:start+20*MIN,spans:[{s:start,e:start+20*MIN,app:'tv.video',label:'YouTube'}],pauses:[{t:start,end:start+20*MIN,minutes:30,kind:'dns',reason:'Watch'}]}};
  const sources=DeviceStats.sources(null,[offline]);
  const sum=DeviceStats.summarize(sources,start,end);
  assert.equal(sum.active,20*MIN);assert.equal(sum.off,20*MIN);
  assert.equal(sum.labels[0].device_name,'');
});
test('missing snapshots do not masquerade as zero-valued recorded devices', () => {
  const sources=DeviceStats.sources(null,[{id:'missing',name:'TV',snapshot:null}]);
  assert.equal(sources.length,0);
});
test('phone feedback survives snapshots and uses only the selected device history', () => {
  const phone = { id: 'phone', name: 'Phone', snapshot: { kind: 'phone', now: end, apps: [],
    spans: [{ s: start, e: end, app: 'site:x.com', label: 'X', domain: 'x.com' }],
    pauses: [{ t: start, end: start + MIN, minutes: 1, kind: 'dns', reason: 'Read X',
      end_kind: 'dns_resumed', check_in: 'bored', rating: 1, rating_note: 'Nothing relevant' }],
  } };
  const all = DeviceStats.summarize(DeviceStats.sources(laptop, [tv, phone]), start, end);
  assert.equal(all.pauses.find(p => p.rating === 1).rating_note, 'Nothing relevant');
  assert.equal(Stats.pauseFeedback(all.pauses).worth.domain, 'x.com');
  const filtered = DeviceStats.summarize(DeviceStats.sources(laptop, [tv, phone], ['phone']), start, end);
  assert.equal(Stats.pauseFeedback(filtered.pauses).worth, null);
  assert.equal(Stats.pauseFeedback(filtered.pauses).hours.length, 0);
});
test('cross-midnight pauses stay visible and durations are clipped to the range', () => {
  const source={id:'laptop',kind:'windows',name:'Laptop',history:{now:end,spans:[],pauses:[{t:start-10*MIN,end:start+5*MIN,minutes:30,reason:'Late break'}]}};
  const sum=DeviceStats.summarize([source],start,end);
  assert.equal(sum.off,5*MIN);assert.equal(sum.pauses.length,1);
});
test('phone snapshots preserve kind=phone and classify mobile browser sites', async () => {
  const { default: ActivityClassifier } = await import('../ui/activity-classifier.js');
  const phone = {
    id: 'phone-one', name: 'Galaxy S24', snapshot: {
      kind: 'phone', now: end,
      spans: [
        { s: start, e: start + 15 * MIN, app: 'site:github.com', label: 'Github', domain: 'github.com' },
        { s: start + 15 * MIN, e: start + 30 * MIN, app: 'com.instagram.android', label: 'Instagram' },
      ],
      pauses: [], apps: [],
    },
  };
  const sources = DeviceStats.sources(null, [phone]);
  assert.equal(sources[0].kind, 'phone');
  const classified = ActivityClassifier.summarizeClassified(sources, start, end);
  assert.equal(classified.creatingMs, 15 * MIN);
  assert.equal(classified.consumingMs, 15 * MIN);
  assert.equal(classified.creating[0].app, 'GitHub');
  assert.equal(classified.consuming[0].app, 'Instagram');
});
test('multi-device laptop and phone apps preserve both actual device names', async () => {
  const { default: ActivityClassifier } = await import('../ui/activity-classifier.js');
  const laptop = {
    now: end,
    spans: [{ s: start, e: start + 10 * MIN, app: 'chrome', label: 'GitHub', source: 'chromium_extension', browser: { url: 'https://github.com/example-org/sample-project', title: 'focus-app' } }],
    pauses: [],
  };
  const phone = {
    id: 'phone-one', kind: 'phone', name: 'S25 Ultra', snapshot: {
      kind: 'phone', now: end,
      spans: [{ s: start + 10 * MIN, e: start + 20 * MIN, app: 'site:github.com', label: 'GitHub', domain: 'github.com' }],
      pauses: [], apps: [],
    },
  };
  const sources = DeviceStats.sources(laptop, [phone]);
  const classified = ActivityClassifier.summarizeClassified(sources, start, end);
  assert.equal(classified.creating[0].app, 'GitHub');
  assert.deepEqual(classified.creating[0].devices.map(d => d.name), ['Laptop', 'S25 Ultra']);
});
