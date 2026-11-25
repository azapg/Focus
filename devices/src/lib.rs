//! Device identity and management are independent of the Windows UI.
//! The only connection implementation today is pinned HTTPS on the local network.
mod approval;
mod discovery;
pub mod events;
pub mod secrets;
pub mod server;
pub use approval::ApprovalView;
pub use discovery::NearbyDevice;
pub use server::{IncomingApprovalView, LaptopServer};
pub mod transport;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    fs,
    path::{Path, PathBuf},
    sync::Mutex,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

// Match the Android LAN server's /v1/sync request limit, in UTF-8 bytes.
const PHONE_SYNC_REQUEST_LIMIT: usize = 4 * 1024 * 1024;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Connection {
    LanHttps {
        address: String,
        fingerprint: String,
    },
}
impl Connection {
    fn request(&self, token: &str, path: &str, body: Option<&Value>,
        outcome: &mut transport::RequestOutcome) -> Result<Value, String> {
        match self {
            Self::LanHttps {
                address,
                fingerprint,
            } => {
                transport::request_with_body(address, Some(fingerprint), Some(token), path,
                    |_| Ok(body.map(Value::to_string)), outcome).map(|v| v.0)
            }
        }
    }
}
fn default_kind() -> String {
    "tv".into()
}
#[derive(Clone, Serialize, Deserialize)]
struct Device {
    id: String,
    #[serde(default = "default_kind")]
    kind: String,
    name: String,
    connection: Connection,
    credential: String,
    #[serde(default)]
    snapshot: Option<Value>,
    #[serde(default)]
    last_seen: u64,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    event_cursor: String,
}
#[derive(Clone, Serialize)]
pub struct DeviceView {
    pub id: String,
    pub kind: String,
    pub name: String,
    pub connection: Connection,
    pub snapshot: Option<Value>,
    pub last_seen: u64,
    // Online is session-only. A saved snapshot never implies current connectivity.
    pub online: bool,
    pub error: Option<String>,
}
#[derive(Default, Serialize, Deserialize, Clone)]
struct Registry {
    devices: Vec<Device>,
}
struct State {
    registry: Registry,
    online: std::collections::HashSet<String>,
    errors: std::collections::HashMap<String, String>,
}
pub struct DeviceManager {
    path: PathBuf,
    events: events::EventLog,
    recent_sync: Mutex<std::collections::HashMap<String, (transport::RequestOutcome, Instant)>>,
    state: Mutex<State>,
    operations: Mutex<()>,
    discovery: Mutex<Option<discovery::Discovery>>,
    approval: Mutex<Option<approval::Pending>>,
}
#[derive(Serialize)]
pub struct Probe {
    pub address: String,
    pub fingerprint: String,
    pub kind: String,
    pub name: String,
    pub id: String,
}
fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64
}
fn identity(value: &Value) -> Result<(&str, &str, &str), String> {
    let kind = value["kind"].as_str().unwrap_or("");
    if value["version"] != 1 || (kind != "tv" && kind != "phone") {
        return Err("This device uses an unsupported Focus protocol".into());
    }
    let id = value["device_id"]
        .as_str()
        .filter(|s| !s.is_empty() && s.len() <= 100)
        .ok_or("Missing device identity")?;
    let name = value["name"]
        .as_str()
        .filter(|s| !s.is_empty() && s.chars().count() <= 80)
        .ok_or("Missing device name")?;
    Ok((id, name, kind))
}
fn check_snapshot(value: &Value, id: &str) -> Result<(), String> {
    if identity(value)?.0 != id
        || (value["scope"] != "household" && value["scope"] != "personal")
        || !value["spans"].is_array()
        || !value["pauses"].is_array()
        || !value["apps"].is_array()
        || !value["now"].is_u64()
    {
        return Err("Device returned an incompatible usage snapshot".into());
    }
    let text = |v: &Value| v.as_str().is_some_and(|s| s.len() <= 1000);
    let integer = |v: &Value, max: u64| v.as_u64().is_some_and(|n| n <= max);
    let dns = &value["dns"];
    let permissions = &value["permissions"];
    let shape = text(&value["time_zone"])
        && value["tracking_age_ms"].as_i64().is_some_and(|n| n >= -1)
        && ["usage", "restrictions", "dns"]
            .iter()
            .all(|key| permissions[key].is_boolean())
        && dns["enabled"].is_boolean()
        && text(&dns["profile"])
        && text(&dns["status"])
        && dns["pause_until"].is_u64()
        && value["spans"].as_array().unwrap().iter().all(|span| {
            span["s"].is_u64() && span["e"].is_u64() && text(&span["app"]) && text(&span["label"])
        })
        && value["pauses"].as_array().unwrap().iter().all(|pause| {
            pause["t"].is_u64()
                && pause["end"].is_u64()
                && integer(&pause["minutes"], 300)
                && pause["reason"].is_string()
                && text(&pause["package"])
                && (pause["kind"] == "dns" || pause["kind"] == "app")
        })
        && value["apps"].as_array().unwrap().iter().all(|app| {
            text(&app["package"])
                && text(&app["label"])
                && app["blocked"].is_boolean()
                && app["scheduled"].is_boolean()
                && integer(&app["daily_limit_ms"], 86_400_000)
                && integer(&app["from_minute"], 1439)
                && integer(&app["to_minute"], 1439)
                && app["pause_until"].is_u64()
        });
    if !shape {
        return Err("TV returned incomplete device settings; cached data was preserved".into());
    }
    Ok(())
}
struct ReceivedSnapshot {
    snapshot: Value,
    events: Option<Value>,
}

