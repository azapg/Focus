package org.nightingalelab.focus.tv;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Strict local guard for the tiny "Ask on TV" fallback button.
 * TV Ask never allows bare bypasses or recreational streaming requests; those belong in Store Free Time.
 */
public final class TvPauseJudge {
  public static final int MAX_TASK_MINUTES = 3;

  private static final Set<String> BYPASS_TOKENS = new HashSet<>(Arrays.asList(
      "full", "pause", "dns", "off", "disable", "turn", "stop", "open", "unlock",
      "unblock", "override", "bypass", "please", "pls", "now", "just", "quick",
      "short", "for", "a", "an", "the", "me", "give", "need", "want",
      "min", "mins", "minute", "minutes", "m", "hr", "hrs", "hour", "hours", "h"));

  private static final Set<String> RECREATIONAL_HINTS = new HashSet<>(Arrays.asList(
      "relax", "chill", "bored", "break", "scroll", "feed", "binge",
      "entertainment", "movie", "movies", "netflix", "show", "shows",
      "episode", "watch", "watching", "youtube", "twitch", "stream", "music"));

  public static final class Reply {
    public final boolean allowed;
    public final int minutes;
    public final String message;

    Reply(boolean allowed, int minutes, String message) {
      this.allowed = allowed;
      this.minutes = minutes;
      this.message = message;
    }
  }

  public static boolean isBareBypass(String text) {
    if (text == null) return true;
    String lower = text.trim().toLowerCase(Locale.ROOT);
    if (lower.isEmpty()) return true;
    String[] parts = lower.split("[^a-z0-9.]+");
    int count = 0;
    for (String part : parts) {
      String token = part.replaceAll("^\\.+|\\.+$", "");
      if (token.isEmpty()) continue;
      count++;
      boolean dur = token.matches("\\d+(?:m|min|mins|minute|minutes|h|hr|hrs|hour|hours)?");
      if (!dur && !BYPASS_TOKENS.contains(token)) return false;
    }
    return count > 0;
  }

  public static Reply evaluate(String text, long balanceCoins) {
    if (text == null || text.trim().length() < 4 || isBareBypass(text)) {
      return new Reply(false, 0,
          "Name a specific task, or use Store Free Time (" + balanceCoins + " coins).");
    }
    String lower = text.trim().toLowerCase(Locale.ROOT);
    String[] words = lower.split("[^a-z0-9]+");
    int nonEmpty = 0;
    boolean hasRec = false;
    for (String w : words) {
      if (w.isEmpty()) continue;
      nonEmpty++;
      if (RECREATIONAL_HINTS.contains(w)) hasRec = true;
    }
    if (hasRec || nonEmpty < 3) {
      return new Reply(false, 0,
          "Entertainment requires Store Free Time (" + balanceCoins + " coins) or Ask on phone.");
    }
    return new Reply(false, 0,
        "Ask on your phone so the assistant can review this TV request.");
  }
}
