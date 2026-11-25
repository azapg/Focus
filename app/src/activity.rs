//! Screen time. Every few seconds: which app is in front (and which site, for browsers),
//! and are you actually there? No mouse/keyboard for a while, or Windows locked = away,
//! which is not counted. Stored as small "spans" in %LOCALAPPDATA%\FocusDNS\activity\<day>.jsonl.
//! Extension context enriches this single stream; title-based labels remain a fallback.

use focus_telemetry as telemetry;
use chrono::{Local, NaiveDate, TimeZone};
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::Mutex,
    thread,
    time::Duration,
};

const TICK_MS: u64 = 1_000;
const IDLE_MS: u64 = 2 * 60_000;
/// Watching a video means no input for a long time; don't call that "away" too early.
const VIDEO_IDLE_MS: u64 = 30 * 60_000;
const VIDEO: [&str; 6] = ["YouTube", "Netflix", "Twitch", "Prime Video", "Disney+", "VLC"];
const BROWSERS: [&str; 9] = ["chrome", "msedge", "firefox", "brave", "opera", "vivaldi", "arc", "zen", "thorium"];

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Span {
    pub s: u64,
    pub e: u64,
    pub app: String,
    pub label: String,
    #[serde(default = "window_source")]
    pub source: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub browser: Option<BrowserContext>,
}
fn window_source() -> String { "window_title".into() }

/// Observed browser metadata; audible is a tab flag, not proof of watching.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct BrowserContext {
    pub url: Option<String>,
    pub title: Option<String>,
    pub visibility: String,
    pub audible: bool,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub habit_feed: bool,
    pub tab_id: Option<i64>,
    pub window_id: Option<i64>,
    pub browser_pid: u32,
    pub host_pid: u32,
}

pub struct Tracker {
    dir: PathBuf,
    cur: Mutex<Option<Span>>,
    data_dir: PathBuf,
    started_at: u64,
}

fn day_of(ms: u64) -> NaiveDate {
    Local.timestamp_millis_opt(ms as i64).unwrap().date_naive()
}

enum Sample {
    Active(String, String, Option<BrowserContext>),
    /// Away. If it's because of no input, when the last input happened.
    Away(Option<u64>),
}

impl Tracker {
    pub fn new(data_dir: &Path) -> Self {
        let dir = data_dir.join("activity");
        let _ = fs::create_dir_all(&dir);
        Tracker { dir, cur: Mutex::new(None), data_dir: data_dir.to_owned(), started_at: chrono::Utc::now().timestamp_millis() as u64 }
    }

    pub fn evidence_start(&self) -> u64 { self.started_at }

    fn flush(&self, sp: &Span) {
        if sp.e <= sp.s {
            return;
        }
        let path = self.dir.join(format!("{}.jsonl", day_of(sp.s)));
        if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(path) {
            let row = serde_json::to_string(sp).unwrap();
            if writeln!(f, "{row}").is_ok() {
                telemetry::ACTIVITY_WRITE.count(1);
                telemetry::ACTIVITY_WRITE_BYTES.count(row.len() as u64 + 1);
            }
        }
    }

    /// All spans that touch [from, to], including the one being recorded right now.
    pub fn spans(&self, from: u64, to: u64) -> Vec<Span> {
        let mut out = Vec::new();
        let (mut d, last) = (day_of(from), day_of(to));
        while d <= last {
            if let Ok(text) = fs::read_to_string(self.dir.join(format!("{d}.jsonl"))) {
                out.extend(
                    text.lines()
                        .filter_map(|l| serde_json::from_str::<Span>(l).ok())
                        .filter(|s| s.e > from && s.s < to),
                );
            }
            d = d.succ_opt().unwrap();
        }
        if let Some(c) = self.cur.lock().unwrap().clone() {
            if c.e > from && c.s < to {
                out.push(c);
            }
        }
        out
    }