fn receive_snapshot(mut snapshot: Value, id: &str) -> Result<ReceivedSnapshot, String> {
    check_snapshot(&snapshot, id)?;
    let events = snapshot.as_object_mut().and_then(|o| o.remove("focus_events"));
    Ok(ReceivedSnapshot { snapshot, events })
}

impl DeviceManager {
    pub fn open(dir: &Path) -> Result<Self, String> {
        Self::open_with_events(dir, events::EventLog::new(dir, "unknown"))
    }

    pub fn open_with_events(dir: &Path, events: events::EventLog) -> Result<Self, String> {
        let path = dir.join("devices.json");
        let mut registry: Registry = match fs::read(&path) {
            Ok(bytes) => serde_json::from_slice(&bytes)
                .map_err(|_| "Saved device connections are unreadable; the file was preserved")?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Registry::default(),
            Err(e) => return Err(e.to_string()),
        };
        for device in &mut registry.devices {
            if let Some(object) = device.snapshot.as_mut().and_then(Value::as_object_mut) {
                object.remove("focus_events");
            }
        }
        Ok(Self {
            path,
            events,
            recent_sync: Mutex::new(Default::default()),
            state: Mutex::new(State {
                registry,
                online: Default::default(),
                errors: Default::default(),
            }),
            operations: Mutex::new(()),
            discovery: Mutex::new(None),
            approval: Mutex::new(None),
        })
    }
    fn persist(&self, registry: &Registry) -> Result<(), String> {
        let bytes = serde_json::to_vec(registry).map_err(|e| e.to_string())?;
        focus_browser_host::atomic_write(&self.path, &bytes)
            .map_err(|e| format!("Could not save devices: {e}"))
    }
    pub fn list(&self) -> Vec<DeviceView> {
        let state = self.state.lock().unwrap();
        state
            .registry
            .devices
            .iter()
            .map(|d| {
                let kind = d
                    .snapshot
                    .as_ref()
                    .and_then(|s| s["kind"].as_str())
                    .unwrap_or(&d.kind)
                    .to_owned();
                DeviceView {
                    id: d.id.clone(),
                    kind,
                    name: d.name.clone(),
                    connection: d.connection.clone(),
                    snapshot: d.snapshot.clone(),
                    last_seen: d.last_seen,
                    online: state.online.contains(&d.id) && now().saturating_sub(d.last_seen) < 75_000,
                    error: state.errors.get(&d.id).cloned(),
                }
            })
            .collect()
    }
    pub fn probe(&self, address: &str) -> Result<Probe, String> {
        let (hello, fingerprint) = transport::request(address, None, None, "/v1/hello", None)?;
        let (id, name, kind) = identity(&hello)?;
        Ok(Probe {
            address: address.to_owned(),
            fingerprint,
            kind: kind.to_owned(),
            id: id.to_owned(),
            name: name.to_owned(),
        })
    }
    pub fn pair(
        &self,
        address: &str,
        fingerprint: &str,
        code: &str,
        name: &str,
    ) -> Result<Vec<DeviceView>, String> {
        let _operation = self.operations.lock().unwrap();
        if name.trim().chars().count() > 80 {
            return Err("Device names can have up to 80 characters".into());
        }
        if fingerprint.len() != 64 || !fingerprint.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err("Confirm the TV identity first".into());
        }
        if code.len() != 6 || !code.bytes().all(|b| b.is_ascii_digit()) {
            return Err("Enter the six-digit code shown on the TV".into());
        }
        let connection = Connection::LanHttps {
            address: address.to_owned(),
            fingerprint: fingerprint.to_owned(),
        };
        let (paired, _) = transport::request(
            address,
            Some(fingerprint),
            None,
            "/v1/pair",
            Some(&json!({"code":code,"manager_name":"Windows panel"})),
        )?;
        let (id, tv_name, kind) = identity(&paired)?;
        let token = paired["token"]
            .as_str()
            .filter(|s| s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit()))
            .ok_or("Device did not return a valid pairing credential")?;
        let name = if name.trim().is_empty() {
            tv_name
        } else {
            name.trim()
        };
        if name.chars().count() > 80 {
            return Err("Device names can have up to 80 characters".into());
        }
        self.save_pair(id, kind, name, connection, token)?;
        let _ = self.refresh_inner(id);
        Ok(self.list())
    }
    fn save_pair(
        &self,
        id: &str,
        kind: &str,
        name: &str,
        connection: Connection,
        token: &str,
    ) -> Result<(), String> {
        if name.chars().count() > 80
            || token.len() != 64
            || !token.bytes().all(|b| b.is_ascii_hexdigit())
        {
            return Err("Invalid device pairing credential".into());
        }
        let credential = secrets::protect(token)?;
        let mut state = self.state.lock().unwrap();
        if state.registry.devices.len() >= 16 && !state.registry.devices.iter().any(|d| d.id == id)
        {
            return Err("Remove a device before adding another".into());
        }
        let old = state.registry.devices.iter().find(|d| d.id == id);
        let mut next = state.registry.clone();
        next.devices.retain(|d| d.id != id);
        next.devices.push(Device {
            id: id.to_owned(),
            kind: kind.to_owned(),
            name: name.to_owned(),
            connection,
            credential,
            snapshot: old.and_then(|d| d.snapshot.clone()),
            last_seen: old.map(|d| d.last_seen).unwrap_or(0),
            event_cursor: String::new(),
        });
        self.persist(&next)?;
        state.registry = next;
        Ok(())
    }
    fn peers(&self) -> Result<Vec<discovery::Peer>, String> {
        let mut discovery = self.discovery.lock().unwrap();
        if discovery.is_none() {
            *discovery = Some(discovery::Discovery::start()?);
        }
        Ok(discovery.as_ref().unwrap().peers())
    }
    pub fn nearby(&self) -> Result<Vec<NearbyDevice>, String> {
        let peers = self.peers()?;
        let state = self.state.lock().unwrap();
        let mut result = std::collections::BTreeMap::new();
        for peer in peers {
            let paired=state.registry.devices.iter().any(|d|d.id==peer.id&&matches!(&d.connection,Connection::LanHttps{fingerprint,..} if fingerprint==&peer.fingerprint));
            result.insert(
                peer.key.clone(),
                NearbyDevice {
                    key: peer.key,
                    id: peer.id,
                    kind: peer.kind,
                    name: peer.name,
                    paired,
                },
            );
        }
        Ok(result.into_values().collect())
    }
    /// Only a previously trusted certificate can move a saved device to a new address.
    pub fn reconnect_discovered(&self) -> Result<(), String> {
        let peers = self.peers()?;
        self.adopt_peers(&peers)
    }
    fn adopt_peers(&self, peers: &[discovery::Peer]) -> Result<(), String> {
        let _operation = self.operations.lock().unwrap();
        let mut state = self.state.lock().unwrap();
        let mut next = state.registry.clone();
        let mut changed = false;
        for device in &mut next.devices {
            let Connection::LanHttps {
                address,
                fingerprint,
            } = &mut device.connection;
            if let Some(peer) = peers.iter().find(|p| {
                p.id == device.id && &p.fingerprint == fingerprint && p.address != *address
            }) {
                *address = peer.address.clone();
                changed = true;
            }
        }
        if changed {
            self.persist(&next)?;
            state.registry = next;
            self.events.record("lan", "address", "ok", "ok", 0, json!({"origin": "laptop"}));
        }
        Ok(())
    }
    pub fn request_approval(&self, key: &str, manager_name: &str) -> Result<ApprovalView, String> {
        let peer = self
            .peers()?
            .into_iter()
            .find(|p| p.key == key)
            .ok_or("TV is no longer nearby. Search again.")?;
        self.request_peer_approval(peer, manager_name)
    }
    /// Transport entry for resolved endpoints (also used by the installation checks).
    /// The normal UI resolves these through discovery and never asks for an address.
    pub fn request_resolved_approval(
        &self,
        address: &str,
        manager_name: &str,
    ) -> Result<ApprovalView, String> {
        self.request_peer_approval(discovery::probe_peer(address)?, manager_name)
    }
    fn request_peer_approval(
        &self,
        peer: discovery::Peer,
        manager_name: &str,
    ) -> Result<ApprovalView, String> {
        let _operation = self.operations.lock().unwrap();
        if let Some(old) = self.approval.lock().unwrap().take() {
            let _ = old.request("/v2/pair/cancel");
        }
        let pending = approval::Pending::start(peer, manager_name)?;
        let view = pending.view("waiting");
        *self.approval.lock().unwrap() = Some(pending);
        Ok(view)
    }
    pub fn approval_status(&self, id: &str) -> Result<ApprovalView, String> {
        let _operation = self.operations.lock().unwrap();
        let pending = self
            .approval
            .lock()
            .unwrap()
            .as_ref()
            .filter(|p| p.request_id == id)
            .cloned()
            .ok_or("Pairing request ended. Start again.")?;
        if pending.started.elapsed().as_secs() >= 240 {
            self.approval.lock().unwrap().take();
            return Ok(pending.view("expired"));
        }
        let reply = pending.request("/v2/pair/status")?;
        if reply["device_id"] != pending.peer.id || reply["code"] != pending.code {
            return Err("TV identity changed during pairing".into());
        }
        let status = reply["status"]
            .as_str()
            .ok_or("Invalid TV approval status")?;
        match status {
            "approved" => {
                let token = reply["token"]
                    .as_str()
                    .ok_or("TV approval did not include a credential")?;
                let peer = &pending.peer;
                self.save_pair(
                    &peer.id,
                    &peer.kind,
                    &peer.name,
                    Connection::LanHttps {
                        address: peer.address.clone(),
                        fingerprint: peer.fingerprint.clone(),
                    },
                    token,
                )?;
                self.approval.lock().unwrap().take();
                let _ = self.refresh_inner(&peer.id);
            }
            "declined" | "expired" => {
                self.approval.lock().unwrap().take();
            }
            "waiting" => {}
            _ => return Err("Invalid TV approval status".into()),
        }
        Ok(pending.view(status))
    }
    pub fn cancel_approval(&self, id: &str) -> Result<(), String> {
        let _operation = self.operations.lock().unwrap();
        let mut slot = self.approval.lock().unwrap();
        if slot.as_ref().is_some_and(|p| p.request_id == id) {
            let pending = slot.take().unwrap();
            drop(slot);
            let _ = pending.request("/v2/pair/cancel");
        }
        Ok(())
    }
    fn device(&self, id: &str) -> Result<Device, String> {
        self.state
            .lock()
            .unwrap()
            .registry
            .devices
            .iter()
            .find(|d| d.id == id)
            .cloned()
            .ok_or("Unknown device".into())
    }
    fn record(&self, id: &str, result: Result<ReceivedSnapshot, String>, import_events: bool)
        -> Result<(), String>
    {
        let mut state = self.state.lock().unwrap();
        // A response arriving after Forget must not recreate that device.
        if !state.registry.devices.iter().any(|d| d.id == id) {
            return Err("Device was removed".into());
        }
        match result {
            Ok(ReceivedSnapshot { snapshot, events }) => {
                let cursor = events.filter(|_| import_events && snapshot["kind"] == "phone")
                    .and_then(|page| self.events.import_phone(id, &page).ok().flatten());
                let mut next = state.registry.clone();
                let d = next.devices.iter_mut().find(|d| d.id == id).unwrap();
                if let Some(kind) = snapshot["kind"].as_str() {
                    d.kind = kind.to_owned();
                }
                d.snapshot = Some(snapshot);
                d.last_seen = now();
                if let Some(cursor) = cursor { d.event_cursor = cursor; }
                self.persist(&next)?;
                state.registry = next;
                state.online.insert(id.to_owned());
                state.errors.remove(id);
                Ok(())
            }
            Err(error) => {
                state.online.remove(id);
                state.errors.insert(id.to_owned(), error.clone());
                Err(error)
            }
        }
    }
    pub fn refresh(&self, id: &str) -> Result<(), String> {
        let _operation = self.operations.lock().unwrap();
        self.refresh_inner(id)
    }
    /// Bidirectional sync with a paired phone: sends the laptop's own snapshot (and reciprocal
    /// LAN details / relayed peers) via POST /v1/sync and records the returned phone snapshot.
    /// Falls back cleanly to GET /v1/snapshot on older phone builds that lack /v1/sync.
    pub fn sync_phone(&self, id: &str, build_payload: impl FnOnce(std::net::SocketAddr) -> Result<Value, String>) -> Result<(), String> {
        let _operation = self.operations.lock().unwrap();
        let device = self.device(id)?;
        let token = secrets::unprotect(&device.credential)?;
        let cursor = if events::valid_id(&device.event_cursor) { &device.event_cursor } else { "" };
        let started = Instant::now();
        let mut trace = transport::RequestOutcome::default();
        let mut import_events = false;
        let Connection::LanHttps { address, fingerprint } = &device.connection;
        // Build the relay payload from the local address selected for this connection.
        let result = transport::request_with_body(address, Some(fingerprint), Some(&token), "/v1/sync", |local| {
            let payload = build_payload(local)?;
            let mut body = payload.to_string();
            if let Some(object) = payload.as_object().filter(|object| !object.contains_key("event_sync")) {
                let separator = if object.is_empty() { "" } else { "," };
                let extension = format!("{separator}\"event_sync\":{}", json!({"after": cursor}));
                if body.len() + extension.len() <= PHONE_SYNC_REQUEST_LIMIT {
                    body.pop(); // Insert the optional property before the object's closing brace.
                    body.push_str(&extension);
                    body.push('}');
                    import_events = true;
                }
            }
            Ok(Some(body))
        }, &mut trace).map(|v| v.0);
        let result = match result {
            Ok(snapshot) => Ok(snapshot),
            Err(err) if err.contains("Unsupported device API") => {
                import_events = false;
                trace = transport::RequestOutcome::default();
                device.connection.request(&token, "/v1/snapshot", None, &mut trace)
            }
            Err(err) => Err(err),
        };
        let result = result.and_then(|snapshot| receive_snapshot(snapshot, id).map_err(|error| {
            trace.cause = "lan_invalid_reply";
            error
        }));
        let mut recent = self.recent_sync.lock().unwrap();
        if !recent.get(id).is_some_and(|(prior, at)| {
            *prior == trace && at.elapsed() < Duration::from_secs(300)
        }) {
            recent.insert(id.to_owned(), (trace, started));
            let mut fields = json!({"direction": "laptop_to_phone"});
            if events::valid_id(id) { fields["peer_id"] = json!(id); }
            if let Some(status) = trace.status {
                fields["http_status"] = json!(status);
                fields["status_class"] = json!(status / 100);
            }
            let outcome = if trace.cause == "ok" { "ok" } else { "failed" };
            if trace.stage != "request" {
                self.events.record("lan", trace.stage, outcome, trace.cause, events::elapsed(started), fields.clone());
            }
            self.events.record("lan", "reachability", outcome, trace.cause, events::elapsed(started), fields);
        }
        drop(recent);
        self.record(id, result, import_events)
    }
    pub fn exchange_habits(&self, id: &str, payload: &Value) -> Result<Value, String> {
        let device = self.device(id)?;
        let token = secrets::unprotect(&device.credential)?;
        device.connection.request(&token, "/v1/habits", Some(payload), &mut Default::default())
    }

    fn refresh_inner(&self, id: &str) -> Result<(), String> {
        let device = self.device(id)?;
        let result = secrets::unprotect(&device.credential)
            .and_then(|token| device.connection.request(&token, "/v1/snapshot", None, &mut Default::default()));
        self.record(id, result.and_then(|snapshot| receive_snapshot(snapshot, id)), false)
    }
    pub fn command(&self, id: &str, command: Value) -> Result<Vec<DeviceView>, String> {
        let _operation = self.operations.lock().unwrap();
        let device = self.device(id)?;
        // No retry: a disconnected response can follow a successful mutation.
        let result = secrets::unprotect(&device.credential).and_then(|token| {
            device
                .connection
                .request(&token, "/v1/command", Some(&command), &mut Default::default())
        });
        if let Err(error) = self.record(id, result.and_then(|snapshot| receive_snapshot(snapshot, id)), false) {
            if command["action"] == "dns_pause" {
                // A refused pause may mean the allowance changed. Read choices without repeating the command.
                let _ = self.refresh_inner(id);
            }
            return Err(error);
        }
        Ok(self.list())
    }
    pub fn forget(&self, id: &str) -> Result<Vec<DeviceView>, String> {
        let _operation = self.operations.lock().unwrap();
        self.recent_sync.lock().unwrap().remove(id);
        {
            let mut state = self.state.lock().unwrap();
            let mut next = state.registry.clone();
            next.devices.retain(|d| d.id != id);
            self.persist(&next)?;
            state.registry = next;
            state.online.remove(id);
            state.errors.remove(id);
        }
        Ok(self.list())
    }
}

