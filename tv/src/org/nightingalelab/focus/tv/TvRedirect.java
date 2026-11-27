package org.nightingalelab.focus.tv;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Pure copy and redirect selection for the Apple TV home and quiet-redirect block screens. */
public final class TvRedirect {
  private static final String[] IDLE_HEADLINES = {
    "Nothing to watch here. Go read or focus.",
    "TV is quiet. Pick one thing to focus on.",
    "Go read a chapter or work on your laptop."
  };

  private static final Set<String> LEADING_VERBS = new HashSet<>(Arrays.asList(
      "read", "write", "walk", "run", "stretch", "study", "meditate", "journal",
      "exercise", "practice", "review", "work", "code", "plan", "clean", "cook",
      "learn", "focus", "draw", "drink", "take", "call", "go"));

  public static String homeKicker(boolean permissionsMissing, String dueHabitName,
      boolean overdue, String dueMeta) {
    if (permissionsMissing) return "Setup needed";
    if (dueHabitName != null && !dueHabitName.trim().isEmpty()) {
      String suffix = dueMeta == null || dueMeta.trim().isEmpty() ? "" : " · " + dueMeta.trim();
      return (overdue ? "Overdue" : "Due today") + suffix;
    }
    return "All habits done today";
  }

  public static String homeHeadline(boolean permissionsMissing, String dueHabitName, int daySeed) {
    if (permissionsMissing) return "Finish setup to lock this TV.";
    if (dueHabitName != null && !dueHabitName.trim().isEmpty()) {
      String clean = dueHabitName.trim();
      if (clean.endsWith(".")) clean = clean.substring(0, clean.length() - 1).trim();
      String lower = clean.toLowerCase(Locale.ROOT);
      String firstWord = lower.split("\\s+")[0];
      if ("go".equals(firstWord)) {
        return Character.toUpperCase(clean.charAt(0)) + clean.substring(1) + ".";
      }
      if (LEADING_VERBS.contains(firstWord)) {
        String rest = Character.toLowerCase(clean.charAt(0)) + clean.substring(1);
        return "Go " + rest + ".";
      }
      return "Finish " + clean + ".";
    }
    int idx = Math.floorMod(daySeed, IDLE_HEADLINES.length);
    return IDLE_HEADLINES[idx];
  }

  public static String blockSubhead(String dueHabitName, boolean hasAnyHabits) {
    if (dueHabitName != null && !dueHabitName.trim().isEmpty()) {
      return "Go do " + dueHabitName.trim() + " first, or pick up your book.";
    }
    if (hasAnyHabits) {
      return "All habits done. Go read a chapter or focus on your laptop.";
    }
    return "Go read a chapter, do a habit, or focus on your laptop.";
  }
}
