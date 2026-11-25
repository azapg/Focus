package org.nightingalelab.focus.phone;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateFormat;
import java.util.*;
import org.nightingalelab.focus.telemetry.LocalTelemetry.Metric;

/** Delivers the phone DNS pause timer, insights, and update notifications. */
public final class FocusNotifications {
  private static final String CHANNEL_DNS_PAUSE = "focus_dns_pause";
  private static final String CHANNEL_DNS_LIVE = "focus_dns_live";
  private static final int DNS_PAUSE_ID = 2004;
  private static final int SCOPED_ACCESS_ID = 2007;
  private static ScopedAccessWindow scopedPosted;
  private static long scopedUpdatedAt;
  private static boolean scopedBlocked;
  private static boolean scopedChecked;
  // The public promotion request extra is also understood before the 36.1 setter was added.
  private static final String REQUEST_PROMOTED = "android.requestPromotedOngoing";
  private static final String CHANNEL_INSIGHTS = "focus_insights";
  private static final String CHANNEL_PAUSE_RATING = "focus_pause_rating";
  private static final int PAUSE_RATING_ID = 2005;
  private static final String CHANNEL_UPDATES = "focus_updates";
  private static final String CHANNEL_HABIT_NUDGE = "focus_habit_nudges";
  private static final int HABIT_NUDGE_ID = 2006;

