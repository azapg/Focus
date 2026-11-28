package org.nightingalelab.focus.tv;

/** Pure copy and redirect selection for the Apple TV home and quiet-redirect block screens. */
public final class TvRedirect {
  private static final String[] IDLE_HEADLINES = {
    "Nothing to watch here. Go read or focus.",
    "TV is quiet. Pick one thing to focus on.",
    "Go read a chapter or work on your laptop."
  };

  public static String dnsStatusBadge(String status) {
    if (status == null || status.trim().isEmpty()) return "NextDNS";
    String clean = status.trim();
    return clean.startsWith("NextDNS") ? clean : "NextDNS " + clean;
  }

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
      if (!clean.isEmpty()) return clean + ".";
    }
    int idx = Math.floorMod(daySeed, IDLE_HEADLINES.length);
    return IDLE_HEADLINES[idx];
  }

  public static String blockSubhead(String dueHabitName, boolean hasAnyHabits) {
    if (dueHabitName != null && !dueHabitName.trim().isEmpty()) {
      return dueHabitName.trim() + " first, or pick up your book.";
    }
    if (hasAnyHabits) {
      return "All habits done. Go read a chapter or focus on your laptop.";
    }
    return "Go read a chapter, do a habit, or focus on your laptop.";
  }
}
