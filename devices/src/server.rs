//! Local pinned-TLS server + mDNS advertisement so Android phones can discover this laptop,
//! pair via approval-v2 (or reciprocal sync), read laptop stats, and pause/resume DNS.
use crate::{approval, events, secrets, transport};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use focus_telemetry as telemetry;
use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer},
    ServerConfig, ServerConnection, StreamOwned,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    fs,
    io::{self, BufRead, BufReader, Read, Write},
    net::{IpAddr, SocketAddr, TcpListener, TcpStream},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    thread,
    time::{Duration, Instant},
};

pub const LAPTOP_SERVICE_TYPE: &str = "_focus-laptop._tcp.local.";
pub const DEFAULT_PORT: u16 = 47833;
const MAX_REQUEST_BODY: usize = 64 * 1024;
const MAX_REQUEST_HEAD: u64 = 8 * 1024;
const MAX_CONNECTIONS: usize = 32;
const REQUEST_TIMEOUT: Duration = Duration::from_secs(5);
const APPROVAL_TTL_SECS: u64 = 180;

#[derive(Clone, Serialize)]
pub struct IncomingApprovalView {
    pub request_id: String,
    pub manager_name: String,
    pub code: String,
}

#[derive(Serialize, Deserialize, Clone)]
struct PersistedIdentity {
    device_id: String,
    cert_der_hex: String,
    key_credential: String,
    #[serde(default)]
    managers: Vec<String>,
    #[serde(default)]
    peer_tokens: HashMap<String, String>,
}

struct ApprovalSession {
    request_id: String,
    poll_secret: String,
    commitment: String,
    server_nonce: String,
    manager_name: String,
    code: String,
    status: String,
    token: String,
    started: Instant,
}

struct ServerState {
    identity: PersistedIdentity,
    pending: Option<ApprovalSession>,
}

pub type SnapshotCallback = Arc<dyn Fn() -> Result<Value, String> + Send + Sync>;
pub type HabitsCallback = Arc<dyn Fn(Value) -> Result<Value, String> + Send + Sync>;
pub type CommandCallback = Arc<dyn Fn(Value) -> Result<Option<Value>, String> + Send + Sync>;
pub type IncomingCallback = Arc<dyn Fn(Option<IncomingApprovalView>) + Send + Sync>;

pub struct LaptopServer {
    path: PathBuf,
    name: String,
    port: u16,
    fingerprint: String,
    state: Arc<Mutex<ServerState>>,
    stop: Arc<AtomicBool>,
    on_incoming: IncomingCallback,
    mdns: Option<ServiceDaemon>,
    thread: Option<thread::JoinHandle<()>>,
}

