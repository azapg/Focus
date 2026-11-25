use crate::{activity::Tracker, pause_history::read_pauses, Core, LaptopPauseSnapshot, ALLOWED_MINUTES, NO_WINDOW};
use focus_devices::{
    ApprovalView, DeviceManager, DeviceView, IncomingApprovalView, LaptopServer,
    NearbyDevice, Probe,
};
use serde_json::{json, Value};
use std::{path::Path, process::Command, sync::Arc, thread, time::{Duration, Instant}};
use tauri::{AppHandle, Emitter, State};

pub struct Devices(pub Result<DeviceManager, String>);
impl Devices {
    pub fn open(dir: &Path) -> Self {
        Self(DeviceManager::open_with_events(dir, focus_devices::events::EventLog::new(dir, env!("FOCUS_BUILD_ID"))))
    }
    fn manager(&self) -> Result<&DeviceManager, String> {
        self.0.as_ref().map_err(Clone::clone)
    }
}

pub struct LaptopHost(pub Result<LaptopServer, String>);
impl LaptopHost {
    pub fn start(
        dir: &Path,
        app: AppHandle,
        core: Arc<Core>,
        tracker: Arc<Tracker>,
        devices: Arc<Devices>,
    ) -> Self {
        let name = laptop_display_name();
        let snap_core = core.clone();
        let snap_tracker = tracker.clone();
        let snap_name = name.clone();
        let cmd_app = app.clone();
        let cmd_core = core.clone();
        let habits_core = core.clone();
        let habits_app = app.clone();
        let cmd_tracker = tracker.clone();
        let inc_app = app.clone();
        let inc_core = core.clone();

        let started = Instant::now();
        let res = LaptopServer::start_with_events(
            dir,
            name,
            None,
            true,
            Arc::new(move || build_laptop_snapshot(&snap_core, &snap_tracker, None, &snap_name)),
            Arc::new(move |input| handle_remote_laptop_command(&cmd_app, &cmd_core, &cmd_tracker, &devices, input)),
            Arc::new(move |input| {
                let response = crate::habit_commands::exchange(&habits_core, input)?;
                let _ = habits_app.emit("habits-updated", ());
                let _ = habits_app.emit("wallet-updated", ());
                Ok(response)
            }),
            Arc::new(move |incoming| {
                if let Some(ref view) = incoming {
                    inc_core.log(&format!(
                        "incoming pairing request from {} code={}",
                        view.manager_name, view.code
                    ));
                }
                let _ = inc_app.emit("incoming-pairing", incoming);
            }),
            core.event_log(),
        );
        match &res {
            Ok(server) => {
                core.event("lan", "bind", "ok", "ok", focus_devices::events::elapsed(started), json!({"port": server.port(), "network_category": active_network_category(), "firewall_profiles": "domain_private"}));
                core.log(&format!(
                    "laptop LAN server listening on port {} (id={}, mdns=_focus-laptop._tcp.local.)",
                    server.port(),
                    server.device_id()
                ));
                #[cfg(windows)]
                if !core.dry {
                    let port = server.port();
                    let fw_core = core.clone();
                    thread::spawn(move || ensure_lan_firewall_rule(port, &fw_core));
                }
            }
            Err(e) => {
                core.event("lan", "bind", "failed", "lan_bind_failed", focus_devices::events::elapsed(started), json!({}));
                core.log(&format!("laptop LAN server unavailable: {e}"));
            }
        }
        Self(res)
    }

    pub fn server(&self) -> Option<&LaptopServer> {
        self.0.as_ref().ok()
    }
}

fn laptop_display_name() -> String {
    let user = std::env::var("USERNAME").unwrap_or_default();
    if !user.is_empty() && user.chars().count() < 50 {
        format!("{user}'s laptop")
    } else {
        "Windows laptop".into()
    }
}

