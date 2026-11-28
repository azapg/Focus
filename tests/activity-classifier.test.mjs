import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import ActivityClassifier from '../ui/activity-classifier.js';

const MIN = 60_000;
const focusEligibilityFixtures = JSON.parse(fs.readFileSync(new URL('../shared/focus-eligibility-fixtures.json', import.meta.url)));

test('normalizes app.notion.com into Notion and deduplicates pages by page ID', () => {
  const spans = [
    {
      s: 0, e: 2 * MIN, app: 'Chrome', label: 'app.notion.com', source: 'chromium_extension',
      browser: { url: 'https://app.notion.com/p/Reading-Notes-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa?p=REDACTED&pm=REDACTED&t=1234', title: 'Reading Notes | Notion', audible: false },
    },
    {
      s: 2 * MIN, e: 3 * MIN, app: 'Chrome', label: 'app.notion.com', source: 'chromium_extension',
      browser: { url: 'https://app.notion.com/p/r-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', title: 'r | Notion', audible: false },
    },
    {
      s: 3 * MIN, e: 3.5 * MIN, app: 'Chrome', label: 'app.notion.com', source: 'chromium_extension',
      browser: { url: 'https://app.notion.com/p/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', title: 'New page | Notion', audible: false },
    },
    {
      s: 3.5 * MIN, e: 6.5 * MIN, app: 'Chrome', label: 'app.notion.com', source: 'chromium_extension',
      browser: { url: 'https://app.notion.com/p/Project-Roadmap-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', title: 'Project Roadmap | Notion', audible: false },
    },
  ];
  const sum = ActivityClassifier.summarizeClassified(
    [{ id: 'laptop', name: 'Laptop', kind: 'windows', history: { now: 10 * MIN, spans, pauses: [] } }],
    0,
    10 * MIN,
  );
  assert.equal(sum.creatingPct, 100);
  assert.equal(sum.consumingPct, 0);
  assert.equal(sum.apps.length, 1);
  assert.equal(sum.apps[0].app, 'Notion');
  assert.equal(sum.apps[0].items.length, 2);
  assert.equal(sum.apps[0].items[0].title, 'Reading Notes');
  assert.equal(sum.apps[0].items[0].ms, 3.5 * MIN);
  assert.equal(sum.apps[0].items[1].title, 'Project Roadmap');
  assert.equal(sum.apps[0].items[1].ms, 3 * MIN);
});

test('categorizes Windows and TV apps into human activities', () => {
  const cases = [
    [{ app: 'T3 Code', label: 'T3 Code', source: 'window_title' }, 'windows', 'T3 Code', 'building'],
    [{ app: 'WINWORD', label: 'WINWORD', source: 'window_title' }, 'windows', 'Word', 'writing'],
    [{ app: 'olk', label: 'olk', source: 'window_title' }, 'windows', 'Outlook', 'messaging'],
    [{ app: 'com.google.android.youtube.tv', label: 'YouTube' }, 'tv', 'YouTube', 'watching'],
    [{ app: 'com.netflix.ninja', label: 'Netflix' }, 'tv', 'Netflix', 'watching'],
    [{ app: 'tv.twitch.android.app', label: 'Twitch' }, 'tv', 'Twitch', 'watching'],
    [{ app: 'com.spotify.tv.android', label: 'Spotify' }, 'tv', 'Spotify', 'listening'],
    [{
      app: 'Chrome', label: 'www.acquisition.com', source: 'chromium_extension',
      browser: { url: 'https://www.acquisition.com/training/sample-lesson', title: 'Sample lesson', audible: true },
    }, 'windows', '$100M Leads Course', 'watching'],
    [{
      app: 'Chrome', label: 'www.figma.com', source: 'chromium_extension',
      browser: { url: 'https://www.figma.com/design/fictionalFixture/Garden-Labels', title: 'Seed labels (Community) – Figma', audible: false },
    }, 'windows', 'Figma', 'designing'],
    [{
      app: 'Chrome', label: 'github.com', source: 'chromium_extension',
      browser: { url: 'https://github.com/example-org/seed-catalog/issues/8', title: 'Add seed packet labels', audible: false },
    }, 'windows', 'GitHub', 'building'],
  ];

  for (const [span, kind, expectedApp, expectedCat] of cases) {
    const out = ActivityClassifier.classifySpan(span, kind);
    assert.equal(out.app, expectedApp, `app mismatch for ${JSON.stringify(span)}`);
    assert.equal(out.category, expectedCat, `category mismatch for ${JSON.stringify(span)}`);
  }
});

