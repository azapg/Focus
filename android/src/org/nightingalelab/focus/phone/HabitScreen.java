package org.nightingalelab.focus.phone;

import android.content.Context;
import android.os.SystemClock;
import java.util.*;
import org.nightingalelab.focus.habits.*;

final class HabitScreen {
  static Habits.Screen local(Context context, long now) {
    Store store = Store.get(context);
    if (!store.usageAllowed() || Store.heartbeat == 0 || SystemClock.elapsedRealtime() - Store.heartbeat > 30_000) return null;
    long from = Math.max(now - 32 * 60_000L, store.habitObservedFrom());
    List<long[]> spans = new ArrayList<>();
    for (InsightsEngine.SpanRecord span : store.localSpans(from, now)) spans.add(new long[] {span.s, span.e});
    return new Habits.Screen(from, now, spans);
  }

  static int away(Context context, long now) {
    Map<String, Habits.Screen> peers = HabitStore.get(context).screens();
    for (DeviceHub.PairedDevice device : DeviceHub.get(context).list()) {
      Habits.Screen direct = HabitStore.fromSnapshot(device.snapshot);
      Habits.Screen relayed = peers.get(device.id);
      if (relayed == null || (direct != null && relayed.to <= direct.to)) peers.put(device.id, direct);
    }
    peers.remove(LanController.get(context).deviceId());
    return Habits.away(now, local(context, now), new ArrayList<>(peers.values()));
  }

  static PausePolicy.Config pauseAllowance(Context context, long now) {
    PausePolicy.Config config = Store.get(context).policyConfig();
    config.dailyLimitMinutes += HabitStore.get(context).read().credit(Habits.day(now));
    return config;
  }

  static long offToday(Context context, long now) {
    long from = InsightsEngine.dayStart(now, TimeZone.getDefault());
    long total = 0;
    for (InsightsEngine.PauseRecord pause : DeviceHub.get(context).combinedPauses(from, now, true)) {
      total += Math.max(0, Math.min(now, pause.ended) - Math.max(from, pause.started));
    }
    return total;
  }
}