fn hex_encode(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn hex_decode(hex: &str) -> Result<Vec<u8>, String> {
    if hex.len() % 2 != 0 {
        return Err("Invalid hex length".into());
    }
    let mut out = Vec::with_capacity(hex.len() / 2);
    for pair in hex.as_bytes().chunks(2) {
        let s = std::str::from_utf8(pair).map_err(|_| "Invalid hex")?;
        out.push(u8::from_str_radix(s, 16).map_err(|_| "Invalid hex")?);
    }
    Ok(out)
}

fn load_or_create_identity(path: &Path) -> Result<(PersistedIdentity, Vec<u8>, Vec<u8>), String> {
    if let Ok(bytes) = fs::read(path) {
        if let Ok(id) = serde_json::from_slice::<PersistedIdentity>(&bytes) {
            if let (Ok(cert_der), Ok(key_hex)) =
                (hex_decode(&id.cert_der_hex), secrets::unprotect(&id.key_credential))
            {
                if let Ok(key_der) = hex_decode(&key_hex) {
                    return Ok((id, cert_der, key_der));
                }
            }
        }
    }
    let cert = rcgen::generate_simple_self_signed(vec!["focus-laptop.local".into()])
        .map_err(|e| format!("Could not create TLS identity: {e}"))?;
    let cert_der = cert.cert.der().as_ref().to_vec();
    let key_der = cert.key_pair.serialize_der();
    let device_id = format!("laptop-{}", &approval::random()?[..16]);
    let identity = PersistedIdentity {
        device_id,
        cert_der_hex: hex_encode(&cert_der),
        key_credential: secrets::protect(&hex_encode(&key_der))?,
        managers: Vec::new(),
        peer_tokens: HashMap::new(),
    };
    let raw = serde_json::to_vec(&identity).map_err(|e| e.to_string())?;
    focus_browser_host::atomic_write(path, &raw)
        .map_err(|e| format!("Could not save laptop TLS identity: {e}"))?;
    Ok((identity, cert_der, key_der))
}

fn persist_identity(path: &Path, identity: &PersistedIdentity) -> Result<(), String> {
    let raw = serde_json::to_vec(identity).map_err(|e| e.to_string())?;
    focus_browser_host::atomic_write(path, &raw)
        .map_err(|e| format!("Could not save laptop LAN identity: {e}"))
}

fn sanitize_instance_name(name: &str) -> String {
    let mut out = String::from("Focus ");
    for ch in name.chars() {
        let mut buf = [0u8; 4];
        let s = ch.encode_utf8(&mut buf);
        if out.len() + s.len() > 60 {
            break;
        }
        out.push(ch);
    }
    out
}

fn register_mdns(device_id: &str, name: &str, port: u16) -> Option<ServiceDaemon> {
    let daemon = ServiceDaemon::new().ok()?;
    let host_slug: String = device_id
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-')
        .take(32)
        .collect();
    let host_name = format!("focus-{host_slug}.local.");
    let instance = sanitize_instance_name(name);
    let props = [
        ("id", device_id),
        ("kind", "laptop"),
        ("pairing", "approval-v2"),
    ];
    let info = ServiceInfo::new(
        LAPTOP_SERVICE_TYPE,
        &instance,
        &host_name,
        "",
        port,
        &props[..],
    )
    .ok()?
    .enable_addr_auto();
    let _ = daemon.register(info);
    Some(daemon)
}

impl LaptopServer {
    pub fn start(
        dir: &Path,
        name: String,
        bind_addr: Option<&str>,
        advertise_mdns: bool,
        snapshot_fn: SnapshotCallback,
        command_fn: CommandCallback,
        habits_fn: HabitsCallback,
        on_incoming: IncomingCallback,
    ) -> Result<Self, String> {
        Self::start_with_events(dir, name, bind_addr, advertise_mdns, snapshot_fn, command_fn,
            habits_fn, on_incoming, events::EventLog::new(dir, "unknown"))
    }

    #[allow(clippy::too_many_arguments)]
    pub fn start_with_events(
        dir: &Path,
        name: String,
        bind_addr: Option<&str>,
        advertise_mdns: bool,
        snapshot_fn: SnapshotCallback,
        command_fn: CommandCallback,
        habits_fn: HabitsCallback,
        on_incoming: IncomingCallback,
        events: events::EventLog,
    ) -> Result<Self, String> {
        let path = dir.join("lan-identity.json");
        let (identity, cert_der, key_der) = load_or_create_identity(&path)?;
        let fingerprint = transport::fingerprint(&cert_der);
        let tls = ServerConfig::builder_with_provider(Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .map_err(|e| e.to_string())?
        .with_no_client_auth()
        .with_single_cert(
            vec![CertificateDer::from(cert_der)],
            PrivateKeyDer::try_from(key_der).map_err(|e| format!("Invalid TLS key: {e}"))?,
        )
        .map_err(|e| format!("TLS server config failed: {e}"))?;

        let listener = match bind_addr {
            Some(addr) => TcpListener::bind(addr),
            None => TcpListener::bind(("0.0.0.0", DEFAULT_PORT))
                .or_else(|_| TcpListener::bind(("0.0.0.0", 0))),
        }
        .map_err(|e| format!("Could not bind laptop LAN listener: {e}"))?;
        listener
            .set_nonblocking(true)
            .map_err(|e| format!("Could not configure LAN listener: {e}"))?;
        let port = listener
            .local_addr()
            .map_err(|e| e.to_string())?
            .port();

        let mdns = if advertise_mdns {
            register_mdns(&identity.device_id, &name, port)
        } else {
            None
        };

        let state = Arc::new(Mutex::new(ServerState {
            identity,
            pending: None,
        }));
        let stop = Arc::new(AtomicBool::new(false));
        let tls = Arc::new(tls);

        let t_state = state.clone();
        let t_stop = stop.clone();
        let t_path = path.clone();
        let t_name = name.clone();
        let t_fp = fingerprint.clone();
        let t_incoming = on_incoming.clone();
        let recent = Arc::new(Mutex::new(HashMap::new()));
        let thread = thread::spawn(move || {
            // Only this accept loop acquires slots. Each handler drops its slot on exit.
            let slots = Arc::new(());
            while !t_stop.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((tcp, _)) => {
                        if Arc::strong_count(&slots) > MAX_CONNECTIONS {
                            continue;
                        }
                        let slot = slots.clone();
                        let deadline = Instant::now() + REQUEST_TIMEOUT;
                        let tls = tls.clone();
                        let state = t_state.clone();
                        let path = t_path.clone();
                        let name = t_name.clone();
                        let fp = t_fp.clone();
                        let snap = snapshot_fn.clone();
                        let cmd = command_fn.clone();
                        let inc = t_incoming.clone();
                        let habits = habits_fn.clone();
                        let events = events.clone();
                        let recent = recent.clone();
                        let _ = thread::Builder::new().spawn(move || {
                            let _slot = slot;
                            let started = Instant::now();
                            let mut trace = ServerOutcome::default();
                            let result = handle_connection(tcp, deadline, tls, state, &path, &name, &fp,
                                snap, cmd, habits, inc, &mut trace);
                            let (stage, cause) = match result {
                                Err(error) => (if trace.tls { "request" } else { "tls" },
                                    events::io_cause(&error, "lan_rejected")),
                                Ok(()) if trace.status == 0 => ("request", "lan_invalid_reply"),
                                Ok(()) => (if trace.status == 401 { "auth" } else { "request" },
                                    events::status_cause(trace.status)),
                            };
                            if trace.correlation.is_none() {
                                let mut recent = recent.lock().unwrap();
                                let key = (stage, cause, trace.status, trace.authenticated);
                                if recent.get(&key).is_some_and(|at: &Instant| at.elapsed() < Duration::from_secs(300)) {
                                    return;
                                }
                                recent.insert(key, started);
                            }
                            let mut fields = json!({
                                "direction": "phone_to_laptop",
                                "authenticated": trace.authenticated,
                            });
                            if trace.status != 0 {
                                fields["http_status"] = json!(trace.status);
                                fields["status_class"] = json!(trace.status / 100);
                            }
                            if let Some(id) = trace.correlation {
                                fields["correlation_id"] = json!(id);
                            }
                            events.record("lan", stage, if cause == "ok" { "ok" } else { "failed" },
                                cause, events::elapsed(started), fields);
                        });
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        telemetry::DEVICE_SERVER_TICK.count(1);
                        thread::sleep(Duration::from_millis(25));
                    }
                    Err(_) => break,
                }
            }
        });

        Ok(Self {
            path,
            name,
            port,
            fingerprint,
            state,
            stop,
            on_incoming,
            mdns,
            thread: Some(thread),
        })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn device_id(&self) -> String {
        self.state.lock().unwrap().identity.device_id.clone()
    }

    pub fn name(&self) -> &str {
        &self.name
    }

    pub fn fingerprint(&self) -> &str {
        &self.fingerprint
    }

    pub fn pending_approval(&self) -> Option<IncomingApprovalView> {
        let mut st = self.state.lock().unwrap();
        expire_if_needed(&mut st);
        st.pending
            .as_ref()
            .filter(|p| p.status == "waiting" && !p.code.is_empty())
            .map(|p| IncomingApprovalView {
                request_id: p.request_id.clone(),
                manager_name: p.manager_name.clone(),
                code: p.code.clone(),
            })
    }

    pub fn approve(&self, request_id: &str) -> Result<(), String> {
        let mut st = self.state.lock().unwrap();
        expire_if_needed(&mut st);
        let ServerState { identity, pending } = &mut *st;
        let Some(pending) = pending.as_mut() else {
            return Err("Pairing request expired".into());
        };
        if pending.request_id != request_id || pending.status != "waiting" || pending.code.is_empty() {
            return Err("Pairing request is no longer waiting".into());
        }
        let token = approval::random()?;
        let hash = transport::fingerprint(token.as_bytes());
        if identity.managers.len() >= 16 {
            return Err("Too many paired devices".into());
        }
        identity.managers.push(hash);
        persist_identity(&self.path, identity)?;
        pending.token = token;
        pending.status = "approved".into();
        drop(st);
        (self.on_incoming)(None);
        Ok(())
    }

    pub fn decline(&self, request_id: &str) -> Result<(), String> {
        let mut st = self.state.lock().unwrap();
        if let Some(pending) = st.pending.as_mut() {
            if pending.request_id == request_id {
                pending.status = "declined".into();
            }
        }
        drop(st);
        (self.on_incoming)(None);
        Ok(())
    }

    /// Creates or retrieves a dedicated reciprocal token for a paired phone so Windows
    /// can link both ways automatically during `/v1/sync` without requiring a second pairing.
    pub fn reciprocal_for_peer(&self, peer_id: &str, local: SocketAddr) -> Result<Value, String> {
        let mut st = self.state.lock().unwrap();
        let token = if let Some(cred) = st.identity.peer_tokens.get(peer_id) {
            secrets::unprotect(cred).ok()
        } else {
            None
        };
        let token = match token {
            Some(t) => t,
            None => {
                let t = approval::random()?;
                let hash = transport::fingerprint(t.as_bytes());
                if !st.identity.managers.contains(&hash) {
                    st.identity.managers.push(hash);
                }
                let cred = secrets::protect(&t)?;
                st.identity.peer_tokens.insert(peer_id.to_owned(), cred);
                persist_identity(&self.path, &st.identity)?;
                t
            }
        };
        let hash = transport::fingerprint(token.as_bytes());
        if !st.identity.managers.contains(&hash) {
            st.identity.managers.push(hash);
            persist_identity(&self.path, &st.identity)?;
        }
        drop(st);

        let address = reciprocal_address(local.ip(), self.port);
        Ok(json!({
            "address": address,
            "fingerprint": self.fingerprint,
            "token": token,
        }))
    }
}

impl Drop for LaptopServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(daemon) = self.mdns.take() {
            let _ = daemon.shutdown();
        }
        if let Some(handle) = self.thread.take() {
            let _ = handle.join();
        }
    }
}

