package org.nightingalelab.focus.phone;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Classifies phone apps, mobile browser domains, TV packages, and desktop apps into
 * Creating vs Consuming (mirroring ui/activity-classifier.js) and checks pause honesty.
 */
public final class ActivityClassifier {
  public static final String GROUP_CREATING = "creating";
  public static final String GROUP_CONSUMING = "consuming";
  public static final String GROUP_LEARN = "learn";
  public static final String GROUP_OTHER = "other";
  public static final String GROUP_UNKNOWN = "unknown";

  public static final class UserRule {
    public final String match; // "package", "host", or "app"
    public final String value;
    public final String app;
    public final String category;

    public UserRule(String match, String value, String app, String category) {
      this.match = match == null ? "" : match.toLowerCase(Locale.ROOT);
      this.value = value == null ? "" : value.trim();
      this.app = app == null ? "" : app.trim();
      this.category = category == null ? "" : category.toLowerCase(Locale.ROOT);
    }
  }

  public static final class Result {
    public final String app;
    public final String category;
    public final String group;
    public final boolean noise;
    public final String matchType;
    public final String matchValue;
    public final boolean userRule;

    public Result(String app, String category, boolean noise, String matchType, String matchValue, boolean userRule) {
      this.app = app;
      this.category = category;
      this.group = groupForCategory(category);
      this.noise = noise;
      this.matchType = matchType;
      this.matchValue = matchValue;
      this.userRule = userRule;
    }
  }

  private static final class Entry {
    final String app;
    final String category;
    final boolean noise;

    Entry(String app, String category) {
      this(app, category, false);
    }

    Entry(String app, String category, boolean noise) {
      this.app = app;
      this.category = category;
      this.noise = noise;
    }
  }

  private static final Map<String, Entry> PACKAGES = new HashMap<>();
  private static final Map<String, Entry> HOSTS = new HashMap<>();
  private static final Map<String, Entry> DESKTOP_APPS = new HashMap<>();
  private static final String HOST_LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
  private static final Pattern HOSTNAME = Pattern.compile(HOST_LABEL + "(?:\\." + HOST_LABEL + ")+");
  private static final Map<String, String[]> PAUSE_HINTS = new HashMap<>();
  private static final Set<String> SYSTEM_NOISE = new HashSet<>(Arrays.asList(
      "searchhost", "applicationframehost", "shellhost", "pickerhost", "shellexperiencehost",
      "startmenuexperiencehost", "officeclicktorun", "credentialuibroker", "werfault",
      "yourphoneappproxyhost", "lockapp", "unknown", "focusdns",
      "org.nightingalelab.focus.phone", "org.nightingalelab.focus.tv"
  ));

