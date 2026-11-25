"""Render the actual app UI with synthetic Tauri history; no Windows/DNS operations."""
import asyncio

from screenshot_browser import BUILD, capture_panel, fixture_browser, fixture_page

MOCK = r'''(() => {
  const day = new Date('2026-02-12T14:00:00Z'); day.setHours(0, 0, 0, 0);
  const base = day.getTime(), m = 60000;
  const row = (minute, duration, label, url, title, audible = false) => ({
    s: base + minute * m,
    e: base + (minute + duration) * m,
    app: url ? 'Chrome' : label,
    label,
    source: url ? 'chromium_extension' : 'window_title',
    ...(url ? { browser: { url, title, visibility: 'page', audible, tab_id: 1, window_id: 1, browser_pid: 123, host_pid: 456 } } : {}),
  });
  window.__rules = { version: 1, rules: [] };
  window.__uncategorized = null;
  window.__testHistory = {
    now: base + 14 * 60 * m,
    spans: [
      row(540, 28, 'app.notion.com', 'https://app.notion.com/p/Seed-Library-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 'Seed Library | Notion'),
      row(568, 22, 'app.notion.com', 'https://app.notion.com/p/Greenhouse-Notes-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa?p=REDACTED&t=123', 'Greenhouse Notes | Notion'),
      row(590, 2, 'app.notion.com', 'https://app.notion.com/p/r-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'r | Notion'),
      row(592, 42, 'VS Code'),
      row(634, 18, 'github.com', 'https://github.com/example-org/seed-catalog/issues/8', 'Add seed packet labels · Issue #8 · example-org/seed-catalog · GitHub'),
      row(652, 26, 'www.youtube.com', 'https://www.youtube.com/watch?v=demo', 'How to germinate marigolds - YouTube', true),
      row(678, 12, 'Chrome'),
      row(-1440 * 2 + 550, 35, 'VS Code'),
      row(-1440 * 3 + 1430, 18, 'VS Code'),
      row(-1440 * 4 + 900, 32, 'www.youtube.com', 'https://www.youtube.com/watch?v=demo', 'How to germinate marigolds - YouTube', true),
      row(690, 14, 'newtool.example', 'https://newtool.example/workspace', 'Design sandbox'),
      row(710, 25, 'x.com', 'https://x.com/home', 'Home / X'),
      row(735, 5, 'x.com', 'https://x.com/seed_library', 'Seed Library (@seed_library) / X'),
      row(740, 3, 'x.com', 'https://x.com/search?q=seed%20swap', 'Search / X'),
      row(743, 12, 'x.com', 'https://x.com/messages', 'Messages / X'),
    ],
    peeks: [base + 640 * m, base + 670 * m],
    pauses: [
      { t: base + 652 * m, end: base + 662 * m, minutes: 10, reason: 'Find the seed swap time on Discord',
        check_in: 'bored', rating: 1, rating_note: '<The schedule was already in my calendar>' },
    ],
  };
  window.__calls = [];
  window.__handlers = {};
  window.__TAURI__ = {
    core: {
      invoke: async (c, args) => {
        window.__calls.push({ c, args });
        if (c === 'history') {
          if (window.__delayStats) {
            window.__delayStats = false;
            await new Promise(resolve => { window.__releaseStats = resolve; });
          }
          return window.__testHistory;
        }
        if (c === 'startup_panel') return 'today';
        if (c === 'peek_link') return args.url.endsWith('/explore') ? { refusal: 'permalink' }
          : window.__peekReply || { snapshot: { site: 'X', text: 'The seed swap opens at noon. Bring labeled envelopes.' } };
        if (c === 'judge_pause') return { id: 'ask-test', failure: 'configuration', decision: null,
          scope_available: false, friction: { hold_ms: 2000, wait_ms: 0 }, manual_choices: [1] };
        if (c === 'get_state') return { paused_at: 0, paused_until: 0, now: Date.now(), dry_run: true };
        if (c === 'rate_pause') return { paused_at: 0, paused_until: 0, now: Date.now(), rating_prompt: null };
        if (c === 'activity_rules_load') return structuredClone(window.__rules);
        if (c === 'activity_rules_save') { window.__rules = structuredClone(args.rules); return null; }
        if (c === 'activity_uncategorized_save') { window.__uncategorized = structuredClone(args.inbox); return null; }
        return null;
      },
    },
    event: { listen: async (name, handler) => { window.__handlers[name] = handler; return () => {}; } },
  };
})();'''