// Use the local address of the established sync connection. The production listener
// is IPv4-only. Avoid advertising Windows autoconfiguration or CGNAT addresses.
fn reciprocal_address(ip: IpAddr, port: u16) -> String {
    match ip {
        IpAddr::V4(ip) if ip.is_private() || ip.is_loopback() => SocketAddr::new(ip.into(), port).to_string(),
        _ => String::new(),
    }
}

fn expire_if_needed(st: &mut ServerState) {
    if let Some(p) = st.pending.as_mut() {
        if p.started.elapsed().as_secs() >= APPROVAL_TTL_SECS && p.status == "waiting" {
            p.status = "expired".into();
        }
    }
}

fn hello_json(device_id: &str, name: &str) -> Value {
    json!({
        "version": 1,
        "kind": "laptop",
        "device_id": device_id,
        "name": name,
        "pairing": "approval-v2"
    })
}

fn write_json(stream: &mut impl Write, status: u16, body: &Value) -> std::io::Result<()> {
    let reason = match status {
        200 => "OK",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        409 => "Conflict",
        _ => "Error",
    };
    let payload = serde_json::to_vec(body).unwrap_or_else(|_| b"{}".to_vec());
    write!(
        stream,
        "HTTP/1.1 {status} {reason}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        payload.len()
    )?;
    stream.write_all(&payload)?;
    stream.flush()?;
    telemetry::DEVICE_INBOUND_TX.count(payload.len() as u64);
    Ok(())
}