  static {
    // Phone & TV Android packages
    // Creating
    PACKAGES.put("com.anthropic.claude", new Entry("Claude", "building"));
    PACKAGES.put("com.openai.chatgpt", new Entry("ChatGPT", "building"));
    PACKAGES.put("com.github.android", new Entry("GitHub", "building"));
    PACKAGES.put("com.termux", new Entry("Termux", "building"));
    PACKAGES.put("com.t3tools.t3code", new Entry("T3 Code", "building"));
    PACKAGES.put("md.obsidian", new Entry("Obsidian", "writing"));
    PACKAGES.put("notion.id", new Entry("Notion", "writing"));
    PACKAGES.put("com.google.android.apps.docs", new Entry("Google Docs", "writing"));
    PACKAGES.put("com.google.android.apps.docs.editors.docs", new Entry("Google Docs", "writing"));
    PACKAGES.put("com.google.android.apps.docs.editors.sheets", new Entry("Google Sheets", "writing"));
    PACKAGES.put("com.google.android.keep", new Entry("Google Keep", "writing"));
    PACKAGES.put("com.samsung.android.app.notes", new Entry("Samsung Notes", "writing"));
    PACKAGES.put("com.figma.mirror", new Entry("Figma", "designing"));
    PACKAGES.put("com.canva.editor", new Entry("Canva", "designing"));
    PACKAGES.put("com.anki.android", new Entry("Anki", "writing"));
    PACKAGES.put("com.ichi2.anki", new Entry("Anki", "writing"));

    // Reading and learning
    PACKAGES.put("com.bilingify.readest", new Entry("Readest", "reading"));
    PACKAGES.put("com.duolingo", new Entry("Duolingo", "learning"));

    // Consuming (Phone)
    PACKAGES.put("com.google.android.videos", new Entry("Google TV", "watching"));
    PACKAGES.put("com.instagram.android", new Entry("Instagram", "browsing"));
    PACKAGES.put("com.twitter.android", new Entry("X", "browsing"));
    PACKAGES.put("com.reddit.frontpage", new Entry("Reddit", "browsing"));
    PACKAGES.put("com.zhiliaoapp.musically", new Entry("TikTok", "watching"));
    PACKAGES.put("com.ss.android.ugc.trill", new Entry("TikTok", "watching"));
    PACKAGES.put("com.google.android.youtube", new Entry("YouTube", "watching"));
    PACKAGES.put("com.google.android.apps.youtube.music", new Entry("YouTube Music", "listening"));
    PACKAGES.put("com.netflix.mediaclient", new Entry("Netflix", "watching"));
    PACKAGES.put("tv.twitch.android.app", new Entry("Twitch", "watching"));
    PACKAGES.put("com.disney.disneyplus", new Entry("Disney+", "watching"));
    PACKAGES.put("com.amazon.avod.thirdpartyclient", new Entry("Prime Video", "watching"));
    PACKAGES.put("com.wbd.stream", new Entry("HBO Max", "watching"));
    PACKAGES.put("com.spotify.music", new Entry("Spotify", "listening"));
    PACKAGES.put("com.whatsapp", new Entry("WhatsApp", "messaging"));
    PACKAGES.put("com.whatsapp.w4b", new Entry("WhatsApp Business", "messaging"));
    PACKAGES.put("com.discord", new Entry("Discord", "messaging"));
    PACKAGES.put("org.telegram.messenger", new Entry("Telegram", "messaging"));
    PACKAGES.put("com.Slack", new Entry("Slack", "messaging"));
    PACKAGES.put("com.google.android.gm", new Entry("Gmail", "messaging"));
    PACKAGES.put("com.microsoft.office.outlook", new Entry("Outlook", "messaging"));
    PACKAGES.put("com.facebook.katana", new Entry("Facebook", "browsing"));
    PACKAGES.put("com.facebook.orca", new Entry("Messenger", "messaging"));
    PACKAGES.put("com.snapchat.android", new Entry("Snapchat", "messaging"));
    PACKAGES.put("com.instagram.barcelona", new Entry("Threads", "browsing"));
    PACKAGES.put("xyz.blueskyweb.app", new Entry("Bluesky", "browsing"));
    PACKAGES.put("com.linkedin.android", new Entry("LinkedIn", "browsing"));
    PACKAGES.put("com.pinterest", new Entry("Pinterest", "browsing"));

    // TV packages (mirrors ui/activity-classifier.js)
    PACKAGES.put("com.google.android.youtube.tv", new Entry("YouTube", "watching"));
    PACKAGES.put("com.google.android.youtube.tvkids", new Entry("YouTube Kids", "watching"));
    PACKAGES.put("com.google.android.youtube.tvunplugged", new Entry("YouTube TV", "watching"));
    PACKAGES.put("com.google.android.youtube.tvmusic", new Entry("YouTube Music", "listening"));
    PACKAGES.put("com.netflix.ninja", new Entry("Netflix", "watching"));
    PACKAGES.put("com.spotify.tv.android", new Entry("Spotify", "listening"));
    PACKAGES.put("com.apple.atve.androidtv.appletv", new Entry("Apple TV", "watching"));
    PACKAGES.put("com.amazon.amazonvideo.livingroom", new Entry("Prime Video", "watching"));
    PACKAGES.put("com.hbo.hbonow", new Entry("HBO Max", "watching"));
    PACKAGES.put("com.hulu.livingroomplus", new Entry("Hulu", "watching"));
    PACKAGES.put("com.crunchyroll.crunchyroid", new Entry("Crunchyroll", "watching"));
    PACKAGES.put("com.stremio.one", new Entry("Stremio", "watching"));
    PACKAGES.put("com.tcl.tv", new Entry("Live TV", "watching"));
    PACKAGES.put("com.tcl.dashboard", new Entry("TCL Home", "system", true));
    PACKAGES.put("com.tcl.suspension", new Entry("Quick Panel", "system", true));
    PACKAGES.put("com.tcl.guard", new Entry("Safety Guard", "system", true));
    PACKAGES.put("com.tcl.t_solo", new Entry("T-Solo", "system", true));

    // Utilities / System
    PACKAGES.put("tech.simha.androidtvremote", new Entry("Remote ATV", "system"));
    PACKAGES.put("com.google.android.calendar", new Entry("Google Calendar", "system"));
    PACKAGES.put("com.samsung.android.calendar", new Entry("Calendar", "system"));
    PACKAGES.put("com.google.android.apps.maps", new Entry("Google Maps", "system"));
    PACKAGES.put("com.android.vending", new Entry("Play Store", "system"));
    PACKAGES.put("com.google.android.apps.photos", new Entry("Photos", "system"));
    PACKAGES.put("com.sec.android.gallery3d", new Entry("Gallery", "system"));
    PACKAGES.put("com.google.android.dialer", new Entry("Phone", "system"));
    PACKAGES.put("com.samsung.android.dialer", new Entry("Phone", "system"));
    PACKAGES.put("com.google.android.apps.messaging", new Entry("Messages", "messaging"));
    PACKAGES.put("com.samsung.android.messaging", new Entry("Messages", "messaging"));
    PACKAGES.put("com.android.chrome", new Entry("Chrome", "browsing"));
    PACKAGES.put("com.sec.android.app.sbrowser", new Entry("Samsung Internet", "browsing"));

    // Web Hosts
    HOSTS.put("web.readest.com", new Entry("Readest", "reading"));
    HOSTS.put("duolingo.com", new Entry("Duolingo", "learning"));
    HOSTS.put("github.com", new Entry("GitHub", "building"));
    HOSTS.put("claude.ai", new Entry("Claude", "building"));
    HOSTS.put("chatgpt.com", new Entry("ChatGPT", "building"));
    HOSTS.put("chat.openai.com", new Entry("ChatGPT", "building"));
    HOSTS.put("stackoverflow.com", new Entry("Stack Overflow", "building"));
    HOSTS.put("developer.mozilla.org", new Entry("MDN", "building"));
    HOSTS.put("developer.chrome.com", new Entry("Chrome Docs", "building"));
    HOSTS.put("developer.android.com", new Entry("Android Docs", "building"));
    HOSTS.put("docs.rs", new Entry("docs.rs", "building"));
    HOSTS.put("crates.io", new Entry("crates.io", "building"));
    HOSTS.put("npmjs.com", new Entry("npm", "building"));
    HOSTS.put("localhost", new Entry("Local Dev", "building"));
    HOSTS.put("notion.so", new Entry("Notion", "writing"));
    HOSTS.put("notion.com", new Entry("Notion", "writing"));
    HOSTS.put("docs.google.com", new Entry("Google Docs", "writing"));
    HOSTS.put("figma.com", new Entry("Figma", "designing"));
    HOSTS.put("youtube.com", new Entry("YouTube", "watching"));
    HOSTS.put("youtu.be", new Entry("YouTube", "watching"));
    HOSTS.put("twitch.tv", new Entry("Twitch", "watching"));
    HOSTS.put("netflix.com", new Entry("Netflix", "watching"));
    HOSTS.put("tiktok.com", new Entry("TikTok", "watching"));
    HOSTS.put("x.com", new Entry("X", "browsing"));
    HOSTS.put("twitter.com", new Entry("X", "browsing"));
    HOSTS.put("instagram.com", new Entry("Instagram", "browsing"));
    HOSTS.put("reddit.com", new Entry("Reddit", "browsing"));
    HOSTS.put("old.reddit.com", new Entry("Reddit", "browsing"));
    HOSTS.put("facebook.com", new Entry("Facebook", "browsing"));
    HOSTS.put("threads.net", new Entry("Threads", "browsing"));
    HOSTS.put("bsky.app", new Entry("Bluesky", "browsing"));
    HOSTS.put("linkedin.com", new Entry("LinkedIn", "browsing"));
    HOSTS.put("news.ycombinator.com", new Entry("Hacker News", "browsing"));
    HOSTS.put("google.com", new Entry("Google Search", "browsing"));
    HOSTS.put("mail.google.com", new Entry("Gmail", "messaging"));
    HOSTS.put("web.whatsapp.com", new Entry("WhatsApp", "messaging"));
    HOSTS.put("discord.com", new Entry("Discord", "messaging"));
    HOSTS.put("open.spotify.com", new Entry("Spotify", "listening"));
    HOSTS.put("spotify.com", new Entry("Spotify", "listening"));
    HOSTS.put("accounts.google.com", new Entry("Google Sign-in", "system", true));
    HOSTS.put("t.co", new Entry("Link redirect", "system", true));
    HOSTS.put("test.nextdns.io", new Entry("NextDNS Test", "system", true));

    // Desktop apps
    DESKTOP_APPS.put("t3 code", new Entry("T3 Code", "building"));
    DESKTOP_APPS.put("t3code", new Entry("T3 Code", "building"));
    DESKTOP_APPS.put("antigravity", new Entry("Antigravity", "building"));
    DESKTOP_APPS.put("vs code", new Entry("VS Code", "building"));
    DESKTOP_APPS.put("code", new Entry("VS Code", "building"));
    DESKTOP_APPS.put("zed", new Entry("Zed", "building"));
    DESKTOP_APPS.put("cursor", new Entry("Cursor", "building"));
    DESKTOP_APPS.put("terminal", new Entry("Terminal", "building"));
    DESKTOP_APPS.put("windowsterminal", new Entry("Terminal", "building"));
    DESKTOP_APPS.put("wt", new Entry("Terminal", "building"));
    DESKTOP_APPS.put("powershell", new Entry("PowerShell", "building"));
    DESKTOP_APPS.put("pwsh", new Entry("PowerShell", "building"));
    DESKTOP_APPS.put("cmd", new Entry("Command Prompt", "building"));
    DESKTOP_APPS.put("docker desktop", new Entry("Docker Desktop", "building"));
    DESKTOP_APPS.put("claude", new Entry("Claude", "building"));
    DESKTOP_APPS.put("chatgpt", new Entry("ChatGPT", "building"));
    DESKTOP_APPS.put("github", new Entry("GitHub", "building"));
    DESKTOP_APPS.put("obsidian", new Entry("Obsidian", "writing"));
    DESKTOP_APPS.put("notion", new Entry("Notion", "writing"));
    DESKTOP_APPS.put("word", new Entry("Word", "writing"));
    DESKTOP_APPS.put("winword", new Entry("Word", "writing"));
    DESKTOP_APPS.put("excel", new Entry("Excel", "writing"));
    DESKTOP_APPS.put("powerpnt", new Entry("PowerPoint", "designing"));
    DESKTOP_APPS.put("google docs", new Entry("Google Docs", "writing"));
    DESKTOP_APPS.put("figma", new Entry("Figma", "designing"));
    DESKTOP_APPS.put("photoshop", new Entry("Photoshop", "designing"));
    DESKTOP_APPS.put("blender", new Entry("Blender", "designing"));
    DESKTOP_APPS.put("chrome", new Entry("Chrome", "browsing"));
    DESKTOP_APPS.put("msedge", new Entry("Edge", "browsing"));
    DESKTOP_APPS.put("edge", new Entry("Edge", "browsing"));
    DESKTOP_APPS.put("firefox", new Entry("Firefox", "browsing"));
    DESKTOP_APPS.put("brave", new Entry("Brave", "browsing"));
    DESKTOP_APPS.put("arc", new Entry("Arc", "browsing"));
    DESKTOP_APPS.put("zen", new Entry("Zen", "browsing"));
    DESKTOP_APPS.put("youtube", new Entry("YouTube", "watching"));
    DESKTOP_APPS.put("netflix", new Entry("Netflix", "watching"));
    DESKTOP_APPS.put("twitch", new Entry("Twitch", "watching"));
    DESKTOP_APPS.put("tiktok", new Entry("TikTok", "watching"));
    DESKTOP_APPS.put("vlc", new Entry("VLC", "watching"));
    DESKTOP_APPS.put("steam", new Entry("Steam", "watching"));
    DESKTOP_APPS.put("epicgameslauncher", new Entry("Epic Games", "watching"));
    DESKTOP_APPS.put("instagram", new Entry("Instagram", "browsing"));
    DESKTOP_APPS.put("x", new Entry("X", "browsing"));
    DESKTOP_APPS.put("reddit", new Entry("Reddit", "browsing"));
    DESKTOP_APPS.put("whatsapp", new Entry("WhatsApp", "messaging"));
    DESKTOP_APPS.put("discord", new Entry("Discord", "messaging"));
    DESKTOP_APPS.put("slack", new Entry("Slack", "messaging"));
    DESKTOP_APPS.put("telegram", new Entry("Telegram", "messaging"));
    DESKTOP_APPS.put("messenger", new Entry("Messenger", "messaging"));
    DESKTOP_APPS.put("spotify", new Entry("Spotify", "listening"));
    DESKTOP_APPS.put("gmail", new Entry("Gmail", "messaging"));
    DESKTOP_APPS.put("outlook", new Entry("Outlook", "messaging"));
    DESKTOP_APPS.put("explorer", new Entry("Explorer", "system", true));
    DESKTOP_APPS.put("taskmgr", new Entry("Task Manager", "system", true));
    DESKTOP_APPS.put("systemsettings", new Entry("Settings", "system", true));

    // Pause reason matching hints
    DESKTOP_APPS.put("readest", new Entry("Readest", "reading"));
    DESKTOP_APPS.put("duolingo", new Entry("Duolingo", "learning"));
    DESKTOP_APPS.put("remote atv", new Entry("Remote ATV", "system"));
    DESKTOP_APPS.put("google tv", new Entry("Google TV", "watching"));

    PAUSE_HINTS.put("x", new String[] {"twitter", "tweet", " x ", "x.com", "post"});
    PAUSE_HINTS.put("youtube", new String[] {"youtube", "video", "yt", "talk", "watch", "tv", "lecture", "course", "movie"});
    PAUSE_HINTS.put("netflix", new String[] {"netflix", "episode", "watch", "tv", "movie", "show"});
    PAUSE_HINTS.put("twitch", new String[] {"twitch", "stream", "watch", "tv"});
    PAUSE_HINTS.put("discord", new String[] {"discord"});
    PAUSE_HINTS.put("reddit", new String[] {"reddit", "thread", "post"});
    PAUSE_HINTS.put("instagram", new String[] {"instagram", "reel", "ig", "dm"});
    PAUSE_HINTS.put("whatsapp", new String[] {"whatsapp", "message", "reply", "chat"});
    PAUSE_HINTS.put("tiktok", new String[] {"tiktok", "video"});
  }

