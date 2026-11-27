package org.nightingalelab.focus.tv;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.widget.*;
import java.util.List;
import org.nightingalelab.focus.habits.HabitStore;
import org.nightingalelab.focus.habits.HabitViews;
import org.nightingalelab.focus.habits.Habits;

/** Option A quiet redirect: calm monochrome block screen that leads with a habit or focus nudge. */
public final class BlockActivity extends Activity {
  public static volatile boolean visible;
  public static volatile String presentedPackage = "";
  private String pkg;
  private FrameLayout host;

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
    render();
    Store.get(this).diagnostic("blocked_app_presented");
  }

  private void render() {
    if (pkg == null || !AppCatalog.allowed(this, pkg) || Store.get(this).restricted(pkg) == null) {
      finish();
      return;
    }
    long now = System.currentTimeMillis();
    int day = Habits.day(now);
    Habits ledger = HabitStore.get(this).read();
    List<Habits.Status> statuses = ledger.status(now);
    Habits.Status nextDue = null;
    for (Habits.Status s : statuses) {
      if (s.due) {
        nextDue = s;
        break;
      }
    }
    boolean coins = ledger.wallet.active(day);

    if (host == null) {
      host = new FrameLayout(this);
      setContentView(host);
    } else if (host.getChildCount() > 0) {
      host.removeViewAt(0);
    }

    LinearLayout root = Ui.column(this);
    root.setBackground(Ui.tvBackground());
    root.setPadding(Ui.dp(this, 48), Ui.dp(this, 28), Ui.dp(this, 48), Ui.dp(this, 28));
    root.setGravity(Gravity.CENTER);

    LinearLayout badge = Ui.row(this);
    badge.setGravity(Gravity.CENTER);
    ImageView mark = new ImageView(this);
    mark.setImageResource(R.drawable.focus_ring);
    mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    badge.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
    Ui.gap(badge, 8);
    badge.addView(Ui.text(this, "Focus  ·  NextDNS " + DnsController.get(this).status(), 14, Ui.MUTED));
    root.addView(badge);
    Ui.gap(root, 14);

    TextView title = Ui.text(this, AppCatalog.label(this, pkg) + " is blocked", 38, Ui.FG);
    title.setGravity(Gravity.CENTER);
    root.addView(title);
    Ui.gap(root, 8);

    String habitName = nextDue == null ? "" : nextDue.habit.name;
    String subText = TvRedirect.blockSubhead(habitName, !statuses.isEmpty());
    TextView sub = Ui.text(this, subText, 17, Ui.MUTED);
    sub.setGravity(Gravity.CENTER);
    if (nextDue != null) {
      int start = subText.indexOf(habitName);
      if (start >= 0) {
        SpannableString styled = new SpannableString(subText);
        int accent = (nextDue.overdue || nextDue.grace) ? Ui.RED : Ui.ORANGE;
        styled.setSpan(new ForegroundColorSpan(accent), start, start + habitName.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sub.setText(styled);
      }
    }
    root.addView(sub);
    Ui.gap(root, 20);

    if (nextDue != null) {
      Habits.Status target = nextDue;
      HabitViews.Hold hold = new HabitViews.Hold(
          this, target, 17, coins, () -> HabitDashboard.completeHabit(this, target, host, this::render), null);
      Ui.applyFocusLift(hold, 1.02f);
      Ui.add(root, hold, 430, -2);
      Ui.gap(root, 18);
    }

    Button doneWithTv = Ui.glassButton(this, "Done with TV", this::home);
    Ui.add(root, doneWithTv, 300, 50);
    Ui.gap(root, 10);

    Button phoneOnly = Ui.dockTab(this, "Need a break? Use Focus on phone", false, this::showPhoneHint);
    Ui.add(root, phoneOnly, 330, 42);

    host.addView(root, 0, new FrameLayout.LayoutParams(-1, -1));
    doneWithTv.requestFocus();
  }

  private void showPhoneHint() {
    LinearLayout body = Ui.column(this);
    body.addView(Ui.text(
        this,
        "Free Time and task exceptions live in Focus on your phone. Open Focus there if you really need a break.",
        16,
        Ui.MUTED));
    Dialog d = Ui.dialog(this, "Use Focus on phone", body);
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