// Bound the entire handshake and request, including peers that send just enough
// data to keep an ordinary per-read socket timeout from firing.
struct RequestSocket {
    tcp: TcpStream,
    deadline: Option<Instant>,
}

impl RequestSocket {
    fn remaining(deadline: Instant) -> io::Result<Duration> {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(io::ErrorKind::TimedOut.into());
        }
        Ok(remaining)
    }

    fn finish_request(&mut self) -> io::Result<()> {
        if let Some(deadline) = self.deadline.take() {
            Self::remaining(deadline)?;
        }
        self.tcp.set_read_timeout(Some(REQUEST_TIMEOUT))?;
        self.tcp.set_write_timeout(Some(REQUEST_TIMEOUT))
    }
}

impl Read for RequestSocket {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if let Some(deadline) = self.deadline {
            self.tcp.set_read_timeout(Some(Self::remaining(deadline)?))?;
        }
        self.tcp.read(buf)
    }
}

impl Write for RequestSocket {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if let Some(deadline) = self.deadline {
            self.tcp.set_write_timeout(Some(Self::remaining(deadline)?))?;
        }
        self.tcp.write(buf)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.tcp.flush()
    }
}

fn read_request_line(head: &mut impl BufRead, line: &mut String) -> io::Result<()> {
    line.clear();
    head.read_line(line)?;
    if !line.ends_with('\n') {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Incomplete or oversized request headers"));
    }
    Ok(())
}

#[derive(Default)]
struct ServerOutcome {
    tls: bool,
    authenticated: bool,
    correlation: Option<String>,
    status: u16,
}

