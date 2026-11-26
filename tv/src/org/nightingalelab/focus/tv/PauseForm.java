package org.nightingalelab.focus.tv;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.util.UUID;
import org.nightingalelab.focus.habits.HabitStore;
import org.nightingalelab.focus.habits.Habits;
import org.nightingalelab.focus.habits.Wallet;

public final class PauseForm {
  private static final int[] PACKS = {5, 10};

  public static void showFreeTimeStore(Activity a, String pkg, Runnable done) {
    Wallet wallet = HabitStore.get(a).read().wallet;
    int day = Habits.day(System.currentTimeMillis());
    long balance = wallet.balance();
    int remaining = wallet.timeOffLimitRemaining(day);

    LinearLayout body = Ui.column(a);
    body.addView(Ui.text(a, balance + (balance == 1 ? " coin" : " coins")
        + "  ·  " + remaining + " min left today", 16, Ui.MUTED));
    Ui.gap(body, 14);

    final Dialog[] dlg = new Dialog[1];
    for (int minutes : PACKS) {
      int price = minutes * Wallet.TIME_OFF_PRICE_PER_MINUTE;
      boolean ok = wallet.active(day) && remaining >= minutes && balance >= price;
      Button b = Ui.button(a, minutes + " min free · " + price + " coins", () -> {
        if (dlg[0] != null) dlg[0].dismiss();
        confirmFreeTime(a, pkg, minutes, price, done);
      });
      b.setEnabled(ok);
      b.setAlpha(ok ? 1f : 0.45f);
      body.addView(b);
      Ui.gap(body, 8);
    }
    if (!wallet.active(day)) {
      body.addView(Ui.text(a, "Wallet activates tomorrow after setup.", 14, Ui.MUTED));
    } else if (remaining < 5) {
      body.addView(Ui.text(a, "Daily free time limit reached.", 14, Ui.MUTED));
    } else if (balance < 5 * Wallet.TIME_OFF_PRICE_PER_MINUTE) {
      body.addView(Ui.text(a, "Earn coins from deep focus or habits first.", 14, Ui.MUTED));
    }
    dlg[0] = Ui.dialog(a, "Free time", body);
    dlg[0].show();
  }

  public static void confirmFreeTime(Activity a, String pkg, int minutes, int price, Runnable done) {
    LinearLayout body = Ui.column(a);
    body.addView(Ui.text(a,
        "Start " + minutes + " min free on TV now for " + price + " coins?",
        17, Ui.FG));
    Ui.gap(body, 16);
    TextView err = Ui.text(a, "", 14, Ui.MUTED);
    err.setVisibility(View.GONE);
    final Dialog[] dlg = new Dialog[1];
    String requestId = UUID.randomUUID().toString();
    Button start = Ui.button(a, "Start · " + price + " coins", () -> {
      try {
        long now = System.currentTimeMillis();
        int day = Habits.day(now);
        Wallet.Purchase purchase = HabitStore.get(a).updateWallet(
            w -> w.buyTimeOff(requestId, minutes, now, day));
        if (purchase.status == Wallet.Purchase.Status.NOT_ENOUGH) {
          err.setText(purchase.missing + " more coins needed.");
          err.setVisibility(View.VISIBLE);
          return;
        }
        if (purchase.status == Wallet.Purchase.Status.LIMIT_REACHED) {
          err.setText("Daily free time limit reached.");
          err.setVisibility(View.VISIBLE);
          return;
        }
        DnsController.get(a).startFreeTime(minutes, requestId, DnsController.KEY.equals(pkg) ? null : pkg);
        if (dlg[0] != null) dlg[0].dismiss();
        done.run();
      } catch (RuntimeException e) {
        err.setText(e.getMessage() == null ? "Could not start free time." : e.getMessage());
        err.setVisibility(View.VISIBLE);
      }
    });
    body.addView(start);
    Ui.gap(body, 8);
    body.addView(Ui.button(a, "Cancel", () -> {
      if (dlg[0] != null) dlg[0].dismiss();
    }));
    Ui.gap(body, 8);
    body.addView(err);
    dlg[0] = Ui.dialog(a, "Are you sure?", body);
    dlg[0].show();
  }

  public static void showTvAsk(Activity a, String pkg, Runnable done) {
    long balance = HabitStore.get(a).read().wallet.balance();
    LinearLayout body = Ui.column(a);
    EditText input = new EditText(a);
    input.setSingleLine(true);
    input.setHint("What specific task?");
    input.setTextColor(Ui.FG);
    input.setHintTextColor(Ui.MUTED);
    input.setTextSize(17);
    input.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
    input.setBackground(Ui.shape(Ui.BG, Ui.BORDER, 12, a));
    body.addView(input, new LinearLayout.LayoutParams(-1, Ui.dp(a, 48)));
    Ui.gap(body, 12);
    TextView replyView = Ui.text(a, "", 15, Ui.MUTED);
    replyView.setVisibility(View.GONE);
    body.addView(replyView);
    Ui.gap(body, 12);
    final Dialog[] dlg = new Dialog[1];
    body.addView(Ui.button(a, "Ask", () -> {
      TvPauseJudge.Reply reply = TvPauseJudge.evaluate(input.getText().toString(), balance);
      replyView.setText(reply.message);
      replyView.setVisibility(View.VISIBLE);
      if (reply.allowed && reply.minutes > 0) {
        Store.get(a).pause(pkg, reply.minutes * 60_000L, input.getText().toString().trim());
        if (dlg[0] != null) dlg[0].dismiss();
        done.run();
      }
    }));
    dlg[0] = Ui.dialog(a, "Ask on TV", body);
    dlg[0].show();
  }

  /** Retained for legacy callers; routes directly to Store Free Time. */
  public static void show(Activity a, String pkg, Runnable done) {
    showFreeTimeStore(a, pkg, done);
  }

  public static void openApp(Activity a, String pkg) {
    Intent launch = a.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
    if (launch == null) {
      Toast.makeText(a, "App unavailable", Toast.LENGTH_SHORT).show();
      return;
    }
    try {
      a.startActivity(launch);
    } catch (RuntimeException e) {
      Toast.makeText(a, "Could not open app", Toast.LENGTH_SHORT).show();
    }
  }
}
