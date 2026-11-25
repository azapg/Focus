//! Native Windows overlay window enforcement, desktop shell readiness wait,
//! single-instance probe, and panic logging.

use std::{
    fs::OpenOptions,
    io::Write,
    path::{Path, PathBuf},
    time::{Duration, Instant},
};

pub const STYLE_MAXIMIZEBOX: u32 = 0x0001_0000;
pub const STYLE_MINIMIZEBOX: u32 = 0x0002_0000;
pub const EX_STYLE_TOPMOST: u32 = 0x0000_0008;
pub const EX_STYLE_TRANSPARENT: u32 = 0x0000_0020;
pub const EX_STYLE_TOOLWINDOW: u32 = 0x0000_0080;
pub const EX_STYLE_APPWINDOW: u32 = 0x0004_0000;
pub const EX_STYLE_LAYERED: u32 = 0x0008_0000;

pub const SYS_CMD_MASK: usize = 0xFFF0;
pub const SYS_CMD_MINIMIZE: usize = 0xF020;
pub const SYS_CMD_MAXIMIZE: usize = 0xF030;

pub const REPAIR_REASON_TASKBAR: usize = 1;
pub const REPAIR_REASON_DISPLAY: usize = 2;
pub const REPAIR_REASON_MINIMIZED: usize = 3;
pub const REPAIR_REASON_HEALTH: usize = 4;
pub const REPAIR_REASON_SECOND_INSTANCE: usize = 5;

pub fn repair_reason_name(reason: usize) -> &'static str {
    match reason {
        REPAIR_REASON_TASKBAR => "taskbar_created",
        REPAIR_REASON_DISPLAY => "display_change",
        REPAIR_REASON_MINIMIZED => "minimized",
        REPAIR_REASON_HEALTH => "health_check",
        REPAIR_REASON_SECOND_INSTANCE => "second_instance",
        _ => "unknown",
    }
}

/// Appends one timestamped line to `%LOCALAPPDATA%\FocusDNS\focusdns.log` without
/// touching any mutex, so it is safe inside panic hooks and Win32 window procs.
pub fn append_log(dir: &Path, msg: &str) {
    let line = format!("{} {}\n", chrono::Local::now().format("%Y-%m-%d %H:%M:%S"), msg);
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(dir.join("focusdns.log")) {
        let _ = f.write_all(line.as_bytes());
        let _ = f.flush();
    }
}

/// Formats a panic into a single log line (newlines collapsed).
pub fn format_panic_report(thread: &str, message: &str, location: Option<(&str, u32, u32)>) -> String {
    let clean = message.trim().replace("\r\n", " | ").replace('\n', " | ");
    let clean = if clean.is_empty() { "<empty panic message>".to_string() } else { clean };
    match location {
        Some((file, line, col)) => format!("panic in thread '{thread}' at {file}:{line}:{col}: {clean}"),
        None => format!("panic in thread '{thread}': {clean}"),
    }
}

/// Installs a process-wide panic hook that writes the panic details to `focusdns.log`
/// and exits with code 1 so Windows Task Scheduler restarts the app.
pub fn install_panic_hook(log_dir: PathBuf) {
    std::panic::set_hook(Box::new(move |info| {
        let payload = if let Some(s) = info.payload().downcast_ref::<&str>() {
            (*s).to_string()
        } else if let Some(s) = info.payload().downcast_ref::<String>() {
            s.clone()
        } else {
            "non-string panic payload".to_string()
        };
        let loc = info.location().map(|l| (l.file(), l.line(), l.column()));
        let current = std::thread::current();
        let thread = current.name().unwrap_or("unnamed");
        let report = format_panic_report(thread, &payload, loc);
        append_log(&log_dir, &format!("PANIC pid={} {report}", std::process::id()));
        std::process::exit(1);
    }));
}

/// Removes minimize and maximize box styles so Windows cannot minimize or maximize the dock.
pub fn sanitize_style(style: u32) -> u32 {
    style & !(STYLE_MINIMIZEBOX | STYLE_MAXIMIZEBOX)
}

/// Enforces `WS_EX_TOOLWINDOW` and strips `WS_EX_APPWINDOW` so the dock stays out
/// of the Windows taskbar and Alt+Tab even across Explorer restarts.
pub fn sanitize_ex_style(ex_style: u32) -> u32 {
    (ex_style | EX_STYLE_TOOLWINDOW) & !EX_STYLE_APPWINDOW
}

