package org.nightingalelab.focus.phone;

import android.app.Activity;
import android.app.Dialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Controls the rules stored and enforced by a directly paired TV. */
final class TvRestrictions {
  private final Activity activity;
  private final DeviceHub hub;
  private final String deviceId;
  private final Runnable onChanged;
  private final LinearLayout body, apps;
  private final LinearLayout search;
  private final EditText searchInput;
  private final TextView status, refresh;
  private Dialog dialog;
  private JSONArray catalog;
  private boolean busy, canChangeRules;

  static void show(Activity a, DeviceHub.PairedDevice device, Runnable onChanged) {
    new TvRestrictions(a, device, onChanged).open(device);
  }

  private TvRestrictions(Activity a, DeviceHub.PairedDevice device, Runnable onChanged) {
    activity = a;
    hub = DeviceHub.get(a);
    deviceId = device.id;
    this.onChanged = onChanged;
    body = Theme.column(a);
    apps = Theme.column(a);
    status = Theme.text(a, "", 13, Theme.MUTED, false);
    refresh = Theme.outlinePillButton(a, "Refresh", () -> run(null, null, false));
    search = Theme.row(a);
    search.setBackground(Theme.roundRect(a, Theme.SEC, Theme.LINE, 999));
    search.setPadding(Theme.dp(a, 14), 0, Theme.dp(a, 5), 0);
    Theme.IconView icon = new Theme.IconView(a, Theme.IconView.Kind.SEARCH, Theme.MUTED, 15);
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    search.addView(icon);
    Theme.gap(search, 9);
    searchInput = Theme.appSearchInput(a);
    search.addView(searchInput, new LinearLayout.LayoutParams(0, -2, 1));
    TextView clear = Theme.text(a, "×", 19, Theme.FG, false);
    clear.setGravity(Gravity.CENTER);
    clear.setContentDescription("Clear search");
    clear.setOnClickListener(v -> searchInput.setText(""));
    clear.setVisibility(View.INVISIBLE);
    search.addView(clear, new LinearLayout.LayoutParams(Theme.dp(a, 42), Theme.dp(a, 42)));
    searchInput.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
      @Override public void afterTextChanged(Editable s) {
        clear.setVisibility(s.length() == 0 ? View.INVISIBLE : View.VISIBLE);
        renderApps();
      }
    });
    body.addView(status);
    body.addView(refresh);
    body.addView(search);
    body.addView(apps);
  }

  private void open(DeviceHub.PairedDevice device) {
    body.setFocusableInTouchMode(true);
    body.requestFocus();
    dialog = SettingsDialogs.sheetDialog(activity, "App restrictions", body);
    dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    render(false, "");
    if (direct(device)) run(null, null, false);
  }

  private static boolean direct(DeviceHub.PairedDevice device) {
    return device != null && !device.relayed && device.token != null && !device.token.isEmpty()
        && device.address != null && !device.address.isEmpty();
  }

  private void render(boolean online, String message) {
    DeviceHub.PairedDevice device = hub.findById(deviceId);
    JSONObject snapshot = device == null ? null : device.snapshot;
    JSONObject permissions = snapshot == null ? null : snapshot.optJSONObject("permissions");
    catalog = snapshot == null ? null : snapshot.optJSONArray("apps");
    boolean paired = direct(device);
    boolean ready = permissions != null && permissions.optBoolean("usage")
        && permissions.optBoolean("restrictions");
    if (!paired) message = "Pair directly with the TV in Settings → Devices.";
    else if (message.isEmpty() && online && !ready)
      message = "Enable Usage Access and Accessibility on the TV.";
    else if (message.isEmpty() && online && (catalog == null || catalog.length() == 0))
      message = "No TV apps available.";
    status.setText(message);
    status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    refresh.setVisibility(paired && (!online || !ready) ? View.VISIBLE : View.GONE);
    canChangeRules = paired && online && ready;
    search.setVisibility(catalog != null && catalog.length() > 0 ? View.VISIBLE : View.GONE);
    renderApps();
  }

  private void renderApps() {
    apps.removeAllViews();
    if (catalog == null) return;
    String query = searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
    for (int i = 0; i < catalog.length(); i++) {
      JSONObject app = catalog.optJSONObject(i);
      if (app == null || app.optString("package").isEmpty()) continue;
      String label = app.optString("label", app.optString("package"));
      if (label.toLowerCase(Locale.ROOT).contains(query)) addApp(app, canChangeRules && !busy);
    }
    if (!query.isEmpty() && catalog.length() > 0 && apps.getChildCount() == 0) {
      TextView empty = Theme.text(activity, "No matching apps", 13, Theme.MUTED, false);
      empty.setPadding(0, Theme.dp(activity, 12), 0, 0);
      apps.addView(empty);
    }
  }

  private void addApp(JSONObject app, boolean enabled) {
    String pkg = app.optString("package");
    String label = app.optString("label", pkg);
    boolean blocked = app.optBoolean("blocked");
    LinearLayout row = Theme.row(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(Theme.dp(activity, 52));
    row.addView(Theme.text(activity, label, 15, Theme.FG, true),
        new LinearLayout.LayoutParams(0, -2, 1));
    TextView button = Theme.outlinePillButton(activity, blocked ? "Unblock" : "Block",
        () -> run(pkg, label, !blocked));
    button.setContentDescription((blocked ? "Unblock " : "Block ") + label);
    button.setEnabled(enabled);
    button.setAlpha(enabled ? 1f : .5f);
    row.addView(button);
    apps.addView(row);
  }

  private void run(String pkg, String label, boolean blocked) {
    if (busy) return;
    busy = true;
    refresh.setEnabled(false);
    renderApps();
    status.setText(pkg == null ? "Connecting to TV…" : "Updating " + label + "…");
    status.setVisibility(View.VISIBLE);
    new Thread(() -> {
      boolean online;
      String message;
      try {
        if (pkg == null) {
          online = hub.refreshOne(deviceId);
          message = online ? "" : "TV unreachable. Wake it and try again.";
        } else {
          hub.sendCommand(deviceId, new JSONObject()
              .put("action", "set_rule").put("package", pkg).put("blocked", blocked));
          online = true;
          message = label + (blocked ? " blocked on the TV." : " unblocked on the TV.");
        }
      } catch (Exception e) {
        online = false;
        message = e.getMessage() == null ? "Could not update the TV. Refresh and try again." : e.getMessage();
      }
      boolean reached = online;
      String result = message;
      activity.runOnUiThread(() -> {
        busy = false;
        if (dialog.isShowing()) {
          refresh.setEnabled(true);
          render(reached, result);
        }
        if (reached && !activity.isFinishing()) onChanged.run();
      });
    }, "focus-tv-rules").start();
  }
}
