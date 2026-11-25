fn git_out(args: &[&str]) -> Option<String> {
    std::process::Command::new("git")
        .args(args)
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|s| !s.is_empty())
}

fn main() {
    println!("cargo:rerun-if-env-changed=FOCUS_BUILD_ID");
    println!("cargo:rerun-if-env-changed=FOCUS_COMMIT_SHA");
    println!("cargo:rerun-if-env-changed=FOCUS_BUILT_AT");
    println!("cargo:rerun-if-env-changed=FOCUS_UPDATER_PUBKEY");
    println!("cargo:rerun-if-env-changed=FOCUS_UPDATER_REPO");

    let build = std::env::var("FOCUS_BUILD_ID")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .or_else(|| git_out(&["describe", "--always", "--dirty"]))
        .unwrap_or_else(|| "unknown".into());
    let commit = std::env::var("FOCUS_COMMIT_SHA")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .or_else(|| git_out(&["rev-parse", "HEAD"]))
        .unwrap_or_else(|| "unknown".into());
    let built_at = std::env::var("FOCUS_BUILT_AT").unwrap_or_default();
    let pubkey = std::env::var("FOCUS_UPDATER_PUBKEY")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| {
            "41295ba8a207db60efaba6b5bb38d66f116af7c251e3f43b5ca35d324cfd7957".into()
        });

    println!("cargo:rustc-env=FOCUS_BUILD_ID={build}");
    println!("cargo:rustc-env=FOCUS_COMMIT_SHA={commit}");
    println!("cargo:rustc-env=FOCUS_BUILT_AT={built_at}");
    println!("cargo:rustc-env=FOCUS_UPDATER_PUBKEY={pubkey}");
    let repo = std::env::var("FOCUS_UPDATER_REPO").unwrap_or_default();
    println!("cargo:rustc-env=FOCUS_UPDATER_REPO={}", repo.trim());
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        println!("cargo:rerun-if-env-changed=FOCUS_PEEK_WORKER");
        let bytes = std::env::var_os("FOCUS_PEEK_WORKER")
            .map(|path| {
                let path = std::path::PathBuf::from(path);
                println!("cargo:rerun-if-changed={}", path.display());
                std::fs::read(path).expect("read the built Focus Peek worker")
            })
            .unwrap_or_default();
        let out = std::path::PathBuf::from(std::env::var_os("OUT_DIR").expect("OUT_DIR"));
        std::fs::write(out.join("peek-worker.exe"), bytes).expect("bundle the Focus Peek worker");
        tauri_build::build();
    }
}
