package org.nightingalelab.focus.phone;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.UUID;
import org.nightingalelab.focus.habits.HabitStore;
import org.nightingalelab.focus.habits.Habits;
import org.nightingalelab.focus.habits.Wallet;

final class StoreSheet {
  private static final int TIME_OFF_MINUTES = 15;

  static void addHeader(Activity activity, LinearLayout header, Runnable refresh, Runnable openPause) {
    Wallet wallet = HabitStore.get(activity).read().wallet;
    if (!wallet.active(Habits.day(System.currentTimeMillis()))) return;
    TextView balance = pill(activity, coins(wallet.balance()) + " ⌄");
    balance.setContentDescription("Open Store, " + coins(wallet.balance()));
    balance.setOnClickListener(view -> show(activity, refresh, openPause));
    header.addView(balance);
    Theme.gap(header, 4);
  }

  private static void show(Activity activity, Runnable refresh, Runnable openPause) {
    Wallet wallet = HabitStore.get(activity).read().wallet;
    int day = Habits.day(System.currentTimeMillis());
    if (!wallet.active(day)) return;
    LinearLayout body = Theme.column(activity);
    body.addView(Theme.text(activity, coins(wallet.balance()), 24, Theme.FG, true));
    Theme.gap(body, 12);

    LinearLayout item = Theme.row(activity);
    item.setPadding(Theme.dp(activity, 12), Theme.dp(activity, 8), Theme.dp(activity, 8), Theme.dp(activity, 8));
    item.setBackground(Theme.roundRect(activity, Theme.CARD, Theme.LINE, 14));
    LinearLayout copy = Theme.column(activity);
    copy.addView(Theme.text(activity, "Time off", 14, Theme.FG, true));
    copy.addView(Theme.text(activity, "15 minutes for today", 12, Theme.MUTED, false));
    item.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

    int price = TIME_OFF_MINUTES * Wallet.TIME_OFF_PRICE_PER_MINUTE;
    TextView buy = pill(activity, coins(price));
    boolean capShort = wallet.timeOffLimitRemaining(day) < TIME_OFF_MINUTES;
    boolean fundsShort = wallet.balance() < price;
    buy.setEnabled(!capShort && !fundsShort);
    buy.setAlpha(buy.isEnabled() ? 1f : .5f);
    item.addView(buy);
    body.addView(item);
    Theme.gap(body, 8);

    String note = capShort ? "Today’s purchase limit leaves fewer than 15 minutes."
        : fundsShort ? coins(price - wallet.balance()) + " needed for 15 minutes off."
        : wallet.timeOffLimitRemaining(day) + " extra minutes available to buy today.";
    body.addView(Theme.text(activity, note, 12, Theme.MUTED, false));
    if (DeviceHub.get(activity).list().stream()
        .anyMatch(peer -> peer.snapshot != null && peer.snapshot.optInt("wallet_sync") != Wallet.SYNC_VERSION)) {
      Theme.gap(body, 8);
      body.addView(Theme.text(activity, "Update paired devices to share purchases.", 12, Theme.EXPLAIN, false));
    }
    Dialog sheet = SettingsDialogs.sheetDialog(activity, "Store", body);
    buy.setOnClickListener(view -> {
      sheet.dismiss();
      confirm(activity, price, day, refresh, openPause);
    });
  }

  private static void confirm(Activity activity, int price, int quotedDay, Runnable refresh, Runnable openPause) {
    LinearLayout body = Theme.column(activity);
    body.addView(Theme.text(activity,
        "Add 15 minutes to today’s pause limit. They expire at midnight. Request a pause as usual.",
        14, Theme.EXPLAIN, false));
    Theme.gap(body, 12);
    TextView confirm = pill(activity, "Confirm · " + coins(price));
    TextView cancel = pill(activity, "Cancel");
    addActions(activity, body, confirm, cancel);
    Dialog sheet = SettingsDialogs.sheetDialog(activity, "Buy 15 minutes?", body);
    cancel.setOnClickListener(view -> {
      sheet.dismiss();
      show(activity, refresh, openPause);
    });
    String requestId = UUID.randomUUID().toString();
    confirm.setOnClickListener(view -> {
      confirm.setEnabled(false);
      HabitStore store = HabitStore.get(activity);
      String result;
      try {
        Wallet.Purchase purchase = store.updateWallet(wallet -> {
          long now = System.currentTimeMillis();
          int day = Habits.day(now);
          if (day != quotedDay) return null;
          return wallet.buyTimeOff(requestId, TIME_OFF_MINUTES, now, day);
        });
        if (purchase == null) {
          result = "A new day started. Open Store to see today’s allowance.";
        } else {
          switch (purchase.status) {
            case BOUGHT:
              new Thread(() -> DeviceHub.get(activity).syncAll(), "focus-wallet-sync").start();
              // Fall through to the existing receipt on a retry.
            case ALREADY_BOUGHT:
              sheet.dismiss();
              refresh.run();
              showReceipt(activity, openPause);
              return;
            case NOT_ENOUGH:
              result = coins(purchase.missing) + " needed to complete this purchase.";
              break;
            default:
              result = "Today’s purchase limit has been reached.";
          }
        }
      } catch (IllegalStateException e) {
        result = "Focus could not save this purchase. Try again from Store.";
      }
      sheet.dismiss();
      refresh.run();
      LinearLayout message = Theme.column(activity);
      message.addView(Theme.text(activity, result, 14, Theme.EXPLAIN, false));
      SettingsDialogs.sheetDialog(activity, "Time off", message);
    });
  }

  private static void showReceipt(Activity activity, Runnable openPause) {
    LinearLayout body = Theme.column(activity);
    body.addView(Theme.text(activity, "Your pause limit is higher for the rest of today.", 14, Theme.EXPLAIN, false));
    Theme.gap(body, 12);
    TextView pause = pill(activity, "Open Pause");
    TextView done = pill(activity, "Done");
    addActions(activity, body, pause, done);
    Dialog sheet = SettingsDialogs.sheetDialog(activity, "15 minutes added", body);
    pause.setOnClickListener(view -> {
      sheet.dismiss();
      openPause.run();
    });
    done.setOnClickListener(view -> sheet.dismiss());
  }

  private static void addActions(Activity activity, LinearLayout body, TextView primary, TextView secondary) {
    LinearLayout row = Theme.row(activity);
    row.addView(primary);
    Theme.gap(row, 8);
    row.addView(secondary);
    body.addView(row);
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
