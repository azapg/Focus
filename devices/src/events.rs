//! Local failure records and the allowlist for events imported from paired phones.
use serde_json::{json, Value};
use std::{
    collections::HashSet,
    fs::{self, OpenOptions},
    io::{self, Write},
    path::{Path, PathBuf},
    sync::Mutex,
    time::{Instant, SystemTime, UNIX_EPOCH},
};

const DAY_MS: u64 = 86_400_000;
const RETENTION: u64 = 14 * DAY_MS;
const MAX_BYTES: usize = 512 * 1024;
const PAGE_ROWS: usize = 32;
const MAX_PHONE_FILES: usize = 16;
static WRITES: Mutex<()> = Mutex::new(());

const EVENTS: &str = "ask pause update lan nextdns dns intention";
const STAGES: &str = "start laptop laptop_abandoned gateway eligibility decision apply reconcile cleanup check download verify install bind firewall network connect tls auth request reachability address pause resume set edit extend end outcome nag_shown parse_fallback";
const OUTCOMES: &str = "started ok failed skipped blocked";
pub const CAUSES: &str = "laptop_unreachable laptop_timeout laptop_tls_mismatch laptop_unauthorized laptop_judge_disabled laptop_unsupported laptop_rejected laptop_invalid_reply gateway_no_key gateway_keystore gateway_http_4xx gateway_http_5xx gateway_cancelled gateway_transport gateway_timeout gateway_unreachable gateway_invalid_reply gateway_refused scope_key_missing scope_profile_mismatch dns_disabled dns_paused private_dns_inactive phone_pause_unavailable cap_exceeded decision_expired apply_timeout nextdns_auth nextdns_rejected nextdns_unreachable ok apk_too_large update_auth update_http update_no_asset update_invalid_manifest update_download update_verify update_no_space update_install update_cancelled lan_unreachable lan_timeout lan_tls_mismatch lan_unauthorized lan_rejected lan_invalid_reply lan_bind_failed firewall_failed dns_apply_failed no_laptop_answer";

fn one_of(value: &Value, choices: &str) -> bool {
    value.as_str().is_some_and(|text| choices.split_ascii_whitespace().any(|choice| choice == text))
}

fn lower_hex(byte: u8) -> bool {
    byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)
}

pub fn valid_id(text: &str) -> bool {
    let bytes = text.as_bytes();
    bytes.len() == 36 && bytes[14] == b'4' && b"89ab".contains(&bytes[19])
        && bytes.iter().enumerate().all(|(i, &byte)| {
            if matches!(i, 8 | 13 | 18 | 23) { byte == b'-' } else { lower_hex(byte) }
        })
}

pub fn new_id() -> Option<String> {
    let mut bytes = [0; 16];
    getrandom::getrandom(&mut bytes).ok()?;
    bytes[6] = bytes[6] & 0x0f | 0x40;
    bytes[8] = bytes[8] & 0x3f | 0x80;
    let hex: String = bytes.iter().map(|byte| format!("{byte:02x}")).collect();
    Some(format!("{}-{}-{}-{}-{}", &hex[..8], &hex[8..12], &hex[12..16], &hex[16..20], &hex[20..]))
}

fn valid_build(text: &str) -> bool {
    matches!(text, "dev" | "local" | "unknown")
        || ((7..=40).contains(&text.len()) && text.bytes().all(lower_hex))
}

fn allowed_field(key: &str, value: &Value) -> bool {
    match key {
        "expected_bytes" | "actual_bytes" | "limit_bytes" | "http_status" | "status_class"
        | "attempt" | "port" | "service_count" | "planned_minutes" | "active_minutes"
        | "extensions" => value.as_u64().is_some_and(|n| n <= i64::MAX as u64),
        "install_status" => value.as_i64().is_some(),
        "vpn_default" | "strict_mode" | "private_dns_active" | "scope_available" | "dns_enabled"
        | "dns_paused" | "authenticated" | "manual_override" => value.is_boolean(),
        "peer_id" => value.as_str().is_some_and(valid_id),
        "from_build" | "to_build" => value.as_str().is_some_and(valid_build),
        "origin" => one_of(value, "phone laptop"),
        "route" => one_of(value, "laptop gateway local"),
        "direction" => one_of(value, "phone_to_laptop laptop_to_phone"),
        "decision_action" => one_of(value, "allow_services full_pause counter_offer deny fallback peek"),
        "failure_detail" => one_of(value, "timeout transport http envelope finish_reason refusal not_text parse minutes_mismatch counter_offer_mismatch cancelled"),
        "firewall_profiles" => one_of(value, "domain_private"),
        "network_category" => one_of(value, "public private domain mixed unknown"),
        "intention_outcome" => one_of(value, "done partly no cleared replaced"),
        "parse_source" => one_of(value, "gateway local"),
        _ => false,
    }
}

