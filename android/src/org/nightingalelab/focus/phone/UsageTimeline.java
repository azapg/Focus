package org.nightingalelab.focus.phone;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Locale;

/** Ordered event reconstruction, pairing tokens, and approval-v2 commit-reveal math. */
public final class UsageTimeline {
  public interface Sink {
    void add(long start, long end, String pkg, String domain);
  }

  public long cursor;
  public String pkg = "", cls = "", domain = "";
  public boolean interactive;

  public UsageTimeline(long cursor, boolean interactive) {
    this.cursor = cursor;
    this.interactive = interactive;
  }

  public void advance(long end, Sink sink) {
    if (end <= cursor) return;
    if (interactive && !pkg.isEmpty()) {
      String dom = BrowserUrlParser.isBrowser(pkg) ? domain : "";
      sink.add(cursor, end, pkg, dom);
    }
    cursor = end;
  }

  public void event(long when, int type, String name, String activity, boolean tracked, Sink sink) {
    if (when < cursor) return;
    advance(when, sink);
    replay(type, name, activity, tracked);
  }

  public void replay(int type, String name, String activity, boolean tracked) {
    name = name == null ? "" : name;
    activity = activity == null ? "" : activity;
    if (type == 1) { // ACTIVITY_RESUMED
      if (!name.equals(pkg)) domain = "";
      pkg = tracked ? name : "";
      cls = tracked ? activity : "";
    } else if ((type == 2 || type == 23)
        && name.equals(pkg)
        && (activity.equals(cls) || activity.isEmpty())) {
      pkg = "";
      cls = "";
      domain = "";
    } else if (type == 16 || type == 17) {
      interactive = false;
      domain = "";
    } else if (type == 15 || type == 18) {
      interactive = true;
    } else if (type == 26 || type == 27) {
      pkg = "";
      cls = "";
      domain = "";
      interactive = false;
    }
  }
}
