use focus_devices::{transport, DeviceManager};
use rustls::{pki_types::PrivateKeyDer, ServerConfig, ServerConnection, StreamOwned};
use serde_json::{json, Value};
use std::{
    io::{BufRead, BufReader, Read, Write},
    net::{TcpListener, TcpStream},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    thread,
    time::Duration,
};

struct Fixture {
    address: String,
    pin: String,
    state: Arc<Mutex<Value>>,
    stop: Arc<AtomicBool>,
    thread: Option<thread::JoinHandle<()>>,
}
impl Fixture {
    fn start() -> Self {
        Self::bind("127.0.0.1:0")
    }
    fn bind(address: &str) -> Self {
        let cert = rcgen::generate_simple_self_signed(vec!["focus-tv.test".into()]).unwrap();
        let pin = transport::fingerprint(cert.cert.der().as_ref());
        let tls =
            ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
                .with_safe_default_protocol_versions()
                .unwrap()
                .with_no_client_auth()
                .with_single_cert(
                    vec![cert.cert.der().clone()],
                    PrivateKeyDer::try_from(cert.key_pair.serialize_der()).unwrap(),
                )
                .unwrap();
        let listener = TcpListener::bind(address).unwrap();
        listener.set_nonblocking(true).unwrap();
        let address = listener.local_addr().unwrap().to_string();
        let state = Arc::new(Mutex::new(
            json!({"blocked":false,"dns_status":"On","token":"a".repeat(64),"paired":false,"commands":0,"wrong_identity":false,"malformed":false}),
        ));
        let stop = Arc::new(AtomicBool::new(false));
        let (server_state, server_stop) = (state.clone(), stop.clone());
        let tls = Arc::new(tls);
        let server_pin = pin.clone();
        let thread = thread::spawn(move || {
            while !server_stop.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((tcp, _)) => {
                        let _ = serve(tcp, tls.clone(), server_state.clone(), &server_pin);
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(5))
                    }
                    Err(_) => break,
                }
            }
        });
        Self {
            address,
            pin,
            state,
            stop,
            thread: Some(thread),
        }
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        self.thread.take().unwrap().join().unwrap();
    }
}
const PHONE_ID: &str = "5efae4b0-bbce-499e-b457-6df287c62211";

