//! Local resource counters. Register only fixed metric names and pass only counts,
//! byte lengths or elapsed time. Each sampler is driven by a component's existing work.
mod process;

use std::{
    fs::{self, OpenOptions},
    io::{self, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering::Relaxed},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

pub struct Counter(AtomicU64);

impl Counter {
    pub fn count(&self, amount: u64) {
        self.0.fetch_add(amount, Relaxed);
    }
}

macro_rules! counters {
    ($($counter:ident => $name:literal),+ $(,)?) => {
        $(pub static $counter: Counter = Counter(AtomicU64::new(0));)+
        const COUNTERS: &[(&str, &Counter)] = &[$(($name, &$counter)),+];
    };
}

counters! {
    DNS_TICK => "dns_watchdog.callbacks",
    DNS_SPAWN => "dns_watchdog.powershell_attempts",
    DNS_COMMAND_MS => "dns_watchdog.command_ms",
    DNS_REQUEST => "dns_watchdog.requests",
    DNS_RX => "dns_watchdog.rx_bytes",
    ACTIVITY_TICK => "activity.callbacks",
    FOCUS_EVAL => "focus_loss.evaluations",
    FOCUS_EVAL_US => "focus_loss.evaluation_us",
    ACTIVITY_WRITE => "activity.file_writes",
    ACTIVITY_WRITE_BYTES => "activity.file_bytes",
    OVERLAY_TICK => "overlay.callbacks",
    DEVICE_TICK => "device_sync.callbacks",
    DEVICE_REQUEST => "device_sync.requests",
    DEVICE_RX => "device_sync.rx_bytes",
    DEVICE_TX => "device_sync.tx_bytes",
    DEVICE_SERVER_TICK => "device_listener.callbacks",
    DEVICE_INBOUND => "device_listener.requests",
    DEVICE_INBOUND_RX => "device_listener.rx_bytes",
    DEVICE_INBOUND_TX => "device_listener.tx_bytes",
    UPDATER_TICK => "updater.callbacks",
    UPDATER_REQUEST => "updater.requests",
    UPDATER_RX => "updater.rx_bytes",
    HOST_MESSAGE => "native_bridge.messages_in",
    HOST_MESSAGE_OUT => "native_bridge.messages_out",
    HOST_PROTECTION_TICK => "native_protection.callbacks",
    HOST_RX => "native_bridge.rx_bytes",
    HOST_WRITE => "native_bridge.file_writes",
    HOST_WRITE_BYTES => "native_bridge.file_bytes",
}

pub enum Component {
    Desktop,
    BrowserHost,
}

impl Component {
    fn name(&self) -> &'static str {
        match self {
            Self::Desktop => "desktop",
            Self::BrowserHost => "browser-host",
        }
    }

    fn counters(&self) -> impl Iterator<Item = (&'static str, &'static Counter)> + '_ {
        COUNTERS
            .iter()
            .copied()
            .filter(|(name, _)| name.starts_with("native_") == matches!(self, Self::BrowserHost))
    }
}

const INTERVAL: Duration = Duration::from_secs(300);
const DAY_MS: u64 = 86_400_000;
const MAX_DAY_BYTES: u64 = 2 * 1024 * 1024;

pub struct Sampler {
    dir: PathBuf,
    component: Component,
    started: Instant,
    previous: process::Sample,
    totals: Vec<u64>,
    pruned_day: Option<u64>,
}

impl Sampler {
    pub fn new(data_dir: &Path, component: Component) -> Self {
        let totals = component
            .counters()
            .map(|(_, c)| c.0.load(Relaxed))
            .collect();
        Self {
            dir: data_dir.join("telemetry").join(component.name()),
            component,
            started: Instant::now(),
            previous: process::Sample::read(),
            totals,
            pruned_day: None,
        }
    }

    pub fn sample_if_due(&mut self) {
        if self.started.elapsed() >= INTERVAL {
            self.flush();
        }
    }

    pub fn finish(mut self) {
        // Preserve short-lived native-host sessions with actual activity.
        if self.started.elapsed() >= Duration::from_secs(1) {
            self.flush();
        }
    }

    fn flush(&mut self) {
        let now = Instant::now();
        let duration_ms = now.duration_since(self.started).as_millis() as u64;
        let previous = std::mem::replace(&mut self.previous, process::Sample::read());
        let (mut counters, gauges) = self.previous.metrics_since(&previous);
        self.started = now;
        for ((name, counter), previous) in self.component.counters().zip(&mut self.totals) {
            let current = counter.0.load(Relaxed);
            counters.insert(name, current.wrapping_sub(*previous));
            *previous = current;
        }
        let Ok(since_epoch) = SystemTime::now().duration_since(UNIX_EPOCH) else {
            return;
        };
        let at_ms = since_epoch.as_millis() as u64;
        let day = at_ms / DAY_MS;
        let row = serde_json::json!({
            "v": 1, "component": self.component.name(),
            "at_ms": at_ms, "duration_ms": duration_ms,
            "counters": counters, "gauges": gauges
        });
        if self.pruned_day != Some(day) && fs::create_dir_all(&self.dir).is_ok() {
            self.pruned_day = Some(day);
            prune(&self.dir, day);
        }
        let _ = append(&self.dir, day, &row);
    }
}

fn prune(dir: &Path, day: u64) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(file_day) = name
            .to_str()
            .and_then(|name| name.strip_suffix(".jsonl"))
            .and_then(|day| day.parse::<u64>().ok())
        else {
            continue;
        };
        if file_day < day.saturating_sub(6) {
            let _ = fs::remove_file(entry.path());
        }
    }
}

fn append(dir: &Path, day: u64, row: &serde_json::Value) -> io::Result<()> {
    let mut bytes = serde_json::to_vec(row)?;
    bytes.push(b'\n');
    let mut file = OpenOptions::new()
        .append(true)
        .create(true)
        .open(dir.join(format!("{day}.jsonl")))?;
    if file.metadata()?.len().saturating_add(bytes.len() as u64) <= MAX_DAY_BYTES {
        // A single append keeps rows together when multiple browser hosts run.
        if file.write(&bytes)? != bytes.len() {
            return Err(io::ErrorKind::WriteZero.into());
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn retains_seven_days_including_today_and_caps_daily_writes() {
        let dir = std::env::temp_dir().join(format!("focus-telemetry-roll-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let row = serde_json::json!({"counters": {"activity.callbacks": 42}});
        for day in [93, 94, 100] {
            append(&dir, day, &row).unwrap();
        }
        prune(&dir, 100);
        assert!(!dir.join("93.jsonl").exists());
        assert!(dir.join("94.jsonl").exists());

        let today = dir.join("100.jsonl");
        assert_eq!(fs::read_to_string(&today).unwrap(), format!("{row}\n"));
        let file = OpenOptions::new().write(true).open(&today).unwrap();
        file.set_len(MAX_DAY_BYTES).unwrap();
        drop(file);
        append(&dir, 100, &row).unwrap();
        assert_eq!(fs::metadata(today).unwrap().len(), MAX_DAY_BYTES);
        fs::remove_dir_all(dir).unwrap();
    }
}