async def main(output=None):
  async with fixture_browser() as context:
    await context.add_init_script(MOCK)
    page = await fixture_page(context)
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    await page.goto((BUILD / 'index.html').as_uri())
    await page.locator('#panel.open').wait_for()
    await page.wait_for_timeout(650)

    # One-sentence hero: total screen time and building share; thin category bar + clickable legend.
    assert await page.locator('[data-total]').inner_text() == '3h 29m'
    assert await page.locator('[data-build-pct]').inner_text() == '1h 52m building'  # Notion 52m + VS Code 42m + GitHub 18m
    assert await page.locator('.thin-bar .thin-seg').count() >= 2
    assert await page.locator('.cat-leg button[data-dcat]').count() >= 2

    # Today hides the noisy 24h timeline and legend completely.
    assert await page.locator('#pTimelineSec').is_hidden()
    await page.evaluate("window.__handlers.habits({payload: {rows: [], credit: 8, cap: 45}})")
    await page.locator('.habit-credit').get_by_text('8m earned', exact=False).wait_for()
    assert await page.get_by_text('2 peeks · 1 DNS pause', exact=True).is_visible()

    # Screen time shows only icon, name, and time at rest; at least 5 rows inside the bento card.
    assert await page.locator('#pApps [data-fit]:not([hidden])').count() >= 5
    assert await page.locator('#pApps .st-cat').count() == 0
    # Time turns amber only when an app was mostly used with DNS off (YouTube: 10m of 26m is not mostly).
    yt_time = page.locator('.st-row', has=page.locator('.st-name', has_text='YouTube')).locator('.n').first
    vscode_time = page.locator('.st-row', has=page.locator('.st-name', has_text='VS Code')).locator('.n').first
    assert int(await yt_time.get_attribute('data-dns-off')) == 600000
    assert 'dns-off' not in (await yt_time.get_attribute('class'))
    assert int(await vscode_time.get_attribute('data-dns-off')) == 0
    # Websites carry a small globe; desktop programs don't.
    assert await page.locator('.st-row', has=page.locator('.st-name', has_text='GitHub')).locator('[data-src=web]').count() == 1
    assert await page.locator('.st-row', has=page.locator('.st-name', has_text='VS Code')).locator('.src-ico').count() == 0

    # Apps without place observations remain plain rows.
    notion = page.locator('#pApps .st-row').filter(has=page.get_by_text('Notion', exact=True))
    assert await notion.count() == 1
    assert await notion.get_attribute('data-app-detail') is None
    assert 'app.notion.com' not in await page.locator('#pApps').inner_text()

    assert '25m Drift' in await page.locator('.clean-score').inner_text()
    await capture_panel(page, output, 'browser-sites.png')
    x_row = page.locator('#pApps [data-app-detail="X"]')
    await x_row.click()
    details = page.locator('#explorerView')
    assert await page.locator('#statsMain').is_hidden()
    assert 'Activity details cover 33m of 45m.' in await details.inner_text()
    assert await details.get_by_role('progressbar').get_attribute('value') == '1500000'
    assert 'Seed Library @seed_library' in await details.inner_text()
    assert 'seed swap' in await details.inner_text()
    assert 'Posts visible' not in await details.inner_text()
    await page.evaluate("Panel.render((c,a)=>window.__TAURI__.core.invoke(c,a),'today')")
    assert await details.get_by_role('heading', name='Home feed').is_visible()
    await capture_panel(page, output, 'app-activity-x.png')
    await page.locator('[data-app-detail-back]').click()
    assert await x_row.is_visible()

    await page.locator('[data-see-all]').click()
    await details.wait_for(state='visible')
    assert await details.locator('.st-row').count() == 7
    await capture_panel(page, output, 'panel-explorer.png')
    await page.locator('#expSearchIn').fill('notion')
    assert await details.locator('.st-row').count() == 1
    await page.locator('#expSearchIn').fill('X')
    await details.locator('[data-app-detail="X"]').click()
    await page.keyboard.press('Escape')
    assert await page.locator('#expSearchIn').input_value() == 'X'
    await page.locator('[data-exp-back]').click()
    await page.locator('#statsMain').wait_for(state='visible')

    # Pause audit flags 10m of unmentioned YouTube during a Discord pause as drift.
    pauses_txt = await page.locator('#pPauses').inner_text()
    assert '1 drifted' in pauses_txt and 'YouTube' in pauses_txt
    assert 'ending on youtube.com' in pauses_txt and 'Check-ins by hour' in pauses_txt
    assert 'Bored · 1/5 · <The schedule was already in my calendar>' in pauses_txt
    assert await page.locator('[data-pause-count]').count() == 0
    assert await page.locator('.dl-ring[data-dns-limit]').count() == 1
    # A pause opens to show what was asked for versus how long DNS was off.
    await page.locator('[data-pz]').first.click()
    assert 'Asked for 10m' in await page.locator('.pz-more:not(.pz-feedback)').inner_text()

    # Uncategorized inbox is still saved for agent review.
    inbox = await page.evaluate('window.__uncategorized')
    assert inbox and len(inbox['items']) == 1 and inbox['items'][0]['value'] == 'newtool.example'

    # Range switching (Week shows quiet 7-day stacked bars with amber DNS-off caps) before XSS mutation.
    await page.locator('[data-r=week]').click()
    await page.wait_for_timeout(150)
    assert await page.locator('#pTimelineSec').is_visible()
    assert await page.locator('#pLegend').is_hidden()
    assert await page.locator('.simple-week .col').count() == 7
    assert 'DNS off' in await page.locator('.wk-key').inner_text()
    await capture_panel(page, output, 'panel-week.png')
    await page.locator('[data-r=today]').click()
    await page.wait_for_timeout(150)
    assert await page.locator('#pTimelineSec').is_hidden()

    # Captured profile names and search terms remain plain text.
    await page.evaluate("""() => {
      window.__testHistory.spans.find(s => s.browser?.url.includes('/seed_library')).browser.title =
        '<img src=x onerror=window.__injected=true> (@seed_library) / X';
    }""")
    await page.evaluate("Panel.render((c,a)=>window.__TAURI__.core.invoke(c,a),'today')")
    await x_row.click()
    assert '<img src=x' in await details.inner_text()
    assert await details.locator('.app-detail-row img,.app-detail-row script').count() == 0
    assert await page.evaluate('window.__injected') is None
    await page.locator('[data-app-detail-back]').click()

    dock_bounds = await page.locator('.w').bounding_box()
    stats_bounds = await page.locator('#panel').bounding_box()
    await page.evaluate("""() => {
      const now = Date.now();
      window.__handlers.state({payload: {paused_at: 0, paused_until: 0, now,
        rating_prompt: {paused_at: 17, pause_id: 42, ended_at: now, expires_at: now + 60000}}});
    }""")
    rating = page.get_by_role('region',name='Was that worth it?')
    await rating.wait_for(state='visible')
    assert await rating.evaluate("(element) => getComputedStyle(element).position") == 'fixed'
    assert await page.locator('.w').bounding_box() == dock_bounds
    # Stats can shrink to leave room for the rating while staying anchored.
    await rating.evaluate("""(element) => {
      const stats = document.getElementById('panel').getBoundingClientRect();
      if (stats.bottom > element.getBoundingClientRect().top)
        throw new Error('Pause rating overlaps Stats');
    }""")
    resized_stats = await page.locator('#panel').bounding_box()
    assert all(resized_stats[key] == stats_bounds[key] for key in ('x', 'y', 'width'))
    assert 0 < resized_stats['height'] <= stats_bounds['height']
    assert await rating.locator('button[aria-label="Dismiss rating"] svg.lucide-x').count() == 1
    await rating.get_by_role('textbox',name='Optional rating note').fill('Nothing relevant on X')
    await rating.get_by_role('button',name='1 out of 5').click()
    await rating.wait_for(state='hidden')
    args = await page.evaluate("window.__calls.find(c => c.c === 'rate_pause').args")
    assert args == {'pausedAt':17, 'pauseId':42, 'rating':1, 'note':'Nothing relevant on X'}

    # An in-flight Stats open must stay closed once Ask replaces it.
    await page.keyboard.press('Escape')
    await page.evaluate("window.__handlers['cursor-in']({})")
    await page.locator('.w .amb').wait_for()
    await page.evaluate("""() => {
      window.__delayStats = true;
      document.querySelector('.w .amb').click();
    }""")
    await page.wait_for_function("typeof window.__releaseStats === 'function'")
    await page.locator('.w .judge').click()
    link = page.locator('#dockPauseJudge textarea')
    button = page.locator('#dockPauseJudge button[type=submit]')
    await link.fill('https://x.com/explore')
    await button.click()
    await page.get_by_text('Use a direct link to a public post.').wait_for()
    await link.fill('  https://x.com/example_garden/status/1234567890  ')
    await link.press('Enter')
    card = page.locator('.dock-peek')
    await card.wait_for()
    assert await card.locator('a, button, iframe, video').count() == 0
    assert await link.count() == 0
    assert await card.evaluate("""(card) => {
      const dock = document.querySelector('.w');
      return getComputedStyle(dock).visibility === 'visible'
        && card.getBoundingClientRect().top >= dock.getBoundingClientRect().bottom
        && card.getBoundingClientRect().top === document.querySelector('#panel').offsetTop;
    }""")
    assert await page.evaluate("""window.__calls.filter(c => c.c === 'peek_link').map(c => c.args.url)""") == [
      'https://x.com/explore', 'https://x.com/example_garden/status/1234567890',
    ]
    assert not await page.evaluate("window.__calls.some(c => c.c === 'judge_pause')")
    if output:
      await card.screenshot(path=output / 'peek-laptop.png', animations="disabled")
    await page.evaluate("""async () => {
      window.__releaseStats();
      await new Promise(requestAnimationFrame);
      await new Promise(requestAnimationFrame);
    }""")
    assert 'open' not in (await page.locator('#panel').get_attribute('class'))
    # Escape also works after keyboard focus moves from the post to the dock.
    await page.keyboard.press('Shift+Tab')
    assert await page.evaluate("document.activeElement.closest('.w') !== null")
    await page.keyboard.press('Escape')
    await card.wait_for(state='hidden')
    assert 'open' not in (await page.locator('#panel').get_attribute('class'))

    await page.evaluate("window.__handlers['cursor-in']({})")
    dock_ask = page.locator('.w .judge')
    await dock_ask.press('Enter')
    await link.fill('https://x.com/example_garden/status/1234567890')
    await link.press('Enter')
    await card.wait_for()
    focus_returns = await page.evaluate("window.__calls.filter(c => c.c === 'return_focus').length")
    await dock_ask.press('Enter')
    await link.wait_for()
    await card.wait_for(state='hidden')
    assert await page.evaluate("window.__calls.filter(c => c.c === 'return_focus').length") == focus_returns

    await link.fill('https://x.com/example_garden/status/1234567890')
    await link.press('Enter')
    await card.wait_for()
    await page.locator('.w .chips button[data-m="1"]').press('Enter')
    await card.wait_for(state='hidden')
    await page.evaluate("window.__handlers['cursor-out']({})")
    await page.wait_for_timeout(450)
    assert await page.locator('.w').get_attribute('data-state') == 'form'
    check_in = page.locator('#dockCheckIn button').first
    await check_in.click()
    assert await check_in.get_attribute('aria-pressed') == 'true'
    await page.locator('.w .reason input').press('Escape')

    await page.evaluate("window.__handlers['cursor-in']({})")
    await dock_ask.click()
    await link.fill('Read https://x.com/test/status/12')
    await button.click()
    await page.get_by_text('Check your Gateway settings.').wait_for()
    if output:
      await page.mouse.move(0, 700)
      await page.wait_for_timeout(300)
      await page.locator('.judge-sheet').screenshot(
        path=output / 'ask-retry.png', animations='disabled', caret='hide', omit_background=True,
      )
    assert await page.evaluate("window.__calls.filter(c => c.c === 'judge_pause').length") == 1
    assert not any(c['c'] in ['pause', 'resume', 'lock'] for c in await page.evaluate('window.__calls'))

    # Color-means-bad signals: verify surgical monochrome -> orange (slip) -> red (breach) transitions.
    await page.keyboard.press('Escape')
    await page.evaluate("""async () => {
      const day = new Date('2026-02-12T14:00:00Z'); day.setHours(0, 0, 0, 0);
      const base = day.getTime(), m = 60000;
      const web = (minute, dur, label, url, title) => ({
        s: base + minute * m, e: base + (minute + dur) * m, app: 'Chrome', label, source: 'chromium_extension',
        browser: { url, title, visibility: 'page', audible: false, tab_id: 1, window_id: 1, browser_pid: 123, host_pid: 456 },
      });
      // 1. Over-limit breach day: 72m DNS off vs 60m default limit + overdue habit + heavy Twitch + low rating.
      window.__testHistory = {
        now: base + 14 * 60 * m,
        spans: [
          { s: base + 480 * m, e: base + 660 * m, app: 'VS Code', label: 'VS Code', source: 'window_title' },
          web(660, 72, 'twitch.tv', 'https://twitch.tv/demo_channel', 'demo_channel - Twitch'),
          web(732, 22, 'x.com', 'https://x.com/home', 'Home / X'),
          web(754, 12, 'youtube.com', 'https://youtube.com/shorts/fixture', 'Shorts - YouTube'),
          web(-1440 * 2 + 540, 75, 'twitch.tv', 'https://twitch.tv/demo_channel', 'demo_channel - Twitch'),
        ],
        peeks: [],
        pauses: [
          { t: base + 660 * m, end: base + 702 * m, minutes: 30, reason: 'quick break', check_in: 'bored', rating: 2 },
          { t: base + 702 * m, end: base + 732 * m, minutes: 30, reason: 'check messages', check_in: 'bored', rating: 1 },
          { t: base - 1440 * 2 * m + 540 * m, end: base - 1440 * 2 * m + 615 * m, minutes: 75, reason: 'weekend stream' },
        ],
      };
      window.__handlers.habits({ payload: {
        rows: [{
          habit: { id: 'walk', name: '20m walk outside', interval: 1, weekdays: 0, time: 720, reward: 10 },
          due: true, overdue: true, grace: false, streak: 3, completion: null,
          week: ['done', 'done', 'miss', 'done', 'done', 'done', 'due'],
        }],
        credit: 0, cap: 45,
      } });
      window.__handlers['focus-state-transition']({ payload: { to: 'unfocused' } });
      await new Promise(r => setTimeout(r, 80));
      document.querySelector('.w .amb').click();
    }""")
    await page.locator('#panel.open').wait_for()
    await page.wait_for_timeout(300)

    # DNS-off ring, pill allowance, idle notch, overdue habit, heavy Twitch, low rating all turn red.
    assert await page.locator('.dl-ring.bad').count() == 1
    assert await page.locator('.dl-ring.bad .ring-svg .fill').evaluate("e => getComputedStyle(e).stroke") == 'rgb(239, 68, 68)'
    assert await page.locator('.dl-ring.bad .ring-txt b').evaluate("e => getComputedStyle(e).color") == 'rgb(248, 113, 113)'
    assert '12m over' in await page.locator('.w .a-off.bad').inner_text()
    assert await page.locator('.w').get_attribute('data-tone') == 'bad'
    assert await page.locator('.w').get_attribute('data-focus') == 'unfocused'
    assert await page.locator('#habitSection .habit-breach').count() >= 2
    assert await page.locator('.hero-drift.bad').count() == 1
    assert await page.locator('.st-row', has=page.locator('.st-name', has_text='Twitch')).locator('.n.dns-bad').count() == 1
    assert await page.locator('.st-row', has=page.locator('.st-name', has_text='X')).locator('.n.waste-warn').count() == 1
    assert await page.locator('.pz-sum .tone-bad').count() >= 2
    assert await page.locator('.d-pz-row.bad').count() >= 1
    # Check-in hours stay neutral white, never warning orange.
    checkin_color = await page.locator('.pz-check-hours b').first.evaluate("e => getComputedStyle(e).color")
    assert checkin_color == 'rgb(250, 250, 250)', f'expected neutral check-in hour, got {checkin_color}'
    # Week chart marks over-limit day red with a red dot.
    await page.locator('[data-r=week]').click()
    await page.wait_for_timeout(150)
    assert await page.locator('.wk-off.bad').count() >= 1
    assert await page.locator('.wk-dot').count() >= 1
    await page.locator('[data-r=today]').click()
    await page.wait_for_timeout(150)

    assert not errors, errors
    print('PASS: v8 OLED desktop (thin bar, clickable legend, bento cards, searchable Explorer, Week stacked bars, openable pauses, XSS escaping, Esc, surgical orange/red signals) verified.')

if __name__ == "__main__":
  asyncio.run(main())