fn serve(
    tcp: TcpStream,
    tls: Arc<ServerConfig>,
    state: Arc<Mutex<Value>>,
    pin: &str,
) -> std::io::Result<()> {
    // Windows accepted sockets inherit the listener's nonblocking mode.
    tcp.set_nonblocking(false)?;
    tcp.set_read_timeout(Some(Duration::from_secs(2)))?;
    let mut stream = BufReader::new(StreamOwned::new(ServerConnection::new(tls).unwrap(), tcp));
    let mut line = String::new();
    stream.read_line(&mut line)?;
    let path = line.split_whitespace().nth(1).unwrap_or("").to_owned();
    let mut length = 0;
    let mut token = String::new();
    loop {
        line.clear();
        if stream.read_line(&mut line)? == 0 {
            return Ok(());
        }
        if line == "\r\n" {
            break;
        }
        if let Some(v) = line.strip_prefix("Content-Length: ") {
            length = v.trim().parse().unwrap();
        }
        if let Some(v) = line.strip_prefix("Authorization: Bearer ") {
            token = v.trim().to_owned();
        }
    }
    let mut body = vec![0; length];
    stream.read_exact(&mut body)?;
    let data: Value = serde_json::from_slice(&body).unwrap_or(Value::Null);
    let mut state = state.lock().unwrap();
    let phone = state["phone"] == true;
    let id = if state["wrong_identity"] == true {
        "another-tv"
    } else if phone {
        PHONE_ID
    } else {
        "tv-fixture"
    };
    let hello = json!({"version":1,"kind":if phone {"phone"} else {"tv"},
        "device_id":id,"name":"Fixture TV","pairing":"approval-v2"});
    let (status, response) = if path == "/v1/sync" && length > 4 * 1024 * 1024 {
        (413, json!({"error":"Request too large"}))
    } else if path == "/v1/hello" {
        (200, hello)
    } else if path == "/v2/pair/request" {
        state["commitment"] = data["commitment"].clone();
        state["approval"] = json!("committed");
        (
            200,
            json!({"request_id":"e".repeat(64),"poll_secret":"f".repeat(64),"server_nonce":"c".repeat(64)}),
        )
    } else if path.starts_with("/v2/pair/") {
        if data["request_id"] != "e".repeat(64) || data["poll_secret"] != "f".repeat(64) {
            (403, json!({"error":"Unknown request"}))
        } else {
            if path.ends_with("reveal") {
                let nonce = data["client_nonce"].as_str().unwrap();
                assert_eq!(
                    state["commitment"],
                    transport::fingerprint(nonce.as_bytes())
                );
                let digest = transport::fingerprint(
                    format!("focus-approval-v2\n{pin}\n{nonce}\n{}", "c".repeat(64)).as_bytes(),
                );
                state["code"] = json!(format!(
                    "{:06}",
                    u32::from_str_radix(&digest[..8], 16).unwrap() % 1_000_000
                ));
                state["approval"] = json!("waiting");
            }
            if path.ends_with("cancel") {
                state["approval"] = json!("declined");
            }
            let mut reply = hello;
            reply["status"] = state["approval"].clone();
            reply["code"] = state["code"].clone();
            if state["approval"] == "approved" {
                state["paired"] = json!(true);
                reply["token"] = state["token"].clone();
            }
            (200, reply)
        }
    } else if path == "/v1/pair" {
        if data["code"] == "123456" && state["paired"] == false {
            state["paired"] = json!(true);
            let mut reply = hello;
            reply["token"] = state["token"].clone();
            (200, reply)
        } else {
            (
                403,
                json!({"error":"Code incorrect, expired or already used"}),
            )
        }
    } else if !state["paired"].as_bool().unwrap() || token != state["token"].as_str().unwrap() {
        (401, json!({"error":"Pair this TV again"}))
    } else if path == "/v1/command" && data["action"] == "dns_pause"
        && state["pause_choices"].as_array().is_some_and(|choices| !choices.contains(&data["minutes"]))
    {
        state["commands"] = json!(state["commands"].as_u64().unwrap() + 1);
        (409, json!({"error":"The remaining allowance changed. Choose a shorter pause."}))
    } else {
        if path == "/v1/command" {
            state["commands"] = json!(state["commands"].as_u64().unwrap() + 1);
            if data["action"] == "set_rule" {
                state["blocked"] = data["blocked"].clone();
            }
            if data["action"] == "dns_pause" {
                state["dns_status"] = json!("Paused");
                state["pauses"] = json!([{
                    "t": 100, "end": 300_100, "minutes": data["minutes"],
                    "reason": data["reason"], "package": "focus:dns", "kind": "dns"
                }]);
            }
        }
        let mut snapshot = hello;
        snapshot["scope"] = json!("household");
        snapshot["now"] = json!(12345678u64);
        snapshot["spans"] = json!([{ "s":100,"e":200,"app":"video","label":"Video" }]);
        snapshot["pauses"] = state.get("pauses").cloned().unwrap_or_else(|| json!([]));
        snapshot["apps"] = json!([{ "package":"video","label":"Video","blocked":state["blocked"], "scheduled":false,"daily_limit_ms":0,"from_minute":1320,"to_minute":420,"pause_until":0 }]);
        snapshot["dns"] =
            json!({"status":state["dns_status"],"enabled":true,"profile":"a1b2c3","pause_until":0});
        if let Some(choices) = state.get("pause_choices") {
            snapshot["dns"]["pause_choices"] = choices.clone();
        }
        snapshot["permissions"] = json!({"usage":true,"restrictions":true,"dns":true});
        snapshot["time_zone"] = json!("UTC");
        snapshot["tracking_age_ms"] = json!(0);
        if state["malformed"] == true {
            snapshot["dns"] = Value::Null;
        }
        if path == "/v1/sync" && phone {
            state["sync_bytes"] = json!(length);
            state["event_cursor"] = data["event_sync"]["after"].clone();
            if let Some(rows) = state.get("event_rows") {
                snapshot["focus_events"] = rows.clone();
            }
        }
        (200, snapshot)
    };
    let bytes = response.to_string();
    let output = stream.get_mut();
    write!(
        output,
        "HTTP/1.1 {status} Focus\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        bytes.len(),
        bytes
    )?;
    output.flush()?;
    output.conn.send_close_notify();
    output.flush()?;
    Ok(())
}

