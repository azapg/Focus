//! Windows protocol probe: named chrome.exe solely to exercise browser ancestry
//! through real OS process and stdio APIs. This is NOT a real browser test.
use focus_browser_host::*;
use std::{
    fs,
    io::Write,
    process::{Command, Stdio},
};
fn main() {
    let host = std::env::args().nth(1).expect("host executable path");
    let dir = std::env::temp_dir().join(format!("focus-browser-probe-{}", std::process::id()));
    fs::create_dir_all(&dir).unwrap();
    let mut child = Command::new(&host)
        .arg(ORIGIN)
        .env("LOCALAPPDATA", &dir)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    let mut input = child.stdin.take().unwrap();
    let mut output = child.stdout.take().unwrap();
    for (seq, enabled) in [(1, true), (2, false)] {
        let e = serde_json::json!({"version":1,"seq":seq,"at":now_ms(),"reason":"setting","focused":true,"idle":"active","enabled":enabled,"tab_id":1,"window_id":1,"url":"https://user:pass@probe.example/project?token=synthetic-secret&tab=readme","title":"Synthetic probe page","audible":false,"visibility":"page"});
        write_frame(&mut input, &e).unwrap();
        let ack: serde_json::Value =
            serde_json::from_slice(&read_frame(&mut output).unwrap().unwrap()).unwrap();
        assert_eq!(ack["seq"], seq);
        assert_eq!(ack["ok"], true);
    }
    let live_dir = dir.join("FocusDNS/browser/live");
    let live: Snapshot = serde_json::from_slice(
        &fs::read(
            fs::read_dir(&live_dir)
                .unwrap()
                .next()
                .unwrap()
                .unwrap()
                .path(),
        )
        .unwrap(),
    )
    .unwrap();
    assert_eq!(live.browser_pid, std::process::id());
    assert!(live.event.url.is_none());
    assert!(live.event.title.is_none());
    drop(input);
    assert!(child.wait().unwrap().success());
    assert_eq!(fs::read_dir(&live_dir).unwrap().count(), 0);
    let event_dir = dir.join("FocusDNS/browser/events");
    let text = fs::read_to_string(
        fs::read_dir(event_dir)
            .unwrap()
            .next()
            .unwrap()
            .unwrap()
            .path(),
    )
    .unwrap();
    assert_eq!(text.lines().count(), 2);
    assert!(!text.contains("synthetic-secret"));
    assert!(!text.contains("pass@"));
    let first: Snapshot = serde_json::from_str(text.lines().next().unwrap()).unwrap();
    assert_eq!(
        first.event.url.as_deref(),
        Some("https://probe.example/project?token=REDACTED&tab=readme")
    );
    let rejected = Command::new(&host)
        .arg("chrome-extension://unapproved/")
        .env("LOCALAPPDATA", &dir)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .status()
        .unwrap();
    assert!(!rejected.success());
    let mut bad = Command::new(&host)
        .arg(ORIGIN)
        .env("LOCALAPPDATA", &dir)
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .spawn()
        .unwrap();
    bad.stdin
        .take()
        .unwrap()
        .write_all(&u32::MAX.to_le_bytes())
        .unwrap();
    assert!(!bad.wait().unwrap().success());
    fs::remove_dir_all(&dir).unwrap();
    println!("PASS: Windows native executable, OS ancestry, framed stdio, persisted URL/title, redaction, pause tombstone, clean disconnect, caller rejection, oversize rejection. Synthetic browser-parent probe; no real browser registration or DNS changes.");
}
