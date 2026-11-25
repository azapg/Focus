package org.nightingalelab.focus.phone;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.nightingalelab.focus.telemetry.LocalTelemetry.Metric;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Self-updater for Focus for Android.
 *
 * Checks the rolling `nightly` GitHub Release (same release used by Desktop), downloads
 * `focus-phone.apk`, verifies byte size + SHA-256 + package name + matching signing certificate,
 * and hands it to Android's PackageInstaller for a 1-tap in-place update that preserves all
 * permissions (WRITE_SECURE_SETTINGS, Accessibility, Usage Access) and local data.
 */
public final class PhoneUpdater {
  public static final String ACTION_STATE = "org.nightingalelab.focus.phone.UPDATE_STATE";
  public static final String MANIFEST_ASSET_NAME = "android-nightly.json";

  private static final int MAX_MANIFEST_BYTES = 64 * 1024;
  private static final long AUTO_CHECK_INTERVAL_MS = 30 * 60_000L;
  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int READ_TIMEOUT_MS = 60_000;

  private static PhoneUpdater instance;

  private final Context context;
  private final SharedPreferences prefs;

  private String status = "idle";
  private UpdateManifest available;
  private String apkAssetUrl;
  private int progressPct;
  private long lastCheckedAt;
  private String message;
  private String error;
  private boolean busy;

  public static final class Snapshot {
    public final String status;
    public final boolean enabled;
    public final boolean hasToken;
    public final String repo;
    public final String tag;
    public final String currentBuildId;
    public final String currentCommitSha;
    public final long currentVersionCode;
    public final String currentVersionName;
    public final String builtAt;
    public final UpdateManifest available;
    public final boolean dismissed;
    public final int progressPct;
    public final long lastCheckedAt;
    public final String message;
    public final String error;
    public final boolean canInstallPackages;

    Snapshot(
        String status,
        boolean enabled,
        boolean hasToken,
        String repo,
        String tag,
        String currentBuildId,
        String currentCommitSha,
        long currentVersionCode,
        String currentVersionName,
        String builtAt,
        UpdateManifest available,
        boolean dismissed,
        int progressPct,
        long lastCheckedAt,
        String message,
        String error,
        boolean canInstallPackages) {
      this.status = status;
      this.enabled = enabled;
      this.hasToken = hasToken;
      this.repo = repo;
      this.tag = tag;
      this.currentBuildId = currentBuildId;
      this.currentCommitSha = currentCommitSha;
      this.currentVersionCode = currentVersionCode;
      this.currentVersionName = currentVersionName;
      this.builtAt = builtAt;
      this.available = available;
      this.dismissed = dismissed;
      this.progressPct = progressPct;
      this.lastCheckedAt = lastCheckedAt;
      this.message = message;
      this.error = error;
      this.canInstallPackages = canInstallPackages;
    }

    public boolean showBanner() {
      return available != null && !dismissed;
    }
  }

  public static synchronized PhoneUpdater get(Context c) {
    if (instance == null) instance = new PhoneUpdater(c.getApplicationContext());
    return instance;
  }

  private PhoneUpdater(Context c) {
    this.context = c;
    this.prefs = c.getSharedPreferences("focus_updater", Context.MODE_PRIVATE);
    migrateNightlyFeed();
    this.lastCheckedAt = prefs.getLong("last_checked_at", 0L);
    restoreCachedManifest();
  }

