package org.nightingalelab.focus.tv;

/** Ordered event reconstruction. No idle timeout; screen-off closes counted time. */
public final class UsageTimeline {
  public interface Sink {
    void add(long start, long end, String pkg);
  }

  public long cursor;
  public String pkg = "", cls = "";
  public boolean interactive;
  private boolean screenKnown;

  public UsageTimeline(long cursor, boolean interactive) {
    this.cursor = cursor;
    this.interactive = interactive;
    screenKnown = true;
  }

  /** Historical replay has no live power reading. A resume can establish the initial state. */
  public static UsageTimeline historical(long cursor) {
    UsageTimeline timeline = new UsageTimeline(cursor, false);
    timeline.screenKnown = false;
    return timeline;
  }

  public void advance(long end, Sink sink) {
    if (end <= cursor) return;
    if (interactive && !pkg.isEmpty()) sink.add(cursor, end, pkg);
    cursor = end;
  }

  public void event(long when, int type, String name, String activity, boolean tracked, Sink sink) {
    if (when < cursor) return;
    advance(when, sink);
    replay(type, name, activity, tracked);
  }

  /** Reapply late metadata at the cursor without importing an old duration. */
  public void replay(int type, String name, String activity, boolean tracked) {
    name = name == null ? "" : name;
    activity = activity == null ? "" : activity;
    if (type == 1) {
      if (!screenKnown) {
        interactive = true;
        screenKnown = true;
      }
      pkg = tracked ? name : "";
      cls = tracked ? activity : "";
    } else if ((type == 2 || type == 23)
        && name.equals(pkg)
        && (activity.equals(cls) || activity.isEmpty())) {
      pkg = "";
      cls = "";
    } else if (type == 16 || type == 17) {
      interactive = false;
      screenKnown = true;
    } else if (type == 15 || type == 18) {
      interactive = true;
      screenKnown = true;
    } else if (type == 26 || type == 27) {
      pkg = "";
      cls = "";
      interactive = false;
      screenKnown = type == 26;
    }
  }
}