fn valid_row(row: &Value, device: &str) -> bool {
    row.as_object().is_some_and(|o| o.len() == 12)
        && row["v"] == 1
        && row["device"] == device
        && row["at_ms"].as_u64().is_some()
        && row["duration_ms"].as_u64().is_some()
        && row["event_id"].as_str().is_some_and(valid_id)
        && row["correlation_id"].as_str().is_some_and(valid_id)
        && row["build_id"].as_str().is_some_and(valid_build)
        && one_of(&row["event"], EVENTS)
        && one_of(&row["stage"], STAGES)
        && one_of(&row["outcome"], OUTCOMES)
        && one_of(&row["cause"], CAUSES)
        && row["fields"].as_object().is_some_and(|o| o.iter().all(|(k, v)| allowed_field(k, v)))
}

fn millis(time: SystemTime) -> u64 {
    time.duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

pub fn elapsed(start: Instant) -> u64 {
    start.elapsed().as_millis() as u64
}

pub fn status_cause(status: u16) -> &'static str {
    match status {
        200..=299 => "ok",
        401 => "lan_unauthorized",
        _ => "lan_rejected",
    }
}

pub fn io_cause(error: &io::Error, fallback: &'static str) -> &'static str {
    match error.kind() {
        io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock => "lan_timeout",
        _ => fallback,
    }
}

#[derive(Clone)]
pub struct EventLog {
    dir: PathBuf,
    build: &'static str,
}

impl EventLog {
    pub fn new(root: &Path, build: &'static str) -> Self {
        Self { dir: root.join("events"), build: if valid_build(build) { build } else { "unknown" } }
    }

    pub fn record(&self, event: &str, stage: &str, outcome: &str, cause: &str, duration: u64, mut fields: Value) {
        let Some(id) = new_id() else { return };
        let Some(fields) = fields.as_object_mut() else { return };
        let correlation = fields.remove("correlation_id").unwrap_or_else(|| json!(id));
        let at = millis(SystemTime::now());
        let row = json!({
            "v": 1, "at_ms": at, "event_id": id, "build_id": self.build, "device": "laptop",
            "correlation_id": correlation, "event": event, "stage": stage, "outcome": outcome,
            "cause": cause, "duration_ms": duration, "fields": fields,
        });
        if valid_row(&row, "laptop") {
            let _guard = WRITES.lock().unwrap_or_else(|e| e.into_inner());
            let _ = self.append_local(at, &row);
        }
    }

    fn append_local(&self, at: u64, row: &Value) -> io::Result<()> {
        fs::create_dir_all(&self.dir)?;
        let path = self.dir.join("laptop.jsonl");
        let line = format!("{row}\n");
        let metadata = fs::metadata(&path).ok();
        if metadata.is_some_and(|m| {
            m.len() + line.len() as u64 + 1 > MAX_BYTES as u64
                || m.modified().is_ok_and(|time| millis(time) / DAY_MS != at / DAY_MS)
        }) {
            let mut rows = read_rows(&path, "laptop", at)?;
            rows.push(row.clone());
            return compact(&path, &rows);
        }
        // Separate any torn final line from the next record.
        let mut file = OpenOptions::new().create(true).append(true).open(path)?;
        file.write_all(format!("\n{line}").as_bytes())
    }

    fn phone_path(&self, peer: &str) -> PathBuf {
        // The filename uses a digest of the existing peer ID, never the peer address or pin.
        self.dir.join(format!("phone-{}.jsonl", crate::transport::fingerprint(peer.as_bytes())))
    }

    pub fn maintain(&self) -> io::Result<()> {
        let _guard = WRITES.lock().unwrap_or_else(|e| e.into_inner());
        let at = millis(SystemTime::now());
        let entries = match fs::read_dir(&self.dir) {
            Ok(entries) => entries,
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
            Err(error) => return Err(error),
        };
        for entry in entries {
            let entry = entry?;
            let name = entry.file_name();
            let device = match name.to_str() {
                Some("laptop.jsonl") => "laptop",
                Some(name) if phone_filename(name) => "phone",
                _ => continue,
            };
            let path = entry.path();
            if !past_day(&path, at) { continue }
            let rows = read_rows(&path, device, at)?;
            if rows.is_empty() {
                fs::remove_file(&path)?;
            } else {
                compact(&path, &rows)?;
            }
        }
        Ok(())
    }

    pub(crate) fn import_phone(&self, peer: &str, page: &Value) -> io::Result<Option<String>> {
        let Some(page) = page.as_array().filter(|rows| rows.len() <= PAGE_ROWS) else { return Ok(None) };
        // Progress is independent of event retention and allows future versions to add codes.
        // Save it in the registry only after the accepted rows have been written.
        let cursor = page.iter().rev().filter_map(|row| row["event_id"].as_str())
            .find(|id| valid_id(id)).map(str::to_owned);
        let at = millis(SystemTime::now());
        let _guard = WRITES.lock().unwrap_or_else(|e| e.into_inner());
        let path = self.phone_path(peer);
        if page.is_empty() && (!path.exists() || !past_day(&path, at)) {
            return Ok(None);
        }
        let mut rows = read_rows(&path, "phone", at)?;
        let count = rows.len();
        let mut known: HashSet<_> = rows.iter().map(|r| r["event_id"].as_str().unwrap().to_owned()).collect();
        rows.extend(page.iter().filter(|row| {
            valid_row(row, "phone") && recent(row, at)
                && known.insert(row["event_id"].as_str().unwrap().to_owned())
        }).map(|row| {
            let mut row = row.clone();
            // Attribution comes from the pinned paired connection, not the phone payload.
            if valid_id(peer) { row["fields"]["peer_id"] = json!(peer); }
            row
        }));
        if count == rows.len() && !past_day(&path, at) { return Ok(cursor) }
        fs::create_dir_all(&self.dir)?;
        self.prune_phones(&path, at)?;
        if rows.is_empty() {
            if path.exists() { fs::remove_file(&path)?; }
        } else {
            compact(&path, &rows)?;
        }
        Ok(cursor)
    }

    fn prune_phones(&self, current: &Path, at: u64) -> io::Result<()> {
        let mut others = Vec::new();
        for entry in fs::read_dir(&self.dir)? {
            let entry = entry?;
            let path = entry.path();
            let name = entry.file_name();
            if path == current || !name.to_str().is_some_and(phone_filename) { continue }
            let modified = millis(entry.metadata()?.modified()?);
            if modified < at.saturating_sub(RETENTION) {
                fs::remove_file(path)?;
            } else {
                others.push((modified, path));
            }
        }
        others.sort_unstable();
        let excess = (others.len() + 1).saturating_sub(MAX_PHONE_FILES);
        for (_, path) in others.into_iter().take(excess) {
            fs::remove_file(path)?;
        }
        Ok(())
    }
}

fn phone_filename(name: &str) -> bool {
    name.strip_prefix("phone-").and_then(|n| n.strip_suffix(".jsonl"))
        .is_some_and(|digest| digest.len() == 64 && digest.bytes().all(lower_hex))
}

fn past_day(path: &Path, at: u64) -> bool {
    fs::metadata(path).and_then(|m| m.modified())
        .is_ok_and(|time| millis(time) / DAY_MS != at / DAY_MS)
}

fn recent(row: &Value, at: u64) -> bool {
    row["at_ms"].as_u64().is_some_and(|stamp| (at.saturating_sub(RETENTION)..=at.saturating_add(DAY_MS)).contains(&stamp))
}

fn read_rows(path: &Path, device: &str, at: u64) -> io::Result<Vec<Value>> {
    let bytes = match fs::read(path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(error),
    };
    Ok(bytes.split(|&byte| byte == b'\n')
        .filter_map(|line| serde_json::from_slice(line).ok())
        .filter(|row| valid_row(row, device) && recent(row, at))
        .collect())
}

fn compact(path: &Path, rows: &[Value]) -> io::Result<()> {
    let mut lines = Vec::new();
    let mut bytes = 0;
    for row in rows.iter().rev() {
        let line = format!("{row}\n");
        bytes += line.len();
        if bytes > MAX_BYTES / 2 { break }
        lines.push(line);
    }
    let mut output = Vec::new();
    for line in lines.into_iter().rev() {
        output.extend_from_slice(line.as_bytes());
    }
    focus_browser_host::durable_atomic_write(path, &output)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn phone_row() -> Value {
        json!({
            "v": 1, "at_ms": millis(SystemTime::now()), "event_id": new_id().unwrap(),
            "build_id": "abcdef1", "device": "phone", "correlation_id": new_id().unwrap(),
            "event": "ask", "stage": "eligibility", "outcome": "blocked", "cause": "dns_disabled",
            "duration_ms": 12, "fields": {"origin": "phone", "dns_enabled": false},
        })
    }

    #[test]
    fn imported_rows_are_validated_deduplicated_and_age_limited() {
        let dir = tempfile::tempdir().unwrap();
        let log = EventLog::new(dir.path(), "abcdef0");
        let mut row = phone_row();
        row["fields"]["decision_action"] = json!("full_pause");
        row["fields"]["manual_override"] = json!(true);
        let mut rejected = vec![];
        for field in ["request", "reason", "url", "hostname", "title", "app_name", "key", "token", "fingerprint"] {
            let mut bad = phone_row();
            bad["fields"][field] = json!("private content");
            rejected.push(bad);
        }
        let mut custom_build = phone_row();
        custom_build["build_id"] = json!("private.example");
        rejected.push(custom_build);
        let mut custom_route = phone_row();
        custom_route["fields"]["route"] = json!("private route");
        rejected.push(custom_route);
        let mut custom_action = phone_row();
        custom_action["fields"]["decision_action"] = json!("private action");
        rejected.push(custom_action);
        let mut custom_override = phone_row();
        custom_override["fields"]["manual_override"] = json!("private override");
        rejected.push(custom_override);
        let mut stale = phone_row();
        stale["at_ms"] = json!(millis(SystemTime::now()) - RETENTION - 1);
        rejected.push(stale);

        let page = json!([row.clone()]);
        log.import_phone("test-peer", &page).unwrap();
        log.import_phone("test-peer", &json!(rejected)).unwrap();
        assert_eq!(log.import_phone("test-peer", &page).unwrap().as_deref(), row["event_id"].as_str());
        let persisted = fs::read_to_string(log.phone_path("test-peer")).unwrap();
        assert_eq!(persisted.lines().count(), 1);
        assert!(persisted.contains("\"decision_action\":\"full_pause\""));
        assert!(persisted.contains("\"manual_override\":true"));
        assert!(!persisted.contains("private"));
    }

    #[test]
    fn append_recovers_from_torn_tail_and_rejects_unknown_local_fields() {
        let dir = tempfile::tempdir().unwrap();
        let log = EventLog::new(dir.path(), "abcdef0");
        log.record("lan", "bind", "ok", "ok", 0, json!({"port": 47833}));
        let path = log.dir.join("laptop.jsonl");
        OpenOptions::new().append(true).open(&path).unwrap().write_all(b"{\"partial\":").unwrap();
        log.record("lan", "bind", "ok", "ok", 0, json!({"port": 47834}));
        log.record("ask", "start", "ok", "ok", 0, json!({"url": "private.example"}));
        let rows = read_rows(&path, "laptop", millis(SystemTime::now())).unwrap();
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[1]["fields"]["port"], 47834);
    }
}
