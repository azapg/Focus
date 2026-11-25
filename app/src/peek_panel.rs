use crate::{now_ms, Core};
#[path = "peek_process.rs"]
mod process;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    ffi::OsString,
    fs,
    mem::size_of,
    os::windows::{
        ffi::{OsStrExt, OsStringExt},
        fs::MetadataExt,
        io::{AsRawHandle, FromRawHandle, OwnedHandle},
    },
    path::{Path, PathBuf},
    ptr,
    sync::{Arc, Mutex},
};
use tauri::State;
use windows_sys::Win32::{
    Foundation::LocalFree,
    Security::{
        Authorization::{
            ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW,
        },
        GetTokenInformation, TokenUser, SECURITY_ATTRIBUTES, TOKEN_QUERY, TOKEN_USER,
    },
    Storage::FileSystem::{CreateDirectoryW, FILE_ATTRIBUTE_REPARSE_POINT},
    System::{
        Com::CoTaskMemFree,
        Threading::{GetCurrentProcess, OpenProcessToken},
    },
    UI::Shell::{FOLDERID_ProgramFiles, SHGetKnownFolderPath},
};

const WORKER: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/peek-worker.exe"));
const MAX_REPLY: usize = 1024 * 1024;
static FETCH: Mutex<()> = Mutex::new(());

#[tauri::command]
pub async fn peek_link(url: String, core: State<'_, Arc<Core>>) -> Result<Value, String> {
    if core.dry {
        return Ok(json!({ "refusal": "protection_off" }));
    }
    let core = core.inner().clone();
    Ok(tauri::async_runtime::spawn_blocking(move || {
        let _active = FETCH.try_lock().ok()?;
        fetch(url.trim(), &core)
    })
    .await
    .ok()
    .flatten()
    .unwrap_or_else(|| json!({ "refusal": "unavailable" })))
}

fn installation() -> Option<PathBuf> {
    let mut raw = ptr::null_mut();
    let result =
        unsafe { SHGetKnownFolderPath(&FOLDERID_ProgramFiles, 0, ptr::null_mut(), &mut raw) };
    if result < 0 || raw.is_null() {
        return None;
    }
    let root = unsafe {
        let len = (0..).find(|&i| *raw.add(i) == 0).unwrap();
        let path = OsString::from_wide(std::slice::from_raw_parts(raw, len));
        CoTaskMemFree(raw.cast());
        PathBuf::from(path)
    };
    let dir = root.canonicalize().ok()?.join("FocusDNS");
    // The elevated app never launches the separately installed, user-writable
    // browser host. Only embedded bytes in the admin-owned installation run.
    let exe = std::env::current_exe().ok()?.canonicalize().ok()?;
    let metadata = fs::symlink_metadata(&dir).ok()?;
    (metadata.is_dir()
        && metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT == 0
        && exe.parent() == Some(dir.as_path()))
    .then_some(dir)
}

fn history_root(dir: &Path, data_dir: &Path) -> Option<PathBuf> {
    let identity: Vec<u8> = data_dir
        .as_os_str()
        .encode_wide()
        .flat_map(u16::to_le_bytes)
        .collect();
    let parent = dir.join("peek-history");
    let _ = fs::create_dir(&parent);
    if !regular_directory(&parent) {
        return None;
    }
    let account = parent.join(format!("{:x}", Sha256::digest(identity)));
    if !account.exists() {
        create_private_directory(&account)?;
    }
    regular_directory(&account).then_some(account)
}

pub(crate) fn timestamps(data_dir: &Path, from: u64, to: u64) -> Vec<u64> {
    installation()
        .and_then(|dir| history_root(&dir, data_dir))
        .map(|path| focus_browser_host::peek_history::timestamps(&path, from, to))
        .unwrap_or_default()
}

fn regular_directory(path: &Path) -> bool {
    fs::symlink_metadata(path).is_ok_and(|metadata| {
        metadata.is_dir() && metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT == 0
    })
}