#[test]
#[cfg(any(windows, feature = "portable-test-harness"))]
fn refused_pause_refreshes_choices_without_retrying_the_pause() {
    let tv = Fixture::start();
    let dir = tempfile::tempdir().unwrap();
    let manager = DeviceManager::open(dir.path()).unwrap();
    tv.state.lock().unwrap()["pause_choices"] = json!([1, 5, 10, 30]);
    manager.pair(&tv.address, &tv.pin, "123456", "TV").unwrap();
    tv.state.lock().unwrap()["pause_choices"] = json!([1]);

    let error = manager.command("tv-fixture", json!({
        "action": "dns_pause", "minutes": 30, "reason": "Test the new allowance"
    })).err().expect("the TV must refuse a pause beyond its allowance");
    assert!(error.contains("remaining allowance"));
    let views = manager.list();
    assert!(views[0].online);
    assert_eq!(views[0].snapshot.as_ref().unwrap()["dns"]["pause_choices"], json!([1]));
    assert_eq!(tv.state.lock().unwrap()["commands"], 1);
}
#[test]
#[cfg(any(windows, feature = "portable-test-harness"))]
fn normal_phone_sync_collects_valid_events_incrementally() {
    use std::time::{SystemTime, UNIX_EPOCH};

    let phone = Fixture::start();
    phone.state.lock().unwrap()["phone"] = json!(true);
    let dir = tempfile::tempdir().unwrap();
    let manager = DeviceManager::open(dir.path()).unwrap();
    manager.pair(&phone.address, &phone.pin, "123456", "Phone").unwrap();
    // Older peers omit the page and still sync their snapshot.
    manager.sync_phone(PHONE_ID, |local| {
        assert!(local.ip().is_loopback());
        Ok(json!({"peer": {}}))
    }).unwrap();
    assert!(manager.list()[0].online);

    let id = focus_devices::events::new_id().unwrap();
    let row = json!({
        "v": 1, "at_ms": SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as u64,
        "event_id": id, "build_id": "abcdef1", "device": "phone", "correlation_id": id,
        "event": "ask", "stage": "gateway", "outcome": "failed", "cause": "gateway_transport",
        "duration_ms": 4000, "fields": {"origin": "phone", "attempt": 2, "failure_detail": "transport"},
    });
    let mut invalid = row.clone();
    invalid["fields"]["request"] = json!("private request");
    let invalid_id = focus_devices::events::new_id().unwrap();
    invalid["event_id"] = json!(invalid_id);
    phone.state.lock().unwrap()["event_rows"] = json!([row, invalid]);
    manager.sync_phone(PHONE_ID, |_| Ok(json!({"peer": {}}))).unwrap();
    assert_eq!(phone.state.lock().unwrap()["event_cursor"], "");
    assert!(manager.list()[0].snapshot.as_ref().unwrap().get("focus_events").is_none());

    manager.sync_phone(PHONE_ID, |_| Ok(json!({"peer": {}}))).unwrap();
    assert_eq!(phone.state.lock().unwrap()["event_cursor"], invalid_id);
    let events = dir.path().join("events")
        .join(format!("phone-{}.jsonl", transport::fingerprint(PHONE_ID.as_bytes())));
    let contents = std::fs::read_to_string(events).unwrap();
    assert_eq!(contents.lines().count(), 1);
    let event: Value = serde_json::from_str(&contents).unwrap();
    assert_eq!(event["fields"]["peer_id"], PHONE_ID);
    assert_eq!(event["fields"]["attempt"], 2);
    assert_eq!(event["fields"]["failure_detail"], "transport");
    assert!(!contents.contains("private request"));

    let limit = 4 * 1024 * 1024;
    let mut full = json!({"peer": {"padding": ""}});
    let bytes = limit - full.to_string().len();
    full["peer"]["padding"] = json!("é".repeat(bytes / 2) + &"x".repeat(bytes % 2));
    manager.sync_phone(PHONE_ID, |_| Ok(full)).unwrap();
    assert_eq!(phone.state.lock().unwrap()["sync_bytes"], limit);
    assert!(phone.state.lock().unwrap()["event_cursor"].is_null());
    assert!(manager.list()[0].online);

    let mut fits = json!({"peer": {"padding": ""}, "event_sync": {"after": invalid_id}});
    fits["peer"]["padding"] = json!("x".repeat(limit - fits.to_string().len()));
    fits.as_object_mut().unwrap().remove("event_sync");
    manager.sync_phone(PHONE_ID, |_| Ok(fits)).unwrap();
    assert_eq!(phone.state.lock().unwrap()["sync_bytes"], limit);
    assert_eq!(phone.state.lock().unwrap()["event_cursor"], invalid_id);
}