  public static String groupForCategory(String cat) {
    if (cat == null) return GROUP_UNKNOWN;
    switch (cat.toLowerCase(Locale.ROOT)) {
      case "building":
      case "writing":
      case "designing":
        return GROUP_CREATING;
      case "reading":
      case "learning":
        return GROUP_LEARN;
      case "watching":
      case "browsing":
      case "messaging":
      case "listening":
        return GROUP_CONSUMING;
      case "system":
      case "ignore":
        return GROUP_OTHER;
      default:
        return GROUP_UNKNOWN;
    }
  }

  public static String prettyHost(String host) {
    if (host == null || host.isEmpty()) return "Web";
    String base = host.toLowerCase(Locale.ROOT);
    if (base.startsWith("www.")) base = base.substring(4);
    String[] parts = base.split("\\.");
    if (parts.length >= 2) {
      String root = parts[parts.length - 2];
      if (root.length() <= 3) return base;
      return Character.toUpperCase(root.charAt(0)) + root.substring(1);
    }
    return base;
  }

  public static Result classify(String rawApp, String rawLabel, String domain, List<UserRule> rules) {
    String app = rawApp == null ? "" : rawApp.trim();
    String label = (rawLabel == null || rawLabel.trim().isEmpty()) ? app : rawLabel.trim();
    String dom = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
    if (dom.startsWith("site:")) dom = dom.substring(5);
    if (app.startsWith("site:") && dom.isEmpty()) dom = app.substring(5).toLowerCase(Locale.ROOT);

    // 1. Browser domain (per-site mobile or desktop tracking)
    if (!dom.isEmpty()) {
      Entry known = findHost(dom);
      Result base = known != null
          ? new Result(known.app, known.category, known.noise, "host", dom, false)
          : new Result(prettyHost(dom), "unknown", false, "host", dom, false);
      return applyUserRule(base, findRule(rules, dom, "", base.app, label));
    }

    // 2. System noise
    String lowApp = app.toLowerCase(Locale.ROOT);
    String lowLabel = label.toLowerCase(Locale.ROOT);
    if (SYSTEM_NOISE.contains(lowApp) || SYSTEM_NOISE.contains(lowLabel)) {
      return new Result(label, "system", true, "app", app, false);
    }

    // 3. Known Android package (phone or TV)
    Entry pkgEntry = PACKAGES.get(app);
    if (pkgEntry == null) pkgEntry = PACKAGES.get(lowApp);
    if (pkgEntry != null) {
      Result base = new Result(pkgEntry.app, pkgEntry.category, pkgEntry.noise, "package", app, false);
      return applyUserRule(base, findRule(rules, "", app, base.app, label));
    }

    // Paired desktops may supply a known browser host only as the span label.
    if ((BROWSER_APPS.contains(lowApp) || BrowserUrlParser.isBrowser(app))
        && lowLabel.length() <= 253 && HOSTNAME.matcher(lowLabel).matches()) {
      String host = lowLabel.startsWith("www.") ? lowLabel.substring(4) : lowLabel;
      Entry known = host.equals("web.readest.com") || host.endsWith(".web.readest.com")
          ? HOSTS.get("web.readest.com")
          : host.equals("duolingo.com") || host.endsWith(".duolingo.com") ? HOSTS.get("duolingo.com") : null;
      if (known != null) {
        Result base = new Result(known.app, known.category, known.noise, "host", host, false);
        return applyUserRule(base, findRule(rules, lowLabel, "", base.app, label));
      }
    }

    // 4. Known desktop/generic app name
    Entry deskEntry = DESKTOP_APPS.get(lowApp);
    if (deskEntry == null) deskEntry = DESKTOP_APPS.get(lowLabel);
    if (deskEntry != null) {
      String matchType = app.contains(".") ? "package" : "app";
      Result base = new Result(deskEntry.app, deskEntry.category, deskEntry.noise, matchType, app, false);
      return applyUserRule(base, findRule(rules, "", app, base.app, label));
    }

    // 5. Fallback: Uncategorized (so user can sort it cleanly in Settings)
    String matchType = app.contains(".") ? "package" : "app";
    Result base = new Result(label.isEmpty() ? "Unknown" : label, "unknown", false, matchType, app.isEmpty() ? label : app, false);
    return applyUserRule(base, findRule(rules, "", app, base.app, label));
  }