    pub fn run(&self, now_ms: fn() -> u64, mut observe: impl FnMut(u64, Option<Span>, Option<Span>, Option<u64>)) {
        let live = self.data_dir.join("browser");
        let _ = fs::create_dir_all(&live);
        let mut resources = telemetry::Sampler::new(&self.data_dir, telemetry::Component::Desktop);
        loop {
            thread::sleep(Duration::from_millis(TICK_MS));
            telemetry::ACTIVITY_TICK.count(1);
            let now = now_ms();
            // Liveness only: no page metadata or commands cross this file.
            let seen = now.to_string();
            if focus_browser_host::atomic_write(&live.join("app-seen"), seen.as_bytes()).is_ok() {
                telemetry::ACTIVITY_WRITE.count(1);
                telemetry::ACTIVITY_WRITE_BYTES.count(seen.len() as u64);
            }
            let (sample, idle_ms) = sample(now, &self.data_dir);
            let closed = self.record(now, sample);
            let current = self.cur.lock().unwrap().clone();
            observe(now, closed, current, idle_ms);
            resources.sample_if_due();
        }
    }

    fn record(&self, now: u64, sample: Sample) -> Option<Span> {
        let mut cur = self.cur.lock().unwrap();
        match sample {
            Sample::Active(app, label, browser) => {
                let same = cur.as_ref().is_some_and(|c| {
                    c.app == app && c.label == label && c.browser == browser && now.saturating_sub(c.e) < 4 * TICK_MS
                        && now.saturating_sub(c.s) < 60_000 && day_of(c.s) == day_of(now)
                });
                if same {
                    cur.as_mut().unwrap().e = now;
                    None
                } else {
                    let closed = cur.take();
                    let start = closed.as_ref().map_or(now, |c| {
                        self.flush(c);
                        if now.saturating_sub(c.e) < 4 * TICK_MS && day_of(c.e) == day_of(now) { c.e } else { now }
                    });
                    let source = if browser.is_some() { "chromium_extension" } else { "window_title" }.into();
                    *cur = Some(Span { s: start, e: now, app, label, source, browser });
                    closed
                }
            }
            Sample::Away(last_input) => {
                cur.take().map(|mut c| {
                    if let Some(t) = last_input { c.e = c.e.min(t).max(c.s); }
                    self.flush(&c);
                    c
                })
            }
        }
    }

}

#[cfg(windows)]
fn sample(now: u64, data_dir: &Path) -> (Sample, Option<u64>) {
    use windows_sys::Win32::{
        System::SystemInformation::GetTickCount,
        UI::{
            Input::KeyboardAndMouse::{GetLastInputInfo, LASTINPUTINFO},
            WindowsAndMessaging::{GetForegroundWindow, GetWindowTextW, GetWindowThreadProcessId},
        },
    };
    unsafe {
        let hwnd = GetForegroundWindow();
        if hwnd.is_null() {
            return (Sample::Away(None), None);
        }
        let mut pid = 0u32;
        GetWindowThreadProcessId(hwnd, &mut pid);
        let exe = exe_name(pid).unwrap_or_default();
        if exe.eq_ignore_ascii_case("LockApp.exe") {
            return (Sample::Away(None), None); // the Windows lock screen
        }
        let mut buf = [0u16; 512];
        let n = GetWindowTextW(hwnd, buf.as_mut_ptr(), buf.len() as i32).max(0) as usize;
        let (app, label) = classify(&exe, &String::from_utf16_lossy(&buf[..n]));
        let browser = focus_browser_host::latest(data_dir, pid, &exe, now);

        let mut lii = LASTINPUTINFO { cbSize: std::mem::size_of::<LASTINPUTINFO>() as u32, dwTime: 0 };
        let idle_ms = (GetLastInputInfo(&mut lii) != 0)
            .then(|| GetTickCount().wrapping_sub(lii.dwTime) as u64);
        if let Some(idle) = idle_ms {
            // Extension-backed Chromium uses actual idle state and the normal
            // Windows limit. Do not infer watching from a page title or audible tab.
            let limit = if browser.is_none() && VIDEO.contains(&label.as_str()) { VIDEO_IDLE_MS } else { IDLE_MS };
            if idle > limit {
                return (Sample::Away(Some(now.saturating_sub(idle))), idle_ms);
            }
        }
        (enrich(app, label, browser), idle_ms)
    }
}

