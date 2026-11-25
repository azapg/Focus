//! Native browser bridge with local, unprivileged activity ingestion.
#[cfg(feature = "peek")]
pub mod peek;
pub mod peek_history;

use serde::{Deserialize, Serialize};
use std::{
    fs,
    io::{self, Read, Write},
    path::Path,
    time::{SystemTime, UNIX_EPOCH},
};

pub const HOST: &str = "org.nightingalelab.focusdns";
pub const ORIGIN: &str = "chrome-extension://mfafgohdpfnknfgedjiicnmgomjpdock/";
pub const MAX_FRAME: usize = 32 * 1024;
pub const FRESH_MS: u64 = 15_000;
pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_millis() as u64
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Event {
    pub version: u8,
    pub seq: u64,
    pub at: u64,
    pub reason: String,
    pub focused: bool,
    pub idle: String,
    pub enabled: bool,
    pub tab_id: Option<i64>,
    pub window_id: Option<i64>,
    pub url: Option<String>,
    pub title: Option<String>,
    pub audible: bool,
    pub visibility: String,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub habit_feeds: bool,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub habit_feed: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub habit_feed_lock: Option<u64>,
}

#[derive(Clone, Debug, PartialEq, Eq, Deserialize, Serialize)]
pub struct FeedLock {
    pub id: u64,
    pub until: u64,
    pub browser_pid: u32,
    pub host_pid: u32,
    pub tab_id: i64,
    pub window_id: i64,
    pub url: String,
}

impl FeedLock {
    pub fn same_page(&self, other: &Self) -> bool {
        self.browser_pid == other.browser_pid && self.host_pid == other.host_pid
            && self.tab_id == other.tab_id && self.window_id == other.window_id && self.url == other.url
    }
}

