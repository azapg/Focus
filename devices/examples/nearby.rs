//! Diagnostic: public nearby names only, using a disposable registry.
use focus_devices::DeviceManager;
use std::{thread, time::Duration};
fn main() {
    let dir = tempfile::tempdir().unwrap();
    let manager = DeviceManager::open(dir.path()).unwrap();
    for _ in 0..20 {
        let peers = manager.nearby().expect("Network discovery unavailable");
        if !peers.is_empty() {
            println!("{}", serde_json::to_string(&peers).unwrap());
            return;
        }
        thread::sleep(Duration::from_secs(1));
    }
    eprintln!("No nearby Focus TV responded within 20 seconds");
    std::process::exit(1);
}