  static void sendHabitNudge(Context c, String text) {
    NotificationManager manager = c.getSystemService(NotificationManager.class);
    if (manager == null || !manager.areNotificationsEnabled()) return;
    NotificationChannel channel = new NotificationChannel(
        CHANNEL_HABIT_NUDGE, "Habits", NotificationManager.IMPORTANCE_HIGH);
    channel.setSound(null, null);
    channel.enableVibration(false);
    channel.setShowBadge(false);
    manager.createNotificationChannel(channel);
    PendingIntent open = PendingIntent.getActivity(c, HABIT_NUDGE_ID, habitsIntent(c),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    manager.notify(HABIT_NUDGE_ID, new Notification.Builder(c, CHANNEL_HABIT_NUDGE)
        .setSmallIcon(R.drawable.ic_stat_focus)
        .setContentTitle(text)
        .setContentIntent(open)
        .setColor(Theme.WARN)
        .setAutoCancel(true)
        .setLocalOnly(true)
        .setShowWhen(false)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setTimeoutAfter(20_000)
        .build());
  }

  static Intent habitsIntent(Context c) {
    return new Intent(c, MainActivity.class).putExtra("page", "habits")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
  }

  static void clearHabitNudge(Context c) {
    NotificationManager manager = c.getSystemService(NotificationManager.class);
    if (manager != null) manager.cancel(HABIT_NUDGE_ID);
  }

  static synchronized void updateDnsPause(Context c, long deadline, boolean onlyIfShowing) {
    try {
      NotificationManager nm = c.getSystemService(NotificationManager.class);
      if (nm == null) return;
      long remaining = deadline - SystemClock.elapsedRealtime();
      long endTime = System.currentTimeMillis() + remaining;
      if (remaining <= 0) {
        nm.cancel(DNS_PAUSE_ID);
        return;
      }
      if (!nm.areNotificationsEnabled()) return;
      if (onlyIfShowing && Arrays.stream(nm.getActiveNotifications())
          .noneMatch(active -> active.getId() == DNS_PAUSE_ID)) return;

      String channelId = dnsPauseChannel(nm);
      PendingIntent open = PendingIntent.getActivity(c, DNS_PAUSE_ID,
          new Intent(c, MainActivity.class).putExtra("page", "today"), PendingIntent.FLAG_IMMUTABLE);
      // Give each pause its own action so an older notification can't end a newer pause.
      Intent resumeIntent = new Intent(c, DnsExpiryReceiver.class)
          .setAction(DnsExpiryReceiver.ACTION_RESUME)
          .setData(Uri.parse("focus://dns-pause/" + deadline))
          .putExtra(DnsExpiryReceiver.EXTRA_DEADLINE, deadline);
      PendingIntent resume = PendingIntent.getBroadcast(
          c, DNS_PAUSE_ID, resumeIntent, PendingIntent.FLAG_IMMUTABLE);

      long duration = Build.VERSION.SDK_INT >= 36 ? Store.get(c).dnsPauseDurationMs(deadline) : 0;
      Notification notification = dnsTimer(c, channelId, open, resume, "Turn on now", "Phone DNS off",
          "Back on at " + DateFormat.getTimeFormat(c).format(new Date(endTime)), endTime, remaining,
          duration);
      nm.notify(DNS_PAUSE_ID, notification);
      if (Build.VERSION.SDK_INT >= 36 && !onlyIfShowing) {
        logDnsLiveUpdate(c, nm, DNS_PAUSE_ID, "dns", notification);
      }
    } catch (RuntimeException e) {
      Store.get(c).diagnostic("dns_pause_notification_failed_" + e.getClass().getSimpleName());
    }
  }

  /** Returns false when the selected grant expired after the hub checked its deadline. */
  static synchronized boolean updateScopedAccess(Context c, ScopedAccessWindow access, boolean refreshProgress) {
    try {
      NotificationManager nm = c.getSystemService(NotificationManager.class);
      if (nm == null) return true;
      if (access == null || access.grantId.isEmpty() || access.duration <= 0) {
        clearScopedAccess(c, nm);
        return true;
      }

      String channel = dnsPauseChannel(nm);
      if (!nm.areNotificationsEnabled()
          || nm.getNotificationChannel(channel).getImportance() == NotificationManager.IMPORTANCE_NONE) {
        if (!scopedBlocked) Store.get(c).diagnostic("scoped_timer_failed_notifications_disabled");
        scopedBlocked = true;
        return true;
      }

      long now = SystemClock.elapsedRealtime();
      boolean sameGrant = access.sameGrant(scopedPosted);
      if (sameGrant && !scopedBlocked) {
        if (access.deadline == scopedPosted.deadline && access.services.equals(scopedPosted.services)
            && (!refreshProgress || now - scopedUpdatedAt < 15_000)) return true;
        if (Arrays.stream(nm.getActiveNotifications()).noneMatch(active -> active.getId() == SCOPED_ACCESS_ID)) {
          return true;
        }
      }

      StringJoiner names = new StringJoiner(", ");
      for (String service : access.services) names.add(JudgeSheetDialog.serviceName(service));
      PendingIntent open = PendingIntent.getActivity(c, SCOPED_ACCESS_ID,
          new Intent(c, MainActivity.class).putExtra("page", "today")
              .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
          PendingIntent.FLAG_IMMUTABLE);
      PendingIntent end = PendingIntent.getBroadcast(c, SCOPED_ACCESS_ID,
          new Intent(c, DnsExpiryReceiver.class).setAction(DnsExpiryReceiver.ACTION_END_SCOPE)
              .setData(new Uri.Builder().scheme("focus").authority("scoped-access")
                  .appendPath(access.laptopId).appendPath(access.grantId).build())
              .putExtra(DnsExpiryReceiver.EXTRA_LAPTOP_ID, access.laptopId)
              .putExtra(DnsExpiryReceiver.EXTRA_GRANT_ID, access.grantId),
          PendingIntent.FLAG_IMMUTABLE);
      long remaining = access.remaining();
      if (remaining == 0) return false;
      Notification notification = dnsTimer(c, channel, open, end, "End", names + " open", null,
          System.currentTimeMillis() + remaining, remaining, access.duration);
      if (access.remaining() == 0) return false;
      nm.notify(SCOPED_ACCESS_ID, notification);
      scopedChecked = true;
      scopedPosted = access;
      scopedUpdatedAt = now;
      if (!sameGrant || scopedBlocked) {
        Store.get(c).diagnostic("scoped_timer_posted");
        if (Build.VERSION.SDK_INT >= 36) logDnsLiveUpdate(c, nm, SCOPED_ACCESS_ID, "scoped", notification);
      }
      scopedBlocked = false;
    } catch (RuntimeException e) {
      Store.get(c).diagnostic("scoped_timer_failed_" + e.getClass().getSimpleName());
    }
    return true;
  }

  private static void clearScopedAccess(Context c, NotificationManager nm) {
    if (scopedPosted != null || (!scopedChecked && Arrays.stream(nm.getActiveNotifications())
        .anyMatch(active -> active.getId() == SCOPED_ACCESS_ID))) {
      nm.cancel(SCOPED_ACCESS_ID);
      Store.get(c).diagnostic("scoped_timer_cleared");
    }
    scopedPosted = null;
    scopedBlocked = false;
    scopedChecked = true;
  }

  private static Notification dnsTimer(Context c, String channel, PendingIntent open, PendingIntent action,
      String actionLabel, String title, String text, long endTime, long remaining, long duration) {
    Notification.Builder timer = new Notification.Builder(c, channel)
        .setSmallIcon(R.drawable.ic_stat_focus)
        .setContentTitle(title)
        .setContentText(text)
        .setColor(Theme.WARN)
        .setCategory(Notification.CATEGORY_STOPWATCH)
        .setWhen(endTime)
        .setShowWhen(true)
        .setUsesChronometer(true)
        .setChronometerCountDown(true)
        .setTimeoutAfter(remaining)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setLocalOnly(true)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setContentIntent(open)
        .addAction(new Notification.Action.Builder(null, actionLabel, action).build());
    int progress = duration > 0
        ? (int) ((duration - Math.min(duration, remaining)) * 100 / duration) : 0;
    if (Build.VERSION.SDK_INT >= 36) {
      timer.getExtras().putBoolean(REQUEST_PROMOTED, true);
      timer.setStyle(new Notification.ProgressStyle()
          .setStyledByProgress(false)
          .addProgressSegment(new Notification.ProgressStyle.Segment(100).setColor(Theme.WARN))
          .setProgress(progress));
    } else if (duration > 0) {
      timer.setProgress(100, progress, false);
    }
    return timer.build();
  }

  private static String dnsPauseChannel(NotificationManager nm) {
    if (Build.VERSION.SDK_INT >= 36 && nm.getNotificationChannel(CHANNEL_DNS_LIVE) != null) {
      return CHANNEL_DNS_LIVE;
    }
    NotificationChannel existing = nm.getNotificationChannel(CHANNEL_DNS_PAUSE);
    // Android keeps the original importance of an existing channel. Use a new silent channel
    // for Live Updates, while honoring users who explicitly changed or blocked the original.
    if (Build.VERSION.SDK_INT < 36 || (existing != null
        && (existing.hasUserSetImportance() || existing.hasUserSetSound()
            || existing.getImportance() == NotificationManager.IMPORTANCE_NONE))) {
      if (existing == null) {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_DNS_PAUSE, "DNS pause timer", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
      }
      return CHANNEL_DNS_PAUSE;
    }
    NotificationChannel channel = new NotificationChannel(
        CHANNEL_DNS_LIVE, "Live DNS pause timer", NotificationManager.IMPORTANCE_DEFAULT);
    channel.setSound(null, null);
    channel.setShowBadge(false);
    nm.createNotificationChannel(channel);
    return CHANNEL_DNS_LIVE;
  }

  private static void logDnsLiveUpdate(Context c, NotificationManager nm, int id, String kind, Notification posted) {
    new Handler(Looper.getMainLooper()).postDelayed(() -> {
      try {
        for (android.service.notification.StatusBarNotification active : nm.getActiveNotifications()) {
          Notification current = active.getNotification();
          if (active.getId() != id || Math.abs(current.when - posted.when) > 1_000) continue;
          boolean promoted = (current.flags & Notification.FLAG_PROMOTED_ONGOING) != 0;
          Store.get(c).diagnostic(kind + "_live_update_eligible_" + posted.hasPromotableCharacteristics()
              + "_allowed_" + nm.canPostPromotedNotifications() + "_promoted_" + promoted);
          return;
        }
      } catch (RuntimeException e) {
        Store.get(c).diagnostic(kind + "_live_update_status_failed_" + e.getClass().getSimpleName());
      }
    }, 500);
  }

  private static NotificationManager manager(Context c) {
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (nm != null) {
      nm.createNotificationChannel(
          new NotificationChannel(CHANNEL_INSIGHTS, "Focus insights", NotificationManager.IMPORTANCE_DEFAULT));
      nm.createNotificationChannel(
          new NotificationChannel(CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_LOW));
    }
    return nm;
  }

  public static void maybeSendUpdateAvailable(Context c, UpdateManifest manifest) {
    if (manifest == null) return;
    NotificationManager nm = manager(c);
    if (nm == null) return;
    if (Build.VERSION.SDK_INT >= 33
        && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      return;
    }
    Intent openIntent = new Intent(c, MainActivity.class)
        .putExtra("page", "settings")
        .putExtra("open_updater", true);
    PendingIntent open = PendingIntent.getActivity(
        c, 2003, openIntent,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    String title = "Focus update ready · " + manifest.buildId;
    String body = manifest.commitSubject.isEmpty()
        ? "Tap to install the latest nightly build."
        : manifest.commitSubject;
    Notification n = new Notification.Builder(c, CHANNEL_UPDATES)
        .setSmallIcon(R.drawable.ic_stat_focus)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(new Notification.BigTextStyle().bigText(body))
        .setAutoCancel(true)
        .setContentIntent(open)
        .build();
    nm.notify(2003, n);
  }

  static void sendPauseRating(Context c, InsightsEngine.PauseRecord endedPause) {
    Store store = Store.get(c);
    try {
      NotificationManager nm = c.getSystemService(NotificationManager.class);
      if (nm == null || !nm.areNotificationsEnabled()) return;
      nm.createNotificationChannel(new NotificationChannel(
          CHANNEL_PAUSE_RATING, "Pause ratings", NotificationManager.IMPORTANCE_LOW));
      long now = System.currentTimeMillis();
      if (!PausePolicy.ratingOpen(endedPause.ended, now)) return;
      String body = "";
      if (store.policyConfig().notifyHonesty) {
        List<InsightsEngine.SpanRecord> spans = DeviceHub.get(c).combinedSpans(endedPause.started, endedPause.ended);
        InsightsEngine.PauseHonesty honesty =
            InsightsEngine.evaluatePauseHonesty(endedPause, spans, store.userRules(), now);
        body = endedPause.reason + " · " + honesty.summary;
        if (!endedPause.afterPlan.isEmpty()) body += " · " + endedPause.afterPlan;
      }
      String tag = ratingTag(endedPause.started, endedPause.ended);
      PendingIntent open = PendingIntent.getActivity(c, PAUSE_RATING_ID,
          new Intent(c, MainActivity.class)
              .setData(Uri.parse("focus://pause-rating/" + tag))
              .putExtra("rate_pause_started", endedPause.started)
              .putExtra("rate_pause_ended", endedPause.ended),
          PendingIntent.FLAG_IMMUTABLE);
      Notification.Builder rating = new Notification.Builder(c, CHANNEL_PAUSE_RATING)
          .setSmallIcon(R.drawable.ic_stat_focus)
          .setContentTitle("Was that worth it?")
          .setAutoCancel(true)
          .setLocalOnly(true)
          .setTimeoutAfter(endedPause.ended + PausePolicy.RATING_WINDOW_MS - now)
          .setContentIntent(open)
          .addAction(new Notification.Action.Builder(null, "Rate", open).build());
      if (!body.isEmpty()) rating.setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body));
      nm.notify(tag, PAUSE_RATING_ID, rating.build());
    } catch (RuntimeException e) {
      store.diagnostic("pause_rating_notification_failed");
    }
  }