#[allow(clippy::too_many_arguments)]
fn handle_connection(
    tcp: TcpStream,
    deadline: Instant,
    tls: Arc<ServerConfig>,
    state: Arc<Mutex<ServerState>>,
    path: &Path,
    name: &str,
    fingerprint: &str,
    snapshot_fn: SnapshotCallback,
    command_fn: CommandCallback,
    habits_fn: HabitsCallback,
    on_incoming: IncomingCallback,
    trace: &mut ServerOutcome,
) -> std::io::Result<()> {
    tcp.set_nonblocking(false)?;
    let conn = ServerConnection::new(tls).map_err(std::io::Error::other)?;
    let socket = RequestSocket { tcp, deadline: Some(deadline) };
    let mut stream = StreamOwned::new(conn, socket);
    while stream.conn.is_handshaking() {
        stream.conn.complete_io(&mut stream.sock)?;
    }
    trace.tls = true;
    let mut stream = BufReader::new(stream);

    let mut head = (&mut stream).take(MAX_REQUEST_HEAD);
    let mut req_line = String::new();
    read_request_line(&mut head, &mut req_line)?;
    telemetry::DEVICE_INBOUND.count(1);
    let mut parts = req_line.split_whitespace();
    let method = parts.next().unwrap_or("").to_owned();
    let req_path = parts.next().unwrap_or("").to_owned();

    let mut content_length: usize = 0;
    let mut auth_header = String::new();
    let mut header_count = 0;
    let mut line = String::new();
    loop {
        read_request_line(&mut head, &mut line)?;
        if line == "\r\n" || line == "\n" {
            break;
        }
        header_count += 1;
        if header_count > 64 {
            trace.status = 400;
            return write_json(stream.get_mut(), 400, &json!({"error": "Too many headers"}));
        }
        if let Some((k, v)) = line.split_once(':') {
            let k = k.trim().to_ascii_lowercase();
            let v = v.trim();
            if k == "content-length" {
                content_length = v.parse().unwrap_or(usize::MAX);
            } else if k == "authorization" {
                auth_header = v.to_owned();
            }
        }
    }
    let body_limit = if req_path == "/v1/habits" { 512 * 1024 } else { MAX_REQUEST_BODY };
    if content_length > body_limit {
        trace.status = 400;
        return write_json(stream.get_mut(), 400, &json!({"error": "Request too large"}));
    }
    let mut body = vec![0u8; content_length];
    if content_length > 0 {
        stream.read_exact(&mut body)?;
    }
    stream.get_mut().sock.finish_request()?;

    telemetry::DEVICE_INBOUND_RX.count(body.len() as u64);
    let (status, resp) = dispatch(
        &method,
        &req_path,
        &auth_header,
        &body,
        &state,
        path,
        name,
        fingerprint,
        &snapshot_fn,
        &command_fn,
        &habits_fn,
        &on_incoming,
        trace,
    );
    trace.status = status;
    write_json(stream.get_mut(), status, &resp)
}

