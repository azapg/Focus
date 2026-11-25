// Real Windows Edge -> unpacked extension -> native host, disposable profile.
// Run with Windows Node: node tests/windows-browser.mjs <Windows build root>
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { once } from 'node:events';
const build=path.resolve(process.argv[2] || '.');
const base=fs.mkdtempSync(path.join(os.tmpdir(),'focus-edge-e2e-'));
const profile=path.join(base,'profile'),data=path.join(base,'data'),extension=path.join(base,'extension');
fs.mkdirSync(profile);fs.mkdirSync(data);fs.cpSync(path.join(build,'extension'),extension,{recursive:true});
const hostName=`org.nightingalelab.focusdns.test_${process.pid}`;
const background=path.join(extension,'background.js');
fs.writeFileSync(background,fs.readFileSync(background,'utf8').replace("'org.nightingalelab.focusdns'",JSON.stringify(hostName)));
const manifest=path.join(base,'host.json');
fs.writeFileSync(manifest,JSON.stringify({name:hostName,description:'Disposable FocusDNS runtime test',path:path.join(build,'browser-host/target/release/focus-browser-host.exe'),type:'stdio',allowed_origins:['chrome-extension://mfafgohdpfnknfgedjiicnmgomjpdock/']}));
const registry=`HKCU:\\Software\\Microsoft\\Edge\\NativeMessagingHosts\\${hostName}`;
const quote=(s)=>"'"+s.replaceAll("'","''")+"'";
const ps=(script)=>execFileSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',script],{stdio:'pipe'});
const wait=ms=>new Promise(r=>setTimeout(r,ms));
async function until(fn,label){for(let i=0;i<100;i++){const v=await fn();if(v)return v;await wait(100)}throw Error(`Timeout: ${label}`)}
let socket,child;
const server=http.createServer((_req,res)=>{res.setHeader('Content-Type','text/html');res.end('<!doctype html><title>Windows synthetic project</title><h1>Project</h1>')});
try{
 ps(`$ErrorActionPreference='Stop';New-Item -Path ${quote(registry)} -Force | Out-Null;Set-Item -Path ${quote(registry)} -Value ${quote(manifest)}`);
 server.listen(0,'127.0.0.1');await once(server,'listening');const port=server.address().port;
 const edge=path.join(process.env['ProgramFiles(x86)'],'Microsoft/Edge/Application/msedge.exe');
 child=spawn(edge,[`--user-data-dir=${profile}`,`--disable-extensions-except=${extension}`,`--load-extension=${extension}`,'--headless=new','--remote-debugging-port=0','--no-first-run','--no-default-browser-check','--disable-background-networking'],{env:{...process.env,LOCALAPPDATA:data},stdio:'ignore'});
 const devtools=path.join(profile,'DevToolsActivePort');
 await until(()=>fs.existsSync(devtools),'Edge debugging port');
 const [debugPort,debugPath]=fs.readFileSync(devtools,'utf8').trim().split(/\r?\n/);
 socket=new WebSocket(`ws://127.0.0.1:${debugPort}${debugPath}`);await once(socket,'open');
 let seq=0;const pending=new Map();
 socket.addEventListener('message',e=>{const m=JSON.parse(e.data);if(m.id){const p=pending.get(m.id);pending.delete(m.id);m.error?p?.reject(Error(m.error.message)):p?.resolve(m.result)}});
 function cdp(method,params={},sessionId){return new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});socket.send(JSON.stringify({id,method,params,...(sessionId?{sessionId}:{})}));setTimeout(()=>{if(pending.delete(id))reject(Error('CDP timeout '+method))},15000).unref()})}
 const target=await until(async()=> (await cdp('Target.getTargets')).targetInfos.find(t=>t.type==='service_worker'&&t.url.includes('mfafgohdpfnknfgedjiicnmgomjpdock')),'unpacked extension worker');
 const worker=(await cdp('Target.attachToTarget',{targetId:target.targetId,flatten:true})).sessionId;
 const created=await cdp('Target.createTarget',{url:`http://127.0.0.1:${port}/project?token=synthetic-secret&tab=readme`});
 const page=(await cdp('Target.attachToTarget',{targetId:created.targetId,flatten:true})).sessionId;
 const evalAt=async(session,expression)=>{const r=await cdp('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true},session);if(r.exceptionDetails)throw Error(r.exceptionDetails.text);return r.result.value};
 const live=path.join(data,'FocusDNS/browser/live');
 const snapshots=()=>fs.existsSync(live)?fs.readdirSync(live).filter(n=>n.endsWith('.json')).flatMap(n=>{try{return [JSON.parse(fs.readFileSync(path.join(live,n),'utf8'))]}catch{return []}}):[];
 const first=await until(()=>snapshots().find(s=>s.event.title==='Windows synthetic project'),'active URL/title snapshot');
 if(first.browser_exe.toLowerCase()!=='msedge.exe')throw Error('Wrong OS browser ancestry');
 if(first.event.url!==`http://127.0.0.1:${port}/project?token=REDACTED&tab=readme`)throw Error('Redaction mismatch');
 await evalAt(page,"history.pushState({},'', '/project/spa');document.title='Windows SPA project'");
 await until(()=>snapshots().find(s=>s.event.url?.endsWith('/project/spa')&&s.event.title==='Windows SPA project'),'SPA');
 await evalAt(worker,'chrome.storage.local.set({enabled:false})');
 await until(()=>snapshots().find(s=>!s.event.enabled&&!s.event.url&&!s.event.title),'pause');
 await evalAt(page,"history.pushState({},'', '/paused-private');document.title='Windows paused private'");await wait(400);
 const events=path.join(data,'FocusDNS/browser/events');const journal=fs.readdirSync(events).map(n=>fs.readFileSync(path.join(events,n),'utf8')).join('');
 if(journal.includes('synthetic-secret')||journal.includes('/paused-private')||journal.includes('Windows paused private'))throw Error('Paused identity or URL secret persisted');
 await evalAt(worker,'chrome.storage.local.set({enabled:true})');
 await until(()=>snapshots().find(s=>s.event.title==='Windows paused private'),'resume');
 const report={runtime:'real Windows Edge + unpacked extension + registered native host, disposable profile',browser:'Edge',checks:['test-only native registration discovered','real OS browser ancestry','active URL/title delivered','URL credentials redacted','SPA URL/title delivered','pause clears details and suppresses navigation journal','resume captures current page'],daily_browser_changed:false,dns_changed:false};
 console.log(JSON.stringify(report,null,2));
 await cdp('Browser.close').catch(()=>{});
}finally{
 socket?.close();
 if(child&&child.exitCode===null){child.kill();await wait(300)}
 await new Promise(r=>server.close(r));
 ps(`Remove-Item -Path ${quote(registry)} -Force -ErrorAction SilentlyContinue`);
 // Edge can retain file handles briefly while closing; cleanup is scoped to this profile.
 for(let i=0;i<20;i++){try{fs.rmSync(base,{recursive:true,force:true});break}catch{await wait(200)}}
}