/// OS foreground process is authoritative. Extension metadata refines only that
/// process; it never creates a second independent timer.
fn enrich(app: String, fallback: String, snapshot: Option<focus_browser_host::Snapshot>) -> Sample {
    let Some(s) = snapshot else { return Sample::Active(app, fallback, None) };
    if !s.event.enabled || !s.event.focused { return Sample::Active(app.clone(), app, None); }
    if !s.counting() { return Sample::Away(None); }
    let e = s.event;
    let label = e.url.as_deref().and_then(|u| url_host(u)).unwrap_or_else(|| app.clone());
    Sample::Active(app, label, Some(BrowserContext {
        url: e.url, title: e.title, visibility: e.visibility, audible: e.audible,
        habit_feed: e.habit_feeds && e.habit_feed,
        tab_id: e.tab_id, window_id: e.window_id, browser_pid: s.browser_pid, host_pid: s.host_pid,
    }))
}
fn url_host(raw: &str) -> Option<String> { focus_browser_host::url_host(raw) }

#[cfg(not(windows))]
fn sample(_now: u64, _data_dir: &Path) -> (Sample, Option<u64>) { (Sample::Away(None), None) }

#[cfg(windows)]
fn exe_name(pid: u32) -> Option<String> {
    use windows_sys::Win32::{
        Foundation::CloseHandle,
        System::Threading::{OpenProcess, QueryFullProcessImageNameW, PROCESS_QUERY_LIMITED_INFORMATION},
    };
    unsafe {
        let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if h.is_null() {
            return None;
        }
        let mut buf = [0u16; 1024];
        let mut len = buf.len() as u32;
        let ok = QueryFullProcessImageNameW(h, 0, buf.as_mut_ptr(), &mut len) != 0;
        CloseHandle(h);
        let path = String::from_utf16_lossy(&buf[..len as usize]);
        ok.then(|| path.rsplit('\\').next().unwrap_or("").to_string())
    }
}

/// exe + window title -> (app name, label shown in stats). For browsers the label is the site.
pub fn classify(exe: &str, title: &str) -> (String, String) {
    let stem = exe.strip_suffix(".exe").or_else(|| exe.strip_suffix(".EXE")).unwrap_or(exe);
    let lower = stem.to_lowercase();
    let app = match lower.as_str() {
        "code" => "VS Code",
        "chrome" => "Chrome",
        "msedge" => "Edge",
        "firefox" => "Firefox",
        "brave" => "Brave",
        "windowsterminal" | "wt" => "Terminal",
        "explorer" => "Explorer",
        l if l.starts_with("t3 code") || l.starts_with("t3code") => "T3 Code",
        "focus-dns" | "focusdns" => "FocusDNS",
        "slack" => "Slack",
        "discord" => "Discord",
        "spotify" => "Spotify",
        "whatsapp" | "whatsapp.root" => "WhatsApp",
        "" => "Unknown",
        _ => stem,
    }
    .to_string();
    let label = if BROWSERS.contains(&lower.as_str()) { site(title).unwrap_or_else(|| app.clone()) } else { app.clone() };
    (app, label)
}

