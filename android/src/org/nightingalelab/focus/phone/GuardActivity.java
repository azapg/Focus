package org.nightingalelab.focus.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;

/** Full-screen black shield shown when the user tries to toggle Private DNS off in Settings. */
public final class GuardActivity extends Activity {
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Theme.applySystemBars(this);
    String reason = getIntent().getStringExtra("reason");
    boolean isUninstall = "uninstall_guard".equals(reason);

    LinearLayout root = Theme.column(this);
    root.setBackgroundColor(Theme.BG);
    root.setGravity(Gravity.CENTER);
    int pad = Theme.dp(this, 32);
    root.setPadding(pad, pad, pad, pad);

    TextView title = Theme.text(this,
        isUninstall ? "Focus is protecting your phone." : "Private DNS is locked.",
        26, Theme.FG, true);
    title.setGravity(Gravity.CENTER);
    root.addView(title);

    Theme.gap(root, 12);
    TextView sub = Theme.text(this,
        "If you really need a break, open Focus and hold to pause with a reason.",
        14, Theme.MUTED, false);
    sub.setGravity(Gravity.CENTER);
    root.addView(sub);

    Theme.gap(root, 28);
    TextView open = Theme.primaryPillButton(this, "Open Focus", () -> {
      startActivity(new Intent(this, MainActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
      finish();
    });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    root.addView(open, lp);

    Theme.gap(root, 12);
    TextView back = Theme.outlinePillButton(this, "Back to home", () -> {
      Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
      startActivity(home);
      finish();
    });
    root.addView(back, lp);

    setContentView(root);
  }
}
