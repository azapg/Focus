(() => {
 const m=60000, d=window.__FOCUS_PRODUCT_SAMPLE__ ? new Date() : new Date('2026-02-12T14:00:00Z');d.setHours(0,0,0,0);const base=d.getTime();
 const now=base+14*60*m,s=base+9*60*m;
 window.__calls=[];window.__fail=false;
 window.__history={now,spans:[{s,e:s+60*m,app:'VS Code',label:'VS Code'}],pauses:[]};
 if(window.__FOCUS_PRODUCT_SAMPLE__){
  const start=s+90*m,end=start+22*m;
  window.__history.spans.push(
   {s:start,e:start+8*m,app:'Screen Studio',label:'Screen Studio'},
   {s:start+8*m,e:end,app:'Chrome',label:'x.com'}
  );
  window.__history.intentions=[{
   id:'sample-guide',start:s,end:s+60*m,label:'Draft the onboarding guide',
   planned_minutes:60,outcome:'done',
  }];
  window.__history.intention_notes=[{
   start,end,text:'Recorded and shared a quick demo. I spent extra time on X finding the original post so I could credit it.',
  }];
 }
 window.__devices=[{id:'tv-one',name:'Juniper TV',connection:{kind:'lan_https',address:'192.0.2.20:47832',fingerprint:'a'.repeat(64)},online:true,last_seen:now,error:null,snapshot:{version:1,device_id:'tv-one',kind:'tv',name:'Juniper TV',scope:'household',now,range_from:base-8*86400000,time_zone:'UTC',tracking_age_ms:1000,permissions:{usage:true,restrictions:true,dns:true},dns:{enabled:true,profile:'f0c057',status:'On',pause_until:0},spans:[{s,e:s+30*m,app:'tv.youtube',label:'YouTube'}],pauses:[{t:s,end:s+10*m,minutes:10,reason:'Watch the pottery demo',kind:'dns',package:'focus:dns'},{t:s+10*m,end:s+20*m,minutes:10,reason:'Finish a nature episode',kind:'app',package:'tv.netflix'}],apps:[{package:'tv.youtube',label:'YouTube',blocked:false,daily_limit_ms:0,scheduled:false,from_minute:1320,to_minute:420,pause_until:0},{package:'tv.netflix',label:'Netflix',blocked:false,daily_limit_ms:0,scheduled:false,from_minute:1320,to_minute:420,pause_until:0}]}}];
 window.__handlers={};
 window.__TAURI__={event:{listen:async(name,fn)=>{window.__handlers[name]=fn;return()=>{}}},core:{invoke:async(c,args={})=>{
  window.__calls.push({c,args});
  if(c==='get_state')return {paused_at:0,paused_until:0,now,dry_run:true};
  if(c==='startup_panel')return 'today';if(c==='history')return structuredClone(window.__history);
  if(c==='intention_save_note' && window.__FOCUS_PRODUCT_SAMPLE__){
   const note=args.note;
   const notes=window.__history.intention_notes;
   window.__history.intention_notes=notes.filter(item=>note.intention_id
    ? item.intention_id!==note.intention_id
    : item.intention_id || item.start!==note.start);
   if(note.text)window.__history.intention_notes.push(note);
   return note.text ? structuredClone(note) : null;
  }
  if(c==='pause_judge_settings')return {model:'',default_model:'Default',luna_model:'Fast',has_gateway_key:false,has_nextdns_key:false,enabled:false};
  if(c==='browser_setup_chrome'){
   if(window.__failBrowserSetup)throw 'Could not prepare the Chrome extension.';
   return String.raw`C:\Users\Spruce\AppData\Local\FocusDNS\browser-bridge\extension`;
  }
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
})();