#[cfg(windows)]
fn ensure_lan_firewall_rule(port: u16, core: &Core) {
    use std::os::windows::process::CommandExt;
    let started = Instant::now();
    let Ok(exe) = std::env::current_exe() else {
        core.event("lan", "firewall", "failed", "firewall_failed", 0, json!({"port": port, "network_category": active_network_category(), "firewall_profiles": "domain_private"}));
        return;
    };
    let exe_str = exe.to_string_lossy().to_string();
    let rule_name = "FocusDNS LAN Sync";
    let port_str = port.to_string();
    let _ = Command::new("netsh")
        .args([
            "advfirewall",
            "firewall",
            "delete",
            "rule",
            &format!("name={rule_name}"),
        ])
        .creation_flags(NO_WINDOW)
        .output();
    let out = Command::new("netsh")
        .args([
            "advfirewall",
            "firewall",
            "add",
            "rule",
            &format!("name={rule_name}"),
            "dir=in",
            "action=allow",
            &format!("program={exe_str}"),
            "protocol=TCP",
            &format!("localport={port_str}"),
            "profile=private,domain",
        ])
        .creation_flags(NO_WINDOW)
        .output();
    let success = out.as_ref().is_ok_and(|result| result.status.success());
    core.event("lan", "firewall", if success { "ok" } else { "failed" },
        if success { "ok" } else { "firewall_failed" },
        focus_devices::events::elapsed(started), json!({"port": port, "network_category": active_network_category(), "firewall_profiles": "domain_private"}));
    match out {
        Ok(o) if o.status.success() => {
            core.log(&format!("firewall rule ensured for LAN sync port {port}"));
        }
        Ok(o) => {
            let msg = String::from_utf8_lossy(&o.stderr);
            core.log(&format!("firewall rule setup skipped: {}", msg.trim()));
        }
        Err(e) => core.log(&format!("firewall rule setup error: {e}")),
    }
}

fn active_network_category() -> &'static str {
    use windows::Win32::{
        NetworkManagement::WindowsFirewall::{INetFwPolicy2, NetFwPolicy2},
        System::Com::{CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED},
    };

    let profiles = unsafe {
        if CoInitializeEx(None, COINIT_MULTITHREADED).is_err() {
            return "unknown";
        }
        let result = CoCreateInstance::<_, INetFwPolicy2>(&NetFwPolicy2, None, CLSCTX_INPROC_SERVER)
            .and_then(|policy| policy.CurrentProfileTypes());
        CoUninitialize();
        result
    };
    match profiles {
        Ok(1) => "domain",
        Ok(2) => "private",
        Ok(4) => "public",
        Ok(mask) if (1..=7).contains(&mask) => "mixed",
        _ => "unknown",
    }
}

