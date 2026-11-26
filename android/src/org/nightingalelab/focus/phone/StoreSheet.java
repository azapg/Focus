package org.nightingalelab.focus.phone;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;
import org.nightingalelab.focus.habits.HabitStore;
import org.nightingalelab.focus.habits.Habits;
import org.nightingalelab.focus.habits.Wallet;

final class StoreSheet {
  private static final int[] PACKS = {5, 10};

  static void addHeader(Activity activity, LinearLayout header, Runnable refresh, Runnable openPause) {
    Wallet wallet = HabitStore.get(activity).read().wallet;
    if (!wallet.active(Habits.day(System.currentTimeMillis()))) return;
    TextView balance = pill(activity, coins(wallet.balance()) + " ⌄");
    balance.setContentDescription("Open Store, " + coins(wallet.balance()));
    balance.setOnClickListener(view -> show(activity, refresh, openPause));
    header.addView(balance);
    Theme.gap(header, 4);
  }

  static void show(Activity activity, Runnable refresh, Runnable openPause) {
    Wallet wallet = HabitStore.get(activity).read().wallet;
    int day = Habits.day(System.currentTimeMillis());
    if (!wallet.active(day)) return;
    LinearLayout body = Theme.column(activity);
    body.addView(Theme.text(activity, coins(wallet.balance()), 24, Theme.FG, true));
    Theme.gap(body, 10);

    List<String> targetIds = new ArrayList<>();
    List<String> targetLabels = new ArrayList<>();
    targetIds.add("phone");
    targetLabels.add("Phone");
    for (DeviceHub.PairedDevice peer : DeviceHub.get(activity).list()) {
      if (!peer.relayed) {
        targetIds.add(peer.id);
        targetLabels.add(peer.name);
      }
    }
    final int[] selected = {0};
    if (targetIds.size() > 1) {
      LinearLayout picker = Theme.row(activity);
      TextView[] chips = new TextView[targetIds.size()];
      for (int i = 0; i < targetIds.size(); i++) {
        final int idx = i;
        boolean on = i == 0;
        TextView chip = Theme.text(activity, targetLabels.get(i), 12f, on ? Theme.FG : Theme.MUTED, on);
        chip.setPadding(Theme.dp(activity, 10), Theme.dp(activity, 6), Theme.dp(activity, 10), Theme.dp(activity, 6));
        chip.setBackground(Theme.roundRect(activity, on ? Theme.DARK_ACTIVE : Color.TRANSPARENT, Theme.LINE, 999));
        chips[i] = chip;
        chip.setOnClickListener(v -> {
          selected[0] = idx;
          for (int j = 0; j < chips.length; j++) {
            boolean sel = j == idx;
            chips[j].setBackground(Theme.roundRect(activity, sel ? Theme.DARK_ACTIVE : Color.TRANSPARENT, Theme.LINE, 999));
            chips[j].setTextColor(sel ? Theme.FG : Theme.MUTED);
          }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (i > 0) lp.leftMargin = Theme.dp(activity, 6);
        picker.addView(chip, lp);
      }
      body.addView(picker);
      Theme.gap(body, 10);
    }

    int remaining = wallet.timeOffLimitRemaining(day);
    final Dialog[] sheetRef = new Dialog[1];
    for (int minutes : PACKS) {
      int price = minutes * Wallet.TIME_OFF_PRICE_PER_MINUTE;
      LinearLayout row = item(activity, minutes + " min free", "Starts immediately");
      TextView buy = pill(activity, coins(price));
      boolean enabled = remaining >= minutes && wallet.balance() >= price;
      buy.setEnabled(enabled);
      buy.setAlpha(enabled ? 1f : .5f);
      buy.setOnClickListener(v -> {
        if (sheetRef[0] != null) sheetRef[0].dismiss();
        confirm(activity, minutes, price, targetIds.get(selected[0]), targetLabels.get(selected[0]), day, refresh, openPause);
      });
      row.addView(buy);
      body.addView(row);
      Theme.gap(body, 8);
    }

    body.addView(Theme.text(activity,
        remaining + " min left today · " + Wallet.TIME_OFF_PRICE_PER_MINUTE + " coins/min",
        12, Theme.MUTED, false));

    sheetRef[0] = SettingsDialogs.sheetDialog(activity, "Store", body);
    loadFreeze(activity, sheetRef[0], body, refresh, openPause);
  }

  private static void loadFreeze(Activity activity, Dialog sheet, LinearLayout body, Runnable refresh, Runnable openPause) {
    new Thread(() -> {
      final JSONObject view;
      try {
        view = StreakAuthority.view(activity);
      } catch (Exception unavailable) {
        return;
      }
      if (view == null || view.optInt("priceCoins") <= 0) return;
      activity.runOnUiThread(() -> {
        if (!sheet.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
        int price = view.optInt("priceCoins");
        boolean held = view.optBoolean("freezeReady");
        long missing = price - view.optLong("balanceCoins");
        boolean canBuy = view.optBoolean("canBuy");
        String detail = held ? "One freeze held" : missing > 0 ? coins(missing) + " needed"
            : canBuy ? "Protect one future Drift day" : "Unavailable right now";
        LinearLayout row = item(activity, "Streak Freeze", detail);
        if (!held) {
          TextView buy = pill(activity, coins(price));
          buy.setContentDescription("Streak Freeze, " + coins(price));
          buy.setOnClickListener(button -> {
            sheet.dismiss();
            confirmFreeze(activity, price, refresh, openPause);
          });
          buy.setEnabled(canBuy);
          buy.setAlpha(buy.isEnabled() ? 1f : .5f);
          row.addView(buy);
        }
        Theme.gap(body, 8);
        body.addView(row);
      });
    }, "focus-freeze-view").start();
  }

  private static void confirmFreeze(Activity activity, int price, Runnable refresh, Runnable openPause) {
    LinearLayout body = Theme.column(activity);
    body.addView(Theme.text(activity,
        "Protects one future Drift day starting tomorrow.", 14, Theme.EXPLAIN, false));
    Theme.gap(body, 12);
    TextView confirm = pill(activity, "Confirm · " + coins(price));
    TextView cancel = pill(activity, "Cancel");
    addActions(activity, body, confirm, cancel);
    TextView message = Theme.text(activity, "", 12, Theme.MUTED, false);
    body.addView(message);
    Dialog sheet = SettingsDialogs.sheetDialog(activity, "Buy Streak Freeze?", body);
    cancel.setOnClickListener(button -> {
      sheet.dismiss();
      show(activity, refresh, openPause);
    });
    String requestId = UUID.randomUUID().toString();
    confirm.setOnClickListener(button -> {
      confirm.setEnabled(false);
      cancel.setEnabled(false);
      sheet.setCancelable(false);
      message.setText("");
      new Thread(() -> {
        String status;
        try {
          status = StreakAuthority.buyFreeze(activity, requestId).optString("status", "unavailable");
        } catch (Exception unconfirmed) {
          status = "unconfirmed";
        }
        final String outcome = status;
        if ("bought".equals(outcome) || "already_bought".equals(outcome)) {
          DeviceHub.get(activity).refreshOne(StreakAuthority.trialAuthority(activity));
        }
        activity.runOnUiThread(() -> {
          if (!sheet.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
          if ("unconfirmed".equals(outcome)) {
            message.setText("Couldn't confirm. Try again.");
            confirm.setEnabled(true);
            cancel.setEnabled(true);
            sheet.setCancelable(true);
            return;
          }
          sheet.dismiss();
          refresh.run();
        });
      }, "focus-freeze-buy").start();
    });
  }

  private static void confirm(Activity activity, int minutes, int price, String targetId, String targetLabel,
      int quotedDay, Runnable refresh, Runnable openPause) {
    LinearLayout body = Theme.column(activity);
    body.addView(Theme.text(activity,
        "Start " + minutes + " min free on " + targetLabel + " now for " + coins(price) + "?",
        14, Theme.EXPLAIN, false));
    Theme.gap(body, 12);
    TextView confirm = pill(activity, "Start · " + coins(price));
    TextView cancel = pill(activity, "Cancel");
    addActions(activity, body, confirm, cancel);
    TextView err = Theme.text(activity, "", 12, Theme.ATTN, false);
    body.addView(err);
    Dialog sheet = SettingsDialogs.sheetDialog(activity, "Are you sure?", body);
    cancel.setOnClickListener(view -> {
      sheet.dismiss();
      show(activity, refresh, openPause);
    });
    String requestId = UUID.randomUUID().toString();
    confirm.setOnClickListener(view -> {
      confirm.setEnabled(false);
      cancel.setEnabled(false);
      err.setText("");
      new Thread(() -> {
        try {
          if ("phone".equals(targetId)) {
            if (!DnsController.get(activity).canPause()) {
              throw new IllegalStateException("Phone cannot start free time right now.");
            }
          }
          HabitStore store = HabitStore.get(activity);
          Wallet.Purchase purchase = store.updateWallet(wallet -> {
            long now = System.currentTimeMillis();
            int day = Habits.day(now);
            if (day != quotedDay) return null;
            return wallet.buyTimeOff(requestId, minutes, now, day);
          });
          if (purchase == null) throw new IllegalStateException("New day started. Reopen Store.");
          if (purchase.status == Wallet.Purchase.Status.NOT_ENOUGH) {
            throw new IllegalStateException(coins(purchase.missing) + " more needed.");
          }
          if (purchase.status == Wallet.Purchase.Status.LIMIT_REACHED) {
            throw new IllegalStateException("Daily free time limit reached.");
          }
          try {
            if ("phone".equals(targetId)) {
              DnsController.get(activity).startFreeTime(minutes, requestId);
            } else {
              DeviceHub.get(activity).sendCommand(targetId, new JSONObject()
                  .put("action", "start_free_time")
                  .put("minutes", minutes)
                  .put("request_id", requestId));
            }
          } catch (Exception startErr) {
            long now = System.currentTimeMillis();
            store.updateWallet(wallet -> {
              wallet.reverse("timeoff:" + requestId, now, Habits.day(now));
              return null;
            });
            throw startErr;
          }
          new Thread(() -> DeviceHub.get(activity).syncAll(), "focus-wallet-sync").start();
          activity.runOnUiThread(() -> {
            if (sheet.isShowing()) sheet.dismiss();
            refresh.run();
          });
        } catch (Exception e) {
          String msg = e.getMessage() == null ? "Could not start free time." : e.getMessage();
          activity.runOnUiThread(() -> {
            if (!sheet.isShowing()) return;
            err.setText(msg);
            confirm.setEnabled(true);
            cancel.setEnabled(true);
          });
        }
      }, "focus-store-buy").start();
    });
  }

  private static void addActions(Activity activity, LinearLayout body, TextView primary, TextView secondary) {
    LinearLayout row = Theme.row(activity);
    row.addView(primary);
    Theme.gap(row, 8);
    row.addView(secondary);
    body.addView(row);
  }

  private static LinearLayout item(Activity activity, String title, String detail) {
    LinearLayout row = Theme.row(activity);
    row.setPadding(Theme.dp(activity, 12), Theme.dp(activity, 8), Theme.dp(activity, 8), Theme.dp(activity, 8));
    row.setBackground(Theme.roundRect(activity, Theme.CARD, Theme.LINE, 14));
    LinearLayout copy = Theme.column(activity);
    copy.addView(Theme.text(activity, title, 14, Theme.FG, true));
    copy.addView(Theme.text(activity, detail, 12, Theme.MUTED, false));
    row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
    return row;
  }

  private static TextView pill(Activity activity, String label) {
    TextView text = Theme.text(activity, label, 13, Theme.FG, true);
    text.setPadding(Theme.dp(activity, 11), Theme.dp(activity, 7), Theme.dp(activity, 11), Theme.dp(activity, 7));
    text.setGravity(Gravity.CENTER);
    text.setBackground(Theme.roundRect(activity, Theme.DARK_ACTIVE, Theme.LINE, 999));
    return text;
  }

  private static String coins(long amount) {
    return amount == 1 ? "1 coin" : amount + " coins";
  }
}