/// Toggles click-through (`WS_EX_TRANSPARENT | WS_EX_LAYERED`) while keeping
/// the overlay's toolwindow/no-appwindow bits intact.
pub fn apply_click_through_ex_style(ex_style: u32, click_through: bool) -> u32 {
    let base = sanitize_ex_style(ex_style);
    if click_through {
        base | EX_STYLE_TRANSPARENT | EX_STYLE_LAYERED
    } else {
        base & !(EX_STYLE_TRANSPARENT | EX_STYLE_LAYERED)
    }
}

/// Returns true when the HWND's style and extended style satisfy all overlay invariants.
pub fn overlay_styles_valid(style: u32, ex_style: u32) -> bool {
    (style & (STYLE_MINIMIZEBOX | STYLE_MAXIMIZEBOX)) == 0
        && (ex_style & EX_STYLE_TOOLWINDOW) != 0
        && (ex_style & EX_STYLE_APPWINDOW) == 0
        && (ex_style & EX_STYLE_TOPMOST) != 0
}

/// Returns true for `WM_SYSCOMMAND` requests that would minimize or maximize the overlay.
pub fn should_block_syscommand(wparam: usize) -> bool {
    let cmd = wparam & SYS_CMD_MASK;
    cmd == SYS_CMD_MINIMIZE || cmd == SYS_CMD_MAXIMIZE
}

/// Computes the top-center physical pixel position on the primary monitor.
/// The `--dry-run` copy sits 90px lower so it never covers the real dock.
pub fn top_center_position(mon_x: i32, mon_y: i32, mon_w: i32, win_w: i32, dry: bool) -> (i32, i32) {
    let x = mon_x + (mon_w - win_w) / 2;
    let y = mon_y + if dry { 90 } else { 0 };
    (x, y)
}