fn handle_remote_laptop_command(
    app: &AppHandle, core: &Arc<Core>, tracker: &Arc<Tracker>, devices: &Arc<Devices>, input: Value,
) -> Result<Option<Value>, String> {
    use crate::{nextdns::PauseTrace, pause_judge};
    let trace = || PauseTrace::new(true, input["correlation_id"].as_str());
    let id = || input["id"].as_str().ok_or("Missing pause decision ID".to_string());
    let override_full = || input["override_full"].as_bool().unwrap_or(false);
    let invalid = |_| "Invalid pause judge command".to_string();
    let action = input["action"].as_str().unwrap_or("");
    match action {
        "dns_pause" => {
            let minutes = input["minutes"]
                .as_u64()
                .ok_or("Pick 1, 5, 10 or 30 minutes")?;
            if !ALLOWED_MINUTES.contains(&minutes) {
                return Err("Pick 1, 5, 10 or 30 minutes".into());
            }
            let reason = input["reason"].as_str().unwrap_or("").trim().to_owned();
            if reason.chars().count() < 3 {
                return Err("Add a reason (at least 3 characters)".into());
            }
            let check_in = serde_json::from_value(input["check_in"].clone())
                .map_err(|_| "Choose a valid check-in")?;
            core.start_pause(minutes, reason, check_in)?;
            let _ = app.emit("state", core.view());
            core.log(&format!("laptop paused remotely from phone for {minutes}m"));
            Ok(None)
        }
        "dns_resume" => {
            core.enable(app, "turned on from phone")?;
            core.log("laptop resumed remotely from phone");
            Ok(None)
        }
        "end_scoped_access" => {
            let grant_id = input["grant_id"].as_str().filter(|id| !id.is_empty())
                .ok_or("Missing scoped grant ID")?;
            if end_scoped_access(core, grant_id)? {
                core.publish(app);
            }
            Ok(None)
        }
        "judge_pause" => {
            let budget = crate::judge::ask_budget(input["answer_budget_ms"].as_u64(), true);
            let deadline = Instant::now() + budget;
            let (core, tracker, devices) = (core.clone(), tracker.clone(), devices.clone());
            let (done, reply) = std::sync::mpsc::sync_channel(1);
            tauri::async_runtime::spawn_blocking(move || {
                let result = (|| {
                    if !pause_judge::uses_judge(&core)? {
                        let trace = PauseTrace::new(true, input["correlation_id"].as_str());
                        core.event("ask", "laptop", "blocked", "laptop_judge_disabled", 0, trace.fields());
                        return Err("Enable the pause judge on the laptop first".into());
                    }
                    let request = serde_json::from_value(input["request"].clone())
                        .map_err(|_| "Invalid pause judge command".to_string())?;
                    let mut decision = json!(pause_judge::judge_remote(
                        &core, &tracker, &devices, request, deadline, input["correlation_id"].as_str(),
                    )?);
                    decision["scope_profile"] = json!(core.dns_profile());
                    Ok(Some(decision))
                })();
                let _ = done.send(result);
            });
            reply.recv_timeout(deadline.saturating_duration_since(Instant::now()))
                .map_err(|_| "Pause assistant timed out. Ask again.".to_owned())?
        }
        "begin_pause_hold" => {
            Ok(Some(json!(pause_judge::begin_hold(core, devices, id()?, override_full())?)))
        }
        "cancel_pause_hold" => {
            pause_judge::cancel_hold(core, id()?, input["hold_id"].as_str().ok_or("Missing hold ID")?);
            Ok(Some(json!({"cancelled": true})))
        }
        "apply_decision" => {
            let started = Instant::now();
            let decision = serde_json::from_value(input["input"].clone()).map_err(invalid)?;
            let result = pause_judge::apply_with_trace(core, devices, decision, trace());
            let _ = app.emit("state", core.view());
            let mut reply = json!(result?);
            if let (Some(until), Some(id)) = (reply["scoped_until"].as_u64(), input["input"]["id"].as_str()) {
                match scoped_apply_sample(core, id, until, started) {
                    Ok(Some(sample)) => reply["scoped_timer"] = sample,
                    Ok(None) => {}
                    Err(error) => core.log(&error),
                }
            }
            Ok(Some(reply))
        }
        _ => Err("Unsupported laptop control".into()),
    }
}

pub(super) fn scoped_apply_sample(core: &Core, id: &str, until: u64, started: Instant) -> Result<Option<Value>, String> {
    let saved = core.saved.lock().unwrap();
    let Some(lease) = saved.scoped_pause.as_ref().filter(|lease|
        lease.until == until && lease.started.is_some()
            && lease.timer.as_ref().is_some_and(|timer| timer.id == id)
    ) else { return Ok(None); };
    let processing_ms = started.elapsed().as_millis() as u64;
    let now = crate::now_ms();
    Ok(Some(json!({
        "now": now,
        "snapshot_revision": core.next_event_sequence(now)?,
        "processing_ms": processing_ms,
        "dns": {
            "scoped_until": until,
            "scoped_grant": lease.timer,
            "scoped_services": lease.services,
        },
    })))
}