  private static String ratingTag(long started, long ended) {
    return started + "/" + ended;
  }

  static void dismissRating(Context c, long started, long ended) {
    NotificationManager nm = c.getSystemService(NotificationManager.class);
    if (nm != null) nm.cancel(ratingTag(started, ended), PAUSE_RATING_ID);
  }

  public static void sendEveningSummary(Context c) {
    Store store = Store.get(c);
    if (!store.policyConfig().notifyEvening) return;
    long now = System.currentTimeMillis();
    long start = InsightsEngine.dayStart(now, TimeZone.getDefault()) - 86_400_000L;
    InsightsEngine.TodaySummary sum = InsightsEngine.computeToday(
        DeviceHub.get(c).combinedSpans(start, now),
        DeviceHub.get(c).combinedPauses(start, now),
        store.userRules(),
        now,
        TimeZone.getDefault());
    if (sum.creatingMs + sum.consumingMs < 60_000L && sum.pauseRatingSummary.isEmpty()) return;
    String title = sum.heroLine1 + " " + sum.heroLine2;
    String body = (sum.widgetSubtitle + " " + sum.pauseRatingSummary).trim();
    send(c, 2002, title, body);
  }

  public static void scheduleEveningSummary(Context c) {
    AlarmManager am = c.getSystemService(AlarmManager.class);
    if (am == null) return;
    PendingIntent pi = PendingIntent.getBroadcast(
        c, 733, new Intent(c, EveningSummaryReceiver.class),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    if (!Store.get(c).policyConfig().notifyEvening) {
      am.cancel(pi);
      return;
    }
    Calendar next = Calendar.getInstance();
    next.set(Calendar.HOUR_OF_DAY, 22);
    next.set(Calendar.MINUTE, 0);
    next.set(Calendar.SECOND, 0);
    next.set(Calendar.MILLISECOND, 0);
    if (next.getTimeInMillis() <= System.currentTimeMillis()) next.add(Calendar.DAY_OF_YEAR, 1);
    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
    Metric.SUMMARY_ALARM_SCHEDULED.count(1);
  }

  private static void send(Context c, int id, String title, String body) {
    NotificationManager nm = manager(c);
    if (nm == null) return;
    if (Build.VERSION.SDK_INT >= 33
        && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      return;
    }
    PendingIntent open = PendingIntent.getActivity(
        c, id, new Intent(c, MainActivity.class),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification n = new Notification.Builder(c, CHANNEL_INSIGHTS)
        .setSmallIcon(R.drawable.ic_stat_focus)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(new Notification.BigTextStyle().bigText(body))
        .setAutoCancel(true)
        .setContentIntent(open)
        .build();
    nm.notify(id, n);
  }
}
