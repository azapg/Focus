package org.nightingalelab.focus.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps the intention visible outside the app: the always-on Focus notification, one drift nudge,
 * the time's-up question and the Now widget. All of these are ordinary notifications, so they work
 * on One UI without Live Updates or the Now Bar.
 */
final class IntentionMonitor {
  private static final String CHANNEL = "focus_intention";
  static final int MONITOR_ID = 1;
  private static final int TIME_UP_ID = 2008;
  private static final int NUDGE_ID = 2009;

  static final String ACTION = "org.nightingalelab.focus.phone.INTENTION_ACTION";
  static final String EXTRA_DO = "do";
  static final String EXTRA_APP = "app";

  /** Latest drift, for the Today headline and the widget. */
  static volatile Intention.Drift drift = Intention.Drift.NONE;
  private static String postedKey = "";

  private IntentionMonitor() {}

  /** Runs on the monitor thread every couple of seconds. */
  static void tick(Context c) {
    IntentionStore store = IntentionStore.get(c);
    store.adoptFromLaptop();
    store.flushIfDue();
    Intention current = store.current();
    long now = System.currentTimeMillis();
    Intention.Drift next = current == null ? Intention.Drift.NONE : computeDrift(c, current, now);
    drift = next;
    if (current != null && Intention.nudgeDue(next, store.lastNudgeAt(), now)) {
      store.nudged(now);
      postNudge(c, current, next);
    }
    if (current != null && current.timeUp(now) && store.claimTimeUp()) postTimeUp(c, current);
    refresh(c);
  }

  /** Re-renders the monitor notification and the widget when what they show has changed. */
  static synchronized void refresh(Context c) {
    Intention current = IntentionStore.get(c).current();
    long now = System.currentTimeMillis();
    Intention.Drift d = drift;
    String key = current == null ? "none"
        : current.id + "|" + current.label + "|" + current.doneWhen + "|" + current.until + "|"
            + (current.remainingMs(now) + Intention.MINUTE - 1) / Intention.MINUTE + "|"
            + (d.showing() ? d.text() : "");
    if (key.equals(postedKey)) return;
    postedKey = key;
    NotificationManager nm = c.getSystemService(NotificationManager.class);
    if (nm != null) {
      try {
        nm.notify(MONITOR_ID, monitorNotification(c));
      } catch (RuntimeException ignored) {}
      if (current == null || !current.timeUp(now)) nm.cancel(TIME_UP_ID);
      if (current == null || !d.current) nm.cancel(NUDGE_ID);
    }
    WidgetUpdater.updateIntention(c);
  }

  // --- Drift -------------------------------------------------------------------------------------

  static Intention.Drift computeDrift(Context c, Intention current, long now) {
    Store store = Store.get(c);
    if (!store.usageAllowed()) return Intention.Drift.NONE;
    long since = Math.max(current.start, now - 30 * Intention.MINUTE);
    List<InsightsEngine.SpanRecord> spans = store.localSpans(since, now);
    List<ActivityClassifier.UserRule> rules = store.userRules();
    Set<String> fits = new HashSet<>(IntentionStore.get(c).fits());
    ScopedAccessWindow approved = DeviceHub.get(c).activeScopedAccess();
    Set<String> approvedServices = new HashSet<>();
    if (approved != null) for (String s : approved.services) approvedServices.add(serviceKey(s));
    String foreground = Store.foreground;
    List<Intention.Use> uses = new ArrayList<>();
    for (int i = 0; i < spans.size(); i++) {
      InsightsEngine.SpanRecord span = spans.get(i);
      boolean live = i == spans.size() - 1 && foreground.equals(span.app) && now - span.e <= 2_000;
      long end = live ? now : span.e;
      ActivityClassifier.Result result = ActivityClassifier.classify(span.app, span.label, span.domain, rules);
      boolean drifting = false;
      if (!result.noise && !c.getPackageName().equals(span.app)) {
        ActivityClassifier.Fine fine = ActivityClassifier.fineCategory(result, "phone");
        drifting = fine != null && ActivityClassifier.FG_DRIFT.equals(fine.group)
            && !fits.contains(result.app) && !approvedServices.contains(serviceKey(result.app));
      }
      uses.add(new Intention.Use(span.s, end, result.app == null ? "" : result.app, drifting));
    }
    return Intention.drift(uses, since, now);
  }

