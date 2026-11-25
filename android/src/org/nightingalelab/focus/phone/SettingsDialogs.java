package org.nightingalelab.focus.phone;

import android.app.*;
import android.content.ActivityNotFoundException;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** Modal sheets for Settings rows: This Phone, Add Device, Paired Device, Pause Rules, Categories, Profile. */
public final class SettingsDialogs {

  static Dialog sheetDialog(Activity a, String title, View body) {
    Dialog d = new Dialog(a);
    d.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout root = Theme.column(a);
    root.setBackground(Theme.obsidianGlassTop(a, 24));
    root.setPadding(Theme.dp(a, 18), Theme.dp(a, 10), Theme.dp(a, 18), Theme.dp(a, 22));

    View grab = new View(a);
    grab.setBackground(Theme.roundRect(a, 0xFF333333, 0, 9));
    LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(Theme.dp(a, 36), Theme.dp(a, 4));
    glp.gravity = Gravity.CENTER_HORIZONTAL;
    glp.bottomMargin = Theme.dp(a, 12);
    root.addView(grab, glp);

    root.addView(Theme.sheetHeader(a, title, d::dismiss));
    Theme.gap(root, 8);
    root.addView(body);

    ScrollView sv = new ScrollView(a);
    sv.addView(root);
    d.setContentView(sv);
    Window w = d.getWindow();
    if (w != null) {
      w.setBackgroundDrawableResource(android.R.color.transparent);
      w.setGravity(Gravity.BOTTOM);
      w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
      w.setDimAmount(0.72f);
      Theme.applySheetBlur(w);
    }
    d.show();
    return d;
  }