#[allow(clippy::too_many_arguments)]
fn dispatch(
    method: &str,
    req_path: &str,
    auth_header: &str,
    body: &[u8],
    state: &Arc<Mutex<ServerState>>,
    path: &Path,
    name: &str,
    fingerprint: &str,
    snapshot_fn: &SnapshotCallback,
    command_fn: &CommandCallback,
    habits_fn: &HabitsCallback,
    on_incoming: &IncomingCallback,
    trace: &mut ServerOutcome,
) -> (u16, Value) {
    let device_id = state.lock().unwrap().identity.device_id.clone();
    if method == "GET" && req_path == "/v1/hello" {
        return (200, hello_json(&device_id, name));
    }

    if method == "POST" && req_path.starts_with("/v2/pair/") {
        let Ok(input) = serde_json::from_slice::<Value>(body) else {
            return (400, json!({"error": "Invalid JSON"}));
        };
        let mut st = state.lock().unwrap();
        expire_if_needed(&mut st);

        if req_path == "/v2/pair/request" {
            let Some(commitment) = input["commitment"]
                .as_str()
                .filter(|s| s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit()))
            else {
                return (400, json!({"error": "Invalid commitment"}));
            };
            let manager_name = input["manager_name"]
                .as_str()
                .unwrap_or("Phone")
                .trim()
                .chars()
                .take(80)
                .collect::<String>();
            let Ok(request_id) = approval::random() else {
                return (500, json!({"error": "Random unavailable"}));
            };
            let Ok(poll_secret) = approval::random() else {
                return (500, json!({"error": "Random unavailable"}));
            };
            let Ok(server_nonce) = approval::random() else {
                return (500, json!({"error": "Random unavailable"}));
            };
            st.pending = Some(ApprovalSession {
                request_id: request_id.clone(),
                poll_secret: poll_secret.clone(),
                commitment: commitment.to_owned(),
                server_nonce: server_nonce.clone(),
                manager_name: if manager_name.is_empty() {
                    "Phone".into()
                } else {
                    manager_name
                },
                code: String::new(),
                status: "waiting".into(),
                token: String::new(),
                started: Instant::now(),
            });
            return (
                200,
                json!({
                    "request_id": request_id,
                    "poll_secret": poll_secret,
                    "server_nonce": server_nonce
                }),
            );
        }

        let Some(req_id) = input["request_id"].as_str() else {
            return (400, json!({"error": "Missing request_id"}));
        };
        let Some(secret) = input["poll_secret"].as_str() else {
            return (400, json!({"error": "Missing poll_secret"}));
        };
        let ServerState { identity, pending } = &mut *st;
        let Some(pending) = pending.as_mut() else {
            return (400, json!({"error": "Invalid or expired pairing request"}));
        };
        if pending.request_id != req_id || pending.poll_secret != secret {
            return (403, json!({"error": "Invalid pairing credentials"}));
        }

        if req_path == "/v2/pair/reveal" {
            let Some(client_nonce) = input["client_nonce"]
                .as_str()
                .filter(|s| s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit()))
            else {
                return (400, json!({"error": "Invalid client_nonce"}));
            };
            if transport::fingerprint(client_nonce.as_bytes()) != pending.commitment {
                return (400, json!({"error": "Commitment mismatch"}));
            }
            pending.code = approval::comparison(fingerprint, client_nonce, &pending.server_nonce);
            let view = IncomingApprovalView {
                request_id: pending.request_id.clone(),
                manager_name: pending.manager_name.clone(),
                code: pending.code.clone(),
            };
            let mut reply = hello_json(&device_id, name);
            reply["status"] = json!(pending.status);
            reply["code"] = json!(pending.code);
            drop(st);
            on_incoming(Some(view));
            return (200, reply);
        }

        if req_path == "/v2/pair/cancel" {
            if pending.status == "approved" && !pending.token.is_empty() {
                let hash = transport::fingerprint(pending.token.as_bytes());
                identity.managers.retain(|h| h != &hash);
                let _ = persist_identity(path, identity);
            }
            pending.status = "declined".into();
            let mut reply = hello_json(&device_id, name);
            reply["status"] = json!("declined");
            reply["code"] = json!(pending.code);
            drop(st);
            on_incoming(None);
            return (200, reply);
        }

        if req_path == "/v2/pair/status" {
            let mut reply = hello_json(&device_id, name);
            reply["status"] = json!(pending.status);
            reply["code"] = json!(pending.code);
            if pending.status == "approved" {
                reply["token"] = json!(pending.token);
            }
            return (200, reply);
        }
        return (404, json!({"error": "Unknown pairing action"}));
    }

    // Authenticated endpoints
    let authorized = auth_header
        .strip_prefix("Bearer ")
        .filter(|t| t.len() == 64 && t.bytes().all(|b| b.is_ascii_hexdigit()))
        .map(|t| {
            let hash = transport::fingerprint(t.as_bytes());
            state.lock().unwrap().identity.managers.contains(&hash)
        })
        .unwrap_or(false);
    if !authorized {
        return (
            401,
            json!({"error": "Pair this laptop again; device access was refused"}),
        );
    }

    trace.authenticated = true;

    if method == "POST" && req_path == "/v1/habits" {
        let Ok(input) = serde_json::from_slice::<Value>(body) else {
            return (400, json!({"error": "Invalid habits JSON"}));
        };
        return match habits_fn(input) {
            Ok(result) => (200, result),
            Err(err) => (409, json!({"error": err})),
        };
    }
    if method == "POST" && req_path == "/v1/command" {
        let Ok(input) = serde_json::from_slice::<Value>(body) else {
            return (400, json!({"error": "Invalid command JSON"}));
        };
        trace.correlation = input["correlation_id"].as_str().filter(|id| events::valid_id(id)).map(str::to_owned);
        match command_fn(input) {
            Ok(Some(result)) => return (200, result),
            Ok(None) => {}
            Err(err) => return (409, json!({"error": err})),
        }
    } else if !(method == "GET" && req_path == "/v1/snapshot") {
        return (404, json!({"error": "Unsupported laptop API request"}));
    }

    match snapshot_fn() {
        Ok(mut snap) => {
            if let Some(obj) = snap.as_object_mut() {
                obj.insert("version".into(), json!(1));
                obj.insert("kind".into(), json!("laptop"));
                obj.insert("device_id".into(), json!(device_id));
                obj.insert("name".into(), json!(name));
                obj.insert("pairing".into(), json!("approval-v2"));
            }
            (200, snap)
        }
        Err(err) => (500, json!({"error": err})),
    }
}