fn create_private_directory(path: &Path) -> Option<()> {
    let mut raw = ptr::null_mut();
    if unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut raw) } == 0 {
        return None;
    }
    let token = unsafe { OwnedHandle::from_raw_handle(raw) };
    let mut bytes = 0;
    unsafe {
        GetTokenInformation(
            token.as_raw_handle(),
            TokenUser,
            ptr::null_mut(),
            0,
            &mut bytes,
        )
    };
    let mut user = vec![0usize; (bytes as usize).div_ceil(size_of::<usize>())];
    if unsafe {
        GetTokenInformation(
            token.as_raw_handle(),
            TokenUser,
            user.as_mut_ptr().cast(),
            bytes,
            &mut bytes,
        )
    } == 0
    {
        return None;
    }
    let mut sid = ptr::null_mut();
    if unsafe { ConvertSidToStringSidW((*user.as_ptr().cast::<TOKEN_USER>()).User.Sid, &mut sid) }
        == 0
    {
        return None;
    }
    let description: Vec<u16> = unsafe {
        let len = (0..).find(|&i| *sid.add(i) == 0)?;
        let account = String::from_utf16_lossy(std::slice::from_raw_parts(sid, len));
        LocalFree(sid.cast());
        // Standard users cannot change journal files or their ACLs, including
        // the account that owns the directory. Elevated writes require the
        // Administrators group. This account can still read its own history.
        format!("D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;FR;;;{account})(A;OICI;FR;;;OW)")
            .encode_utf16()
            .chain([0])
            .collect()
    };
    let mut descriptor = ptr::null_mut();
    if unsafe {
        ConvertStringSecurityDescriptorToSecurityDescriptorW(
            description.as_ptr(),
            1,
            &mut descriptor,
            ptr::null_mut(),
        )
    } == 0
    {
        return None;
    }
    let attributes = SECURITY_ATTRIBUTES {
        nLength: size_of::<SECURITY_ATTRIBUTES>() as u32,
        lpSecurityDescriptor: descriptor,
        bInheritHandle: 0,
    };
    let path: Vec<u16> = path.as_os_str().encode_wide().chain([0]).collect();
    let result = unsafe { CreateDirectoryW(path.as_ptr(), &attributes) };
    unsafe { LocalFree(descriptor) };
    (result != 0).then_some(())
}

fn fetch(url: &str, core: &Core) -> Option<Value> {
    if WORKER.is_empty() || url.len() > 4096 {
        return None;
    }
    let profile = core.dns_profile()?;
    let dir = installation()?;
    let path = dir.join("focus-peek-worker.exe");
    let current = fs::symlink_metadata(&path).ok().is_some_and(|meta| {
        meta.is_file()
            && meta.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT == 0
            && meta.len() == WORKER.len() as u64
    });
    if !current || fs::read(&path).ok().as_deref() != Some(WORKER) {
        focus_browser_host::atomic_write(&path, WORKER).ok()?;
    }
    let mut child = process::Worker::launch(&path, &dir)?;
    let result = (|| {
        let input = &mut child.input;
        let output = &mut child.output;
        focus_browser_host::write_frame(input, &json!({ "url": url, "profile": profile })).ok()?;
        for _ in 0..4 {
            let bytes = focus_browser_host::read_frame_bounded(output, MAX_REPLY).ok()??;
            let reply: Value = serde_json::from_slice(&bytes).ok()?;
            if reply == json!({ "check_protection": true }) {
                let protected = core.peek_protected();
                focus_browser_host::write_frame(input, &protected).ok()?;
            } else {
                return Some(reply);
            }
        }
        None
    })();
    if child.finish(result.is_some()) != Some(0) {
        return None;
    }
    let result = result?;
    if result.get("snapshot").is_some_and(Value::is_object) {
        if core.view().paused_until != 0 {
            return Some(json!({ "refusal": "protection_off" }));
        }
        if let Some(history) = history_root(&dir, &core.dir) {
            let _ = focus_browser_host::peek_history::record(&history, now_ms());
        }
    }
    Some(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn restricted_worker_exchanges_frames_and_refuses_a_feed() {
        let Some(path) = std::env::var_os("FOCUS_PEEK_WORKER").map(PathBuf::from) else {
            return; // Windows CI supplies the executable.
        };
        let mut worker = process::Worker::launch(&path, path.parent().unwrap())
            .expect("launch the restricted Peek worker");
        focus_browser_host::write_frame(
            &mut worker.input,
            &json!({ "url": "https://x.com/explore", "profile": "a1b2c3" }),
        )
        .unwrap();
        let read = |worker: &mut process::Worker| -> Option<Value> {
            let bytes =
                focus_browser_host::read_frame_bounded(&mut worker.output, MAX_REPLY).unwrap()?;
            Some(serde_json::from_slice(&bytes).unwrap())
        };
        let Some(first) = read(&mut worker) else {
            panic!(
                "worker exited before checking protection: {:x?}",
                worker.finish(true)
            );
        };
        assert_eq!(first, json!({ "check_protection": true }));
        focus_browser_host::write_frame(&mut worker.input, &true).unwrap();
        assert_eq!(read(&mut worker), Some(json!({ "refusal": "permalink" })));
        assert_eq!(worker.finish(true), Some(0));
    }
}
