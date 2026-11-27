use std::{fs, io, path::Path};

// The app binary is already checked by the nightly updater. Ship the extension changes inside it.
const MANIFEST: &[u8] = include_bytes!("../../extension/manifest.json");
// Install dependencies before the scripts that load them, and the manifest last.
const FILES: &[(&str, &[u8])] = &[
    ("capture.js", include_bytes!("../../extension/capture.js")),
    ("habit-feeds.js", include_bytes!("../../extension/habit-feeds.js")),
    ("overlay.js", include_bytes!("../../extension/overlay.js")),
    ("public-tlds.js", include_bytes!("../../extension/public-tlds.js")),
    ("protection.js", include_bytes!("../../extension/protection.js")),
    ("telemetry.js", include_bytes!("../../extension/telemetry.js")),
    ("peek-page.js", include_bytes!("../../extension/peek-page.js")),
    ("peek.css", include_bytes!("../../extension/peek.css")),
    ("peek.html", include_bytes!("../../extension/peek.html")),
    ("peek-navigation.js", include_bytes!("../../extension/peek-navigation.js")),
    ("background.js", include_bytes!("../../extension/background.js")),
    ("icons/icon-16.png", include_bytes!("../../extension/icons/icon-16.png")),
    ("icons/icon-32.png", include_bytes!("../../extension/icons/icon-32.png")),
    ("icons/icon-48.png", include_bytes!("../../extension/icons/icon-48.png")),
    ("icons/icon-128.png", include_bytes!("../../extension/icons/icon-128.png")),
    ("manifest.json", MANIFEST),
];

pub fn update_installed(dir: &Path) -> io::Result<()> {
    let extension = dir.join("browser-bridge/extension");
    let manifest = match fs::read(extension.join("manifest.json")) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error),
    };
    let installed: serde_json::Value = serde_json::from_slice(&manifest)?;
    let bundle: serde_json::Value = serde_json::from_slice(MANIFEST)?;
    if installed["key"] != bundle["key"] {
        return Ok(());
    }
    for (name, bytes) in FILES {
        let path = extension.join(name);
        if !fs::read(&path).is_ok_and(|current| current == *bytes) {
            fs::create_dir_all(path.parent().unwrap())?;
            focus_browser_host::atomic_write(&path, bytes)?;
        }
    }
    Ok(())
}

#[derive(Clone, Copy)]
pub struct BrowserSession {
    pub browser_pid: u32,
    pub host_pid: u32,
}

impl From<&focus_browser_host::FeedLock> for BrowserSession {
    fn from(lock: &focus_browser_host::FeedLock) -> Self {
        Self { browser_pid: lock.browser_pid, host_pid: lock.host_pid }
    }
}

pub struct BrowserSessions(Vec<focus_browser_host::Snapshot>);

impl BrowserSessions {
    pub fn load(dir: &Path) -> Self {
        Self::read(dir, focus_browser_host::now_ms)
    }

    fn read(dir: &Path, clock: impl Fn() -> u64) -> Self {
        use focus_browser_host::{Snapshot, FRESH_MS, MAX_FRAME};
        Self(fs::read_dir(dir.join("browser/live")).into_iter().flatten().filter_map(Result::ok)
            .filter(|file| file.path().extension().is_some_and(|extension| extension == "json")
                && file.metadata().is_ok_and(|m| m.len() <= MAX_FRAME as u64 + 2048))
            .filter_map(|file| serde_json::from_slice::<Snapshot>(&fs::read(file.path()).ok()?).ok())
            .filter(|snapshot| {
                // The helper may replace its snapshot while we are reading this directory.
                let now = clock();
                snapshot.received_at <= now && now - snapshot.received_at < FRESH_MS
                    && process_alive(snapshot.host_pid)
            }).collect())
    }

    pub fn ready(&self) -> bool {
        !self.0.is_empty() && self.0.iter().all(|snapshot| snapshot.event.habit_feeds)
    }

    fn session(&self, session: BrowserSession) -> Option<&focus_browser_host::Event> {
        self.0.iter().find(|snapshot| snapshot.browser_pid == session.browser_pid
            && snapshot.host_pid == session.host_pid && snapshot.event.habit_feeds)
            .map(|snapshot| &snapshot.event)
    }

    pub fn has_cover(&self, lock: &focus_browser_host::FeedLock) -> bool {
        self.session(lock.into()).is_some_and(|event| event.habit_feed_lock == Some(lock.id))
    }

    pub fn can_cover(&self, session: BrowserSession, tab: Option<i64>, window: Option<i64>, url: Option<&str>) -> bool {
        self.session(session).is_some_and(|event| event.habit_feed && event.focused
            && event.enabled && event.idle == "active" && tab.is_some() && window.is_some() && url.is_some()
            && event.tab_id == tab && event.window_id == window && event.url.as_deref() == url)
    }

    pub fn covered(&self, lock: &focus_browser_host::FeedLock) -> bool {
        self.session(lock.into()).is_some_and(|event| event.habit_feed_lock == Some(lock.id)
            && event.tab_id == Some(lock.tab_id) && event.window_id == Some(lock.window_id)
            && event.url.as_deref() == Some(lock.url.as_str()))
    }
}

#[cfg(windows)]
fn process_alive(pid: u32) -> bool {
    use windows_sys::Win32::{
        Foundation::{CloseHandle, STILL_ACTIVE},
        System::Threading::{GetExitCodeProcess, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION},
    };
    unsafe {
        let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if handle.is_null() {
            return false;
        }
        let mut code = 0;
        let alive = GetExitCodeProcess(handle, &mut code) != 0 && code == STILL_ACTIVE as u32;
        CloseHandle(handle);
        alive
    }
}