test('unknown sites stay out of Focus Ratio until tagged via rules.json', () => {
  const spans = [
    { s: 0, e: 60 * MIN, app: 'T3 Code', label: 'T3 Code', source: 'window_title' },
    { s: 60 * MIN, e: 120 * MIN, app: 'Chrome', label: 'x.com', source: 'chromium_extension', browser: { url: 'https://x.com/home', title: 'Home / X', audible: false } },
    { s: 120 * MIN, e: 150 * MIN, app: 'Chrome', label: 'drafting.example', source: 'chromium_extension', browser: { url: 'https://drafting.example/', title: 'Drafting', audible: false } },
  ];
  const src = [{ id: 'laptop', name: 'Laptop', kind: 'windows', history: { now: 160 * MIN, spans, pauses: [] } }];

  const before = ActivityClassifier.summarizeClassified(src, 0, 160 * MIN);
  assert.equal(before.creatingPct, 50, 'unknown site must not distort Creating vs Consuming %');
  assert.equal(before.consumingPct, 50);
  assert.equal(before.uncategorized.length, 1);
  assert.equal(before.uncategorized[0].matchValue, 'drafting.example');

  const inbox = ActivityClassifier.buildUncategorizedInbox(before, 160 * MIN);
  assert.equal(inbox.items.length, 1);
  assert.equal(inbox.items[0].value, 'drafting.example');

  const rules = ActivityClassifier.upsertUserRule({ version: 1, rules: [] }, {
    match: 'host', value: 'drafting.example', app: 'Drafting', category: 'building',
  });
  const after = ActivityClassifier.summarizeClassified(src, 0, 160 * MIN, { userRules: rules });
  assert.equal(after.uncategorized.length, 0);
  assert.equal(after.creatingMs, 90 * MIN);
  assert.equal(after.creatingPct, 60);
  assert.equal(after.consumingPct, 40);
});

test('pause audit distinguishes matched, work (creating), and consuming drift', () => {
  const history = {
    now: 20 * MIN,
    spans: [
      { s: 0, e: 6 * MIN, app: 'T3 Code', label: 'T3 Code', source: 'window_title' },
      { s: 6 * MIN, e: 10 * MIN, app: 'Chrome', label: 'x.com', source: 'chromium_extension', browser: { url: 'https://x.com/home', title: 'Home / X', audible: false } },
      { s: 10 * MIN, e: 11 * MIN, app: 'Discord', label: 'Discord', source: 'window_title' },
      { s: 11 * MIN, e: 19 * MIN, app: 'Chrome', label: 'x.com', source: 'chromium_extension', browser: { url: 'https://x.com/home', title: 'Home / X', audible: false } },
    ],
    pauses: [],
  };

  // Staying in T3 Code during an X pause is work, not drift.
  const workPause = ActivityClassifier.classifyPause(history, { t: 0, end: 10 * MIN, reason: 'check opinions on X' });
  assert.equal(workPause.hasDrift, false);
  assert.equal(workPause.items.find((i) => i.app === 'T3 Code').status, 'work');
  assert.equal(workPause.items.find((i) => i.app === 'X').status, 'matched');

  // Drifting to X during a Discord pause is flagged as drift.
  const driftPause = ActivityClassifier.classifyPause(history, { t: 10 * MIN, end: 20 * MIN, reason: 'talk on discord' });
  assert.equal(driftPause.hasDrift, true);
  assert.equal(driftPause.items.find((i) => i.app === 'X').status, 'drift');
  assert.equal(driftPause.items.find((i) => i.app === 'Discord').status, 'matched');
});

