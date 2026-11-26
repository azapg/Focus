package org.nightingalelab.focus.tv;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.nightingalelab.focus.habits.HabitViews;

public final class MainActivity extends Activity {
  public static volatile boolean visible;
  private boolean week, restrictions, habits;
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
      week = state.getBoolean("week");
      restrictions = state.getBoolean("restrictions");
      habits = state.getBoolean("habits");
      selectedSlot = state.getInt("selectedSlot", -1);
      selectedCategory = state.getInt("selectedCategory", -1);
    }
    if (Store.get(this).usageAllowed() || LanController.get(this).enabled()) MonitorService.ensure(this);
    render();
  }

  @Override
  public void onResume() {
    super.onResume();
    visible = true;
    Ui.fullscreen(this);
    AppCatalog.invalidate();
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
    if (LanController.get(this).pendingApproval() != null) startActivity(new Intent(this,PairingActivity.class));
  }

  @Override
  public void onPause() {
    visible = false;
    handler.removeCallbacks(refresh);
    super.onPause();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    b.putBoolean("week", week);
    b.putBoolean("restrictions", restrictions);
    b.putBoolean("habits", habits);
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

  static int controlId(String id) {
    return (id.hashCode() & 0x00ffffff) + 1;
  }

  private void render() {
    if (HabitViews.Hold.active()) return;
    int focused = getCurrentFocus() == null ? 0 : getCurrentFocus().getId();
    root = Ui.screen(this);
    root.setPadding(root.getPaddingLeft(), Ui.dp(this, 20), root.getPaddingRight(), Ui.dp(this, 20));
    LinearLayout header = Ui.row(this);
    TextView title = Ui.text(this, restrictions ? "App restrictions" : "Focus", 24, Ui.FG);
    if (!restrictions && !habits) {
      ImageView mark = new ImageView(this);
      mark.setImageResource(R.drawable.focus_ring);
      mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      header.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));
      Ui.gap(header, 5);
    }
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    Button first;
    if (restrictions) {
      first = button("Back to usage", "nav_usage", this::openUsage);
      header.addView(first);
    } else {
      first = button("Today", "today", () -> selectPeriod(false));
      first.setSelected(!week && !habits);
      header.addView(first);
      Ui.gap(header, 8);
      Button period = button("Week", "week", () -> selectPeriod(true));
      period.setSelected(week && !habits);
      header.addView(period);
      Ui.gap(header, 8);
      Button habitTab = button("Habits", "habits", () -> { habits = true; render(); });
      habitTab.setSelected(habits);
      header.addView(habitTab);
      Ui.gap(header, 8);
      header.addView(button("Settings", "settings", this::settings));
      if (habits) first = habitTab;
      else if (week) first = period;
    }
    root.addView(header);
    Ui.gap(root, 12);
    Store store = Store.get(this);
    if (overlay == null) {
      overlay = new FrameLayout(this);
      setContentView(overlay);
    }
    if (overlay.getChildCount() > 0) overlay.removeViewAt(0);
    overlay.addView(root, 0, new FrameLayout.LayoutParams(-1, -1));
    if (restrictions) {
      showRestrictions();
    } else if (habits) {
      root.addView(new HabitDashboard(this, System.currentTimeMillis()).view(overlay, this::render),
          new LinearLayout.LayoutParams(-1, 0, 1));
    } else if (store.usageAllowed()) {
      showUsage();
      if (Store.heartbeat == 0 || SystemClock.elapsedRealtime() - Store.heartbeat > 15000) {
        Ui.gap(root, 8);
        root.addView(button("Restart tracking", "restart", () -> MonitorService.ensure(this)));
      }
    } else {
      root.addView(button("Enable usage access", "usage_access",
          () -> openSetting(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
    }
    View prior = focused == 0 ? null : findViewById(focused);
    if (prior != null) prior.requestFocus();
    else first.requestFocus();
  }

  private void selectPeriod(boolean showWeek) {
    habits = false;
    week = showWeek;
    selectedSlot = -1;
    selectedCategory = -1;
    render();
  }

  private void openUsage() {
    restrictions = false;
    render();
  }

  void openRestrictions() {
    restrictions = true;
    render();
  }

  @Override
  public void onBackPressed() {
    if (restrictions) openUsage();
    else if (habits) selectPeriod(false);
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
    row.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
    TextView name = Ui.text(this, app.label, 17, Ui.FG),
        amount = Ui.text(this, suffix, 15, Ui.MUTED);
    name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    amount.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(amount);
    android.graphics.drawable.StateListDrawable bg =
        new android.graphics.drawable.StateListDrawable();
    bg.addState(new int[] {android.R.attr.state_focused}, Ui.shape(Ui.FG, Ui.FG, 10, this));
    bg.addState(new int[] {}, Ui.shape(Ui.BG, 0, 10, this));
    row.setBackground(bg);
    row.setOnFocusChangeListener(
        (v, focus) -> {
          name.setTextColor(focus ? Ui.BG : Ui.FG);
          amount.setTextColor(focus ? Ui.BG : Ui.MUTED);
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
                      ? "Blocked"
                      : rule.dailyLimitMs > 0
                          ? Ui.time(rule.dailyLimitMs) + " / day"
                          : rule.scheduled ? "Scheduled" : "No limit";
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
    Button blocked = Ui.button(this, blockLabel(label, rule.blocked), () -> {});
    if (!store.usageAllowed() || !RestrictionService.connected) {
      blocked.setText("Enable app restrictions");
      blocked.setOnClickListener(v -> openSetting(!store.usageAllowed()
          ? Settings.ACTION_USAGE_ACCESS_SETTINGS : Settings.ACTION_ACCESSIBILITY_SETTINGS));
    } else {
      blocked.setOnClickListener(v -> {
        Rules fresh = store.rule(pkg);
        fresh.blocked = !fresh.blocked;
        if (!save(pkg, fresh)) return;
        blocked.setText(blockLabel(label, fresh.blocked));
        summary.setText(Ui.time(used) + "  ·  " + period + "  ·  "
            + (fresh.blocked ? "Block on" : "Block off"));
      });
    }
    body.addView(blocked);
    Ui.gap(body, 7);
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
    long deadline = store.pauseDeadline(pkg);
    if (deadline > 0)
      body.addView(
          Ui.button(
              this,
              "End pause · " + Ui.countdown(deadline),
              () -> {
                store.endPause(pkg, "manual");
                Toast.makeText(this, "Pause ended", Toast.LENGTH_SHORT).show();
              }));
    body.addView(
        Ui.button(
            this,
            "Free time (Store)",
            () -> PauseForm.showFreeTimeStore(this, pkg, () -> PauseForm.openApp(this, pkg))));
    Ui.gap(body, 7);
    body.addView(Ui.button(this, "Open app", () -> PauseForm.openApp(this, pkg)));
    Dialog dialog = Ui.dialog(this, label, body);
    dialog.setOnDismissListener(d -> render());
    dialog.show();
  }

  private String blockLabel(String app, boolean blocked) {
    return (blocked ? "Unblock " : "Block ") + app;
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
    LinearLayout body = Ui.column(this);
    final Dialog[] dialog = new Dialog[1];
    for (int min : new int[] {0, 15, 30, 60, 120}) {
      body.addView(
          Ui.button(
              this,
              min == 0 ? "None" : Ui.time(min * 60000L),
              () -> {
                Rules r = Store.get(this).rule(pkg);
                r.dailyLimitMs = min * 60000L;
                save(pkg, r);
                dialog[0].dismiss();
              }));
      Ui.gap(body, 5);
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
    Button enabled = Ui.button(this, "Enabled · " + (r.scheduled ? "On" : "Off"), () -> {});
    enabled.setOnClickListener(
        v -> {
          r.scheduled = !r.scheduled;
          save(pkg, r);
          enabled.setText("Enabled · " + (r.scheduled ? "On" : "Off"));
        });
    body.addView(enabled);
    Ui.gap(body, 8);
    for (boolean start : new boolean[] {true, false}) {
      Button time =
          Ui.button(
              this,
              (start ? "From · " : "Until · ") + clock(start ? r.fromMinute : r.toMinute),
              () -> {});
      time.setOnClickListener(
          v -> {
            int n = start ? r.fromMinute : r.toMinute;
            new TimePickerDialog(
                    this,
                    (picker, h, m) -> {
                      if (start) r.fromMinute = h * 60 + m;
                      else r.toMinute = h * 60 + m;
                      save(pkg, r);
                      time.setText((start ? "From · " : "Until · ") + clock(h * 60 + m));
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
    Dialog dialog = Ui.dialog(this, "Schedule", body);
    dialog.setOnDismissListener(x -> changed.run());
    dialog.show();
  }

  private void openSetting(String action) {
    try {
      startActivity(new Intent(action));
    } catch (RuntimeException e) {
      Toast.makeText(this, "Open this permission in TV Settings", Toast.LENGTH_LONG).show();
      Store.get(this).diagnostic("settings_unavailable_" + e.getClass().getSimpleName());
    }
  }

  private void settings() {
    Store store = Store.get(this);
    LinearLayout body = Ui.column(this);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(body);
    LinearLayout holder = Ui.column(this);
    holder.addView(scroll, new LinearLayout.LayoutParams(-1, Ui.dp(this, 320)));
    Dialog dialog = Ui.dialog(this, "Settings", holder);
    body.addView(Ui.button(this, "NextDNS · " + DnsController.get(this).status(), () -> {
      dialog.dismiss();
      dns();
    }));
    Ui.gap(body, 7);
    body.addView(Ui.button(this, "App restrictions", () -> {
      dialog.dismiss();
      restrictions = true;
      render();
    }));
    Ui.gap(body, 7);
    body.addView(Ui.button(this, "Connected devices", this::connections));
    Ui.gap(body, 7);
    body.addView(
        Ui.button(
            this,
            "Usage access · " + (store.usageAllowed() ? "On" : "Off"),
            () -> openSetting(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
    Ui.gap(body, 7);
    body.addView(
        Ui.button(
            this,
            "Accessibility · " + (RestrictionService.connected ? "On" : "Off"),
            () -> openSetting(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    Ui.gap(body, 7);
    body.addView(Ui.button(this, "Pause history", this::pauseHistory));
    Ui.gap(body, 7);
    body.addView(
        Ui.button(
            this,
            "Export history",
            () -> {
              try {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                if (dir == null) throw new IOException("Storage unavailable");
                if (!dir.exists() && !dir.mkdirs())
                  throw new IOException("Could not create directory");
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
            }));
    Ui.gap(body, 7);
    body.addView(Ui.button(this, "Diagnostics", this::diagnostics));
    dialog.show();
  }

  private void connections() {
    LanController lan = LanController.get(this);
    LinearLayout body = Ui.column(this);
    body.addView(Ui.text(this, lan.pairedCount() + " paired managers", 16, Ui.MUTED));
    Ui.gap(body, 10);
    body.addView(Ui.text(this, lan.enabled() ? "This TV appears automatically in Focus on your laptop." : "Nearby connections are off.", 16, Ui.MUTED));
    Ui.gap(body, 10);
    body.addView(Ui.button(this, "Allow nearby connections", () -> { lan.enable(); Toast.makeText(this,"Look for this TV on your laptop",Toast.LENGTH_LONG).show(); }));
    Ui.gap(body, 8);
    body.addView(Ui.button(this, "Disconnect all managers", () -> {
      LinearLayout confirm = Ui.column(this);
      confirm.addView(Ui.text(this, "Laptops and phones will need to pair again. Local tracking, app rules and DNS stay on.", 15, Ui.MUTED));
      Dialog dialog = Ui.dialog(this, "Disconnect all managers?", confirm);
      Ui.gap(confirm, 12);
      confirm.addView(Ui.button(this, "Disconnect all", () -> { lan.disconnectAll(); dialog.dismiss(); }));
      dialog.show();
    }));
    if (!lan.error().isEmpty()) body.addView(Ui.text(this, lan.error(), 15, Ui.MUTED));
    Ui.dialog(this, "Connected devices", body).show();
  }

  public void dns() {
    DnsController dns = DnsController.get(this);
    LinearLayout body = Ui.column(this);
    TextView status = Ui.text(this, dns.status(), 18, Ui.FG);
    body.addView(status);
    Ui.gap(body, 12);
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
    Dialog dialog = Ui.dialog(this, "NextDNS", body);
    if (!dns.permitted()) body.addView(Ui.text(this, "Computer setup required", 16, Ui.MUTED));
    else {
      body.addView(
          Ui.button(
              this,
              dns.enabled() ? "Save profile" : "Connect",
              () -> {
                try {
                  dns.connect(profile.getText().toString().trim());
                  dns.checkAsync(() -> status.setText(dns.status()));
                  status.setText(dns.status());
                } catch (RuntimeException e) {
                  profile.setError(e.getMessage());
                }
              }));
      Ui.gap(body, 7);
      if (dns.enabled()) {
        body.addView(
            Ui.button(
                this,
                dns.deadline() > 0 ? "Resume filtering" : "Free time (Store)",
                () -> {
                  dialog.dismiss();
                  if (dns.deadline() > 0) {
                    try {
                      dns.resume();
                    } catch (RuntimeException e) {
                      Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    dns();
                  } else
                    PauseForm.showFreeTimeStore(
                        this,
                        DnsController.KEY,
                        () -> {
                          render();
                          dns();
                        });
                }));
        Ui.gap(body, 7);
        body.addView(
            Ui.button(
                this,
                "Check connection",
                () -> {
                  dns.checkAsync(() -> status.setText(dns.status()));
                  status.setText(dns.status());
                }));
        Ui.gap(body, 7);
        body.addView(
            Ui.button(
                this,
                "Restore previous DNS",
                () ->
                    new AlertDialog.Builder(this)
                        .setTitle("Restore previous DNS?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton(
                            "Restore",
                            (d, w) -> {
                              try {
                                dns.disconnect();
                                dialog.dismiss();
                                render();
                              } catch (RuntimeException e) {
                                status.setText(e.getMessage());
                              }
                            })
                        .show()));
      }
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
                    + new java.text.SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
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
