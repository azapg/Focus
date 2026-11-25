use focus_browser_host::*;
use focus_telemetry as telemetry;
use std::{
    fs::{self, OpenOptions},
    io::{self, Write},
    path::{Path, PathBuf},
    thread,
    time::Duration,
};

#[derive(serde::Deserialize)]
struct SavedPause {
    paused_until: u64,
    #[serde(default)]
    pause_ended_at: Option<u64>,
    #[serde(default)]
    scope_revision: u64,
    #[serde(default)]
    profile: Option<String>,
}

#[derive(Default, PartialEq, serde::Serialize)]
struct Protection {
    paused_until: u64,
    ended_at: Option<u64>,
    scope_revision: u64,
    profile: Option<String>,
}

fn read_protection(path: &Path) -> Option<Protection> {
    let saved: SavedPause = serde_json::from_slice(&fs::read(path).ok()?).ok()?;
    Some(Protection {
        paused_until: saved.paused_until,
        ended_at: saved.pause_ended_at,
        scope_revision: saved.scope_revision,
        profile: saved.profile.as_deref().and_then(|profile| configured_nextdns_profile(path.parent()?, profile)),
    })
}

fn send(message: &serde_json::Value) -> io::Result<()> {
    // Keep the frame header and body under one lock, including unsolicited state updates.
    write_frame(&mut io::stdout().lock(), message)?;
    telemetry::HOST_MESSAGE_OUT.count(1);
    Ok(())
}

fn watch_protection(path: &Path, habit_feed_lock: &Path, browser_pid: u32) {
    let mut last = None;
    let mut last_lock = None;
    loop {
        telemetry::HOST_PROTECTION_TICK.count(1);
        let state = read_protection(path).unwrap_or_default();
        if Some(&state) != last.as_ref() {
            if send(&serde_json::json!({"type":"protection","version":1,"state":state})).is_err() {
                break;
            }
            last = Some(state);
        }
        let lock = fs::read(habit_feed_lock).ok()
            .and_then(|bytes| serde_json::from_slice::<FeedLock>(&bytes).ok())
            .filter(|lock| lock.browser_pid == browser_pid && lock.host_pid == std::process::id() && lock.until > now_ms());
        if last_lock.as_ref() != Some(&lock) {
            if send(&serde_json::json!({"type":"habits-feed-lock","lease":lock})).is_err() {
                break;
            }
            last_lock = Some(lock);
        }
        thread::sleep(Duration::from_millis(250));
    }
}

