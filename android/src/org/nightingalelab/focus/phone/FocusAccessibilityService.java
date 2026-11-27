package org.nightingalelab.focus.phone;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.*;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.*;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.*;
import org.nightingalelab.focus.telemetry.LocalTelemetry.Metric;

/**
 * Single Accessibility service handling both Option 1 protections:
 * 1. Private DNS & anti-tamper guard in Android / Samsung One UI Settings.
 * 2. Active tab domain extraction from Chrome, Samsung Internet, Brave, Edge, Firefox, etc.
 *    (holds the last observed domain while the browser hides the toolbar on scroll).
 */
public final class FocusAccessibilityService extends AccessibilityService {
  public static volatile boolean connected;
  private static volatile String feedBrowser = "";
  private static volatile String coverableFeedBrowser = "";

  static boolean onFeed(String pkg) {
    return !pkg.isEmpty() && pkg.equals(feedBrowser);
  }

  static boolean canCoverFeed(String pkg) {
    return connected && !pkg.isEmpty() && pkg.equals(coverableFeedBrowser);
  }

  public static boolean isEnabled(Context c) {
    if (connected) return true;
    String raw = android.provider.Settings.Secure.getString(
        c.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    return raw != null && raw.contains(c.getPackageName());
  }
  private static final Set<String> SETTINGS_PKGS = new HashSet<>(Arrays.asList(
      "com.android.settings",
      "com.samsung.android.settings",
      "com.android.packageinstaller",
      "com.google.android.packageinstaller",
      "com.samsung.android.packageinstaller"));

  private static volatile FocusAccessibilityService activeInstance;
  private static final Set<String> SHIELDED_APPS = new HashSet<>(Arrays.asList(
      "com.twitter.android",
      "com.google.android.youtube",
      "com.instagram.android",
      "com.reddit.frontpage",
      "com.zhiliaoapp.musically",
      "com.ss.android.ugc.trill",
      "tv.twitch.android.app",
      "com.facebook.katana",
      "com.netflix.mediaclient"));
  private static final Set<String> SHIELDED_HOSTS = new HashSet<>(Arrays.asList(
      "x.com", "twitter.com", "youtube.com", "youtu.be", "instagram.com",
      "reddit.com", "tiktok.com", "twitch.tv", "facebook.com", "netflix.com"));
  private static final long POST_PAUSE_GUARD_MS = 5 * 60_000L;

  static boolean isShieldedHost(String host) {
    if (host == null || host.isEmpty()) return false;
    String clean = host.trim().toLowerCase(Locale.ROOT);
    for (String bad : SHIELDED_HOSTS) {
      if (clean.equals(bad) || clean.endsWith("." + bad)) return true;
    }
    return false;
  }

  static void onProtectionRestored(Context context) {
    FocusAccessibilityService svc = activeInstance;
    if (svc != null) {
      svc.feedHandler.post(svc::enforceShieldNow);
    }
  }

  private String browserPkg = "";
  private String foregroundPkg = "";
  private int browserWindowId = -1;
  private long lastGuardBounceMs;
  private final Handler feedHandler = new Handler(Looper.getMainLooper());
  private View feedCover;
  private Rect feedCoverBounds;
  private View shieldCover;
  private String shieldCoverPkg = "";
  private boolean lockReceiverRegistered;
  private final Runnable recheckFeed = () -> inspectBrowserWindow(false);
  private final BroadcastReceiver lockReceiver = new BroadcastReceiver() {
    @Override public void onReceive(Context context, Intent intent) { inspectBrowserWindow(false); }
  };

  @Override
  protected void onServiceConnected() {
    connected = true;
    activeInstance = this;
    if (!lockReceiverRegistered) {
      IntentFilter filter = new IntentFilter(HabitNudges.LOCK_CHANGED);
      if (Build.VERSION.SDK_INT >= 33) registerReceiver(lockReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
      else registerReceiver(lockReceiver, filter);
      lockReceiverRegistered = true;
    }
    MonitorService.ensure(this);
    Store.get(this).diagnostic("accessibility_connected");
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event == null) return;
    Metric.ACCESSIBILITY_EVENT.count(1);
    String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
    int type = event.getEventType();
    boolean windowChanged = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED;
    if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !pkg.isEmpty()
        && !pkg.equals("com.android.systemui") && !pkg.equals(getPackageName())) {
      foregroundPkg = pkg;
    }

    if (BrowserUrlParser.isBrowser(pkg) || (windowChanged && !browserPkg.isEmpty())) {
      inspectBrowserWindow(windowChanged);
    }
    if (SETTINGS_PKGS.contains(pkg) && (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
      inspectSettingsForTamper(pkg);
    }
    enforceShieldForPackage(foregroundPkg);
  }

  private void enforceShieldNow() {
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root != null) {
      try {
        if (root.getPackageName() != null) foregroundPkg = root.getPackageName().toString();
      } finally {
        root.recycle();
      }
    }
    enforceShieldForPackage(foregroundPkg);
  }