#[cfg(not(windows))]
fn process_alive(pid: u32) -> bool {
    pid == std::process::id()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_runtime_files_match(source: &Path, installed: &Path) {
        for entry in fs::read_dir(source).unwrap().map(Result::unwrap) {
            let name = entry.file_name();
            if name == "package.json" {
                continue; // Node module settings for development and tests.
            }
            let target = installed.join(&name);
            if entry.file_type().unwrap().is_dir() {
                assert_runtime_files_match(&entry.path(), &target);
            } else {
                assert_eq!(fs::read(&target).unwrap_or_else(|err| panic!("{}: {err}", target.display())),
                    fs::read(entry.path()).unwrap(), "{}", target.display());
            }
        }
    }

    #[test]
    fn existing_managed_extensions_get_all_runtime_files_on_app_startup() {
        let dir = std::env::temp_dir().join(format!("focus-extension-update-{}-{}", std::process::id(), focus_browser_host::now_ms()));
        let installed = dir.join("browser-bridge/extension");
        update_installed(&dir).unwrap();
        assert!(!installed.exists());
        fs::create_dir_all(&installed).unwrap();
        let mut old: serde_json::Value = serde_json::from_slice(MANIFEST).unwrap();
        old["version"] = "0.2.1".into();
        fs::write(installed.join("manifest.json"), old.to_string()).unwrap();
        fs::write(installed.join("background.js"), b"old").unwrap();
        fs::write(installed.join("protection.js"), b"old").unwrap();
        update_installed(&dir).unwrap();
        let source = Path::new(env!("CARGO_MANIFEST_DIR")).join("../extension");
        assert_runtime_files_match(&source, &installed);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn only_a_recent_compatible_helper_can_enable_feed_lock() {
        let dir = std::env::temp_dir().join(format!("focus-extension-ready-{}-{}", std::process::id(), focus_browser_host::now_ms()));
        let live = dir.join("browser/live");
        fs::create_dir_all(&live).unwrap();
        let now = 1_234_567;
        let reels = Some("https://www.instagram.com/reels/");
        let tiktok = Some("https://www.tiktok.com/foryou");
        let mut snapshot = serde_json::json!({
            "received_at": now, "browser_pid": 123, "browser_exe": "chrome.exe", "host_pid": std::process::id(),
            "event": {
                "version": 1, "seq": 1, "at": now, "reason": "heartbeat", "focused": true,
                "idle": "active", "enabled": true, "tab_id": 1, "window_id": 1, "url": reels,
                "title": null, "audible": false, "visibility": "page"
            }
        });
        let file = live.join("456.json");
        let read = || BrowserSessions::read(&dir, || now);
        fs::write(&file, snapshot.to_string()).unwrap();
        assert!(!read().ready());
        let chrome = BrowserSession { browser_pid: 123, host_pid: std::process::id() };
        let edge = BrowserSession { browser_pid: 234, host_pid: std::process::id() };
        let mut edge_snapshot = snapshot.clone();
        edge_snapshot["browser_pid"] = edge.browser_pid.into();
        edge_snapshot["browser_exe"] = "msedge.exe".into();
        edge_snapshot["event"]["habit_feeds"] = true.into();
        fs::write(live.join("edge.json"), edge_snapshot.to_string()).unwrap();
        assert!(!read().ready());
        assert!(read().session(chrome).is_none());
        assert!(read().session(edge).is_some());
        assert!(!read().can_cover(edge, Some(1), Some(1), reels));
        snapshot["event"]["habit_feeds"] = true.into();
        fs::write(&file, snapshot.to_string()).unwrap();
        assert!(read().ready());
        assert!(read().session(chrome).is_some());
        assert!(!read().can_cover(chrome, Some(1), Some(1), reels));
        fs::remove_file(live.join("edge.json")).unwrap();
        assert!(!BrowserSessions::read(&dir, || now + focus_browser_host::FRESH_MS).ready());

        snapshot["received_at"] = (now + 1).into();
        snapshot["event"]["habit_feed"] = true.into();
        fs::write(&file, snapshot.to_string()).unwrap();
        // The working time from before scanning predates the new heartbeat.
        let refreshed = BrowserSessions::read(&dir, || now + 2);
        assert!(refreshed.session(chrome).is_some());
        assert!(refreshed.can_cover(chrome, Some(1), Some(1), reels));
        assert!(!refreshed.can_cover(chrome, Some(2), Some(1), reels));
        assert!(!refreshed.can_cover(chrome, Some(1), None, reels));
        assert!(!refreshed.can_cover(chrome, Some(1), Some(1), None));
        let lock = focus_browser_host::FeedLock {
            id: 44, until: now + 35_000, browser_pid: chrome.browser_pid, host_pid: chrome.host_pid,
            tab_id: 1, window_id: 1, url: reels.unwrap().into(),
        };
        assert!(!refreshed.covered(&lock));
        snapshot["event"]["habit_feed_lock"] = lock.id.into();
        fs::write(&file, snapshot.to_string()).unwrap();
        assert!(BrowserSessions::read(&dir, || now + 2).covered(&lock));
        snapshot["event"]["url"] = tiktok.into();
        fs::write(&file, snapshot.to_string()).unwrap();
        let navigated = BrowserSessions::read(&dir, || now + 2);
        assert!(!navigated.can_cover(chrome, Some(1), Some(1), reels));
        assert!(navigated.can_cover(chrome, Some(1), Some(1), tiktok));
        assert!(!navigated.covered(&lock));
        assert!(navigated.has_cover(&lock));
        snapshot["host_pid"] = 0.into();
        fs::write(&file, snapshot.to_string()).unwrap();
        assert!(!BrowserSessions::read(&dir, || now + 2).ready());
        fs::remove_dir_all(dir).unwrap();
    }
}