#[cfg(windows)]
fn parent_browser() -> Option<(u32, String)> {
    use windows_sys::Win32::{
        Foundation::{CloseHandle, INVALID_HANDLE_VALUE},
        System::Diagnostics::ToolHelp::*,
    };
    unsafe {
        let h = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if h == INVALID_HANDLE_VALUE {
            return None;
        }
        let mut entry: PROCESSENTRY32W = std::mem::zeroed();
        entry.dwSize = std::mem::size_of::<PROCESSENTRY32W>() as u32;
        let mut processes = std::collections::HashMap::new();
        let mut ok = Process32FirstW(h, &mut entry);
        while ok != 0 {
            let n = entry
                .szExeFile
                .iter()
                .position(|c| *c == 0)
                .unwrap_or(entry.szExeFile.len());
            processes.insert(
                entry.th32ProcessID,
                (
                    entry.th32ParentProcessID,
                    String::from_utf16_lossy(&entry.szExeFile[..n]),
                ),
            );
            ok = Process32NextW(h, &mut entry);
        }
        CloseHandle(h);
        let mut pid = std::process::id();
        for _ in 0..8 {
            let (parent, _) = processes.get(&pid)?;
            pid = *parent;
            let (_, exe) = processes.get(&pid)?;
            if browser(exe) {
                return Some((pid, exe.clone()));
            }
        }
        None
    }
}
#[cfg(not(windows))]
fn parent_browser() -> Option<(u32, String)> {
    let mut pid = std::process::id();
    for _ in 0..8 {
        let stat = fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
        pid = stat
            .rsplit_once(')')?
            .1
            .split_whitespace()
            .nth(1)?
            .parse()
            .ok()?;
        let exe = fs::read_link(format!("/proc/{pid}/exe"))
            .ok()?
            .file_name()?
            .to_string_lossy()
            .to_string();
        if browser(&exe) {
            return Some((pid, exe));
        }
    }
    None
}
fn run() -> Result<(), Box<dyn std::error::Error>> {
    if std::env::args().nth(1).as_deref() != Some(ORIGIN) {
        return Err("caller rejected".into());
    }
    let (pid, exe) = parent_browser().ok_or("browser parent not found")?;
    let dir = PathBuf::from(std::env::var_os("LOCALAPPDATA").ok_or("LOCALAPPDATA missing")?)
        .join("FocusDNS");
    let live = dir.join("browser/live");
    fs::create_dir_all(&live)?;
    let path = live.join(format!("{}.json", std::process::id()));
    let state_path = dir.join("state.json");
    let habit_feed_lock = dir.join("habit-feed-lock.json");
    thread::spawn(move || watch_protection(&state_path, &habit_feed_lock, pid));
    let result = ingest(&dir, &path, pid, exe, &mut io::stdin().lock(), send);
    let _ = fs::remove_file(path);
    result
}
fn ingest(
    dir: &Path,
    path: &Path,
    pid: u32,
    exe: String,
    input: &mut impl io::Read,
    mut acknowledge: impl FnMut(&serde_json::Value) -> io::Result<()>,
) -> Result<(), Box<dyn std::error::Error>> {
    let mut telemetry = telemetry::Sampler::new(dir, telemetry::Component::BrowserHost);
    let mut last_seq = 0;
    let mut previous: Option<Event> = None;
    while let Some(frame) = read_frame(input)? {
        #[cfg(feature = "peek")]
        if let Some(reply) = peek::native(&frame, dir) {
            acknowledge(&reply)?;
            return Ok(());
        }
        let now = now_ms();
        let e = validate(serde_json::from_slice::<Event>(&frame)?, now)?;
        if e.seq <= last_seq {
            return Err("replayed event".into());
        }
        last_seq = e.seq;
        telemetry::HOST_MESSAGE.count(1);
        telemetry::HOST_RX.count(frame.len() as u64);
        let snapshot = Snapshot {
            received_at: now,
            browser_pid: pid,
            browser_exe: exe.clone(),
            host_pid: std::process::id(),
            event: e.clone(),
        };
        // Every transition is durable, even if it falls between app samples. Heartbeats only refresh the live snapshot.
        if e.reason != "heartbeat" || previous.as_ref().is_none_or(|old| !old.same_context(&e)) {
            let day = chrono::Local::now().format("%Y-%m-%d").to_string();
            let events = dir.join("browser/events");
            fs::create_dir_all(&events)?;
            let mut bytes = serde_json::to_vec(&snapshot)?;
            bytes.push(b'\n');
            OpenOptions::new()
                .append(true)
                .create(true)
                .open(events.join(format!("{day}.jsonl")))?
                .write_all(&bytes)?;
            telemetry::HOST_WRITE.count(1);
            telemetry::HOST_WRITE_BYTES.count(bytes.len() as u64);
        }
        let live_bytes = serde_json::to_vec(&snapshot)?;
        atomic_write(path, &live_bytes)?;
        telemetry::HOST_WRITE.count(1);
        telemetry::HOST_WRITE_BYTES.count(live_bytes.len() as u64);
        previous = Some(e);
        let app_online = fs::read_to_string(dir.join("browser/app-seen"))
            .ok()
            .and_then(|s| s.parse::<u64>().ok())
            .is_some_and(|t| t <= now && now - t < 5000);
        acknowledge(
            &serde_json::json!({"ok":true,"seq":last_seq,"received_at":now,"app_online":app_online}),
        )?;
        telemetry.sample_if_due();
    }
    if last_seq > 0 {
        telemetry.finish();
    }
    Ok(())
}
#[cfg(feature = "peek")]
fn peek_worker(
    input: &mut impl io::Read,
    output: &mut impl io::Write,
) -> Result<(), Box<dyn std::error::Error>> {
    #[derive(serde::Deserialize)]
    #[serde(deny_unknown_fields)]
    struct Query {
        url: String,
        profile: String,
    }

    let frame = read_frame(input)?.ok_or("missing input")?;
    let query: Query = serde_json::from_slice(&frame)?;
    let reply = match peek::worker_post(&query.url, &query.profile, || {
        write_frame(output, &serde_json::json!({ "check_protection": true })).is_ok()
            && read_frame(input)
                .ok()
                .flatten()
                .and_then(|bytes| serde_json::from_slice::<bool>(&bytes).ok())
                .unwrap_or(false)
    }) {
        Ok(snapshot) => serde_json::json!({ "snapshot": snapshot }),
        Err(refusal) => serde_json::json!({ "refusal": refusal }),
    };
    write_frame(output, &reply)?;
    Ok(())
}