  private static Entry findHost(String host) {
    Entry direct = HOSTS.get(host);
    if (direct != null) return direct;
    int dot = host.indexOf('.');
    while (dot > 0 && dot < host.length() - 1) {
      String parent = host.substring(dot + 1);
      Entry e = HOSTS.get(parent);
      if (e != null) return e;
      dot = host.indexOf('.', dot + 1);
    }
    return null;
  }

  private static UserRule findRule(List<UserRule> rules, String host, String pkg, String appName, String label) {
    if (rules == null || rules.isEmpty()) return null;
    String lowHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
    String lowPkg = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
    String lowApp = appName == null ? "" : appName.toLowerCase(Locale.ROOT);
    String lowLabel = label == null ? "" : label.toLowerCase(Locale.ROOT);
    String legacyApp = lowPkg.equals("com.google.android.videos") ? "play movies" : "";
    for (UserRule r : rules) {
      String v = r.value.toLowerCase(Locale.ROOT);
      if (r.match.equals("host") && !lowHost.isEmpty()) {
        if (lowHost.equals(v) || lowHost.endsWith("." + v)) return r;
      } else if (r.match.equals("package") && !lowPkg.isEmpty() && lowPkg.equals(v)) {
        return r;
      } else if (r.match.equals("app") && (lowApp.equals(v) || lowLabel.equals(v) || (!legacyApp.isEmpty() && legacyApp.equals(v)))) {
        return r;
      }
    }
    return null;
  }