  /** "YouTube" and "youtube" are the same Ask service. */
  static String serviceKey(String name) {
    return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  // --- Notifications -----------------------------------------------------------------------------

  /** The always-on "Focus" notification. With an intention, it becomes the intention. */
  static Notification monitorNotification(Context c) {
    Intention current = IntentionStore.get(c).current();
    long now = System.currentTimeMillis();
    Notification.Builder b = new Notification.Builder(c, "focus_monitor")
        .setSmallIcon(R.drawable.ic_stat_focus)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setContentIntent(open(c, 0, false));
    if (current == null) {
      return b.setContentTitle("Focus").setContentText("Tracking activity").build();
    }
    Intention.Drift d = drift;
    String left = current.timeUp(now) ? "time's up" : Intention.left(current.remainingMs(now));
    CharSequence text = d.showing() ? orange(d.text())
        : current.doneWhen.isEmpty() ? "Until " + clock(c, current.until) : "Done when " + current.doneWhen;
    b.setContentTitle(current.label + " · " + left).setContentText(text)
        .addAction(action(c, "done", "Done", 1))
        .addAction(action(c, "extend", "+10 min", 2));
    if (d.showing()) b.setColor(Theme.WARN);
    return b.build();
  }

  private static void postNudge(Context c, Intention current, Intention.Drift d) {
    NotificationManager nm = manager(c);
    if (nm == null) return;
    Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent back = PendingIntent.getActivity(c, 40, home,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    long minutes = Math.max(1, d.ms / Intention.MINUTE);
    String text = minutes + (minutes == 1 ? " minute" : " minutes") + " in " + d.app + ". "
        + Intention.left(current.remainingMs(System.currentTimeMillis())) + ".";
    Intent fits = new Intent(c, Actions.class).setAction(ACTION).putExtra(EXTRA_DO, "fits").putExtra(EXTRA_APP, d.app);
    nm.notify(NUDGE_ID, new Notification.Builder(c, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_focus)
        .setContentTitle("You said " + current.label)
        .setContentText(text)
        .setColor(Theme.WARN)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setCategory(Notification.CATEGORY_REMINDER)
        .setContentIntent(open(c, 41, false))
        .addAction(new Notification.Action.Builder(null, "Back to it", back).build())
        .addAction(new Notification.Action.Builder(null, "Ask", open(c, 42, true)).build())
        .addAction(new Notification.Action.Builder(null, "Not drifting", PendingIntent.getBroadcast(c, 43, fits,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build())
        .build());
  }

  private static void postTimeUp(Context c, Intention current) {
    NotificationManager nm = manager(c);
    if (nm == null) return;
    nm.cancel(NUDGE_ID);
    nm.notify(TIME_UP_ID, new Notification.Builder(c, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_focus)
        .setContentTitle(current.label + " · time's up")
        .setContentText(current.question())
        .setAutoCancel(true)
        .setShowWhen(false)
        .setCategory(Notification.CATEGORY_REMINDER)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setContentIntent(open(c, 44, false))
        .addAction(action(c, "done", "Done", 11))
        .addAction(action(c, "partly", "Partly", 12))
        .addAction(action(c, "extend", "10 more min", 13))
        .addAction(action(c, "no", "No", 14))
        .build());
  }

  private static NotificationManager manager(Context c) {
    NotificationManager nm = c.getSystemService(NotificationManager.class);
    if (nm == null || !nm.areNotificationsEnabled()) return null;
    NotificationChannel channel = new NotificationChannel(CHANNEL, "Intentions", NotificationManager.IMPORTANCE_HIGH);
    channel.setDescription("Drift nudges and the time's up question");
    channel.setSound(null, null);
    channel.setShowBadge(false);
    nm.createNotificationChannel(channel);
    return nm;
  }

  private static Notification.Action action(Context c, String what, String label, int code) {
    Intent intent = new Intent(c, Actions.class).setAction(ACTION).putExtra(EXTRA_DO, what);
    PendingIntent pending = PendingIntent.getBroadcast(c, 500 + code, intent,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Action.Builder(null, label, pending).build();
  }

  static PendingIntent open(Context c, int code, boolean ask) {
    Intent intent = new Intent(c, MainActivity.class).putExtra("page", "today")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    if (ask) intent.putExtra("open_pause_sheet", true);
    return PendingIntent.getActivity(c, 600 + code, intent,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  static String clock(Context c, long at) {
    return android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date(at));
  }

  private static CharSequence orange(String text) {
    SpannableString s = new SpannableString(text);
    s.setSpan(new ForegroundColorSpan(Theme.WARN), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    return s;
  }

  /** Done, Partly, No, +10 min and Not drifting from any notification or the widget. */
  public static final class Actions extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
      String what = intent.getStringExtra(EXTRA_DO);
      if (what == null) return;
      PendingResult pending = goAsync();
      new Thread(() -> {
        try {
          IntentionStore store = IntentionStore.get(c);
          switch (what) {
            case "done":
            case "partly":
            case "no":
              store.end(what);
              break;
            case "extend":
              store.extend(Intention.EXTEND_MINUTES);
              break;
            case "fits":
              store.markFits(intent.getStringExtra(EXTRA_APP));
              drift = Intention.Drift.NONE;
              break;
            default:
              break;
          }
          NotificationManager nm = c.getSystemService(NotificationManager.class);
          if (nm != null) {
            nm.cancel(NUDGE_ID);
            if (!"fits".equals(what)) nm.cancel(TIME_UP_ID);
          }
          store.flushAsync();
          refresh(c);
        } finally {
          pending.finish();
        }
      }, "focus-intention-action").start();
    }
  }
}
