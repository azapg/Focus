use std::collections::BTreeMap;
type Metrics = BTreeMap<&'static str, u64>;

#[derive(Default)]
pub(super) struct Sample {
    cumulative: Metrics,
    gauges: Metrics,
}

impl Sample {
    pub fn metrics_since(&self, previous: &Self) -> (Metrics, &Metrics) {
        let counters = self
            .cumulative
            .iter()
            .filter_map(|(&name, &now)| {
                now.checked_sub(*previous.cumulative.get(name)?)
                    .map(|delta| (name, delta))
            })
            .collect();
        (counters, &self.gauges)
    }

    #[cfg(windows)]
    pub fn read() -> Self {
        use windows_sys::Win32::{
            Foundation::{CloseHandle, FILETIME, INVALID_HANDLE_VALUE},
            System::{
                Diagnostics::ToolHelp::{
                    CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W,
                    TH32CS_SNAPPROCESS,
                },
                ProcessStatus::{K32GetProcessMemoryInfo, PROCESS_MEMORY_COUNTERS_EX},
                Threading::{
                    GetCurrentProcess, GetProcessHandleCount, GetProcessIoCounters,
                    GetProcessTimes, IO_COUNTERS,
                },
            },
        };
        let mut sample = Self::default();
        // All handles and structures are local to this sample.
        unsafe {
            let process = GetCurrentProcess();
            let mut times: [FILETIME; 4] = std::mem::zeroed();
            let [created, exited, kernel, user] = &mut times;
            if GetProcessTimes(process, created, exited, kernel, user) != 0 {
                let ticks =
                    |t: &FILETIME| (u64::from(t.dwHighDateTime) << 32) | u64::from(t.dwLowDateTime);
                sample
                    .cumulative
                    .insert("process.cpu_us", (ticks(kernel) + ticks(user)) / 10);
            }
            let mut memory: PROCESS_MEMORY_COUNTERS_EX = std::mem::zeroed();
            memory.cb = std::mem::size_of_val(&memory) as u32;
            if K32GetProcessMemoryInfo(
                process,
                (&mut memory as *mut PROCESS_MEMORY_COUNTERS_EX).cast(),
                memory.cb,
            ) != 0
            {
                sample
                    .gauges
                    .insert("process.working_set_bytes", memory.WorkingSetSize as u64);
                sample
                    .gauges
                    .insert("process.private_bytes", memory.PrivateUsage as u64);
            }
            let mut handles = 0;
            if GetProcessHandleCount(process, &mut handles) != 0 {
                sample.gauges.insert("process.handles", u64::from(handles));
            }
            let mut io: IO_COUNTERS = std::mem::zeroed();
            if GetProcessIoCounters(process, &mut io) != 0 {
                sample
                    .cumulative
                    .insert("process.io_writes", io.WriteOperationCount);
                sample
                    .cumulative
                    .insert("process.io_write_bytes", io.WriteTransferCount);
            }
            let snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
            if snapshot != INVALID_HANDLE_VALUE {
                let mut entry: PROCESSENTRY32W = std::mem::zeroed();
                entry.dwSize = std::mem::size_of_val(&entry) as u32;
                let mut ok = Process32FirstW(snapshot, &mut entry);
                while ok != 0 {
                    if entry.th32ProcessID == std::process::id() {
                        sample
                            .gauges
                            .insert("process.threads", u64::from(entry.cntThreads));
                        break;
                    }
                    ok = Process32NextW(snapshot, &mut entry);
                }
                CloseHandle(snapshot);
            }
        }
        sample
    }

    #[cfg(target_os = "linux")]
    pub fn read() -> Self {
        let mut sample = Self::default();
        let mut time = std::mem::MaybeUninit::uninit();
        if unsafe { libc::clock_gettime(libc::CLOCK_PROCESS_CPUTIME_ID, time.as_mut_ptr()) } == 0 {
            let time = unsafe { time.assume_init() };
            sample.cumulative.insert(
                "process.cpu_us",
                time.tv_sec as u64 * 1_000_000 + time.tv_nsec as u64 / 1_000,
            );
        }
        if let Ok(status) = std::fs::read_to_string("/proc/self/status") {
            for (key, name, scale) in [
                ("VmRSS:", "process.working_set_bytes", 1024),
                ("Threads:", "process.threads", 1),
            ] {
                if let Some(value) = proc_value(&status, key) {
                    sample.gauges.insert(name, value * scale);
                }
            }
        }
        if let Ok(io) = std::fs::read_to_string("/proc/self/io") {
            for (key, name) in [
                ("syscw:", "process.io_writes"),
                ("wchar:", "process.io_write_bytes"),
                ("write_bytes:", "process.storage_write_bytes"),
            ] {
                if let Some(value) = proc_value(&io, key) {
                    sample.cumulative.insert(name, value);
                }
            }
        }
        sample
    }

    #[cfg(not(any(windows, target_os = "linux")))]
    pub fn read() -> Self {
        Self::default()
    }
}

#[cfg(target_os = "linux")]
fn proc_value(file: &str, key: &str) -> Option<u64> {
    file.lines()
        .find_map(|line| line.strip_prefix(key))?
        .split_whitespace()
        .next()?
        .parse()
        .ok()
}