  private static Result applyUserRule(Result base, UserRule rule) {
    if (rule == null) return base;
    boolean ignore = rule.category.equals("ignore");
    String cat = ignore ? "system" : rule.category;
    String appName = rule.app.isEmpty() ? base.app : rule.app;
    return new Result(appName, cat, ignore, base.matchType, base.matchValue, true);
  }

  // --- Fine categories (mirrors FINE / fineCategory in ui/activity-classifier.js) ---
  // Groups: create = building, drift = drifting, the rest are neutral for the headline.
  public static final String FG_CREATE = "create";
  public static final String FG_LEARN = "learn";
  public static final String FG_CONNECT = "connect";
  public static final String FG_DRIFT = "drift";
  public static final String FG_NEUTRAL = "neutral";

  public static final class Fine {
    public final String id;
    public final String label;
    public final String group;
    public final int color; // OLED palette: grays, amber shades only for drift

    Fine(String id, String label, String group, int color) {
      this.id = id;
      this.label = label;
      this.group = group;
      this.color = color;
    }
  }

  private static final Map<String, Fine> FINE = new LinkedHashMap<>();
  private static final Set<String> SOCIAL_APPS = new HashSet<>(Arrays.asList(
      "x", "reddit", "instagram", "facebook", "tiktok", "threads", "bluesky", "linkedin", "hacker news"));
  private static final Set<String> BROWSER_APPS = new HashSet<>(Arrays.asList(
      "chrome", "samsung internet", "edge", "firefox", "brave", "arc", "zen", "opera", "vivaldi", "duckduckgo"));

