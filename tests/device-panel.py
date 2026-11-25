"""Exercise the actual Windows UI with synthetic device responses; never changes system DNS."""
import asyncio

from screenshot_browser import BUILD, capture_panel, fixture_browser, fixture_page

MOCK = r'''(() => {
 const m=60000, d=new Date('2026-02-12T14:00:00Z');d.setHours(0,0,0,0);const base=d.getTime();
 const now=base+14*60*m,s=base+9*60*m;
 window.__calls=[];window.__fail=false;
 window.__history={now,spans:[{s,e:s+60*m,app:'VS Code',label:'VS Code'}],pauses:[]};
 window.__devices=[{id:'tv-one',name:'Juniper TV',connection:{kind:'lan_https',address:'192.0.2.20:47832',fingerprint:'a'.repeat(64)},online:true,last_seen:now,error:null,snapshot:{version:1,device_id:'tv-one',kind:'tv',name:'Juniper TV',scope:'household',now,range_from:base-8*86400000,time_zone:'UTC',tracking_age_ms:1000,permissions:{usage:true,restrictions:true,dns:true},dns:{enabled:true,profile:'f0c057',status:'On',pause_until:0},spans:[{s,e:s+30*m,app:'tv.youtube',label:'YouTube'}],pauses:[{t:s,end:s+10*m,minutes:10,reason:'Watch the pottery demo',kind:'dns',package:'focus:dns'},{t:s+10*m,end:s+20*m,minutes:10,reason:'Finish a nature episode',kind:'app',package:'tv.netflix'}],apps:[{package:'tv.youtube',label:'YouTube',blocked:false,daily_limit_ms:0,scheduled:false,from_minute:1320,to_minute:420,pause_until:0},{package:'tv.netflix',label:'Netflix',blocked:false,daily_limit_ms:0,scheduled:false,from_minute:1320,to_minute:420,pause_until:0}]}}];
 window.__handlers={};
 window.__TAURI__={event:{listen:async(name,fn)=>{window.__handlers[name]=fn;return()=>{}}},core:{invoke:async(c,args={})=>{
  window.__calls.push({c,args});
  if(c==='get_state')return {paused_at:0,paused_until:0,now,dry_run:true};
  if(c==='startup_panel')return 'today';if(c==='history')return window.__history;
  if(c==='activity_rules_load')return {version:1,rules:[]};
  if(c==='activity_rules_save'||c==='activity_uncategorized_save')return null;
  if(c==='device_list')return structuredClone(window.__devices);
  if(c==='device_refresh'){window.__devices.forEach(d=>{d.online=!window.__fail;d.error=window.__fail?'TV unreachable':null;if(d.online)d.last_seen=Date.now()});return structuredClone(window.__devices)}
  if(c==='device_command'){
   await new Promise(r=>setTimeout(r,40));if(window.__fail){window.__devices[0].online=false;window.__devices[0].error='TV unreachable';throw 'TV unreachable'}
   const d=window.__devices.find(d=>d.id===args.id),command=args.command,a=command.action;
   if(a==='set_rule')Object.assign(d.snapshot.apps.find(a=>a.package===command.package),command);
   if(a==='dns_connect'){d.snapshot.dns.profile=command.profile;d.snapshot.dns.status='On';d.snapshot.dns.enabled=true}
   if(a==='dns_pause'){d.snapshot.dns.status='Paused';d.snapshot.dns.pause_until=now+command.minutes*m}
   if(a==='dns_resume'){d.snapshot.dns.status='On';d.snapshot.dns.pause_until=0;}
   if(a==='app_pause')d.snapshot.apps.find(a=>a.package===command.package).pause_until=now+command.minutes*m;
   d.last_seen=Date.now();return structuredClone(window.__devices);
  }
  if(c==='device_nearby')return [{key:'nearby-two',id:'tv-two',name:'Maple TV',paired:window.__devices.some(d=>d.id==='tv-two')}];
  if(c==='device_request_approval'){window.__approval={request_id:'request-'+Date.now(),device_id:'tv-two',name:'Maple TV',code:'284619',status:'waiting'};window.__approvalStatus='waiting';return structuredClone(window.__approval)}
  if(c==='device_cancel_approval'){window.__approvalStatus='declined';return null}
  if(c==='device_approval_status'){
    const status=window.__approvalStatus;
    if(status==='approved'&&!window.__devices.some(d=>d.id==='tv-two')){const d=structuredClone(window.__devices[0]);Object.assign(d,{id:'tv-two',name:'Maple TV',last_seen:Date.now(),online:true});d.snapshot.device_id='tv-two';window.__devices.push(d)}
    return {...window.__approval,status};
  }
  if(c==='device_forget'){window.__devices=window.__devices.filter(d=>d.id!==args.id);return structuredClone(window.__devices)}
  return null;
 }}};
})();'''
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
  await page.locator('[data-stab="device:tv-one"]').click()
  assert await page.locator('#dnsProfile').input_value()=='f0c057'
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
  print('PASS: real UI filter, independent totals, two-pane settings, TV blocks/limits/schedules, DNS and app pauses, required reasons, failed-save drafts, offline controls, nearby discovery, TV-only approval, cancellation and decline, forget, escaped names, Today/Week and Escape.')

if __name__ == "__main__":
 asyncio.run(main())