  public static void showPhoneSheet(Activity a, Runnable onChanged) {
    Store store = Store.get(a);
    DnsController dns = DnsController.get(a);
    PausePolicy.Config cfg = store.policyConfig();

    LinearLayout body = Theme.column(a);
    LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

    // Permissions summary
    boolean usageOk = store.usageAllowed();
    boolean accOk = FocusAccessibilityService.isEnabled(a);
    boolean dnsPermOk = dns.permitted();
    AlarmManager am = a.getSystemService(AlarmManager.class);
    boolean alarmOk = Build.VERSION.SDK_INT < 31 || (am != null && am.canScheduleExactAlarms());

    body.addView(statusRow(a, "Usage Access", usageOk ? "On" : "Off — tap to enable", usageOk, () ->
        a.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))));
    body.addView(statusRow(a, "Accessibility (guard + Chrome URLs)", accOk ? "On" : "Off — tap to enable", accOk, () ->
        a.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
    body.addView(statusRow(a, "Exact alarms", alarmOk ? "On" : "Off — tap to allow", alarmOk, () -> {
      if (Build.VERSION.SDK_INT >= 31) {
        a.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:" + a.getPackageName())));
      }
    }));
    if (Build.VERSION.SDK_INT >= 36) {
      NotificationManager notifications = a.getSystemService(NotificationManager.class);
      boolean allowed = notifications != null && notifications.canPostPromotedNotifications();
      body.addView(statusRow(a, "Live notifications", allowed ? "Allowed by Android" : "Tap to allow",
          allowed, () -> {
            Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName());
            try {
              a.startActivity(settings);
            } catch (ActivityNotFoundException e) {
              a.startActivity(settings.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS));
            }
          }));
    }
    body.addView(statusRow(a, "Private DNS control (ADB)", dnsPermOk ? "Granted" : "Needs one-time ADB grant", dnsPermOk, null));

    if (!dnsPermOk) {
      Theme.gap(body, 6);
      TextView cmd = Theme.text(a,
          "adb shell pm grant org.nightingalelab.focus.phone android.permission.WRITE_SECURE_SETTINGS",
          11, Theme.MUTED, false);
      cmd.setPadding(Theme.dp(a, 10), Theme.dp(a, 8), Theme.dp(a, 10), Theme.dp(a, 8));
      cmd.setBackground(Theme.roundRect(a, Theme.BG, Theme.LINE, 10));
      body.addView(cmd, full);
    }

    Theme.gap(body, 14);
    body.addView(Theme.text(a, "NextDNS · " + dns.status(), 12.5f, Theme.FG, true));
    Theme.gap(body, 8);
    TextView feedback = Theme.text(a, "", 11.5f, Theme.ATTN, false);
    feedback.setVisibility(View.GONE);
    body.addView(feedback);

    final Dialog[] dlg = new Dialog[1];
    if (dnsPermOk) {
      if (dns.profile().isEmpty()) {
        body.addView(Theme.primaryPillButton(a, "Set NextDNS profile", () -> {
          if (dlg[0] != null) dlg[0].dismiss();
          showProfileSheet(a, onChanged);
        }), full);
      } else if (!dns.enabled()) {
        body.addView(Theme.primaryPillButton(a, "Connect NextDNS (" + dns.profile() + ")", () -> {
          try {
            dns.connect(dns.profile());
            dns.checkAsync(onChanged);
            onChanged.run();
            if (dlg[0] != null) dlg[0].dismiss();
          } catch (RuntimeException e) {
            feedback.setText(e.getMessage());
            feedback.setVisibility(View.VISIBLE);
          }
        }), full);
      } else {
        LinearLayout actions = Theme.row(a);
        TextView verify = Theme.outlinePillButton(a, "Verify now", () -> {
          dns.checkAsync(() -> {
            feedback.setText("Status: " + dns.status());
            feedback.setTextColor(Theme.FG);
            feedback.setVisibility(View.VISIBLE);
            onChanged.run();
          });
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        actions.addView(verify, half);
        Theme.gap(actions, 8);
        TextView guardToggle = Theme.outlinePillButton(a,
            cfg.guardPrivateDns ? "Guard: On" : "Guard: Off", () -> {
              PausePolicy.UpdateOutcome out = store.updateGuardPrivateDns(!cfg.guardPrivateDns);
              feedback.setText(out.appliedNow
                  ? "Private DNS guard updated."
                  : "Loosening Private DNS guard starts tomorrow.");
              feedback.setTextColor(out.appliedNow ? Theme.FG : Theme.ATTN);
              feedback.setVisibility(View.VISIBLE);
              onChanged.run();
            });
        actions.addView(guardToggle, half);
        body.addView(actions, full);
      }
    }

    Theme.gap(body, 14);
    LanController lan = LanController.get(a);
    body.addView(Theme.text(a,
        "Wi-Fi sync · " + (lan.enabled() ? "Listening (" + lan.pairedManagersCount() + " paired)" : "Off"),
        11.5f, Theme.MUTED, false));

    Theme.gap(body, 10);
    List<String> logs = store.recentDiagnostics(8);
    if (!logs.isEmpty()) {
      body.addView(Theme.text(a, "Recent diagnostics", 11, Theme.DIM, false));
      Theme.gap(body, 4);
      TextView logView = Theme.text(a, String.join("\n", logs), 10.5f, Theme.MUTED, false);
      logView.setPadding(Theme.dp(a, 10), Theme.dp(a, 8), Theme.dp(a, 10), Theme.dp(a, 8));
      logView.setBackground(Theme.roundRect(a, Theme.BG, Theme.LINE, 10));
      body.addView(logView, full);
    }

    TextView shareDiagnostics = Theme.text(a, "Share diagnostics", 13, Theme.FG, false);
    shareDiagnostics.setMinHeight(Theme.dp(a, 48));
    shareDiagnostics.setGravity(android.view.Gravity.CENTER_VERTICAL);
    shareDiagnostics.setOnClickListener(v -> FocusEventsExport.share(a));
    body.addView(shareDiagnostics, full);

    dlg[0] = sheetDialog(a, "This phone", body);
  }

  private static View statusRow(Activity a, String label, String status, boolean ok, Runnable onClick) {
    LinearLayout r = Theme.row(a);
    r.setPadding(0, Theme.dp(a, 8), 0, Theme.dp(a, 8));
    View dot = new View(a);
    dot.setBackground(Theme.roundRect(a, ok ? Theme.KNOB_ON : Theme.DIM, 0, 99));
    r.addView(dot, new LinearLayout.LayoutParams(Theme.dp(a, 7), Theme.dp(a, 7)));
    Theme.gap(r, 10);
    LinearLayout col = Theme.column(a);
    col.addView(Theme.text(a, label, 13, Theme.FG, false));
    col.addView(Theme.text(a, status, 11, Theme.MUTED, false));
    r.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    if (onClick != null) {
      r.addView(new Theme.IconView(a, Theme.IconView.Kind.CHEVRON, Theme.DIM, 13));
      r.setOnClickListener(v -> onClick.run());
    }
    return r;
  }

  public static void showAddDeviceSheet(Activity a, Runnable onChanged) {
    LanDiscovery discovery = LanController.get(a).discovery();
    LinearLayout body = Theme.column(a);
    LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

    TextView statusText = Theme.text(a, "Searching Wi-Fi for Focus TV and Laptop…", 12, Theme.MUTED, false);
    body.addView(statusText);
    Theme.gap(body, 10);

    LinearLayout foundList = Theme.column(a);
    body.addView(foundList, full);

    Theme.gap(body, 14);
    body.addView(Theme.text(a, "Or connect by local IP", 11, Theme.MUTED, false));
    Theme.gap(body, 6);
    EditText ipField = new EditText(a);
    ipField.setHint("192.0.2.20:47832");
    ipField.setHintTextColor(Theme.DIM);
    ipField.setTextColor(Theme.FG);
    ipField.setTextSize(13.5f);
    ipField.setPadding(Theme.dp(a, 12), Theme.dp(a, 10), Theme.dp(a, 12), Theme.dp(a, 10));
    ipField.setBackground(Theme.roundRect(a, Theme.BG, Theme.LINE, 12));
    body.addView(ipField, full);

    Theme.gap(body, 10);
    TextView pairCodeView = Theme.text(a, "", 28, Theme.FG, true);
    pairCodeView.setGravity(Gravity.CENTER);
    pairCodeView.setVisibility(View.GONE);
    body.addView(pairCodeView, full);

    TextView errorView = Theme.text(a, "", 12, Theme.ATTN, false);
    errorView.setVisibility(View.GONE);
    body.addView(errorView, full);

    final DeviceHub.PendingPair[] activePair = new DeviceHub.PendingPair[1];
    final boolean[] cancelled = {false};
    final Dialog[] dlg = new Dialog[1];

    java.util.function.Consumer<String> beginWithAddress = addr -> {
      errorView.setVisibility(View.GONE);
      statusText.setText("Contacting " + addr + "…");
      new Thread(() -> {
        try {
          DeviceHub hub = DeviceHub.get(a);
          DeviceHub.NearbyPeer peer = hub.probe(addr);
          DeviceHub.PendingPair pending = hub.startApproval(peer);
          activePair[0] = pending;
          String fmtCode = pending.code.substring(0, 3) + " " + pending.code.substring(3);
          a.runOnUiThread(() -> {
            statusText.setText("Approve on " + peer.name + " if both screens match:");
            pairCodeView.setText(fmtCode);
            pairCodeView.setVisibility(View.VISIBLE);
          });
          for (int i = 0; i < 60 && !cancelled[0]; i++) {
            Thread.sleep(2000);
            String st = hub.pollApproval(pending);
            if ("approved".equals(st)) {
              a.runOnUiThread(() -> {
                onChanged.run();
                if (dlg[0] != null) dlg[0].dismiss();
              });
              return;
            }
            if ("declined".equals(st) || "expired".equals(st)) {
              a.runOnUiThread(() -> {
                pairCodeView.setVisibility(View.GONE);
                errorView.setText("Pairing " + st + ".");
                errorView.setVisibility(View.VISIBLE);
              });
              return;
            }
          }
        } catch (Exception e) {
          a.runOnUiThread(() -> {
            pairCodeView.setVisibility(View.GONE);
            errorView.setText(e.getMessage() == null ? "Could not reach device" : e.getMessage());
            errorView.setVisibility(View.VISIBLE);
          });
        }
      }, "focus-pair-client").start();
    };

    Theme.gap(body, 10);
    body.addView(Theme.primaryPillButton(a, "Connect", () -> {
      String addr = ipField.getText().toString().trim();
      if (!addr.isEmpty()) beginWithAddress.accept(addr);
    }), full);

    Runnable refreshFound = () -> a.runOnUiThread(() -> {
      foundList.removeAllViews();
      List<LanDiscovery.DiscoveredService> list = discovery.discovered();
      if (list.isEmpty()) {
        foundList.addView(Theme.text(a, "No nearby devices seen yet.", 12, Theme.DIM, false));
        return;
      }
      for (LanDiscovery.DiscoveredService s : list) {
        boolean alreadyConnected = DeviceHub.get(a).isAddressPaired(s.address);
        String kindTag = LanDiscovery.LAPTOP_TYPE.equals(s.serviceType) ? "Laptop" : "TV";
        LinearLayout row = Theme.row(a);
        row.setPadding(Theme.dp(a, 12), Theme.dp(a, 10), Theme.dp(a, 12), Theme.dp(a, 10));
        row.setBackground(Theme.roundRect(a, Theme.CARD, Theme.LINE, 12));
        LinearLayout col = Theme.column(a);
        col.addView(Theme.text(a, s.serviceName, 13, Theme.FG, true));
        col.addView(Theme.text(a, kindTag + " · " + s.address, 11, Theme.MUTED, false));
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Theme.text(a, alreadyConnected ? "Connected" : "Pair", 12,
            alreadyConnected ? Theme.MUTED : Theme.FG, !alreadyConnected));
        if (!alreadyConnected) {
          row.setOnClickListener(v -> beginWithAddress.accept(s.address));
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Theme.dp(a, 6);
        foundList.addView(row, rlp);
      }
    });

    discovery.startBrowsing(refreshFound);
    refreshFound.run();

    dlg[0] = sheetDialog(a, "Add a device", body);
    dlg[0].setOnDismissListener(d -> {
      cancelled[0] = true;
      discovery.stopBrowsing();
      if (activePair[0] != null) {
        DeviceHub.PendingPair p = activePair[0];
        new Thread(() -> DeviceHub.get(a).cancelApproval(p)).start();
      }
    });
  }

  public static void showPairedDeviceSheet(Activity a, DeviceHub.PairedDevice device,
      Runnable onChanged, Runnable onPause) {
    LinearLayout body = Theme.column(a);
    LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

    String dnsStatus = device.snapshot != null && device.snapshot.optJSONObject("dns") != null
        ? device.snapshot.optJSONObject("dns").optString("status", "Unknown")
        : "Not synced yet";
    body.addView(Theme.text(a,
        (device.online ? "Connected · " : "Offline · ") + device.address + " · DNS: " + dnsStatus,
        12, Theme.MUTED, false));

    TextView msg = Theme.text(a, "", 12, Theme.ATTN, false);
    msg.setVisibility(View.GONE);
    Theme.gap(body, 10);
    body.addView(msg);

    final Dialog[] dlg = new Dialog[1];
    if ("tv".equals(device.kind)) {
      body.addView(Theme.outlinePillButton(a, "App restrictions",
          () -> TvRestrictions.show(a, device, onChanged)), full);
      Theme.gap(body, 10);
    }
    if (device.dnsPausedNow(System.currentTimeMillis())) {
      body.addView(Theme.primaryPillButton(a, "Turn NextDNS back on now", () -> {
        new Thread(() -> {
          try {
            DeviceHub.get(a).sendCommand(device.id, new JSONObject().put("action", "dns_resume"));
            a.runOnUiThread(() -> {
              onChanged.run();
              if (dlg[0] != null) dlg[0].dismiss();
            });
          } catch (Exception e) {
            a.runOnUiThread(() -> {
              msg.setText(e.getMessage());
              msg.setVisibility(View.VISIBLE);
            });
          }
        }).start();
      }), full);
      Theme.gap(body, 10);
    }

    if (device.online && !device.relayed && !device.dnsPausedNow(System.currentTimeMillis())) {
      body.addView(Theme.outlinePillButton(a, "Pause " + device.name, () -> {
        if (dlg[0] != null) dlg[0].dismiss();
        onPause.run();
      }), full);
      Theme.gap(body, 10);
    }

    body.addView(Theme.outlinePillButton(a, "Refresh now", () -> {
      new Thread(() -> {
        boolean ok = DeviceHub.get(a).refreshOne(device.id);
        a.runOnUiThread(() -> {
          msg.setText(ok ? "Refreshed." : "Device unreachable right now.");
          msg.setTextColor(ok ? Theme.FG : Theme.ATTN);
          msg.setVisibility(View.VISIBLE);
          onChanged.run();
        });
      }).start();
    }), full);

    Theme.gap(body, 10);
    TextView forget = Theme.outlinePillButton(a, "Forget device", () -> {
      DeviceHub.get(a).forget(device.id);
      onChanged.run();
      if (dlg[0] != null) dlg[0].dismiss();
    });
    forget.setTextColor(Theme.ATTN);
    body.addView(forget, full);

    dlg[0] = sheetDialog(a, device.name, body);
  }

  public static void showDailyLimitSheet(Activity a, Runnable onChanged) {
    Store store = Store.get(a);
    PausePolicy.Config cfg = store.policyConfig();
    LinearLayout body = Theme.column(a);
    LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

    body.addView(Theme.text(a,
        "Shared across all devices. Tightening starts now; loosening starts tomorrow.",
        12, Theme.MUTED, false));
    Theme.gap(body, 14);

    TextView note = Theme.text(a, "", 12, Theme.EXPLAIN, false);
    note.setVisibility(View.GONE);

    body.addView(choiceRow(a, new int[] {30, 45, 60, 90, 120}, cfg.dailyLimitMinutes,
        PausePolicy::formatMinutes, picked -> {
          PausePolicy.UpdateOutcome out = store.updatePolicyInt("daily_limit_minutes", picked);
          note.setText(out.appliedNow
              ? "Daily limit set to " + PausePolicy.formatMinutes(picked) + " right away."
              : "Limit " + PausePolicy.formatMinutes(cfg.dailyLimitMinutes) + " \u2192 "
                  + PausePolicy.formatMinutes(picked) + " starts tomorrow.");
          note.setVisibility(View.VISIBLE);
          onChanged.run();
        }));

    Theme.gap(body, 12);
    body.addView(note, full);
    sheetDialog(a, "Daily DNS-off limit", body);
  }

  public static void showAutoCategoriesSheet(Activity a) {
    LinearLayout body = Theme.column(a);
    LinearLayout card = Theme.column(a);
    card.setBackground(Theme.roundRect(a, Theme.CARD, Theme.LINE, 14));
    card.setPadding(Theme.dp(a, 13), Theme.dp(a, 11), Theme.dp(a, 13), Theme.dp(a, 11));
    card.addView(Theme.text(a, "Sorted automatically", 13.5f, Theme.FG, false));
    body.addView(card, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    Theme.gap(body, 10);
    body.addView(Theme.text(a, "Anything unclear counts as Not sure.", 12, Theme.MUTED, false));
    sheetDialog(a, "App categories", body);
  }

  public static void showPauseLadderSheet(Activity a, Runnable onChanged) {
    Store store = Store.get(a);
    PausePolicy.Config cfg = store.policyConfig();
    LinearLayout body = Theme.column(a);
    LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

    LinearLayout ladder = Theme.column(a);
    ladder.setBackground(Theme.roundRect(a, Theme.CARD, Theme.LINE, 14));
    ladder.setPadding(Theme.dp(a, 13), Theme.dp(a, 10), Theme.dp(a, 13), Theme.dp(a, 10));
    ladder.addView(ladderLine(a, "Pause 1\u2013" + Math.max(1, cfg.waitThreshold - 1), "Reason only"));
    Theme.gap(ladder, 4);
    ladder.addView(ladderLine(a, "Pause " + cfg.waitThreshold + "+", "Wait " + cfg.waitSeconds + "s"));
    body.addView(ladder, full);

    TextView note = Theme.text(a, "", 12, Theme.ATTN, false);
    note.setVisibility(View.GONE);

    Theme.gap(body, 12);
    body.addView(Theme.text(a, "Wait time", 11, Theme.MUTED, false));
    Theme.gap(body, 7);
    body.addView(choiceRow(a, new int[] {10, 15, 30, 45}, cfg.waitSeconds, v -> v + "s", picked -> {
      PausePolicy.UpdateOutcome out = store.updatePolicyInt("wait_seconds", picked);
      note.setText(out.appliedNow ? "Wait is " + picked + "s from now." : picked + "s from tomorrow.");
      note.setVisibility(View.VISIBLE);
      onChanged.run();
    }));

    Theme.gap(body, 12);
    body.addView(Theme.text(a, "Longest pause", 11, Theme.MUTED, false));
    Theme.gap(body, 7);
    body.addView(choiceRow(a, new int[] {5, 10, 30}, cfg.maxPhoneMinutes, PausePolicy::formatDurationChip, picked -> {
      PausePolicy.UpdateOutcome a1 = store.updatePolicyInt("max_phone_minutes", picked);
      store.updatePolicyInt("max_laptop_minutes", picked);
      note.setText(a1.appliedNow
          ? "Longest pause is " + PausePolicy.formatDurationChip(picked) + " from now."
          : PausePolicy.formatDurationChip(picked) + " from tomorrow.");
      note.setVisibility(View.VISIBLE);
      onChanged.run();
    }));

    Theme.gap(body, 10);
    body.addView(Theme.text(a, "Looser rules start tomorrow. Stricter ones start now.", 11.5f, Theme.MUTED, false));
    Theme.gap(body, 8);
    body.addView(note, full);

    sheetDialog(a, "Wait before pausing", body);
  }

  private static View ladderLine(Activity a, String left, String right) {
    LinearLayout row = Theme.row(a);
    row.addView(Theme.text(a, left, 12, Theme.MUTED, false),
        new LinearLayout.LayoutParams(Theme.dp(a, 76), LinearLayout.LayoutParams.WRAP_CONTENT));
    row.addView(Theme.text(a, right, 12, Theme.FG, false));
    return row;
  }

  private static View choiceRow(
      Activity a, int[] values, int current, java.util.function.IntFunction<String> labelFn,
      java.util.function.IntConsumer onPick) {
    LinearLayout row = Theme.row(a);
    TextView[] chips = new TextView[values.length];
    for (int i = 0; i < values.length; i++) {
      final int idx = i;
      final int val = values[i];
      boolean on = val == current;
      TextView chip = Theme.text(a, labelFn.apply(val), 12.5f, on ? Theme.FG : Theme.MUTED, on);
      chip.setPadding(Theme.dp(a, 12), Theme.dp(a, 7), Theme.dp(a, 12), Theme.dp(a, 7));
      chip.setBackground(Theme.roundRect(a, on ? Theme.DARK_ACTIVE : Color.TRANSPARENT, on ? 0xFF3A3A40 : Theme.LINE, 999));
      chips[i] = chip;
      chip.setOnClickListener(v -> {
        for (int j = 0; j < chips.length; j++) {
          boolean sel = j == idx;
          chips[j].setBackground(Theme.roundRect(a, sel ? Theme.DARK_ACTIVE : Color.TRANSPARENT, sel ? 0xFF3A3A40 : Theme.LINE, 999));
          chips[j].setTextColor(sel ? Theme.FG : Theme.MUTED);
        }
        onPick.accept(val);
      });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      if (i > 0) lp.leftMargin = Theme.dp(a, 6);
      row.addView(chip, lp);
    }
    return row;
  }

  public static void showProfileSheet(Activity a, Runnable onChanged) {
    DnsController dns = DnsController.get(a);
    LinearLayout body = Theme.column(a);
    LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

    EditText input = new EditText(a);
    input.setText(dns.profile());
    input.setHint("Profile ID");
    input.setHintTextColor(Theme.DIM);
    input.setTextColor(Theme.FG);
    input.setTextSize(15);
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    input.setPadding(Theme.dp(a, 12), Theme.dp(a, 10), Theme.dp(a, 12), Theme.dp(a, 10));
    input.setBackground(Theme.roundRect(a, Theme.BG, Theme.LINE, 12));
    body.addView(input, full);

    TextView err = Theme.text(a, "", 11.5f, Theme.ATTN, false);
    err.setVisibility(View.GONE);
    Theme.gap(body, 8);
    body.addView(err);

    final Dialog[] dlg = new Dialog[1];
    body.addView(Theme.primaryPillButton(a, "Save profile", () -> {
      try {
        dns.setProfile(input.getText().toString().trim());
        onChanged.run();
        if (dlg[0] != null) dlg[0].dismiss();
      } catch (RuntimeException e) {
        err.setText(e.getMessage());
        err.setVisibility(View.VISIBLE);
      }
    }), full);

    dlg[0] = sheetDialog(a, "NextDNS profile", body);
  }

  /**
   * "Update ready" sheet opened from the update icon in the header: what's new in the build
   * (bold title + one sentence, up to 3 items, plus "Plus N more fixes.") and one button.
   * Falls back to the commit subject when the build shipped without notes.
   */
  public static void showUpdateReadySheet(Activity a, Runnable onChanged) {
    PhoneUpdater updater = PhoneUpdater.get(a);
    PhoneUpdater.Snapshot first = updater.snapshot();
    if (first.available == null) {
      showUpdaterSheet(a, onChanged);
      return;
    }
    LinearLayout body = Theme.column(a);
    LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    body.addView(updateNotesView(a, first.available), full);

    TextView status = Theme.text(a, "", 11.5f, Theme.ATTN, false);
    status.setVisibility(View.GONE);
    Theme.gap(body, 8);
    body.addView(status, full);

    TextView btn = Theme.primaryPillButton(a, "Update & restart", () -> {
      PhoneUpdater.Snapshot s = updater.snapshot();
      boolean busy = "downloading".equals(s.status) || "installing".equals(s.status);
      if (!busy) updater.install(a);
    });
    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    blp.topMargin = Theme.dp(a, 4);
    body.addView(btn, blp);

    Runnable refresh = () -> {
      PhoneUpdater.Snapshot s = updater.snapshot();
      boolean busy = "downloading".equals(s.status) || "installing".equals(s.status);
      btn.setText(busy ? (s.message != null ? s.message : "Updating\u2026") : "Update & restart");
      btn.setAlpha(busy ? 0.55f : 1f);
      String line = s.error != null && !s.error.isEmpty() ? s.error
          : !busy && !s.canInstallPackages ? "Android will ask you to allow installs from Focus first."
          : "";
      status.setText(line);
      status.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
    };
    refresh.run();

    BroadcastReceiver rx = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        a.runOnUiThread(() -> {
          refresh.run();
          onChanged.run();
        });
      }
    };
    IntentFilter filter = new IntentFilter(PhoneUpdater.ACTION_STATE);
    if (Build.VERSION.SDK_INT >= 33) {
      a.registerReceiver(rx, filter, Context.RECEIVER_NOT_EXPORTED);
    } else {
      a.registerReceiver(rx, filter);
    }
    Dialog d = sheetDialog(a, "Update ready", body);
    d.setOnDismissListener(x -> {
      try { a.unregisterReceiver(rx); } catch (IllegalArgumentException ignored) {}
    });
    Store.get(a).diagnostic("update_sheet_opened_" + first.available.buildId
        + (first.available.hasNotes() ? "_notes" : "_subject"));
  }

  /** Version line + release notes, or the commit subject when the build has no notes. */
  static View updateNotesView(Activity a, UpdateManifest m) {
    LinearLayout col = Theme.column(a);
    String ver = (m.versionName == null || m.versionName.isEmpty() ? m.buildId : m.versionName)
        + " \u00b7 " + UpdateManifest.formatBytes(m.apkSize);
    col.addView(Theme.text(a, ver, 11.5f, Theme.MUTED, false));
    Theme.gap(col, 8);
    if (m.hasNotes()) {
      for (int i = 0; i < m.notes.size(); i++) {
        if (i > 0) Theme.gap(col, 6);
        col.addView(noteBullet(a, m.notes.get(i)[0], m.notes.get(i)[1]));
      }
      if (!m.notesMore.isEmpty()) {
        Theme.gap(col, 8);
        col.addView(Theme.text(a, m.notesMore, 12, Theme.SUB, true));
      }
    } else if (m.commitSubject != null && !m.commitSubject.isEmpty()) {
      col.addView(noteBullet(a, "", m.commitSubject));
    }
    return col;
  }

  private static View noteBullet(Activity a, String title, String sentence) {
    LinearLayout row = Theme.row(a);
    row.setGravity(Gravity.TOP);
    row.addView(Theme.text(a, "\u2022", 13, Theme.SUB, false),
        new LinearLayout.LayoutParams(Theme.dp(a, 14), LinearLayout.LayoutParams.WRAP_CONTENT));
    android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
    if (!title.isEmpty()) {
      sb.append(title.endsWith(".") ? title : title + ".");
      sb.setSpan(new android.text.style.ForegroundColorSpan(Theme.FG), 0, sb.length(),
          android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length(),
          android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      if (!sentence.isEmpty()) sb.append(' ');
    }
    sb.append(sentence);
    TextView tv = Theme.text(a, sb, 13, Theme.ATTN, false);
    tv.setLineSpacing(Theme.dp(a, 2), 1f);
    row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    return row;
  }

  public static void showUpdaterSheet(Activity a, Runnable onChanged) {
    PhoneUpdater updater = PhoneUpdater.get(a);
    LinearLayout body = Theme.column(a);
    LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    final boolean[] editingToken = {!updater.snapshot().hasToken};
    final Dialog[] dlg = new Dialog[1];

    Runnable[] rebuild = new Runnable[1];
    rebuild[0] = () -> {
      PhoneUpdater.Snapshot s = updater.snapshot();
      body.removeAllViews();

      // Current build summary
      body.addView(Theme.text(a,
          "Current build · " + s.currentBuildId + " (v" + s.currentVersionCode + ")",
          12, Theme.MUTED, false));
      if (s.builtAt != null && !s.builtAt.isEmpty()) {
        body.addView(Theme.text(a, "Built " + s.builtAt, 11, Theme.DIM, false));
      }
      Theme.gap(body, 12);

      // Available update card with release notes
      if (s.available != null) {
        LinearLayout card = Theme.column(a);
        card.setPadding(Theme.dp(a, 14), Theme.dp(a, 12), Theme.dp(a, 14), Theme.dp(a, 12));
        card.setBackground(Theme.roundRect(a, Theme.CARD, Theme.LINE, 14));
        card.addView(updateNotesView(a, s.available));
        Theme.gap(card, 12);
        boolean busy = "downloading".equals(s.status) || "installing".equals(s.status);
        String btnLabel = busy
            ? (s.message != null ? s.message : "Updating\u2026")
            : (s.canInstallPackages ? "Update & restart" : "Allow installs & update");
        TextView installBtn = Theme.glassPillButton(a, btnLabel, () -> {
          if (!busy) updater.install(a);
        });
        if (busy) installBtn.setAlpha(0.65f);
        card.addView(installBtn, full);
        body.addView(card, full);
        Theme.gap(body, 12);
      }

      if (s.error != null && !s.error.isEmpty()) {
        body.addView(Theme.text(a, s.error, 12, Theme.ATTN, false));
        Theme.gap(body, 10);
      } else if (s.message != null && !s.message.isEmpty() && s.available == null) {
        body.addView(Theme.text(a, s.message, 12, Theme.EXPLAIN, false));
        Theme.gap(body, 10);
      }

      boolean checking = "checking".equals(s.status) || "downloading".equals(s.status) || "installing".equals(s.status);
      TextView checkBtn = Theme.outlinePillButton(a, checking ? "Checking…" : "Check now", () -> {
        if (!checking) updater.checkNow(false);
      });
      if (checking) checkBtn.setAlpha(0.6f);
      body.addView(checkBtn, full);

      Theme.gap(body, 12);
      body.addView(statusRow(a,
          "Install unknown apps",
          s.canInstallPackages ? "Allowed" : "Off · tap to allow",
          s.canInstallPackages,
          () -> updater.openInstallPermissionSettings(a)));

      // Auto-check toggle row
      LinearLayout autoRow = Theme.row(a);
      autoRow.setPadding(0, Theme.dp(a, 10), 0, Theme.dp(a, 10));
      LinearLayout autoCol = Theme.column(a);
      autoCol.addView(Theme.text(a, "Auto-check", 13, Theme.FG, false));
      autoCol.addView(Theme.text(a, "Check quietly for new nightly builds", 11, Theme.MUTED, false));
      autoRow.addView(autoCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
      TextView autoState = Theme.text(a, s.enabled ? "On" : "Off", 12, s.enabled ? Theme.FG : Theme.MUTED, s.enabled);
      autoState.setPadding(Theme.dp(a, 10), Theme.dp(a, 5), Theme.dp(a, 10), Theme.dp(a, 5));
      autoState.setBackground(Theme.roundRect(a, s.enabled ? Theme.SEC : Color.TRANSPARENT, Theme.LINE, 999));
      autoRow.addView(autoState);
      autoRow.setOnClickListener(v -> {
        updater.setEnabled(!s.enabled);
        onChanged.run();
        rebuild[0].run();
      });
      body.addView(autoRow, full);

      // GitHub token section (private repo)
      Theme.gap(body, 10);
      body.addView(Theme.text(a, "GitHub token · " + s.repo, 11.5f, Theme.MUTED, false));
      Theme.gap(body, 6);
      if (s.hasToken && !editingToken[0]) {
        LinearLayout tokRow = Theme.row(a);
        tokRow.setPadding(Theme.dp(a, 12), Theme.dp(a, 10), Theme.dp(a, 12), Theme.dp(a, 10));
        tokRow.setBackground(Theme.roundRect(a, Theme.BG, Theme.LINE, 12));
        View dot = new View(a);
        dot.setBackground(Theme.roundRect(a, Theme.GREEN, 0, 99));
        tokRow.addView(dot, new LinearLayout.LayoutParams(Theme.dp(a, 7), Theme.dp(a, 7)));
        Theme.gap(tokRow, 8);
        tokRow.addView(Theme.text(a, "Saved on device", 12.5f, Theme.FG, false),
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView replace = Theme.text(a, "Replace", 12, Theme.MUTED, false);
        replace.setPadding(Theme.dp(a, 8), Theme.dp(a, 4), Theme.dp(a, 8), Theme.dp(a, 4));
        replace.setOnClickListener(v -> {
          editingToken[0] = true;
          rebuild[0].run();
        });
        tokRow.addView(replace);
        TextView remove = Theme.text(a, "Remove", 12, Theme.MUTED, false);
        remove.setPadding(Theme.dp(a, 8), Theme.dp(a, 4), Theme.dp(a, 2), Theme.dp(a, 4));
        remove.setOnClickListener(v -> {
          updater.setToken("");
          editingToken[0] = true;
          onChanged.run();
          rebuild[0].run();
        });
        tokRow.addView(remove);
        body.addView(tokRow, full);
      } else {
        EditText tokInput = new EditText(a);
        tokInput.setHint("github_pat_…");
        tokInput.setHintTextColor(Theme.DIM);
        tokInput.setTextColor(Theme.FG);
        tokInput.setTextSize(13.5f);
        tokInput.setSingleLine(true);
        tokInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokInput.setPadding(Theme.dp(a, 12), Theme.dp(a, 10), Theme.dp(a, 12), Theme.dp(a, 10));
        tokInput.setBackground(Theme.roundRect(a, Theme.BG, Theme.LINE, 12));
        body.addView(tokInput, full);
        Theme.gap(body, 6);
        body.addView(Theme.text(a,
            "Read-only fine-grained token (Contents: Read) for private repo.",
            11, Theme.DIM, false));
        Theme.gap(body, 8);
        body.addView(Theme.primaryPillButton(a, "Save token & check", () -> {
          String raw = tokInput.getText().toString().trim();
          if (!raw.isEmpty()) {
            editingToken[0] = false;
            updater.setToken(raw);
            onChanged.run();
            rebuild[0].run();
          }
        }), full);
      }
    };

    rebuild[0].run();

    BroadcastReceiver rx = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        a.runOnUiThread(() -> {
          if (dlg[0] != null && dlg[0].isShowing()) {
            rebuild[0].run();
          }
          onChanged.run();
        });
      }
    };
    IntentFilter filter = new IntentFilter(PhoneUpdater.ACTION_STATE);
    if (Build.VERSION.SDK_INT >= 33) {
      a.registerReceiver(rx, filter, Context.RECEIVER_NOT_EXPORTED);
    } else {
      a.registerReceiver(rx, filter);
    }

    dlg[0] = sheetDialog(a, "Nightly updates", body);
    dlg[0].setOnDismissListener(d -> {
      try { a.unregisterReceiver(rx); } catch (IllegalArgumentException ignored) {}
    });
  }
}
