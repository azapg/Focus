package org.nightingalelab.focus.phone;

import java.util.*;

/**
 * Extracts a clean, privacy-safe domain from a mobile browser address bar string.
 * Rejects search queries, incomplete typing, local/internal browser pages, and query parameters.
 */
public final class BrowserUrlParser {
  public static final class Parsed {
    public final String host;
    public final String pathHint;

    public Parsed(String host, String pathHint) {
      this.host = host;
      this.pathHint = pathHint;
    }

    public String storageKey() {
      return "site:" + host;
    }
  }

  private static final class Toolbar {
    final Set<String> addressViews;
    final Set<String> nonPageViews;

    Toolbar(String[] addressViews, String[] nonPageViews) {
      this.addressViews = new HashSet<>(Arrays.asList(addressViews));
      this.nonPageViews = new HashSet<>(Arrays.asList(nonPageViews));
    }
  }

  private static final Map<String, Toolbar> BROWSERS = new HashMap<>();
  static {
    register(new String[] {
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "org.chromium.chrome", "com.brave.browser", "com.brave.browser_beta",
        "com.brave.browser_nightly", "com.microsoft.emmx", "com.microsoft.emmx.beta",
        "com.microsoft.emmx.dev", "com.microsoft.emmx.canary",
        "com.vivaldi.browser", "com.vivaldi.browser.snapshot"
    }, new String[] {"url_bar"}, "new_tab_page", "incognito_new_tab_page",
        "tab_list_recycler_view", "tab_list_view");
    register(new String[] {"com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta"},
        new String[] {"location_bar_edit_text", "compact_url_text", "url_bar"},
        "quickaccess_main_layout", "quickaccess_dummy_url_bar_text", "tab_manager_bottom_secret_button");
    register(new String[] {
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
        "org.mozilla.focus", "org.mozilla.klar"
    }, new String[] {"mozac_browser_toolbar_url_view", "url_bar_title"},
        "homepageView", "homeLayout", "tab_tray");
    register(new String[] {"com.opera.browser", "com.opera.browser.beta",
        "com.opera.mini.native", "com.opera.mini.native.beta", "com.opera.gx"},
        new String[] {"url_field", "url_bar", "address_bar_edit_text"});
    register(new String[] {"com.duckduckgo.mobile.android"}, new String[] {"omnibarTextInput"},
        "new_tab_page");
  }

  private static void register(String[] packages, String[] addressViews, String... nonPageViews) {
    Toolbar toolbar = new Toolbar(addressViews, nonPageViews);
    for (String pkg : packages) BROWSERS.put(pkg, toolbar);
  }

  public static boolean isBrowser(String pkg) {
    return BROWSERS.containsKey(pkg);
  }

  public static boolean isAddressBar(String pkg, String viewId) {
    Toolbar toolbar = BROWSERS.get(pkg);
    return toolbar != null && toolbar.addressViews.contains(viewName(viewId));
  }

  public static boolean isNonPageView(String pkg, String viewId) {
    Toolbar toolbar = BROWSERS.get(pkg);
    return toolbar != null && toolbar.nonPageViews.contains(viewName(viewId));
  }

  public static String tabStripItemViewId(String tabListId) {
    String name = viewName(tabListId);
    if (!name.equals("tab_list_recycler_view") && !name.equals("tab_list_view")) return null;
    // The group strip shares the grid's list ID but has its own tab button.
    return tabListId.substring(0, tabListId.lastIndexOf('/') + 1) + "tab_strip_item_button";
  }

  // A browser variant can use its base app's resource namespace.
  private static String viewName(String viewId) {
    return viewId == null ? "" : viewId.substring(viewId.lastIndexOf('/') + 1);
  }

  /**
   * Parses raw address bar text such as "m.youtube.com/watch?v=abc",
   * "https://www.reddit.com/r/android", or "github.com/example-org/sample-project".
   * Returns null for search queries, internal pages, or invalid text.
   */
  public static Parsed parse(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.isEmpty() || s.length() > 512) return null;
    if (s.indexOf(' ') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\t') >= 0) return null;
    String lower = s.toLowerCase(Locale.ROOT);
    int schemeIdx = lower.indexOf("://");
    if (schemeIdx >= 0 && !lower.startsWith("http://") && !lower.startsWith("https://")) return null;
    if (lower.startsWith("about:") || lower.startsWith("javascript:")
        || lower.startsWith("data:") || lower.startsWith("intent:")) {
      return null;
    }
    String rest = schemeIdx >= 0 ? s.substring(schemeIdx + 3) : s;
    if (rest.isEmpty()) return null;
    int slashIdx = rest.indexOf('/');
    int queryIdx = rest.indexOf('?');
    int hashIdx = rest.indexOf('#');
    int endHost = rest.length();
    if (slashIdx >= 0 && slashIdx < endHost) endHost = slashIdx;
    if (queryIdx >= 0 && queryIdx < endHost) endHost = queryIdx;
    if (hashIdx >= 0 && hashIdx < endHost) endHost = hashIdx;
    String authority = rest.substring(0, endHost);
    // Strip userinfo if ever present.
    int atIdx = authority.lastIndexOf('@');
    if (atIdx >= 0) authority = authority.substring(atIdx + 1);
    // Strip port.
    int colonIdx = authority.lastIndexOf(':');
    if (colonIdx > 0 && authority.indexOf(']') < colonIdx) {
      authority = authority.substring(0, colonIdx);
    }
    String host = authority.toLowerCase(Locale.ROOT);
    if (host.startsWith("www.")) host = host.substring(4);
    if (host.startsWith("m.") && host.indexOf('.', 2) > 0) host = host.substring(2);
    if (host.startsWith("mobile.") && host.indexOf('.', 7) > 0) host = host.substring(7);
    if (host.isEmpty() || host.length() > 253) return null;
    if (host.equals("localhost")) return new Parsed("localhost", "");
    int dot = host.lastIndexOf('.');
    if (dot <= 0 || dot >= host.length() - 2) return null;
    String tld = host.substring(dot + 1);
    if (!tld.matches("[a-z]{2,24}") && !tld.matches("[0-9]+")) return null;
    if (!host.matches("[a-z0-9.-]+") || host.contains("..") || host.startsWith(".") || host.startsWith("-")) {
      return null;
    }
    String path = "";
    if (slashIdx >= 0) {
      int endPath = rest.length();
      if (queryIdx >= 0 && queryIdx < endPath) endPath = queryIdx;
      if (hashIdx >= 0 && hashIdx < endPath) endPath = hashIdx;
      if (endPath > slashIdx) path = rest.substring(slashIdx, Math.min(endPath, slashIdx + 64));
    }
    return new Parsed(host, path);
  }
}