  private void migrateNightlyFeed() {
    String previousHash = BuildInfo.PREVIOUS_FEED_SHA256;
    if (previousHash.isEmpty() || BuildInfo.DEFAULT_REPO.isEmpty()
        || prefs.getString("migrated_feed", "").equals(previousHash)
        || !BuildInfo.DEFAULT_TAG.equals(prefs.getString("tag", BuildInfo.DEFAULT_TAG))) return;
    String saved = prefs.getString("repo", "");
    if (!saved.isEmpty()) {
      try {
        byte[] repo = saved.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        if (!previousHash.equals(UpdateManifest.sha256Hex(new ByteArrayInputStream(repo)))) return;
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
    // Older installations normally used the built-in feed and saved only a token.
    prefs.edit().putString("repo", BuildInfo.DEFAULT_REPO).putString("migrated_feed", previousHash)
        .remove("github_token").remove("cached_manifest").remove("cached_apk_url")
        .remove("dismissed_sha").remove("notified_sha").apply();
  }

  private void restoreCachedManifest() {
    String raw = prefs.getString("cached_manifest", "");
    String url = prefs.getString("cached_apk_url", "");
    if (raw.isEmpty() || url.isEmpty()) return;
    try {
      UpdateManifest m = UpdateManifest.parseManifest(raw);
      if (UpdateManifest.isUpdateAvailable(installedVersionCode(), BuildInfo.COMMIT_SHA, BuildInfo.BUILD_ID, m)) {
        available = m;
        apkAssetUrl = url;
        status = "available";
      } else {
        prefs.edit().remove("cached_manifest").remove("cached_apk_url").apply();
      }
    } catch (RuntimeException ignored) {
      prefs.edit().remove("cached_manifest").remove("cached_apk_url").apply();
    }
  }

  public long installedVersionCode() {
    try {
      PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      return pi.getLongVersionCode();
    } catch (PackageManager.NameNotFoundException e) {
      return BuildInfo.VERSION_CODE;
    }
  }

  public boolean canInstallPackages() {
    try {
      return context.getPackageManager().canRequestPackageInstalls();
    } catch (RuntimeException e) {
      return false;
    }
  }

  public synchronized Snapshot snapshot() {
    boolean enabled = prefs.getBoolean("enabled", true);
    String token = prefs.getString("github_token", "");
    String repo = prefs.getString("repo", BuildInfo.DEFAULT_REPO);
    String tag = prefs.getString("tag", BuildInfo.DEFAULT_TAG);
    String dismissedSha = prefs.getString("dismissed_sha", "");
    boolean dismissed = available != null
        && !dismissedSha.isEmpty()
        && dismissedSha.equalsIgnoreCase(available.commitSha);
    return new Snapshot(
        status,
        enabled,
        !token.isEmpty(),
        repo,
        tag,
        BuildInfo.BUILD_ID,
        BuildInfo.COMMIT_SHA,
        installedVersionCode(),
        BuildInfo.VERSION_NAME,
        BuildInfo.BUILT_AT,
        available,
        dismissed,
        progressPct,
        lastCheckedAt,
        message,
        error,
        canInstallPackages());
  }

  public synchronized void setEnabled(boolean enabled) {
    prefs.edit().putBoolean("enabled", enabled).apply();
    Store.get(context).diagnostic("updater_auto_check_" + (enabled ? "on" : "off"));
    notifyListeners();
  }

  public synchronized void setToken(String token) {
    String clean = token == null ? "" : token.trim();
    SharedPreferences.Editor ed = prefs.edit();
    if (clean.isEmpty()) ed.remove("github_token");
    else ed.putString("github_token", clean);
    ed.apply();
    if ("needs_auth".equals(status) && !clean.isEmpty()) {
      status = "idle";
      error = null;
      message = null;
    }
    Store.get(context).diagnostic(clean.isEmpty() ? "updater_token_removed" : "updater_token_saved");
    notifyListeners();
    if (!clean.isEmpty()) {
      checkNow(false);
    }
  }

  public synchronized void dismiss() {
    if (available != null) {
      prefs.edit().putString("dismissed_sha", available.commitSha).apply();
      Store.get(context).diagnostic("updater_dismissed_" + available.buildId);
      notifyListeners();
    }
  }

  public void openInstallPermissionSettings(Activity activity) {
    Intent intent = new Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:" + context.getPackageName()));
    activity.startActivity(intent);
  }

  /** Called from MainActivity.onResume() and MonitorService periodic tick. */
  public void checkIfDue() {
    synchronized (this) {
      if (busy) return;
      if (!prefs.getBoolean("enabled", true)) return;
      long now = System.currentTimeMillis();
      if (now - lastCheckedAt < AUTO_CHECK_INTERVAL_MS) return;
      if (!isOnline()) return;
    }
    checkNow(true);
  }

  public void checkNow(boolean silent) {
    final String repo;
    final String tag;
    final String token;
    synchronized (this) {
      if (busy) return;
      repo = prefs.getString("repo", BuildInfo.DEFAULT_REPO);
      if (repo.isEmpty()) {
        if (!silent) {
          status = "error";
          error = "This build has no nightly release repository configured.";
          message = null;
          notifyListeners();
        }
        return;
      }
      busy = true;
      tag = prefs.getString("tag", BuildInfo.DEFAULT_TAG);
      token = prefs.getString("github_token", "");
      if (!silent) {
        status = "checking";
        error = null;
        message = "Checking GitHub nightly release…";
        notifyListeners();
      }
    }

    new Thread(() -> runCheck(repo, tag, token, silent), "focus-updater-check").start();
  }

  private final class UpdateAttempt {
    final String id = FocusEvents.newId();
    long began = SystemClock.elapsedRealtime();
    String stage;
    String failure;
    int status;
    long actual;
    long limit;
    UpdateManifest target;

    UpdateAttempt(String stage, String failure) {
      this.stage = stage;
      this.failure = failure;
    }

    void advance(String next, String cause) {
      record("ok", "ok");
      stage = next;
      failure = cause;
      began = SystemClock.elapsedRealtime();
    }

    void record(String outcome, String cause) {
      try {
        JSONObject fields = fields();
        FocusEvents.record(context, "update", stage, outcome, cause,
            SystemClock.elapsedRealtime() - began, fields);
      } catch (JSONException ignored) {}
    }

    JSONObject fields() throws JSONException {
      JSONObject fields = new JSONObject().put("correlation_id", id)
          .put("from_build", FocusEvents.buildId(BuildInfo.BUILD_ID));
      if (target != null) fields.put("to_build", FocusEvents.buildId(target.buildId))
          .put("expected_bytes", target.apkSize);
      if (!stage.equals("check")) fields.put("actual_bytes", actual).put("limit_bytes", limit);
      if (status > 0) fields.put("http_status", status);
      return fields;
    }
  }

  private void runCheck(String repo, String tag, String token, boolean silent) {
    UpdateAttempt attempt = new UpdateAttempt("check", "update_http");
    try {
      String releaseUrl = "https://api.github.com/repos/" + repo + "/releases/tags/" + tag;
      HttpResult releaseResp = httpGet(releaseUrl, "application/vnd.github+json", token, attempt);
      long checkedNow = System.currentTimeMillis();

      if (releaseResp.code == 401 || releaseResp.code == 403 || (releaseResp.code == 404 && token.isEmpty())) {
        synchronized (this) {
          busy = false;
          lastCheckedAt = checkedNow;
          prefs.edit().putLong("last_checked_at", checkedNow).apply();
          available = null;
          apkAssetUrl = null;
          status = "needs_auth";
          error = "Private repo: add a read-only GitHub token below.";
          message = null;
        }
        attempt.record("failed", "update_auth");
        Store.get(context).diagnostic("updater_needs_auth");
        notifyListeners();
        return;
      }
      if (releaseResp.code == 404) {
        attempt.record("skipped", "update_no_asset");
        synchronized (this) {
          busy = false;
          lastCheckedAt = checkedNow;
          prefs.edit().putLong("last_checked_at", checkedNow).apply();
          available = null;
          apkAssetUrl = null;
          status = "up_to_date";
          error = null;
          message = "No nightly release published yet.";
        }
        notifyListeners();
        return;
      }
      if (releaseResp.code < 200 || releaseResp.code >= 300) {
        throw new IOException("GitHub returned HTTP " + releaseResp.code);
      }

      String manifestUrl = UpdateManifest.findReleaseAssetUrl(releaseResp.body, MANIFEST_ASSET_NAME);
      if (manifestUrl == null) {
        synchronized (this) {
          busy = false;
          lastCheckedAt = checkedNow;
          prefs.edit().putLong("last_checked_at", checkedNow).apply();
          available = null;
          apkAssetUrl = null;
          status = "up_to_date";
          error = null;
          message = "No Android nightly build published yet.";
        }
        attempt.record("skipped", "update_no_asset");
        Store.get(context).diagnostic("updater_no_android_asset");
        notifyListeners();
        return;
      }

      byte[] manifestBytes = downloadAssetBytes(manifestUrl, token, MAX_MANIFEST_BYTES, attempt);
      attempt.failure = "update_invalid_manifest";
      UpdateManifest manifest = UpdateManifest.parseManifest(new String(manifestBytes, StandardCharsets.UTF_8));
      attempt.target = manifest;
      if (!context.getPackageName().equals(manifest.packageName)) {
        throw new IOException("Manifest package mismatch: " + manifest.packageName);
      }

      String foundApkUrl = UpdateManifest.findReleaseAssetUrl(releaseResp.body, manifest.apkName);
      if (foundApkUrl == null) {
        attempt.failure = "update_no_asset";
        throw new IOException("Nightly release is missing " + manifest.apkName);
      }

      boolean isNewer = UpdateManifest.isUpdateAvailable(
          installedVersionCode(), BuildInfo.COMMIT_SHA, BuildInfo.BUILD_ID, manifest);

      boolean shouldNotify = false;
      synchronized (this) {
        busy = false;
        lastCheckedAt = checkedNow;
        SharedPreferences.Editor ed = prefs.edit().putLong("last_checked_at", checkedNow);
        if (isNewer) {
          available = manifest;
          apkAssetUrl = foundApkUrl;
          status = "available";
          error = null;
          message = null;
          ed.putString("cached_manifest", manifest.toJsonString());
          ed.putString("cached_apk_url", foundApkUrl);
          String notifiedSha = prefs.getString("notified_sha", "");
          if (silent && !manifest.commitSha.equalsIgnoreCase(notifiedSha)) {
            ed.putString("notified_sha", manifest.commitSha);
            shouldNotify = true;
          }
        } else {
          available = null;
          apkAssetUrl = null;
          status = "up_to_date";
          error = null;
          message = "You're on the latest nightly (" + BuildInfo.BUILD_ID + ").";
          ed.remove("cached_manifest").remove("cached_apk_url");
        }
        ed.apply();
      }

      attempt.record("ok", "ok");
      Store.get(context).diagnostic(isNewer
          ? "updater_available_" + manifest.buildId
          : "updater_up_to_date_" + BuildInfo.BUILD_ID);
      if (shouldNotify) {
        FocusNotifications.maybeSendUpdateAvailable(context, manifest);
      }
      notifyListeners();
    } catch (Exception e) {
      synchronized (this) {
        busy = false;
        if (!silent) {
          status = "error";
          error = e.getMessage() == null ? "Update check failed." : e.getMessage();
          message = null;
        }
      }
      attempt.record("failed", attempt.failure);
      Store.get(context).diagnostic("updater_check_failed_" + e.getClass().getSimpleName());
      notifyListeners();
    }
  }

  public void install(Activity activity) {
    if (!canInstallPackages()) {
      synchronized (this) {
        error = null;
        message = "Allow \"Install unknown apps\" for Focus, then tap Update again.";
      }
      notifyListeners();
      openInstallPermissionSettings(activity);
      return;
    }

    final UpdateManifest target;
    final String url;
    final String token;
    synchronized (this) {
      if (busy) return;
      if (available == null || apkAssetUrl == null || apkAssetUrl.isEmpty()) {
        error = "Check for updates first.";
        notifyListeners();
        return;
      }
      busy = true;
      target = available;
      url = apkAssetUrl;
      token = prefs.getString("github_token", "");
      status = "downloading";
      progressPct = 0;
      error = null;
      message = "Downloading nightly " + target.buildId + "…";
      notifyListeners();
    }

    new Thread(() -> runDownloadAndInstall(target, url, token), "focus-updater-install").start();
  }

  private void runDownloadAndInstall(UpdateManifest target, String url, String token) {
    UpdateAttempt attempt = new UpdateAttempt("download", "update_download");
    attempt.target = target;
    attempt.limit = UpdateManifest.APK_SANITY_LIMIT;
    File apkFile = stagedApkFile();
    try {
      File parent = apkFile.getParentFile();
      if (!parent.isDirectory() && !parent.mkdirs()) {
        throw new IOException("Could not create the update download directory.");
      }
      if (apkFile.exists() && !apkFile.delete()) {
        throw new IOException("Could not clear the previous update download.");
      }

      downloadAssetToFile(url, token, apkFile, target.apkSize, attempt);
      attempt.advance("verify", "update_verify");

      synchronized (this) {
        status = "installing";
        progressPct = 100;
        message = "Verifying APK signature and hash…";
      }
      notifyListeners();

      PackageInfo archive = verifiedApk(context, apkFile, target);
      attempt.advance("install", "update_install");
      Store.get(context).diagnostic("updater_verified_" + target.buildId);

      long installBytes = apkFile.length();
      if ((archive.applicationInfo.flags & ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0) {
        installBytes = Math.addExact(installBytes, nativeExtractionBytes(apkFile));
      }
      attempt.failure = "update_no_space";
      UpdateManifest.checkFreeSpace(installBytes, parent.getUsableSpace());
      attempt.failure = "update_install";

      synchronized (this) {
        message = "Opening Android installer…";
      }
      notifyListeners();

      attempt.record("started", "ok");
      commitPackageInstallerSession(apkFile, target.buildId, attempt);
    } catch (Exception e) {
      if (apkFile.exists()) apkFile.delete();
      synchronized (this) {
        busy = false;
        status = available != null ? "available" : "error";
        progressPct = 0;
        message = null;
        error = e.getMessage() == null ? "Update failed." : e.getMessage();
      }
      attempt.record("failed", attempt.failure);
      Store.get(context).diagnostic("updater_install_error_" + e.getClass().getSimpleName());
      notifyListeners();
    }
  }

  /**
   * Verifies downloaded APK integrity and signing continuity before handing it to Android's
   * PackageInstaller. Exposed for emulator instrumentation verification.
   */
  public static void verifyApkForInstall(Context c, File apkFile, UpdateManifest manifest) throws IOException {
    verifiedApk(c, apkFile, manifest);
  }

  private static PackageInfo verifiedApk(Context c, File apkFile, UpdateManifest manifest) throws IOException {
    UpdateManifest.verifyFile(apkFile, manifest.apkSize, manifest.apkSha256);

    PackageManager pm = c.getPackageManager();
    int flags = PackageManager.GET_SIGNING_CERTIFICATES;
    PackageInfo archive = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), flags);
    if (archive == null) {
      throw new IOException("Downloaded file is not a valid Android APK.");
    }
    if (!c.getPackageName().equals(archive.packageName)) {
      throw new IOException("APK package name mismatch (" + archive.packageName + ").");
    }
    if (archive.getLongVersionCode() != manifest.versionCode) {
      throw new IOException("APK version code does not match manifest.");
    }
    PackageInfo installed;
    try {
      installed = pm.getPackageInfo(c.getPackageName(), flags);
    } catch (PackageManager.NameNotFoundException e) {
      throw new IOException("Installed package info unavailable.");
    }
    if (archive.getLongVersionCode() < installed.getLongVersionCode()) {
      throw new IOException("APK version is older than the installed app.");
    }
    Set<String> installedCerts = certFingerprints(installed.signingInfo);
    Set<String> archiveCerts = certFingerprints(archive.signingInfo);
    if (installedCerts.isEmpty() || !installedCerts.equals(archiveCerts)) {
      throw new IOException(
          "APK signing key does not match this phone's install. Update GitHub Secrets with the same release.jks.");
    }
    return archive;
  }

  private static long nativeExtractionBytes(File apkFile) throws IOException {
    try (ZipFile apk = new ZipFile(apkFile)) {
      for (String abi : Build.SUPPORTED_ABIS) {
        String prefix = "lib/" + abi + "/";
        long bytes = 0;
        for (Enumeration<? extends ZipEntry> entries = apk.entries(); entries.hasMoreElements();) {
          ZipEntry entry = entries.nextElement();
          String name = entry.getName();
          if (name.startsWith(prefix) && name.endsWith(".so") && name.indexOf('/', prefix.length()) < 0) {
            bytes = Math.addExact(bytes, entry.getSize());
          }
        }
        if (bytes > 0) return bytes;
      }
      return 0;
    }
  }

  private static Set<String> certFingerprints(SigningInfo info) {
    Set<String> out = new HashSet<>();
    if (info == null) return out;
    Signature[] sigs = info.hasMultipleSigners()
        ? info.getApkContentsSigners()
        : info.getSigningCertificateHistory();
    if (sigs == null) return out;
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      for (Signature s : sigs) {
        out.add(UpdateManifest.toHex(md.digest(s.toByteArray())));
      }
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  private void commitPackageInstallerSession(File apkFile, String buildId, UpdateAttempt attempt) throws IOException {
    PackageInstaller installer = context.getPackageManager().getPackageInstaller();
    PackageInstaller.SessionParams params =
        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
    params.setAppPackageName(context.getPackageName());
    params.setSize(apkFile.length());
    if (Build.VERSION.SDK_INT >= 31) {
      params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
    }

    int sessionId = installer.createSession(params);
    try (PackageInstaller.Session session = installer.openSession(sessionId)) {
      try (OutputStream out = session.openWrite("focus-phone-" + buildId + ".apk", 0, apkFile.length());
          InputStream in = new BufferedInputStream(new FileInputStream(apkFile))) {
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
          out.write(buf, 0, n);
        }
        session.fsync(out);
      }

      // Explicit Intent + FLAG_MUTABLE is required on Android 12-15 so PackageInstaller can
      // attach EXTRA_STATUS and EXTRA_INTENT for user confirmation.
      Intent callback = new Intent(context, UpdateInstallReceiver.class)
          .putExtra("event_began", attempt.began);
      try {
        callback.putExtra("event_fields", attempt.fields().toString());
      } catch (JSONException ignored) {}
      int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
      PendingIntent pi = PendingIntent.getBroadcast(context, sessionId, callback, flags);
      session.commit(pi.getIntentSender());
    } catch (IOException | RuntimeException e) {
      try { installer.abandonSession(sessionId); } catch (RuntimeException ignored) {}
      throw e;
    }
  }

  synchronized void onInstallResult(int installStatus, String detail) {
    File f = stagedApkFile();
    if (f.exists()) f.delete();
    busy = false;
    progressPct = 0;
    if (installStatus == PackageInstaller.STATUS_SUCCESS) {
      status = "up_to_date";
      available = null;
      apkAssetUrl = null;
      error = null;
      message = "Updated cleanly.";
      prefs.edit().remove("cached_manifest").remove("cached_apk_url").apply();
      Store.get(context).diagnostic("updater_install_success");
    } else if (installStatus == PackageInstaller.STATUS_FAILURE_ABORTED) {
      status = available != null ? "available" : "idle";
      error = null;
      message = detail;
      Store.get(context).diagnostic("updater_install_cancelled");
    } else {
      status = available != null ? "available" : "error";
      message = null;
      error = detail;
      Store.get(context).diagnostic("updater_install_failed_" + installStatus);
    }
    notifyListeners();
  }

  private File stagedApkFile() {
    return new File(new File(context.getCacheDir(), "updates"), "focus-phone.new.apk");
  }

  private boolean isOnline() {
    ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    if (cm == null) return false;
    Network net = cm.getActiveNetwork();
    if (net == null) return false;
    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
    return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
  }

  private static final class HttpResult {
    final int code;
    final String body;
    HttpResult(int code, String body) {
      this.code = code;
      this.body = body;
    }
  }

  private HttpResult httpGet(String url, String accept, String token, UpdateAttempt attempt) throws IOException {
    if (!UpdateManifest.isAllowedApiUrl(url)) {
      throw new IOException("Refusing unexpected GitHub API URL");
    }
    HttpURLConnection conn = openConnection(url, accept, token);
    try {
      Metric.UPDATER_REQUEST.count(1);
      int code = conn.getResponseCode();
      attempt.status = code;
      InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
      String body = stream == null ? "" : new String(readBounded(stream, 512 * 1024), StandardCharsets.UTF_8);
      return new HttpResult(code, body);
    } finally {
      conn.disconnect();
    }
  }

  /**
   * Downloads a GitHub Release asset via the API URL (`Accept: application/octet-stream`),
   * manually following a single HTTPS redirect WITHOUT forwarding the GitHub Bearer token
   * (Azure/S3 signed blob URLs reject extra Authorization headers with HTTP 400).
   */
  private HttpURLConnection openAssetConnection(String assetApiUrl, String token, UpdateAttempt attempt) throws IOException {
    if (!UpdateManifest.isAllowedApiUrl(assetApiUrl)) {
      throw new IOException("Refusing unexpected GitHub asset URL");
    }
    HttpURLConnection first = openConnection(assetApiUrl, "application/octet-stream", token);
    Metric.UPDATER_REQUEST.count(1);
    int code = first.getResponseCode();
    attempt.status = code;
    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
      String location = first.getHeaderField("Location");
      first.disconnect();
      if (!UpdateManifest.isAllowedDownloadRedirect(location)) {
        throw new IOException("Refusing non-HTTPS download redirect");
      }
      HttpURLConnection redirected = openConnection(location, null, "");
      Metric.UPDATER_REQUEST.count(1);
      int rCode = redirected.getResponseCode();
      attempt.status = rCode;
      if (rCode < 200 || rCode >= 300) {
        attempt.failure = rCode == 401 || rCode == 403 ? "update_auth" : "update_http";
        redirected.disconnect();
        throw new IOException("Download failed with HTTP " + rCode);
      }
      return redirected;
    }
    if (code < 200 || code >= 300) {
      attempt.failure = code == 401 || code == 403 ? "update_auth" : "update_http";
      first.disconnect();
      throw new IOException("Asset request failed with HTTP " + code);
    }
    return first;
  }

