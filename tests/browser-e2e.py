"""Real Chromium + unpacked MV3 extension + real native host. Isolated Linux profile.
Run: uv run --with playwright python tests/browser-e2e.py
No daily profile, Windows registry, DNS, ADB or SDK changes.
"""
import asyncio, base64, io, json, os, pathlib, tempfile, time, wave
from playwright.async_api import async_playwright
ROOT = pathlib.Path(__file__).resolve().parents[1]
ID = 'mfafgohdpfnknfgedjiicnmgomjpdock'
CHROME = os.environ.get('FOCUS_TEST_CHROME', str(pathlib.Path.home()/'.cache/ms-playwright/chromium-1254/chrome-linux64/chrome'))
EXTENSION = pathlib.Path(os.environ.get('FOCUS_TEST_EXTENSION', ROOT/'extension')).resolve()

async def verify_gate(context, worker, data, allowed):
    state = data/'FocusDNS/state.json'
    profile = 'a1b2c3'
    state.with_name('nextdns.json').write_text(json.dumps({
        'profile': profile, 'legacyConfirmed': False,
        'servers': ['192.0.2.10', '198.51.100.20', '2001:db8::10', '2001:db8::20'],
    }))
    def save(paused_until=0, ended_at=None):
        tmp = state.with_suffix('.tmp')
        tmp.write_text(json.dumps({'paused_until': paused_until, 'pause_ended_at': ended_at, 'profile': profile}))
        tmp.replace(state)

    await worker.evaluate("""() => {
      globalThis.fetch = async (url) => {
        const host = url.searchParams.get('name');
        const blocked = host === 'focus-blocked.example.org';
        return {ok: true, json: async () => ({
          Status: 0, Question: [{name: host + '.', type: 1}],
          Answer: [{type: 1, data: blocked ? '0.0.0.0' : '192.0.2.1', TTL: 60}],
        })};
      };
    }""")
    # An elapsed deadline alone does not end a pause while DNS-on is still pending.
    save(int(time.time()*1000)-1000)
    wav = io.BytesIO()
    with wave.open(wav, 'wb') as audio:
        audio.setparams((1, 1, 8000, 0, 'NONE', 'not compressed'))
        audio.writeframes(b'\x80'*8000)
    player = '<audio loop autoplay src="data:audio/wav;base64,' + base64.b64encode(wav.getvalue()).decode() + '"></audio>'
    site = 'https://focus-blocked.example.org/'
    embedded = 'https://focus-media.example.org/player'
    await context.route(site+'**', lambda route: route.fulfill(
        body='<html><title>Blocked test page</title><input><div style="height:2000px"></div>'
             + player + f'<iframe src="{embedded}"></iframe></html>', content_type='text/html'))
    await context.route(site+'history', lambda route: route.fulfill(
        body='<title>History test</title><p>Cached page</p>', content_type='text/html'))
    await context.route(embedded, lambda route: route.fulfill(body=player, content_type='text/html'))
    async with context.expect_page() as new_tab:
        await worker.evaluate("chrome.tabs.create({active:false})")
    blocked = await new_tab.value
    await blocked.goto(site)
    await blocked.evaluate("""async () => {
      const host = document.body.appendChild(document.createElement('div'));
      const root = host.attachShadow({mode:'closed'});
      window.shadowPlayer = root.appendChild(document.querySelector('audio').cloneNode());
      await window.shadowPlayer.play();
      window.lateShadowHost = document.body.appendChild(document.createElement('div'));
    }""")
    await blocked.locator('input').fill('synthetic unsaved work')
    await blocked.evaluate('scrollTo(0, 250)')
    cached = await context.new_page()
    await cached.goto(site+'history')
    await cached.evaluate('window.addEventListener("pageshow", e => { window.restoredFromCache = e.persisted; })')
    await cached.goto('https://focus-test.example.org/away')
    await allowed.bring_to_front()
    await blocked.wait_for_function('!document.querySelector("audio").paused', polling=50)
    embedded_player = blocked.frame_locator('iframe').locator('audio')
    assert await embedded_player.evaluate('(audio) => !audio.paused')
    gate = blocked.get_by_role('dialog', name='DNS is back on. This site is blocked.')
    async def wait_for_mute(expected):
        for _ in range(35):
            muted = await worker.evaluate("""async (url) => {
              const tab = (await chrome.tabs.query({})).find((tab) => tab.url === url);
              return tab?.mutedInfo?.muted === true;
            }""", site)
            if muted == expected:
                return
            await asyncio.sleep(.1)
        raise AssertionError(f'Tab mute did not reach {expected}')
    assert not await gate.count()
    ended_at = int(time.time()*1000)
    save(ended_at=ended_at)
    await gate.wait_for(timeout=3500)
    await wait_for_mute(True)
    assert await blocked.evaluate('document.querySelector("audio").paused')
    assert await blocked.evaluate('window.shadowPlayer.paused')
    await blocked.evaluate('window.shadowPlayer.play().catch(() => {})')
    assert await blocked.evaluate('window.shadowPlayer.paused')
    assert await embedded_player.evaluate('(audio) => audio.paused')
    await blocked.bring_to_front()
    await blocked.evaluate("""async () => {
      const root = window.lateShadowHost.attachShadow({mode:'closed'});
      window.lateShadowPlayer = root.appendChild(document.querySelector('audio').cloneNode());
      await window.lateShadowPlayer.play().catch(() => {});
    }""")
    await blocked.wait_for_function('window.lateShadowPlayer.paused', timeout=5000)
    await blocked.keyboard.press('Escape')
    await blocked.keyboard.type('ignored')
    assert await gate.is_visible()
    assert not await allowed.get_by_role('dialog').count()
    await cached.go_back(wait_until='commit')
    await cached.get_by_role('dialog').wait_for(timeout=3500)
    assert await cached.evaluate('window.restoredFromCache === true')
    await blocked.evaluate('window.gateTestMarker = 123')
    fresh = await context.new_page()
    await fresh.goto(site+'fresh')
    await fresh.get_by_role('dialog').wait_for(timeout=3500)
    save(ended_at+60_000)
    await gate.wait_for(state='detached', timeout=3500)
    await fresh.get_by_role('dialog').wait_for(state='detached', timeout=3500)
    await cached.get_by_role('dialog').wait_for(state='detached', timeout=3500)
    await wait_for_mute(False)
    assert await blocked.evaluate('window.gateTestMarker === 123 && scrollY === 250')
    assert await blocked.locator('input').input_value() == 'synthetic unsaved work'
    assert await blocked.evaluate('document.querySelector("audio").paused')
    await blocked.close()
    await cached.close()
    await fresh.close()
    state.unlink()