test('separates OS chrome noise and ignored user rules from active categories', () => {
  const spans = [
    { s: 0, e: MIN, app: 'SearchHost', label: 'SearchHost', source: 'window_title' },
    { s: MIN, e: 2 * MIN, app: 'ShellHost', label: 'ShellHost', source: 'window_title' },
    { s: 2 * MIN, e: 3 * MIN, app: 'Chrome', label: 't.co', source: 'chromium_extension', browser: { url: 'https://t.co/abc', title: 'redirect', audible: false } },
    { s: 3 * MIN, e: 8 * MIN, app: 'Zed', label: 'Zed', source: 'window_title' },
    { s: 8 * MIN, e: 10 * MIN, app: 'NoisyApp', label: 'NoisyApp', source: 'window_title' },
  ];
  const rules = ActivityClassifier.upsertUserRule(null, { match: 'app', value: 'NoisyApp', app: 'NoisyApp', category: 'ignore' });
  const sum = ActivityClassifier.summarizeClassified(
    [{ id: 'laptop', name: 'Laptop', kind: 'windows', history: { now: 12 * MIN, spans, pauses: [] } }],
    0,
    12 * MIN,
    { userRules: rules },
  );
  assert.equal(sum.active, 5 * MIN);
  assert.equal(sum.noiseMs, 5 * MIN);
  assert.equal(sum.apps.length, 1);
  assert.equal(sum.apps[0].app, 'Zed');
});

test('fine categories use categories.json, then fall back to V1 verbs', () => {
  const m = 60e3;
  const span = (s, e, url, title) => ({ s, e, app: 'Chrome', label: new URL(url).hostname, source: 'chromium_extension', browser: { url, title, visibility: 'page' } });
  const sources = [{ name: 'Laptop', kind: 'windows', history: { now: 100 * m, spans: [
    span(0, 10 * m, 'https://x.com/home', 'Home / X'),
    span(10 * m, 30 * m, 'https://www.youtube.com/watch?v=a', 'How to label seed packets - YouTube'),
    span(30 * m, 35 * m, 'https://example.org/report.pdf', 'report.pdf'),
  ], pauses: [] } }];
  const cache = { items: { 'item:YouTube|How to label seed packets': 'learning' } };
  const sum = ActivityClassifier.summarizeFine(sources, 0, 100 * m, { cache });
  const byId = Object.fromEntries(sum.categories.map((c) => [c.id, c.ms]));
  assert.equal(byId.social, 10 * m, 'X falls back to Social feeds');
  assert.equal(byId.learning, 20 * m, 'a cached page beats the app default');
  assert.equal(byId.reading, 5 * m, 'PDFs count as Reading');
  assert.equal(sum.total, 35 * m);
  assert.equal(sum.apps[0].app, 'YouTube');
});

test('an ignore rule cannot hide a site that categories.json names explicitly', () => {
  const m = 60e3;
  const sources = [{ name: 'Phone', kind: 'phone', history: { now: 10 * m, spans: [
    { s: 0, e: m, app: 'site:example-adult.test', label: 'Example', domain: 'example-adult.test' },
  ], pauses: [] } }];
  const rules = { rules: [{ match: 'host', value: 'example-adult.test', app: 'Example', category: 'ignore' }] };
  const hidden = ActivityClassifier.summarizeFine(sources, 0, 10 * m, { userRules: rules });
  assert.equal(hidden.total, 0);
  const shown = ActivityClassifier.summarizeFine(sources, 0, 10 * m, { userRules: rules, cache: { items: { 'app:Example': 'adult' } } });
  assert.equal(shown.categories[0].id, 'adult');
});

test('pause drift needs two minutes of unmentioned consuming apps', () => {
  const m = 60e3;
  const history = { now: 30 * m, spans: [
    { s: 0, e: 1 * m, app: 'Chrome', label: 'x.com', source: 'chromium_extension', browser: { url: 'https://x.com/home', title: 'Home / X', visibility: 'page' } },
    { s: 1 * m, e: 4 * m, app: 'Banco', label: 'Banco' },
  ] };
  const short = ActivityClassifier.classifyPause(history, { t: 0, end: 5 * m, reason: 'bank app is broken' });
  assert.equal(short.hasDrift, false, 'one minute of X and an unknown app is not drift');
  history.spans[0].e = 3 * m;
  history.spans[1].s = 3 * m;
  const long = ActivityClassifier.classifyPause(history, { t: 0, end: 5 * m, reason: 'bank app is broken' });
  assert.equal(long.hasDrift, true);
});

