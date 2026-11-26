package org.nightingalelab.focus.phone;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings-only Emergency Shield rotating accountability phrases + 1/24h offline cooldown.
 * No password setup required; a fresh phrase is shown every time and paste is disabled.
 */
public final class EmergencyShieldStore {
  public static final int SHIELD_MINUTES = 10;
  public static final long OFFLINE_COOLDOWN_MS = 24L * 60 * 60_000L;

  public static final String[] PHRASES = {
      "I know I have to be focused to achieve my goals",
      "This bypass is for a real block, not for a feed",
      "My attention is worth more than a quick scroll",
      "I build things that matter when I stay on task",
      "Short distractions quietly steal whole afternoons",
      "I will fix this block and get straight back to work",
      "Free time belongs in the Store, not in emergencies",
      "Protecting my focus today makes tomorrow easier",
      "I do not need a feed to handle a blocked tool",
      "Staying deliberate is how hard projects get shipped",
      "One honest minute beats ten minutes of drifting",
      "I am opening this only to unblock real work",
      "My future self depends on what I finish today",
      "Deep work is my real advantage and I protect it",
      "If this is just boredom, I should close this screen",
      "Real progress comes from finishing what I started",
      "I will not trade a strong day for a cheap impulse",
      "Unblock the tool, ignore the noise, keep moving",
      "Clarity comes from doing the work in front of me",
      "I choose deliberate breaks over sneaky workarounds",
      "Every clean hour compounds into something real",
      "This shield keeps distractions out while I fix a block",
      "Small compromises turn into lost days if I let them",
      "Focus first, earn free time cleanly after the work"
  };

  private final SharedPreferences prefs;

  public EmergencyShieldStore(Context context) {
    this.prefs = context.getApplicationContext()
        .getSharedPreferences("focus_emergency_shield", Context.MODE_PRIVATE);
  }

  public synchronized String currentPhrase() {
    int idx = prefs.getInt("phrase_idx", -1);
    if (idx < 0 || idx >= PHRASES.length) {
      return rotatePhrase();
    }
    return PHRASES[idx];
  }

  public synchronized String rotatePhrase() {
    List<Integer> recent = readRecent();
    List<Integer> eligible = new ArrayList<>();
    for (int i = 0; i < PHRASES.length; i++) {
      if (!recent.contains(i)) eligible.add(i);
    }
    int next = eligible.isEmpty()
        ? new SecureRandom().nextInt(PHRASES.length)
        : eligible.get(new SecureRandom().nextInt(eligible.size()));
    recent.add(next);
    while (recent.size() > 8) recent.remove(0);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < recent.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(recent.get(i));
    }
    prefs.edit().putInt("phrase_idx", next).putString("recent", sb.toString()).commit();
    return PHRASES[next];
  }

  public synchronized boolean verifyPhrase(String typed) {
    if (typed == null) return false;
    return normalize(typed).equals(normalize(currentPhrase()));
  }

  public synchronized long offlineCooldownLeftMs(long nowMs) {
    long last = prefs.getLong("last_offline_at", 0);
    if (last <= 0) return 0;
    return Math.max(0, (last + OFFLINE_COOLDOWN_MS) - nowMs);
  }

  public synchronized void recordOfflineUse(long nowMs) {
    prefs.edit().putLong("last_offline_at", nowMs).commit();
    rotatePhrase();
  }

  private List<Integer> readRecent() {
    List<Integer> list = new ArrayList<>();
    String raw = prefs.getString("recent", "");
    if (raw == null || raw.isEmpty()) return list;
    for (String part : raw.split(",")) {
      try {
        list.add(Integer.parseInt(part.trim()));
      } catch (NumberFormatException ignored) {}
    }
    return list;
  }

  private static String normalize(String text) {
    return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }
}
