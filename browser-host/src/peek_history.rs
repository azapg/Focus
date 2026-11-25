//! Peek history and the one-shot request to bring up the existing pause controls.
use chrono::TimeZone;
use chrono::{DateTime, Local};
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, File, Metadata, OpenOptions},
    io::Read,
    path::Path,
};

use std::{
    io::{self, Write},
    path::PathBuf,
};

const PAUSE_REQUEST: &str = "browser/peek-pause";
const PAUSE_REQUEST_TTL: u64 = 10_000;
const MAX_DAILY_HISTORY: u64 = 2 * 1024 * 1024;

#[derive(Deserialize, Serialize)]
struct PeekEvent {
    t: u64,
}

fn history_file(dir: &Path, day: chrono::NaiveDate) -> PathBuf {
    dir.join("browser/peeks").join(format!("{day}.jsonl"))
}

pub fn record(dir: &Path, t: u64) -> io::Result<()> {
    let day = Local
        .timestamp_millis_opt(t as i64)
        .single()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "invalid Peek event time"))?;
    let path = history_file(dir, day.date_naive());
    fs::create_dir_all(path.parent().unwrap())?;
    let mut line = serde_json::to_vec(&PeekEvent { t })?;
    line.push(b'\n');
    OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)?
        .write_all(&line)
}

fn regular_file(metadata: &Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        use windows_sys::Win32::Storage::FileSystem::FILE_ATTRIBUTE_REPARSE_POINT;
        if metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0 {
            return false;
        }
    }
    metadata.is_file()
}

fn bounded_text(path: &Path, limit: u64) -> Option<String> {
    if !regular_file(&fs::symlink_metadata(path).ok()?) {
        return None;
    }
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        use windows_sys::Win32::Storage::FileSystem::FILE_FLAG_OPEN_REPARSE_POINT;
        options.custom_flags(FILE_FLAG_OPEN_REPARSE_POINT);
    }
    let file: File = options.open(path).ok()?;
    let metadata = file.metadata().ok()?;
    if !regular_file(&metadata) || metadata.len() > limit {
        return None;
    }
    let mut text = String::new();
    file.take(limit + 1).read_to_string(&mut text).ok()?;
    (text.len() as u64 <= limit).then_some(text)
}

pub fn timestamps(dir: &Path, from: u64, to: u64) -> Vec<u64> {
    let date = |t| {
        DateTime::from_timestamp_millis(i64::try_from(t).ok()?)
            .map(|dt| dt.with_timezone(&Local).date_naive())
    };
    let (Some(start), Some(end)) = (date(from), date(to.saturating_sub(1))) else {
        return Vec::new();
    };
    if from >= to {
        return Vec::new();
    }
    let Ok(entries) = fs::read_dir(dir.join("browser/peeks")) else {
        return Vec::new();
    };
    entries
        .filter_map(Result::ok)
        .filter(|entry| {
            entry
                .path()
                .file_stem()
                .and_then(|s| s.to_str())
                .and_then(|s| s.parse::<chrono::NaiveDate>().ok())
                .is_some_and(|day| day >= start && day <= end)
        })
        .filter_map(|entry| bounded_text(&entry.path(), MAX_DAILY_HISTORY))
        .flat_map(|text| {
            text.lines()
                .filter_map(|line| serde_json::from_str::<PeekEvent>(line).ok())
                .map(|e| e.t)
                .filter(|t| (from..to).contains(t))
                .collect::<Vec<_>>()
        })
        .collect()
}

#[cfg(feature = "peek")]
pub(crate) fn request_pause(dir: &Path, now: u64) -> io::Result<()> {
    crate::atomic_write(&dir.join(PAUSE_REQUEST), now.to_string().as_bytes())
}

pub fn take_pause_request(dir: &Path, now: u64) -> bool {
    let path = dir.join(PAUSE_REQUEST);
    // Consume the exact file we read. A request that arrives concurrently
    // remains under the original name for the following poll.
    let claimed = path.with_extension("claimed");
    if !fs::symlink_metadata(&path).is_ok_and(|metadata| regular_file(&metadata))
        || fs::rename(&path, &claimed).is_err()
    {
        return false;
    }
    let valid = bounded_text(&claimed, 24)
        .and_then(|text| text.parse::<u64>().ok())
        .is_some_and(|at| at <= now && now - at <= PAUSE_REQUEST_TTL);
    let _ = fs::remove_file(claimed);
    valid
}

#[cfg(all(test, feature = "peek"))]
mod tests {
    use super::*;

    #[test]
    fn only_success_timestamps_and_fresh_pause_requests_are_read() {
        let t = crate::now_ms();
        let dir =
            std::env::temp_dir().join(format!("focus-peek-history-{}-{t}", std::process::id()));
        assert!(timestamps(&dir, t, t + 1).is_empty());
        record(&dir, t).unwrap();
        assert_eq!(timestamps(&dir, t, t + 1), vec![t]);
        assert!(timestamps(&dir, t + 1, t + 2).is_empty());

        request_pause(&dir, t).unwrap();
        assert!(take_pause_request(&dir, t + 100));
        assert!(!take_pause_request(&dir, t + 101));
        request_pause(&dir, t).unwrap();
        assert!(!take_pause_request(&dir, t + PAUSE_REQUEST_TTL + 1));
        fs::remove_dir_all(dir).unwrap();
    }
}
