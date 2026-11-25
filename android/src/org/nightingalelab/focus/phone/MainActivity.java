package org.nightingalelab.focus.phone;

import android.app.Activity;
import android.app.Dialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;
import org.nightingalelab.focus.habits.*;

/**
 * Main Focus phone UI implementing the approved v8 pure OLED design:
 * - Today / Week tabs: no date, device filter pill, one-sentence headline, thin category bar
 *   with unclipped legend, clean Screen time card (icon + name + optional device/globe + time),
 *   and Pauses card (DNS-off ring + 3 biggest pauses with running/fresh pinned first).
 * - See all ("Screen time"): full searchable list with filter chips and floating bottom search pill.
 * - Settings: compact Linear-density OLED cards (Protection, Devices, Activity, App).
 * - Floating bottom dock: [ Today | Week ] + text-only Pause pill (or amber "Turn on <device> · m:ss").
 */
public final class MainActivity extends Activity {
  private enum Page { TODAY, WEEK, ALL_APPS, SETTINGS }

  private Page page = Page.TODAY;
  private Page statsPageBeforeSub = Page.TODAY;
  private String deviceScope; // null = All devices, else "Phone", "Laptop", "TV"
  private String selectedCategoryId;
  private int selectedWeekDay = -1; // -1 = full week, 0..6 = narrowed day
  private boolean showMoreCategories;
  private boolean showAllPauses;
  private String expandedAppName;
  private String expandedPauseKey;
  private String allAppsFilter = "all"; // all | create | drift | off
  private String searchQuery = "";
  private int savedStatsScrollY;
  /** Test/screenshot hook: open the pause whose reason contains this text on next render. */
  private String openPauseKeyOnRender;

  private FrameLayout root;
  private HabitSection habits;
  private final int habitOverviewId = View.generateViewId();
  private boolean scrollToHabits;
  private long renderSequence;
  private final Runnable habitsChanged = () -> runOnUiThread(() -> {
    if (!HabitViews.Hold.active()) render();
    WidgetUpdater.updateAll(this);
  });
  private ScrollView scrollView;
  private LinearLayout scrollContent;
  private View edgeScrim;
  private LinearLayout bottomDock;
  private LinearLayout navCapsule;
  private Button pauseButton;
  private LinearLayout scopedDock;
  private LinearLayout scopedLogos;
  private TextView scopedClock;
  private Button scopedEnd;
  private List<String> scopedServices = Collections.emptyList();
  private TextView todayTabBtn;
  private TextView weekTabBtn;
  private LinearLayout searchBar;
  private EditText searchInput;
  private TextView searchClearBtn;
  private boolean suppressSearchEvents;

  private final Map<String, Drawable> iconCache = new HashMap<>();
  private FocusStats.View lastView;
  private List<InsightsEngine.PauseRecord> lastAllPauses = Collections.emptyList();
  private long lastNow;

  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private Dialog ratingDialog;
  private long ratingStart;
  private long ratingEnd;
  private final Runnable liveTick = new Runnable() {
    @Override
    public void run() {
      long now = System.currentTimeMillis();
      if ((page == Page.TODAY || page == Page.WEEK) && now / 60_000L != lastNow / 60_000L) {
        render();
      } else if (page == Page.TODAY || page == Page.WEEK) {
        updatePauseDock();
      }
      uiHandler.postDelayed(this, 1000);
    }
  };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Theme.applySystemBars(this);
    MonitorService.ensure(this);

    root = new FrameLayout(this);
    habits = new HabitSection(this, root, this::render);
    root.setBackgroundColor(Theme.BG);
    root.setOnApplyWindowInsetsListener((v, insets) -> {
      if (Build.VERSION.SDK_INT >= 30) {
        Insets sys = insets.getInsets(WindowInsets.Type.systemBars());
        v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
      } else {
        v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
      }
      return insets;
    });

