"""Exercise the actual Windows UI with synthetic device responses; never changes system DNS."""
import asyncio
from pathlib import Path

from screenshot_browser import BUILD, capture_panel, fixture_browser, fixture_page

MOCK = (Path(__file__).parent / "fixtures" / "device-panel.mock.js").read_text(encoding="utf-8")
async def main(output=None):
 async with fixture_browser() as context:
  await context.add_init_script(MOCK)
  page=await fixture_page(context);errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  await page.goto((BUILD/'index.html').as_uri())
  await page.locator('#panel.open').wait_for();await page.wait_for_timeout(600)
  total_all=await page.locator('[data-total]').inner_text()
  assert total_all!='0m' and 'building' in await page.locator('[data-build-pct]').inner_text()
  if await page.locator('[data-see-all-pauses]').count(): await page.locator('[data-see-all-pauses]').click()
  assert 'Netflix' in await page.locator('#pPauses').inner_text()  # TV app exceptions name the app
  await page.locator('#deviceFilter').click()
  assert await page.locator('#deviceMenu [data-slot=dropdown-menu-checkbox-item-indicator] svg.lucide').count()==3
  assert await page.locator('#deviceSettings svg.lucide-settings').count()==1
  await page.get_by_role('menuitemcheckbox',name='Juniper TV').click()
  await page.wait_for_function(f"document.querySelector('[data-total]').textContent!=='{total_all}'")
  assert await page.locator('#deviceFilterLabel').inner_text()=='Laptop'
  await page.get_by_role('menuitemcheckbox',name='Laptop',exact=True).click()
  await page.wait_for_function("document.querySelector('#deviceFilterLabel').textContent==='No devices'")
  assert 'No device data selected.' in await page.locator('#pApps').inner_text()
  await page.get_by_role('menuitemcheckbox',name='All devices',exact=True).click()
  await page.keyboard.press('Escape');await page.locator('#deviceMenu').wait_for(state='hidden')
  assert 'open' in await page.locator('#panel').get_attribute('class')
  await page.locator('#deviceFilter').click()
  await capture_panel(page, output, 'device-filter.png')
  await page.keyboard.press('Escape')
  await page.locator('#deviceSettings').click()
  assert not await page.locator('#statsPage').is_visible()
  await page.locator('[data-stab="laptop"]').click()
  setup_chrome = page.get_by_role('button', name='Set up Chrome')
  await setup_chrome.scroll_into_view_if_needed()
  await capture_panel(page, output, 'chrome-setup-entry.png')
  await page.evaluate('window.__failBrowserSetup=true')
  await setup_chrome.click()
  await page.get_by_role('alert').get_by_text('Could not prepare the Chrome extension.').wait_for()
  await page.evaluate('window.__failBrowserSetup=false')
  await setup_chrome.click()
  folder = page.get_by_role('textbox', name='Extension folder')
  await folder.wait_for()
  assert await folder.input_value() == r'C:\Users\Spruce\AppData\Local\FocusDNS\browser-bridge\extension'
  await page.get_by_text('Load unpacked', exact=True).wait_for()
  await page.evaluate("Object.defineProperty(navigator, 'clipboard', {value: {writeText: async text => {window.__copied = text;}}})")
  await page.get_by_role('button', name='Copy path').click()
  await page.get_by_role('status').get_by_text('Copied', exact=True).wait_for()
  assert await page.evaluate('window.__copied') == await folder.input_value()
  await capture_panel(page, output, 'chrome-setup-ready.png')
  await page.locator('[data-stab="device:tv-one"]').click()
  assert await page.locator('#dnsProfile').input_value()=='f0c057'
  assert await page.get_by_role('button',name='Restore previous DNS').count()==0
  youtube=page.locator('[data-package="tv.youtube"]')
  await youtube.locator('[data-slot=collapsible-trigger]').first.click()
  await youtube.get_by_role('checkbox',name='Block app',exact=True).check()
  await youtube.get_by_role('button',name='Save',exact=True).click()
  await page.wait_for_function("window.__devices[0].snapshot.apps[0].blocked===true")
  await page.wait_for_timeout(100)
  assert 'Blocked' in await youtube.locator('[data-slot=collapsible-trigger]').first.inner_text()
  # Rule fields are real controls; the server receives every existing TV policy option.
  await youtube.locator('[name="limit"]').fill('45')
  await youtube.get_by_role('checkbox',name='Block on schedule',exact=True).check()
  await youtube.get_by_role('button',name='Save',exact=True).click()
  await page.wait_for_timeout(120)
  last=await page.evaluate("window.__calls.filter(c=>c.c==='device_command').at(-1).args.command")
  assert last['daily_limit_ms']==45*60000 and last['scheduled'] and last['from_minute']==1320 and last['to_minute']==420
  await page.get_by_role('button',name='30m',exact=True).last.click()
  pause=page.locator('#pauseFormSlot form')
  await pause.locator('[name="reason"]').fill('Watch the pottery demo')
  assert await pause.get_by_role('button',name='Pause',exact=True).is_disabled()
  await pause.get_by_role('button',name='Need something specific',exact=True).click()
  await pause.get_by_role('button',name='Pause',exact=True).click();await page.wait_for_timeout(120)
  last=await page.evaluate("window.__calls.filter(c=>c.c==='device_command'&&c.args.command.action==='dns_pause').at(-1).args.command")
  assert last['reason']=='Watch the pottery demo' and last['check_in']=='specific'
  assert 'Paused' in await page.locator('#remoteDnsStatus').inner_text()
  await page.get_by_role('button',name='Turn on',exact=True).last.click();await page.wait_for_timeout(120)
  await youtube.get_by_role('button',name='Allow temporarily',exact=True).click()
  app_pause=youtube.locator('.app-pause-slot form');assert await app_pause.is_visible()
  await app_pause.locator('[name="reason"]').fill('Finish this episode')
  await app_pause.get_by_role('button',name='Pause',exact=True).click();await page.wait_for_timeout(120)
  assert await youtube.get_by_role('button',name='End exception').is_visible()
  # Failed saves retain drafts, show recovery and disable controls until refreshed.
  await page.evaluate('window.__fail=true')
  await page.locator('#dnsProfile').fill('abcdef')
  await page.locator('#dnsProfileForm').get_by_role('button',name='Save',exact=True).click();await page.wait_for_timeout(120)
  assert await page.locator('#dnsProfile').input_value()=='abcdef'
  assert 'TV unreachable' in await page.locator('#settingsError').inner_text()
  assert await youtube.get_by_role('button',name='Save',exact=True).is_disabled()
  await page.evaluate('window.__fail=false')
  await page.locator('#refreshSettings').click();await page.wait_for_timeout(150)
  assert await page.locator('#dnsProfile').input_value()=='abcdef'
  assert not await youtube.get_by_role('button',name='Save',exact=True).is_disabled()
  # Explicit refresh preserves an unsaved edit. Reopen to use the TV's confirmed values.
  await page.locator('#backToStats').click();await page.locator('#deviceSettings').click()
  await page.locator('[data-stab="device:tv-one"]').click()
  await capture_panel(page, output, 'device-rule-settings.png')
  await youtube.locator('[data-slot=collapsible-trigger]').first.click()
  await page.locator('#dnsProfile').fill('f0c057')
  await page.wait_for_timeout(250)
  await capture_panel(page, output, 'device-settings.png')
  # Discovery replaces address entry. Approval can only complete through the TV.
  await page.locator('#connectTv').click()
  pairing=page.get_by_role('region',name='Connect a nearby device')
  await pairing.get_by_text('Maple TV',exact=True).wait_for()
  if output:
   await pairing.screenshot(path=output / 'device-nearby.png', animations='disabled')
  assert await page.locator('#tvAddress,#pairCode,#confirmIdentity').count()==0
  await pairing.get_by_role('button',name='Connect',exact=True).click()
  await pairing.get_by_text('284 619',exact=True).wait_for()
  assert len(await page.evaluate('window.__devices'))==1
  assert await pairing.get_by_role('button',name='Allow',exact=True).count()==0
  await pairing.get_by_role('button',name='Cancel',exact=True).click()
  await page.wait_for_function("window.__calls.some(c=>c.c==='device_cancel_approval')")
  await pairing.get_by_role('button',name='Connect',exact=True).click()
  await pairing.get_by_text('284 619',exact=True).wait_for()
  await page.evaluate("window.__approvalStatus='declined'")
  await pairing.get_by_text('Connection declined on Maple TV.',exact=True).wait_for()
  assert len(await page.evaluate('window.__devices'))==1
  await pairing.get_by_role('button',name='Try again',exact=True).click()
  await pairing.get_by_role('button',name='Connect',exact=True).click()
  await pairing.get_by_text('284 619',exact=True).wait_for()
  if output:
   await pairing.screenshot(path=output / 'device-pairing.png', animations='disabled')
  await page.evaluate("window.__approvalStatus='approved'")
  await page.wait_for_function("window.__devices.length===2")
  await page.wait_for_function("document.querySelector('#settingsPage').textContent.includes('Maple TV')")
  assert not await pairing.count()
  await page.get_by_role('button',name='Forget device',exact=True).click()
  await page.locator('#confirmForget').click();await page.wait_for_timeout(100)
  assert len(await page.evaluate('window.__devices'))==1
  # Remote names remain plain text in the dropdown and settings.
  await page.evaluate("window.__devices[0].name='<img src=x onerror=window.__injected=true>';window.__handlers.devices({payload:structuredClone(window.__devices)})")
  await page.locator('#backToStats').click();await page.locator('#deviceFilter').click()
  assert await page.locator('#deviceMenu img').count()==0
  assert await page.evaluate('window.__injected') is None
  await page.keyboard.press('Escape');await page.locator('#deviceMenu').wait_for(state='hidden')
  await page.evaluate("window.__devices[0].snapshot.spans[0].app='tv.malicious';window.__devices[0].snapshot.spans[0].label='<img src=x onerror=window.__tooltipInjected=true>';window.__handlers.devices({payload:structuredClone(window.__devices)})")
  await page.wait_for_timeout(100)
  assert await page.locator('#pApps img').count()==0
  assert await page.evaluate('window.__tooltipInjected') is None
  await page.locator('#pApps summary').first.hover()
  assert await page.locator('#tip img').count()==0
  assert await page.evaluate('window.__tooltipInjected') is None
  await page.locator('[data-r=week]').click();await page.locator('[data-r=today]').click()
  await page.keyboard.press('Escape');assert 'open' not in await page.locator('#panel').get_attribute('class')
  assert not errors,errors
  assert not any(c['c'] in ['pause','resume','lock'] for c in await page.evaluate('window.__calls'))
  print('PASS: Chrome setup and retry, real UI filter, independent totals, two-pane settings, TV blocks/limits/schedules, DNS and app pauses, required reasons, failed-save drafts, offline controls, nearby discovery, TV-only approval, cancellation and decline, forget, escaped names, Today/Week and Escape.')

if __name__ == "__main__":
 asyncio.run(main())
