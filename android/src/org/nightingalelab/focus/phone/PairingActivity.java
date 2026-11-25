package org.nightingalelab.focus.phone;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;

/** Local approval prompt when Windows FocusDNS asks to pair with this phone. */
public final class PairingActivity extends Activity {
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Theme.applySystemBars(this);
    render();
  }

  private void render() {
    ApprovalPairing.Session pending = LanController.get(this).pendingApproval();
    if (pending == null) {
      finish();
      return;
    }
    LinearLayout root = Theme.column(this);
    root.setBackgroundColor(Theme.BG);
    root.setGravity(Gravity.CENTER);
    int pad = Theme.dp(this, 32);
    root.setPadding(pad, pad, pad, pad);

    TextView h = Theme.text(this, "Connect " + pending.manager + "?", 24, Theme.FG, true);
    h.setGravity(Gravity.CENTER);
    root.addView(h);
    Theme.gap(root, 10);
    TextView sub = Theme.text(this, "Check that both screens show the same six-digit number.", 13.5f, Theme.MUTED, false);
    sub.setGravity(Gravity.CENTER);
    root.addView(sub);
    Theme.gap(root, 24);

    String formatted = pending.code.length() == 6
        ? pending.code.substring(0, 3) + " " + pending.code.substring(3)
        : pending.code;
    TextView code = Theme.text(this, formatted, 40, Theme.FG, true);
    code.setGravity(Gravity.CENTER);
    code.setLetterSpacing(0.12f);
    root.addView(code);

    Theme.gap(root, 32);
    LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    root.addView(Theme.primaryPillButton(this, "Allow", () -> {
      try {
        LanController.get(this).approve(pending.id);
      } catch (RuntimeException ignored) {}
      finish();
    }), full);
    Theme.gap(root, 10);
    root.addView(Theme.outlinePillButton(this, "Decline", () -> {
      LanController.get(this).decline(pending.id);
      finish();
    }), full);

    setContentView(root);
  }
}