  private void enforceShieldForPackage(String pkg) {
    DnsController dns = DnsController.get(this);
    boolean emergencyShield = dns.isEmergencyShieldActive();
    long endedAt = dns.lastPauseEndedElapsed();
    boolean postPauseKick = dns.enabled() && dns.deadline() == 0 && endedAt > 0
        && (SystemClock.elapsedRealtime() - endedAt) < POST_PAUSE_GUARD_MS;
    boolean blockedApp = !pkg.isEmpty() && SHIELDED_APPS.contains(pkg) && (emergencyShield || postPauseKick);
    if (!blockedApp) {
      removeShieldCover();
      return;
    }
    showShieldCover(pkg, emergencyShield ? "Shield active" : "Time's up");
  }

  private void showShieldCover(String pkg, String title) {
    WindowManager windows = getSystemService(WindowManager.class);
    if (windows == null) return;
    if (shieldCover != null && pkg.equals(shieldCoverPkg)) return;
    removeShieldCover();
    LinearLayout cover = Theme.column(this);
    cover.setGravity(Gravity.CENTER);
    cover.setBackgroundColor(Theme.BG);
    cover.setPadding(Theme.dp(this, 24), Theme.dp(this, 24), Theme.dp(this, 24), Theme.dp(this, 24));
    cover.addView(Theme.text(this, title, 20, Theme.FG, true));
    Theme.gap(cover, 8);
    cover.addView(Theme.text(this, "Protection is on for distraction apps.", 13, Theme.MUTED, false));
    Theme.gap(cover, 18);
    TextView home = Theme.outlinePillButton(this, "Go home", () -> {
      removeShieldCover();
      foregroundPkg = "";
      performGlobalAction(GLOBAL_ACTION_HOME);
    });
    cover.addView(home);
    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.OPAQUE);
    try {
      windows.addView(cover, params);
      shieldCover = cover;
      shieldCoverPkg = pkg;
    } catch (RuntimeException ignored) {}
  }

  private void removeShieldCover() {
    if (shieldCover == null) return;
    WindowManager windows = getSystemService(WindowManager.class);
    if (windows != null) {
      try { windows.removeViewImmediate(shieldCover); } catch (IllegalArgumentException ignored) {}
    }
    shieldCover = null;
    shieldCoverPkg = "";
  }

  private void inspectBrowserWindow(boolean windowChanged) {
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root == null) {
      updateFeedCover("", false, null);
      if (windowChanged) forgetBrowser();
      return;
    }
    try {
      String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
      if (!BrowserUrlParser.isBrowser(pkg)) {
        updateFeedCover("", false, null);
        if (windowChanged && !browserPkg.isEmpty() && !pkg.equals("com.android.systemui") && isAppWindow(root)) {
          forgetBrowser();
        }
        return;
      }

      int windowId = root.getWindowId();
      boolean sameWindow = pkg.equals(browserPkg) && windowId == browserWindowId;
      browserPkg = pkg;
      browserWindowId = windowId;
      Rect content = (HabitNudges.lockEnabled(this) || DnsController.get(this).isEmergencyShieldActive()) ? new Rect() : null;
      BrowserUrlParser.Parsed page = readAddressBar(root, pkg, content);
      boolean emergencyBlockedHost = DnsController.get(this).isEmergencyShieldActive()
          && page != null && isShieldedHost(page.host);
      updateFeedCover(pkg, HabitNudgePolicy.isFeed(page) || emergencyBlockedHost, content);
      if (page != null || !sameWindow) {
        Store.get(this).observeBrowserDomain(pkg, page == null ? "" : page.host);
      }
    } catch (RuntimeException ignored) {
      // A browser window can close while Android is returning its accessibility nodes.
      updateFeedCover("", false, null);
    } finally {
      root.recycle();
    }
  }

  private void updateFeedCover(String pkg, boolean confirmedFeed, Rect content) {
    feedBrowser = confirmedFeed ? pkg : "";
    coverableFeedBrowser = confirmedFeed && content != null && !content.isEmpty() ? pkg : "";
    feedHandler.removeCallbacks(recheckFeed);
    WindowManager windows = getSystemService(WindowManager.class);
    if (windows == null) return;
    if (!confirmedFeed || content == null || content.isEmpty()
        || (!HabitNudges.locked(this) && !DnsController.get(this).isEmergencyShieldActive())) {
      if (feedCover != null) {
        windows.removeViewImmediate(feedCover);
        feedCover = null;
        feedCoverBounds = null;
      }
      return;
    }
    WindowManager.LayoutParams params = new WindowManager.LayoutParams(content.width(), content.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.TOP | Gravity.LEFT;
    params.x = content.left;
    params.y = content.top;
    if (feedCover == null) {
      LinearLayout cover = Theme.column(this);
      cover.setGravity(Gravity.CENTER);
      cover.setBackgroundColor(Theme.BG);
      cover.addView(Theme.text(this, HabitNudgePolicy.LOCKED, 16, Theme.WARN, false));
      Theme.gap(cover, 16);
      TextView open = Theme.text(this, "Habits", 14, Theme.FG, false);
      open.setPadding(24, 18, 24, 18);
      open.setOnClickListener(v -> startActivity(FocusNotifications.habitsIntent(this)));
      cover.addView(open);
      windows.addView(cover, params);
      feedCover = cover;
    } else if (!content.equals(feedCoverBounds)) {
      windows.updateViewLayout(feedCover, params);
    }
    feedCoverBounds = content;
    feedHandler.postDelayed(recheckFeed, 1000);
  }

  private void forgetBrowser() {
    if (browserPkg.isEmpty()) return;
    browserPkg = "";
    browserWindowId = -1;
    Store.get(this).clearBrowserDomain();
  }

  private static boolean isAppWindow(AccessibilityNodeInfo root) {
    AccessibilityWindowInfo window = root.getWindow();
    if (window == null) return true;
    try {
      return window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION;
    } finally {
      window.recycle();
    }
  }

  // Null means the toolbar is temporarily hidden or being edited. Empty means there is no site.
  private static BrowserUrlParser.Parsed readAddressBar(AccessibilityNodeInfo root, String pkg, Rect content) {
    BrowserUrlParser.Parsed empty = new BrowserUrlParser.Parsed("", "");
    if (root.getChildCount() == 0) return empty;
    final int maxNodes = 160;
    ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
    queue.add(root);
    int discovered = 1;
    boolean sawEmptyBar = false;
    boolean multiplePages = false;
    BrowserUrlParser.Parsed page = null;
    Rect address = new Rect();
    try {
      while (!queue.isEmpty()) {
        AccessibilityNodeInfo node = queue.remove();
        try {
          if (!pkg.equals(String.valueOf(node.getPackageName()))) continue;
          if ("android.webkit.WebView".equals(String.valueOf(node.getClassName()))) {
            if (content != null && node.isVisibleToUser()) {
              Rect viewport = new Rect();
              node.getBoundsInScreen(viewport);
              if (!viewport.isEmpty()) {
                multiplePages |= !content.isEmpty() && !content.equals(viewport);
                content.set(viewport);
              }
            }
            continue;
          }
          String id = node.getViewIdResourceName();
          if (node.isVisibleToUser()) {
            if (BrowserUrlParser.isNonPageView(pkg, id)) {
              String stripItem = BrowserUrlParser.tabStripItemViewId(id);
              if (stripItem == null || !hasVisibleView(node, stripItem)) return empty;
              continue;
            }
            if (BrowserUrlParser.isAddressBar(pkg, id) && !(node.isEditable() && node.isFocused())) {
              BrowserUrlParser.Parsed parsed = node.isShowingHintText() ? null : parseAddress(node);
              if (parsed != null) {
                if (content == null) return parsed;
                page = parsed;
                node.getBoundsInScreen(address);
              } else sawEmptyBar = true;
            }
          }
          for (int i = 0; i < node.getChildCount() && discovered < maxNodes; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
              queue.add(child);
              discovered++;
            }
          }
        } finally {
          if (node != root) node.recycle();
        }
      }
      if (content != null && (page == null || address.isEmpty() || multiplePages
          || (content.top < address.bottom && content.bottom > address.top))) {
        // Do not draw over a browser unless we can separate the page from its toolbar.
        content.setEmpty();
      }
      return page != null ? page : sawEmptyBar ? empty : null;
    } finally {
      for (AccessibilityNodeInfo node : queue) node.recycle();
    }
  }

  private static boolean hasVisibleView(AccessibilityNodeInfo root, String viewId) {
    List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByViewId(viewId);
    try {
      for (AccessibilityNodeInfo node : matches) {
        if (node.isVisibleToUser()) return true;
      }
      return false;
    } finally {
      for (AccessibilityNodeInfo node : matches) node.recycle();
    }
  }

  private static BrowserUrlParser.Parsed parseAddress(AccessibilityNodeInfo node) {
    CharSequence address = node.getText();
    if (address == null || address.length() == 0) address = node.getContentDescription();
    return address == null ? null : BrowserUrlParser.parse(address.toString());
  }

  private void inspectSettingsForTamper(String pkg) {
    DnsController dns = DnsController.get(this);
    if (!dns.enabled() || dns.profile().isEmpty() || dns.deadline() > 0) return;
    if (!Store.get(this).policyConfig().guardPrivateDns) return;

    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root == null) return;
    try {
      String reason = detectProtectedSettingsScreen(root, pkg);
      if (reason != null) {
        dns.snapBackIfTampered();
        long now = SystemClock.elapsedRealtime();
        long elapsed = now - lastGuardBounceMs;
        if (elapsed < 500) return;
        boolean repeated = elapsed < 4000;
        lastGuardBounceMs = now;
        Store.get(this).diagnostic("settings_guard_triggered_" + reason);
        performGlobalAction(repeated ? GLOBAL_ACTION_HOME : GLOBAL_ACTION_BACK);
        try {
          startActivity(new Intent(this, GuardActivity.class)
              .putExtra("reason", reason)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (RuntimeException ignored) {}
      }
    } catch (RuntimeException ignored) {
    } finally {
      root.recycle();
    }
  }

  /**
   * Scans the active Settings window (bounded depth & node count) for:
   * - Private DNS dialog / preference screen (AOSP & Samsung One UI, English & Spanish)
   * - Focus's own Uninstall / Force stop confirmation
   */
  private String detectProtectedSettingsScreen(AccessibilityNodeInfo root, String pkg) {
    ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
    queue.add(root);
    int visited = 0;
    boolean sawPrivateDnsHeading = false;
    boolean sawPrivateDnsOption = false;
    boolean sawFocusSelf = false;
    boolean sawUninstallOrForceStop = false;

    while (!queue.isEmpty() && visited < 120) {
      AccessibilityNodeInfo node = queue.poll();
      visited++;
      String id = node.getViewIdResourceName();
      String text = node.getText() == null ? "" : node.getText().toString().toLowerCase(Locale.ROOT);
      String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().toLowerCase(Locale.ROOT);
      String combined = text + " " + desc;

      if (id != null) {
        String lowId = id.toLowerCase(Locale.ROOT);
        if (lowId.contains("private_dns_mode_off")
            || lowId.contains("private_dns_mode_opportunistic")
            || lowId.contains("private_dns_mode_provider")
            || lowId.contains("private_dns_specifier")) {
          return "private_dns_dialog";
        }
      }

      if (combined.contains("private dns") || combined.contains("dns privado")) {
        sawPrivateDnsHeading = true;
      }
      if (combined.contains("private dns provider hostname")
          || combined.contains("nombre de host del proveedor")
          || combined.contains("dns.nextdns.io")
          || (sawPrivateDnsHeading && (combined.contains("automatic") || combined.contains("automático"))
              && (combined.contains("off") || combined.contains("desactivado")))) {
        sawPrivateDnsOption = true;
      }
      if (combined.contains("focus") && (combined.contains("org.nightingalelab") || combined.contains("app info") || combined.contains("uninstall"))) {
        sawFocusSelf = true;
      }
      if (combined.contains("uninstall") || combined.contains("desinstalar")
          || combined.contains("force stop") || combined.contains("forzar detención")) {
        sawUninstallOrForceStop = true;
      }

      int childCount = node.getChildCount();
      for (int i = 0; i < childCount && visited + queue.size() < 160; i++) {
        AccessibilityNodeInfo child = node.getChild(i);
        if (child != null) queue.add(child);
      }
      if (node != root) node.recycle();
    }
    while (!queue.isEmpty()) {
      AccessibilityNodeInfo n = queue.poll();
      if (n != root) n.recycle();
    }
    if (sawPrivateDnsHeading && sawPrivateDnsOption) return "private_dns_dialog";
    if (pkg.contains("packageinstaller") && sawFocusSelf && sawUninstallOrForceStop) return "uninstall_guard";
    return null;
  }

  @Override
  public void onInterrupt() {}

  @Override
  public void onDestroy() {
    connected = false;
    activeInstance = null;
    if (HabitNudges.locked(this)) FocusNotifications.clearHabitNudge(this);
    HabitNudges.setLocked(this, false);
    if (lockReceiverRegistered) {
      unregisterReceiver(lockReceiver);
      lockReceiverRegistered = false;
    }
    updateFeedCover("", false, null);
    removeShieldCover();
    Store.get(this).clearBrowserDomain();
    Store.get(this).diagnostic("accessibility_disconnected");
    super.onDestroy();
  }
}