fn site(title: &str) -> Option<String> {
    let t = title.trim();
    let l = t.to_lowercase();
    const KNOWN: [(&str, &str); 22] = [
        ("youtube", "YouTube"), ("instagram", "Instagram"), ("facebook", "Facebook"), ("reddit", "Reddit"),
        ("tiktok", "TikTok"), ("linkedin", "LinkedIn"), ("github", "GitHub"), ("netflix", "Netflix"),
        ("twitch", "Twitch"), ("whatsapp", "WhatsApp"), ("gmail", "Gmail"), ("chatgpt", "ChatGPT"),
        ("claude", "Claude"), ("google docs", "Google Docs"), ("google calendar", "Google Calendar"),
        ("notion", "Notion"), ("discord", "Discord"), ("prime video", "Prime Video"), ("disney+", "Disney+"),
        ("hacker news", "Hacker News"), ("threads", "Threads"), ("bluesky", "Bluesky"),
    ];
    if l.contains(" / x - ") || l.contains(" / x —") || l.contains("x.com") || l.contains(" on x: ") {
        return Some("X".into());
    }
    if let Some((_, name)) = KNOWN.iter().find(|(k, _)| l.contains(k)) {
        return Some(name.to_string());
    }
    // Otherwise "<page> - <site> - <browser>": take the part just before the browser name.
    // With only "<page> - <browser>" that part is the page title, which we never store.
    let norm = t.replace('\u{200b}', "");
    let parts: Vec<&str> = norm
        .split(" - ")
        .flat_map(|p| p.split(" — "))
        .map(str::trim)
        .filter(|p| !p.is_empty() && *p != "Personal" && *p != "Work" && !p.starts_with("Profile "))
        .collect();
    if parts.len() >= 3 {
        return Some(parts[parts.len() - 2].chars().take(30).collect());
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    fn label(exe: &str, title: &str) -> String {
        classify(exe, title).1
    }

    #[test]
    fn legacy_spans_remain_readable() {
        let s: Span = serde_json::from_str(r#"{"s":1,"e":2,"app":"Chrome","label":"YouTube"}"#).unwrap();
        assert_eq!(s.source, "window_title"); assert!(s.browser.is_none());
    }
    #[test]
    fn transitions_form_one_stream_and_away_stops_it() {
        let dir = std::env::temp_dir().join(format!("focus-tracker-test-{}", std::process::id()));
        let t = Tracker::new(&dir);
        let now = 1_800_000_000_000;
        t.record(now, Sample::Active("Chrome".into(), "github.com".into(), None));
        t.record(now+1000, Sample::Active("Chrome".into(), "github.com".into(), None));
        t.record(now+2000, Sample::Active("Code".into(), "Code".into(), None));
        t.record(now+3000, Sample::Active("Code".into(), "Code".into(), None));
        t.record(now+4000, Sample::Away(None));
        t.record(now+5000, Sample::Away(None));
        let spans=t.spans(now, now+10000);
        assert_eq!(spans.len(),2); assert_eq!(spans[0].e,spans[1].s);
        assert_eq!(spans.iter().map(|s| s.e-s.s).sum::<u64>(),3000);
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn extension_metadata_wins_over_title_guessing() {
        let event: focus_browser_host::Event = serde_json::from_value(serde_json::json!({"version":1,"seq":1,"at":1000,"reason":"tab","focused":true,"idle":"active","enabled":true,"tab_id":2,"window_id":3,"url":"https://docs.example.org/project","title":"YouTube is mentioned in this document","audible":false,"visibility":"page"})).unwrap();
        let mut s=focus_browser_host::Snapshot { received_at:1000,browser_pid:1,browser_exe:"chrome.exe".into(),host_pid:2,event };
        match enrich("Chrome".into(),"YouTube".into(),Some(s.clone())) {
            Sample::Active(_, label, Some(b)) => { assert_eq!(label,"docs.example.org"); assert!(b.title.unwrap().contains("YouTube")); }, _ => panic!("missing browser metadata")
        }
        s.event.idle="idle".into(); assert!(matches!(enrich("Chrome".into(),"YouTube".into(),Some(s)),Sample::Away(_)));
    }
    #[test]
    fn persisted_browser_snapshot_enriches_history_without_adding_time() {
        let dir=std::env::temp_dir().join(format!("focus-history-integration-{}",std::process::id()));
        let live=dir.join("browser/live");fs::create_dir_all(&live).unwrap();
        let now=1_800_000_000_000;
        let mut snapshot: focus_browser_host::Snapshot=serde_json::from_value(serde_json::json!({"received_at":now,"browser_pid":123,"browser_exe":"chrome.exe","host_pid":456,"event":{"version":1,"seq":1,"at":now,"reason":"tab","focused":true,"idle":"active","enabled":true,"tab_id":1,"window_id":1,"url":"https://github.com/example-org/sample-project","title":"Actual project title","audible":false,"visibility":"page"}})).unwrap();
        let path=live.join("456.json");
        focus_browser_host::atomic_write(&path,&serde_json::to_vec(&snapshot).unwrap()).unwrap();
        let tracker=Tracker::new(&dir);
        for delta in [0,1000,2000] {
            let browser=focus_browser_host::latest(&dir,123,"chrome.exe",now+delta);
            tracker.record(now+delta,enrich("Chrome".into(),"Guessed title site".into(),browser));
        }
        snapshot.received_at=now+3000;snapshot.event.enabled=false;snapshot.event.url=None;snapshot.event.title=None;
        focus_browser_host::atomic_write(&path,&serde_json::to_vec(&snapshot).unwrap()).unwrap();
        for delta in [3000,4000] { tracker.record(now+delta,enrich("Chrome".into(),"Guessed site".into(),focus_browser_host::latest(&dir,123,"chrome.exe",now+delta))); }
        tracker.record(now+5000,Sample::Away(None));
        let spans=tracker.spans(now,now+6000);
        assert_eq!(spans.len(),2);assert_eq!(spans[0].source,"chromium_extension");
        assert_eq!(spans[0].label,"github.com");assert_eq!(spans[0].browser.as_ref().unwrap().title.as_deref(),Some("Actual project title"));
        assert_eq!(spans[1].source,"window_title");assert_eq!(spans[1].label,"Chrome");assert!(spans[1].browser.is_none());
        assert_eq!(spans[0].e,spans[1].s);assert_eq!(spans.iter().map(|s|s.e-s.s).sum::<u64>(),4000);
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn names_apps() {
        assert_eq!(classify("Code.exe", "main.rs - focus-app - Visual Studio Code"), ("VS Code".into(), "VS Code".into()));
        assert_eq!(label("T3 Code (Nightly).exe", "anything"), "T3 Code");
        assert_eq!(label("WindowsTerminal.exe", "bash"), "Terminal");
        assert_eq!(label("SomethingElse.exe", "x"), "SomethingElse");
    }

    #[test]
    fn names_sites_from_browser_titles() {
        assert_eq!(label("chrome.exe", "lofi beats - YouTube - Google Chrome"), "YouTube");
        assert_eq!(label("chrome.exe", "(3) Home / X - Google Chrome"), "X");
        assert_eq!(label("msedge.exe", "Demo User on X: \"hi\" / X - Personal - Microsoft\u{200b} Edge"), "X");
        assert_eq!(label("chrome.exe", "example-org/sample-project: FocusDNS - GitHub - Google Chrome"), "GitHub");
        assert_eq!(label("firefox.exe", "std::fs - Rust docs — Mozilla Firefox"), "Rust docs");
        assert_eq!(label("chrome.exe", "Some Page - Example Site - Google Chrome"), "Example Site");
        // A bare page title is private: fall back to the browser name.
        assert_eq!(label("chrome.exe", "New Tab - Google Chrome"), "Chrome");
        assert_eq!(label("chrome.exe", "Sample Account - Google Chrome"), "Chrome");
        assert_eq!(label("firefox.exe", "My private doc — Mozilla Firefox"), "Firefox");
        assert_eq!(label("chrome.exe", "Google Chrome"), "Chrome");
    }
}
