package org.nightingalelab.focus.tv;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.UUID;
import org.nightingalelab.focus.habits.HabitStore;
import org.nightingalelab.focus.habits.Habits;
import org.nightingalelab.focus.habits.Wallet;

public final class BlockActivity extends Activity {
  public static volatile boolean visible;
  public static volatile String presentedPackage = "";
  private String pkg;

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    Ui.fullscreen(this);
    show(getIntent());
  }

  @Override
  public void onNewIntent(Intent i) {
    super.onNewIntent(i);
    setIntent(i);
    show(i);
  }

  private void show(Intent i) {
    pkg = i.getStringExtra("package");
    if (pkg == null || !AppCatalog.allowed(this, pkg) || Store.get(this).restricted(pkg) == null) {
      finish();
      return;
    }
    presentedPackage = pkg;
    Wallet wallet = HabitStore.get(this).read().wallet;
    int day = Habits.day(System.currentTimeMillis());
    long balance = wallet.balance();
    int remaining = wallet.timeOffLimitRemaining(day);
    int packMinutes = (remaining >= 10 && balance >= 10 * Wallet.TIME_OFF_PRICE_PER_MINUTE) ? 10 : 5;
    int packCoins = packMinutes * Wallet.TIME_OFF_PRICE_PER_MINUTE;
    boolean canBuy = wallet.active(day) && remaining >= packMinutes && balance >= packCoins;

    LinearLayout root = Ui.screen(this);
    root.setGravity(Gravity.CENTER);
    TextView title = Ui.text(this, AppCatalog.label(this, pkg), 38, Ui.FG);
    title.setGravity(Gravity.CENTER);
    root.addView(title);
    Ui.gap(root, 10);
    TextView status = Ui.text(this,
        Store.get(this).restricted(pkg) + "  ·  " + balance + (balance == 1 ? " coin" : " coins"),
        18, Ui.MUTED);
    status.setGravity(Gravity.CENTER);
    root.addView(status);
    Ui.gap(root, 26);

    String buyLabel = canBuy
        ? packMinutes + " min free · " + packCoins + " coins"
        : "Store free time (" + balance + " coins)";
    Button freeTime = Ui.button(this, buyLabel, () -> {
      if (canBuy) {
        PauseForm.confirmFreeTime(this, pkg, packMinutes, packCoins, () -> {
          PauseForm.openApp(this, pkg);
          finish();
        });
      } else {
        PauseForm.showFreeTimeStore(this, pkg, () -> {
          PauseForm.openApp(this, pkg);
          finish();
        });
      }
    });
    Ui.add(root, freeTime, 310, 50);
    Ui.gap(root, 10);

    Button phoneAsk = Ui.button(this, "Ask on phone", this::showPhoneHint);
    Ui.add(root, phoneAsk, 310, 48);
    Ui.gap(root, 10);

    Ui.add(root, Ui.button(this, "Home", this::home), 310, 48);
    Ui.gap(root, 18);

    // Tiny ghost button below in case the phone isn't nearby.
    Button tvAsk = Ui.button(this, "Ask on TV", () -> PauseForm.showTvAsk(this, pkg, () -> {
      PauseForm.openApp(this, pkg);
      finish();
    }));
    tvAsk.setTextSize(13);
    tvAsk.setAlpha(0.55f);
    Ui.add(root, tvAsk, 180, 38);

    setContentView(root);
    freeTime.requestFocus();
    Store.get(this).diagnostic("blocked_app_presented");
  }

  private void showPhoneHint() {
    LinearLayout body = Ui.column(this);
    body.addView(Ui.text(this, "Open Focus on your phone to buy Free Time for this TV or ask for a task exception.", 16, Ui.MUTED));
    Dialog d = Ui.dialog(this, "Ask on phone", body);
    Ui.gap(body, 14);
    body.addView(Ui.button(this, "OK", d::dismiss));
    d.show();
  }

  private void home() {
    startActivity(
        new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    finish();
  }

  @Override
  public void onResume() {
    super.onResume();
    visible = true;
    Ui.fullscreen(this);
  }

  @Override
  public void onPause() {
    visible = false;
    super.onPause();
  }

  @Override
  public void onBackPressed() {
    home();
  }
}