test('summarizeFine computes per-device DNS-off overlap without cross-device bleed', () => {
  const m = 60e3;
  const sources = [
    {
      name: 'Laptop',
      kind: 'windows',
      history: {
        now: 60 * m,
        spans: [
          { s: 0, e: 20 * m, app: 'VS Code', label: 'VS Code', source: 'window_title' },
          { s: 20 * m, e: 40 * m, app: 'Chrome', label: 'www.youtube.com', source: 'chromium_extension', browser: { url: 'https://www.youtube.com/watch?v=1', title: 'Clip - YouTube' } },
        ],
        // Overlapping pauses on laptop from 20m..35m (should not double count 25m..30m).
        pauses: [
          { t: 20 * m, end: 30 * m, minutes: 10, reason: 'break' },
          { t: 25 * m, end: 35 * m, minutes: 10, reason: 'still break' },
        ],
      },
    },
    {
      name: 'TV',
      kind: 'tv',
      history: {
        now: 60 * m,
        spans: [{ s: 0, e: 20 * m, app: 'com.netflix.ninja', label: 'Netflix' }],
        // TV pause during 0..20m must not mark Laptop VS Code as DNS-off.
        pauses: [{ t: 0, end: 10 * m, minutes: 10, reason: 'movie' }],
      },
    },
  ];
  const fine = ActivityClassifier.summarizeFine(sources, 0, 60 * m);
  const byApp = Object.fromEntries(fine.apps.map((a) => [a.app, a]));
  assert.equal(byApp['VS Code'].off, 0);
  assert.equal(byApp['VS Code'].offRatio, 0);
  assert.equal(byApp['YouTube'].off, 15 * m);
  assert.equal(byApp['YouTube'].offRatio, 0.75);
  assert.equal(byApp['Netflix'].off, 10 * m);
  assert.equal(byApp['Netflix'].offRatio, 0.5);
});

test('classifies finite activities and TV apps across device inputs', () => {
  const cases = [
    [{ app: 'com.bilingify.readest' }, 'phone', 'Readest', 'reading', 'learn'],
    [{ app: 'Readest' }, 'windows', 'Readest', 'reading', 'learn'],
    [{ app: 'Chrome', label: 'web.readest.com' }, 'windows', 'Readest', 'reading', 'learn'],
    [{ app: 'com.duolingo' }, 'phone', 'Duolingo', 'learning', 'learn'],
    [{ app: 'site:www.duolingo.com' }, 'phone', 'Duolingo', 'learning', 'learn'],
    [{ app: 'tech.simha.androidtvremote' }, 'phone', 'Remote ATV', 'system', 'other'],
    [{ app: 'com.google.android.videos', label: 'Play Movies' }, 'phone', 'Google TV', 'watching', 'consuming'],
  ];
  for (const [span, kind, app, category, group] of cases) {
    const classified = ActivityClassifier.classifySpan(span, kind);
    assert.equal(classified.app, app);
    assert.equal(classified.category, category);
    const history = { now: MIN, spans: [{ ...span, s: 0, e: MIN }], pauses: [] };
    const sources = [{ id: kind, kind, history }];
    const summary = ActivityClassifier.summarizeClassified(sources, 0, MIN);
    assert.equal(summary.apps[0].group, group);
    if (group === 'learn') {
      const fine = ActivityClassifier.summarizeFine(sources, 0, MIN, { cache: { items: { [`app:${app}`]: 'writing' } } });
      assert.equal(fine.apps[0].category, category);
      assert.equal(summary.learningMs, MIN);
      assert.equal(summary.ratioDenom, 0);
      assert.equal(ActivityClassifier.classifyPause(history, { t: 0, end: MIN, reason: 'Another activity' }, kind).items[0].status, 'work');
    }
  }
});

test('editing a category rule preserves configured finite alternatives', () => {
  const finiteAlternatives = [{ id: 'book', app: 'Book site', goal: '5 pages', laptopUrl: 'https://example.org' }];
  const doc = ActivityClassifier.upsertUserRule({ version: 1, rules: [], finiteAlternatives }, {
    match: 'host', value: 'example.org', app: 'Book site', category: 'reading',
  });
  assert.deepEqual(doc.finiteAlternatives, finiteAlternatives);
  assert.equal(ActivityClassifier.classifySpan({ app: 'site:example.org' }, 'phone', doc).category, 'reading');
});