async def main():
    with tempfile.TemporaryDirectory(prefix='focus-browser-e2e-') as tmp:
        base = pathlib.Path(tmp)
        host = ROOT/'browser-host/target/debug/focus-browser-host'
        assert host.exists(), 'cargo build --manifest-path browser-host/Cargo.toml first'
        # Chromium searches the overridden user-data-dir for user native hosts.
        reg = base/'profile'/'NativeMessagingHosts'; reg.mkdir(parents=True)
        (reg/'org.nightingalelab.focusdns.json').write_text(json.dumps({'name':'org.nightingalelab.focusdns','description':'isolated test host','path':str(host),'type':'stdio','allowed_origins':[f'chrome-extension://{ID}/']}))
        data = base/'data'; data.mkdir()
        env = {**os.environ, 'HOME':str(base), 'XDG_CONFIG_HOME':str(base/'config'), 'LOCALAPPDATA':str(data)}
        async with async_playwright() as p:
            context = await p.chromium.launch_persistent_context(str(base/'profile'), executable_path=CHROME, headless=True, env=env, ignore_default_args=['--disable-back-forward-cache'], args=[f'--disable-extensions-except={EXTENSION}', f'--load-extension={EXTENSION}', '--no-sandbox', '--autoplay-policy=no-user-gesture-required'])
            try:
                worker = context.service_workers[0] if context.service_workers else await context.wait_for_event('serviceworker')
                await context.route('https://focus-test.example.org/**', lambda route: route.fulfill(body='<html><title>Focus test project</title><h1>Project</h1></html>',content_type='text/html'))
                page = context.pages[0]
                await page.goto('https://focus-test.example.org/project?token=synthetic-secret&tab=readme')
                await page.bring_to_front()
                async def snapshots():
                    result=[]
                    for f in (data/'FocusDNS/browser/live').glob('*.json'):
                        try: result.append(json.loads(f.read_text()))
                        except (ValueError, FileNotFoundError): pass
                    return result
                async def wait_for(predicate):
                    for _ in range(80):
                        values = await snapshots()
                        for s in values:
                            if predicate(s['event']): return s
                        await asyncio.sleep(.1)
                    status = await worker.evaluate('chrome.storage.session.get(null)'); diagnostic = await worker.evaluate("chrome.runtime.sendNativeMessage('org.nightingalelab.focusdns',{}).catch(e=>e.message)")
                    raise AssertionError(f'No matching snapshot; status={status}, diagnostic={diagnostic}, snapshots={await snapshots()}')
                first = await wait_for(lambda e: e.get('title')=='Focus test project')
                assert first['event']['url'] == 'https://focus-test.example.org/project?token=REDACTED&tab=readme'
                assert first['browser_pid'] > 0
                await page.evaluate("history.pushState({}, '', '/project/spa?tab=issues');document.title='SPA project'")
                spa = await wait_for(lambda e: '/project/spa' in (e.get('url') or '') and e.get('title')=='SPA project')
                await worker.evaluate("chrome.tabs.create({url:'https://focus-test.example.org/background-only',active:false})")
                await asyncio.sleep(.5)
                journal = ''.join(f.read_text() for f in (data/'FocusDNS/browser/events').glob('*.jsonl'))
                assert '/background-only' not in journal
                await worker.evaluate('chrome.storage.local.set({enabled:false})')
                paused = await wait_for(lambda e: not e['enabled'])
                assert paused['event']['url'] is None and paused['event']['title'] is None
                await page.evaluate("history.pushState({}, '', '/paused-private');document.title='Paused private page'")
                await asyncio.sleep(.4)
                journal = ''.join(f.read_text() for f in (data/'FocusDNS/browser/events').glob('*.jsonl'))
                for private in ('synthetic-secret', 'Paused private page', '/paused-private'):
                    assert private not in journal, [line for line in journal.splitlines() if private in line]
                await worker.evaluate('chrome.storage.local.set({enabled:true})')
                await wait_for(lambda e: e['enabled'] and e.get('title')=='Paused private page')
                # Native port disconnect: process must be reconstructed with latest state.
                old = (await snapshots())[0]['host_pid']
                os.kill(old, 15)
                await page.evaluate("document.title='After reconnect'")
                recovered = await wait_for(lambda e: e.get('title')=='After reconnect')
                assert recovered['host_pid'] != old
                await verify_gate(context, worker, data, page)
                # Full browser restart also discards the MV3 worker/native port.
                await worker.evaluate('chrome.storage.local.set({enabled:false})')
                await wait_for(lambda e: not e['enabled'])
                await context.close()
                context = await p.chromium.launch_persistent_context(str(base/'profile'), executable_path=CHROME, headless=True, env=env, args=[f'--disable-extensions-except={EXTENSION}', f'--load-extension={EXTENSION}', '--no-sandbox'])
                await context.route('https://focus-test.example.org/**', lambda route: route.fulfill(body='<html><title>Focus test project</title><h1>Project</h1></html>',content_type='text/html'))
                worker = context.service_workers[0] if context.service_workers else await context.wait_for_event('serviceworker')
                page = context.pages[0]
                await page.goto('https://focus-test.example.org/after-browser-restart')
                await wait_for(lambda e: not e['enabled'])
                await worker.evaluate('chrome.storage.local.set({enabled:true})')
                await wait_for(lambda e: '/after-browser-restart' in (e.get('url') or ''))
                events = [json.loads(line) for f in (data/'FocusDNS/browser/events').glob('*.jsonl') for line in f.read_text().splitlines()]
                assert all(e['event']['url'] is None and e['event']['title'] is None for e in events if not e['event']['enabled'])
                report={'runtime':'real Chromium + unpacked extension + native host, isolated Linux profile', 'checks':['active URL/title delivered','credentials redacted','SPA URL and title delivered','background tab excluded','pause clears live identity','paused navigation not journaled','resume captures current page','native-host restart reconnects without replay','browser restart preserves pause and reconstructs current page','DNS resume covers a background tab, a fresh navigation and a back/forward-cache restore, pauses top-level and cross-origin HTML media, and preserves scroll and unsaved input through the next pause'], 'example':{'browser_exe':first['browser_exe'],'url':first['event']['url'],'title':first['event']['title']}, 'event_count':len(events)}
                print(json.dumps(report,indent=2))
            finally:
                await context.close()

asyncio.run(main())