#[test]
#[cfg(any(windows, feature = "portable-test-harness"))]
fn pinned_pairing_controls_persistence_and_revocation() {
    let tv = Fixture::start();
    let dir = tempfile::tempdir().unwrap();
    let manager = DeviceManager::open(dir.path()).unwrap();
    assert!(transport::request(
        &tv.address,
        Some(&tv.pin),
        None,
        "/v1/command",
        Some(&json!({"action":"set_rule","blocked":true}))
    )
    .is_err());
    assert_eq!(tv.state.lock().unwrap()["commands"], 0);
    let probe = manager.probe(&tv.address).unwrap();
    assert_eq!(probe.fingerprint, tv.pin);
    assert!(manager.pair(&tv.address, &tv.pin, "000000", "TV").is_err());
    let views = manager
        .pair(&tv.address, &tv.pin, "123456", "Living room TV")
        .unwrap();
    assert!(views[0].online);
    let serialized = serde_json::to_string(&views).unwrap();
    assert!(!serialized.contains(&"a".repeat(64)));
    assert!(!serialized.contains("credential"));
    manager
        .command("tv-fixture", json!({"action":"set_rule","blocked":true}))
        .unwrap();
    assert_eq!(
        manager.list()[0].snapshot.as_ref().unwrap()["apps"][0]["blocked"],
        true
    );
    let reason = "I want to look up an example, then return to the draft. ".repeat(100);
    manager
        .command(
            "tv-fixture",
            json!({"action":"dns_pause","minutes":5,"reason":reason}),
        )
        .unwrap();
    assert_eq!(
        manager.list()[0].snapshot.as_ref().unwrap()["dns"]["status"],
        "Paused"
    );
    let reopened = DeviceManager::open(dir.path()).unwrap();
    assert!(!reopened.list()[0].online, "persisted data is never live");
    assert_eq!(
        reopened.list()[0].snapshot.as_ref().unwrap()["pauses"][0]["reason"],
        reason
    );
    assert_eq!(
        reopened.list()[0].snapshot.as_ref().unwrap()["apps"][0]["blocked"],
        true
    );
    reopened.refresh("tv-fixture").unwrap();
    assert!(reopened.list()[0].online);
    tv.state.lock().unwrap()["token"] = json!("b".repeat(64));
    assert!(reopened.refresh("tv-fixture").is_err());
    let view = &reopened.list()[0];
    assert!(!view.online);
    assert!(view.snapshot.is_some());
    assert!(view.error.is_some());
    reopened.forget("tv-fixture").unwrap();
    assert!(DeviceManager::open(dir.path()).unwrap().list().is_empty());
}
#[test]
fn changed_certificate_is_refused_before_a_command_is_received() {
    let tv = Fixture::start();
    let error = transport::request(
        &tv.address,
        Some(&"0".repeat(64)),
        Some(&"a".repeat(64)),
        "/v1/command",
        Some(&json!({"action":"set_rule","blocked":true})),
    )
    .unwrap_err();
    assert!(error.contains("identity changed"), "{error}");
    assert_eq!(tv.state.lock().unwrap()["commands"], 0);
    assert!(transport::request(
        &tv.address,
        None,
        None,
        "/v1/pair",
        Some(&json!({"code":"123456"}))
    )
    .is_err());
}
#[test]
#[cfg(any(windows, feature = "portable-test-harness"))]
fn changed_device_identity_preserves_the_last_valid_snapshot() {
    let tv = Fixture::start();
    let dir = tempfile::tempdir().unwrap();
    let manager = DeviceManager::open(dir.path()).unwrap();
    manager.pair(&tv.address, &tv.pin, "123456", "TV").unwrap();
    tv.state.lock().unwrap()["wrong_identity"] = json!(true);
    assert!(manager.refresh("tv-fixture").is_err());
    let view = &manager.list()[0];
    assert!(!view.online);
    assert_eq!(view.snapshot.as_ref().unwrap()["device_id"], "tv-fixture");
}
#[test]
#[cfg(any(windows, feature = "portable-test-harness"))]
fn incomplete_snapshot_preserves_the_last_valid_settings() {
    let tv = Fixture::start();
    let dir = tempfile::tempdir().unwrap();
    let manager = DeviceManager::open(dir.path()).unwrap();
    manager.pair(&tv.address, &tv.pin, "123456", "TV").unwrap();
    tv.state.lock().unwrap()["malformed"] = json!(true);
    assert!(manager.refresh("tv-fixture").is_err());
    let view = &manager.list()[0];
    assert!(!view.online);
    assert!(view.snapshot.as_ref().unwrap()["dns"].is_object());
}
#[test]
fn corrupt_registry_is_preserved_and_non_lan_addresses_are_rejected() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(dir.path().join("devices.json"), "broken").unwrap();
    assert!(DeviceManager::open(dir.path()).is_err());
    assert_eq!(
        std::fs::read_to_string(dir.path().join("devices.json")).unwrap(),
        "broken"
    );
    for invalid in [
        "example.com:443",
        "8.8.8.8:443",
        "127.0.0.1:0",
        "http://192.168.1.2:443",
        "[::ffff:8.8.8.8]:443",
    ] {
        assert!(transport::address(invalid).is_err(), "{invalid}");
    }
    for valid in [
        "192.168.1.20:47832",
        "10.0.0.1:1234",
        "127.0.0.1:1234",
        "[fd01::2]:47832",
    ] {
        assert!(transport::address(valid).is_ok());
    }
}