  private HttpURLConnection openConnection(String url, String accept, String token) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setInstanceFollowRedirects(false);
    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
    conn.setReadTimeout(READ_TIMEOUT_MS);
    conn.setRequestMethod("GET");
    conn.setRequestProperty("User-Agent", "FocusPhone/" + BuildInfo.BUILD_ID);
    conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
    if (accept != null) conn.setRequestProperty("Accept", accept);
    if (token != null && !token.isEmpty()) {
      conn.setRequestProperty("Authorization", "Bearer " + token);
    }
    return conn;
  }

  private byte[] downloadAssetBytes(String assetApiUrl, String token, int maxBytes, UpdateAttempt attempt) throws IOException {
    HttpURLConnection conn = openAssetConnection(assetApiUrl, token, attempt);
    try (InputStream in = conn.getInputStream()) {
      return readBounded(in, maxBytes);
    } finally {
      conn.disconnect();
    }
  }

  private void downloadAssetToFile(String assetApiUrl, String token, File dest, long expectedSize,
      UpdateAttempt attempt) throws IOException {
    attempt.failure = expectedSize > UpdateManifest.APK_SANITY_LIMIT ? "apk_too_large"
        : expectedSize <= 0 ? "update_invalid_manifest" : "update_no_space";
    UpdateManifest.checkDownloadSize(expectedSize, dest.getParentFile().getUsableSpace());
    attempt.limit = expectedSize;
    attempt.failure = "update_download";
    HttpURLConnection conn = openAssetConnection(assetApiUrl, token, attempt);
    try (InputStream in = conn.getInputStream();
        OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
      byte[] buf = new byte[32 * 1024];
      long total = 0;
      int lastPct = -1;
      int n;
      // Read at most one byte beyond the declared size to detect an oversized stream.
      while ((n = in.read(buf, 0, (int) Math.min(buf.length, expectedSize - total + 1))) != -1) {
        Metric.UPDATER_RX.count(n);
        total += n;
        attempt.actual = total;
        if (total > expectedSize) attempt.failure = "apk_too_large";
        UpdateManifest.checkApkSize(total, expectedSize, false);
        out.write(buf, 0, n);
        int pct = (int) Math.min(99, (total * 100L) / expectedSize);
        if (pct - lastPct >= 10) {
          lastPct = pct;
          synchronized (this) {
            progressPct = pct;
            message = "Downloading… " + pct + "%";
          }
          notifyListeners();
        }
      }
      UpdateManifest.checkApkSize(total, expectedSize, true);
      out.flush();
    } finally {
      conn.disconnect();
    }
  }

  private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int total = 0;
    int n;
    while ((n = in.read(buf)) > 0) {
      Metric.UPDATER_RX.count(n);
      total += n;
      if (total > maxBytes) throw new IOException("Response exceeds size limit");
      baos.write(buf, 0, n);
    }
    return baos.toByteArray();
  }

  private void notifyListeners() {
    Intent i = new Intent(ACTION_STATE).setPackage(context.getPackageName());
    context.sendBroadcast(i);
  }

  /** Used only by PhoneDeviceChecks emulator verification to preview the update banner & sheet. */
  public synchronized void seedPreviewUpdateForTest(UpdateManifest preview) {
    if (preview == null) {
      available = null;
      apkAssetUrl = null;
      status = "idle";
      message = null;
      error = null;
      prefs.edit().remove("cached_manifest").remove("cached_apk_url").remove("dismissed_sha").apply();
    } else {
      available = preview;
      apkAssetUrl = "https://api.github.com/repos/example-org/sample-project/releases/assets/0";
      status = "available";
      message = null;
      error = null;
      // Persist like a real check so the preview survives the app process restarting after instrumentation.
      prefs.edit()
          .remove("dismissed_sha")
          .putString("cached_manifest", preview.toJsonString())
          .putString("cached_apk_url", apkAssetUrl)
          .apply();
    }
    notifyListeners();
  }
}
