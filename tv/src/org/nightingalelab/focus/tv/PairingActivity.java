package org.nightingalelab.focus.tv;

import android.app.Activity;
import android.os.*;
import android.content.Intent;
import android.view.KeyEvent;
import android.widget.*;

/** First-time control is granted only by a local TV action, never a network command. */
public final class PairingActivity extends Activity {
  public static volatile boolean visible;
  @Override public void onResume() { super.onResume(); visible = true; }
  @Override public void onPause() { visible = false; super.onPause(); }
  private final Handler handler = new Handler(Looper.getMainLooper());
  private String requestId;
  private final Runnable expiry = new Runnable() { public void run() {
    ApprovalPairing.Session session = LanController.get(PairingActivity.this).pendingApproval();
    if (session == null || !session.id.equals(requestId)) { finish(); return; }
    handler.postDelayed(this, 500);
  }};
  @Override public void onCreate(Bundle state) { super.onCreate(state); Ui.fullscreen(this); render(); }
  @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); render(); }
  private void render() {
    LanController controller = LanController.get(this);
    ApprovalPairing.Session session = controller.pendingApproval();
    if (session == null) { finish(); return; }
    requestId = session.id;
    LinearLayout root = Ui.screen(this);
    root.setGravity(android.view.Gravity.CENTER);
    root.addView(Ui.text(this, session.manager + " wants to connect", 27, Ui.FG));
    Ui.gap(root, 24);
    root.addView(Ui.text(this, session.code.substring(0,3) + " " + session.code.substring(3), 48, Ui.FG));
    Ui.gap(root, 18);
    root.addView(Ui.text(this, "Allow only if this number matches your laptop.", 18, Ui.MUTED));
    Ui.gap(root, 28);
    LinearLayout buttons = Ui.row(this);
    Button allow = Ui.button(this, "Allow", () -> {
      try { controller.approve(requestId); finish(); }
      catch (RuntimeException e) { controller.decline(requestId); Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); finish(); }
    });
    buttons.addView(allow); Ui.gap(buttons, 12);
    buttons.addView(Ui.button(this, "Decline", () -> { controller.decline(requestId); finish(); }));
    root.addView(buttons); setContentView(root);
    // Enter is never accepted implicitly while the prompt is appearing.
    buttons.getChildAt(2).requestFocus();
    handler.removeCallbacks(expiry); handler.postDelayed(expiry,500);
  }
  @Override public boolean onKeyDown(int key, KeyEvent event) {
    if (key == KeyEvent.KEYCODE_BACK) { LanController.get(this).decline(requestId); finish(); return true; }
    return super.onKeyDown(key,event);
  }
  @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