test('Play Movies still matches Google TV rules and pause reasons by name', () => {
  const googleTv = { app: 'com.google.android.videos', s: MIN, e: 5 * MIN };
  const rules = { rules: [{ match: 'app', value: 'Play Movies', category: 'system' }] };
  const classified = ActivityClassifier.classifySpan(googleTv, 'phone', rules);
  assert.equal(classified.app, 'Google TV');
  assert.equal(classified.category, 'system');

  const audit = (app, reason) => ActivityClassifier.classifyPause({
    now: 5 * MIN, spans: [{ ...googleTv, app }],
  }, { t: MIN, end: 5 * MIN, reason }, 'phone');
  for (const reason of ['Play Movies', 'Play Movies & TV', 'Watch Play Movies']) {
    assert.equal(audit(googleTv.app, reason).items[0].status, 'matched');
    assert.equal(audit('com.disney.disneyplus', reason).hasDrift, true);
    assert.equal(audit('com.netflix.mediaclient', reason).hasDrift, true);
  }
  assert.equal(audit('com.netflix.mediaclient', 'Play Movies and Netflix').hasDrift, false);
});

test('coarse browser titles must be whole hostnames to classify as sites', () => {
  for (const label of ['Guide to www.duolingo.com', 'Guide to@www.duolingo.com', 'Guide@web.readest.com']) {
    const result = ActivityClassifier.classifySpan({ app: 'Chrome', label }, 'windows');
    assert.equal(result.category, 'unknown');
    assert.equal(result.matchType, 'app');
  }
  const lesson = ActivityClassifier.classifySpan({ app: 'Chrome', label: 'www.duolingo.com' }, 'windows');
  assert.equal(lesson.category, 'learning');
  assert.equal(lesson.matchType, 'host');
});

test('fine category palette keeps productive and neutral work monochrome and colors wasting categories', () => {
  const isGray = (hex) => {
    const [, r, g, b] = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex) || [];
    return r && Math.max(parseInt(r, 16), parseInt(g, 16), parseInt(b, 16)) - Math.min(parseInt(r, 16), parseInt(g, 16), parseInt(b, 16)) <= 16;
  };
  const byId = Object.fromEntries(ActivityClassifier.FINE.map((c) => [c.id, c]));
  for (const id of ['coding', 'business', 'writing', 'design', 'learning', 'reading', 'messaging', 'music', 'web', 'errands', 'unsorted']) {
    assert.equal(isGray(byId[id].color), true, `${id} should stay monochrome (${byId[id].color})`);
  }
  assert.equal(byId.music.group, 'neutral');
  assert.equal(byId.web.group, 'neutral');
  for (const id of ['social', 'video', 'tv', 'games', 'adult']) {
    assert.equal(isGray(byId[id].color), false, `${id} should carry a warning/breach color (${byId[id].color})`);
  }
});

test('classifies NotebookLM, notes, Kindle, and Anki into eligible learning/reading/writing categories', () => {
  const cases = [
    [{ app: 'Chrome', label: 'notebooklm.google.com', source: 'chromium_extension', browser: { url: 'https://notebooklm.google.com/notebook/abc', title: 'Quantum Notes - NotebookLM' } }, 'windows', 'NotebookLM', 'learning'],
    [{ app: 'Chrome', label: 'keep.google.com', source: 'chromium_extension', browser: { url: 'https://keep.google.com/', title: 'Google Keep' } }, 'windows', 'Google Keep', 'writing'],
    [{ app: 'OneNote', label: 'OneNote' }, 'windows', 'OneNote', 'writing'],
    [{ app: 'com.amazon.kindle', label: 'Kindle' }, 'phone', 'Kindle', 'reading'],
    [{ app: 'com.ichi2.anki', label: 'Anki' }, 'phone', 'Anki', 'learning'],
  ];
  for (const [span, kind, app, category] of cases) {
    const out = ActivityClassifier.classifySpan(span, kind);
    assert.equal(out.app, app);
    assert.equal(out.category, category);
  }
});