pub(super) fn end_scoped_access(core: &Core, grant_id: &str) -> Result<bool, String> {
    let mut saved = core.saved.lock().unwrap();
    if !saved.scoped_pause.as_ref().and_then(|lease| lease.timer.as_ref())
        .is_some_and(|timer| timer.id == grant_id)
    {
        return Ok(false);
    }
    // Reapply protection to adapters that appeared while the grant was active.
    core.dns("on")?;
    crate::pause_judge::sweep_scope(core, &mut saved, true)
}

fn extract_domain(url: &str) -> String {
    let trimmed = url.trim();
    let without_scheme = trimmed
        .strip_prefix("https://")
        .or_else(|| trimmed.strip_prefix("http://"))
        .unwrap_or(trimmed);
    let host_port = without_scheme
        .split(['/', '?', '#'])
        .next()
        .unwrap_or("");
    let host = host_port
        .rsplit('@')
        .next()
        .unwrap_or("")
        .split(':')
        .next()
        .unwrap_or("")
        .trim()
        .to_ascii_lowercase();
    if host.contains('.') || host == "localhost" {
        host.strip_prefix("www.").unwrap_or(&host).to_owned()
    } else {
        String::new()
    }
}

pub fn build_laptop_snapshot(
    core: &Core,
    tracker: &Tracker,
    server: Option<&LaptopServer>,
    fallback_name: &str,
) -> Result<Value, String> {
    let LaptopPauseSnapshot { now, revision, active_pause, paused_until, scoped_until, scoped_grant, scoped_services, history, desktop_limit } =
        core.laptop_pause_snapshot()?;
    let range_from = now.saturating_sub(8 * 86_400_000);
    let raw_spans = tracker.spans(range_from, now);

    let mut spans: Vec<Value> = Vec::with_capacity(raw_spans.len().min(4000));
    let start_idx = raw_spans.len().saturating_sub(4000);
    for sp in &raw_spans[start_idx..] {
        let domain = sp
            .browser
            .as_ref()
            .and_then(|b| b.url.as_deref())
            .map(extract_domain)
            .unwrap_or_default();
        if let Some(prev) = spans.last_mut() {
            let same_app = prev["app"].as_str() == Some(sp.app.as_str());
            let same_dom = prev["domain"].as_str() == Some(domain.as_str());
            let prev_e = prev["e"].as_u64().unwrap_or(0);
            if same_app && same_dom && sp.s <= prev_e.saturating_add(3_000) {
                if sp.e > prev_e {
                    prev["e"] = json!(sp.e);
                }
                continue;
            }
        }
        spans.push(json!({
            "s": sp.s,
            "e": sp.e,
            "app": sp.app,
            "label": sp.label,
            "domain": domain,
        }));
    }

    let mut pauses_vec = read_pauses(&history, active_pause, now);
    pauses_vec.retain(|p| p.end > range_from && p.t < now);
    let pauses: Vec<Value> = pauses_vec
        .into_iter()
        .map(|p| {
            json!({
                "t": p.t,
                "pause_id": p.pause_id,
                "end": p.end,
                "minutes": p.minutes,
                "reason": p.reason,
                "check_in": p.check_in,
                "rating": p.rating,
                "rating_note": p.rating_note,
                "end_kind": if p.active { None } else { Some("dns_resumed") },
                "package": "focus:dns",
                "kind": "dns",
            })
        })
        .collect();

    let dns_paused = paused_until > now;
    let device_id = server
        .map(|s| s.device_id())
        .unwrap_or_else(|| "windows-laptop".into());
    let name = server
        .map(|s| s.name().to_owned())
        .unwrap_or_else(|| fallback_name.to_owned());
    let tz = chrono::Local::now().offset().to_string();
    Ok(json!({
        "version": 1,
        "kind": "laptop",
        "scope": "personal",
        "device_id": device_id,
        "name": name,
        "pairing": "approval-v2",
        "now": now,
        "snapshot_revision": revision,
        "range_from": range_from,
        "time_zone": tz,
        "permissions": { "usage": true, "restrictions": true, "dns": true },
        "tracking_age_ms": 0,
        "habits_sync": 1,
        "wallet_sync": focus_dns::habits::WALLET_SYNC_VERSION,
        "pause_daily_base": desktop_limit.effective(now),
        "pause_daily_limit": desktop_limit,
        "activity_observed_from": tracker.evidence_start(),
        "dns": {
            "enabled": true,
            "profile": "",
            "pause_assistant": crate::pause_judge::uses_judge(core).unwrap_or(false),
            "status": if dns_paused { "Paused" } else { "On" },
            "pause_until": if dns_paused { paused_until } else { 0 },
            "scoped_until": scoped_until,
            "scoped_grant": scoped_grant,
            "scoped_services": scoped_services,
        },
        "spans": spans,
        "pauses": pauses,
        "apps": [],
    }))
}

