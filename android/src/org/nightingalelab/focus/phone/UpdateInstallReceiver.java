package org.nightingalelab.focus.phone;

import android.content.*;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Receives Android PackageInstaller session results. Launches the 1-tap system confirmation
 * dialog on STATUS_PENDING_USER_ACTION and reports terminal outcomes back to PhoneUpdater.
 */
public final class UpdateInstallReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null) return;
    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
    String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
      Intent confirm;
      if (Build.VERSION.SDK_INT >= 33) {
        confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
      } else {
        @SuppressWarnings("deprecation")
        Intent legacy = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        confirm = legacy;
      }
      if (confirm != null) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
          context.startActivity(confirm);
          Store.get(context).diagnostic("updater_install_prompt");
        } catch (RuntimeException e) {
          recordResult(context, intent, PackageInstaller.STATUS_FAILURE);
          PhoneUpdater.get(context).onInstallResult(
              PackageInstaller.STATUS_FAILURE,
              "Could not open Android installer: " + e.getMessage());
        }
      } else {
        recordResult(context, intent, PackageInstaller.STATUS_FAILURE);
        PhoneUpdater.get(context).onInstallResult(
            PackageInstaller.STATUS_FAILURE,
            "Android did not provide an install confirmation dialog.");
      }
      return;
    }

    recordResult(context, intent, status);
    PhoneUpdater.get(context).onInstallResult(status, friendlyError(status, message));
  }

  private static void recordResult(Context context, Intent intent, int status) {
    String raw = intent.getStringExtra("event_fields");
    if (raw == null) return; // Callback from an older build.
    try {
      JSONObject fields = new JSONObject(raw).put("install_status", status);
      boolean ok = status == PackageInstaller.STATUS_SUCCESS;
      boolean cancelled = status == PackageInstaller.STATUS_FAILURE_ABORTED;
      long elapsed = SystemClock.elapsedRealtime() - intent.getLongExtra("event_began", 0);
      FocusEvents.record(context, "update", "install", ok ? "ok" : "failed",
          ok ? "ok" : cancelled ? "update_cancelled" : "update_install", Math.max(0, elapsed), fields);
    } catch (JSONException ignored) {}
  }

  private static String friendlyError(int status, String raw) {
    switch (status) {
      case PackageInstaller.STATUS_SUCCESS:
        return "Updated.";
      case PackageInstaller.STATUS_FAILURE_ABORTED:
        return "Update cancelled.";
      case PackageInstaller.STATUS_FAILURE_CONFLICT:
        return "Signing key does not match the installed app.";
      case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
        return "This APK is incompatible with this phone.";
      case PackageInstaller.STATUS_FAILURE_STORAGE:
        return "Not enough storage space to install update.";
      case PackageInstaller.STATUS_FAILURE_BLOCKED:
        return "Install was blocked by Android settings.";
      default:
        if (raw != null && !raw.trim().isEmpty()) return "Install failed: " + raw.trim();
        return "Install failed (status " + status + ").";
    }
  }
}
