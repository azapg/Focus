//! Nightly updater for FocusDNS.
//!
//! Checks a rolling GitHub Release (`nightly` by default), verifies an Ed25519-signed
//! manifest and SHA-256 binary hashes, and atomically replaces the installed binary
//! without needing another UAC prompt (since FocusDNS already runs elevated).
//!
//! Works for both public repositories (no token needed) and private repositories
//! (optional read-only GitHub token encrypted at rest with Windows DPAPI).

use crate::Core;
use focus_telemetry as telemetry;
use reqwest::blocking::Client;
use reqwest::header::{ACCEPT, AUTHORIZATION, LOCATION, USER_AGENT};
use reqwest::redirect::Policy;
use ring::signature::{UnparsedPublicKey, ED25519};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File},
    io::{Read, Write},
    os::windows::ffi::OsStrExt,
    path::{Path, PathBuf},
    process::Command,
    sync::{Arc, Mutex},
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tauri::{AppHandle, Emitter, State};
use windows_sys::Win32::Storage::FileSystem::GetDiskFreeSpaceExW;

pub const DEFAULT_REPO: &str = env!("FOCUS_UPDATER_REPO");
pub const DEFAULT_TAG: &str = "nightly";
pub const CURRENT_BUILD_ID: &str = env!("FOCUS_BUILD_ID");
pub const CURRENT_COMMIT_SHA: &str = env!("FOCUS_COMMIT_SHA");
pub const BUILT_AT: &str = env!("FOCUS_BUILT_AT");
pub const EMBEDDED_PUBKEY_HEX: &str = env!("FOCUS_UPDATER_PUBKEY");
const INITIAL_CHECK_DELAY: Duration = Duration::from_secs(20);
const POLL_INTERVAL: Duration = Duration::from_secs(30 * 60);
const MAX_MANIFEST_BYTES: usize = 64 * 1024;
const BINARY_SANITY_LIMIT: u64 = 256 * 1024 * 1024;
const NO_WINDOW: u32 = 0x0800_0000;

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct UpdaterConfig {
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(default = "default_repo")]
    pub repo: String,
    #[serde(default = "default_tag")]
    pub tag: String,
    #[serde(default)]
    pub token_dpapi: Option<String>,
    #[serde(default)]
    pub dismissed_sha: Option<String>,
}

fn default_true() -> bool {
    true
}
fn default_repo() -> String {
    DEFAULT_REPO.to_string()
}
fn default_tag() -> String {
    DEFAULT_TAG.to_string()
}

