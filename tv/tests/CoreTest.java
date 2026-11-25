import java.util.*;
import org.nightingalelab.focus.tv.*;

public final class CoreTest {
  static int count;

  static void ok(boolean v, String message) {
    count++;
    if (!v) throw new AssertionError(message);
  }

  static final class Span {
    long a, b;
    String pkg;

    Span(long a, long b, String p) {
      this.a = a;
      this.b = b;
      pkg = p;
    }
  }

  public static void main(String[] args) {
    Rules r = new Rules();
    ok(!r.configured(), "New installs have no restrictions");
    ok(r.restriction(99999, 1320) == null, "No default block");
    r.blocked = true;
    ok("Blocked".equals(r.restriction(0, 0)), "Permanent block");
    r.blocked = false;
    r.dailyLimitMs = 60000;
    ok(r.restriction(59999, 0) == null, "Below allowance");
    ok(r.restriction(60000, 0) != null, "Exact allowance exhausted");
    r.dailyLimitMs = 0;
    r.scheduled = true;
    for (int minute : new int[] {1320, 1439, 0, 419})
      ok(r.inSchedule(minute), "Overnight within " + minute);
    for (int minute : new int[] {420, 800, 1319})
      ok(!r.inSchedule(minute), "Overnight outside " + minute);
    r.fromMinute = 600;
    r.toMinute = 720;
    ok(r.inSchedule(600), "Day start inclusive");
    ok(!r.inSchedule(599), "Before day start");
    ok(r.inSchedule(719), "Last day minute");
    ok(!r.inSchedule(720), "Day end exclusive");
    r.toMinute = 600;
    ok(r.inSchedule(0) && r.inSchedule(900), "Equal endpoints all day");
    ok(Rules.exceptionValid(5000, 4, 4, 4999), "Before exception deadline");
    ok(!Rules.exceptionValid(5000, 4, 4, 5000), "Exact expiry");
    ok(!Rules.exceptionValid(5000, 4, 5, 100), "Reboot invalidates exception");
    ok(!Rules.exceptionValid(5000, -1, -1, 100), "Unknown boot fails closed");
    ok(Rules.deadline(1000, 5000) == 6000, "Elapsed deadline");
    for (long d : new long[] {0, -1, 1800001, Long.MAX_VALUE}) {
      boolean threw = false;
      try {
        Rules.deadline(1000, d);
      } catch (IllegalArgumentException e) {
        threw = true;
      }
      ok(threw, "Invalid pause rejected " + d);
    }
    boolean overflow = false;
    try {
      Rules.deadline(Long.MAX_VALUE - 1, 5000);
    } catch (IllegalArgumentException e) {
      overflow = true;
    }
    ok(overflow, "Overflow rejected");
    List<Span> spans = new ArrayList<>();
    UsageTimeline.Sink sink = (a, b, p) -> spans.add(new Span(a, b, p));
    UsageTimeline t = new UsageTimeline(1000, true);
    t.advance(2000, sink);
    ok(spans.isEmpty(), "No guessed initial foreground");
    t.event(2000, 1, "A", "one", true, sink);
    t.event(3000, 1, "B", "main", true, sink);
    t.event(4000, 2, "A", "one", true, sink);
    t.advance(5000, sink);
    ok(
        spans.stream().mapToLong(s -> s.b - s.a).sum() == 3000,
        "One foreground stream, no double count");
    ok(t.pkg.equals("B"), "Late pause from old app ignored");
    t.event(5000, 16, "", "", false, sink);
    t.advance(8000, sink);
    ok(spans.stream().mapToLong(s -> s.b - s.a).sum() == 3000, "Screen off excluded");
    t.event(8000, 15, "", "", false, sink);
    t.advance(9000, sink);
    ok(spans.stream().mapToLong(s -> s.b - s.a).sum() == 4000, "Foreground resumes on screen on");
    t.event(9000, 1, "B", "two", true, sink);
    t.event(9500, 2, "B", "main", true, sink);
    ok(t.pkg.equals("B"), "Old activity pause cannot close new activity");
    t.event(10000, 2, "B", "two", true, sink);
    t.advance(11000, sink);
    ok(t.pkg.isEmpty(), "Matching activity pause closes span");
    t.event(11000, 1, "settings", "main", false, sink);
    t.advance(12000, sink);
    ok(t.pkg.isEmpty(), "Untracked system activity excluded");
    long cursor = t.cursor;
    t.event(11999, 1, "A", "one", true, sink);
    ok(t.cursor == cursor && t.pkg.isEmpty(), "Old event cannot rewind time");
    t.event(12000, 1, "A", "one", true, sink);
    t.event(12500, 26, "", "", false, sink);
    t.advance(18000, sink);
    ok(!t.interactive && t.pkg.isEmpty(), "Shutdown closes foreground");
    long previous = 0;
    for (Span s : spans) {
      ok(s.b > s.a, "Positive span");
      ok(s.a >= previous, "Nonoverlapping spans");
      previous = s.b;
    }
    List<Span> late = new ArrayList<>();
    UsageTimeline.Sink lateSink = (a, b, pkg) -> late.add(new Span(a, b, pkg));
    UsageTimeline recovered = new UsageTimeline(10000, true);
    recovered.replay(1, "A", "main", true);
    ok(recovered.cursor == 10000 && late.isEmpty(), "Late metadata never imports historical time");
    recovered.advance(11000, lateSink);
    ok(
        late.size() == 1 && late.get(0).a == 10000 && late.get(0).b == 11000,
        "Late resume recovers subsequent usage");
    recovered.replay(1, "A", "main", true);
    recovered.advance(12000, lateSink);
    ok(
        late.stream().mapToLong(span -> span.b - span.a).sum() == 2000,
        "Overlapping event query cannot double count");
    recovered.replay(2, "A", "old", true);
    ok(recovered.pkg.equals("A"), "Replayed old activity pause respects current class");
    recovered.replay(2, "A", "main", true);
    recovered.advance(13000, lateSink);
    ok(
        late.stream().mapToLong(span -> span.b - span.a).sum() == 2000,
        "Late pause stops subsequent accounting");
    List<Span> missed = new ArrayList<>();
    UsageTimeline.Sink history = (a, b, pkg) -> missed.add(new Span(a, b, pkg));
    UsageTimeline afterBoot = UsageTimeline.historical(0);
    afterBoot.event(100, 15, "", "", false, history);
    afterBoot.event(100, 26, "", "", false, history);
    afterBoot.event(100, 27, "", "", false, history);
    afterBoot.event(200, 1, "youtube", "shell", true, history);
    afterBoot.event(200, 2, "youtube", "shell", true, history);
    afterBoot.event(200, 1, "youtube", "main", true, history);
    afterBoot.event(201, 23, "youtube", "shell", true, history);
    afterBoot.event(1_940_200, 2, "youtube", "main", true, history);
    ok(missed.stream().mapToLong(span -> span.b - span.a).sum() == 1_940_000,
        "Recover the completed long session after TV startup");
    afterBoot.event(1_940_300, 16, "", "", false, history);
    afterBoot.event(1_940_400, 1, "youtube", "main", true, history);
    afterBoot.event(1_950_000, 2, "youtube", "main", true, history);
    ok(missed.stream().mapToLong(span -> span.b - span.a).sum() == 1_940_000,
        "A resume cannot override explicit screen off");
    System.out.println("PASS " + count + " policy and timeline assertions");
  }
}