fn build_phone_sync_payload(
    manager: &DeviceManager,
    phone: &DeviceView,
    core: &Core,
    tracker: &Tracker,
    host: Option<&LaptopServer>,
    local: std::net::SocketAddr,
) -> Result<Value, String> {
    let fallback_name = laptop_display_name();
    let peer = build_laptop_snapshot(core, tracker, host, &fallback_name)?;
    let reciprocal = host.and_then(|server| server.reciprocal_for_peer(&phone.id, local).ok());
    let relayed_peers: Vec<Value> = manager
        .list()
        .into_iter()
        .filter(|other| other.id != phone.id && other.snapshot.is_some())
        .filter_map(|other| other.snapshot)
        .collect();
    let mut payload = json!({
        "peer": peer,
        "relayed_peers": relayed_peers,
    });
    if let Some(recip) = reciprocal {
        payload["reciprocal"] = recip;
    }
    Ok(payload)
}

pub fn sync_one_device(
    manager: &DeviceManager,
    device: &DeviceView,
    core: &Core,
    tracker: &Tracker,
    host: Option<&LaptopServer>,
) -> Result<(), String> {
    if device.kind == "phone" {
        manager.sync_phone(&device.id, |local| build_phone_sync_payload(manager, device, core, tracker, host, local))?;
    } else {
        manager.refresh(&device.id)?;
    }
    if let Some(peer) = manager.list().into_iter().find(|p| p.id == device.id) {
        if let Err(error) = sync_habits(manager, &peer, core, tracker, host) {
            core.log(&format!("habits sync failed for {}: {error}", peer.name));
        }
    }
    Ok(())
}

pub fn sync_habits(
    manager: &DeviceManager,
    peer: &DeviceView,
    core: &Core,
    tracker: &Tracker,
    host: Option<&LaptopServer>,
) -> Result<(), String> {
    if peer.snapshot.as_ref().is_none_or(|s| s["habits_sync"] != 1) { return Ok(()); }
    let wallet_version = focus_dns::habits::WALLET_SYNC_VERSION;
    let wallet = peer.snapshot.as_ref().is_some_and(|snapshot| snapshot["wallet_sync"] == wallet_version);
    let own = core.habits.read();
    let peers = manager.list();
    let laptop_id = host.map_or_else(|| "windows-laptop".into(), LaptopServer::device_id);
    let mut send = Some(String::new());
    let mut receive = Some(String::new());
    let mut screens = Some(crate::habit_commands::screen_payload(core, tracker,
        &laptop_id, &peers, &peer.id));
    let mut allowance = if peer.kind == "tv" {
        let snapshot = build_laptop_snapshot(core, tracker, host, "Laptop")?;
        Some(crate::habit_commands::allowance_payload(&snapshot, &peers, &peer.id, &laptop_id))
    } else { None };
    while send.is_some() || receive.is_some() {
        let page = send.take().map(|after| {
            let (page, next) = own.page(&after, wallet);
            send = next;
            page
        });
        let mut payload = json!({ "habits": page, "after": receive, "screens": screens.take(), "allowance": allowance.take() });
        if wallet { payload["wallet_sync"] = json!(wallet_version); }
        let result = manager.exchange_habits(&peer.id, &payload)?;
        let next = result["next"].as_str().map(str::to_owned);
        if next.is_some() && (receive.is_none() || next <= receive) {
            return Err("Invalid habits cursor".into());
        }
        receive = next;
        crate::habit_commands::exchange(core, result)?;
    }
    if peer.kind == "tv" {
        // The pre-exchange snapshot cannot include the allowance we just sent.
        manager.refresh(&peer.id)?;
    }
    Ok(())
}