  private static void fine(String id, String label, String group, int color) {
    FINE.put(id, new Fine(id, label, group, color));
  }

  static {
    fine("coding", "Coding", FG_CREATE, 0xFFD4D4D8);
    fine("business", "Business", FG_CREATE, 0xFFC4C4CA);
    fine("writing", "Writing", FG_CREATE, 0xFFBDBDC4);
    fine("design", "Design", FG_CREATE, 0xFFA8A8B0);
    fine("learning", "Learning", FG_LEARN, 0xFFA1A1AA);
    fine("reading", "Reading", FG_LEARN, 0xFF8F8F98);
    fine("messaging", "Messaging", FG_CONNECT, 0xFF71717A);
    fine("social", "Social feeds", FG_DRIFT, 0xFF7C7C85);
    fine("video", "Videos", FG_DRIFT, 0xFF7C7C85);
    fine("tv", "TV & streams", FG_DRIFT, 0xFF7C7C85);
    fine("music", "Music", FG_DRIFT, 0xFF63636C);
    fine("games", "Games", FG_DRIFT, 0xFF7C7C85);
    fine("news", "News", FG_DRIFT, 0xFF52525B);
    fine("shopping", "Shopping", FG_DRIFT, 0xFF52525B);
    fine("web", "Web", FG_DRIFT, 0xFF52525B);
    fine("adult", "Adult", FG_DRIFT, 0xFF52525B);
    fine("errands", "Errands", FG_NEUTRAL, 0xFF52525B);
    fine("unsorted", "Not sure", FG_NEUTRAL, 0xFF3F3F46);
  }