#[test]
#[cfg(any(windows, feature = "portable-test-harness"))]
fn nearby_approval_never_saves_before_tv_allows() {
    let tv = Fixture::start();
    let dir = tempfile::tempdir().unwrap();
    let manager = DeviceManager::open(dir.path()).unwrap();
    let pending = manager
        .request_resolved_approval(&tv.address, "Test laptop")
        .unwrap();
    assert_eq!(pending.code.len(), 6);
    assert!(manager.list().is_empty());
    assert!(!dir.path().join("devices.json").exists());
    assert_eq!(
        manager.approval_status(&pending.request_id).unwrap().status,
        "waiting"
    );
    assert!(manager.list().is_empty());
    tv.state.lock().unwrap()["approval"] = json!("approved");
    assert_eq!(
        manager.approval_status(&pending.request_id).unwrap().status,
        "approved"
    );
    assert!(manager.list()[0].online);
    let serialized = serde_json::to_string(&manager.list()).unwrap();
    assert!(!serialized.contains(&"a".repeat(64)));
    assert!(!serde_json::to_string(&pending)
        .unwrap()
        .contains("poll_secret"));
    let reopened = DeviceManager::open(dir.path()).unwrap();
    reopened.refresh("tv-fixture").unwrap();
    assert!(reopened.list()[0].online);
}
#[test]
#[cfg(any(windows, feature = "portable-test-harness"))]
fn cancelled_declined_and_changed_identity_do_not_pair() {
    let tv = Fixture::start();
    let dir = tempfile::tempdir().unwrap();
    let manager = DeviceManager::open(dir.path()).unwrap();
    let a = manager
        .request_resolved_approval(&tv.address, "Laptop")
        .unwrap();
    manager.cancel_approval(&a.request_id).unwrap();
    assert_eq!(tv.state.lock().unwrap()["approval"], "declined");
    assert!(manager.approval_status(&a.request_id).is_err());
    let a = manager
        .request_resolved_approval(&tv.address, "Laptop")
        .unwrap();
    tv.state.lock().unwrap()["approval"] = json!("declined");
    assert_eq!(
        manager.approval_status(&a.request_id).unwrap().status,
        "declined"
    );
    let a = manager
        .request_resolved_approval(&tv.address, "Laptop")
        .unwrap();
    tv.state.lock().unwrap()["wrong_identity"] = json!(true);
    tv.state.lock().unwrap()["approval"] = json!("approved");
    assert!(manager.approval_status(&a.request_id).is_err());
    assert!(manager.list().is_empty());
    assert!(!dir.path().join("devices.json").exists());
}

#[test]
#[ignore = "opens multicast sockets on the host; run explicitly on a local network"]
fn discovery_finds_tls_device_without_an_address() {
    use mdns_sd::{ServiceDaemon, ServiceInfo};
    let tv = Fixture::bind("0.0.0.0:0");
    let port = tv
        .address
        .rsplit(':')
        .next()
        .unwrap()
        .parse::<u16>()
        .unwrap();
    let dir = tempfile::tempdir().unwrap();
    let manager = DeviceManager::open(dir.path()).unwrap();
    assert!(manager.nearby().unwrap().is_empty());
    let advertiser = ServiceDaemon::new().unwrap();
    let info = ServiceInfo::new(
        "_focus-tv._tcp.local.",
        &format!("Focus fixture {port}"),
        &format!("focus-fixture-{port}.local."),
        "",
        port,
        &[("pairing", "approval-v2")][..],
    )
    .unwrap()
    .enable_addr_auto();
    advertiser.register(info).unwrap();
    let deadline = std::time::Instant::now() + Duration::from_secs(15);
    let mut found = false;
    while std::time::Instant::now() < deadline {
        if manager
            .nearby()
            .unwrap()
            .iter()
            .any(|p| p.id == "tv-fixture" && p.name == "Fixture TV" && !p.paired)
        {
            found = true;
            break;
        }
        thread::sleep(Duration::from_millis(100));
    }
    let _ = advertiser.shutdown();
    assert!(
        found,
        "mDNS service must resolve through a TLS hello before appearing"
    );
    assert!(
        manager.list().is_empty(),
        "Discovery never pairs automatically"
    );
}