    scrollView = new ScrollView(this);
    scrollView.setVerticalScrollBarEnabled(false);
    scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    scrollContent = Theme.column(this);
    scrollView.addView(scrollContent, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
    root.addView(scrollView, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

    edgeScrim = new View(this);
    edgeScrim.setBackground(Theme.bottomScrim());
    root.addView(edgeScrim, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, Theme.dp(this, 96), Gravity.BOTTOM));

    // Floating dock: [ Today | Week ]  [ Pause ]
    bottomDock = Theme.row(this);
    bottomDock.setGravity(Gravity.CENTER_VERTICAL);

    navCapsule = Theme.row(this);
    navCapsule.setBackground(Theme.obsidianGlass(this, 999));
    navCapsule.setElevation(Theme.dp(this, 10));
    int navPad = Theme.dp(this, 4);
    navCapsule.setPadding(navPad, navPad, navPad, navPad);
    todayTabBtn = makeNavTab("Today", Page.TODAY);
    weekTabBtn = makeNavTab("Week", Page.WEEK);
    navCapsule.addView(todayTabBtn, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
    navCapsule.addView(weekTabBtn, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
    bottomDock.addView(navCapsule, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, Theme.dp(this, 46)));

    Theme.gap(bottomDock, 8);

    pauseButton = new Button(this);
    pauseButton.setAllCaps(false);
    pauseButton.setTextSize(13);
    pauseButton.setBackground(Theme.obsidianGlass(this, 999));
    pauseButton.setPadding(Theme.dp(this, 18), 0, Theme.dp(this, 18), 0);
    pauseButton.setElevation(Theme.dp(this, 10));
    bottomDock.addView(pauseButton, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, Theme.dp(this, 46)));

    scopedDock = Theme.row(this);
    scopedDock.setGravity(Gravity.CENTER_VERTICAL);
    JudgeSheetDialog.styleCapsule(this, scopedDock);
    scopedLogos = Theme.row(this);
    scopedDock.addView(scopedLogos);
    scopedClock = Theme.text(this, "", 14, Theme.FG, true);
    scopedClock.setPadding(Theme.dp(this, 6), 0, Theme.dp(this, 8), 0);
    scopedDock.addView(scopedClock, new LinearLayout.LayoutParams(0, -2, 1));
    scopedEnd = new Button(this);
    scopedEnd.setAllCaps(false);
    scopedEnd.setText("End");
    scopedEnd.setTextSize(13);
    scopedEnd.setTextColor(Theme.FG);
    scopedEnd.setBackground(Theme.roundRect(this, Theme.DARK_ACTIVE, Theme.LINE, 999));
    scopedDock.addView(scopedEnd, new LinearLayout.LayoutParams(Theme.dp(this, 62), Theme.dp(this, 38)));
    bottomDock.addView(scopedDock, new LinearLayout.LayoutParams(
        Math.min(Theme.dp(this, 360), getResources().getDisplayMetrics().widthPixels - Theme.dp(this, 36)),
        Theme.dp(this, 46)));
    scopedDock.setVisibility(View.GONE);

    FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    dockLp.bottomMargin = Theme.dp(this, 16);
    root.addView(bottomDock, dockLp);

    // Floating bottom Search pill for See all ("Screen time")
    searchBar = Theme.row(this);
    searchBar.setBackground(Theme.obsidianGlass(this, 999));
    searchBar.setElevation(Theme.dp(this, 10));
    searchBar.setPadding(Theme.dp(this, 14), 0, Theme.dp(this, 12), 0);
    searchBar.addView(new Theme.IconView(this, Theme.IconView.Kind.SEARCH, Theme.MUTED, 15));
    Theme.gap(searchBar, 9);

    searchInput = Theme.appSearchInput(this);
    searchInput.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
      @Override public void afterTextChanged(Editable s) {
        if (suppressSearchEvents) return;
        searchQuery = s == null ? "" : s.toString();
        searchClearBtn.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE);
        if (page == Page.ALL_APPS) renderAllAppsListOnly();
      }
    });
    searchBar.addView(searchInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

    searchClearBtn = Theme.text(this, "\u00d7", 14f, Theme.FG, true);
    searchClearBtn.setGravity(Gravity.CENTER);
    searchClearBtn.setBackground(Theme.roundRect(this, Theme.DARK_ACTIVE, 0, 999));
    searchClearBtn.setVisibility(View.GONE);
    searchClearBtn.setOnClickListener(v -> {
      searchQuery = "";
      suppressSearchEvents = true;
      searchInput.setText("");
      suppressSearchEvents = false;
      searchClearBtn.setVisibility(View.GONE);
      if (page == Page.ALL_APPS) renderAllAppsListOnly();
    });
    searchBar.addView(searchClearBtn, new LinearLayout.LayoutParams(Theme.dp(this, 22), Theme.dp(this, 22)));
    searchBar.setVisibility(View.GONE);

    FrameLayout.LayoutParams searchLp = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, Theme.dp(this, 46), Gravity.BOTTOM);
    searchLp.setMargins(Theme.dp(this, 18), 0, Theme.dp(this, 18), Theme.dp(this, 16));
    root.addView(searchBar, searchLp);

    setContentView(root);
    handleLaunchExtras(getIntent());
    if (savedInstanceState != null && ratingDialog == null) {
      openRating(savedInstanceState.getLong("rating_start"), savedInstanceState.getLong("rating_end"),
          savedInstanceState.getString("rating_note", ""));
    }
  }

  private void openHabitCard(String id) {
    HabitStore store = HabitStore.get(this);
    Habits.Status status = store.read().status(System.currentTimeMillis()).stream()
        .filter(row -> row.due && row.habit.id.equals(id)).findFirst().orElse(null);
    if (status == null) return;
    android.app.Dialog dialog = new android.app.Dialog(this);
    FrameLayout host = new FrameLayout(this);
    host.setLayoutParams(new ViewGroup.LayoutParams(-1, HabitViews.dp(this, 138)));
    LinearLayout card = HabitSection.card(this);
    card.addView(HabitViews.text(this, "Habits", 13, HabitViews.GREY));
    card.addView(new HabitViews.Hold(this, status, 14, () -> habits.complete(status, host, true, () -> {
      card.setVisibility(View.INVISIBLE);
      host.postDelayed(dialog::dismiss, 5100);
      render();
    })));
    host.addView(card, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    HabitViews.dismissOnSwipe(card, dialog::dismiss);
    dialog.setContentView(host);
    dialog.show();
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawableResource(android.R.color.transparent);
      window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - HabitViews.dp(this, 28),
          HabitViews.dp(this, 360)), HabitViews.dp(this, 138));
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleLaunchExtras(intent);
  }

  private void handleLaunchExtras(Intent intent) {
    if (intent == null) return;
    long ratingStart = intent.getLongExtra("rate_pause_started", 0);
    if (ratingStart > 0) {
      long ratingEnd = intent.getLongExtra("rate_pause_ended", 0);
      intent.removeExtra("rate_pause_started");
      intent.removeExtra("rate_pause_ended");
      openRating(ratingStart, ratingEnd, "");
    }
    String startPage = intent.getStringExtra("page");
    if ("habits".equals(startPage)) {
      page = Page.TODAY;
      scrollToHabits = true;
    }
    else if ("week".equals(startPage) || "patterns".equals(startPage)) page = Page.WEEK;
    else if ("settings".equals(startPage)) page = Page.SETTINGS;
    else if ("all".equals(startPage)) page = Page.ALL_APPS;
    else if ("today".equals(startPage)) page = Page.TODAY;
    if (startPage != null) {
      if (page == Page.TODAY) selectedWeekDay = -1;
      if (page == Page.TODAY || page == Page.WEEK) statsPageBeforeSub = page;
      if (scrollView != null) scrollView.scrollTo(0, 0);
    }
    String quickHabit = intent.getStringExtra("habit");
    if (quickHabit != null) uiHandler.post(() -> openHabitCard(quickHabit));
    if (intent.getBooleanExtra("open_pause_sheet", false)) {
      uiHandler.postDelayed(this::openPauseSheet, 250);
    }
    if (intent.getBooleanExtra("open_update_sheet", false)) {
      uiHandler.postDelayed(() -> SettingsDialogs.showUpdateReadySheet(this, this::render), 250);
    }
    if (intent.getStringExtra("open_pause") != null) {
      expandedPauseKey = null;
      openPauseKeyOnRender = intent.getStringExtra("open_pause");
    }
    if (intent.getStringExtra("open_app") != null) {
      expandedAppName = intent.getStringExtra("open_app");
    }
    if (intent.getBooleanExtra("open_updater", false)) {
      uiHandler.postDelayed(() -> SettingsDialogs.showUpdaterSheet(this, this::render), 250);
    }
  }

  private void openRating(long started, long ended, String note) {
    if (started <= 0) return;
    if (ratingDialog != null) ratingDialog.dismiss();
    ratingStart = started;
    ratingEnd = ended;
    ratingDialog = PauseSheetDialog.showRating(this, started, ended, note, this::render);
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    if (ratingDialog != null && ratingDialog.isShowing()) {
      EditText note = ratingDialog.findViewById(PauseSheetDialog.RATING_NOTE_ID);
      out.putLong("rating_start", ratingStart);
      out.putLong("rating_end", ratingEnd);
      out.putString("rating_note", note.getText().toString());
    }
  }

  @Override
  protected void onDestroy() {
    if (ratingDialog != null) ratingDialog.dismiss();
    super.onDestroy();
  }

  private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      render();
    }
  };

  @Override
  protected void onResume() {
    super.onResume();
    HabitStore.get(this).addListener(habitsChanged);
    IntentFilter filter = new IntentFilter();
    filter.addAction(DeviceHub.ACTION_DEVICES_UPDATED);
    filter.addAction(PhoneUpdater.ACTION_STATE);
    if (Build.VERSION.SDK_INT >= 33) {
      registerReceiver(syncReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    } else {
      registerReceiver(syncReceiver, filter);
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 42);
    }
    PhoneUpdater.get(this).checkIfDue();
    new Thread(() -> {
      Store.get(this).collect();
      DnsController.get(this).tick();
      DeviceHub.get(this).syncAll();
      runOnUiThread(this::render);
    }, "focus-ui-refresh").start();
    render();
    uiHandler.removeCallbacks(liveTick);
    uiHandler.postDelayed(liveTick, 1000);
  }

  @Override
  protected void onPause() {
    HabitStore.get(this).removeListener(habitsChanged);
    uiHandler.removeCallbacks(liveTick);
    try {
      unregisterReceiver(syncReceiver);
    } catch (IllegalArgumentException ignored) {}
    super.onPause();
  }

  @Override
  public void onBackPressed() {
    if (page == Page.ALL_APPS) {
      page = statsPageBeforeSub;
      render();
      scrollView.post(() -> scrollView.scrollTo(0, savedStatsScrollY));
      return;
    }
    if (page == Page.SETTINGS) {
      page = statsPageBeforeSub;
      scrollView.scrollTo(0, 0);
      render();
      return;
    }
    super.onBackPressed();
  }

  private TextView makeNavTab(String label, Page target) {
    TextView b = Theme.text(this, label, 12.5f, Theme.MUTED, true);
    b.setGravity(Gravity.CENTER);
    b.setPadding(Theme.dp(this, 16), 0, Theme.dp(this, 16), 0);
    b.setOnClickListener(v -> {
      if (page != target) {
        page = target;
        if (target == Page.TODAY) selectedWeekDay = -1;
        scrollView.scrollTo(0, 0);
        render();
      }
    });
    return b;
  }

  private static final class ActivePauseTarget {
    final String label;
    final long remainingMs;
    final Runnable resume;

    ActivePauseTarget(String label, long remainingMs, Runnable resume) {
      this.label = label;
      this.remainingMs = remainingMs;
      this.resume = resume;
    }
  }

  private ActivePauseTarget activePauseTarget() {
    DnsController dns = DnsController.get(this);
    if (dns.deadline() > 0) {
      return new ActivePauseTarget("Phone", dns.remainingMs(), () -> {
        try {
          dns.resume();
          render();
        } catch (RuntimeException ignored) {}
      });
    }
    long now = System.currentTimeMillis();
    for (DeviceHub.PairedDevice d : DeviceHub.get(this).list()) {
      if (d.online && !d.relayed && d.dnsPausedNow(now)) {
        String lbl = "tv".equals(d.kind) ? "TV" : "laptop".equals(d.kind) ? "Laptop" : d.name;
        return new ActivePauseTarget(lbl, Math.max(0, d.dnsPauseUntil() - now), () -> {
          new Thread(() -> {
            try {
              DeviceHub.get(this).sendCommand(d.id, new JSONObject().put("action", "dns_resume"));
              runOnUiThread(this::render);
            } catch (Exception ignored) {}
          }).start();
        });
      }
    }
    return null;
  }

  private boolean updateScopedDock() {
    DeviceHub hub = DeviceHub.get(this);
    ScopedAccessWindow access = hub.activeScopedAccess();
    if (access == null) return false;
    if (!access.services.equals(scopedServices)) {
      scopedServices = access.services;
      JudgeSheetDialog.setServiceLogos(this, scopedLogos, scopedServices);
    }
    scopedClock.setText(FocusStats.fmtClock(access.remaining()));
    scopedEnd.setOnClickListener(v -> {
      scopedEnd.setEnabled(false);
      new Thread(() -> {
        try {
          hub.endScopedAccess(access.laptopId, access.grantId, access.remoteDeadline);
        } finally {
          runOnUiThread(() -> { scopedEnd.setEnabled(true); updatePauseDock(); });
        }
      }, "focus-end-scope").start();
    });
    return true;
  }

  private void updatePauseDock() {
    ActivePauseTarget active = activePauseTarget();
    boolean scoped = active == null && updateScopedDock();
    for (int i = 0; i < bottomDock.getChildCount(); i++) {
      View child = bottomDock.getChildAt(i);
      child.setVisibility((child == scopedDock) == scoped ? View.VISIBLE : View.GONE);
    }
    if (scoped) return;
    if (active != null) {
      pauseButton.setText("Turn on " + active.label + " · " + FocusStats.fmtClock(active.remainingMs));
      pauseButton.setTextColor(Theme.WARN);
      pauseButton.setOnClickListener(v -> active.resume.run());
    } else {
      pauseButton.setText("Ask");
      pauseButton.setTextColor(Theme.FG);
      pauseButton.setOnClickListener(v -> openPauseSheet());
    }
  }

  private int countPausesToday() {
    long todayStart = InsightsEngine.dayStart(lastNow > 0 ? lastNow : System.currentTimeMillis(), TimeZone.getDefault());
    int n = 0;
    for (InsightsEngine.PauseRecord p : lastAllPauses) {
      if (p.started >= todayStart) n++;
    }
    return n;
  }

  private void recompute() {
    lastNow = System.currentTimeMillis();
    TimeZone tz = TimeZone.getDefault();
    long weekStart = FocusStats.localDayStart(lastNow, tz, -6);
    List<InsightsEngine.SpanRecord> spans = DeviceHub.get(this).combinedSpans(weekStart, lastNow);
    lastAllPauses = DeviceHub.get(this).combinedPauses(weekStart, lastNow);
    List<ActivityClassifier.UserRule> rules = Store.get(this).userRules();
    boolean isWeek = (page == Page.WEEK) || (page == Page.ALL_APPS && statsPageBeforeSub == Page.WEEK);
    int dayIdx = isWeek ? selectedWeekDay : -1;
    lastView = FocusStats.compute(spans, lastAllPauses, rules, lastNow, tz, isWeek, dayIdx, deviceScope);
  }

  private void render() {
    if (HabitViews.Hold.active()) return;
    long sequence = ++renderSequence;
    recompute();
    int prevY = scrollView.getScrollY();
    scrollContent.removeAllViews();

    boolean isStats = (page == Page.TODAY || page == Page.WEEK);
    boolean isAllApps = (page == Page.ALL_APPS);
    scrollContent.setPadding(
        Theme.dp(this, 18),
        Theme.dp(this, 10),
        Theme.dp(this, 18),
        Theme.dp(this, isStats ? 104 : isAllApps ? 96 : 28));

    bottomDock.setVisibility(isStats ? View.VISIBLE : View.GONE);
    searchBar.setVisibility(isAllApps ? View.VISIBLE : View.GONE);
    edgeScrim.setVisibility((isStats || isAllApps) ? View.VISIBLE : View.GONE);

    if (isStats) {
      updateNavStyles();
      updatePauseDock();
    }

    switch (page) {
      case TODAY:
        buildStatsPage(false);
        break;
      case WEEK:
        buildStatsPage(true);
        break;
      case ALL_APPS:
        buildAllAppsPage();
        break;
      case SETTINGS:
        buildSettingsPage();
        break;
    }

    View habitTarget = scrollToHabits ? scrollContent.findViewById(habitOverviewId) : null;
    if (habitTarget != null) {
      habitTarget.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
        @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
            int oldLeft, int oldTop, int oldRight, int oldBottom) {
          view.removeOnLayoutChangeListener(this);
          if (sequence == renderSequence && scrollToHabits && view.isAttachedToWindow()) {
            scrollToHabits = false;
            scrollView.scrollTo(0, top);
          }
        }
      });
    } else {
      scrollView.post(() -> { if (sequence == renderSequence && !scrollToHabits) scrollView.scrollTo(0, prevY); });
    }
  }

  private void renderAllAppsListOnly() {
    if (page != Page.ALL_APPS) return;
    scrollContent.removeAllViews();
    buildAllAppsPage();
  }

  private void updateNavStyles() {
    boolean onToday = page == Page.TODAY;
    todayTabBtn.setBackground(onToday ? Theme.roundRect(this, Theme.DARK_ACTIVE, 0, 999) : null);
    todayTabBtn.setTextColor(onToday ? Theme.FG : Theme.MUTED);
    weekTabBtn.setBackground(!onToday ? Theme.roundRect(this, Theme.DARK_ACTIVE, 0, 999) : null);
    weekTabBtn.setTextColor(!onToday ? Theme.FG : Theme.MUTED);
  }

  // =========================================================================
  // TODAY / WEEK STATS PAGE (v8 OLED minimalist design)
  // =========================================================================

  private void buildStatsPage(boolean isWeek) {
    FocusStats.View v = lastView;

    // Header: NO date. Left: All devices pill; Right: Update icon (with dot) + Gear
    LinearLayout top = Theme.row(this);
    top.addView(deviceFilterPill());
    View spacer = new View(this);
    top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

    PhoneUpdater.Snapshot upd = PhoneUpdater.get(this).snapshot();
    if (upd.available != null) {
      FrameLayout updHit = new FrameLayout(this);
      int sz = Theme.dp(this, 32);
      updHit.setContentDescription("Update available");
      Theme.IconView ic = new Theme.IconView(this, Theme.IconView.Kind.UPDATE_CIRCLE, Theme.SUB, 17);
      updHit.addView(ic, new FrameLayout.LayoutParams(Theme.dp(this, 17), Theme.dp(this, 17), Gravity.CENTER));
      View dot = new View(this);
      dot.setBackground(Theme.roundRect(this, Theme.FG, 0, 99));
      FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(Theme.dp(this, 5), Theme.dp(this, 5), Gravity.TOP | Gravity.END);
      dlp.topMargin = Theme.dp(this, 6);
      dlp.rightMargin = Theme.dp(this, 6);
      updHit.addView(dot, dlp);
      updHit.setOnClickListener(view -> SettingsDialogs.showUpdateReadySheet(this, this::render));
      LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(sz, sz);
      ulp.rightMargin = Theme.dp(this, 4);
      top.addView(updHit, ulp);
    }

    FrameLayout gearHit = new FrameLayout(this);
    int gsz = Theme.dp(this, 32);
    gearHit.setContentDescription("Settings");
    gearHit.addView(new Theme.IconView(this, Theme.IconView.Kind.GEAR, Theme.MUTED, 18),
        new FrameLayout.LayoutParams(Theme.dp(this, 18), Theme.dp(this, 18), Gravity.CENTER));
    gearHit.setOnClickListener(view -> {
      statsPageBeforeSub = page;
      page = Page.SETTINGS;
      scrollView.scrollTo(0, 0);
      render();
    });
    top.addView(gearHit, new LinearLayout.LayoutParams(gsz, gsz));
    scrollContent.addView(top);
    Theme.gap(scrollContent, 12);

    // Optional setup banner if phone permissions are incomplete
    Store store = Store.get(this);
    DnsController dns = DnsController.get(this);
    if (!store.usageAllowed() || !FocusAccessibilityService.isEnabled(this) || !dns.permitted()) {
      LinearLayout setup = Theme.row(this);
      setup.setPadding(Theme.dp(this, 13), Theme.dp(this, 10), Theme.dp(this, 13), Theme.dp(this, 10));
      setup.setBackground(Theme.roundRect(this, Theme.CARD, Theme.LINE, 14));
      LinearLayout col = Theme.column(this);
      col.addView(Theme.text(this, "Finish phone setup", 13, Theme.FG, true));
      List<String> missing = new ArrayList<>();
      if (!store.usageAllowed()) missing.add("Usage Access");
      if (!FocusAccessibilityService.isEnabled(this)) missing.add("Accessibility");
      if (!dns.permitted()) missing.add("Private DNS grant");
      col.addView(Theme.text(this, "Missing: " + String.join(", ", missing), 11.5f, Theme.MUTED, false));
      setup.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
      setup.addView(new Theme.IconView(this, Theme.IconView.Kind.CHEVRON, Theme.DIM, 13));
      setup.setOnClickListener(view -> SettingsDialogs.showPhoneSheet(this, this::render));
      scrollContent.addView(setup);
      Theme.gap(scrollContent, 12);
    }

    // Hero total + one-sentence breakdown (building in white, drifting in amber)
    TextView totalTv = Theme.text(this, FocusStats.fmt(v.totalMs), 31f, Theme.FG, true);
    totalTv.setLetterSpacing(-0.025f);
    scrollContent.addView(totalTv);

    Theme.gap(scrollContent, 3);
    scrollContent.addView(buildHeroSentence(v));
    if (deviceScope == null || "Phone".equals(deviceScope)) {
      int peeks = store.peekCount(v.from, v.to);
      if (peeks > 0) {
        String prefix = deviceScope == null ? "Phone · " : "";
        String count = peeks == 1 ? "1 peek" : peeks + " peeks";
        scrollContent.addView(Theme.text(this, prefix + count, 12f, Theme.MUTED, false));
      }
    }

    // Thin category bar + unclipped legend (prepends Ice Deep focus segment when present)
    List<FocusStats.Category> barCats = v.barCategories();
    Theme.gap(scrollContent, 11);
    Theme.CategoryBarView catBar = new Theme.CategoryBarView(this);
    catBar.setData(barCats, selectedCategoryId);
    scrollContent.addView(catBar, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(this, 9)));

    if (!barCats.isEmpty()) {
      Theme.gap(scrollContent, 8);
      scrollContent.addView(buildCategoryLegend(barCats));
    }

    // Option C: one quiet plain-text log line for today's unbroken focus runs (no badges).
    if (!v.focusRuns.isEmpty()) {
      Theme.gap(scrollContent, 8);
      scrollContent.addView(Theme.divider(this, 0, 0));
      Theme.gap(scrollContent, 7);
      scrollContent.addView(buildFocusLogLine(v.focusRuns));
    }

    // Week 7-day chart card
    if (isWeek) {
      Theme.gap(scrollContent, 16);
      scrollContent.addView(buildWeekSection(v));
    }

    // Screen time card (top 4 apps)
    Theme.gap(scrollContent, 16);
    List<FocusStats.AppStat> visibleApps = new ArrayList<>();
    for (FocusStats.AppStat a : v.apps) {
      if (selectedCategoryId == null
          || ("focus".equals(selectedCategoryId) && a.focusMs > 0)
          || (a.category != null && selectedCategoryId.equals(a.category.id))) {
        visibleApps.add(a);
      }
    }

    LinearLayout stHead = Theme.row(this);
    stHead.addView(Theme.text(this, "Screen time", 12f, Theme.MUTED, false));
    if (selectedCategoryId != null) {
      Theme.gap(stHead, 8);
      ActivityClassifier.Fine f = ActivityClassifier.fineById(selectedCategoryId);
      TextView chip = Theme.text(this, f.label + " \u00d7", 11f, Theme.FG, true);
      chip.setPadding(Theme.dp(this, 8), Theme.dp(this, 2), Theme.dp(this, 8), Theme.dp(this, 2));
      chip.setBackground(Theme.roundRect(this, Theme.DARK_ACTIVE, 0, 999));
      chip.setOnClickListener(view -> {
        selectedCategoryId = null;
        render();
      });
      stHead.addView(chip);
    }
    View stSpacer = new View(this);
    stHead.addView(stSpacer, new LinearLayout.LayoutParams(0, 1, 1f));
    if (!v.apps.isEmpty()) {
      TextView seeAll = Theme.text(this, "See all " + v.apps.size() + " \u203a", 12f, Theme.EXPLAIN, false);
      seeAll.setOnClickListener(view -> {
        savedStatsScrollY = scrollView.getScrollY();
        statsPageBeforeSub = page;
        page = Page.ALL_APPS;
        scrollView.scrollTo(0, 0);
        render();
      });
      stHead.addView(seeAll);
    }
    View habitOverview = habits.overview();
    habitOverview.setId(habitOverviewId);
    scrollContent.addView(habitOverview);
    Theme.gap(scrollContent, 16);
    scrollContent.addView(stHead);
    Theme.gap(scrollContent, 7);

    LinearLayout stCard = makeCard();
    if (visibleApps.isEmpty()) {
      TextView empty = Theme.text(this, "Nothing recorded yet.", 12.5f, Theme.DIM, false);
      empty.setPadding(Theme.dp(this, 14), Theme.dp(this, 14), Theme.dp(this, 14), Theme.dp(this, 14));
      stCard.addView(empty);
    } else {
      int limit = Math.min(4, visibleApps.size());
      for (int i = 0; i < limit; i++) {
        if (i > 0) stCard.addView(Theme.divider(this, 48, 12));
        stCard.addView(buildAppRow(visibleApps.get(i), v.multiDevice(), false));
      }
    }
    scrollContent.addView(stCard);

    // Pauses card (DNS-off ring + 3 biggest pauses, live/fresh pinned first)
    Theme.gap(scrollContent, 16);
    LinearLayout pzHead = Theme.row(this);
    pzHead.addView(Theme.text(this, "Pauses", 12f, Theme.MUTED, false),
        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    TextView editLim = Theme.text(this, "Edit limit", 12f, Theme.EXPLAIN, false);
    editLim.setOnClickListener(view -> SettingsDialogs.showDailyLimitSheet(this, this::render));
    pzHead.addView(editLim);
    scrollContent.addView(pzHead);
    Theme.gap(scrollContent, 7);

    PausePolicy.Config cfg = Store.get(this).policyConfig();
    LinearLayout pzCard = makeCard();

    // Ring header inside Pauses card
    LinearLayout ringRow = Theme.row(this);
    ringRow.setPadding(Theme.dp(this, 13), Theme.dp(this, 12), Theme.dp(this, 13), Theme.dp(this, 12));
    Theme.DnsRingView ring = new Theme.DnsRingView(this);
    Habits ledger = HabitStore.get(this).read();
    int day = Habits.day(lastNow);
    int extra = ledger.wallet.extraPauseMinutes(day);
    int allowance = cfg.dailyLimitMinutes + (v.singleDay && selectedWeekDay < 0 ? extra : 0);
    float ringFrac = FocusStats.ringFraction(v, allowance);
    long limitMs = allowance * FocusStats.MIN;
    long perDayOff = v.offAllMs / Math.max(1, v.nDays);
    boolean overLimit = limitMs > 0 && perDayOff >= limitMs;
    boolean warnLimit = !overLimit && ringFrac >= 0.75f;
    ring.setFraction(ringFrac, overLimit ? Theme.BAD : warnLimit ? Theme.WARN : 0xFF71717A);
    ringRow.addView(ring, new LinearLayout.LayoutParams(Theme.dp(this, 42), Theme.dp(this, 42)));
    Theme.gap(ringRow, 12);
    LinearLayout ringCol = Theme.column(this);
    ringCol.addView(Theme.text(this, FocusStats.fmt(v.offAllMs) + " DNS off", 13.5f,
        overLimit ? Theme.BAD_TEXT : Theme.FG, true));
    Theme.gap(ringCol, 2);
    ringCol.addView(Theme.text(this,
        FocusStats.ringSubtitle(v, allowance, deviceScope != null),
        11.5f, overLimit ? Theme.BAD_TEXT : warnLimit ? Theme.WARN : Theme.MUTED, false));
    ringRow.addView(ringCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    pzCard.addView(ringRow);
    if (allowance > cfg.dailyLimitMinutes) {
      TextView credit = Theme.text(this, cfg.dailyLimitMinutes + "m base + " + extra + (ledger.wallet.active(day) ? "m bought" : "m earned"), 12, Theme.MUTED, false);
      credit.setPadding(Theme.dp(this, 13), 0, 0, Theme.dp(this, 9));
      pzCard.addView(credit);
    }

    if (!v.ratingSummary.isEmpty()) addPauseSummary(pzCard, v.ratingSummary);
    if (!v.checkInsByHour.isEmpty()) {
      StringJoiner hours = new StringJoiner("\n");
      v.checkInsByHour.forEach((hour, counts) -> {
        StringJoiner feelings = new StringJoiner(", ");
        counts.forEach((feeling, count) -> feelings.add(feeling.label + " ×" + count));
        hours.add(String.format(Locale.ROOT, "%02d:00  %s", hour, feelings));
      });
      addPauseSummary(pzCard, "Check-ins by hour\n" + hours);
    }
    if (v.pauses.isEmpty()) {
      pzCard.addView(Theme.divider(this, 12, 12));
      TextView none = Theme.text(this, "No pauses " + (v.singleDay ? "today." : "this week."), 12.5f, Theme.MUTED, false);
      none.setPadding(Theme.dp(this, 13), Theme.dp(this, 11), Theme.dp(this, 13), Theme.dp(this, 11));
      pzCard.addView(none);
    } else {
      List<FocusStats.PauseStat> shown = showAllPauses ? v.pauses : v.topPauses(3);
      for (FocusStats.PauseStat ps : shown) {
        pzCard.addView(Theme.divider(this, 12, 12));
        pzCard.addView(buildPauseRow(ps, !v.singleDay));
      }
      if (v.pauses.size() > 3) {
        pzCard.addView(Theme.divider(this, 0, 0));
        String btnTxt = showAllPauses
            ? "Show biggest 3"
            : "See all " + v.pauses.size() + " pauses · newest first";
        TextView foot = Theme.text(this, btnTxt, 12f, Theme.EXPLAIN, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(Theme.dp(this, 12), Theme.dp(this, 11), Theme.dp(this, 12), Theme.dp(this, 11));
        foot.setOnClickListener(view -> {
          showAllPauses = !showAllPauses;
          render();
        });
        pzCard.addView(foot, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
      }
    }
    scrollContent.addView(pzCard);
  }

  private View deviceFilterPill() {
    String label = deviceScope == null ? "All devices" : deviceScope;
    LinearLayout pill = Theme.row(this);
    pill.setPadding(Theme.dp(this, 11), Theme.dp(this, 5), Theme.dp(this, 10), Theme.dp(this, 5));
    pill.setBackground(Theme.roundRect(this, Theme.CARD, Theme.LINE, 999));
    pill.addView(Theme.text(this, label, 12f, Theme.EXPLAIN, true));
    Theme.gap(pill, 4);
    pill.addView(new Theme.IconView(this, Theme.IconView.Kind.CARET_DOWN, Theme.MUTED, 11));
    pill.setOnClickListener(this::showDevicePicker);
    return pill;
  }

  /** Small dark dropdown: All devices, then each device (an offline TV still shows its data). */
  private void showDevicePicker(View anchor) {
    LinkedHashMap<String, String> options = new LinkedHashMap<>();
    options.put("", "All devices");
    options.put("Phone", "Phone");
    if (lastView != null) for (String d : lastView.devicesWithData) options.put(d, d);
    for (DeviceHub.PairedDevice d : DeviceHub.get(this).list()) {
      String lbl = FocusStats.deviceLabel(d.kind, d.name);
      options.put(lbl, d.online ? lbl : lbl + " \u00b7 offline");
    }

    LinearLayout menu = Theme.column(this);
    int pad = Theme.dp(this, 4);
    menu.setPadding(pad, pad, pad, pad);
    menu.setBackground(Theme.roundRect(this, 0xFF070708, 0x1FFFFFFF, 12));
    menu.setElevation(Theme.dp(this, 14));
    PopupWindow popup = new PopupWindow(menu, Theme.dp(this, 176),
        LinearLayout.LayoutParams.WRAP_CONTENT, true);
    popup.setElevation(Theme.dp(this, 14));
    popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    popup.setOutsideTouchable(true);
    for (Map.Entry<String, String> e : options.entrySet()) {
      String key = e.getKey().isEmpty() ? null : e.getKey();
      boolean on = Objects.equals(key, deviceScope);
      LinearLayout item = Theme.row(this);
      item.setPadding(Theme.dp(this, 10), Theme.dp(this, 8), Theme.dp(this, 10), Theme.dp(this, 8));
      item.addView(Theme.text(this, e.getValue(), 12.5f, Theme.ATTN, true),
          new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
      if (on) item.addView(Theme.text(this, "\u2713", 12.5f, Theme.ATTN, false));
      item.setBackground(Theme.roundRect(this, on ? 0x0FFFFFFF : Color.TRANSPARENT, 0, 8));
      item.setOnClickListener(v -> {
        popup.dismiss();
        deviceScope = key;
        expandedAppName = null;
        expandedPauseKey = null;
        render();
      });
      menu.addView(item, new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }
    // Right-align under the pill when it sits on the right (See all page).
    int[] loc = new int[2];
    anchor.getLocationOnScreen(loc);
    boolean rightSide = loc[0] > getResources().getDisplayMetrics().widthPixels / 2;
    int xOff = rightSide ? anchor.getWidth() - Theme.dp(this, 176) : 0;
    popup.showAsDropDown(anchor, xOff, Theme.dp(this, 6));
  }

  private TextView buildHeroSentence(FocusStats.View v) {
    SpannableStringBuilder sb = new SpannableStringBuilder();
    if (v.totalMs <= 0) {
      sb.append("on screens.");
      return Theme.text(this, sb, 13f, Theme.MUTED, false);
    }
    sb.append("on screens, ");
    int bStart = sb.length();
    sb.append(FocusStats.fmt(v.buildMs)).append(" building");
    sb.setSpan(new ForegroundColorSpan(Theme.FG), bStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    sb.setSpan(new StyleSpan(Typeface.BOLD), bStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    if (v.focusMs >= 60_000L) {
      sb.append(", ");
      int fStart = sb.length();
      sb.append(FocusStats.fmt(v.focusMs)).append(" deep focus");
      sb.setSpan(new ForegroundColorSpan(Theme.ICE), fStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      sb.setSpan(new StyleSpan(Typeface.BOLD), fStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      long coins = focusCoinsForView(v);
      if (coins > 0) {
        int cStart = sb.length();
        sb.append(" · +").append(Long.toString(coins)).append(coins == 1 ? " coin" : " coins");
        sb.setSpan(new ForegroundColorSpan(Theme.ICE_SOFT), cStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
    }
    if (v.driftMs >= 30_000L) {
      sb.append(", ");
      int dStart = sb.length();
      sb.append(FocusStats.fmt(v.driftMs)).append(" drifting");
      int days = Math.max(1, v.nDays);
      boolean heavy = (v.driftMs / days) >= 90L * FocusStats.MIN
          || (v.totalMs >= 30L * FocusStats.MIN && v.driftMs * 100 >= v.totalMs * 35);
      sb.setSpan(new ForegroundColorSpan(heavy ? Theme.BAD_TEXT : Theme.WARN), dStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    sb.append(".");
    return Theme.text(this, sb, 13f, Theme.MUTED, false);
  }

  private long focusCoinsForView(FocusStats.View v) {
    org.nightingalelab.focus.habits.Wallet wallet = HabitStore.get(this).read().wallet;
    int todayDay = Habits.day(lastNow);
    if (!wallet.active(todayDay)) return 0L;
    int fromDay = Habits.day(v.from);
    int toDay = v.singleDay ? fromDay : todayDay;
    return org.nightingalelab.focus.habits.FocusRewards.coinsInRange(wallet, fromDay, toDay);
  }

  private TextView buildFocusLogLine(List<FocusStats.FocusRun> runs) {
    SpannableStringBuilder sb = new SpannableStringBuilder();
    int lblStart = sb.length();
    sb.append("Long runs  ");
    sb.setSpan(new ForegroundColorSpan(Theme.ICE), lblStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    int shown = Math.min(5, runs.size());
    for (int i = 0; i < shown; i++) {
      if (i > 0) sb.append(" · ");
      FocusStats.FocusRun r = runs.get(i);
      int appStart = sb.length();
      sb.append(r.app).append(" ");
      sb.setSpan(new ForegroundColorSpan(Theme.SUB), appStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      int durStart = sb.length();
      sb.append(FocusStats.fmt(r.ms));
      sb.setSpan(new ForegroundColorSpan(Theme.ICE), durStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    if (runs.size() > shown) {
      sb.append("  +").append(String.valueOf(runs.size() - shown)).append(" more");
    }
    return Theme.text(this, sb, 11.5f, Theme.MUTED, false);
  }

  private View buildCategoryLegend(List<FocusStats.Category> cats) {
    LinearLayout wrap = Theme.column(this);
    int perRow = 3;
    int shown = showMoreCategories ? cats.size() : Math.min(perRow, cats.size());
    for (int start = 0; start < shown; start += perRow) {
      if (start > 0) Theme.gap(wrap, start == perRow ? 5 : 4);
      LinearLayout row = Theme.row(this);
      for (int i = start; i < Math.min(start + perRow, shown); i++) {
        if (i > start) Theme.gap(row, 10);
        row.addView(categoryLegendItem(cats.get(i)));
      }
      wrap.addView(row);
    }

    if (cats.size() > perRow) {
      Theme.gap(wrap, showMoreCategories ? 4 : 5);
      String label = showMoreCategories ? "Less" : "+" + (cats.size() - perRow) + " more";
      TextView toggle = Theme.text(this, label, 11.5f, Theme.MUTED, false);
      toggle.setOnClickListener(view -> {
        showMoreCategories = !showMoreCategories;
        render();
      });
      wrap.addView(toggle, new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }
    return wrap;
  }

  private View categoryLegendItem(FocusStats.Category c) {
    LinearLayout item = Theme.row(this);
    View swatch = new View(this);
    swatch.setBackground(Theme.roundRect(this, c.fine.color, 0, 2));
    item.addView(swatch, new LinearLayout.LayoutParams(Theme.dp(this, 7), Theme.dp(this, 7)));
    Theme.gap(item, 5);
    boolean active = c.fine.id.equals(selectedCategoryId);
    SpannableStringBuilder sb = new SpannableStringBuilder(c.fine.label + " ");
    int mStart = sb.length();
    sb.append(FocusStats.fmt(c.ms));
    sb.setSpan(new ForegroundColorSpan(Theme.MUTED), mStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    TextView tv = Theme.text(this, sb, 11.5f, active ? Theme.FG : Theme.EXPLAIN, active);
    if (selectedCategoryId != null && !active) item.setAlpha(0.45f);
    item.addView(tv);
    item.setOnClickListener(view -> {
      selectedCategoryId = active ? null : c.fine.id;
      render();
    });
    return item;
  }

  private View buildWeekSection(FocusStats.View v) {
    LinearLayout sec = Theme.column(this);
    LinearLayout head = Theme.row(this);
    head.addView(Theme.text(this, "Last 7 days", 12f, Theme.MUTED, false));
    boolean anyFocus = false;
    for (FocusStats.DayStat d : v.week) if (d.focusMs >= 60_000L) anyFocus = true;
    if (anyFocus) {
      Theme.gap(head, 10);
      View focusSw = new View(this);
      focusSw.setBackground(Theme.roundRect(this, Theme.ICE, 0, 2));
      head.addView(focusSw, new LinearLayout.LayoutParams(Theme.dp(this, 7), Theme.dp(this, 7)));
      Theme.gap(head, 5);
      head.addView(Theme.text(this, "Deep focus", 11.5f, Theme.MUTED, false));
    }
    Theme.gap(head, 10);
    View keySw = new View(this);
    keySw.setBackground(Theme.roundRect(this, Theme.WARN, 0, 2));
    head.addView(keySw, new LinearLayout.LayoutParams(Theme.dp(this, 7), Theme.dp(this, 7)));
    Theme.gap(head, 5);
    head.addView(Theme.text(this, "DNS off", 11.5f, Theme.MUTED, false));
    View sp = new View(this);
    head.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
    if (selectedWeekDay >= 0 && selectedWeekDay < v.week.size()) {
      TextView clr = Theme.text(this, v.week.get(selectedWeekDay).label + " · All 7 days \u00d7", 11f, Theme.FG, true);
      clr.setPadding(Theme.dp(this, 8), Theme.dp(this, 2), Theme.dp(this, 8), Theme.dp(this, 2));
      clr.setBackground(Theme.roundRect(this, Theme.DARK_ACTIVE, 0, 999));
      clr.setOnClickListener(view -> {
        selectedWeekDay = -1;
        render();
      });
      head.addView(clr);
    }
    sec.addView(head);
    Theme.gap(sec, 7);

    LinearLayout card = makeCard();
    card.setPadding(Theme.dp(this, 10), Theme.dp(this, 12), Theme.dp(this, 10), Theme.dp(this, 12));
    LinearLayout bars = Theme.row(this);
    bars.setGravity(Gravity.BOTTOM);

    long maxMs = 60L * FocusStats.MIN;
    for (FocusStats.DayStat d : v.week) maxMs = Math.max(maxMs, d.totalMs);
    int maxBarPx = Theme.dp(this, 60);

    for (int i = 0; i < v.week.size(); i++) {
      final int dayIdx = i;
      FocusStats.DayStat d = v.week.get(i);
      LinearLayout col = Theme.column(this);
      col.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
      col.setPadding(Theme.dp(this, 2), Theme.dp(this, 4), Theme.dp(this, 2), Theme.dp(this, 4));
      if (selectedWeekDay == dayIdx) {
        col.setBackground(Theme.roundRect(this, Theme.DARK_ACTIVE, 0, 10));
      }

      TextView topLbl = Theme.text(this, d.totalMs >= 30_000L ? FocusStats.fmtBar(d.totalMs) : "\u2014",
          10.5f, Theme.SUB, false);
      topLbl.setSingleLine(true);
      col.addView(topLbl);
      Theme.gap(col, 5);

      // Bar container of fixed height so baselines align cleanly
      LinearLayout stackWrap = Theme.column(this);
      stackWrap.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
      int totalH = d.totalMs >= 30_000L ? Math.max(Theme.dp(this, 4), (int) (maxBarPx * d.totalMs / (float) maxMs)) : 0;
      int offH = d.offMs >= 30_000L && d.totalMs > 0
          ? Math.max(Theme.dp(this, 3), Math.min(totalH, (int) (maxBarPx * Math.min(d.totalMs, d.offMs) / (float) maxMs)))
          : 0;
      int nonOffH = Math.max(0, totalH - offH);
      long cappedFocus = Math.min(Math.max(0L, d.totalMs - Math.min(d.totalMs, d.offMs)), d.focusMs);
      int focusH = cappedFocus >= 60_000L && nonOffH > 0
          ? Math.max(Theme.dp(this, 3), Math.min(nonOffH, (int) (maxBarPx * cappedFocus / (float) maxMs)))
          : 0;
      int onH = Math.max(0, nonOffH - focusH);

      LinearLayout barStack = Theme.column(this);
      int barW = Theme.dp(this, 22);
      PausePolicy.Config wkCfg = Store.get(this).policyConfig();
      int wkExtra = d.today ? HabitStore.get(this).read().wallet.extraPauseMinutes(Habits.day(lastNow)) : 0;
      long dayLimMs = (wkCfg.dailyLimitMinutes + wkExtra) * FocusStats.MIN;
      boolean dayOver = dayLimMs > 0 && d.offMs > dayLimMs;
      if (offH > 0) {
        View offSeg = new View(this);
        offSeg.setBackground(Theme.topRounded(this, dayOver ? Theme.BAD : Theme.WARN, 0, 3));
        barStack.addView(offSeg, new LinearLayout.LayoutParams(barW, offH));
      }
      if (onH > 0) {
        View onSeg = new View(this);
        onSeg.setBackground(offH > 0
            ? Theme.roundRect(this, Theme.BAR_GRAY, 0, 0)
            : Theme.topRounded(this, Theme.BAR_GRAY, 0, 3));
        barStack.addView(onSeg, new LinearLayout.LayoutParams(barW, onH));
      }
      if (focusH > 0) {
        View focusSeg = new View(this);
        focusSeg.setBackground((offH > 0 || onH > 0)
            ? Theme.roundRect(this, Theme.ICE, 0, 0)
            : Theme.topRounded(this, Theme.ICE, 0, 3));
        barStack.addView(focusSeg, new LinearLayout.LayoutParams(barW, focusH));
      }
      stackWrap.addView(barStack);
      col.addView(stackWrap, new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT, maxBarPx));

      Theme.gap(col, 5);
      col.addView(Theme.text(this, d.label, 10.5f, d.today ? Theme.FG : Theme.MUTED, d.today));

      col.setOnClickListener(view -> {
        selectedWeekDay = (selectedWeekDay == dayIdx) ? -1 : dayIdx;
        render();
      });
      bars.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    card.addView(bars, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    sec.addView(card);
    return sec;
  }

  private View buildAppRow(FocusStats.AppStat a, boolean multiDevice, boolean largeIcon) {
    LinearLayout wrap = Theme.column(this);
    LinearLayout row = Theme.row(this);
    row.setPadding(Theme.dp(this, 13), Theme.dp(this, 10), Theme.dp(this, 13), Theme.dp(this, 10));

    int icoDp = largeIcon ? 30 : 24;
    row.addView(buildAppIconView(a, icoDp));
    Theme.gap(row, 11);

    LinearLayout nameWrap = Theme.row(this);
    TextView nameTv = Theme.text(this, a.name, largeIcon ? 14f : 13.5f, Theme.FG, false);
    nameTv.setSingleLine(true);
    nameTv.setEllipsize(TextUtils.TruncateAt.END);
    nameWrap.addView(nameTv, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

    // Single-device badge icon or globe icon for websites
    if (a.web) {
      Theme.gap(nameWrap, 6);
      nameWrap.addView(new Theme.IconView(this, Theme.IconView.Kind.GLOBE, Theme.DIM, 11));
    } else if (multiDevice && a.devices.size() == 1) {
      String dev = a.devices.get(0);
      Theme.IconView.Kind kind = "TV".equals(dev)
          ? Theme.IconView.Kind.TV
          : "Laptop".equals(dev) ? Theme.IconView.Kind.LAPTOP : Theme.IconView.Kind.PHONE;
      Theme.gap(nameWrap, 6);
      nameWrap.addView(new Theme.IconView(this, kind, Theme.DIM, 11));
    }
    row.addView(nameWrap, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

    Theme.gap(row, 8);
    String catId = a.category != null ? a.category.id : "";
    boolean singleDayView = page == Page.TODAY || selectedWeekDay >= 0;
    boolean wasteWarn = singleDayView && (("social".equals(catId) || "tv".equals(catId) || "games".equals(catId) || "adult".equals(catId))
        ? a.ms >= 10L * FocusStats.MIN
        : "video".equals(catId) && a.ms >= 30L * FocusStats.MIN);
    boolean heavyWaste = singleDayView && ("adult".equals(catId)
        || (a.category != null && ActivityClassifier.FG_DRIFT.equals(a.category.group) && a.ms >= 60L * FocusStats.MIN));
    boolean hasFocus = a.focusMs > 0 && !heavyWaste && !a.mostlyDnsOff() && !wasteWarn;
    int timeColor = heavyWaste ? Theme.BAD_TEXT : (a.mostlyDnsOff() || wasteWarn) ? Theme.WARN : hasFocus ? Theme.ICE : Theme.FG;
    row.addView(Theme.text(this, FocusStats.fmt(a.ms), 13.5f, timeColor, true));

    row.setOnClickListener(view -> {
      expandedAppName = a.name.equals(expandedAppName) ? null : a.name;
      if (page == Page.ALL_APPS) renderAllAppsListOnly();
      else render();
    });
    wrap.addView(row);

    if (a.name.equals(expandedAppName)) {
      LinearLayout detail = Theme.column(this);
      detail.setPadding(Theme.dp(this, icoDp + 24), 0, Theme.dp(this, 13), Theme.dp(this, 10));
      SpannableStringBuilder sb = new SpannableStringBuilder();
      if (a.category != null) sb.append(a.category.label);
      if (!a.devices.isEmpty()) {
        if (sb.length() > 0) sb.append(" · ");
        sb.append(String.join(" + ", a.devices));
      }
      if (a.offMs >= 30_000L) {
        if (sb.length() > 0) sb.append(" · ");
        int offStart = sb.length();
        sb.append(FocusStats.fmt(a.offMs)).append(" with DNS off");
        sb.setSpan(new ForegroundColorSpan(Theme.WARN), offStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
      detail.addView(Theme.text(this, sb, 11.5f, Theme.MUTED, false));
      if (hasFocus && !a.focusRuns.isEmpty()) {
        java.text.SimpleDateFormat hhmm = new java.text.SimpleDateFormat("HH:mm", Locale.ROOT);
        int limitRuns = Math.min(5, a.focusRuns.size());
        for (int ri = 0; ri < limitRuns; ri++) {
          FocusStats.FocusRun fr = a.focusRuns.get(ri);
          Theme.gap(detail, 4);
          LinearLayout frRow = Theme.row(this);
          frRow.addView(Theme.text(this, FocusStats.fmt(fr.ms) + " unbroken" + (fr.active ? " · active" : ""), 11f, Theme.ICE, true),
              new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
          frRow.addView(Theme.text(this, hhmm.format(new Date(fr.start)) + "–" + hhmm.format(new Date(fr.end)), 11f, Theme.MUTED, false));
          detail.addView(frRow);
        }
      }
      wrap.addView(detail);
    }
    return wrap;
  }

  private View buildAppIconView(FocusStats.AppStat a, int sizeDp) {
    Drawable d = resolveAppIcon(a);
    int px = Theme.dp(this, sizeDp);
    if (d != null) {
      ImageView iv = new ImageView(this);
      iv.setImageDrawable(d);
      iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
      iv.setLayoutParams(new LinearLayout.LayoutParams(px, px));
      return iv;
    }
    FrameLayout box = new FrameLayout(this);
    box.setBackground(Theme.roundRect(this, Theme.SEC, Theme.LINE, 7));
    if (a.web) {
      box.addView(new Theme.IconView(this, Theme.IconView.Kind.GLOBE, Theme.MUTED, sizeDp * 0.55f),
          new FrameLayout.LayoutParams(Theme.dp(this, sizeDp * 0.55f), Theme.dp(this, sizeDp * 0.55f), Gravity.CENTER));
    } else {
      String letter = a.name.isEmpty() ? "?" : a.name.substring(0, 1).toUpperCase(Locale.ROOT);
      TextView tv = Theme.text(this, letter, sizeDp >= 28 ? 12.5f : 11f, Theme.EXPLAIN, true);
      tv.setGravity(Gravity.CENTER);
      box.addView(tv, new FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }
    box.setLayoutParams(new LinearLayout.LayoutParams(px, px));
    return box;
  }

  private Drawable resolveAppIcon(FocusStats.AppStat a) {
    String key = a.iconPackage != null ? a.iconPackage : a.name.toLowerCase(Locale.ROOT);
    if (iconCache.containsKey(key)) return iconCache.get(key);
    PackageManager pm = getPackageManager();
    Drawable found = null;
    if (a.iconPackage != null) {
      try { found = pm.getApplicationIcon(a.iconPackage); } catch (Exception ignored) {}
    }
    if (found == null) {
      for (String pkg : ActivityClassifier.packagesForApp(a.name)) {
        try { found = pm.getApplicationIcon(pkg); break; } catch (Exception ignored) {}
      }
    }
    iconCache.put(key, found);
    return found;
  }

  private void addPauseSummary(LinearLayout card, String summary) {
    card.addView(Theme.divider(this, 0, 0));
    TextView text = Theme.text(this, summary, 11.5f, Theme.MUTED, false);
    text.setPadding(Theme.dp(this, 13), Theme.dp(this, 8), Theme.dp(this, 13), Theme.dp(this, 8));
    card.addView(text);
  }

  private View buildPauseRow(FocusStats.PauseStat p, boolean withDay) {
    if (openPauseKeyOnRender != null && p.reason != null && p.reason.contains(openPauseKeyOnRender)) {
      expandedPauseKey = p.key();
      openPauseKeyOnRender = null;
    }
    boolean open = p.key().equals(expandedPauseKey);
    LinearLayout wrap = Theme.column(this);
    wrap.setPadding(Theme.dp(this, 13), Theme.dp(this, 10), Theme.dp(this, 13), Theme.dp(this, 10));

    LinearLayout top = Theme.row(this);
    top.setGravity(Gravity.TOP);

    String tmTxt = withDay ? p.dayTimeLabel : p.timeLabel;
    boolean lowRating = p.rating > 0 && p.rating <= 2;
    int tmColor = lowRating ? Theme.BAD_TEXT : p.drift ? Theme.WARN : Theme.MUTED;
    TextView tm = Theme.text(this, tmTxt, 11f, tmColor, false);
    tm.setPadding(0, Theme.dp(this, 2), 0, 0);
    top.addView(tm, new LinearLayout.LayoutParams(Theme.dp(this, withDay ? 56 : 40), LinearLayout.LayoutParams.WRAP_CONTENT));

    LinearLayout mid = Theme.column(this);
    TextView why = Theme.text(this, p.reason == null || p.reason.isEmpty() ? "(no reason)" : p.reason,
        12.5f, Theme.FG, false);
    if (!open) {
      why.setMaxLines(2);
      why.setEllipsize(TextUtils.TruncateAt.END);
    }
    mid.addView(why);

    // Trail: "→ apps · Device". Only drifting apps are amber; the device name is never cut off.
    {
      Theme.gap(mid, 3);
      SpannableStringBuilder didSb = new SpannableStringBuilder();
      if (p.live && p.did.isEmpty()) {
        didSb.append("Paused now");
      } else if (p.did.isEmpty()) {
        didSb.append("No recorded use");
      } else {
        didSb.append("\u2192 ");
        for (int i = 0; i < p.did.size(); i++) {
          if (i > 0) didSb.append(", ");
          FocusStats.DidApp da = p.did.get(i);
          int start = didSb.length();
          didSb.append(FocusStats.didText(da));
          if (da.drifting) {
            didSb.setSpan(new ForegroundColorSpan(Theme.WARN), start, didSb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
          }
        }
      }
      String devTxt = " \u00b7 " + p.deviceLabel;
      if (open) {
        // Opened: everything wraps, nothing is cut.
        didSb.append(devTxt);
        mid.addView(Theme.text(this, didSb, 11f, Theme.MUTED, false));
      } else {
        // Closed: the wrap-content row shrinks only the apps text (weight), so the device stays visible
        // right after the apps instead of being pushed to the far edge.
        LinearLayout trailRow = Theme.row(this);
        TextView didTv = Theme.text(this, didSb, 11f, Theme.MUTED, false);
        didTv.setSingleLine(true);
        didTv.setEllipsize(TextUtils.TruncateAt.END);
        trailRow.addView(didTv, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView devTv = Theme.text(this, devTxt, 11f, Theme.MUTED, false);
        devTv.setSingleLine(true);
        trailRow.addView(devTv, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0f));
        mid.addView(trailRow, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
      }
    }

    StringJoiner feedback = new StringJoiner(" · ");
    if (p.checkIn != null) feedback.add(p.checkIn.label);
    if (p.rating > 0) feedback.add(p.rating + "/5");
    if (!p.ratingNote.isEmpty()) feedback.add(p.ratingNote);
    if (feedback.length() > 0) {
      Theme.gap(mid, 3);
      TextView detail = Theme.text(this, feedback.toString(), 11f, Theme.MUTED, false);
      if (!open) {
        detail.setMaxLines(1);
        detail.setEllipsize(TextUtils.TruncateAt.END);
      }
      mid.addView(detail);
    }
    if (open) {
      Theme.gap(mid, 4);
      mid.addView(Theme.text(this, FocusStats.pauseDetail(p), 11f, Theme.MUTED, false));
    }

    top.addView(mid, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    Theme.gap(top, 8);

    LinearLayout right = Theme.row(this);
    right.addView(Theme.text(this, FocusStats.fmt(p.offMs), 11.5f, Theme.EXPLAIN, false));
    Theme.gap(right, 3);
    right.addView(new Theme.IconView(this,
        open ? Theme.IconView.Kind.CARET_DOWN : Theme.IconView.Kind.CARET_RIGHT, Theme.DIM, 10));
    top.addView(right);

    wrap.addView(top);
    wrap.setOnClickListener(view -> {
      expandedPauseKey = open ? null : p.key();
      render();
    });
    return wrap;
  }

  // =========================================================================
  // SEE ALL ("Screen time") + FLOATING SEARCH PILL
  // =========================================================================

  private void buildAllAppsPage() {
    FocusStats.View v = lastView;

    LinearLayout top = Theme.row(this);
    FrameLayout backHit = new FrameLayout(this);
    backHit.setPadding(0, Theme.dp(this, 6), Theme.dp(this, 10), Theme.dp(this, 6));
    backHit.setContentDescription("Back");
    backHit.addView(new Theme.IconView(this, Theme.IconView.Kind.BACK, Theme.FG, 19));
    backHit.setOnClickListener(view -> onBackPressed());
    top.addView(backHit);

    LinearLayout titleCol = Theme.row(this);
    titleCol.addView(Theme.text(this, "Screen time", 18f, Theme.FG, true));
    if (statsPageBeforeSub == Page.WEEK) {
      Theme.gap(titleCol, 8);
      titleCol.addView(Theme.text(this, "This week", 12f, Theme.MUTED, false));
    }
    top.addView(titleCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    top.addView(deviceFilterPill());
    scrollContent.addView(top);
    Theme.gap(scrollContent, 10);

    // Filter chips
    HorizontalScrollView hs = new HorizontalScrollView(this);
    hs.setHorizontalScrollBarEnabled(false);
    LinearLayout chips = Theme.row(this);
    String[][] filters = {
        {"all", "All (" + v.apps.size() + ")"},
        {"create", "Building"},
        {"drift", "Drifting"},
        {"off", "DNS off"},
    };
    for (int i = 0; i < filters.length; i++) {
      final String key = filters[i][0];
      boolean on = key.equals(allAppsFilter) && selectedCategoryId == null;
      TextView c = Theme.text(this, filters[i][1], 11.5f, on ? Theme.FG : Theme.MUTED, on);
      c.setPadding(Theme.dp(this, 11), Theme.dp(this, 5), Theme.dp(this, 11), Theme.dp(this, 5));
      c.setBackground(Theme.roundRect(this, on ? Theme.DARK_ACTIVE : Theme.CARD, on ? 0xFF3A3A40 : Theme.LINE, 999));
      c.setOnClickListener(view -> {
        allAppsFilter = key;
        if ("all".equals(key)) selectedCategoryId = null;
        renderAllAppsListOnly();
      });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      if (i > 0) lp.leftMargin = Theme.dp(this, 6);
      chips.addView(c, lp);
    }
    if (selectedCategoryId != null) {
      ActivityClassifier.Fine f = ActivityClassifier.fineById(selectedCategoryId);
      TextView catChip = Theme.text(this, f.label + " \u00d7", 11.5f, Theme.FG, true);
      catChip.setPadding(Theme.dp(this, 11), Theme.dp(this, 5), Theme.dp(this, 11), Theme.dp(this, 5));
      catChip.setBackground(Theme.roundRect(this, Theme.DARK_ACTIVE, 0xFF3A3A40, 999));
      catChip.setOnClickListener(view -> {
        selectedCategoryId = null;
        renderAllAppsListOnly();
      });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      lp.leftMargin = Theme.dp(this, 6);
      chips.addView(catChip, lp);
    }
    hs.addView(chips);
    scrollContent.addView(hs);
    Theme.gap(scrollContent, 10);

    String q = searchQuery.trim().toLowerCase(Locale.ROOT);
    List<FocusStats.AppStat> filtered = new ArrayList<>();
    for (FocusStats.AppStat a : v.apps) {
      if (selectedCategoryId != null && (a.category == null || !selectedCategoryId.equals(a.category.id))) continue;
      if ("create".equals(allAppsFilter) && (a.category == null || !ActivityClassifier.FG_CREATE.equals(a.category.group))) continue;
      if ("drift".equals(allAppsFilter) && !a.drifting()) continue;
      if ("off".equals(allAppsFilter) && a.offMs < 30_000L) continue;
      if (!q.isEmpty()) {
        boolean matchName = a.name.toLowerCase(Locale.ROOT).contains(q);
        boolean matchCat = a.category != null && a.category.label.toLowerCase(Locale.ROOT).contains(q);
        if (!matchName && !matchCat) continue;
      }
      filtered.add(a);
    }

    LinearLayout card = makeCard();
    if (filtered.isEmpty()) {
      TextView empty = Theme.text(this,
          q.isEmpty() ? "No matching apps." : "No apps matching \"" + searchQuery.trim() + "\".",
          12.5f, Theme.MUTED, false);
      empty.setGravity(Gravity.CENTER);
      empty.setPadding(Theme.dp(this, 16), Theme.dp(this, 24), Theme.dp(this, 16), Theme.dp(this, 24));
      card.addView(empty);
    } else {
      for (int i = 0; i < filtered.size(); i++) {
        if (i > 0) card.addView(Theme.divider(this, 54, 13));
        card.addView(buildAppRow(filtered.get(i), v.multiDevice(), true));
      }
    }
    scrollContent.addView(card);
  }

  // =========================================================================
  // SETTINGS PAGE (Compact Linear-density OLED cards)
  // =========================================================================

  private void buildSettingsPage() {
    Store store = Store.get(this);
    DnsController dns = DnsController.get(this);
    PausePolicy.Config cfg = store.policyConfig();
    Map<String, PausePolicy.PendingChange> pending = store.pendingPolicyChanges();

    // Header
    LinearLayout top = Theme.row(this);
    FrameLayout backHit = new FrameLayout(this);
    backHit.setPadding(0, Theme.dp(this, 6), Theme.dp(this, 10), Theme.dp(this, 6));
    backHit.setContentDescription("Back");
    backHit.addView(new Theme.IconView(this, Theme.IconView.Kind.BACK, Theme.FG, 20));
    backHit.setOnClickListener(v -> onBackPressed());
    top.addView(backHit);
    top.addView(Theme.text(this, "Settings", 20f, Theme.FG, true));
    scrollContent.addView(top);

    // Card 1: Protection
    scrollContent.addView(settingsSectionTitle("Protection"));
    LinearLayout protCard = makeCard();
    protCard.addView(settingsCardRow(
        "Daily DNS-off limit",
        pendingNote(pending.get("daily_limit_minutes")),
        PausePolicy.formatMinutes(cfg.dailyLimitMinutes),
        Theme.IconView.Kind.UPDOWN,
        pending.get("daily_limit_minutes") != null ? () -> { store.cancelPendingPolicy("daily_limit_minutes"); render(); } : null,
        () -> SettingsDialogs.showDailyLimitSheet(this, this::render)));
    protCard.addView(Theme.divider(this, 14, 14));
    String waitKey = pending.containsKey("wait_seconds") ? "wait_seconds"
        : pending.containsKey("max_phone_minutes") ? "max_phone_minutes"
        : pending.containsKey("max_laptop_minutes") ? "max_laptop_minutes" : null;
    protCard.addView(settingsCardRow(
        "Wait before pausing",
        waitKey == null ? null : pendingNote(pending.get(waitKey)),
        cfg.waitSeconds + "s",
        Theme.IconView.Kind.CHEVRON,
        waitKey == null ? null : () -> {
          store.cancelPendingPolicy("wait_seconds");
          store.cancelPendingPolicy("max_phone_minutes");
          store.cancelPendingPolicy("max_laptop_minutes");
          render();
        },
        () -> SettingsDialogs.showPauseLadderSheet(this, this::render)));
    protCard.addView(Theme.divider(this, 14, 14));
    // Auto-resume DNS = the Private DNS guard: DNS turns itself back on if switched off outside a pause.
    // Turning it off is a loosening, so it waits until tomorrow (tap again to undo).
    boolean guardOffPending = pending.containsKey("guard_private_dns");
    protCard.addView(toggleCardRow("Auto-resume DNS",
        guardOffPending ? "Off from tomorrow \u00b7 tap to undo" : null, null, cfg.guardPrivateDns, val -> {
          store.updateGuardPrivateDns(guardOffPending || val);
          render();
        }));
    protCard.addView(Theme.divider(this, 14, 14));
    protCard.addView(settingsCardRow(
        "DNS profile",
        null,
        dns.profile(),
        Theme.IconView.Kind.CHEVRON,
        null,
        () -> SettingsDialogs.showProfileSheet(this, this::render)));
    protCard.addView(Theme.divider(this, 14, 14));
    protCard.addView(settingsCardRow("Pause assistant", null,
        PauseGatewaySettings.configured(this) ? "Gateway key saved" : "",
        Theme.IconView.Kind.CHEVRON, null,
        () -> PauseGatewaySettings.show(this, this::render)));
    scrollContent.addView(protCard);

    // Card 2: Devices
    scrollContent.addView(settingsSectionTitle("Devices"));
    LinearLayout devCard = makeCard();
    String phoneState = !dns.enabled() ? "Finish setup" : dns.deadline() > 0 ? "Paused" : "Protected";
    String phoneName = android.provider.Settings.Global.getString(getContentResolver(), "device_name");
    if (phoneName == null || phoneName.trim().isEmpty()) phoneName = Build.MODEL;
    devCard.addView(settingsCardRow(phoneName + " (This phone)", null, phoneState, Theme.IconView.Kind.CHEVRON, null,
        () -> SettingsDialogs.showPhoneSheet(this, this::render)));
    for (DeviceHub.PairedDevice d : DeviceHub.get(this).list()) {
      devCard.addView(Theme.divider(this, 14, 14));
      String st = d.online ? (d.dnsPausedNow(lastNow) ? "Paused" : "Protected") : "Offline";
      devCard.addView(settingsCardRow(d.name, null, st, Theme.IconView.Kind.CHEVRON, null,
          () -> SettingsDialogs.showPairedDeviceSheet(this, d, this::render,
              () -> openRemotePauseSheet(d))));
    }
    devCard.addView(Theme.divider(this, 14, 14));
    devCard.addView(settingsCardRow("Add device", null, "", Theme.IconView.Kind.CHEVRON, null,
        () -> SettingsDialogs.showAddDeviceSheet(this, this::render)));
    scrollContent.addView(devCard);

    // Card 3: Activity
    scrollContent.addView(settingsSectionTitle("Activity"));
    LinearLayout actCard = makeCard();
    actCard.addView(settingsCardRow("App categories", null, "Sorted automatically", Theme.IconView.Kind.CHEVRON, null,
        () -> SettingsDialogs.showAutoCategoriesSheet(this)));
    actCard.addView(Theme.divider(this, 14, 14));
    actCard.addView(toggleCardRow("Honesty check",
        "Compares your reason with what you opened.", null, cfg.notifyHonesty, val -> {
          store.setNotificationToggle("notify_honesty", val);
          render();
        }));
    actCard.addView(Theme.divider(this, 14, 14));
    actCard.addView(toggleCardRow("Evening summary", null, "22:00", cfg.notifyEvening, val -> {
      store.setNotificationToggle("notify_evening", val);
      FocusNotifications.scheduleEveningSummary(this);
      render();
    }));
    scrollContent.addView(actCard);

    Theme.gap(scrollContent, 12);
    scrollContent.addView(habits.settings());

    // Card 4: App
    scrollContent.addView(settingsSectionTitle("App"));
    LinearLayout appCard = makeCard();
    PhoneUpdater.Snapshot upd = PhoneUpdater.get(this).snapshot();
    String updValue = upd.available != null ? "Ready"
        : "checking".equals(upd.status) ? "Checking\u2026"
        : "up_to_date".equals(upd.status) ? "Up to date"
        : upd.currentBuildId;
    appCard.addView(settingsCardRow("Updates", null, updValue, Theme.IconView.Kind.CHEVRON, null,
        () -> SettingsDialogs.showUpdaterSheet(this, this::render)));
    appCard.addView(Theme.divider(this, 14, 14));
    int okCount = (store.usageAllowed() ? 1 : 0)
        + (FocusAccessibilityService.isEnabled(this) ? 1 : 0)
        + (dns.permitted() ? 1 : 0)
        + (Build.VERSION.SDK_INT < 31 || getSystemService(android.app.AlarmManager.class).canScheduleExactAlarms() ? 1 : 0);
    appCard.addView(settingsCardRow("Permissions & logs", null, okCount + " / 4", Theme.IconView.Kind.CHEVRON, null,
        () -> SettingsDialogs.showPhoneSheet(this, this::render)));
    scrollContent.addView(appCard);

    Theme.gap(scrollContent, 16);
    TextView foot = Theme.text(this, "Focus " + BuildInfo.VERSION_NAME, 10.5f, Theme.DIM, false);
    foot.setGravity(Gravity.CENTER);
    scrollContent.addView(foot, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
  }

  private String pendingNote(PausePolicy.PendingChange pc) {
    if (pc == null) return null;
    return pc.toText + " from tomorrow";
  }

  private LinearLayout makeCard() {
    LinearLayout card = Theme.column(this);
    card.setBackground(Theme.roundRect(this, Theme.CARD, Theme.LINE, 16));
    return card;
  }

  private View settingsSectionTitle(String label) {
    TextView tv = Theme.text(this, label, 11.5f, Theme.MUTED, false);
    tv.setPadding(Theme.dp(this, 4), Theme.dp(this, 14), 0, Theme.dp(this, 6));
    return tv;
  }

  private View settingsCardRow(
      String title,
      String subNote,
      String value,
      Theme.IconView.Kind rightIcon,
      Runnable onCancelPending,
      Runnable onClick) {
    LinearLayout row = Theme.row(this);
    row.setPadding(Theme.dp(this, 14), Theme.dp(this, 11), Theme.dp(this, 14), Theme.dp(this, 11));

    LinearLayout col = Theme.column(this);
    col.addView(Theme.text(this, title, 13.5f, Theme.FG, false));
    if (subNote != null && !subNote.isEmpty()) {
      LinearLayout subRow = Theme.row(this);
      subRow.setPadding(0, Theme.dp(this, 2), 0, 0);
      subRow.addView(Theme.text(this, subNote, 11f, Theme.MUTED, false));
      if (onCancelPending != null) {
        Theme.gap(subRow, 8);
        TextView cancel = Theme.text(this, "Undo", 10.5f, Theme.FG, true);
        cancel.setPadding(Theme.dp(this, 7), Theme.dp(this, 1), Theme.dp(this, 7), Theme.dp(this, 1));
        cancel.setBackground(Theme.roundRect(this, Theme.DARK_ACTIVE, 0, 999));
        cancel.setOnClickListener(v -> onCancelPending.run());
        subRow.addView(cancel);
      }
      col.addView(subRow);
    }
    row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

    if (value != null && !value.isEmpty()) {
      row.addView(Theme.text(this, value, 12.5f, Theme.MUTED, false));
      Theme.gap(row, 6);
    }
    if (rightIcon != null) {
      row.addView(new Theme.IconView(this, rightIcon, Theme.DIM, 13));
    }
    row.setOnClickListener(v -> onClick.run());
    return row;
  }

  private View toggleCardRow(
      String title, String sub, String rightLabel, boolean checked, java.util.function.Consumer<Boolean> onToggle) {
    LinearLayout row = Theme.row(this);
    row.setPadding(Theme.dp(this, 14), Theme.dp(this, 11), Theme.dp(this, 14), Theme.dp(this, 11));
    LinearLayout col = Theme.column(this);
    col.addView(Theme.text(this, title, 13.5f, Theme.FG, false));
    if (sub != null && !sub.isEmpty()) {
      col.addView(Theme.text(this, sub, 11f, Theme.MUTED, false));
    }
    row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

    if (rightLabel != null && !rightLabel.isEmpty()) {
      row.addView(Theme.text(this, rightLabel, 12.5f, Theme.MUTED, false));
      Theme.gap(row, 10);
    }

    // Dark OLED toggle: dark gray track with light knob (no bright white fill)
    FrameLayout track = new FrameLayout(this);
    int tw = Theme.dp(this, 36), th = Theme.dp(this, 20), knob = Theme.dp(this, 14), pad = Theme.dp(this, 3);
    track.setBackground(Theme.roundRect(this, checked ? Theme.TOGGLE_ON : Theme.TOGGLE_OFF, Theme.LINE, 99));
    View dot = new View(this);
    dot.setBackground(Theme.roundRect(this, checked ? Theme.KNOB_ON : Theme.KNOB_OFF, 0, 99));
    FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(knob, knob,
        Gravity.CENTER_VERTICAL | (checked ? Gravity.END : Gravity.START));
    dlp.setMargins(pad, 0, pad, 0);
    track.addView(dot, dlp);
    row.addView(track, new LinearLayout.LayoutParams(tw, th));
    row.setOnClickListener(v -> onToggle.accept(!checked));
    return row;
  }

  // =========================================================================
  // PAUSE SHEET
  // =========================================================================

  private void openPauseSheet() {
    JudgeSheetDialog.show(this, this::render);
  }

  private void openRemotePauseSheet(DeviceHub.PairedDevice device) {
    JSONObject remoteDns = device.snapshot == null ? null : device.snapshot.optJSONObject("dns");
    if ("laptop".equals(device.kind) && remoteDns != null && remoteDns.optBoolean("pause_assistant")) {
      JudgeSheetDialog.show(this, device, this::render);
      return;
    }
    long now = System.currentTimeMillis();
    PauseSheetDialog.DeviceOption option = new PauseSheetDialog.DeviceOption(device.id, device.kind,
        device.name, device.dnsPausedNow(now), device.online);
    PauseSheetDialog.show(this, Collections.singletonList(option), HabitScreen.pauseAllowance(this, now),
        countPausesToday() + 1, HabitScreen.offToday(this, now),
        (target, minutes, reason, plan, checkIn, done, error) -> {
          long submitTime = System.currentTimeMillis();
          boolean allowed = PausePolicy.pauseChoices(HabitScreen.pauseAllowance(this, submitTime), target.kind,
              HabitScreen.offToday(this, submitTime)).stream().anyMatch(choice -> choice.minutes == minutes && choice.allowed);
          if (!allowed) {
            error.accept("The remaining allowance changed. Choose a shorter pause.");
            return;
          }
          new Thread(() -> {
            try {
              JSONObject command = new JSONObject().put("action", "dns_pause").put("minutes", minutes)
                  .put("reason", reason).put("check_in", checkIn.value);
              if (!plan.isEmpty()) command.put("after_plan", plan);
              DeviceHub.get(this).sendCommand(target.id, command);
              runOnUiThread(() -> { done.run(); render(); });
            } catch (Exception failure) {
              runOnUiThread(() -> error.accept(failure.getMessage() == null
                  ? "Could not reach " + target.label : failure.getMessage()));
            }
          }, "focus-remote-pause").start();
        });
  }
}