/// Decides whether the overlay window has drifted and needs a repair pass.
pub fn needs_overlay_repair(
    visible: bool,
    minimized: bool,
    style: u32,
    ex_style: u32,
    pos: (i32, i32),
    expected_pos: (i32, i32),
) -> bool {
    !visible || minimized || !overlay_styles_valid(style, ex_style) || pos != expected_pos
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ShellStatus {
    pub has_tray: bool,
    pub has_monitor: bool,
}

impl ShellStatus {
    pub fn ready(self) -> bool {
        self.has_tray && self.has_monitor
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ShellWaitResult {
    pub ready: bool,
    pub status: ShellStatus,
    pub attempts: u32,
    pub waited: Duration,
}

/// Polls `probe` until both Explorer (`Shell_TrayWnd`) and a primary monitor exist,
/// or until `timeout` elapses. Clock and sleep are injected so unit tests run deterministically.
pub fn wait_for_shell_with<P, S, N>(
    timeout: Duration,
    poll_interval: Duration,
    mut probe: P,
    mut sleep: S,
    mut elapsed: N,
) -> ShellWaitResult
where
    P: FnMut() -> ShellStatus,
    S: FnMut(Duration),
    N: FnMut() -> Duration,
{
    let mut attempts = 0u32;
    loop {
        attempts += 1;
        let status = probe();
        let waited = elapsed();
        if status.ready() {
            return ShellWaitResult { ready: true, status, attempts, waited };
        }
        if waited >= timeout {
            return ShellWaitResult { ready: false, status, attempts, waited };
        }
        let remaining = timeout.saturating_sub(waited);
        sleep(poll_interval.min(remaining));
    }
}

#[cfg(windows)]
mod win32 {
    use super::*;
    use std::cell::Cell;
    use tauri::WebviewWindow;
    use windows_sys::Win32::{
        Foundation::{CloseHandle, HWND, LPARAM, LRESULT, POINT, RECT, WPARAM},
        Graphics::Gdi::{GetMonitorInfoW, MonitorFromPoint, MONITORINFO, MONITOR_DEFAULTTOPRIMARY},
        System::{
            LibraryLoader::GetModuleHandleW,
            Threading::{OpenMutexW, SYNCHRONIZATION_SYNCHRONIZE},
        },
        UI::{
            Shell::{DefSubclassProc, RemoveWindowSubclass, SetWindowSubclass},
            WindowsAndMessaging::{
                ChangeWindowMessageFilterEx, FindWindowW, GetSystemMetrics, GetWindowLongPtrW,
                GetWindowRect, IsIconic, IsWindowVisible, LoadImageW, PostMessageW,
                RegisterWindowMessageW, SendMessageW, SetWindowLongPtrW, SetWindowPos, ShowWindow,
                GWL_EXSTYLE, GWL_STYLE, HWND_TOPMOST, ICON_BIG, ICON_SMALL, IMAGE_ICON, LR_SHARED,
                MSGFLT_ALLOW, SIZE_MINIMIZED, SM_CXICON, SM_CXSMICON, SM_CYICON, SM_CYSMICON,
                STYLESTRUCT, SWP_FRAMECHANGED, SWP_NOACTIVATE, SWP_NOMOVE, SWP_NOSIZE,
                SWP_NOZORDER, SW_SHOWNOACTIVATE, WM_APP, WM_DISPLAYCHANGE, WM_DPICHANGED,
                WM_NCDESTROY, WM_SETICON, WM_SIZE, WM_STYLECHANGING, WM_SYSCOMMAND,
            },
        },
    };

    const OVERLAY_SUBCLASS_ID: usize = 0xF0C5_0001;
    const WM_APP_REPAIR: u32 = WM_APP + 1;
    const APP_ICON_RESOURCE_ID: usize = 32512;

    #[derive(Clone, Copy, Debug)]
    pub struct OverlaySnapshot {
        pub style: u32,
        pub ex_style: u32,
        pub visible: bool,
        pub minimized: bool,
        pub x: i32,
        pub y: i32,
    }

    struct SubclassState {
        taskbar_created_msg: u32,
        dry: bool,
        log_dir: PathBuf,
        in_repair: Cell<bool>,
    }

    fn encode_wide(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(std::iter::once(0)).collect()
    }

    /// Probes `tauri-plugin-single-instance`'s named mutex without creating it, so a
    /// duplicate launch can log its exit clearly and skip shell waits / background threads.
    pub fn is_single_instance_mutex_held(identifier: &str) -> bool {
        let name = encode_wide(&format!("{identifier}-sim"));
        unsafe {
            let handle = OpenMutexW(SYNCHRONIZATION_SYNCHRONIZE, 0, name.as_ptr());
            if !handle.is_null() {
                CloseHandle(handle);
                true
            } else {
                false
            }
        }
    }

    fn primary_monitor_rect() -> Option<RECT> {
        unsafe {
            let hmon = MonitorFromPoint(POINT { x: 0, y: 0 }, MONITOR_DEFAULTTOPRIMARY);
            if hmon.is_null() {
                return None;
            }
            let mut info: MONITORINFO = std::mem::zeroed();
            info.cbSize = std::mem::size_of::<MONITORINFO>() as u32;
            if GetMonitorInfoW(hmon, &mut info) == 0 {
                return None;
            }
            let r = info.rcMonitor;
            ((r.right - r.left) > 0 && (r.bottom - r.top) > 0).then_some(r)
        }
    }

    pub fn probe_desktop_shell() -> ShellStatus {
        let tray_class = encode_wide("Shell_TrayWnd");
        let has_tray = unsafe { !FindWindowW(tray_class.as_ptr(), std::ptr::null()).is_null() };
        let has_monitor = primary_monitor_rect().is_some();
        ShellStatus { has_tray, has_monitor }
    }

    pub fn wait_for_desktop_shell(log_dir: &Path, timeout: Duration, poll_interval: Duration) -> ShellWaitResult {
        let first = probe_desktop_shell();
        if first.ready() {
            append_log(log_dir, "desktop shell ready (waited 0ms)");
            return ShellWaitResult { ready: true, status: first, attempts: 1, waited: Duration::ZERO };
        }
        append_log(
            log_dir,
            &format!(
                "waiting for desktop shell (tray={}, monitor={}, timeout={}s)",
                first.has_tray,
                first.has_monitor,
                timeout.as_secs()
            ),
        );
        let started = Instant::now();
        let res = wait_for_shell_with(
            timeout,
            poll_interval,
            probe_desktop_shell,
            |d| std::thread::sleep(d),
            || started.elapsed(),
        );
        if res.ready {
            append_log(
                log_dir,
                &format!("desktop shell ready after {}ms ({} checks)", res.waited.as_millis(), res.attempts),
            );
        } else {
            append_log(
                log_dir,
                &format!(
                    "desktop shell wait timed out after {}ms (tray={}, monitor={}); continuing",
                    res.waited.as_millis(),
                    res.status.has_tray,
                    res.status.has_monitor
                ),
            );
        }
        res
    }

    unsafe fn read_snapshot(hwnd: HWND) -> OverlaySnapshot {
        let style = GetWindowLongPtrW(hwnd, GWL_STYLE) as u32;
        let ex_style = GetWindowLongPtrW(hwnd, GWL_EXSTYLE) as u32;
        let visible = IsWindowVisible(hwnd) != 0;
        let minimized = IsIconic(hwnd) != 0;
        let mut rect: RECT = std::mem::zeroed();
        let (x, y) = if GetWindowRect(hwnd, &mut rect) != 0 {
            (rect.left, rect.top)
        } else {
            (0, 0)
        };
        OverlaySnapshot { style, ex_style, visible, minimized, x, y }
    }

    unsafe fn expected_pos_for_hwnd(hwnd: HWND, dry: bool) -> Option<(i32, i32)> {
        let mon = primary_monitor_rect()?;
        let mut rect: RECT = std::mem::zeroed();
        if GetWindowRect(hwnd, &mut rect) == 0 {
            return None;
        }
        let win_w = rect.right - rect.left;
        let mon_w = mon.right - mon.left;
        Some(top_center_position(mon.left, mon.top, mon_w, win_w, dry))
    }

    unsafe fn apply_embedded_icon(hwnd: HWND, log_dir: &Path) {
        let hinst = GetModuleHandleW(std::ptr::null());
        let cx_big = GetSystemMetrics(SM_CXICON).max(32);
        let cy_big = GetSystemMetrics(SM_CYICON).max(32);
        let cx_sm = GetSystemMetrics(SM_CXSMICON).max(16);
        let cy_sm = GetSystemMetrics(SM_CYSMICON).max(16);
        let res_id = APP_ICON_RESOURCE_ID as *const u16;
        let big = LoadImageW(hinst, res_id, IMAGE_ICON, cx_big, cy_big, LR_SHARED);
        let small = LoadImageW(hinst, res_id, IMAGE_ICON, cx_sm, cy_sm, LR_SHARED);
        if !big.is_null() {
            SendMessageW(hwnd, WM_SETICON, ICON_BIG as usize, big as isize);
        }
        if !small.is_null() {
            SendMessageW(hwnd, WM_SETICON, ICON_SMALL as usize, small as isize);
        }
        if big.is_null() || small.is_null() {
            append_log(
                log_dir,
                &format!(
                    "overlay: embedded icon load incomplete (big={}, small={})",
                    !big.is_null(),
                    !small.is_null()
                ),
            );
        }
    }

    unsafe fn apply_click_through_hwnd(hwnd: HWND, click_through: bool) {
        let style = GetWindowLongPtrW(hwnd, GWL_STYLE) as u32;
        let want_style = sanitize_style(style);
        if want_style != style {
            SetWindowLongPtrW(hwnd, GWL_STYLE, want_style as isize);
        }
        let ex_style = GetWindowLongPtrW(hwnd, GWL_EXSTYLE) as u32;
        let want_ex = apply_click_through_ex_style(ex_style, click_through);
        if want_ex != ex_style {
            SetWindowLongPtrW(hwnd, GWL_EXSTYLE, want_ex as isize);
        }
        if want_style != style || want_ex != ex_style {
            SetWindowPos(
                hwnd,
                std::ptr::null_mut(),
                0,
                0,
                0,
                0,
                SWP_NOZORDER | SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_FRAMECHANGED,
            );
        }
    }

    unsafe fn enforce_overlay_hwnd(
        hwnd: HWND,
        dry: bool,
        ensure_visible: bool,
        log_dir: Option<&Path>,
        reason: &str,
    ) -> OverlaySnapshot {
        let before = read_snapshot(hwnd);
        let want_style = sanitize_style(before.style);
        let want_ex = sanitize_ex_style(before.ex_style);
        if want_style != before.style {
            SetWindowLongPtrW(hwnd, GWL_STYLE, want_style as isize);
        }
        if want_ex != before.ex_style {
            SetWindowLongPtrW(hwnd, GWL_EXSTYLE, want_ex as isize);
        }
        if ensure_visible && (before.minimized || !before.visible) {
            ShowWindow(hwnd, SW_SHOWNOACTIVATE);
        }
        if let Some((x, y)) = expected_pos_for_hwnd(hwnd, dry) {
            SetWindowPos(
                hwnd,
                HWND_TOPMOST,
                x,
                y,
                0,
                0,
                SWP_NOSIZE | SWP_NOACTIVATE | SWP_FRAMECHANGED,
            );
        } else {
            SetWindowPos(
                hwnd,
                HWND_TOPMOST,
                0,
                0,
                0,
                0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_FRAMECHANGED,
            );
        }
        let after = read_snapshot(hwnd);
        if let Some(dir) = log_dir {
            append_log(
                dir,
                &format!(
                    "overlay: enforced ({reason}) visible={}->{} minimized={}->{} style=0x{:08x}->0x{:08x} ex=0x{:08x}->0x{:08x} pos=({},{})->({},{})",
                    before.visible,
                    after.visible,
                    before.minimized,
                    after.minimized,
                    before.style,
                    after.style,
                    before.ex_style,
                    after.ex_style,
                    before.x,
                    before.y,
                    after.x,
                    after.y
                ),
            );
        }
        after
    }

    unsafe extern "system" fn overlay_subclass_proc(
        hwnd: HWND,
        msg: u32,
        wparam: WPARAM,
        lparam: LPARAM,
        uid: usize,
        ref_data: usize,
    ) -> LRESULT {
        if ref_data == 0 {
            return DefSubclassProc(hwnd, msg, wparam, lparam);
        }
        let state = &*(ref_data as *const SubclassState);

        match msg {
            WM_STYLECHANGING => {
                if lparam != 0 {
                    let ss = &mut *(lparam as *mut STYLESTRUCT);
                    let kind = wparam as i32;
                    if kind == GWL_STYLE {
                        ss.styleNew = sanitize_style(ss.styleNew);
                    } else if kind == GWL_EXSTYLE {
                        ss.styleNew = sanitize_ex_style(ss.styleNew);
                    }
                }
                DefSubclassProc(hwnd, msg, wparam, lparam)
            }
            WM_SYSCOMMAND if should_block_syscommand(wparam) => 0,
            WM_SIZE if wparam == SIZE_MINIMIZED as usize => {
                let res = DefSubclassProc(hwnd, msg, wparam, lparam);
                PostMessageW(hwnd, WM_APP_REPAIR, REPAIR_REASON_MINIMIZED, 0);
                res
            }
            WM_DISPLAYCHANGE | WM_DPICHANGED => {
                let res = DefSubclassProc(hwnd, msg, wparam, lparam);
                PostMessageW(hwnd, WM_APP_REPAIR, REPAIR_REASON_DISPLAY, 0);
                res
            }
            WM_APP_REPAIR => {
                if !state.in_repair.replace(true) {
                    enforce_overlay_hwnd(
                        hwnd,
                        state.dry,
                        true,
                        Some(&state.log_dir),
                        repair_reason_name(wparam),
                    );
                    state.in_repair.set(false);
                }
                0
            }
            WM_NCDESTROY => {
                RemoveWindowSubclass(hwnd, Some(overlay_subclass_proc), uid);
                let _ = Box::from_raw(ref_data as *mut SubclassState);
                DefSubclassProc(hwnd, msg, wparam, lparam)
            }
            _ => {
                if state.taskbar_created_msg != 0 && msg == state.taskbar_created_msg {
                    append_log(&state.log_dir, "overlay: TaskbarCreated received, scheduling repair");
                    PostMessageW(hwnd, WM_APP_REPAIR, REPAIR_REASON_TASKBAR, 0);
                }
                DefSubclassProc(hwnd, msg, wparam, lparam)
            }
        }
    }

    /// Configures Win32 overlay styles, subclass guards, icon, click-through, and initial
    /// top-center position BEFORE the window is shown for the first time.
    pub fn init_overlay_window(win: &WebviewWindow, dry: bool, log_dir: &Path) -> Result<OverlaySnapshot, String> {
        let hwnd = win.hwnd().map_err(|e| format!("hwnd unavailable: {e}"))?.0 as HWND;
        unsafe {
            let taskbar_msg_name = encode_wide("TaskbarCreated");
            let taskbar_created_msg = RegisterWindowMessageW(taskbar_msg_name.as_ptr());
            if taskbar_created_msg != 0 {
                // FocusDNS runs elevated while Explorer runs at medium integrity; allow
                // Explorer's TaskbarCreated broadcast through UIPI.
                let allowed = ChangeWindowMessageFilterEx(
                    hwnd,
                    taskbar_created_msg,
                    MSGFLT_ALLOW,
                    std::ptr::null_mut(),
                );
                if allowed == 0 {
                    append_log(log_dir, "overlay: ChangeWindowMessageFilterEx(TaskbarCreated) failed");
                }
            }

            let state = Box::new(SubclassState {
                taskbar_created_msg,
                dry,
                log_dir: log_dir.to_path_buf(),
                in_repair: Cell::new(false),
            });
            let state_ptr = Box::into_raw(state);
            if SetWindowSubclass(hwnd, Some(overlay_subclass_proc), OVERLAY_SUBCLASS_ID, state_ptr as usize) == 0 {
                let _ = Box::from_raw(state_ptr);
                return Err("SetWindowSubclass failed".into());
            }

            apply_embedded_icon(hwnd, log_dir);
            apply_click_through_hwnd(hwnd, true);
            Ok(enforce_overlay_hwnd(hwnd, dry, false, None, "init"))
        }
    }

    /// Re-asserts overlay styles, click-through, topmost z-order, and top-center position
    /// immediately after `win.show()` in case Tauri's show path reset any Win32 flags.
    pub fn finalize_overlay_after_show(win: &WebviewWindow, dry: bool, log_dir: &Path) -> Result<OverlaySnapshot, String> {
        let hwnd = win.hwnd().map_err(|e| format!("hwnd unavailable: {e}"))?.0 as HWND;
        unsafe {
            apply_click_through_hwnd(hwnd, true);
            Ok(enforce_overlay_hwnd(hwnd, dry, true, Some(log_dir), "post_show"))
        }
    }

    /// Toggles click-through on the UI thread without invoking tao's `WindowFlags::apply_diff`,
    /// which would otherwise re-apply `WS_EX_APPWINDOW` and drop `WS_EX_TOOLWINDOW`.
    pub fn set_click_through(win: &WebviewWindow, click_through: bool) -> Result<(), String> {
        let hwnd = win.hwnd().map_err(|e| e.to_string())?.0 as isize;
        win.run_on_main_thread(move || unsafe {
            apply_click_through_hwnd(hwnd as HWND, click_through);
        })
        .map_err(|e| e.to_string())
    }

    /// Lightweight health check suitable for calling every few seconds from `hover_loop`.
    /// Posts an async repair to the UI thread only when drift is detected.
    pub fn check_overlay_health(win: &WebviewWindow, dry: bool) {
        let Ok(raw) = win.hwnd() else { return };
        let hwnd = raw.0 as HWND;
        unsafe {
            let snap = read_snapshot(hwnd);
            let expected = expected_pos_for_hwnd(hwnd, dry).unwrap_or((snap.x, snap.y));
            if needs_overlay_repair(
                snap.visible,
                snap.minimized,
                snap.style,
                snap.ex_style,
                (snap.x, snap.y),
                expected,
            ) {
                PostMessageW(hwnd, WM_APP_REPAIR, REPAIR_REASON_HEALTH, 0);
            }
        }
    }

    /// Requests an asynchronous overlay repair pass on the UI thread (safe from any thread).
    pub fn request_repair(win: &WebviewWindow, reason: usize) {
        if let Ok(raw) = win.hwnd() {
            unsafe {
                PostMessageW(raw.0 as HWND, WM_APP_REPAIR, reason, 0);
            }
        }
    }
}

#[cfg(windows)]
pub use win32::*;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanitize_style_strips_minimize_and_maximize_boxes_only() {
        let base = 0x1600_0000u32; // WS_VISIBLE | WS_CLIPSIBLINGS | WS_CLIPCHILDREN
        let input = base | STYLE_MINIMIZEBOX | STYLE_MAXIMIZEBOX;
        assert_eq!(sanitize_style(input), base);
        assert_eq!(sanitize_style(base), base);
    }

    #[test]
    fn sanitize_ex_style_enforces_toolwindow_and_strips_appwindow() {
        let initial = EX_STYLE_APPWINDOW | EX_STYLE_TOPMOST;
        let out = sanitize_ex_style(initial);
        assert_ne!(out & EX_STYLE_TOOLWINDOW, 0);
        assert_eq!(out & EX_STYLE_APPWINDOW, 0);
        assert_ne!(out & EX_STYLE_TOPMOST, 0);
    }

    #[test]
    fn click_through_toggle_never_drops_toolwindow_or_adds_appwindow() {
        let corrupted = EX_STYLE_APPWINDOW | EX_STYLE_TOPMOST;
        let passthrough = apply_click_through_ex_style(corrupted, true);
        assert_ne!(passthrough & EX_STYLE_TOOLWINDOW, 0);
        assert_eq!(passthrough & EX_STYLE_APPWINDOW, 0);
        assert_ne!(passthrough & EX_STYLE_TRANSPARENT, 0);
        assert_ne!(passthrough & EX_STYLE_LAYERED, 0);
        assert_ne!(passthrough & EX_STYLE_TOPMOST, 0);

        let interactive = apply_click_through_ex_style(passthrough, false);
        assert_ne!(interactive & EX_STYLE_TOOLWINDOW, 0);
        assert_eq!(interactive & EX_STYLE_APPWINDOW, 0);
        assert_eq!(interactive & EX_STYLE_TRANSPARENT, 0);
        assert_eq!(interactive & EX_STYLE_LAYERED, 0);
        assert_ne!(interactive & EX_STYLE_TOPMOST, 0);
    }

    #[test]
    fn overlay_styles_valid_checks_all_invariants() {
        let good_style = 0x1400_0000;
        let good_ex = EX_STYLE_TOOLWINDOW | EX_STYLE_TOPMOST;
        assert!(overlay_styles_valid(good_style, good_ex));
        assert!(!overlay_styles_valid(good_style | STYLE_MINIMIZEBOX, good_ex));
        assert!(!overlay_styles_valid(good_style | STYLE_MAXIMIZEBOX, good_ex));
        assert!(!overlay_styles_valid(good_style, EX_STYLE_TOPMOST));
        assert!(!overlay_styles_valid(good_style, good_ex | EX_STYLE_APPWINDOW));
        assert!(!overlay_styles_valid(good_style, EX_STYLE_TOOLWINDOW));
    }

    #[test]
    fn syscommand_blocks_minimize_and_maximize_including_low_bits() {
        assert!(should_block_syscommand(SYS_CMD_MINIMIZE));
        assert!(should_block_syscommand(SYS_CMD_MINIMIZE | 0x0002));
        assert!(should_block_syscommand(SYS_CMD_MAXIMIZE));
        assert!(should_block_syscommand(SYS_CMD_MAXIMIZE | 0x000F));
        assert!(!should_block_syscommand(0xF060)); // SC_CLOSE
        assert!(!should_block_syscommand(0xF010)); // SC_MOVE
        assert!(!should_block_syscommand(0xF100)); // SC_KEYMENU
    }

    #[test]
    fn top_center_position_handles_normal_dry_run_and_offset_monitor() {
        assert_eq!(top_center_position(0, 0, 1920, 820, false), (550, 0));
        assert_eq!(top_center_position(0, 0, 1920, 820, true), (550, 90));
        assert_eq!(top_center_position(-1920, 100, 1920, 820, false), (-1370, 100));
        assert_eq!(top_center_position(100, 200, 2560, 820, true), (970, 290));
    }

    #[test]
    fn needs_overlay_repair_detects_each_drift_condition() {
        let style = 0x1400_0000;
        let ex = EX_STYLE_TOOLWINDOW | EX_STYLE_TOPMOST;
        let pos = (550, 0);
        assert!(!needs_overlay_repair(true, false, style, ex, pos, pos));
        assert!(needs_overlay_repair(false, false, style, ex, pos, pos));
        assert!(needs_overlay_repair(true, true, style, ex, pos, pos));
        assert!(needs_overlay_repair(true, false, style | STYLE_MINIMIZEBOX, ex, pos, pos));
        assert!(needs_overlay_repair(true, false, style, ex | EX_STYLE_APPWINDOW, pos, pos));
        assert!(needs_overlay_repair(true, false, style, EX_STYLE_TOPMOST, pos, pos));
        assert!(needs_overlay_repair(true, false, style, EX_STYLE_TOOLWINDOW, pos, pos));
        assert!(needs_overlay_repair(true, false, style, ex, (0, 0), pos));
    }

    #[test]
    fn wait_for_shell_returns_immediately_when_ready() {
        let mut sleeps = 0;
        let res = wait_for_shell_with(
            Duration::from_secs(60),
            Duration::from_millis(250),
            || ShellStatus { has_tray: true, has_monitor: true },
            |_| sleeps += 1,
            || Duration::ZERO,
        );
        assert!(res.ready);
        assert_eq!(res.attempts, 1);
        assert_eq!(sleeps, 0);
    }

    #[test]
    fn wait_for_shell_polls_until_both_tray_and_monitor_appear() {
        let clock = std::cell::Cell::new(Duration::ZERO);
        let mut checks = 0u32;
        let res = wait_for_shell_with(
            Duration::from_secs(10),
            Duration::from_millis(250),
            || {
                checks += 1;
                match checks {
                    1 => ShellStatus { has_tray: false, has_monitor: false },
                    2 => ShellStatus { has_tray: true, has_monitor: false },
                    _ => ShellStatus { has_tray: true, has_monitor: true },
                }
            },
            |d| clock.set(clock.get() + d),
            || clock.get(),
        );
        assert!(res.ready);
        assert_eq!(res.attempts, 3);
        assert_eq!(res.waited, Duration::from_millis(500));
    }

    #[test]
    fn wait_for_shell_times_out_cleanly_with_last_status() {
        let clock = std::cell::Cell::new(Duration::ZERO);
        let res = wait_for_shell_with(
            Duration::from_millis(600),
            Duration::from_millis(250),
            || ShellStatus { has_tray: false, has_monitor: true },
            |d| clock.set(clock.get() + d),
            || clock.get(),
        );
        assert!(!res.ready);
        assert_eq!(res.status, ShellStatus { has_tray: false, has_monitor: true });
        assert_eq!(res.waited, Duration::from_millis(600));
        assert_eq!(res.attempts, 4);
    }

    #[test]
    fn format_panic_report_collapses_multiline_messages_and_handles_missing_location() {
        let with_loc = format_panic_report("main", "line1\r\nline2\nline3", Some(("app/src/main.rs", 42, 5)));
        assert_eq!(with_loc, "panic in thread 'main' at app/src/main.rs:42:5: line1 | line2 | line3");

        let no_loc = format_panic_report("worker", "   ", None);
        assert_eq!(no_loc, "panic in thread 'worker': <empty panic message>");
    }

    #[cfg(windows)]
    #[test]
    fn win32_constants_match_windows_sys() {
        use windows_sys::Win32::UI::WindowsAndMessaging as wm;
        assert_eq!(STYLE_MINIMIZEBOX, wm::WS_MINIMIZEBOX);
        assert_eq!(STYLE_MAXIMIZEBOX, wm::WS_MAXIMIZEBOX);
        assert_eq!(EX_STYLE_TOPMOST, wm::WS_EX_TOPMOST);
        assert_eq!(EX_STYLE_TRANSPARENT, wm::WS_EX_TRANSPARENT);
        assert_eq!(EX_STYLE_TOOLWINDOW, wm::WS_EX_TOOLWINDOW);
        assert_eq!(EX_STYLE_APPWINDOW, wm::WS_EX_APPWINDOW);
        assert_eq!(EX_STYLE_LAYERED, wm::WS_EX_LAYERED);
        assert_eq!(SYS_CMD_MINIMIZE, wm::SC_MINIMIZE as usize);
        assert_eq!(SYS_CMD_MAXIMIZE, wm::SC_MAXIMIZE as usize);
    }
}
