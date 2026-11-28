package org.nightingalelab.focus.tv;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.os.*;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import org.nightingalelab.focus.habits.HabitStore;
import org.nightingalelab.focus.habits.HabitViews;
import org.nightingalelab.focus.habits.Habits;

public final class MainActivity extends Activity {
  public static volatile boolean visible;

  private enum Mode { HOME, HABITS, ACTIVITY, RESTRICTIONS }

  private Mode mode = Mode.HOME;
  private boolean week;
  private boolean controlOpen;
  private String controlTile = "dns";
  private FrameLayout overlay;
  private int selectedSlot = -1, selectedCategory = -1;
  private LinearLayout root;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable refresh =
      new Runnable() {
        public void run() {
          if (visible && hasWindowFocus()) render();
          handler.postDelayed(this, 5000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    Ui.fullscreen(this);
    if (state != null) {
      String saved = state.getString("mode", Mode.HOME.name());
      try { mode = Mode.valueOf(saved); } catch (IllegalArgumentException ignored) { mode = Mode.HOME; }
      week = state.getBoolean("week");
      controlOpen = state.getBoolean("controlOpen");
      controlTile = state.getString("controlTile", "dns");
      selectedSlot = state.getInt("selectedSlot", -1);
      selectedCategory = state.getInt("selectedCategory", -1);
    }
    Store.get(this).ensureDefaultBlocks();
    if (Store.get(this).usageAllowed() || LanController.get(this).enabled()) MonitorService.ensure(this);
    render();
  }

  @Override
  public void onResume() {
    super.onResume();
    visible = true;
    Ui.fullscreen(this);
    AppCatalog.invalidate();
    Store.get(this).ensureDefaultBlocks();
    if (Store.get(this).usageAllowed() || LanController.get(this).enabled()) MonitorService.ensure(this);
    if (DnsController.get(this).enabled()) {
      MonitorService.ensure(this);
      DnsController.get(this)
          .checkAsync(
              () -> {
                if (visible && hasWindowFocus()) render();
              });
    }
    render();
    handler.postDelayed(refresh, 2000);
    if (LanController.get(this).pendingApproval() != null) startActivity(new Intent(this, PairingActivity.class));
  }

  @Override
  public void onPause() {
    visible = false;
    handler.removeCallbacks(refresh);
    super.onPause();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    b.putString("mode", mode.name());
    b.putBoolean("week", week);
    b.putBoolean("controlOpen", controlOpen);
    b.putString("controlTile", controlTile);
    b.putInt("selectedSlot", selectedSlot);
    b.putInt("selectedCategory", selectedCategory);
    super.onSaveInstanceState(b);
  }

  @Override
  public void onNewIntent(Intent i) {
    super.onNewIntent(i);
    render();
  }

  private Button button(String text, String id, Runnable action) {
    Button b = Ui.button(this, text, action);
    b.setId(controlId(id));
    return b;
  }

  private Button dockButton(String text, String id, boolean selected, Runnable action) {
    Button b = Ui.dockTab(this, text, selected, action);
    b.setId(controlId(id));
    return b;
  }

  static int controlId(String id) {
    return (id.hashCode() & 0x00ffffff) + 1;
  }

  private void render() {
    if (HabitViews.Hold.active()) return;
    int focused = getCurrentFocus() == null ? 0 : getCurrentFocus().getId();
    if (overlay == null) {
      overlay = new FrameLayout(this);
      setContentView(overlay);
    }
    View undoToast = overlay.findViewWithTag("habitUndo");
    overlay.removeAllViews();

    root = Ui.column(this);
    root.setBackground(Ui.tvBackground());
    root.setPadding(Ui.dp(this, 44), Ui.dp(this, 24), Ui.dp(this, 44), Ui.dp(this, 24));
    overlay.addView(root, new FrameLayout.LayoutParams(-1, -1));

    View defaultFocus;
    if (mode == Mode.HOME) {
      defaultFocus = renderHome();
    } else if (mode == Mode.HABITS) {
      defaultFocus = renderHabitsScreen();
    } else if (mode == Mode.ACTIVITY) {
      defaultFocus = renderActivityScreen();
    } else {
      defaultFocus = renderRestrictionsScreen();
    }

    if (controlOpen) {
      root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
      root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
      View sheetFocus = renderControlSheet();
      if (sheetFocus != null) defaultFocus = sheetFocus;
    }

    if (undoToast != null) overlay.addView(undoToast);

    View prior = focused == 0 ? null : overlay.findViewById(focused);
    if (prior != null && (!controlOpen || isDescendant(overlay.findViewWithTag("controlSheet"), prior))) {
      prior.requestFocus();
    } else if (defaultFocus != null) {
      defaultFocus.requestFocus();
    }
  }

  private static boolean isDescendant(View container, View target) {
    if (container == null || target == null) return false;
    ViewParent p = target.getParent();
    while (p != null) {
      if (p == container) return true;
      p = p.getParent();
    }
    return container == target;
  }

  /** Design C: ultra-minimal Apple TV calm Home with floating glass status pill and bottom dock. */
  private View renderHome() {
    long now = System.currentTimeMillis();
    int day = Habits.day(now);
    Store store = Store.get(this);
    DnsController dns = DnsController.get(this);
    Habits ledger = HabitStore.get(this).read();
    List<Habits.Status> statuses = ledger.status(now);
    Habits.Status nextDue = null;
    int dueCount = 0;
    for (Habits.Status s : statuses) {
      if (s.due) {
        if (nextDue == null) nextDue = s;
        dueCount++;
      }
    }
    boolean coins = ledger.wallet.active(day);
    long balance = ledger.wallet.balance();
    boolean missingAccess = !store.usageAllowed() || !RestrictionService.connected;
    List<AppCatalog.App> blocked = store.blockedApps();

    // Top bar: minimal brand on left, floating glass status pill on right.
    LinearLayout top = Ui.row(this);
    ImageView mark = new ImageView(this);
    mark.setImageResource(R.drawable.focus_ring);
    mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    top.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
    Ui.gap(top, 8);
    top.addView(Ui.text(this, "Focus", 16, Ui.SUB), new LinearLayout.LayoutParams(0, -2, 1));

    String clockStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(now));
    String dnsText = TvRedirect.dnsStatusBadge(dns.status());
    boolean dnsBroken = !dns.permitted() || (dns.enabled() && dns.deadline() == 0 && !dns.active());
    boolean dnsWarn = dns.deadline() > 0 || !dns.enabled();
    int dnsColor = dnsBroken ? Ui.RED : dnsWarn ? Ui.ORANGE : Ui.SUB;
    SpannableStringBuilder pill = new SpannableStringBuilder();
    pill.append(clockStr).append("  ·  ");
    int dnsStart = pill.length();
    pill.append(dnsText);
    pill.setSpan(new ForegroundColorSpan(dnsColor), dnsStart, pill.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    if (coins) pill.append("  ·  ").append(String.valueOf(balance)).append(" coins");
    TextView statusPill = Ui.text(this, "", 13, Ui.SUB);
    statusPill.setText(pill);
    statusPill.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
    statusPill.setBackground(Ui.shape(Ui.GLASS, Ui.GLASS_BORDER, 999, this));
    top.addView(statusPill);
    root.addView(top);

    // Generous center-left Apple TV hero.
    LinearLayout hero = Ui.column(this);
    hero.setGravity(Gravity.CENTER_VERTICAL);
    String dueMeta = nextDue != null && nextDue.habit.time >= 0
        ? String.format(Locale.ROOT, "%02d:%02d", nextDue.habit.time / 60, nextDue.habit.time % 60)
        : "";
    String dueName = nextDue == null ? "" : nextDue.habit.name;
    boolean overdue = nextDue != null && (nextDue.overdue || nextDue.grace);
    String kicker = TvRedirect.homeKicker(missingAccess, dueName, overdue, dueMeta);
    int kickerColor = missingAccess || overdue ? Ui.RED : nextDue != null ? Ui.ORANGE : Ui.MUTED;
    hero.addView(Ui.text(this, kicker, 15, kickerColor));
    Ui.gap(hero, 6);

    TextView headline = Ui.text(this, TvRedirect.homeHeadline(missingAccess, dueName, day), 36, Ui.FG);
    headline.setMaxWidth(Ui.dp(this, 620));
    hero.addView(headline);
    Ui.gap(hero, 18);

    View primaryFocus;
    if (missingAccess) {
      Button setup = Ui.glassButton(
          this,
          !store.usageAllowed() ? "Enable usage access" : "Enable app restrictions",
          () -> openSetting(!store.usageAllowed()
              ? Settings.ACTION_USAGE_ACCESS_SETTINGS : Settings.ACTION_ACCESSIBILITY_SETTINGS));
      setup.setId(controlId("home_setup"));
      hero.addView(setup, new LinearLayout.LayoutParams(-2, -2));
      primaryFocus = setup;
    } else if (nextDue != null) {
      Habits.Status target = nextDue;
      HabitViews.Hold hold = new HabitViews.Hold(
          this, target, 17, coins, () -> HabitDashboard.completeHabit(this, target, overlay, this::render), null);
      hold.setId(controlId("home_habit_" + target.habit.id));
      Ui.applyFocusLift(hold, 1.02f);
      hero.addView(hold, new LinearLayout.LayoutParams(Ui.dp(this, 440), -2));
      Ui.gap(hero, 12);
      Button blockedBtn = Ui.glassButton(
          this,
          blocked.size() + (blocked.size() == 1 ? " app blocked" : " apps blocked"),
          () -> openControl("apps"));
      blockedBtn.setId(controlId("home_blocked"));
      hero.addView(blockedBtn, new LinearLayout.LayoutParams(-2, -2));
      primaryFocus = hold;
    } else {
      LinearLayout actions = Ui.row(this);
      Button done = Ui.glassButton(this, "Done with TV", this::goHome);
      done.setId(controlId("home_done"));
      actions.addView(done);
      Ui.gap(actions, 10);
      Button blockedBtn = Ui.glassButton(
          this,
          blocked.size() + (blocked.size() == 1 ? " app blocked" : " apps blocked"),
          () -> openControl("apps"));
      blockedBtn.setId(controlId("home_blocked"));
      actions.addView(blockedBtn);
      hero.addView(actions);
      primaryFocus = done;
    }

    if (store.usageAllowed() && (Store.heartbeat == 0 || SystemClock.elapsedRealtime() - Store.heartbeat > 15000)) {
      Ui.gap(hero, 12);
      Button restart = Ui.dockTab(this, "Restart tracking", false, () -> {
        MonitorService.ensure(this);
        render();
      });
      restart.setId(controlId("home_restart"));
      hero.addView(restart, new LinearLayout.LayoutParams(-2, -2));
    }

    root.addView(hero, new LinearLayout.LayoutParams(-1, 0, 1));

    // Bottom minimal dock.
    long todayMs = 0;
    for (long ms : store.usage(Store.dayStart(now), now).values()) todayMs += ms;
    LinearLayout dock = Ui.row(this);
    dock.addView(dockButton("Home", "dock_home", true, () -> { mode = Mode.HOME; render(); }));
    Ui.gap(dock, 8);
    String habitsLabel = dueCount > 0 ? "Habits · " + dueCount : "Habits";
    dock.addView(dockButton(habitsLabel, "dock_habits", false, () -> { mode = Mode.HABITS; render(); }));
    Ui.gap(dock, 8);
    dock.addView(dockButton("Activity · " + Ui.time(todayMs), "dock_activity", false, () -> {
      mode = Mode.ACTIVITY;
      week = false;
      selectedSlot = -1;
      selectedCategory = -1;
      render();
    }));
    Ui.gap(dock, 8);
    dock.addView(dockButton("Control & Settings", "dock_control", false, () -> openControl("dns")));
    root.addView(dock);
    return primaryFocus;
  }

  private View renderHabitsScreen() {
    LinearLayout header = Ui.row(this);
    header.addView(Ui.text(this, "Habits", 24, Ui.FG), new LinearLayout.LayoutParams(0, -2, 1));
    Button back = dockButton("Back to Home", "habits_back", false, () -> { mode = Mode.HOME; render(); });
    header.addView(back);
    Ui.gap(header, 8);
    header.addView(dockButton("Control & Settings", "habits_control", false, () -> openControl("dns")));
    root.addView(header);
    Ui.gap(root, 12);
    root.addView(new HabitDashboard(this, System.currentTimeMillis()).view(overlay, this::render),
        new LinearLayout.LayoutParams(-1, 0, 1));
    return back;
  }

  private View renderActivityScreen() {
    LinearLayout header = Ui.row(this);
    header.addView(Ui.text(this, "Activity", 24, Ui.FG), new LinearLayout.LayoutParams(0, -2, 1));
    Button today = dockButton("Today", "act_today", !week, () -> selectPeriod(false));
    header.addView(today);
    Ui.gap(header, 8);
    Button weekBtn = dockButton("Week", "act_week", week, () -> selectPeriod(true));
    header.addView(weekBtn);
    Ui.gap(header, 8);
    header.addView(dockButton("Home", "act_home", false, () -> { mode = Mode.HOME; render(); }));
    Ui.gap(header, 8);
    header.addView(dockButton("Control & Settings", "act_control", false, () -> openControl("dns")));
    root.addView(header);
    Ui.gap(root, 12);
    if (Store.get(this).usageAllowed()) {
      showUsage();
    } else {
      root.addView(button("Enable usage access", "usage_access",
          () -> openSetting(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
    }
    return week ? weekBtn : today;
  }

  private View renderRestrictionsScreen() {
    LinearLayout header = Ui.row(this);
    header.addView(Ui.text(this, "Blocked apps", 24, Ui.FG), new LinearLayout.LayoutParams(0, -2, 1));
    Button back = dockButton("Back to Home", "nav_home", false, () -> { mode = Mode.HOME; render(); });
    header.addView(back);
    root.addView(header);
    Ui.gap(root, 6);
    root.addView(Ui.text(this, "You can block apps here. Unblocking requires Focus on your phone.", 14, Ui.MUTED));
    Ui.gap(root, 12);
    showRestrictions();
    return back;
  }

  /** Floating Apple TV Control Center glass sheet on the right side of the screen. */
  private View renderControlSheet() {
    View scrim = new View(this);
    scrim.setBackgroundColor(0x99000000);
    scrim.setOnClickListener(v -> closeControl());
    overlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

    LinearLayout sheet = Ui.column(this);
    sheet.setTag("controlSheet");
    sheet.setBackground(Ui.shape(Ui.SHEET_BG, Ui.GLASS_BORDER, 20, this));
    sheet.setPadding(Ui.dp(this, 22), Ui.dp(this, 20), Ui.dp(this, 22), Ui.dp(this, 18));

    Store store = Store.get(this);
    DnsController dns = DnsController.get(this);
    LanController lan = LanController.get(this);
    List<AppCatalog.App> blocked = store.blockedApps();
    boolean healthy = store.usageAllowed() && RestrictionService.connected;

    // Sheet header
    LinearLayout head = Ui.row(this);
    head.addView(Ui.text(this, "Focus Control", 20, Ui.FG), new LinearLayout.LayoutParams(0, -2, 1));
    String nowStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
    head.addView(Ui.text(this, nowStr + " · " + (healthy ? "Locked" : "Setup"), 13, healthy ? Ui.MUTED : Ui.RED));
    sheet.addView(head);
    Ui.gap(sheet, 14);

    // 2x2 Control Center tile grid
    LinearLayout row1 = Ui.row(this);
    String dnsShort = !dns.permitted() ? "Setup needed" : dns.enabled() ? dns.status() : "Not connected";
    boolean dnsTileBroken = !dns.permitted() || (dns.enabled() && dns.deadline() == 0 && !dns.active());
    int dnsColor = dnsTileBroken ? Ui.RED : (dns.deadline() > 0 || !dns.enabled()) ? Ui.ORANGE : Ui.MUTED;
    LinearLayout tDns = Ui.controlTile(this, "NextDNS", dnsShort, dnsColor, "dns".equals(controlTile), () -> selectTile("dns"));
    tDns.setId(controlId("tile_dns"));
    row1.addView(tDns, new LinearLayout.LayoutParams(0, -2, 1));
    Ui.gap(row1, 10);
    LinearLayout tApps = Ui.controlTile(this, "Blocked apps", blocked.size() + " blocked", Ui.MUTED, "apps".equals(controlTile), () -> selectTile("apps"));
    tApps.setId(controlId("tile_apps"));
    row1.addView(tApps, new LinearLayout.LayoutParams(0, -2, 1));
    sheet.addView(row1);
    Ui.gap(sheet, 10);

    LinearLayout row2 = Ui.row(this);
    LinearLayout tDev = Ui.controlTile(this, "Devices", lan.pairedCount() + " paired", Ui.MUTED, "devices".equals(controlTile), () -> selectTile("devices"));
    tDev.setId(controlId("tile_devices"));
    row2.addView(tDev, new LinearLayout.LayoutParams(0, -2, 1));
    Ui.gap(row2, 10);
    LinearLayout tHealth = Ui.controlTile(this, "Health", healthy ? "Healthy" : "Action needed", healthy ? Ui.MUTED : Ui.RED, "health".equals(controlTile), () -> selectTile("health"));
    tHealth.setId(controlId("tile_health"));
    row2.addView(tHealth, new LinearLayout.LayoutParams(0, -2, 1));
    sheet.addView(row2);
    Ui.gap(sheet, 14);

    // Live detail panel for the active tile
    ScrollView detailScroll = new ScrollView(this);
    LinearLayout detail = Ui.column(this);
    detail.setBackground(Ui.shape(0x10ffffff, Ui.GLASS_BORDER, 14, this));
    detail.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
    populateControlDetail(detail, store, dns, lan, blocked);
    detailScroll.addView(detail);
    sheet.addView(detailScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    Ui.gap(sheet, 10);

    Button closeBtn = Ui.dockTab(this, "Close", false, this::closeControl);
    closeBtn.setId(controlId("control_close"));
    LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(-2, -2);
    closeLp.gravity = Gravity.END;
    sheet.addView(closeBtn, closeLp);

    int width = Math.max(Ui.dp(this, 410), getResources().getDisplayMetrics().widthPixels * 47 / 100);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, -1, Gravity.END);
    int m = Ui.dp(this, 18);
    lp.setMargins(m, m, m, m);
    overlay.addView(sheet, lp);

    if ("apps".equals(controlTile)) return tApps;
    if ("devices".equals(controlTile)) return tDev;
    if ("health".equals(controlTile)) return tHealth;
    return tDns;
  }

  private void populateControlDetail(LinearLayout detail, Store store, DnsController dns,
      LanController lan, List<AppCatalog.App> blocked) {
    if ("dns".equals(controlTile)) {
      boolean locked = dns.enabled() && !dns.profile().isEmpty();
      String heading;
      int headingColor = Ui.FG;
      String desc;
      if (!locked) {
        heading = "NextDNS setup";
        desc = "Connect your six-character NextDNS profile. External changes will snap back automatically.";
      } else if (dns.deadline() > 0) {
        heading = "NextDNS is paused · " + Ui.countdown(dns.deadline());
        headingColor = Ui.ORANGE;
        desc = "Profile " + dns.profile() + " will resume automatically when the timer ends.";
      } else if (!dns.active()) {
        heading = "NextDNS needs attention · " + dns.status();
        headingColor = Ui.RED;
        desc = "Profile " + dns.profile() + " is configured, but Private DNS is not validated on the active network.";
      } else {
        heading = "NextDNS is locked on";
        desc = "Profile " + dns.profile() + " is active. External changes in TV Settings snap back automatically.";
      }
      detail.addView(Ui.text(this, heading, 15, headingColor));
      Ui.gap(detail, 4);
      detail.addView(Ui.text(this, desc, 13, Ui.MUTED));
      Ui.gap(detail, 12);
      if (!dns.permitted()) {
        detail.addView(Ui.text(this, "Computer setup required (WRITE_SECURE_SETTINGS).", 13, Ui.RED));
      } else if (!locked) {
        detail.addView(button("Connect NextDNS", "ctrl_dns_connect", () -> {
          closeControl();
          dns();
        }));
      } else {
        if (dns.deadline() > 0) {
          detail.addView(button("Resume filtering", "ctrl_dns_resume", () -> {
            try {
              dns.resume();
              render();
            } catch (RuntimeException e) {
              Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
          }));
          Ui.gap(detail, 8);
        }
        detail.addView(button("Check connection", "ctrl_dns_check", () -> {
          dns.checkAsync(this::render);
          Toast.makeText(this, "Checking NextDNS…", Toast.LENGTH_SHORT).show();
        }));
        Ui.gap(detail, 8);
        detail.addView(Ui.text(this, "Free Time and profile removal live on your phone.", 12, Ui.MUTED));
      }
      return;
    }

    if ("apps".equals(controlTile)) {
      detail.addView(Ui.text(this, "Blocked apps · Tighten only on TV", 15, Ui.FG));
      Ui.gap(detail, 4);
      detail.addView(Ui.text(this, "YouTube, Twitch, Netflix, and Disney+ are blocked by default. Unblocking requires your phone.", 13, Ui.MUTED));
      Ui.gap(detail, 10);
      if (blocked.isEmpty()) {
        detail.addView(Ui.text(this, "No blocked apps installed yet.", 13, Ui.MUTED));
      } else {
        int shown = Math.min(4, blocked.size());
        for (int i = 0; i < shown; i++) {
          AppCatalog.App app = blocked.get(i);
          LinearLayout line = Ui.row(this);
          line.addView(Ui.text(this, app.label, 14, Ui.FG), new LinearLayout.LayoutParams(0, -2, 1));
          line.addView(Ui.text(this, "Locked on TV", 12, Ui.MUTED));
          detail.addView(line);
          Ui.gap(detail, 4);
        }
        if (blocked.size() > shown) {
          detail.addView(Ui.text(this, "+" + (blocked.size() - shown) + " more blocked", 12, Ui.MUTED));
          Ui.gap(detail, 4);
        }
      }
      Ui.gap(detail, 8);
      detail.addView(button("Block another app", "ctrl_apps_all", () -> {
        controlOpen = false;
        mode = Mode.RESTRICTIONS;
        render();
      }));
      return;
    }

    if ("devices".equals(controlTile)) {
      detail.addView(Ui.text(this, "Connected devices", 15, Ui.FG));
      Ui.gap(detail, 4);
      detail.addView(Ui.text(this,
          lan.pairedCount() + (lan.pairedCount() == 1 ? " paired manager" : " paired managers")
              + " · " + (lan.enabled() ? "Nearby discovery on" : "Nearby discovery off"),
          13, Ui.MUTED));
      Ui.gap(detail, 6);
      detail.addView(Ui.text(this, "Manage TV rules, habits, and Free Time from Focus on your phone or laptop.", 13, Ui.MUTED));
      Ui.gap(detail, 12);
      if (!lan.enabled()) {
        detail.addView(button("Allow nearby connections", "ctrl_lan_enable", () -> {
          lan.enable();
          Toast.makeText(this, "Look for this TV on your phone or laptop", Toast.LENGTH_LONG).show();
          render();
        }));
        Ui.gap(detail, 8);
      }
      detail.addView(button("Pause history", "ctrl_pause_history", this::pauseHistory));
      if (lan.pairedCount() > 0) {
        Ui.gap(detail, 8);
        detail.addView(button("Disconnect all managers", "ctrl_lan_disconnect", () -> {
          LinearLayout confirm = Ui.column(this);
          confirm.addView(Ui.text(
              this,
              "Laptops and phones will need to pair again. Local tracking, app rules, and NextDNS stay on.",
              15,
              Ui.MUTED));
          Dialog dialog = Ui.dialog(this, "Disconnect all managers?", confirm);
          Ui.gap(confirm, 12);
          Button cancel = Ui.button(this, "Cancel", dialog::dismiss);
          confirm.addView(cancel);
          Ui.gap(confirm, 8);
          confirm.addView(Ui.button(this, "Disconnect all", () -> {
            lan.disconnectAll();
            dialog.dismiss();
            render();
          }));
          dialog.show();
        }));
      }
      if (!lan.error().isEmpty()) {
        Ui.gap(detail, 8);
        detail.addView(Ui.text(this, lan.error(), 12, Ui.ORANGE));
      }
      return;
    }

    // Health tile: never show Accessibility/Usage shortcuts when already healthy.
    detail.addView(Ui.text(this, "Protection health", 15, Ui.FG));
    Ui.gap(detail, 6);
    boolean usageOk = store.usageAllowed();
    boolean accOk = RestrictionService.connected;
    boolean trackingOk = SystemClock.elapsedRealtime() - Store.heartbeat < 15000;
    detail.addView(Ui.text(this, "Usage access · " + (usageOk ? "On" : "Off"), 13, usageOk ? Ui.SUB : Ui.RED));
    Ui.gap(detail, 4);
    detail.addView(Ui.text(this, "App restrictions · " + (accOk ? "Running" : "Off"), 13, accOk ? Ui.SUB : Ui.RED));
    Ui.gap(detail, 4);
    detail.addView(Ui.text(this, "Tracking · " + (trackingOk ? "Running" : "Starting"), 13, Ui.SUB));
    Ui.gap(detail, 10);
    if (!usageOk) {
      detail.addView(button("Enable usage access", "ctrl_enable_usage",
          () -> openSetting(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
      Ui.gap(detail, 8);
    }
    if (!accOk) {
      detail.addView(button("Enable app restrictions", "ctrl_enable_acc",
          () -> openSetting(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
      Ui.gap(detail, 8);
    }
    if (usageOk && accOk) {
      detail.addView(Ui.text(this, "System permission shortcuts stay hidden while protection is active.", 12, Ui.MUTED));
      Ui.gap(detail, 10);
    }
    detail.addView(button("Export history", "ctrl_export", this::exportHistory));
    Ui.gap(detail, 8);
    detail.addView(button("Diagnostics", "ctrl_diag", this::diagnostics));
  }

  private void openControl(String tile) {
    controlTile = tile;
    controlOpen = true;
    render();
  }

  private void selectTile(String tile) {
    controlTile = tile;
    render();
  }

  private void closeControl() {
    controlOpen = false;
    render();
  }

  private void goHome() {
    startActivity(
        new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }

  private void selectPeriod(boolean showWeek) {
    mode = Mode.ACTIVITY;
    week = showWeek;
    selectedSlot = -1;
    selectedCategory = -1;
    render();
  }

  void openRestrictions() {
    controlOpen = false;
    mode = Mode.RESTRICTIONS;
    render();
  }

  @Override
  public void onBackPressed() {
    if (controlOpen) closeControl();
    else if (mode != Mode.HOME) { mode = Mode.HOME; render(); }
    else super.onBackPressed();
  }

  private void showUsage() {
    try {
      UsageDashboard dashboard = new UsageDashboard(this, week, selectedSlot, selectedCategory,
          slot -> {
            int prior = selectedSlot;
            selectedSlot = selectedSlot == slot ? -1 : slot;
            selectedCategory = -1;
            render();
            if (slot < 0 && prior >= 0) {
              View previousBar = findViewById(controlId("chart_" + prior));
              if (previousBar != null) previousBar.requestFocus();
            }
          },
          category -> {
            selectedCategory = selectedCategory == category ? -1 : category;
            render();
          });
      root.addView(dashboard.view(), new LinearLayout.LayoutParams(-1, 0, 1));
    } catch (org.json.JSONException e) {
      root.addView(Ui.text(this, "Screen time unavailable", 16, Ui.MUTED));
      Store.get(this).diagnostic("dashboard_data_failed");
    }
  }

  private View appRow(AppCatalog.App app, String suffix, Runnable action) {
    LinearLayout row = Ui.row(this);
    row.setId((("app_" + app.pkg).hashCode() & 0x00ffffff) + 1);
    row.setFocusable(true);
    row.setClickable(true);
    row.setContentDescription(app.label + ", " + suffix);
    row.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
    TextView name = Ui.text(this, app.label, 17, Ui.FG),
        amount = Ui.text(this, suffix, 15, Ui.MUTED);
    name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    amount.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(amount);
    android.graphics.drawable.StateListDrawable bg =
        new android.graphics.drawable.StateListDrawable();
    bg.addState(new int[] {android.R.attr.state_focused}, Ui.shape(Ui.FG, Ui.FG, 12, this));
    bg.addState(new int[] {}, Ui.shape(Ui.GLASS, Ui.GLASS_BORDER, 12, this));
    row.setBackground(bg);
    row.setOnFocusChangeListener(
        (v, focus) -> {
          name.setTextColor(focus ? Ui.BG : Ui.FG);
          amount.setTextColor(focus ? 0xff444444 : Ui.MUTED);
          v.animate()
              .scaleX(focus ? 1.015f : 1f)
              .scaleY(focus ? 1.015f : 1f)
              .setDuration(130)
              .start();
        });
    row.setOnClickListener(v -> action.run());
    Ui.buttonSemantics(row);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 54));
    p.setMargins(0, 0, 0, Ui.dp(this, 8));
    row.setLayoutParams(p);
    return row;
  }

  private void showRestrictions() {
    ScrollView scroll = new ScrollView(this);
    LinearLayout list = Ui.column(this);
    if (!RestrictionService.connected || !Store.get(this).usageAllowed()) {
      list.addView(button("Enable app restrictions", "enable_access",
          () -> openSetting(!Store.get(this).usageAllowed()
              ? Settings.ACTION_USAGE_ACCESS_SETTINGS : Settings.ACTION_ACCESSIBILITY_SETTINGS)));
      Ui.gap(list, 12);
    }
    boolean available = RestrictionService.connected && Store.get(this).usageAllowed();
    for (AppCatalog.App app : AppCatalog.apps(this)) {
      Rules rule = Store.get(this).rule(app.pkg);
      String status =
          !available
              ? "Unavailable"
              : Store.get(this).pauseDeadline(app.pkg) > 0
                  ? "Allowed · " + Ui.countdown(Store.get(this).pauseDeadline(app.pkg))
                  : rule.blocked
                      ? "Blocked · Locked on TV"
                      : rule.dailyLimitMs > 0
                          ? Ui.time(rule.dailyLimitMs) + " / day"
                          : rule.scheduled ? "Scheduled" : "Not blocked";
      list.addView(appRow(app, status, () -> appDetails(app.pkg)));
    }
    scroll.addView(list);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
  }

  private void appDetails(String pkg) {
    appDetails(pkg, Store.get(this).usedToday(pkg), "Today");
  }

  void appDetails(String pkg, long used, String period) {
    if (!AppCatalog.allowed(this, pkg)) return;
    Store store = Store.get(this);
    Rules rule = store.rule(pkg);
    LinearLayout body = Ui.column(this);
    String label = AppCatalog.label(this, pkg);
    TextView summary = Ui.text(this, Ui.time(used) + "  ·  " + period, 17, Ui.MUTED);
    body.addView(summary);
    Ui.gap(body, 14);
    Button blocked = Ui.button(this, rule.blocked ? "Blocked · Unblock on phone" : "Block " + label, () -> {});
    if (!store.usageAllowed() || !RestrictionService.connected) {
      blocked.setText("Enable app restrictions");
      blocked.setOnClickListener(v -> openSetting(!store.usageAllowed()
          ? Settings.ACTION_USAGE_ACCESS_SETTINGS : Settings.ACTION_ACCESSIBILITY_SETTINGS));
    } else {
      blocked.setOnClickListener(v -> {
        Rules fresh = store.rule(pkg);
        if (fresh.blocked) {
          Toast.makeText(this, "Unblock apps from Focus on your phone.", Toast.LENGTH_SHORT).show();
          return;
        }
        Rules next = copyRule(fresh);
        next.blocked = true;
        if (!saveTightenOnly(pkg, fresh, next)) return;
        blocked.setText("Blocked · Unblock on phone");
        summary.setText(Ui.time(used) + "  ·  " + period + "  ·  Block on");
      });
    }
    body.addView(blocked);
    Ui.gap(body, 7);
    if (!rule.blocked) {
      Button limit =
          Ui.button(
              this,
              "Daily limit · " + (rule.dailyLimitMs == 0 ? "None" : Ui.time(rule.dailyLimitMs)),
              () -> {});
      limit.setOnClickListener(
          v ->
              limits(
                  pkg,
                  () -> {
                    long n = store.rule(pkg).dailyLimitMs;
                    limit.setText("Daily limit · " + (n == 0 ? "None" : Ui.time(n)));
                  }));
      body.addView(limit);
      Ui.gap(body, 7);
      Button scheduled =
          Ui.button(
              this,
              "Schedule · "
                  + (rule.scheduled ? clock(rule.fromMinute) + " – " + clock(rule.toMinute) : "Off"),
              () -> {});
      scheduled.setOnClickListener(
          v ->
              schedule(
                  pkg,
                  () -> {
                    Rules fresh = store.rule(pkg);
                    scheduled.setText(
                        "Schedule · "
                            + (fresh.scheduled
                                ? clock(fresh.fromMinute) + " – " + clock(fresh.toMinute)
                                : "Off"));
                  }));
      body.addView(scheduled);
      Ui.gap(body, 10);
    }
    long deadline = store.pauseDeadline(pkg);
    if (deadline > 0) {
      body.addView(
          Ui.button(
              this,
              "End pause · " + Ui.countdown(deadline),
              () -> {
                store.endPause(pkg, "manual");
                Toast.makeText(this, "Pause ended", Toast.LENGTH_SHORT).show();
              }));
      Ui.gap(body, 7);
    }
    body.addView(Ui.text(this, "To unblock or start Free Time, use Focus on your phone.", 14, Ui.MUTED));
    Dialog dialog = Ui.dialog(this, label, body);
    dialog.setOnDismissListener(d -> render());
    dialog.show();
  }

  private static Rules copyRule(Rules src) {
    Rules r = new Rules();
    r.blocked = src.blocked;
    r.dailyLimitMs = src.dailyLimitMs;
    r.scheduled = src.scheduled;
    r.fromMinute = src.fromMinute;
    r.toMinute = src.toMinute;
    return r;
  }

  private boolean saveTightenOnly(String pkg, Rules current, Rules next) {
    if (Rules.isLoosening(current, next)) {
      Toast.makeText(this, "Loosening rules requires Focus on your phone.", Toast.LENGTH_SHORT).show();
      return false;
    }
    return save(pkg, next);
  }

  private boolean save(String pkg, Rules r) {
    try {
      Store.get(this).saveRule(pkg, r);
      return true;
    } catch (RuntimeException e) {
      Toast.makeText(this, "Could not save rule", Toast.LENGTH_LONG).show();
      Store.get(this).diagnostic("rule_save_failed_" + e.getClass().getSimpleName());
      return false;
    }
  }

  private void limits(String pkg, Runnable changed) {
    Rules current = Store.get(this).rule(pkg);
    LinearLayout body = Ui.column(this);
    final Dialog[] dialog = new Dialog[1];
    boolean any = false;
    for (int min : new int[] {15, 30, 60, 120}) {
      long candidateMs = min * 60000L;
      if (current.dailyLimitMs > 0 && candidateMs >= current.dailyLimitMs) continue;
      any = true;
      body.addView(
          Ui.button(
              this,
              Ui.time(candidateMs),
              () -> {
                Rules fresh = Store.get(this).rule(pkg);
                Rules next = copyRule(fresh);
                next.dailyLimitMs = candidateMs;
                if (saveTightenOnly(pkg, fresh, next)) dialog[0].dismiss();
              }));
      Ui.gap(body, 5);
    }
    if (!any) {
      body.addView(Ui.text(this, "Daily limit is already at its strictest TV preset. Change it on your phone.", 15, Ui.MUTED));
    }
    dialog[0] = Ui.dialog(this, "Daily limit", body);
    dialog[0].setOnDismissListener(x -> changed.run());
    dialog[0].show();
  }

  private String clock(int minutes) {
    return String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60);
  }

  private void schedule(String pkg, Runnable changed) {
    Rules r = Store.get(this).rule(pkg);
    LinearLayout body = Ui.column(this);
    final Dialog[] dialog = new Dialog[1];
    if (r.scheduled) {
      body.addView(Ui.text(this, "Schedule · " + clock(r.fromMinute) + " – " + clock(r.toMinute), 16, Ui.FG));
      Ui.gap(body, 8);
      body.addView(Ui.text(this, "Schedules stay locked on TV once enabled. Change them from Focus on your phone.", 14, Ui.MUTED));
    } else {
      final int[] draft = {r.fromMinute, r.toMinute};
      Button enable = Ui.button(this, "Enable schedule · " + clock(draft[0]) + " – " + clock(draft[1]), () -> {});
      enable.setOnClickListener(
          v -> {
            Rules fresh = Store.get(this).rule(pkg);
            if (fresh.scheduled) {
              Toast.makeText(this, "Schedule is already locked on TV.", Toast.LENGTH_SHORT).show();
              if (dialog[0] != null) dialog[0].dismiss();
              return;
            }
            Rules next = copyRule(fresh);
            next.scheduled = true;
            next.fromMinute = draft[0];
            next.toMinute = draft[1];
            if (saveTightenOnly(pkg, fresh, next) && dialog[0] != null) {
              dialog[0].dismiss();
            }
          });
      body.addView(enable);
      Ui.gap(body, 8);
      for (boolean start : new boolean[] {true, false}) {
        Button time =
            Ui.button(
                this,
                (start ? "From · " : "Until · ") + clock(start ? draft[0] : draft[1]),
                () -> {});
        time.setOnClickListener(
            v -> {
              int n = start ? draft[0] : draft[1];
              new TimePickerDialog(
                      this,
                      (picker, h, m) -> {
                        if (start) draft[0] = h * 60 + m;
                        else draft[1] = h * 60 + m;
                        time.setText((start ? "From · " : "Until · ") + clock(h * 60 + m));
                        enable.setText("Enable schedule · " + clock(draft[0]) + " – " + clock(draft[1]));
                      },
                      n / 60,
                      n % 60,
                      true)
                  .show();
            });
        body.addView(time);
        Ui.gap(body, 8);
      }
      body.addView(Ui.text(this, "Every day", 14, Ui.MUTED));
    }
    dialog[0] = Ui.dialog(this, "Schedule", body);
    dialog[0].setOnDismissListener(x -> changed.run());
    dialog[0].show();
  }

  private void openSetting(String action) {
    try {
      startActivity(new Intent(action));
    } catch (RuntimeException e) {
      Toast.makeText(this, "Open this permission in TV Settings", Toast.LENGTH_LONG).show();
      Store.get(this).diagnostic("settings_unavailable_" + e.getClass().getSimpleName());
    }
  }

  private void exportHistory() {
    Store store = Store.get(this);
    try {
      File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
      if (dir == null) throw new IOException("Storage unavailable");
      if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create directory");
      File file = new File(dir, "focus-tv-history.json");
      try (Writer out =
          new OutputStreamWriter(
              new FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
        out.write(store.exportHistory().toString());
      }
      Toast.makeText(this, "Export saved", Toast.LENGTH_SHORT).show();
      store.diagnostic("history_exported");
    } catch (Exception e) {
      Toast.makeText(this, "Export failed", Toast.LENGTH_LONG).show();
      store.diagnostic("export_failed_" + e.getClass().getSimpleName());
    }
  }

  public void dns() {
    DnsController dns = DnsController.get(this);
    LinearLayout body = Ui.column(this);
    TextView status = Ui.text(this, dns.status(), 18, Ui.FG);
    body.addView(status);
    Ui.gap(body, 12);
    boolean locked = dns.enabled() && !dns.profile().isEmpty();
    Dialog dialog = Ui.dialog(this, "NextDNS", body);
    if (locked) {
      body.addView(Ui.text(this, "Profile · " + dns.profile(), 16, Ui.MUTED));
      Ui.gap(body, 10);
    }
    if (!dns.permitted()) {
      body.addView(Ui.text(this, "Computer setup required", 16, Ui.MUTED));
    } else if (!locked) {
      EditText profile = new EditText(this);
      profile.setSingleLine(true);
      profile.setText(dns.profile());
      profile.setHint("Profile ID");
      profile.setContentDescription("NextDNS profile ID");
      profile.setTextColor(Ui.FG);
      profile.setTextSize(18);
      profile.setFilters(
          new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(6)});
      body.addView(profile);
      Ui.gap(body, 10);
      body.addView(
          Ui.button(
              this,
              dns.enabled() ? "Save profile" : "Connect",
              () -> {
                try {
                  dns.connectFromTv(profile.getText().toString().trim());
                  dns.checkAsync(() -> status.setText(dns.status()));
                  dialog.dismiss();
                  render();
                } catch (RuntimeException e) {
                  profile.setError(e.getMessage());
                }
              }));
    } else {
      if (dns.deadline() > 0) {
        body.addView(
            Ui.button(
                this,
                "Resume filtering",
                () -> {
                  dialog.dismiss();
                  try {
                    dns.resume();
                  } catch (RuntimeException e) {
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                  }
                  dns();
                }));
        Ui.gap(body, 7);
      }
      body.addView(
          Ui.button(
              this,
              "Check connection",
              () -> {
                dns.checkAsync(() -> status.setText(dns.status()));
                status.setText(dns.status());
              }));
      Ui.gap(body, 8);
      body.addView(Ui.text(this, "Start Free Time from Focus on your phone.", 14, Ui.MUTED));
    }
    Runnable update =
        new Runnable() {
          public void run() {
            if (!dialog.isShowing()) return;
            status.setText(dns.status());
            handler.postDelayed(this, 1000);
          }
        };
    dialog.setOnDismissListener(
        d -> {
          handler.removeCallbacks(update);
          render();
        });
    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    dialog.getWindow().setDimAmount(1f);
    // TCL API 30 can lose cached glyphs after repeated dialog reopens.
    dialog.getWindow().getDecorView().setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    dialog.show();
    handler.postDelayed(update, 1000);
  }

  private void pauseHistory() {
    LinearLayout body = Ui.column(this);
    try (Cursor r =
        Store.get(this)
            .getReadableDatabase()
            .rawQuery(
                "SELECT pkg,reason,started,end_kind FROM pauses ORDER BY started DESC LIMIT 12",
                null)) {
      if (r.getCount() == 0) body.addView(Ui.text(this, "No pauses yet", 16, Ui.MUTED));
      while (r.moveToNext()) {
        body.addView(
            Ui.text(
                this,
                (DnsController.KEY.equals(r.getString(0))
                        ? "NextDNS"
                        : AppCatalog.label(this, r.getString(0)))
                    + " · "
                    + new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                        .format(new Date(r.getLong(2))),
                15,
                Ui.MUTED));
        body.addView(Ui.text(this, r.getString(1), 17, Ui.FG));
        Ui.gap(body, 12);
      }
    }
    ScrollView scroll = new ScrollView(this);
    scroll.addView(body);
    LinearLayout holder = Ui.column(this);
    holder.addView(scroll, new LinearLayout.LayoutParams(-1, Ui.dp(this, 230)));
    Ui.dialog(this, "Pauses", holder).show();
  }

  private void diagnostics() {
    LinearLayout body = Ui.column(this);
    body.addView(
        Ui.text(
            this,
            "Tracking · "
                + (SystemClock.elapsedRealtime() - Store.heartbeat < 15000
                    ? "Running"
                    : "Unavailable"),
            16,
            Ui.FG));
    body.addView(
        Ui.text(
            this,
            "App restrictions · " + (RestrictionService.connected ? "Running" : "Off"),
            16,
            Ui.FG));
    Ui.gap(body, 14);
    try (Cursor r =
        Store.get(this)
            .getReadableDatabase()
            .rawQuery("SELECT kind FROM diagnostics ORDER BY at DESC LIMIT 6", null)) {
      while (r.moveToNext()) body.addView(Ui.text(this, r.getString(0), 12, Ui.MUTED));
    }
    Ui.dialog(this, "Diagnostics", body).show();
  }
}
