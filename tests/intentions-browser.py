"""Exercise desktop Intentions flows and stats section with fictional data."""
import asyncio

from screenshot_browser import BUILD, capture_panel, fixture_browser, fixture_page

MOCK = r'''(() => {
  const day = new Date('2026-02-12T14:00:00Z');
  day.setHours(0, 0, 0, 0);
  const base = day.getTime(), m = 60000;
  const at = (h, min = 0) => base + (h * 60 + min) * m;
  const now = at(13, 34);
  const web = (s, e, label, url, title, audible = false) => ({
    s, e, app: 'Chrome', label, source: 'chromium_extension',
    browser: { url, title, visibility: 'page', audible, tab_id: 1, window_id: 1, browser_pid: 123, host_pid: 456 },
  });

  window.__intentState = {
    active: null,
    time_up: false,
    no_intention_active_ms: 8 * m,
    nag_active: false,
    now,
  };
  window.__testHistory = {
    now,
    spans: [
      { s: at(9, 10), e: at(9, 51), app: 'VS Code', label: 'VS Code', source: 'window_title' },
      web(at(9, 51), at(10, 5), 'github.com', 'https://github.com/example-org/fairgrade/pull/12', 'FairGrade rubric PR · GitHub'),
      { s: at(10, 5), e: at(10, 34), app: 'VS Code', label: 'VS Code', source: 'window_title' },
      { s: at(10, 34), e: at(10, 42), app: 'Slack', label: 'Slack', source: 'window_title' },
      web(at(11, 20), at(11, 26), 'www.youtube.com', 'https://www.youtube.com/watch?v=demo-fold', 'Flatten a pizza box - YouTube', true),
      web(at(12, 10), at(12, 24), 'x.com', 'https://x.com/home', 'Home / X'),
      web(at(12, 24), at(12, 32), 'twitch.tv', 'https://twitch.tv/demo_stream', 'demo_stream - Twitch'),
      web(at(13, 0), at(13, 21), 'docs.google.com', 'https://docs.google.com/document/d/demo', 'Gepeto launch post - Google Docs'),
      web(at(13, 21), at(13, 30), 'x.com', 'https://x.com/home', 'Home / X'),
      { s: at(13, 30), e: at(13, 34), app: 'Slack', label: 'Slack', source: 'window_title' },
    ],
    peeks: [],
    pauses: [
      { t: at(11, 20), end: at(11, 24), minutes: 4, reason: 'Find how to fold pizza boxes on YouTube', check_in: 'specific', rating: 5 },
    ],
    intentions: [
      {
        id: 'int-1', start: at(9, 10), end: at(10, 42), planned_until: at(10, 40), planned_minutes: 90,
        label: 'FairGrade rubric PR', done_when: 'PR open', raw: 'FairGrade rubric PR, 1h 30m, done when PR open',
        parse_source: 'gateway', outcome: 'done', extensions: 0, active: false,
      },
      {
        id: 'int-2', start: at(11, 0), end: at(11, 45), planned_until: at(11, 45), planned_minutes: 45,
        label: 'Cleaning house', done_when: 'trash is out', raw: 'cleaning the house, 45 min, done when the trash is out',
        parse_source: 'gateway', outcome: 'done', extensions: 0, active: false,
      },
      {
        id: 'int-3', start: at(13, 0), end: null, planned_until: at(14, 0), planned_minutes: 60,
        label: 'Gepeto launch post', done_when: 'draft sent to Ana', raw: 'Gepeto launch post, 1h, done when draft sent to Ana',
        parse_source: 'gateway', outcome: null, extensions: 0, active: true,
      },
    ],
  };

  window.__calls = [];
  window.__handlers = {};
  window.__TAURI__ = {
    core: {
      invoke: async (c, args) => {
        window.__calls.push({ c, args });
        if (c === 'history') return structuredClone(window.__testHistory);
        if (c === 'get_state') return { paused_at: 0, paused_until: 0, now, dry_run: true, dns_configured: true };
        if (c === 'pause_judge_settings') return { enabled: true, model: 'anthropic/claude-haiku-5.5', has_gateway_key: true, has_nextdns_key: true };
        if (c === 'activity_rules_load') return { version: 1, rules: [] };
        if (c === 'habits_view') return { rows: [], credit: 0, cap: 45, wallet_active: false };
        if (c === 'intention_state') return structuredClone(window.__intentState);
        if (c === 'intention_parse' || c === 'intention_parse_local') {
          return {
            label: 'Cleaning house',
            planned_minutes: 45,
            until: Date.now() + 45 * m,
            done_when: 'the trash is out',
            raw: args.text,
            parse_source: 'gateway',
          };
        }
        if (c === 'intention_start') {
          const rec = {
            id: 'int-live',
            start: Date.now(),
            end: null,
            planned_until: Date.now() + args.plannedMinutes * m,
            planned_minutes: args.plannedMinutes,
            label: args.label,
            done_when: args.doneWhen ?? null,
            raw: args.raw,
            parse_source: args.parseSource || 'gateway',
            outcome: null,
            extensions: 0,
            active: true,
          };
          window.__intentState = { active: rec, time_up: false, no_intention_active_ms: 0, nag_active: false, now: Date.now() };
          return structuredClone(window.__intentState);
        }
        if (c === 'intention_extend') {
          const active = window.__intentState.active;
          if (active) {
            active.planned_until = Date.now() + (args.minutes || 10) * m;
            active.extensions = (active.extensions || 0) + 1;
            window.__intentState.time_up = false;
          }
          return structuredClone(window.__intentState);
        }
        if (c === 'intention_end') {
          window.__intentState = { active: null, time_up: false, no_intention_active_ms: 0, nag_active: false, now: Date.now() };
          return structuredClone(window.__intentState);
        }
        return null;
      },
    },
    event: { listen: async (name, handler) => { window.__handlers[name] = handler; return () => {}; } },
  };
})();'''