impl Event {
    pub fn same_context(&self, other: &Self) -> bool {
        self.focused == other.focused
            && self.idle == other.idle
            && self.enabled == other.enabled
            && self.tab_id == other.tab_id
            && self.window_id == other.window_id
            && self.url == other.url
            && self.title == other.title
            && self.audible == other.audible
            && self.visibility == other.visibility
            && self.habit_feed == other.habit_feed
    }
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Snapshot {
    pub received_at: u64,
    pub browser_pid: u32,
    pub browser_exe: String,
    pub host_pid: u32,
    pub event: Event,
}
impl Snapshot {
    pub fn matches(&self, pid: u32, exe: &str, now: u64) -> bool {
        self.browser_pid == pid
            && self.browser_exe.eq_ignore_ascii_case(exe)
            && self.received_at <= now
            && now - self.received_at <= FRESH_MS
    }
    pub fn counting(&self) -> bool {
        self.event.focused && self.event.enabled && self.event.idle == "active"
    }
}
pub fn url_host(raw: &str) -> Option<String> {
    url::Url::parse(raw).ok()?.host_str().map(str::to_owned)
}
pub fn browser(exe: &str) -> bool {
    [
        "chrome.exe",
        "msedge.exe",
        "brave.exe",
        "vivaldi.exe",
        "opera.exe",
        "thorium.exe",
        "arc.exe",
        "chromium",
        "chrome",
    ]
    .iter()
    .any(|v| exe.eq_ignore_ascii_case(v))
}

/// Keep real paths and ordinary navigation queries; remove common credentials,
/// unknown query values and non-route fragments. Not a general secret detector.
pub fn sanitize_url(raw: &str) -> Option<String> {
    if raw.len() > 8192 {
        return None;
    }
    let mut u = url::Url::parse(raw).ok()?;
    if !matches!(u.scheme(), "http" | "https") {
        return None;
    }
    u.set_username("").ok()?;
    u.set_password(None).ok()?;
    let safe = [
        "q", "query", "search", "page", "v", "list", "t", "index", "lang", "sort", "tab", "view",
    ];
    let query: Vec<(String, String)> = u
        .query_pairs()
        .map(|(k, v)| {
            let key = k.to_string();
            let val = if safe.contains(&key.to_ascii_lowercase().as_str())
                && v.len() <= 256
                && !looks_secret(&v)
            {
                v.to_string()
            } else {
                "REDACTED".into()
            };
            (key.chars().take(80).collect(), val)
        })
        .collect();
    let path = u
        .path()
        .split('/')
        .map(|s| if looks_secret(s) { "REDACTED" } else { s })
        .collect::<Vec<_>>()
        .join("/");
    u.set_path(&path);
    u.set_query(None);
    if !query.is_empty() {
        u.query_pairs_mut().extend_pairs(query);
    }
    // Hash-routing is useful page identity; ordinary fragments can contain OAuth tokens.
    let fragment = u
        .fragment()
        .filter(|f| f.starts_with('/') && !f.contains('?') && !f.contains('=') && !looks_secret(f))
        .map(str::to_owned);
    u.set_fragment(fragment.as_deref());
    Some(u.to_string())
}
fn looks_secret(s: &str) -> bool {
    s.contains("eyJ")
        || s.split(|c: char| !c.is_ascii_alphanumeric() && c != '_' && c != '-')
            .any(|p| {
                p.len() >= 40
                    && !p.contains('-')
                    && (p.chars().any(|c| c.is_ascii_uppercase())
                        || p.chars().all(|c| c.is_ascii_hexdigit()))
            })
}
pub fn sanitize_title(raw: &str) -> String {
    let mut rest = raw;
    let mut out = String::new();
    loop {
        let lower = rest.to_ascii_lowercase();
        let start = lower
            .find("http://")
            .into_iter()
            .chain(lower.find("https://"))
            .min();
        let Some(start) = start else {
            out.push_str(rest);
            break;
        };
        out.push_str(&rest[..start]);
        let tail = &rest[start..];
        let end = tail
            .find(|c: char| c.is_whitespace() || "<>\"'".contains(c))
            .unwrap_or(tail.len());
        if let Some(url) = sanitize_url(&tail[..end]) {
            out.push_str(&url);
        }
        rest = &tail[end..];
    }
    out.chars().filter(|c| !c.is_control()).take(512).collect()
}
pub fn validate(mut e: Event, now: u64) -> Result<Event, &'static str> {
    if e.version != 1 || e.at.abs_diff(now) > 60_000 || e.seq == 0 {
        return Err("invalid envelope");
    }
    if !["active", "idle", "locked"].contains(&e.idle.as_str())
        || !["page", "restricted", "none"].contains(&e.visibility.as_str())
    {
        return Err("invalid state");
    }
    if ![
        "startup",
        "heartbeat",
        "tab",
        "title",
        "navigation",
        "spa",
        "fragment",
        "focus",
        "idle",
        "closed",
        "setting",
    ]
    .contains(&e.reason.as_str())
    {
        return Err("invalid reason");
    }
    if e.title.as_ref().is_some_and(|s| s.len() > 4096) {
        return Err("title too large");
    }
    e.url = e.url.as_deref().and_then(sanitize_url);
    if !e.focused || !e.enabled || e.visibility != "page" || e.url.is_none() {
        e.url = None;
        e.title = None;
        e.audible = false;
    } else {
        // Titles are requested metadata, never HTML or log text. Strip controls.
        e.title = e.title.map(|s| sanitize_title(&s));
    }
    Ok(e)
}
pub fn read_frame(r: &mut impl Read) -> io::Result<Option<Vec<u8>>> {
    read_frame_bounded(r, MAX_FRAME)
}

pub fn read_frame_bounded(r: &mut impl Read, cap: usize) -> io::Result<Option<Vec<u8>>> {
    let mut head = [0; 4];
    if r.read(&mut head[..1])? == 0 {
        return Ok(None);
    }
    r.read_exact(&mut head[1..])?;
    let n = u32::from_le_bytes(head) as usize;
    if n == 0 || n > cap {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid frame size",
        ));
    }
    let mut data = vec![0; n];
    r.read_exact(&mut data)?;
    Ok(Some(data))
}
pub fn write_frame(w: &mut impl Write, value: &impl Serialize) -> io::Result<()> {
    let bytes = serde_json::to_vec(value)?;
    w.write_all(&(bytes.len() as u32).to_le_bytes())?;
    w.write_all(&bytes)?;
    w.flush()
}
pub fn atomic_write(path: &Path, bytes: &[u8]) -> io::Result<()> {
    replace_file(path, bytes, false)
}

