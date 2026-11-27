package org.nightingalelab.focus.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.graphics.PixelFormat;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.widget.*;
import org.nightingalelab.focus.telemetry.LocalTelemetry.Metric;

/** Package/window events only. Never requests nodes, text, screenshots or gestures. */
public final class RestrictionService extends AccessibilityService {
  public static volatile boolean connected;
  private static volatile RestrictionService instance;
  public static boolean openPairing(Context context) {
    RestrictionService active = instance;
    if (active == null || !connected) return false;
    active.startActivity(new Intent(active,PairingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    return true;
  }
  public static volatile String timerPackage = "";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private String front = "", shown = "", pending = "";
  private long attempted, frontAt;
  private View overlay;
  private boolean isBlock;
  private final Runnable tick =
      new Runnable() {
        public void run() {
          try {
            Metric.ACCESSIBILITY_TICK.count(1);
            evaluate();
          } catch (RuntimeException e) {
            Store.get(RestrictionService.this)
                .diagnostic("restriction_failed_" + e.getClass().getSimpleName());
            remove();
          }
          handler.postDelayed(this, 1000);
        }
      };

  @Override
  protected void onServiceConnected() {
    connected = true;
    instance = this;
    MonitorService.ensure(this);
    Store.get(this).diagnostic("accessibility_connected");
    handler.removeCallbacks(tick);
    handler.post(tick);
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    Metric.ACCESSIBILITY_EVENT.count(1);
    if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        || event.getPackageName() == null) return;
    String pkg = event.getPackageName().toString();
    if (pkg.equals(getPackageName()) && !MainActivity.visible && !BlockActivity.visible && !PairingActivity.visible) return;
    front = pkg;
    frontAt = SystemClock.elapsedRealtime();
    evaluate();
  }

  private void evaluate() {
    Store store = Store.get(this);
    store.expirePauses();
    if (!((PowerManager) getSystemService(POWER_SERVICE)).isInteractive()) {
      remove();
      return;
    }
    MonitorService.recoverIfStale(this);
    if (MainActivity.visible || BlockActivity.visible || PairingActivity.visible) {
      remove();
      return;
    }
    String pkg =
        SystemClock.elapsedRealtime() - Store.heartbeat < 15000 && Store.heartbeat >= frontAt
            ? Store.foreground
            : front;
    if (!AppCatalog.allowed(this, pkg)) {
      pending = "";
      remove();
      return;
    }
    String restriction = store.restricted(pkg);
    if (restriction != null) {
      showBlock(pkg);
      if (!pkg.equals(pending) || SystemClock.elapsedRealtime() - attempted > 3000) {
        pending = pkg;
        attempted = SystemClock.elapsedRealtime();
        try {
          startActivity(
              new Intent(this, BlockActivity.class)
                  .putExtra("package", pkg)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (RuntimeException e) {
          store.diagnostic("block_activity_failed_" + e.getClass().getSimpleName());
        }
      }
      return;
    }
    pending = "";
    long deadline = store.pauseDeadline(pkg);
    long dnsDeadline = DnsController.get(this).deadline();
    if (dnsDeadline > 0 && (deadline == 0 || dnsDeadline < deadline)) deadline = dnsDeadline;
    if (deadline > 0) showTimer(pkg, deadline);
    else remove();
  }

  private void showTimer(String pkg, long deadline) {
    if (overlay != null && !isBlock && shown.equals(pkg)) {
      ((TextView) overlay).setText("◷  " + Ui.countdown(deadline));
      return;
    }
    remove();
    TextView t = Ui.text(this, "◷  " + Ui.countdown(deadline), 18, Ui.FG);
    t.setPadding(Ui.dp(this, 15), Ui.dp(this, 9), Ui.dp(this, 15), Ui.dp(this, 9));
    t.setBackground(Ui.shape(Ui.BG, Ui.BORDER, 26, this));
    t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    WindowManager.LayoutParams p =
        new WindowManager.LayoutParams(
            -2,
            -2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
    p.gravity = Gravity.RIGHT | Gravity.BOTTOM;
    p.x = Ui.dp(this, 42);
    p.y = Ui.dp(this, 62);
    add(t, p, pkg, false);
  }

  private void showBlock(String pkg) {
    if (overlay != null && isBlock && shown.equals(pkg)) return;
    remove();
    LinearLayout root =
        new LinearLayout(this) {
          @Override
          public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
              if (e.getAction() == KeyEvent.ACTION_UP) {
                performGlobalAction(GLOBAL_ACTION_HOME);
                front = "";
                remove();
              }
              return true;
            }
            return super.dispatchKeyEvent(e);
          }
        };
    root.setOrientation(LinearLayout.VERTICAL);
    root.setGravity(Gravity.CENTER);
    root.setBackground(Ui.tvBackground());
    TextView title = Ui.text(this, AppCatalog.label(this, pkg) + " is blocked", 34, Ui.FG);
    title.setGravity(Gravity.CENTER);
    root.addView(title);
    Ui.gap(root, 8);
    TextView hint = Ui.text(this, "Go read, finish a habit, or focus on something.", 16, Ui.MUTED);
    hint.setGravity(Gravity.CENTER);
    root.addView(hint);
    Ui.gap(root, 22);
    Button done =
        Ui.glassButton(
            this,
            "Done with TV",
            () -> {
              performGlobalAction(GLOBAL_ACTION_HOME);
              front = "";
              remove();
            });
    Ui.add(root, done, 260, 48);
    Ui.gap(root, 10);
    Ui.add(
        root,
        Ui.dockTab(
            this,
            "Options",
            false,
            () -> {
              try {
                startActivity(
                    new Intent(this, BlockActivity.class)
                        .putExtra("package", pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
              } catch (RuntimeException e) {
                Store.get(this).diagnostic("block_open_failed");
              }
            }),
        200,
        40);
    WindowManager.LayoutParams p =
        new WindowManager.LayoutParams(
            -1,
            -1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE);
    add(root, p, pkg, true);
    done.requestFocus();
  }

  private void add(View v, WindowManager.LayoutParams p, String pkg, boolean block) {
    try {
      ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(v, p);
      overlay = v;
      shown = pkg;
      isBlock = block;
      timerPackage = block ? "" : pkg;
      Store.get(this).diagnostic(block ? "blocking_overlay_added" : "countdown_added");
    } catch (RuntimeException e) {
      Store.get(this).diagnostic("overlay_failed_" + e.getClass().getSimpleName());
    }
  }

  private void remove() {
    if (overlay == null) return;
    try {
      ((WindowManager) getSystemService(WINDOW_SERVICE)).removeViewImmediate(overlay);
    } catch (IllegalArgumentException ignored) {
    }
    overlay = null;
    shown = "";
    isBlock = false;
    timerPackage = "";
  }

  @Override
  public void onInterrupt() {
    remove();
  }

  @Override
  public void onDestroy() {
    connected = false;
    instance = null;
    handler.removeCallbacksAndMessages(null);
    remove();
    Store.get(this).diagnostic("accessibility_disconnected");
    super.onDestroy();
  }
}