impl Default for UpdaterConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            repo: DEFAULT_REPO.to_string(),
            tag: DEFAULT_TAG.to_string(),
            token_dpapi: None,
            dismissed_sha: None,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ManifestAsset {
    pub name: String,
    pub size: u64,
    pub sha256: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ManifestAssets {
    pub app: ManifestAsset,
    #[serde(default)]
    pub bridge: Option<ManifestAsset>,
}

/// Plain-language notes for the update popover. Not signed (display only); missing on older builds.
#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq)]
pub struct ReleaseNotes {
    #[serde(default)]
    pub items: Vec<(String, String)>,
    #[serde(default)]
    pub more: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct NightlyManifest {
    pub version: u32,
    pub channel: String,
    pub build_id: String,
    pub commit_sha: String,
    #[serde(default)]
    pub commit_subject: String,
    pub published_at: String,
    pub assets: ManifestAssets,
    pub signature: String,
    #[serde(default)]
    pub notes: ReleaseNotes,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct AvailableUpdate {
    pub build_id: String,
    pub commit_sha: String,
    pub commit_subject: String,
    pub published_at: String,
    pub size_bytes: u64,
    pub dismissed: bool,
    #[serde(default)]
    pub notes: ReleaseNotes,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct UpdateState {
    pub status: String,
    pub current_build_id: String,
    pub current_commit_sha: String,
    pub built_at: String,
    pub dry_run: bool,
    pub enabled: bool,
    pub repo: String,
    pub tag: String,
    pub has_token: bool,
    pub available: Option<AvailableUpdate>,
    pub last_checked_at: Option<u64>,
    pub error: Option<String>,
    pub message: Option<String>,
}

#[derive(Deserialize)]
struct GhRelease {
    assets: Vec<GhAsset>,
}

#[derive(Clone, Deserialize)]
struct GhAsset {
    name: String,
    url: String,
    #[allow(dead_code)]
    size: u64,
}

#[derive(Clone)]
struct PendingRelease {
    manifest: NightlyManifest,
    app_asset_url: String,
    bridge_asset_url: Option<String>,
}

struct Inner {
    config: UpdaterConfig,
    state: UpdateState,
    pending: Option<PendingRelease>,
    busy: bool,
}

pub struct Updater {
    dir: PathBuf,
    dry: bool,
    pubkey_hex: String,
    inner: Mutex<Inner>,
}

impl Updater {
    pub fn open(dir: &Path, dry: bool) -> Self {
        let mut config = load_config(dir);
        if migrate_feed(&mut config, DEFAULT_REPO, option_env!("FOCUS_PREVIOUS_FEED_SHA256").unwrap_or("")) {
            // Use the migrated configuration immediately and retry persistence on the next launch.
            if let Err(error) = save_config(dir, &config) {
                eprintln!("Focus updater: {error}");
            }
        }
        let state = UpdateState {
            status: "idle".into(),
            current_build_id: CURRENT_BUILD_ID.into(),
            current_commit_sha: CURRENT_COMMIT_SHA.into(),
            built_at: BUILT_AT.into(),
            dry_run: dry,
            enabled: config.enabled,
            repo: config.repo.clone(),
            tag: config.tag.clone(),
            has_token: config.token_dpapi.as_deref().is_some_and(|s| !s.is_empty()),
            available: None,
            last_checked_at: None,
            error: None,
            message: None,
        };
        Self {
            dir: dir.to_path_buf(),
            dry,
            pubkey_hex: EMBEDDED_PUBKEY_HEX.into(),
            inner: Mutex::new(Inner {
                config,
                state,
                pending: None,
                busy: false,
            }),
        }
    }

    pub fn snapshot(&self) -> UpdateState {
        self.inner.lock().unwrap().state.clone()
    }

    pub fn is_enabled(&self) -> bool {
        self.inner.lock().unwrap().config.enabled
    }

    fn emit(&self, app: &AppHandle) {
        let _ = app.emit("update", self.snapshot());
    }

    pub fn set_enabled(&self, enabled: bool, app: &AppHandle, core: &Core) -> Result<UpdateState, String> {
        let mut g = self.inner.lock().unwrap();
        g.config.enabled = enabled;
        save_config(&self.dir, &g.config)?;
        g.state.enabled = enabled;
        let out = g.state.clone();
        drop(g);
        core.log(&format!("updater auto-check enabled={enabled}"));
        self.emit(app);
        Ok(out)
    }

    pub fn set_token(&self, token: Option<String>, app: &AppHandle, core: &Core) -> Result<UpdateState, String> {
        let cleaned = token
            .map(|t| t.trim().to_string())
            .filter(|t| !t.is_empty());
        let protected = match cleaned {
            Some(raw) => Some(focus_devices::secrets::protect(&raw)?),
            None => None,
        };
        let mut g = self.inner.lock().unwrap();
        g.config.token_dpapi = protected;
        save_config(&self.dir, &g.config)?;
        g.state.has_token = g.config.token_dpapi.is_some();
        if g.state.status == "needs_auth" && g.state.has_token {
            g.state.status = "idle".into();
            g.state.error = None;
        }
        let out = g.state.clone();
        drop(g);
        core.log(if out.has_token {
            "updater GitHub token saved (DPAPI)"
        } else {
            "updater GitHub token removed"
        });
        self.emit(app);
        Ok(out)
    }

    pub fn dismiss(&self, app: &AppHandle, core: &Core) -> Result<UpdateState, String> {
        let mut g = self.inner.lock().unwrap();
        let dismissed_info = if let Some(avail) = g.state.available.as_mut() {
            avail.dismissed = true;
            Some((avail.commit_sha.clone(), avail.build_id.clone()))
        } else {
            None
        };
        if let Some((sha, build_id)) = dismissed_info {
            g.config.dismissed_sha = Some(sha);
            save_config(&self.dir, &g.config)?;
            core.log(&format!("updater dismissed {build_id}"));
        }
        let out = g.state.clone();
        drop(g);
        self.emit(app);
        Ok(out)
    }

    pub fn check(&self, app: &AppHandle, core: &Core) -> Result<UpdateState, String> {
        let (repo, tag, token_dpapi, dismissed_sha) = {
            let mut g = self.inner.lock().unwrap();
            if g.busy {
                return Ok(g.state.clone());
            }
            g.busy = true;
            g.state.status = "checking".into();
            g.state.error = None;
            g.state.message = None;
            (
                g.config.repo.clone(),
                g.config.tag.clone(),
                g.config.token_dpapi.clone(),
                g.config.dismissed_sha.clone(),
            )
        };
        self.emit(app);

        let result = self.fetch_and_verify(&repo, &tag, token_dpapi.as_deref());
        let checked_at = now_ms();

        let out = {
            let mut g = self.inner.lock().unwrap();
            g.busy = false;
            g.state.last_checked_at = Some(checked_at);
            match result {
                Ok(CheckOutcome::NoRelease) => {
                    g.pending = None;
                    g.state.available = None;
                    g.state.status = "up_to_date".into();
                    g.state.message = Some("No nightly release published yet.".into());
                    core.log("updater check: no nightly release published yet");
                }
                Ok(CheckOutcome::NeedsAuth) => {
                    g.pending = None;
                    g.state.available = None;
                    g.state.status = "needs_auth".into();
                    g.state.error = Some(
                        "Private repo: add a read-only GitHub token in Settings to check nightly updates.".into(),
                    );
                    core.log("updater check: repo requires a GitHub token (401/404)");
                }
                Ok(CheckOutcome::Release(pending)) => {
                    let is_newer = is_update_available(
                        CURRENT_COMMIT_SHA,
                        CURRENT_BUILD_ID,
                        &pending.manifest.commit_sha,
                        &pending.manifest.build_id,
                    );
                    if is_newer {
                        let dismissed = dismissed_sha
                            .as_deref()
                            .is_some_and(|s| s.eq_ignore_ascii_case(&pending.manifest.commit_sha));
                        let avail = AvailableUpdate {
                            build_id: pending.manifest.build_id.clone(),
                            commit_sha: pending.manifest.commit_sha.clone(),
                            commit_subject: pending.manifest.commit_subject.clone(),
                            published_at: pending.manifest.published_at.clone(),
                            size_bytes: pending.manifest.assets.app.size,
                            dismissed,
                            notes: pending.manifest.notes.clone(),
                        };
                        core.log(&format!(
                            "updater check: update available {} -> {} ({})",
                            CURRENT_BUILD_ID, avail.build_id, avail.commit_subject
                        ));
                        g.pending = Some(pending);
                        g.state.available = Some(avail);
                        g.state.status = "available".into();
                        g.state.error = None;
                    } else {
                        core.log(&format!(
                            "updater check: up to date ({})",
                            pending.manifest.build_id
                        ));
                        g.pending = None;
                        g.state.available = None;
                        g.state.status = "up_to_date".into();
                        g.state.error = None;
                    }
                }
                Err(e) => {
                    core.log(&format!("updater check failed: {e}"));
                    g.state.status = "error".into();
                    g.state.error = Some(e);
                }
            }
            g.state.clone()
        };
        self.emit(app);
        Ok(out)
    }

    pub fn install(&self, app: &AppHandle, core: &Core) -> Result<UpdateState, String> {
        let (pending, token_dpapi) = {
            let mut g = self.inner.lock().unwrap();
            if g.busy {
                return Err("An update operation is already running.".into());
            }
            let Some(pending) = g.pending.clone() else {
                return Err("No update is ready to install. Check for updates first.".into());
            };
            g.busy = true;
            g.state.status = "downloading".into();
            g.state.error = None;
            g.state.message = Some(format!("Downloading nightly {}...", pending.manifest.build_id));
            (pending, g.config.token_dpapi.clone())
        };
        self.emit(app);

        let step = self.download_verify_and_apply(&pending, token_dpapi.as_deref(), app, core);
        match step {
            Ok(InstallOutcome::DryRunVerified) => {
                let out = {
                    let mut g = self.inner.lock().unwrap();
                    g.busy = false;
                    g.state.status = "available".into();
                    g.state.message = Some(format!(
                        "Dry run verified signature and SHA-256 for {}. Skipping install.",
                        pending.manifest.build_id
                    ));
                    g.state.clone()
                };
                self.emit(app);
                Ok(out)
            }
            Ok(InstallOutcome::RestartScheduled) => {
                let out = {
                    let mut g = self.inner.lock().unwrap();
                    g.busy = false;
                    g.state.status = "applying".into();
                    g.state.message = Some("Restarting into the new nightly build...".into());
                    g.state.clone()
                };
                self.emit(app);
                let handle = app.clone();
                thread::spawn(move || {
                    thread::sleep(Duration::from_millis(250));
                    handle.exit(0);
                });
                Ok(out)
            }
            Err(e) => {
                core.log(&format!("updater install failed: {e}"));
                {
                    let mut g = self.inner.lock().unwrap();
                    g.busy = false;
                    g.state.status = "error".into();
                    g.state.error = Some(e.clone());
                    g.state.message = None;
                }
                self.emit(app);
                Err(e)
            }
        }
    }

    fn fetch_and_verify(
        &self,
        repo: &str,
        tag: &str,
        token_dpapi: Option<&str>,
    ) -> Result<CheckOutcome, String> {
        if repo.is_empty() {
            return Err("This build has no nightly release repository configured.".into());
        }
        let token = match token_dpapi {
            Some(enc) if !enc.is_empty() => Some(focus_devices::secrets::unprotect(enc)?),
            _ => None,
        };
        let client = build_http_client()?;
        let url = format!("https://api.github.com/repos/{repo}/releases/tags/{tag}");
        let mut req = client
            .get(&url)
            .header(ACCEPT, "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28");
        if let Some(t) = token.as_deref() {
            req = req.header(AUTHORIZATION, format!("Bearer {t}"));
        }
        telemetry::UPDATER_REQUEST.count(1);
        let resp = req
            .send()
            .map_err(|e| format!("Could not reach GitHub: {e}"))?;
        let status = resp.status();
        if status.as_u16() == 401 || status.as_u16() == 403 {
            return Ok(CheckOutcome::NeedsAuth);
        }
        if status.as_u16() == 404 {
            if token.is_none() {
                return Ok(CheckOutcome::NeedsAuth);
            }
            return Ok(CheckOutcome::NoRelease);
        }
        if !status.is_success() {
            return Err(format!("GitHub returned HTTP {}", status.as_u16()));
        }

        let body = resp
            .bytes()
            .map_err(|e| format!("Invalid GitHub release response: {e}"))?;
        telemetry::UPDATER_RX.count(body.len() as u64);
        let release: GhRelease = serde_json::from_slice(&body)
            .map_err(|e| format!("Invalid GitHub release response: {e}"))?;
        let manifest_asset = release
            .assets
            .iter()
            .find(|a| a.name == "nightly.json")
            .ok_or_else(|| "Nightly release is missing nightly.json".to_string())?;
        let manifest_bytes = download_github_asset_bytes(
            &client,
            &manifest_asset.url,
            token.as_deref(),
            MAX_MANIFEST_BYTES,
        )?;
        let manifest: NightlyManifest = serde_json::from_slice(&manifest_bytes)
            .map_err(|e| format!("Invalid nightly.json: {e}"))?;

        verify_manifest_signature(&manifest, &self.pubkey_hex)?;

        let app_asset = release
            .assets
            .iter()
            .find(|a| a.name == manifest.assets.app.name)
            .ok_or_else(|| format!("Nightly release is missing {}", manifest.assets.app.name))?;
        let bridge_asset_url = manifest.assets.bridge.as_ref().and_then(|b| {
            release
                .assets
                .iter()
                .find(|a| a.name == b.name)
                .map(|a| a.url.clone())
        });

        Ok(CheckOutcome::Release(PendingRelease {
            manifest,
            app_asset_url: app_asset.url.clone(),
            bridge_asset_url,
        }))
    }

    fn download_verify_and_apply(
        &self,
        pending: &PendingRelease,
        token_dpapi: Option<&str>,
        app: &AppHandle,
        core: &Core,
    ) -> Result<InstallOutcome, String> {
        verify_manifest_signature(&pending.manifest, &self.pubkey_hex)?;

        let token = match token_dpapi {
            Some(enc) if !enc.is_empty() => Some(focus_devices::secrets::unprotect(enc)?),
            _ => None,
        };
        let client = build_http_client()?;
        let stage_dir = self.dir.join("updates");
        fs::create_dir_all(&stage_dir)
            .map_err(|e| format!("Could not create updates folder: {e}"))?;

        let app_stage = stage_dir.join("FocusDNS.new.exe");
        download_verified_binary(
            &client,
            &pending.app_asset_url,
            token.as_deref(),
            &app_stage,
            &pending.manifest.assets.app,
        )?;

        let mut bridge_stage: Option<PathBuf> = None;
        let bridge_target = self.dir.join("browser-bridge").join("focus-browser-host.exe");
        if bridge_target.exists() {
            if let (Some(bridge_meta), Some(bridge_url)) =
                (&pending.manifest.assets.bridge, &pending.bridge_asset_url)
            {
                let staged = stage_dir.join("focus-browser-host.new.exe");
                download_verified_binary(
                    &client,
                    bridge_url,
                    token.as_deref(),
                    &staged,
                    bridge_meta,
                )
                .inspect_err(|_| {
                    let _ = fs::remove_file(&app_stage);
                })?;
                bridge_stage = Some(staged);
            }
        }

        if self.dry {
            let _ = fs::remove_file(&app_stage);
            if let Some(b) = &bridge_stage {
                let _ = fs::remove_file(b);
            }
            core.log(&format!(
                "updater dry-run verified {} ({})",
                pending.manifest.build_id, pending.manifest.commit_sha
            ));
            return Ok(InstallOutcome::DryRunVerified);
        }

        {
            let mut g = self.inner.lock().unwrap();
            g.state.status = "applying".into();
            g.state.message = Some("Installing update and restarting...".into());
        }
        self.emit(app);

        let installed: Result<PathBuf, String> = (|| {
            let current_exe = std::env::current_exe()
                .map_err(|e| format!("Could not determine current executable path: {e}"))?;
            // Both staged files already occupy disk space. Check the bridge's
            // destination before committing the app swap, even on another volume.
            if let Some(staged) = &bridge_stage {
                check_copy_space(&bridge_target, staged)?;
            }
            replace_binary_safely(&current_exe, &app_stage)?;

            if let Some(staged) = &bridge_stage {
                if let Err(e) = replace_binary_safely(&bridge_target, staged) {
                    core.log(&format!("updater bridge swap skipped: {e}"));
                } else {
                    core.log("updater updated focus-browser-host.exe");
                }
            }
            Ok(current_exe)
        })();
        let _ = fs::remove_file(&app_stage);
        if let Some(staged) = &bridge_stage {
            let _ = fs::remove_file(staged);
        }
        let current_exe = installed?;

        core.log(&format!(
            "updater installed {} -> {}, spawning relauncher",
            CURRENT_BUILD_ID, pending.manifest.build_id
        ));
        spawn_relauncher(&current_exe, std::process::id())?;
        Ok(InstallOutcome::RestartScheduled)
    }
}

enum CheckOutcome {
    NoRelease,
    NeedsAuth,
    Release(PendingRelease),
}

enum InstallOutcome {
    DryRunVerified,
    RestartScheduled,
}

fn migrate_feed(config: &mut UpdaterConfig, destination: &str, previous_hash: &str) -> bool {
    if previous_hash.is_empty() || destination.is_empty() || config.tag != DEFAULT_TAG
        || config.repo.eq_ignore_ascii_case(destination) {
        return false;
    }
    let digest = format!("{:x}", Sha256::digest(config.repo.to_ascii_lowercase().as_bytes()));
    if digest != previous_hash {
        return false;
    }
    config.repo = destination.to_owned();
    config.token_dpapi = None;
    config.dismissed_sha = None;
    true
}

fn config_path(dir: &Path) -> PathBuf {
    dir.join("updater.json")
}

fn load_config(dir: &Path) -> UpdaterConfig {
    fs::read_to_string(config_path(dir))
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

fn save_config(dir: &Path, cfg: &UpdaterConfig) -> Result<(), String> {
    let bytes = serde_json::to_vec_pretty(cfg).map_err(|e| format!("Serialize error: {e}"))?;
    focus_browser_host::atomic_write(&config_path(dir), &bytes)
        .map_err(|e| format!("Could not save updater settings: {e}"))
}

fn build_http_client() -> Result<Client, String> {
    Client::builder()
        .redirect(Policy::none())
        .timeout(Duration::from_secs(90))
        .connect_timeout(Duration::from_secs(15))
        .user_agent(format!("FocusDNS/{CURRENT_BUILD_ID}"))
        .build()
        .map_err(|e| format!("Could not initialize HTTP client: {e}"))
}

/// Downloads a GitHub Release asset via the API URL (`Accept: application/octet-stream`),
/// explicitly following a single HTTPS redirect WITHOUT forwarding the GitHub Bearer token
/// (Azure/S3 signed URLs reject extra Authorization headers with HTTP 400).
fn open_github_asset_response(
    client: &Client,
    asset_api_url: &str,
    token: Option<&str>,
) -> Result<reqwest::blocking::Response, String> {
    if !asset_api_url.starts_with("https://api.github.com/") {
        return Err("Refusing unexpected GitHub asset URL".into());
    }
    let mut req = client
        .get(asset_api_url)
        .header(ACCEPT, "application/octet-stream")
        .header(USER_AGENT, format!("FocusDNS/{CURRENT_BUILD_ID}"));
    if let Some(t) = token {
        req = req.header(AUTHORIZATION, format!("Bearer {t}"));
    }
    telemetry::UPDATER_REQUEST.count(1);
    let resp = req
        .send()
        .map_err(|e| format!("Asset request failed: {e}"))?;

    if resp.status().is_redirection() {
        let loc = resp
            .headers()
            .get(LOCATION)
            .and_then(|v| v.to_str().ok())
            .ok_or_else(|| "GitHub redirect missing Location header".to_string())?;
        if !loc.starts_with("https://") {
            return Err("Refusing non-HTTPS download redirect".into());
        }
        telemetry::UPDATER_REQUEST.count(1);
        let redirected = client
            .get(loc)
            .header(USER_AGENT, format!("FocusDNS/{CURRENT_BUILD_ID}"))
            .send()
            .map_err(|e| format!("Download redirect failed: {e}"))?;
        if !redirected.status().is_success() {
            return Err(format!(
                "Download failed with HTTP {}",
                redirected.status().as_u16()
            ));
        }
        return Ok(redirected);
    }

    if !resp.status().is_success() {
        return Err(format!(
            "Asset download failed with HTTP {}",
            resp.status().as_u16()
        ));
    }
    Ok(resp)
}

fn download_github_asset_bytes(
    client: &Client,
    asset_api_url: &str,
    token: Option<&str>,
    max_bytes: usize,
) -> Result<Vec<u8>, String> {
    let resp = open_github_asset_response(client, asset_api_url, token)?;
    let mut out = Vec::new();
    let mut limited = resp.take((max_bytes as u64) + 1);
    let result = limited.read_to_end(&mut out);
    telemetry::UPDATER_RX.count(out.len() as u64);
    result.map_err(|e| format!("Failed reading download: {e}"))?;
    if out.len() > max_bytes {
        return Err("Manifest exceeds maximum allowed size".into());
    }
    Ok(out)
}

fn download_verified_binary(
    client: &Client,
    asset_api_url: &str,
    token: Option<&str>,
    dest: &Path,
    asset: &ManifestAsset,
) -> Result<(), String> {
    match fs::remove_file(dest) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => return Err(format!("Could not clear the previous update download: {e}")),
    }
    let parent = dest.parent().ok_or("Missing update directory")?;
    check_download_size(asset.size, available_disk_space(parent)?)?;
    let resp = open_github_asset_response(client, asset_api_url, token)?;
    let result = (|| {
        let mut file =
            File::create(dest).map_err(|e| format!("Could not create temp file: {e}"))?;
        copy_binary(resp, &mut file, asset.size)?;
        file.sync_all()
            .map_err(|e| format!("Disk sync failed: {e}"))?;
        drop(file);
        verify_file_sha256(dest, asset.size, &asset.sha256)
    })();
    result.inspect_err(|_| {
        let _ = fs::remove_file(dest);
    })
}

fn available_disk_space(dir: &Path) -> Result<u64, String> {
    let mut path: Vec<u16> = dir.as_os_str().encode_wide().collect();
    // GetDiskFreeSpaceExW requires a trailing backslash for UNC paths.
    path.extend([b'\\' as u16, 0]);
    let mut available = 0;
    let ok = unsafe {
        GetDiskFreeSpaceExW(
            path.as_ptr(),
            &mut available,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
        )
    };
    if ok == 0 {
        return Err(format!(
            "Could not check free space: {}",
            std::io::Error::last_os_error()
        ));
    }
    Ok(available)
}

fn check_download_size(expected: u64, available: u64) -> Result<(), String> {
    if expected == 0 || expected > BINARY_SANITY_LIMIT {
        return Err(format!(
            "Manifest binary size is {expected} bytes. Allowed range is 1 to {BINARY_SANITY_LIMIT} bytes."
        ));
    }
    check_free_space(expected, available)
}

fn check_free_space(required: u64, available: u64) -> Result<(), String> {
    if available < required {
        return Err(format!(
            "Update needs {required} bytes of free space. Available: {available} bytes."
        ));
    }
    Ok(())
}

fn copy_binary(input: impl Read, output: &mut impl Write, expected: u64) -> Result<(), String> {
    // Read at most one byte beyond the declared size to detect an oversized stream.
    let mut input = input.take(expected + 1);
    let mut buf = [0u8; 64 * 1024];
    let mut total = 0u64;
    loop {
        let n = input
            .read(&mut buf)
            .map_err(|e| format!("Download interrupted: {e}"))?;
        if n == 0 {
            break;
        }
        telemetry::UPDATER_RX.count(n as u64);
        total += n as u64;
        if total > expected {
            break;
        }
        output
            .write_all(&buf[..n])
            .map_err(|e| format!("Disk write failed: {e}"))?;
    }
    if total != expected {
        return Err(format!(
            "Binary size mismatch (got {total} bytes, expected {expected} bytes)."
        ));
    }
    Ok(())
}

pub fn canonical_payload(manifest: &NightlyManifest) -> String {
    let bridge_line = match &manifest.assets.bridge {
        Some(b) => format!("{}:{}:{}", b.name, b.size, b.sha256),
        None => "none".to_string(),
    };
    format!(
        "focus-nightly-v1\nbuild_id={}\ncommit_sha={}\npublished_at={}\napp={}:{}:{}\nbridge={}",
        manifest.build_id,
        manifest.commit_sha,
        manifest.published_at,
        manifest.assets.app.name,
        manifest.assets.app.size,
        manifest.assets.app.sha256,
        bridge_line
    )
}

fn decode_hex(s: &str) -> Result<Vec<u8>, String> {
    let s = s.trim();
    if s.len() % 2 != 0 {
        return Err("Invalid hex length".into());
    }
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).map_err(|_| "Invalid hex character".into()))
        .collect()
}

pub fn verify_manifest_signature(manifest: &NightlyManifest, pubkey_hex: &str) -> Result<(), String> {
    if manifest.version != 1 {
        return Err(format!("Unsupported manifest version {}", manifest.version));
    }
    let pubkey_bytes = decode_hex(pubkey_hex)?;
    if pubkey_bytes.len() != 32 {
        return Err("Embedded updater public key is invalid".into());
    }
    let sig_bytes = decode_hex(&manifest.signature)?;
    if sig_bytes.len() != 64 {
        return Err("Manifest signature is malformed".into());
    }
    let payload = canonical_payload(manifest);
    let key = UnparsedPublicKey::new(&ED25519, &pubkey_bytes);
    key.verify(payload.as_bytes(), &sig_bytes)
        .map_err(|_| "Update signature verification failed".to_string())
}

pub fn verify_file_sha256(path: &Path, expected_size: u64, expected_hex: &str) -> Result<(), String> {
    let mut file = File::open(path).map_err(|e| format!("Could not open downloaded file: {e}"))?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    let mut total = 0u64;
    loop {
        let n = file
            .read(&mut buf)
            .map_err(|e| format!("Failed hashing downloaded file: {e}"))?;
        if n == 0 {
            break;
        }
        total += n as u64;
        hasher.update(&buf[..n]);
    }
    if total != expected_size {
        return Err(format!(
            "Downloaded file size mismatch (got {total}, expected {expected_size})"
        ));
    }
    let actual_hex: String = hasher.finalize().iter().map(|b| format!("{b:02x}")).collect();
    if !actual_hex.eq_ignore_ascii_case(expected_hex.trim()) {
        return Err("Downloaded file SHA-256 verification failed".into());
    }
    Ok(())
}

pub fn is_update_available(
    current_commit_sha: &str,
    current_build_id: &str,
    remote_commit_sha: &str,
    remote_build_id: &str,
) -> bool {
    let cur_sha = current_commit_sha.trim();
    let rem_sha = remote_commit_sha.trim();
    let cur_id = current_build_id.trim().trim_end_matches("-dirty");
    let rem_id = remote_build_id.trim();

    if rem_sha.is_empty() && rem_id.is_empty() {
        return false;
    }
    if cur_sha.len() == 40 && rem_sha.len() == 40 {
        return !cur_sha.eq_ignore_ascii_case(rem_sha);
    }
    if cur_id.is_empty() || cur_id.eq_ignore_ascii_case("unknown") {
        return true;
    }
    if !rem_id.is_empty() {
        return !cur_id.eq_ignore_ascii_case(rem_id);
    }
    true
}

fn old_binary_path(target: &Path) -> PathBuf {
    let mut name = target.file_name().unwrap_or_default().to_os_string();
    name.push(".old");
    target.with_file_name(name)
}

fn check_copy_space(target: &Path, staged: &Path) -> Result<(), String> {
    let parent = target.parent().ok_or("Missing installation directory")?;
    let size = fs::metadata(staged)
        .map_err(|e| format!("Could not read staged binary size: {e}"))?
        .len();
    check_free_space(size, available_disk_space(parent)?)
        .map_err(|e| format!("{}: {e}", parent.display()))
}

/// Replaces `target` with `staged` using rename-then-copy with rollback on failure.
/// On Windows, a running executable cannot be overwritten directly, but it CAN be renamed.
pub fn replace_binary_safely(target: &Path, staged: &Path) -> Result<(), String> {
    check_copy_space(target, staged)?;
    let backup = old_binary_path(target);
    let _ = fs::remove_file(&backup);

    if target.exists() {
        fs::rename(target, &backup)
            .map_err(|e| format!("Could not move current binary aside: {e}"))?;
    }

    let copy_res = fs::copy(staged, target).and_then(|_| {
        let _ = fs::remove_file(staged);
        Ok(())
    });

    if let Err(e) = copy_res {
        // Roll back immediately so the installation is never left without an executable.
        let _ = fs::remove_file(target);
        if backup.exists() {
            fs::rename(&backup, target)
                .map_err(|rollback| format!("Could not install new binary: {e}. Rollback failed: {rollback}"))?;
        }
        return Err(format!("Could not install new binary: {e}"));
    }
    Ok(())
}

/// Removes leftover `.old` binaries from a previous update.
pub fn cleanup_old_binaries(core: &Core) {
    if let Ok(exe) = std::env::current_exe() {
        let old = old_binary_path(&exe);
        if old.exists() {
            if fs::remove_file(&old).is_ok() {
                core.log("updater cleaned up previous FocusDNS.exe.old");
            }
        }
    }
    let bridge_old = old_binary_path(&core.dir.join("browser-bridge").join("focus-browser-host.exe"));
    if bridge_old.exists() {
        let _ = fs::remove_file(&bridge_old);
    }
}

#[cfg(windows)]
fn spawn_relauncher(exe_path: &Path, current_pid: u32) -> Result<(), String> {
    use std::os::windows::process::CommandExt;
    let escaped_exe = exe_path.to_string_lossy().replace('\'', "''");
    // Wait for the current process to exit so the scheduled task (MultipleInstances IgnoreNew)
    // starts the new binary cleanly; fall back to launching the new binary directly if needed.
    let ps = format!(
        "Wait-Process -Id {current_pid} -Timeout 15 -ErrorAction SilentlyContinue; \
         Start-Sleep -Milliseconds 350; \
         schtasks /run /tn FocusDNS *> $null; \
         if ($LASTEXITCODE -ne 0) {{ Start-Process -FilePath '{escaped_exe}' -ArgumentList '--from-task' }}"
    );
    let mut cmd = Command::new("powershell.exe");
    cmd.args([
        "-NoProfile",
        "-NonInteractive",
        "-WindowStyle",
        "Hidden",
        "-ExecutionPolicy",
        "Bypass",
        "-Command",
        &ps,
    ]);
    cmd.creation_flags(NO_WINDOW);
    cmd.spawn()
        .map_err(|e| format!("Could not start update relauncher: {e}"))?;
    Ok(())
}

#[cfg(not(windows))]
fn spawn_relauncher(_exe_path: &Path, _current_pid: u32) -> Result<(), String> {
    Err("Self-restart is only supported on Windows".into())
}

pub fn poll_loop(app: AppHandle, updater: Arc<Updater>, core: Arc<Core>) {
    thread::sleep(INITIAL_CHECK_DELAY);
    loop {
        telemetry::UPDATER_TICK.count(1);
        if updater.is_enabled() {
            let _ = updater.check(&app, &core);
        }
        thread::sleep(POLL_INTERVAL);
    }
}

#[tauri::command(async)]
pub fn updater_status(updater: State<Arc<Updater>>) -> UpdateState {
    updater.snapshot()
}

#[tauri::command(async)]
pub fn updater_check(
    app: AppHandle,
    updater: State<Arc<Updater>>,
    core: State<Arc<Core>>,
) -> Result<UpdateState, String> {
    updater.check(&app, &core)
}

#[tauri::command(async)]
pub fn updater_install(
    app: AppHandle,
    updater: State<Arc<Updater>>,
    core: State<Arc<Core>>,
) -> Result<UpdateState, String> {
    updater.install(&app, &core)
}

#[tauri::command(async)]
pub fn updater_dismiss(
    app: AppHandle,
    updater: State<Arc<Updater>>,
    core: State<Arc<Core>>,
) -> Result<UpdateState, String> {
    updater.dismiss(&app, &core)
}

#[tauri::command(async)]
pub fn updater_set_token(
    token: Option<String>,
    app: AppHandle,
    updater: State<Arc<Updater>>,
    core: State<Arc<Core>>,
) -> Result<UpdateState, String> {
    updater.set_token(token, &app, &core)
}

#[tauri::command(async)]
pub fn updater_set_enabled(
    enabled: bool,
    app: AppHandle,
    updater: State<Arc<Updater>>,
    core: State<Arc<Core>>,
) -> Result<UpdateState, String> {
    updater.set_enabled(enabled, &app, &core)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::rand::SystemRandom;
    use ring::signature::{Ed25519KeyPair, KeyPair};

    fn hex_encode(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "focus-updater-test-{name}-{}-{}",
            std::process::id(),
            now_ms()
        ));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn binary_download_checks_manifest_size_and_disk_space() {
        let size = 128 * 1024 * 1024 + 1;
        assert!(check_download_size(size, size).is_ok());
        assert!(check_download_size(BINARY_SANITY_LIMIT, BINARY_SANITY_LIMIT).is_ok());
        assert!(check_download_size(BINARY_SANITY_LIMIT + 1, u64::MAX).is_err());
        assert!(check_download_size(0, u64::MAX).is_err());
        assert!(check_download_size(size, size - 1).is_err());
    }

    #[test]
    fn binary_download_enforces_exact_size_for_streams() {
        let mut exact = Vec::new();
        assert!(copy_binary(&b"test"[..], &mut exact, 4).is_ok());
        assert_eq!(exact, b"test");
        let short = copy_binary(&b"abc"[..], &mut Vec::new(), 4).unwrap_err();
        assert!(short.contains("got 3 bytes, expected 4 bytes"));
        let mut oversized = &b"0123456789"[..];
        let mut output = Vec::new();
        let error = copy_binary(&mut oversized, &mut output, 4).unwrap_err();
        assert!(error.contains("got 5 bytes, expected 4 bytes"));
        assert_eq!(oversized, b"56789");
        assert!(output.len() <= 4);
    }

    #[test]
    fn ed25519_manifest_signature_verification_works_and_rejects_tampering() {
        let rng = SystemRandom::new();
        let pkcs8 = Ed25519KeyPair::generate_pkcs8(&rng).unwrap();
        let kp = Ed25519KeyPair::from_pkcs8(pkcs8.as_ref()).unwrap();
        let pub_hex = hex_encode(kp.public_key().as_ref());

        let mut manifest = NightlyManifest {
            version: 1,
            channel: "nightly".into(),
            build_id: "abc1234".into(),
            commit_sha: "a".repeat(40),
            commit_subject: "fix: keep overlay on top".into(),
            published_at: "2026-11-19T05:30:00Z".into(),
            assets: ManifestAssets {
                app: ManifestAsset {
                    name: "FocusDNS.exe".into(),
                    size: 1234,
                    sha256: "b".repeat(64),
                },
                bridge: Some(ManifestAsset {
                    name: "focus-browser-host.exe".into(),
                    size: 567,
                    sha256: "c".repeat(64),
                }),
            },
            signature: String::new(),
            notes: ReleaseNotes::default(),
        };
        let payload = canonical_payload(&manifest);
        manifest.signature = hex_encode(kp.sign(payload.as_bytes()).as_ref());

        assert!(verify_manifest_signature(&manifest, &pub_hex).is_ok());

        let mut tampered = manifest.clone();
        tampered.assets.app.sha256 = "d".repeat(64);
        assert!(verify_manifest_signature(&tampered, &pub_hex).is_err());

        let other = Ed25519KeyPair::generate_pkcs8(&rng).unwrap();
        let other_kp = Ed25519KeyPair::from_pkcs8(other.as_ref()).unwrap();
        let other_pub = hex_encode(other_kp.public_key().as_ref());
        assert!(verify_manifest_signature(&manifest, &other_pub).is_err());
    }

    #[test]
    fn verifies_signature_generated_by_node_sign_nightly_script() {
        let manifest = NightlyManifest {
            version: 1,
            channel: "nightly".into(),
            build_id: "abc1234".into(),
            commit_sha: "a".repeat(40),
            commit_subject: "fix: overlay".into(),
            published_at: "2026-11-19T05:30:00Z".into(),
            assets: ManifestAssets {
                app: ManifestAsset {
                    name: "FocusDNS.exe".into(),
                    size: 1234,
                    sha256: "b".repeat(64),
                },
                bridge: Some(ManifestAsset {
                    name: "focus-browser-host.exe".into(),
                    size: 567,
                    sha256: "c".repeat(64),
                }),
            },
            signature: "70da3224e84944ab7470b11c40fe76176ae9211dcb46539be07a1f6da515d45024133617f2416e19fd43dcd78906379276089791be666be7623b169b7405c702".into(),
            notes: ReleaseNotes::default(),
        };
        let pubkey = "e27ba93140eed335153546922ce4416ef3c1859658f413a8892bd12af4361f2a";
        assert!(verify_manifest_signature(&manifest, pubkey).is_ok());

        // Release notes are display-only: adding or changing them must not break the signature.
        let mut with_notes = manifest.clone();
        with_notes.notes = ReleaseNotes {
            items: vec![("Simpler stats".into(), "Everything fits in one calm view.".into())],
            more: "Plus 3 more fixes.".into(),
        };
        assert!(verify_manifest_signature(&with_notes, pubkey).is_ok());
    }

    #[test]
    fn parses_manifests_with_and_without_release_notes() {
        let old = r#"{"version":1,"channel":"nightly","build_id":"abc1234","commit_sha":"aaaa","published_at":"2026-11-19T05:30:00Z","assets":{"app":{"name":"FocusDNS.exe","size":1,"sha256":"bb"}},"signature":"00"}"#;
        let m: NightlyManifest = serde_json::from_str(old).unwrap();
        assert!(m.notes.items.is_empty());
        let new = r#"{"version":1,"channel":"nightly","build_id":"abc1234","commit_sha":"aaaa","published_at":"2026-11-19T05:30:00Z","assets":{"app":{"name":"FocusDNS.exe","size":1,"sha256":"bb"}},"signature":"00","notes":{"items":[["Simpler stats","One calm view."]],"more":"Plus 2 more fixes."}}"#;
        let m: NightlyManifest = serde_json::from_str(new).unwrap();
        assert_eq!(m.notes.items[0].0, "Simpler stats");
        assert_eq!(m.notes.more, "Plus 2 more fixes.");
    }

    #[test]
    fn file_sha256_verification_catches_corruption_and_size_mismatch() {
        let dir = temp_dir("sha");
        let file_path = dir.join("FocusDNS.exe");
        let data = b"hello focus nightly binary";
        fs::write(&file_path, data).unwrap();
        let hash = hex_encode(&Sha256::digest(data));

        assert!(verify_file_sha256(&file_path, data.len() as u64, &hash).is_ok());
        assert!(verify_file_sha256(&file_path, (data.len() + 1) as u64, &hash).is_err());
        assert!(verify_file_sha256(&file_path, data.len() as u64, &"0".repeat(64)).is_err());

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn update_comparison_handles_full_sha_short_id_dirty_and_unknown() {
        let sha_a = "1111111111111111111111111111111111111111";
        let sha_b = "2222222222222222222222222222222222222222";
        assert!(!is_update_available(sha_a, "1111111", sha_a, "1111111"));
        assert!(is_update_available(sha_a, "1111111", sha_b, "2222222"));
        assert!(!is_update_available("unknown", "1111111-dirty", sha_a, "1111111"));
        assert!(is_update_available("unknown", "unknown", sha_b, "2222222"));
        assert!(is_update_available("unknown", "1111111", sha_b, "2222222"));
    }

    #[test]
    fn binary_swap_replaces_target_and_keeps_backup_until_cleanup() {
        let dir = temp_dir("swap");
        let target = dir.join("FocusDNS.exe");
        let staged = dir.join("FocusDNS.new.exe");
        fs::write(&target, b"old-version").unwrap();
        fs::write(&staged, b"new-version").unwrap();

        replace_binary_safely(&target, &staged).unwrap();
        assert_eq!(fs::read(&target).unwrap(), b"new-version");
        assert!(!staged.exists());
        assert_eq!(fs::read(old_binary_path(&target)).unwrap(), b"old-version");

        // A missing staged file must fail before moving the installed binary.
        let missing = dir.join("missing.exe");
        assert!(replace_binary_safely(&target, &missing).is_err());
        assert_eq!(fs::read(&target).unwrap(), b"new-version");
        assert_eq!(fs::read(old_binary_path(&target)).unwrap(), b"old-version");

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn migration_preserves_user_feeds_and_retires_only_the_previous_nightly() {
        let old = "example/previous";
        let hash = format!("{:x}", Sha256::digest(old.as_bytes()));
        let mut cfg = UpdaterConfig { repo: old.to_uppercase(), enabled: false, tag: "custom".into(),
            token_dpapi: Some("test".into()), ..Default::default() };
        assert!(!migrate_feed(&mut cfg, "example/current", &hash));
        cfg.tag = DEFAULT_TAG.into();
        assert!(migrate_feed(&mut cfg, "example/current", &hash));
        assert!(!cfg.enabled && cfg.token_dpapi.is_none());
        assert!(!migrate_feed(&mut cfg, "example/current", &hash));
    }

    #[test]
    fn config_round_trip_persists_settings_and_dismissed_sha() {
        let dir = temp_dir("cfg");
        let cfg = UpdaterConfig {
            enabled: false,
            repo: "example-org/sample-project".into(),
            tag: "nightly".into(),
            token_dpapi: Some("deadbeef".into()),
            dismissed_sha: Some("a".repeat(40)),
        };
        save_config(&dir, &cfg).unwrap();
        let loaded = load_config(&dir);
        assert!(!loaded.enabled);
        assert_eq!(loaded.token_dpapi.as_deref(), Some("deadbeef"));
        assert_eq!(loaded.dismissed_sha.as_deref(), Some("a".repeat(40).as_str()));

        let _ = fs::remove_dir_all(&dir);
    }
}