fn main() {
    // Do not print payloads/errors: stdout is the native protocol; URLs never enter logs.
    #[cfg(feature = "peek")]
    if std::env::args().nth(1).as_deref() == Some("--peek-worker") {
        if peek_worker(&mut io::stdin().lock(), &mut io::stdout().lock()).is_err() {
            std::process::exit(2);
        }
        return;
    }
    if run().is_err() {
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn event(seq: u64, enabled: bool) -> Event {
        serde_json::from_value(serde_json::json!({"version":1,"seq":seq,"at":now_ms(),"reason":"setting","focused":true,"idle":"active","enabled":enabled,"tab_id":1,"window_id":1,"url":"https://user:pass@example.org/page?token=secret","title":"Project","audible":false,"visibility":"page"})).unwrap()
    }
    #[cfg(feature = "peek")]
    #[test]
    fn stateless_peek_worker_requires_protection_and_refuses_feeds() {
        let cases = [(false, "protection_off"), (true, "permalink")];
        for (protected, refusal) in cases {
            let mut input = Vec::new();
            write_frame(
                &mut input,
                &serde_json::json!({"url":"https://x.com/explore", "profile":"a1b2c3"}),
            )
            .unwrap();
            write_frame(&mut input, &protected).unwrap();
            let mut output = Vec::new();
            peek_worker(&mut &input[..], &mut output).unwrap();
            let mut output = &output[..];
            let next = |out: &mut &[u8]| -> serde_json::Value {
                serde_json::from_slice(&read_frame(out).unwrap().unwrap()).unwrap()
            };
            assert_eq!(
                next(&mut output),
                serde_json::json!({"check_protection":true})
            );
            assert_eq!(next(&mut output)["refusal"], refusal);
            assert!(output.is_empty());
        }
    }

    #[test]
    fn protection_only_publishes_a_profile_matching_local_configuration() {
        let dir = std::env::temp_dir().join(format!("focus-profile-test-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let state = dir.join("state.json");
        fs::write(&state, r#"{"paused_until":50,"profile":"A1B2C3"}"#).unwrap();
        assert!(read_protection(&state).unwrap().profile.is_none());

        let config = dir.join("nextdns.json");
        let write_config = |profile| {
            fs::write(&config, serde_json::json!({
                "profile": profile, "legacyConfirmed": false,
                "servers": ["192.0.2.10", "198.51.100.20", "2001:db8::10", "2001:db8::20"]
            }).to_string()).unwrap();
        };
        write_config("d4e5f6");
        assert!(read_protection(&state).unwrap().profile.is_none());
        write_config("a1b2c3");
        let protection = read_protection(&state).unwrap();
        assert_eq!(protection.profile.as_deref(), Some("a1b2c3"));
        assert_eq!(protection.paused_until, 50);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn native_transport_persists_acknowledged_transitions_and_pause_tombstone() {
        let dir = std::env::temp_dir().join(format!("focus-native-test-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("live.json");
        let mut input = vec![];
        write_frame(&mut input, &event(1, true)).unwrap();
        write_frame(&mut input, &event(2, false)).unwrap();
        let mut output = vec![];
        ingest(
            &dir,
            &path,
            123,
            "chrome.exe".into(),
            &mut &input[..],
            |message| write_frame(&mut output, message),
        )
        .unwrap();
        let live: Snapshot = serde_json::from_slice(&fs::read(path).unwrap()).unwrap();
        assert_eq!(live.browser_pid, 123);
        assert!(!live.event.enabled);
        assert!(live.event.url.is_none());
        assert!(live.event.title.is_none());
        let journal = fs::read_to_string(
            fs::read_dir(dir.join("browser/events"))
                .unwrap()
                .next()
                .unwrap()
                .unwrap()
                .path(),
        )
        .unwrap();
        assert_eq!(journal.lines().count(), 2);
        assert!(!journal.contains("pass@"));
        assert!(!journal.contains("secret"));
        let mut reader = &output[..];
        for seq in [1, 2] {
            let ack: serde_json::Value =
                serde_json::from_slice(&read_frame(&mut reader).unwrap().unwrap()).unwrap();
            assert_eq!(ack["seq"], seq);
            assert_eq!(ack["ok"], true);
        }
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn duplicate_sequence_is_rejected_without_second_ack() {
        let dir = std::env::temp_dir().join(format!("focus-replay-test-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let mut input = vec![];
        for _ in 0..2 {
            write_frame(&mut input, &event(1, true)).unwrap();
        }
        let mut output = vec![];
        assert!(ingest(
            &dir,
            &dir.join("live.json"),
            123,
            "chrome.exe".into(),
            &mut &input[..],
            |message| write_frame(&mut output, message)
        )
        .is_err());
        let mut reader = &output[..];
        assert!(read_frame(&mut reader).unwrap().is_some());
        assert!(read_frame(&mut reader).unwrap().is_none());
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn heartbeat_recovery_persists_changed_identity_without_journaling_steady_ticks() {
        let dir = std::env::temp_dir().join(format!("focus-heartbeat-test-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let mut input = vec![];
        let mut first = event(1, true);
        first.reason = "heartbeat".into();
        write_frame(&mut input, &first).unwrap();
        let mut second = first.clone();
        second.seq = 2;
        second.url = Some("https://example.org/recovered".into());
        write_frame(&mut input, &second).unwrap();
        let mut third = second;
        third.seq = 3;
        write_frame(&mut input, &third).unwrap();
        ingest(
            &dir,
            &dir.join("live.json"),
            123,
            "chrome.exe".into(),
            &mut &input[..],
            |_| Ok(()),
        )
        .unwrap();
        let text = fs::read_to_string(
            fs::read_dir(dir.join("browser/events"))
                .unwrap()
                .next()
                .unwrap()
                .unwrap()
                .path(),
        )
        .unwrap();
        assert_eq!(text.lines().count(), 2);
        assert!(text.contains("/recovered"));
        fs::remove_dir_all(dir).unwrap();
    }
}