  public static Fine fineById(String id) {
    Fine f = FINE.get(id);
    return f != null ? f : FINE.get("unsorted");
  }

  /**
   * Automatic fine category for a classified span. A browser with no known site counts as
   * "Not sure" rather than drift, because we can't see what was open.
   */
  public static Fine fineCategory(Result c, String deviceKind) {
    String app = c.app == null ? "" : c.app.toLowerCase(Locale.ROOT);
    if (!"host".equals(c.matchType) && ("browsing".equals(c.category) || "unknown".equals(c.category))
        && (BROWSER_APPS.contains(app) || BrowserUrlParser.isBrowser(c.matchValue))) {
      return FINE.get("unsorted");
    }
    String id;
    switch (c.category == null ? "" : c.category) {
      case "building": id = "coding"; break;
      case "writing": id = "writing"; break;
      case "reading":
      case "learning": id = c.category; break;
      case "designing": id = "design"; break;
      case "messaging": id = "messaging"; break;
      case "listening": id = "music"; break;
      case "system": id = "errands"; break;
      case "watching": id = "tv".equals(deviceKind) ? "tv" : "video"; break;
      case "browsing": id = SOCIAL_APPS.contains(app) ? "social" : "web"; break;
      default: id = "unsorted";
    }
    return FINE.get(id);
  }