async def capture_dock(page, output, name, height=190):
    if output is None:
        return
    await page.screenshot(
        path=output / name,
        animations="disabled",
        caret="hide",
        omit_background=True,
        clip={"x": 0, "y": 0, "width": 820, "height": height},
    )


async def main(output=None):
    async with fixture_browser() as context:
        await context.add_init_script(MOCK)
        page = await fixture_page(context)
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        await page.goto((BUILD / "index.html").as_uri())
        await page.wait_for_timeout(300)

        # 1. Hover with no intention set (under 15m -> neutral monochrome button).
        await page.evaluate("window.__handlers['cursor-in']({})")
        await page.wait_for_timeout(350)
        intent_btn = page.locator("#intentBtn")
        await intent_btn.wait_for(state="visible")
        assert await intent_btn.inner_text() == "What are you on?"
        assert "warn" not in (await intent_btn.get_attribute("class") or "")
        await capture_dock(page, output, "intentions-hover-empty.png", height=72)

        # 2. Type one sentence -> read-back card ("Got it").
        await intent_btn.click()
        await page.wait_for_timeout(200)
        inp = page.locator("#intentInput")
        await inp.wait_for(state="visible")
        await inp.fill("cleaning the house, 45 min, done when the trash is out")
        await inp.press("Enter")
        card = page.locator("#intentCard")
        await card.wait_for(state="visible")
        await page.wait_for_timeout(250)
        card_txt = await card.inner_text()
        assert "Got it" in card_txt and "Cleaning house" in card_txt and "the trash is out" in card_txt
        await capture_dock(page, output, "intentions-readback.png", height=190)

        # 3. Start -> active intention pill in hover bar.
        await card.locator("[data-intent-start]").click()
        await card.wait_for(state="hidden")
        pill = page.locator("#intentPill")
        await pill.wait_for(state="visible")
        await page.wait_for_timeout(250)
        assert "Cleaning house" in await pill.inner_text()
        assert "45m" in await pill.inner_text()
        await capture_dock(page, output, "intentions-active-pill.png", height=72)

        # 3b. Active intention off-plan (Gepeto launch post + X 9m off-plan in orange).
        await page.evaluate("""() => {
          const now = Date.now(), m = 60000;
          const active = {
            id: 'int-post', start: now - 34 * m, end: null, planned_until: now + 26 * m,
            planned_minutes: 60, label: 'Gepeto launch post', done_when: 'draft sent to Ana',
            raw: 'Gepeto launch post 1h', parse_source: 'gateway', outcome: null, extensions: 0, active: true,
          };
          window.__testHistory.spans.push({
            s: now - 9 * m, e: now, app: 'Chrome', label: 'x.com', source: 'chromium_extension',
            browser: { url: 'https://x.com/home', title: 'Home / X', visibility: 'page', audible: false, tab_id: 1, window_id: 1, browser_pid: 1, host_pid: 2 },
          });
          window.__intentState = { active, time_up: false, no_intention_active_ms: 0, nag_active: false, now };
          window.__handlers['cursor-in']({});
          window.__handlers['intention-state']({ payload: window.__intentState });
        }""")
        await page.wait_for_timeout(300)
        off_plan = page.locator("#intentOffPlan")
        await off_plan.wait_for(state="visible")
        assert "X 9m off-plan" in await off_plan.inner_text()
        assert await page.locator(".w").get_attribute("data-tone") == "warn"
        await capture_dock(page, output, "intentions-off-plan.png", height=72)

        # 4. Time is up card ("Cleaning house · 45m" / "Is the trash out?").
        await page.evaluate("""() => {
          window.__testHistory.spans.pop();
          const now = Date.now(), m = 60000;
          const active = {
            id: 'int-clean', start: now - 45 * m, end: null, planned_until: now - 1000,
            planned_minutes: 45, label: 'Cleaning house', done_when: 'the trash is out',
            raw: 'cleaning the house 45m', parse_source: 'gateway', outcome: null, extensions: 0, active: true,
          };
          window.__intentState = { active, time_up: true, no_intention_active_ms: 0, nag_active: false, now };
          window.__handlers['cursor-in']({});
          window.__handlers['intention-state']({ payload: window.__intentState });
        }""")
        await card.wait_for(state="visible")
        await page.wait_for_timeout(300)
        up_txt = await card.inner_text()
        assert "Cleaning house · 45m" in up_txt
        assert "Is the trash out?" in up_txt
        for label in ("Done", "Partly", "10 more min", "No"):
            assert await card.get_by_role("button", name=label, exact=True).is_visible()
        await capture_dock(page, output, "intentions-time-up.png", height=185)

        # Test "10 more min" extends once and closes the card, then Done records outcome.
        await card.get_by_role("button", name="10 more min", exact=True).click()
        await card.wait_for(state="hidden")
        assert "10m" in await pill.inner_text()

        # 5. "No intention · 18m" orange state (nag active + orange idle pill).
        await page.evaluate("""() => {
          const now = Date.now(), m = 60000;
          window.__intentState = { active: null, time_up: false, no_intention_active_ms: 18 * m, nag_active: true, now };
          window.__handlers['intention-state']({ payload: window.__intentState });
          window.__handlers['cursor-in']({});
        }""")
        await page.wait_for_timeout(300)
        await intent_btn.wait_for(state="visible")
        assert "No intention · 18m" in await intent_btn.inner_text()
        assert "warn" in (await intent_btn.get_attribute("class") or "")
        assert await page.locator(".w").get_attribute("data-tone") == "warn"
        await capture_dock(page, output, "intentions-nag-orange.png", height=72)

        # 6. Stats panel Intentions section above Screen time.
        await page.evaluate("""() => {
          const now = Date.now(), m = 60000;
          const active = {
            id: 'int-3', start: now - 34 * m, end: null, planned_until: now + 26 * m,
            planned_minutes: 60, label: 'Gepeto launch post', done_when: 'draft sent to Ana',
            raw: 'Gepeto launch post, 1h, done when draft sent to Ana', parse_source: 'gateway',
            outcome: null, extensions: 0, active: true,
          };
          window.__intentState = { active, time_up: false, no_intention_active_ms: 0, nag_active: false, now };
          window.__handlers['intention-state']({ payload: window.__intentState });
        }""")
        await page.wait_for_timeout(200)
        await page.locator(".w .amb").click()
        await page.locator("#panel.open").wait_for()
        await page.wait_for_timeout(550)
        sec = page.locator("#pIntentionsSec")
        await sec.wait_for(state="visible")
        sec_txt = await sec.inner_text()
        assert "FairGrade rubric PR" in sec_txt
        assert "Cleaning house" in sec_txt
        assert "No intention" in sec_txt
        assert "Gepeto launch post" in sec_txt
        assert "2 done, 1 running" in await page.locator("[data-intent-summary]").inner_text()
        assert "22m unclear" in await page.locator("[data-intent-unclear]").inner_text()
        assert await page.locator("[data-intent-unclear]").evaluate("e => getComputedStyle(e).color") == "rgb(245, 158, 11)"
        # Verify Intentions appears above Screen time and Week hides it cleanly.
        assert await page.evaluate("""() => {
          const i = document.getElementById('pIntentionsSec').getBoundingClientRect();
          const a = document.getElementById('pAppsCard').getBoundingClientRect();
          return i.bottom <= a.top + 2;
        }""")
        await capture_panel(page, output, "intentions-stats-today.png")

        await page.locator("[data-r=week]").click()
        await page.wait_for_timeout(150)
        assert await sec.is_hidden()

        assert not errors, errors
        print("PASS: desktop Intentions flows (hover, read-back, active pill, off-plan, time-up, 18m orange nag, Today stats section) verified.")


if __name__ == "__main__":
    asyncio.run(main())