test('detectFocusSessions rewards unbroken single-app runs (25m build/write, 15m read/learn) and rejects waste and pauses', () => {
  const m = 60_000, s = 1_000;
  const lap = (start, end, app, url, title) => ({
    s: start, e: end, app: url ? 'Chrome' : app, label: url ? new URL(url).hostname : app,
    source: url ? 'chromium_extension' : 'window_title',
    ...(url ? { browser: { url, title, visibility: 'page' } } : {}),
  });

  const sources = [
    {
      id: 'laptop', name: 'Laptop', kind: 'windows',
      history: {
        now: 180 * m,
        spans: [
          // 1. T3 Code: 15m + 30s glance to Terminal + 15m -> 30m unbroken focus (Terminal 30s not counted).
          lap(0, 15 * m, 'T3 Code'),
          lap(15 * m, 15 * m + 30 * s, 'Terminal'),
          lap(15 * m + 30 * s, 30 * m + 30 * s, 'T3 Code'),
          // 2. 20m Notion interrupted by 10s of X -> breaks immediately (20m < 25m, not rewarded).
          lap(35 * m, 55 * m, 'Notion'),
          lap(55 * m, 55 * m + 10 * s, 'Chrome', 'https://x.com/home', 'Home / X'),
          lap(55 * m + 10 * s, 65 * m + 10 * s, 'Notion'),
          // 3. 60m continuous YouTube -> never eligible.
          lap(70 * m, 130 * m, 'Chrome', 'https://www.youtube.com/watch?v=demo', 'Demo - YouTube'),
          // 4. 40m T3 Code split in the middle by a 5m DNS-off pause -> 18m + 17m, neither hits 25m.
          lap(135 * m, 175 * m, 'T3 Code'),
        ],
        pauses: [
          { t: 153 * m, end: 158 * m, minutes: 5, reason: 'quick check' },
        ],
      },
    },
    {
      id: 'phone', name: 'Phone', kind: 'phone',
      history: {
        now: 180 * m,
        spans: [
          // 5. Duolingo 16m on phone overlapping Laptop T3 Code [10m..26m] -> qualifies (15m min for learning), union deduplicates overlap!
          { s: 10 * m, e: 26 * m, app: 'com.duolingo', label: 'Duolingo' },
          // 6. Readest 14m -> under 15m threshold, stays unrewarded.
          { s: 40 * m, e: 54 * m, app: 'com.bilingify.readest', label: 'Readest' },
          // 7. Active NotebookLM 18m right up to now -> qualifies and marks activeRun.
          { s: 162 * m, e: 180 * m, app: 'site:notebooklm.google.com', domain: 'notebooklm.google.com', label: 'NotebookLM' },
        ],
        pauses: [],
      },
    },
  ];

  const fine = ActivityClassifier.summarizeFine(sources, 0, 180 * m);
  assert.equal(fine.focus.runs.length, 3, 'T3 Code 30m, NotebookLM 18m, Duolingo 16m qualify');
  assert.deepEqual(fine.focus.runs.map((r) => [r.app, r.ms]), [
    ['T3 Code', 30 * m],
    ['NotebookLM', 18 * m],
    ['Duolingo', 16 * m],
  ]);
  assert.equal(fine.focus.totalMs, 64 * m);
  // Union of [0..15m], [15m30s..30m30s], [10m..26m], [162m..180m] = 30.5m + 18m = 48.5m (no double counting).
  assert.equal(fine.focus.unionMs, 48.5 * m);
  assert.equal(fine.focus.activeRun?.app, 'NotebookLM');

  const byApp = Object.fromEntries(fine.apps.map((a) => [a.app, a]));
  assert.equal(byApp['T3 Code'].focusMs, 30 * m);
  assert.equal(byApp['YouTube'].focusMs, 0);
  assert.equal(byApp['Notion'].focusMs, 0);
  assert.equal(byApp['Readest'].focusMs, 0);
  assert.equal(byApp['Duolingo'].focusMs, 16 * m);
  assert.equal(byApp['NotebookLM'].focusMs, 18 * m);
});