#[cfg(all(test, any(windows, feature = "portable-test-harness")))]
mod tests {
    use super::*;

    #[test]
    fn oversized_headers_are_refused() {
        let request = format!("GET /v1/hello HTTP/1.1\r\nX-Test: {}\r\n\r\n", "a".repeat(MAX_REQUEST_HEAD as usize));
        let mut head = io::Cursor::new(request.as_bytes()).take(MAX_REQUEST_HEAD);
        let mut line = String::new();
        read_request_line(&mut head, &mut line).unwrap();
        assert_eq!(read_request_line(&mut head, &mut line).unwrap_err().kind(), io::ErrorKind::InvalidData);
        assert_eq!(head.limit(), 0);
    }

    #[test]
    fn excess_connections_close_and_stalled_connections_release_slots() {
        let dir = tempfile::tempdir().unwrap();
        let server = LaptopServer::start(
            dir.path(), "Test laptop".into(), Some("127.0.0.1:0"), false,
            Arc::new(|| Ok(json!({}))), Arc::new(|_| Ok(None)), Arc::new(Ok), Arc::new(|_| {}),
        ).unwrap();
        let address = format!("127.0.0.1:{}", server.port());
        let mut stalled: Vec<_> = (0..MAX_CONNECTIONS)
            .map(|_| TcpStream::connect(&address).unwrap()).collect();
        // Connect one more peer while all handlers are waiting for TLS.
        let mut extra = TcpStream::connect(&address).unwrap();
        extra.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        assert_closed(&mut extra);

        for peer in &mut stalled {
            peer.set_read_timeout(Some(REQUEST_TIMEOUT + Duration::from_secs(3))).unwrap();
            assert_closed(peer);
        }
        let (hello, pin) = transport::request(&address, None, None, "/v1/hello", None).unwrap();
        assert_eq!(hello["kind"], "laptop");
        assert_eq!(pin, server.fingerprint());
    }

    fn assert_closed(peer: &mut TcpStream) {
        match peer.read(&mut [0]) {
            Ok(0) => {},
            Err(error) if matches!(error.kind(), io::ErrorKind::ConnectionReset | io::ErrorKind::ConnectionAborted) => {},
            result => panic!("expected the listener to close the connection, got {result:?}"),
        }
    }

    #[test]
    fn request_deadline_applies_after_partial_read_progress() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let mut peer = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (tcp, _) = listener.accept().unwrap();
        peer.write_all(b"ab").unwrap();

        let deadline = Instant::now() + Duration::from_secs(1);
        let mut socket = RequestSocket { tcp, deadline: Some(deadline) };
        let mut byte = [0];
        socket.read_exact(&mut byte).unwrap();
        assert_eq!(byte, *b"a");
        thread::sleep(deadline.saturating_duration_since(Instant::now()));
        assert_eq!(socket.read_exact(&mut byte).unwrap_err().kind(), io::ErrorKind::TimedOut);