  /** Known Android packages that share a display name (used to find a real app icon). */
  public static List<String> packagesForApp(String appName) {
    List<String> out = new ArrayList<>();
    if (appName == null) return out;
    for (Map.Entry<String, Entry> e : PACKAGES.entrySet()) {
      if (e.getValue().app.equalsIgnoreCase(appName)) out.add(e.getKey());
    }
    Collections.sort(out, (a, b) -> Integer.compare(a.length(), b.length())); // phone package before .tv variants
    return out;
  }

  // Reasons often name an activity instead of an app ("browse a little", "watch some tv").
  private static final Pattern PLAY_MOVIES = Pattern.compile("\\bplay movies\\b");
  private static final Map<String, String[]> CATEGORY_HINTS = new HashMap<>();

  static {
    CATEGORY_HINTS.put("browsing", new String[] {"browse", "browsing", "scroll", "search", "look up", "website"});
    CATEGORY_HINTS.put("watching", new String[] {"watch", "video", "tv", "movie", "show", "episode", "stream"});
    CATEGORY_HINTS.put("messaging", new String[] {"message", "chat", "reply", "text", "dm"});
    CATEGORY_HINTS.put("listening", new String[] {"music", "song", "listen", "podcast"});
  }

  public static boolean reasonMentionsCategory(String reason, String category) {
    if (reason == null || category == null) return false;
    String[] hints = CATEGORY_HINTS.get(category);
    if (hints == null) return false;
    String r = reason.toLowerCase(Locale.ROOT);
    if (category.equals("watching") && PLAY_MOVIES.matcher(r).find()) return false;
    for (String h : hints) {
      int i = r.indexOf(h);
      while (i >= 0) {
        if (i == 0 || !Character.isLetterOrDigit(r.charAt(i - 1))) return true; // word start, like \bhint
        i = r.indexOf(h, i + 1);
      }
    }
    return false;
  }

  public static boolean reasonMentions(String reason, String appName) {
    if (reason == null || appName == null) return false;
    String original = " " + reason.toLowerCase(Locale.ROOT) + " ";
    String clean = appName.toLowerCase(Locale.ROOT).trim();
    if (clean.isEmpty()) return false;
    if (original.contains(clean)) return true;
    if (PLAY_MOVIES.matcher(original).find()) return clean.equals("google tv");
    String[] hints = PAUSE_HINTS.get(clean);
    if (hints != null) {
      for (String h : hints) {
        if (original.contains(h)) return true;
      }
    }
    return false;
  }
}