test('saved reward categories survive while observed video/feed activity stays excluded', () => {
  const { durationMs, youtubeWatch, youtubeOtherPage, learningControls, readingControl } = focusEligibilityFixtures;
  const desktopRule = { rules: [{ match: 'host', value: 'youtube.com', ...youtubeWatch.displayRule }] };
  const phoneRule = { rules: [{ match: 'package', value: youtubeWatch.phonePackage, ...youtubeWatch.displayRule }] };
  const desktopWatch = {
    s: 0, e: durationMs, app: 'Chrome', label: 'youtube.com', source: 'chromium_extension',
    browser: { url: youtubeWatch.desktopUrl, title: 'Fictional lesson - YouTube', audible: true },
  };
  const phoneWatch = { s: 0, e: durationMs, app: youtubeWatch.phonePackage, label: 'YouTube' };
  const sources = [
    { id: 'laptop', name: 'Laptop', kind: 'windows', history: { now: durationMs, spans: [desktopWatch], pauses: [] } },
    { id: 'phone', name: 'Phone', kind: 'phone', history: { now: durationMs, spans: [phoneWatch], pauses: [] } },
  ];

  const fine = ActivityClassifier.summarizeFine(sources, 0, durationMs, { userRules: desktopRule });
  assert.equal(fine.apps.find((a) => a.app === 'Lessons')?.category, 'learning', 'the visible category remains Learning');
  assert.equal(fine.apps.find((a) => a.app === 'Lessons')?.ms, durationMs);
  assert.equal(fine.focus.totalMs, 0, 'the desktop video stays ineligible when a host rule displays Learning');
  assert.equal(ActivityClassifier.detectFocusSessions(sources, 0, durationMs, {
    userRules: desktopRule,
  }).totalMs, 0, 'a host rule that displays Learning cannot grant focus eligibility');
  assert.equal(ActivityClassifier.detectFocusSessions([sources[0]], 0, durationMs, {
    cache: { items: { 'app:YouTube': 'learning' } },
  }).totalMs, 0, 'a legacy per-app Learning category cannot grant focus eligibility');
  assert.equal(ActivityClassifier.detectFocusSessions([sources[1]], 0, durationMs, { userRules: phoneRule }).totalMs, 0,
    'the phone YouTube package stays ineligible when a package rule displays Learning');
  const homeFeed = { ...desktopWatch, browser: { ...desktopWatch.browser, url: youtubeWatch.homeUrl } };
  assert.equal(ActivityClassifier.detectFocusSessions([{
    id: 'laptop', name: 'Laptop', kind: 'windows', history: { now: durationMs, spans: [homeFeed], pauses: [] },
  }], 0, durationMs, { userRules: desktopRule }).totalMs, 0, 'the YouTube home feed remains excluded when displayed as Learning');

  const otherPage = {
    ...desktopWatch,
    browser: { ...desktopWatch.browser, url: youtubeOtherPage.desktopUrl },
  };
  const observedPage = ActivityClassifier.classifySpan(otherPage, 'windows');
  assert.equal(observedPage.category, 'watching', 'YouTube display category keeps its existing warning behavior');
  assert.equal(observedPage.itemKind, 'feed', 'legacy item display type remains unchanged');
  assert.equal(observedPage.activityKind, 'page', 'focus eligibility still distinguishes non-feed routes');
  assert.equal(ActivityClassifier.classifySpan(homeFeed, 'windows').itemKind, 'feed');
  assert.equal(ActivityClassifier.classifySpan({ ...homeFeed, browser: { ...homeFeed.browser, url: 'https://www.youtube.com/shorts' } }, 'windows').itemKind, 'feed');
  assert.equal(ActivityClassifier.classifySpan(desktopWatch, 'windows').itemKind, 'video');
  assert.equal(ActivityClassifier.focusEligibility(otherPage, 'windows').isWaste, false,
    'a non-video YouTube URL is not blanket-classified as a video or feed');
  const pageWritingRule = { rules: [{ match: 'host', value: 'youtube.com', app: 'Guide', category: 'writing' }] };
  const categorizedOtherPage = ActivityClassifier.focusEligibility(otherPage, 'windows', pageWritingRule);
  assert.equal(categorizedOtherPage.category, 'writing');
  assert.equal(categorizedOtherPage.minMs, 25 * MIN,
    'a saved non-waste category can qualify on a route that is not observed as video or feed');

  const controlSources = [
    { id: 'phone', name: 'Phone', kind: 'phone', history: { now: learningControls.durationMs, spans: [
      { s: 0, e: learningControls.durationMs, app: learningControls.phonePackage, label: 'Duolingo' },
    ], pauses: [] } },
    { id: 'laptop', name: 'Laptop', kind: 'windows', history: { now: readingControl.durationMs, spans: [
      { s: 0, e: readingControl.durationMs, app: 'Chrome', label: 'read.amazon.com', source: 'chromium_extension',
        browser: { url: readingControl.desktopUrl, title: 'Fictional book', audible: false } },
    ], pauses: [] } },
  ];
  assert.equal(ActivityClassifier.detectFocusSessions([controlSources[0]], 0, learningControls.durationMs).totalMs, learningControls.durationMs,
    'known phone Learning remains eligible at the 15m boundary');
  assert.equal(ActivityClassifier.detectFocusSessions([controlSources[1]], 0, readingControl.durationMs).totalMs, readingControl.durationMs,
    'known desktop Reading remains eligible at the 15m boundary');

  const custom = focusEligibilityFixtures.customWritingApp;
  const customRule = { rules: [custom.rule] };
  const exampleDesk = [{ id: 'laptop', name: 'Laptop', kind: 'windows', history: { now: custom.durationMs, spans: [
    { s: 0, e: custom.durationMs, app: custom.app, label: custom.app },
  ], pauses: [] } }];
  assert.equal(ActivityClassifier.detectFocusSessions(exampleDesk, 0, custom.durationMs, { userRules: customRule }).totalMs, custom.durationMs,
    'saved Writing rules preserve an otherwise unknown app as eligible');
  const business = focusEligibilityFixtures.businessApp;
  const businessSource = [{ id: 'laptop', name: 'Laptop', kind: 'windows', history: { now: business.durationMs, spans: [
    { s: 0, e: business.durationMs, app: business.app, label: business.app },
  ], pauses: [] } }];
  assert.equal(ActivityClassifier.detectFocusSessions(businessSource, 0, business.durationMs, {
    cache: { items: { [`app:${business.app}`]: 'business' } },
  }).totalMs, business.durationMs, 'legacy Business category keeps its 25m threshold');
  const midRunFeed = [{ id: 'laptop', name: 'Laptop', kind: 'windows', history: { now: custom.durationMs, spans: [
    { s: 0, e: 15 * MIN, app: custom.app, label: custom.app },
    { ...homeFeed, s: 15 * MIN, e: 15 * MIN + 10_000 },
    { s: 15 * MIN + 10_000, e: custom.durationMs, app: custom.app, label: custom.app },
  ], pauses: [] } }];
  const feedBreakRules = { rules: [custom.rule, { match: 'host', value: 'youtube.com', app: 'Lessons', category: 'writing' }] };
  const feedMidRun = ActivityClassifier.detectFocusSessions(midRunFeed, 0, custom.durationMs, { userRules: feedBreakRules });
  assert.equal(feedMidRun.totalMs, 0, 'a feed is a hard break even when its saved display category is Writing');
  const displayedFeed = ActivityClassifier.classifySpan(homeFeed, 'windows');
  assert.equal(ActivityClassifier.fineCategory(homeFeed, displayedFeed, 'windows').id, 'video',
    'YouTube home remains displayed as video/warning');

  const sharedDisplayName = { rules: [
    { match: 'app', value: 'T3 Code', app: 'Shared work label', category: 'writing' },
    { match: 'app', value: 'VS Code', app: 'Shared work label', category: 'writing' },
  ] };
  const renamedApps = [{ id: 'laptop', name: 'Laptop', kind: 'windows', history: { now: 30 * MIN, spans: [
    { s: 0, e: 15 * MIN, app: 'T3 Code', label: 'T3 Code' },
    { s: 15 * MIN, e: 30 * MIN, app: 'VS Code', label: 'VS Code' },
  ], pauses: [] } }];
  assert.equal(ActivityClassifier.detectFocusSessions(renamedApps, 0, 30 * MIN, { userRules: sharedDisplayName }).totalMs, 0,
    'display aliases cannot join separate observed apps into one qualifying run');
});

test('channel feeds and extension-confirmed feeds interrupt focus under a work label', () => {
  const end = 30 * MIN;
  const span = {
    s: 0, e: end, app: 'Chrome', label: 'youtube.com', source: 'chromium_extension',
  };
  const userRules = { rules: [{ match: 'host', value: 'youtube.com', category: 'learning' }] };
  for (const [path, feed, expected] of [
    ['/@fictional/videos', false, 0],
    ['/@fictional', false, 0],
    ['/policies/guide', true, 0],
    ['/policies/guide', false, end],
  ]) {
    const browser = { url: 'https://www.youtube.com' + path, habit_feed: feed };
    const history = { now: end, spans: [{ ...span, browser }], pauses: [] };
    const result = ActivityClassifier.detectFocusSessions([{ kind: 'windows', history }], 0, end, { userRules });
    assert.equal(result.totalMs, expected, path + ' feed=' + feed);
  }
});