        // Another byte was ready, so an idle timeout alone could not have stopped the read.
        socket.deadline = None;
        socket.read_exact(&mut byte).unwrap();
        assert_eq!(byte, *b"b");
    }

    #[test]
    fn reciprocal_address_uses_routable_lan_source() {
        assert_eq!(reciprocal_address("192.168.1.80".parse().unwrap(), 47833), "192.168.1.80:47833");
        for unsuitable in ["169.254.3.2", "100.117.49.14", "0.0.0.0", "::1"] {
            assert!(reciprocal_address(unsuitable.parse().unwrap(), 47833).is_empty());
        }
    }

    #[test]
    fn laptop_server_supports_approval_snapshot_command_and_reciprocal() {
        let dir = tempfile::tempdir().unwrap();
        let paused = Arc::new(Mutex::new(false));
        let p_snap = paused.clone();
        let p_cmd = paused.clone();
        let server = LaptopServer::start(
            dir.path(),
            "Fixture laptop".into(),
            Some("127.0.0.1:0"),
            false,
            Arc::new(move || {
                Ok(json!({
                    "scope": "personal",
                    "now": 1000,
                    "spans": [{"s": 100, "e": 500, "app": "Code", "label": "VS Code", "domain": ""}],
                    "pauses": [],
                    "dns": {"enabled": true, "profile": "", "status": if *p_snap.lock().unwrap() { "Paused" } else { "On" }, "pause_until": 0},
                    "permissions": {"usage": true, "restrictions": true, "dns": true},
                    "tracking_age_ms": 0,
                    "time_zone": "UTC",
                    "apps": []
                }))
            }),
            Arc::new(move |cmd| {
                if cmd["action"] == "dns_pause" {
                    *p_cmd.lock().unwrap() = true;
                    Ok(None)
                } else if cmd["action"] == "judge_pause" {
                    Ok(Some(json!({"id": "decision", "decision": {"action": "deny"}})))
                } else {
                    Err("unsupported".into())
                }
            }),
            Arc::new(Ok),
            Arc::new(|_| {}),
        )
        .unwrap();

        let addr = format!("127.0.0.1:{}", server.port());
        let (hello, pin) = transport::request(&addr, None, None, "/v1/hello", None).unwrap();
        assert_eq!(hello["kind"], "laptop");
        assert_eq!(pin, server.fingerprint());

        // Unauthenticated snapshot rejected
        assert!(transport::request(&addr, Some(&pin), None, "/v1/snapshot", None).is_err());

        assert!(transport::request(&addr, Some(&pin), None, "/v1/habits", Some(&json!({}))).is_err());

        // Approval v2 handshake
        let client_nonce = "b".repeat(64);
        let commitment = transport::fingerprint(client_nonce.as_bytes());
        let (req, _) = transport::request(
            &addr,
            Some(&pin),
            None,
            "/v2/pair/request",
            Some(&json!({"commitment": commitment, "manager_name": "S25 Ultra"})),
        )
        .unwrap();
        let req_id = req["request_id"].as_str().unwrap().to_owned();
        let poll_secret = req["poll_secret"].as_str().unwrap().to_owned();
        let server_nonce = req["server_nonce"].as_str().unwrap().to_owned();
        let expected = approval::comparison(&pin, &client_nonce, &server_nonce);
        let (rev, _) = transport::request(
            &addr,
            Some(&pin),
            None,
            "/v2/pair/reveal",
            Some(&json!({"request_id": req_id, "poll_secret": poll_secret, "client_nonce": client_nonce})),
        )
        .unwrap();
        assert_eq!(rev["code"], expected);
        assert_eq!(server.pending_approval().unwrap().code, expected);
        server.approve(&req_id).unwrap();
        let (st, _) = transport::request(
            &addr,
            Some(&pin),
            None,
            "/v2/pair/status",
            Some(&json!({"request_id": req_id, "poll_secret": poll_secret})),
        )
        .unwrap();
        assert_eq!(st["status"], "approved");
        let token = st["token"].as_str().unwrap().to_owned();

        // Snapshot + Command
        let (snap, _) = transport::request(&addr, Some(&pin), Some(&token), "/v1/snapshot", None).unwrap();
        let payload = json!({"habits": {"events": {}, "definitions": {}}, "after": ""});
        let (habits, _) = transport::request(&addr, Some(&pin), Some(&token), "/v1/habits", Some(&payload)).unwrap();
        assert_eq!(habits, payload);

        assert_eq!(snap["kind"], "laptop");
        assert_eq!(snap["dns"]["status"], "On");
        let (after, _) = transport::request(
            &addr,
            Some(&pin),
            Some(&token),
            "/v1/command",
            Some(&json!({"action": "dns_pause", "minutes": 5, "reason": "Testing pause"})),
        )
        .unwrap();
        assert_eq!(after["dns"]["status"], "Paused");
        let judge = json!({"action": "judge_pause", "request": {"text": "Can I pause?"}});
        assert!(transport::request(&addr, Some(&pin), None, "/v1/command", Some(&judge)).is_err());
        let (decision, _) = transport::request(&addr, Some(&pin), Some(&token), "/v1/command", Some(&judge)).unwrap();
        assert_eq!(decision["decision"]["action"], "deny");

        // Reciprocal credential works immediately without approval flow
        let recip = server.reciprocal_for_peer("phone-1", addr.parse().unwrap()).unwrap();
        let recip_tok = recip["token"].as_str().unwrap();
        let (snap2, _) = transport::request(&addr, Some(&pin), Some(recip_tok), "/v1/snapshot", None).unwrap();
        assert_eq!(snap2["kind"], "laptop");
    }
}