pub fn trigger_phone_sync_async(
    app: AppHandle,
    devices: Arc<Devices>,
    core: Arc<Core>,
    tracker: Arc<Tracker>,
    host: Arc<LaptopHost>,
) {
    thread::spawn(move || {
        let Ok(manager) = devices.manager() else { return };
        for d in manager.list() {
            if d.kind == "phone" {
                let _ = sync_one_device(manager, &d, &core, &tracker, host.server());
            }
        }
        let _ = app.emit("devices", manager.list());
    });
}

#[tauri::command(async)]
pub fn device_list(devices: State<Arc<Devices>>) -> Result<Vec<DeviceView>, String> {
    Ok(devices.manager()?.list())
}
#[tauri::command(async)]
pub fn device_probe(address: String, devices: State<Arc<Devices>>) -> Result<Probe, String> {
    devices.manager()?.probe(&address)
}
#[tauri::command(async)]
pub fn device_pair(
    address: String,
    fingerprint: String,
    code: String,
    name: String,
    devices: State<Arc<Devices>>,
    core: State<Arc<Core>>,
    tracker: State<Arc<Tracker>>,
    host: State<Arc<LaptopHost>>,
    app: AppHandle,
) -> Result<Vec<DeviceView>, String> {
    let result = devices
        .manager()?
        .pair(&address, &fingerprint, &code, &name);
    core.log(if result.is_ok() {
        "device paired"
    } else {
        "device pairing failed"
    });
    if result.is_ok() {
        trigger_phone_sync_async(
            app,
            devices.inner().clone(),
            core.inner().clone(),
            tracker.inner().clone(),
            host.inner().clone(),
        );
    }
    result
}
#[tauri::command(async)]
pub fn device_nearby(devices: State<Arc<Devices>>) -> Result<Vec<NearbyDevice>, String> {
    devices.manager()?.nearby()
}
#[tauri::command(async)]
pub fn device_request_approval(
    key: String,
    devices: State<Arc<Devices>>,
    core: State<Arc<Core>>,
) -> Result<ApprovalView, String> {
    let name = laptop_display_name();
    let result = devices.manager()?.request_approval(&key, &name);
    core.log(if result.is_ok() {
        "device approval requested"
    } else {
        "device approval request failed"
    });
    result
}
#[tauri::command(async)]
pub fn device_approval_status(
    request_id: String,
    devices: State<Arc<Devices>>,
    core: State<Arc<Core>>,
    tracker: State<Arc<Tracker>>,
    host: State<Arc<LaptopHost>>,
    app: AppHandle,
) -> Result<ApprovalView, String> {
    let view = devices.manager()?.approval_status(&request_id)?;
    if view.status == "approved" && view.kind == "phone" {
        trigger_phone_sync_async(
            app,
            devices.inner().clone(),
            core.inner().clone(),
            tracker.inner().clone(),
            host.inner().clone(),
        );
    }
    Ok(view)
}
#[tauri::command(async)]
pub fn device_cancel_approval(
    request_id: String,
    devices: State<Arc<Devices>>,
) -> Result<(), String> {
    devices.manager()?.cancel_approval(&request_id)
}