#[cfg(all(test, any(windows, feature = "portable-test-harness")))]
mod relocation_tests {
    use super::*;
    #[test]
    fn address_changes_require_both_saved_id_and_certificate() {
        let dir = tempfile::tempdir().unwrap();
        let manager = DeviceManager::open(dir.path()).unwrap();
        let pin = "a".repeat(64);
        manager
            .save_pair(
                "tv",
                "tv",
                "TV",
                Connection::LanHttps {
                    address: "192.168.1.2:47832".into(),
                    fingerprint: pin.clone(),
                },
                &"b".repeat(64),
            )
            .unwrap();
        let mut peer = discovery::Peer {
            key: "key".into(),
            id: "impostor".into(),
            kind: "tv".into(),
            name: "TV".into(),
            address: "192.168.1.3:47832".into(),
            fingerprint: pin.clone(),
        };
        manager.adopt_peers(&[peer.clone()]).unwrap();
        peer.id = "tv".into();
        peer.fingerprint = "c".repeat(64);
        manager.adopt_peers(&[peer.clone()]).unwrap();
        let Connection::LanHttps { address, .. } = &manager.list()[0].connection;
        assert_eq!(address, "192.168.1.2:47832");
        peer.fingerprint = pin;
        manager.adopt_peers(&[peer]).unwrap();
        let reopened = DeviceManager::open(dir.path()).unwrap();
        let Connection::LanHttps { address, .. } = &reopened.list()[0].connection;
        assert_eq!(address, "192.168.1.3:47832");
        assert!(!reopened.list()[0].online);
    }
}