pub fn durable_atomic_write(path: &Path, bytes: &[u8]) -> io::Result<()> {
    replace_file(path, bytes, true)
}

fn replace_file(path: &Path, bytes: &[u8], durable: bool) -> io::Result<()> {
    let tmp = path.with_extension(format!("{}.tmp", std::process::id()));
    if durable {
        let mut file = fs::File::create(&tmp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
    } else {
        fs::write(&tmp, bytes)?;
    }
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        let wide = |p: &Path| {
            p.as_os_str()
                .encode_wide()
                .chain(Some(0))
                .collect::<Vec<_>>()
        };
        let (a, b) = (wide(&tmp), wide(path));
        use windows_sys::Win32::Storage::FileSystem::{
            MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
        };
        let flags = if durable {
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH
        } else {
            MOVEFILE_REPLACE_EXISTING
        };
        if unsafe { MoveFileExW(a.as_ptr(), b.as_ptr(), flags)
        } == 0
        {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }
    #[cfg(not(windows))]
    {
        fs::rename(tmp, path)?;
        if durable {
            let parent = path.parent().filter(|parent| !parent.as_os_str().is_empty()).unwrap_or(Path::new("."));
            fs::File::open(parent)?.sync_all()?;
        }
        Ok(())
    }
}
pub fn latest(dir: &Path, pid: u32, exe: &str, now: u64) -> Option<Snapshot> {
    fs::read_dir(dir.join("browser/live"))
        .ok()?
        .filter_map(Result::ok)
        .filter_map(|f| {
            if f.path().extension()?.to_str()? != "json"
                || f.metadata().ok()?.len() > MAX_FRAME as u64 + 2048
            {
                return None;
            }
            let s: Snapshot = serde_json::from_slice(&fs::read(f.path()).ok()?).ok()?;
            s.matches(pid, exe, now).then_some(s)
        })
        .max_by_key(|s| (s.event.focused, s.received_at))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn framing_is_bounded_and_handles_truncation() {
        assert!(read_frame(&mut &[][..]).unwrap().is_none());
        for b in [vec![1], vec![0, 0, 0, 0], vec![255; 4], vec![3, 0, 0, 0, 1]] {
            assert!(read_frame(&mut &b[..]).is_err());
        }
        let mut b = vec![];
        write_frame(&mut b, &serde_json::json!({"ok":true})).unwrap();
        assert_eq!(read_frame(&mut &b[..]).unwrap().unwrap(), br#"{"ok":true}"#);
    }
    #[test]
    fn redaction_preserves_page_identity() {
        assert_eq!(sanitize_url("https://user:pass@github.com/example-org/sample-project?tab=readme&token=secret#access_token=x").unwrap(), "https://github.com/example-org/sample-project?tab=readme&token=REDACTED");
        assert_eq!(
            sanitize_url("https://example.org/#/projects/1").unwrap(),
            "https://example.org/#/projects/1"
        );
        assert!(sanitize_url("file:///private.txt").is_none());
        assert!(sanitize_url("chrome://settings").is_none());
        assert!(
            !sanitize_url("https://example.org/a/abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO")
                .unwrap()
                .contains("abcdefgh")
        );
    }
    #[test]
    fn credentials_in_embedded_title_urls_are_redacted_too() {
        assert_eq!(
            sanitize_title("Project https://user:pass@example.org/?token=secret details"),
            "Project https://example.org/?token=REDACTED details"
        );
    }
    #[test]
    fn same_redaction_fixtures_as_extension() {
        let fixtures: Vec<serde_json::Value> =
            serde_json::from_str(include_str!("../../tests/url-fixtures.json")).unwrap();
        for f in fixtures {
            assert_eq!(
                sanitize_url(f["input"].as_str().unwrap()),
                f["output"].as_str().map(str::to_owned)
            );
        }
    }
}

#[cfg(test)]
extern crate self as focus_browser_host;
#[cfg(test)]
#[allow(dead_code)]
#[path = "../../app/src/activity.rs"]
mod app_activity_tests;

#[cfg(test)]
mod lifecycle_tests {
    use super::*;
    fn snapshot(pid: u32, at: u64, focused: bool) -> Snapshot {
        let e=serde_json::from_value(serde_json::json!({"version":1,"seq":1,"at":at,"reason":"tab","focused":focused,"idle":"active","enabled":true,"tab_id":1,"window_id":1,"url":"https://example.org/","title":"Example","audible":false,"visibility":"page"})).unwrap();
        Snapshot {
            received_at: at,
            browser_pid: pid,
            browser_exe: "chrome.exe".into(),
            host_pid: 2,
            event: e,
        }
    }
    #[test]
    fn stale_other_process_and_future_snapshots_do_not_match() {
        let s = snapshot(123, 10000, true);
        assert!(s.matches(123, "CHROME.EXE", 10001));
        assert!(!s.matches(124, "chrome.exe", 10001));
        assert!(!s.matches(123, "msedge.exe", 10001));
        assert!(!s.matches(123, "chrome.exe", 9999));
        assert!(!s.matches(123, "chrome.exe", 26000));
    }
    #[test]
    fn inactive_profile_heartbeat_cannot_replace_focused_context() {
        let dir =
            std::env::temp_dir().join(format!("focus-multiprofile-test-{}", std::process::id()));
        let live = dir.join("browser/live");
        fs::create_dir_all(&live).unwrap();
        fs::write(
            live.join("1.json"),
            serde_json::to_vec(&snapshot(123, 10000, true)).unwrap(),
        )
        .unwrap();
        fs::write(
            live.join("2.json"),
            serde_json::to_vec(&snapshot(123, 10001, false)).unwrap(),
        )
        .unwrap();
        assert!(
            latest(&dir, 123, "chrome.exe", 10002)
                .unwrap()
                .event
                .focused
        );
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn invalid_events_fail_closed() {
        let e = snapshot(123, 10000, true).event;
        let mut bad = e.clone();
        bad.version = 2;
        assert!(validate(bad, 10000).is_err());
        let mut bad = e.clone();
        bad.idle = "unknown".into();
        assert!(validate(bad, 10000).is_err());
        assert!(validate(e.clone(), 100000).is_err());
        let mut hidden = e;
        hidden.focused = false;
        let hidden = validate(hidden, 10000).unwrap();
        assert!(hidden.url.is_none());
        assert!(hidden.title.is_none());
    }
}

pub fn normalize_nextdns_profile(profile: &str) -> Option<String> {
    (profile.len() == 6 && profile.bytes().all(|byte| byte.is_ascii_hexdigit()))
        .then(|| profile.to_ascii_lowercase())
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NextDnsConfig {
    pub profile: String,
    pub legacy_confirmed: bool,
    servers: Vec<std::net::IpAddr>,
}

impl NextDnsConfig {
    pub fn read(dir: &std::path::Path) -> Option<Self> {
        let mut config: Self = serde_json::from_slice(&std::fs::read(dir.join("nextdns.json")).ok()?).ok()?;
        config.profile = normalize_nextdns_profile(&config.profile)?;
        if config.servers.len() != 4 { return None; }
        config.servers.sort_unstable();
        config.servers.dedup();
        (config.servers.len() == 4 && config.servers.iter().filter(|ip| ip.is_ipv4()).count() == 2
            && !config.servers.iter().any(|ip| matches!(ip, std::net::IpAddr::V6(v6) if v6.to_ipv4_mapped().is_some())))
            .then_some(config)
    }
}

pub fn configured_nextdns_profile(dir: &std::path::Path, saved: &str) -> Option<String> {
    let profile = normalize_nextdns_profile(saved)?;
    NextDnsConfig::read(dir).filter(|config| config.profile == profile).map(|config| config.profile)
}