#[tauri::command(async)]
pub fn device_refresh(
    id: Option<String>,
    devices: State<Arc<Devices>>,
    core: State<Arc<Core>>,
    tracker: State<Arc<Tracker>>,
    host: State<Arc<LaptopHost>>,
) -> Result<Vec<DeviceView>, String> {
    let manager = devices.manager()?;
    let _ = manager.reconnect_discovered();
    let list = manager.list();
    if let Some(id) = id {
        if let Some(d) = list.iter().find(|x| x.id == id) {
            let _ = sync_one_device(manager, d, &core, &tracker, host.server());
        }
    } else {
        for device in &list {
            let _ = sync_one_device(manager, device, &core, &tracker, host.server());
        }
    }
    Ok(manager.list())
}
#[tauri::command(async)]
pub fn device_command(
    id: String,
    command: Value,
    devices: State<Arc<Devices>>,
    core: State<Arc<Core>>,
) -> Result<Vec<DeviceView>, String> {
    let action = command["action"]
        .as_str()
        .unwrap_or("unknown")
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '_')
        .take(40)
        .collect::<String>();
    let result = devices.manager()?.command(&id, command);
    core.log(&format!(
        "device command action={action} confirmed={}",
        result.is_ok()
    ));
    result
}
#[tauri::command(async)]
pub fn device_forget(
    id: String,
    devices: State<Arc<Devices>>,
    core: State<Arc<Core>>,
) -> Result<Vec<DeviceView>, String> {
    let result = devices.manager()?.forget(&id);
    core.log(&format!("device forgotten confirmed={}", result.is_ok()));
    result
}

#[tauri::command(async)]
pub fn laptop_incoming_approval(host: State<Arc<LaptopHost>>) -> Option<IncomingApprovalView> {
    host.server().and_then(|s| s.pending_approval())
}

#[tauri::command(async)]
pub fn laptop_approve_pairing(
    request_id: String,
    host: State<Arc<LaptopHost>>,
    core: State<Arc<Core>>,
) -> Result<(), String> {
    let server = host
        .server()
        .ok_or("Laptop LAN pairing server is unavailable")?;
    server.approve(&request_id)?;
    core.log("approved incoming pairing request on laptop");
    Ok(())
}

#[tauri::command(async)]
pub fn laptop_decline_pairing(
    request_id: String,
    host: State<Arc<LaptopHost>>,
    core: State<Arc<Core>>,
) -> Result<(), String> {
    let server = host
        .server()
        .ok_or("Laptop LAN pairing server is unavailable")?;
    server.decline(&request_id)?;
    core.log("declined incoming pairing request on laptop");
    Ok(())
}

pub fn sync_loop(
    app: AppHandle,
    devices: Arc<Devices>,
    core: Arc<Core>,
    tracker: Arc<Tracker>,
    host: Arc<LaptopHost>,
) {
    let Ok(manager) = devices.manager() else {
        core.log("device registry unavailable; original file preserved");
        return;
    };
    let mut network_category = None;
    let mut maintenance_day = None;
    loop {
        focus_telemetry::DEVICE_TICK.count(1);
        let day = crate::now_ms() / 86_400_000;
        if maintenance_day != Some(day) {
            let _ = core.event_log().maintain();
            maintenance_day = Some(day);
        }
        if let Some(server) = host.server() {
            let current = active_network_category();
            if network_category.replace(current) != Some(current) {
                core.event("lan", "network", "ok", "ok", 0, json!({
                    "network_category": current, "firewall_profiles": "domain_private", "port": server.port(),
                }));
            }
        }
        if let Err(error) = manager.reconnect_discovered() {
            core.log(&format!("device discovery: {error}"));
        }
        for old in manager.list() {
            let result = sync_one_device(manager, &old, &core, &tracker, host.server());
            if let Err(error) = &result {
                if old.error.as_ref() != Some(error) {
                    core.log(&format!("device sync unavailable ({}): {error}", old.kind));
                }
            } else if !old.online {
                core.log(&format!("device connected ({})", old.kind));
            }
            let _ = app.emit("devices", manager.list());
            let _ = app.emit("wallet-updated", ());
        }
        thread::sleep(Duration::from_secs(20));
    }
}
