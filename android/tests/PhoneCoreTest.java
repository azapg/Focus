import java.util.*;
import org.nightingalelab.focus.phone.*;

public final class PhoneCoreTest {
  private static void check(boolean cond, String msg) {
    if (!cond) throw new AssertionError("FAILED: " + msg);
  }

  private static void pauseBudgetIncludesLiveUse() {
    long used = 55 * 60_000L - 1_000;
    long sample = 10_000_000L;
    long deadline = sample + 30_000L;
    long now = sample + 2_000L;
    long projected = PausePolicy.projectedDnsEnd(sample, sample, deadline, now);
    PausePolicy.Config policy = new PausePolicy.Config();
    if (PausePolicy.minutesLeftToday(policy, used) != 5
        || PausePolicy.minutesLeftToday(policy, used + projected - sample) != 4
        || PausePolicy.projectedDnsEnd(sample, sample, deadline, sample + 31_000L) != deadline
        || PausePolicy.projectedDnsEnd(sample, sample, deadline, sample + 75_000L) != sample) {
      throw new AssertionError("Recent live pauses count up to their deadline, while stale snapshots stay fixed");
    }
  }

  private static void testScreenOffCoverage() {
    UsageTimeline.ScreenOff screen = new UsageTimeline.ScreenOff();
    java.util.List<String> spans = new java.util.ArrayList<>();
    java.util.function.BiConsumer<Long, Long> record = (start, end) -> spans.add(start + ":" + end);
    screen.event(10, 15, record);
    screen.event(20, 16, record);
    screen.event(100, 15, record);
    screen.event(110, 16, record);
    screen.event(120, 26, record);
    screen.event(130, 27, record);
    screen.event(140, 15, record);
    screen.event(150, 16, record);
    screen.event(160, 16, record);
    screen.event(170, 15, record);
    screen.event(180, 16, record);
    check(spans.equals(java.util.Arrays.asList("20:100", "160:170")), "only paired screen-off events count");
  }

  public static void main(String[] args) {
    testScreenOffCoverage();
    pauseBudgetIncludesLiveUse();
    testBrowserUrlParser();
    testClassifierAndHonesty();
    testFiniteActivityClassification();
    testLearningOnlyDayAndGoogleTvHonesty();
    testPausePolicyAndRatchet();
    testPeekLinkRouting();
    testInsightsEngine();
    testTimelineAndBrowserCarryover();
    testApprovalPairing();
    testUpdateManifest();
    testDeepFocusSessions();
    testApkDownloadBounds();
    testUpdateManifestReleaseNotes();
    testPauseCapsAndDailyLimit();
    testFocusStats();
    testPauseFeedback();
    testDesignEdgeCases();
    System.out.println("PhoneCoreTest: ALL PASSED");
  }

  private static void testFiniteActivityClassification() {
    String[][] cases = {
        {"com.bilingify.readest", "", "", "Readest", "reading", "learn", "reading"},
        {"Readest", "", "", "Readest", "reading", "learn", "reading"},
        {"Chrome", "web.readest.com", "", "Readest", "reading", "learn", "reading"},
        {"Chrome", "", "web.readest.com", "Readest", "reading", "learn", "reading"},
        {"com.duolingo", "", "", "Duolingo", "learning", "learn", "learning"},
        {"site:www.duolingo.com", "", "", "Duolingo", "learning", "learn", "learning"},
        {"tech.simha.androidtvremote", "", "", "Remote ATV", "system", "other", "errands"},
        {"com.google.android.videos", "Play Movies", "", "Google TV", "watching", "consuming", "video"},
    };
    for (String[] row : cases) {
      ActivityClassifier.Result result = ActivityClassifier.classify(row[0], row[1], row[2], Collections.emptyList());
      check(row[3].equals(result.app), row[0] + " canonical name");
      check(row[4].equals(result.category), row[0] + " category");
      check(row[5].equals(result.group), row[0] + " activity group");
      check(row[6].equals(ActivityClassifier.fineCategory(result, "phone").id), row[0] + " fine category");
    }
    for (String host : Arrays.asList("play.google.com", "calendar.google.com", "Guide to www.duolingo.com", "Guide to web.readest.com")) {
      ActivityClassifier.Result result = ActivityClassifier.classify("Chrome", host, "", Collections.emptyList());
      check("Chrome".equals(result.app) && "browsing".equals(result.category), "Unrelated coarse label keeps its existing classification: " + host);
    }
    ActivityClassifier.Result coarseLesson = ActivityClassifier.classify("Chrome", "www.duolingo.com", "", Collections.emptyList());
    check("Duolingo".equals(coarseLesson.app) && "learning".equals(coarseLesson.category), "A whole hostname still matches");
    List<ActivityClassifier.UserRule> legacyRules = Collections.singletonList(
        new ActivityClassifier.UserRule("app", "Play Movies", "", "system"));
    ActivityClassifier.Result googleTv = ActivityClassifier.classify("com.google.android.videos", "", "", legacyRules);
    check("Google TV".equals(googleTv.app) && "system".equals(googleTv.category), "Play Movies still matches saved rules");

    List<ActivityClassifier.UserRule> rules = Collections.singletonList(
        new ActivityClassifier.UserRule("host", "example.org", "Book site", "reading"));
    check("reading".equals(ActivityClassifier.classify("site:example.org", "", "", rules).category),
        "custom reading rule applies");
  }

  private static void testLearningOnlyDayAndGoogleTvHonesty() {
    long pauseStart = 12 * 3_600_000L;
    long now = pauseStart + 4 * 60_000L;
    List<InsightsEngine.SpanRecord> lesson = Collections.singletonList(
        new InsightsEngine.SpanRecord(pauseStart, now, "com.duolingo", "Duolingo", "", "phone", "Phone"));
    InsightsEngine.TodaySummary today = InsightsEngine.computeToday(
        lesson, Collections.emptyList(), Collections.emptyList(), now, TimeZone.getTimeZone("UTC"));
    check(today.learningMs == 4 * 60_000L && today.creatingMs == 0 && today.consumingMs == 0,
        "Learning contributes time outside the ratio");
    check(today.heroLine1.contains("4m"), "Learning-only day has a time summary");

    List<InsightsEngine.SpanRecord> googleTv = Collections.singletonList(
        new InsightsEngine.SpanRecord(pauseStart, now, "com.google.android.videos", "Play Movies", "", "phone", "Phone"));
    InsightsEngine.PauseRecord pause = new InsightsEngine.PauseRecord(
        "phone", "phone", "Phone", pauseStart, now, false, 4, "Play Movies", "");
    InsightsEngine.PauseHonesty audit = InsightsEngine.evaluatePauseHonesty(pause, googleTv, Collections.emptyList(), now);
    check(!audit.drift && audit.summary.contains("Google TV"), "Former app name matches a Google TV pause");
    List<InsightsEngine.PauseRecord> pauses = Collections.singletonList(pause);
    TimeZone tz = TimeZone.getTimeZone("UTC");
    FocusStats.PauseStat match = FocusStats.compute(googleTv, pauses, Collections.emptyList(),
        now, tz, false, -1, null).pauses.get(0);
    check(!match.drift && !match.did.get(0).drifting, "Google TV match is clear in the detailed pause trail");

    List<InsightsEngine.SpanRecord> disney = Collections.singletonList(
        new InsightsEngine.SpanRecord(pauseStart, now, "com.disney.disneyplus", "Disney+", "", "phone", "Phone"));
    check(InsightsEngine.evaluatePauseHonesty(pause, disney, Collections.emptyList(), now).drift,
        "Play Movies does not match Disney+ in the legacy audit");
    FocusStats.PauseStat mismatch = FocusStats.compute(disney, pauses, Collections.emptyList(),
        now, tz, false, -1, null).pauses.get(0);
    check(mismatch.drift && mismatch.did.get(0).drifting, "Play Movies does not match Disney+ in the detailed trail");
    for (String reason : Arrays.asList("Play Movies & TV", "Watch Play Movies")) {
      check(ActivityClassifier.reasonMentions(reason, "Google TV"), "Longer legacy reason matches Google TV");
      check(!ActivityClassifier.reasonMentions(reason, "Netflix"), "Longer legacy reason excludes Netflix hints");
      check(!ActivityClassifier.reasonMentionsCategory(reason, "watching"), "Longer legacy reason excludes generic watching");
    }
    check(ActivityClassifier.reasonMentions("Play Movies and Netflix", "Netflix"), "Explicit Netflix mention still matches");
  }

  private static void testPauseFeedback() {
    TimeZone tz = TimeZone.getTimeZone("UTC");
    long start = 1_800_000_000_000L;
    start = InsightsEngine.dayStart(start, tz) + 19 * 3_600_000L;
    long end = start + 60_000;
    long now = end + 60_000;
    check(PausePolicy.CheckIn.parse("specific") == PausePolicy.CheckIn.SPECIFIC, "specific needs check-in");
    check(PausePolicy.CheckIn.parse(null) == null && PausePolicy.CheckIn.parse("invalid") == null, "older or unknown check-ins ignored");
    check(PausePolicy.ratingOpen(end, end), "end opens rating window");
    check(!PausePolicy.ratingOpen(end, end - 1) && !PausePolicy.ratingOpen(end, end + PausePolicy.RATING_WINDOW_MS), "rating window is bounded");
    check(PausePolicy.ratingNote("  " + "🍀".repeat(201) + " ").equals("🍀".repeat(200)), "note truncates at a full code point");
    InsightsEngine.PauseRecord phone = new InsightsEngine.PauseRecord("phone", "phone", "Phone",
        start, end, false, 1, "Read X", "", PausePolicy.CheckIn.BORED, 1, " Nothing relevant today ");
    InsightsEngine.PauseRecord laptop = new InsightsEngine.PauseRecord("laptop", "laptop", "Laptop",
        start, end, false, 1, "Read X", "", PausePolicy.CheckIn.BORED, 2, "");
    InsightsEngine.PauseRecord old = new InsightsEngine.PauseRecord("tv", "tv", "TV",
        start, end, false, 1, "Old reason", "");
    InsightsEngine.PauseRecord active = new InsightsEngine.PauseRecord("phone", "phone", "Phone",
        end, now, true, 1, "Check anything", "", null, 5, "Discard this");
    check(old.checkIn == null && old.rating == 0 && active.rating == 0 && active.ratingNote.isEmpty(), "legacy and active pauses have no rating");
    List<InsightsEngine.PauseRecord> pauses = List.of(phone, laptop, old, active);
    List<InsightsEngine.SpanRecord> spans = List.of(
        new InsightsEngine.SpanRecord(start, end, "chrome", "X", "x.com", "phone", "Phone", "phone"),
        new InsightsEngine.SpanRecord(start, end, "chrome", "X", "www.x.com", "laptop", "Laptop", "laptop"),
        new InsightsEngine.SpanRecord(start + 30_000, end, "other", "Other", "other.example", "phone", "Phone", "other"));
    FocusStats.View all = FocusStats.compute(spans, pauses, List.of(), now, tz, false, -1, null);
    check(all.ratingSummary.equals("Last 2 rated pauses ending on x.com · 1.5/5 average"), "ratings group by observed site and device");
    check(all.checkInsByHour.get(19).get(PausePolicy.CheckIn.BORED) == 2, "hour buckets use the stats timezone");
    FocusStats.View scoped = FocusStats.compute(spans, pauses, List.of(), now, tz, false, -1, "Phone");
    check(scoped.ratingSummary.endsWith("1.0/5 average"), "summary respects the device filter");
    check(scoped.pauses.stream().anyMatch(p -> p.ratingNote.equals("Nothing relevant today")), "short note stays on the pause row");
    check(InsightsEngine.computeToday(spans, pauses, List.of(), now, tz).pauseRatingSummary
        .equals("2 rated pauses averaged 1.5/5 today."), "evening summary uses answered pauses");

    long midnight = InsightsEngine.dayStart(now, tz);
    InsightsEngine.PauseRecord atMidnight = new InsightsEngine.PauseRecord("phone", "phone", "Phone",
        midnight, midnight + 60_000, false, 1, "Read X", "", PausePolicy.CheckIn.BORED, 0, "");
    FocusStats.View yesterday = FocusStats.compute(List.of(), List.of(atMidnight), List.of(), now, tz, true, 5, null);
    FocusStats.View today = FocusStats.compute(List.of(), List.of(atMidnight), List.of(), now, tz, true, 6, null);
    check(yesterday.checkInsByHour.isEmpty(), "midnight check-in stays out of the previous day");
    check(today.checkInsByHour.get(0).get(PausePolicy.CheckIn.BORED) == 1, "midnight check-in belongs to the new day");

    TimeZone ny = TimeZone.getTimeZone("America/New_York");
    long fallbackMidnight = java.time.Instant.parse("2026-11-01T04:00:00Z").toEpochMilli();
    long afterFallback = java.time.Instant.parse("2026-11-02T17:00:00Z").toEpochMilli();
    InsightsEngine.PauseRecord fallbackPause = new InsightsEngine.PauseRecord("phone", "phone", "Phone",
        fallbackMidnight, fallbackMidnight + 60_000, false, 1, "Read X", "", PausePolicy.CheckIn.BORED, 0, "");
    FocusStats.View fallbackDay = FocusStats.compute(List.of(), List.of(fallbackPause), List.of(), afterFallback, ny, true, 5, null);
    check(fallbackDay.to - fallbackDay.from == 25 * 3_600_000L, "fall-back day lasts 25 hours");
    check(fallbackDay.checkInsByHour.get(0).get(PausePolicy.CheckIn.BORED) == 1, "fall-back midnight check-in belongs to its day");
  }

  private static void testBrowserUrlParser() {
    BrowserUrlParser.Parsed p1 = BrowserUrlParser.parse("https://m.youtube.com/watch?v=secret123&t=42");
    check(p1 != null && "youtube.com".equals(p1.host), "m.youtube.com normalized");

    BrowserUrlParser.Parsed p2 = BrowserUrlParser.parse("www.reddit.com/r/android/comments/abc?utm=1");
    check(p2 != null && "reddit.com".equals(p2.host), "www.reddit.com normalized");

    BrowserUrlParser.Parsed p3 = BrowserUrlParser.parse("github.com/example-org/sample-project");
    check(p3 != null && "github.com".equals(p3.host) && "site:github.com".equals(p3.storageKey()), "github.com parsed");

    check(BrowserUrlParser.parse("how to make pasta") == null, "search query rejected");
    check(BrowserUrlParser.parse("chrome://newtab") == null, "chrome internal rejected");
    check(BrowserUrlParser.parse("about:blank") == null, "about:blank rejected");
    check(BrowserUrlParser.parse("opera://internal.example") == null, "browser-specific internal scheme rejected");
    check(BrowserUrlParser.parse("notadomain") == null, "bare word rejected");
    String[][] toolbars = {
        {"com.android.chrome", "com.android.chrome:id/url_bar"},
        {"com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser:id/compact_url_text"},
        {"com.sec.android.app.sbrowser.beta", "com.sec.android.app.sbrowser:id/location_bar_edit_text"},
        {"com.brave.browser", "com.brave.browser:id/url_bar"},
        {"com.microsoft.emmx", "com.microsoft.emmx:id/url_bar"},
        {"org.mozilla.firefox", "org.mozilla.firefox:id/mozac_browser_toolbar_url_view"},
        {"org.mozilla.focus", "org.mozilla.focus:id/mozac_browser_toolbar_url_view"},
        {"com.opera.browser", "com.opera.browser:id/url_field"},
        {"com.opera.mini.native", "com.opera.android:id/url_field"},
        {"com.vivaldi.browser", "com.vivaldi.browser:id/url_bar"},
        {"com.duckduckgo.mobile.android", "com.duckduckgo.mobile.android:id/omnibarTextInput"},
    };
    for (String[] toolbar : toolbars) {
      check(BrowserUrlParser.isBrowser(toolbar[0]), toolbar[0] + " recognized");
      check(BrowserUrlParser.isAddressBar(toolbar[0], toolbar[1]), toolbar[1] + " recognized");
    }
    check(!BrowserUrlParser.isAddressBar("com.example.app", "com.example.app:id/url_bar"),
        "address bar lookup is restricted to supported browsers");
    check(BrowserUrlParser.isNonPageView("com.android.chrome", "com.android.chrome:id/incognito_new_tab_page"),
        "blank incognito page recognized");
  }

  private static void testClassifierAndHonesty() {
    List<ActivityClassifier.UserRule> empty = Collections.emptyList();
    ActivityClassifier.Result ig = ActivityClassifier.classify("com.instagram.android", "Instagram", "", empty);
    check("Instagram".equals(ig.app) && "consuming".equals(ig.group), "Instagram is consuming");

    ActivityClassifier.Result gh = ActivityClassifier.classify("com.android.chrome", "Chrome", "github.com", empty);
    check("GitHub".equals(gh.app) && "creating".equals(gh.group), "Chrome github.com is creating");

    ActivityClassifier.Result rd = ActivityClassifier.classify("com.android.chrome", "Chrome", "reddit.com", empty);
    check("Reddit".equals(rd.app) && "consuming".equals(rd.group), "Chrome reddit.com is consuming");

    ActivityClassifier.Result unk = ActivityClassifier.classify("com.example.mystery", "Mystery", "", empty);
    check("unknown".equals(unk.group), "Unknown app stays honest");

    List<ActivityClassifier.UserRule> rules = Collections.singletonList(
        new ActivityClassifier.UserRule("package", "com.example.mystery", "Mystery IDE", "building"));
    ActivityClassifier.Result overridden = ActivityClassifier.classify("com.example.mystery", "Mystery", "", rules);
    check("creating".equals(overridden.group) && "Mystery IDE".equals(overridden.app), "User rule overrides");

    check(ActivityClassifier.reasonMentions("Watch MIT lecture on YouTube", "YouTube"), "reason mentions YouTube");
    check(!ActivityClassifier.reasonMentions("Check a blocked docs page", "Reddit"), "Reddit is drift for docs reason");
  }

  private static void testPeekLinkRouting() {
    String link = "https://x.com/jack/status/20";
    check(link.equals(PausePolicy.peekLink(" \n" + link + "\u00a0")), "padded bare link routes to Peek");
    check(PausePolicy.peekLink("Read " + link) == null, "written request routes to Ask");
    check(PausePolicy.peekLink(link + " " + link) == null, "multiple links route to Ask");
    check("https://x.com/home".equals(PausePolicy.peekLink("https://x.com/home")), "feed refusal stays local");
  }

  private static void testPausePolicyAndRatchet() {
    PausePolicy.Config cfg = new PausePolicy.Config();
    Map<String, PausePolicy.PendingChange> pending = new LinkedHashMap<>();
    TimeZone tz = TimeZone.getTimeZone("America/New_York");
    long now = 1_763_760_000_000L; // mid-day

    // Escalation ladder across all devices
    check(PausePolicy.level(cfg, 1) == 1 && PausePolicy.holdDurationMs(cfg, 1) == 2000L, "1st pause L1, 2s hold");
    check(PausePolicy.level(cfg, 2) == 1 && PausePolicy.waitSecondsFor(cfg, 2) == 0, "2nd pause L1, no wait");
    check(PausePolicy.level(cfg, 3) == 2 && PausePolicy.holdDurationMs(cfg, 3) == 3000L && PausePolicy.waitSecondsFor(cfg, 3) == 15, "3rd pause L2, 3s hold, 15s wait");
    check(PausePolicy.level(cfg, 5) == 3 && PausePolicy.requiresAfterPlan(cfg, 5), "5th pause L3 requires after plan");

    // Durations: TV gets up to 5h, phone gets up to 30m
    int[] tvDurs = PausePolicy.allowedDurations(cfg, "tv");
    check(Arrays.equals(tvDurs, new int[] {30, 60, 120, 300}), "TV durations 30m..5h");
    int[] phoneDurs = PausePolicy.allowedDurations(cfg, "phone");
    check(Arrays.equals(phoneDurs, new int[] {1, 5, 10, 30}), "Phone durations 1m..30m");

    // Loosening wait 15s -> 10s waits until tomorrow
    PausePolicy.UpdateOutcome loosen = PausePolicy.proposeInt(cfg, pending, "wait_seconds", 10, now, tz);
    check(!loosen.appliedNow && cfg.waitSeconds == 15 && pending.containsKey("wait_seconds"), "Loosening queued");
    long eff = pending.get("wait_seconds").effectiveAt;
    check(eff > now, "Effective tomorrow midnight");

    // Tightening wait 15s -> 30s starts immediately and cancels pending loosening
    PausePolicy.UpdateOutcome tighten = PausePolicy.proposeInt(cfg, pending, "wait_seconds", 30, now, tz);
    check(tighten.appliedNow && cfg.waitSeconds == 30 && !pending.containsKey("wait_seconds"), "Tightening immediate");

    // Loosening again, then crossing midnight applies it
    PausePolicy.proposeInt(cfg, pending, "wait_seconds", 15, now, tz);
    check(cfg.waitSeconds == 30 && pending.containsKey("wait_seconds"), "Still 30s today");
    boolean applied = PausePolicy.applyDuePending(cfg, pending, eff + 1000L);
    check(applied && cfg.waitSeconds == 15 && pending.isEmpty(), "Pending loosening applies after midnight");

    // Disabling Private DNS guard is a loosening (waits until tomorrow)
    PausePolicy.UpdateOutcome guardOff = PausePolicy.proposeGuardPrivateDns(cfg, pending, false, now, tz);
    check(!guardOff.appliedNow && cfg.guardPrivateDns && pending.containsKey("guard_private_dns"), "Guard off waits until tomorrow");
  }

  private static void testInsightsEngine() {
    TimeZone tz = TimeZone.getTimeZone("UTC");
    Calendar cal = Calendar.getInstance(tz);
    cal.set(2026, Calendar.NOVEMBER, 21, 21, 0, 0);
    cal.set(Calendar.MILLISECOND, 0);
    long now = cal.getTimeInMillis();
    long todayStart = InsightsEngine.dayStart(now, tz);

    List<InsightsEngine.SpanRecord> spans = new ArrayList<>();
    // Creating: 2h VS Code on laptop, 1h Claude on phone
    spans.add(new InsightsEngine.SpanRecord(todayStart + 10 * 3600_000L, todayStart + 12 * 3600_000L,
        "VS Code", "VS Code", "", "laptop", "Laptop"));
    spans.add(new InsightsEngine.SpanRecord(todayStart + 13 * 3600_000L, todayStart + 14 * 3600_000L,
        "com.anthropic.claude", "Claude", "", "phone", "Phone"));
    // Consuming: 40m Instagram before 9am on phone, 20m YouTube on TV
    spans.add(new InsightsEngine.SpanRecord(todayStart + 8 * 3600_000L, todayStart + 8 * 3600_000L + 40 * 60_000L,
        "com.instagram.android", "Instagram", "", "phone", "Phone"));
    spans.add(new InsightsEngine.SpanRecord(todayStart + 19 * 3600_000L, todayStart + 19 * 3600_000L + 20 * 60_000L,
        "com.google.android.youtube.tv", "YouTube", "", "tv", "Living room TV"));
    // Chrome Reddit span during a pause
    long pauseStart = todayStart + 12 * 3600_000L + 12 * 60_000L;
    spans.add(new InsightsEngine.SpanRecord(pauseStart + 60_000L, pauseStart + 4 * 60_000L,
        "com.android.chrome", "Reddit", "reddit.com", "phone", "Phone"));

    List<InsightsEngine.PauseRecord> pauses = Collections.singletonList(
        new InsightsEngine.PauseRecord("phone", "phone", "Phone", pauseStart, pauseStart + 10 * 60_000L,
            false, 10, "Check a blocked docs page", ""));

    InsightsEngine.TodaySummary today = InsightsEngine.computeToday(spans, pauses, Collections.emptyList(), now, tz);
    check(today.creatingMs == 180 * 60_000L, "Creating = 3h");
    check(today.consumingMs == 63 * 60_000L, "Consuming = 1h 03m");
    check(today.creatingPct == 74, "Creating pct = 74%, got " + today.creatingPct);
    check(today.heroLine1.contains("3h 00m"), "Hero line 1 shows 3h 00m");
    check(today.explainPlain.contains("Instagram before 9am"), "Explain mentions morning Instagram: " + today.explainPlain);
    check(today.explainPlain.contains("VS Code"), "Explain mentions longest focus VS Code");
    check(today.pauses.size() == 1 && today.pauses.get(0).drift && today.pauses.get(0).outcome.contains("Reddit"),
        "Pause drift detected on Chrome reddit.com");

    InsightsEngine.WeekSummary week = InsightsEngine.computeWeek(spans, pauses, Collections.emptyList(), now, tz);
    check(week.days.size() == 7 && week.days.get(6).isToday, "7 days computed");
    check(week.weekPauses == 1 && week.weekDriftPauses == 1, "Weekly drift counted");
  }

  private static void testTimelineAndBrowserCarryover() {
    UsageTimeline t = new UsageTimeline(1000, true);
    List<String> out = new ArrayList<>();
    UsageTimeline.Sink sink = (s, e, pkg, dom) -> out.add(s + "-" + e + ":" + pkg + ":" + dom);
    t.event(2000, 1, "com.android.chrome", "Main", true, sink);
    t.domain = "reddit.com";
    t.advance(7000, sink);
    t.event(8000, 1, "com.whatsapp", "Chat", true, sink);
    t.advance(12000, sink);
    check(out.size() == 3, "3 spans emitted, got " + out);
    check(out.get(0).equals("2000-7000:com.android.chrome:reddit.com"), "Chrome span carries domain");
    check(out.get(2).equals("8000-12000:com.whatsapp:"), "Non-browser clears domain");
  }

  private static void testApprovalPairing() {
    ApprovalPairing ap = new ApprovalPairing();
    String fp = PairingCode.hash("cert");
    String clientNonce = PairingCode.token();
    String commit = PairingCode.hash(clientNonce);
    ApprovalPairing.Session s = ap.begin(commit, "Fixture laptop", 1000);
    ap.reveal(s.id, s.secret, clientNonce, fp, 2000);
    String expected = ApprovalPairing.comparison(fp, clientNonce, s.serverNonce);
    check(expected.equals(s.code) && expected.length() == 6, "6-digit comparison matches");
    ap.allow(s.id, 3000);
    String tok = PairingCode.token();
    ap.approved(s, tok);
    check("approved".equals(s.status) && tok.equals(s.token), "Approval issues token");
  }

  private static void testUpdateManifest() {
    String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    String manifestJson = "{\n"
        + "  \"version\": 1,\n"
        + "  \"channel\": \"nightly\",\n"
        + "  \"platform\": \"android\",\n"
        + "  \"package_name\": \"org.nightingalelab.focus.phone\",\n"
        + "  \"version_code\": 95234567,\n"
        + "  \"version_name\": \"0.1.0-abc1234\",\n"
        + "  \"build_id\": \"abc1234\",\n"
        + "  \"commit_sha\": \"abc1234567890abcdef1234567890abcdef12345\",\n"
        + "  \"commit_subject\": \"feat: add phone \\\"nightly\\\" updater\",\n"
        + "  \"published_at\": \"2026-11-21T22:30:00Z\",\n"
        + "  \"apk\": {\n"
        + "    \"name\": \"focus-phone.apk\",\n"
        + "    \"size\": 148290,\n"
        + "    \"sha256\": \"" + sha + "\"\n"
        + "  }\n"
        + "}";
    UpdateManifest m = UpdateManifest.parseManifest(manifestJson);
    check(m.version == 1 && "android".equals(m.platform), "Manifest parsed");
    check(m.versionCode == 95234567L && "abc1234".equals(m.buildId), "Version and build parsed");
    check("feat: add phone \"nightly\" updater".equals(m.commitSubject), "Escaped JSON string parsed");
    check("focus-phone.apk".equals(m.apkName) && m.apkSize == 148290L && sha.equals(m.apkSha256), "APK metadata parsed");
    try {
      UpdateManifest.parseManifest(manifestJson.replace("148290,", "148290.5,"));
      throw new AssertionError("Fractional APK sizes must be rejected");
    } catch (IllegalArgumentException expected) {
      check(expected.getMessage().contains("APK size"), "Invalid size reports the APK field");
    }

    // Round-trip serialization
    UpdateManifest roundTrip = UpdateManifest.parseManifest(m.toJsonString());
    check(roundTrip.commitSubject.equals(m.commitSubject) && roundTrip.apkSha256.equals(m.apkSha256),
        "Manifest JSON round-trip preserves fields");

    // GitHub release JSON asset lookup
    String releaseJson = "{\"tag_name\":\"nightly\",\"assets\":["
        + "{\"name\":\"FocusDNS.exe\",\"url\":\"https://api.github.com/repos/example-org/sample-project/releases/assets/1\"},"
        + "{\"name\":\"android-nightly.json\",\"url\":\"https://api.github.com/repos/example-org/sample-project/releases/assets/2\"},"
        + "{\"name\":\"focus-phone.apk\",\"url\":\"https://api.github.com/repos/example-org/sample-project/releases/assets/3\"}"
        + "]}";
    check("https://api.github.com/repos/example-org/sample-project/releases/assets/2"
        .equals(UpdateManifest.findReleaseAssetUrl(releaseJson, "android-nightly.json")),
        "Finds android-nightly.json asset URL");
    check("https://api.github.com/repos/example-org/sample-project/releases/assets/3"
        .equals(UpdateManifest.findReleaseAssetUrl(releaseJson, "focus-phone.apk")),
        "Finds focus-phone.apk asset URL");
    check(UpdateManifest.findReleaseAssetUrl(releaseJson, "missing.apk") == null, "Missing asset returns null");

    // Update comparison rules
    check(!UpdateManifest.isUpdateAvailable(95234000L, m.commitSha, "old1234", m), "Same commit is up to date");
    check(!UpdateManifest.isUpdateAvailable(95234000L, "deadbeef", m.buildId, m), "Same build id is up to date");
    check(UpdateManifest.isUpdateAvailable(95234000L, "deadbeef", "deadbee", m), "Higher versionCode is update");
    check(!UpdateManifest.isUpdateAvailable(95299999L, "deadbeef", "deadbee", m), "Downgrade versionCode is rejected");
    check(UpdateManifest.isUpdateAvailable(1L, "deadbeef", "deadbee", m), "Initial v1 upgrades to nightly");

    // URL validation
    check(UpdateManifest.isAllowedApiUrl("https://api.github.com/repos/example-org/sample-project/releases/tags/nightly"),
        "Allows GitHub API HTTPS URL");
    check(!UpdateManifest.isAllowedApiUrl("http://api.github.com/repos/example-org/sample-project"),
        "Rejects HTTP GitHub URL");
    check(!UpdateManifest.isAllowedApiUrl("https://evil.example/repos/example-org/sample-project"),
        "Rejects non-GitHub API host");
    check(UpdateManifest.isAllowedDownloadRedirect("https://objects.githubusercontent.com/abc"),
        "Allows HTTPS blob redirect");
    check(!UpdateManifest.isAllowedDownloadRedirect("http://objects.githubusercontent.com/abc"),
        "Rejects HTTP blob redirect");

    // File SHA-256 verification
    try {
      java.io.File tmp = java.io.File.createTempFile("focus-upd-test-", ".apk");
      tmp.deleteOnExit();
      byte[] payload = "fake-focus-phone-apk".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
        fos.write(payload);
      }
      String goodHash = UpdateManifest.sha256Hex(tmp);
      UpdateManifest.verifyFile(tmp, payload.length, goodHash);

      boolean badSizeCaught = false;
      try {
        UpdateManifest.verifyFile(tmp, payload.length + 1, goodHash);
      } catch (java.io.IOException expected) {
        badSizeCaught = true;
      }
      check(badSizeCaught, "Size mismatch rejected");

      boolean badHashCaught = false;
      try {
        UpdateManifest.verifyFile(tmp, payload.length, sha);
      } catch (java.io.IOException expected) {
        badHashCaught = true;
      }
      check(badHashCaught, "Hash mismatch rejected");
      tmp.delete();
    } catch (java.io.IOException e) {
      throw new AssertionError("Temp file test failed: " + e.getMessage());
    }

    check("145 KB".equals(UpdateManifest.formatBytes(148290L)), "Formats KB cleanly");
    check("1.5 MB".equals(UpdateManifest.formatBytes(1572864L)), "Formats MB cleanly");
  }

  private static void testApkDownloadBounds() {
    long oldLimit = 32L * 1024 * 1024;
    long ceiling = 256L * 1024 * 1024;
    try {
      UpdateManifest.checkDownloadSize(oldLimit + 1, 2 * (oldLimit + 1));
      UpdateManifest.checkDownloadSize(ceiling, 2 * ceiling);
      UpdateManifest.checkApkSize(99, 100, false);
      UpdateManifest.checkApkSize(100, 100, true);
    } catch (java.io.IOException e) {
      throw new AssertionError("Valid download sizes must be accepted", e);
    }
    expectSizeRejection(() -> UpdateManifest.checkDownloadSize(0, 0), 0, ceiling);
    expectSizeRejection(() -> UpdateManifest.checkDownloadSize(ceiling + 1, Long.MAX_VALUE),
        ceiling + 1, ceiling);
    expectSizeRejection(() -> UpdateManifest.checkDownloadSize(100, 199), 199, 200);
    expectSizeRejection(() -> UpdateManifest.checkApkSize(101, 100, false), 101, 100);
    expectSizeRejection(() -> UpdateManifest.checkApkSize(99, 100, true), 99, 100);
  }

  private interface SizeCheck {
    void run() throws java.io.IOException;
  }

  private static void expectSizeRejection(SizeCheck checkSize, long actual, long allowed) {
    try {
      checkSize.run();
      throw new AssertionError("Invalid download size must be rejected");
    } catch (java.io.IOException e) {
      check(e.getMessage().contains(actual + " bytes") && e.getMessage().contains(allowed + " bytes"),
          "Size error includes both quantities");
    }
  }

  private static void testUpdateManifestReleaseNotes() {
    String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    String withNotes = "{\n"
        + "  \"version\": 1, \"platform\": \"android\", \"package_name\": \"org.nightingalelab.focus.phone\",\n"
        + "  \"version_code\": 95234568, \"build_id\": \"def5678\",\n"
        + "  \"commit_sha\": \"def5678567890abcdef1234567890abcdef12345\",\n"
        + "  \"commit_subject\": \"feat: new oled phone design\",\n"
        + "  \"apk\": {\"name\": \"focus-phone.apk\", \"size\": 200000, \"sha256\": \"" + sha + "\"},\n"
        + "  \"notes\": {\n"
        + "    \"items\": [\n"
        + "      [\"Cleaner OLED layout\", \"Pure black cards with amber only for drifting.\"],\n"
        + "      [\"Shared daily limit\", \"Set one DNS-off budget for all devices.\"],\n"
        + "      [\"Searchable screen time\", \"Filter every app from the bottom bar.\"],\n"
        + "      [\"Fourth extra item\", \"Should be capped at 3 items.\"]\n"
        + "    ],\n"
        + "    \"more\": \"Plus 2 more fixes.\"\n"
        + "  }\n"
        + "}";
    UpdateManifest m = UpdateManifest.parseManifest(withNotes);
    check(m.hasNotes() && m.notes.size() == 3, "Notes parsed and capped at 3");
    check("Cleaner OLED layout".equals(m.notes.get(0)[0]), "First note title matches");
    check("Plus 2 more fixes.".equals(m.notesMore), "Notes more line matches");
    UpdateManifest rt = UpdateManifest.parseManifest(m.toJsonString());
    check(rt.hasNotes() && rt.notes.size() == 3 && "Plus 2 more fixes.".equals(rt.notesMore),
        "Notes survive JSON round-trip");

    // Old manifest without notes still parses with empty notes
    String noNotes = withNotes.replaceFirst(",\\s*\"notes\":[\\s\\S]*\\}", "}");
    UpdateManifest oldM = UpdateManifest.parseManifest(noNotes);
    check(!oldM.hasNotes() && oldM.notes.isEmpty() && oldM.notesMore.isEmpty(),
        "Manifest without notes parses cleanly");
  }

  private static void testPauseCapsAndDailyLimit() {
    PausePolicy.Config cfg = new PausePolicy.Config();
    Map<String, PausePolicy.PendingChange> pending = new LinkedHashMap<>();
    TimeZone tz = TimeZone.getTimeZone("America/New_York");
    long now = 1_763_760_000_000L;

    // Daily limit ratchet: raising waits until tomorrow, lowering starts now
    PausePolicy.UpdateOutcome raise = PausePolicy.proposeInt(cfg, pending, "daily_limit_minutes", 90, now, tz);
    check(!raise.appliedNow && cfg.dailyLimitMinutes == 60 && pending.containsKey("daily_limit_minutes"),
        "Raising daily limit waits until tomorrow");
    PausePolicy.UpdateOutcome lower = PausePolicy.proposeInt(cfg, pending, "daily_limit_minutes", 45, now, tz);
    check(lower.appliedNow && cfg.dailyLimitMinutes == 45 && !pending.containsKey("daily_limit_minutes"),
        "Lowering daily limit applies immediately");

    // Pause choices respect both max pause and remaining daily limit; 1m always allowed
    cfg.dailyLimitMinutes = 60;
    cfg.maxPhoneMinutes = 10;
    List<PausePolicy.Choice> ch = PausePolicy.pauseChoices(cfg, "phone", 52 * 60_000L); // 8m left
    check(ch.size() == 3, "30m hidden when max pause is 10m");
    check(ch.get(0).minutes == 1 && ch.get(0).allowed, "1m allowed");
    check(ch.get(1).minutes == 5 && ch.get(1).allowed, "5m allowed with 8m left");
    check(ch.get(2).minutes == 10 && !ch.get(2).allowed, "10m disabled with 8m left");
    check(PausePolicy.defaultChoice(ch) == 5, "Default choice is 5m when allowed");

    List<PausePolicy.Choice> overCh = PausePolicy.pauseChoices(cfg, "phone", 65 * 60_000L);
    check(overCh.get(0).minutes == 1 && overCh.get(0).allowed, "1m always allowed over limit");
    check(!overCh.get(1).allowed && PausePolicy.defaultChoice(overCh) == 1, "Defaults to 1m when over limit");

    String note = PausePolicy.sheetNote(cfg, 6, 47 * 60_000L);
    check(note.contains("6th pause today") && note.contains("15s wait") && note.contains("13m left"),
        "Sheet note names pause number, wait, and remaining limit: " + note);
  }

  private static void testFocusStats() {
    TimeZone tz = TimeZone.getTimeZone("UTC");
    Calendar cal = Calendar.getInstance(tz);
    cal.set(2026, Calendar.NOVEMBER, 21, 20, 0, 0);
    cal.set(Calendar.MILLISECOND, 0);
    long now = cal.getTimeInMillis();
    long todayStart = InsightsEngine.dayStart(now, tz);

    List<InsightsEngine.SpanRecord> spans = new ArrayList<>();
    spans.add(new InsightsEngine.SpanRecord(todayStart + 9 * 3600_000L, todayStart + 11 * 3600_000L,
        "T3 Code", "T3 Code", "", "laptop", "Laptop"));
    spans.add(new InsightsEngine.SpanRecord(todayStart + 12 * 3600_000L, todayStart + 12 * 3600_000L + 20 * 60_000L,
        "com.android.chrome", "YouTube", "youtube.com", "phone", "Phone"));

    long p1Start = todayStart + 12 * 3600_000L;
    long p2Start = now - 3 * 60_000L; // fresh just-ended pause
    List<InsightsEngine.PauseRecord> pauses = Arrays.asList(
        new InsightsEngine.PauseRecord("p1", "phone", "Phone", p1Start, p1Start + 25 * 60_000L,
            false, 30, "Check docs", ""),
        new InsightsEngine.PauseRecord("p2", "laptop", "Laptop", p2Start, now - 60_000L,
            false, 5, "Quick check", ""));

    FocusStats.View v = FocusStats.compute(spans, pauses, Collections.emptyList(), now, tz, false, -1, null);
    check(v.totalMs == 140 * FocusStats.MIN, "Total 140m");
    check(v.buildMs == 120 * FocusStats.MIN, "Build 120m");
    check(v.driftMs == 20 * FocusStats.MIN, "Drift 20m");
    check(v.multiDevice(), "Detects multiple devices");
    check(v.apps.size() == 2 && v.apps.get(1).web && v.apps.get(1).mostlyDnsOff(),
        "YouTube marked web and mostly DNS off");

    List<FocusStats.PauseStat> top = v.topPauses(3);
    check(top.size() == 2 && top.get(0).fresh, "Fresh pause pinned first before bigger older pause");
    check(top.get(1).drift && FocusStats.pauseDetail(top.get(1)).contains("Asked for 30m · DNS off 25m"),
        "Drift and pause detail computed");

    // Device scope narrows apps & pauses while keeping shared offAllMs for the ring
    FocusStats.View phoneOnly = FocusStats.compute(spans, pauses, Collections.emptyList(), now, tz, false, -1, "Phone");
    check(phoneOnly.apps.size() == 1 && "YouTube".equals(phoneOnly.apps.get(0).name), "Phone scope narrows apps");
    check(phoneOnly.offAllMs == 27 * FocusStats.MIN, "Ring keeps shared all-device DNS-off total");
  }

  private static void testDesignEdgeCases() {
    // Malformed notes never break the update check; title-only items survive.
    String base = "{\"version\":1,\"platform\":\"android\",\"package_name\":\"org.nightingalelab.focus.phone\","
        + "\"version_code\":2,\"build_id\":\"abc1234\",\"commit_sha\":\"abc1234567890abcdef1234567890abcdef12345\","
        + "\"commit_subject\":\"fix: thing\",\"apk\":{\"name\":\"focus-phone.apk\",\"size\":10,"
        + "\"sha256\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}";
    check(!UpdateManifest.parseManifest(base + ",\"notes\":\"oops\"}").hasNotes(), "String notes ignored");
    UpdateManifest mixed = UpdateManifest.parseManifest(base
        + ",\"notes\":{\"items\":[42,[\"Only title\"],[],[\"\",\"\"]],\"more\":7}}");
    check(mixed.notes.size() == 1 && "Only title".equals(mixed.notes.get(0)[0]) && mixed.notesMore.isEmpty(),
        "Bad note items skipped, title-only kept");
    check(!UpdateManifest.parseManifest(UpdateManifest.parseManifest(base + "}").toJsonString()).hasNotes(),
        "No notes stays no notes after the cached round trip");

    // Pause sheet rules: TV offers the same 1/5/10/30m, a typed reason is required, no wait before pause 3.
    PausePolicy.Config cfg = new PausePolicy.Config();
    List<PausePolicy.Choice> tv = PausePolicy.pauseChoices(cfg, "tv", 0);
    check(tv.size() == 4 && tv.get(3).minutes == 30 && tv.get(3).allowed, "TV offers 1/5/10/30m");
    check(!PausePolicy.reasonOk("") && !PausePolicy.reasonOk(" a ") && PausePolicy.reasonOk("Reply to Ana"),
        "Reason must be typed");
    check(PausePolicy.sheetNote(cfg, 2, 0).equals("2nd pause today · no wait · 1h 00m left of your limit"),
        "No wait before the 3rd pause: " + PausePolicy.sheetNote(cfg, 2, 0));

    // Stats: activity words in a reason are not drift; week bars carry DNS-off; a bare browser is "Not sure".
    TimeZone tz = TimeZone.getTimeZone("UTC");
    Calendar cal = Calendar.getInstance(tz);
    cal.set(2026, Calendar.NOVEMBER, 22, 23, 0, 0);
    cal.set(Calendar.MILLISECOND, 0);
    long now = cal.getTimeInMillis();
    long t0 = InsightsEngine.dayStart(now, tz);
    long M = FocusStats.MIN, H = 60 * M;
    List<InsightsEngine.SpanRecord> spans = Arrays.asList(
        new InsightsEngine.SpanRecord(t0 + 8 * H, t0 + 8 * H + 5 * M, "com.android.chrome", "Reddit", "reddit.com", "phone", "Phone"),
        new InsightsEngine.SpanRecord(t0 + 9 * H, t0 + 9 * H + 16 * M, "com.android.chrome", "Chrome", "", "phone", "Phone"),
        new InsightsEngine.SpanRecord(t0 - 5 * H, t0 - 3 * H, "com.twitter.android", "X", "", "phone", "Phone"));
    List<InsightsEngine.PauseRecord> pauses = Arrays.asList(
        new InsightsEngine.PauseRecord("phone", "phone", "Phone", t0 + 8 * H, t0 + 8 * H + 6 * M, false, 10,
            "Browse a few links", ""),
        new InsightsEngine.PauseRecord("phone", "phone", "Phone", t0 - 4 * H, t0 - 4 * H + 20 * M, false, 30,
            "Check a docs page", ""));
    FocusStats.View today = FocusStats.compute(spans, pauses, Collections.emptyList(), now, tz, false, -1, null);
    check(today.pauses.size() == 1 && !today.pauses.get(0).drift, "\"browse\" + Reddit is not drift");
    FocusStats.AppStat chrome = null;
    for (FocusStats.AppStat a : today.apps) if ("Chrome".equals(a.name)) chrome = a;
    check(chrome != null && "Not sure".equals(chrome.category.label) && !today.multiDevice(), "Bare browser is Not sure");
    FocusStats.View week = FocusStats.compute(spans, pauses, Collections.emptyList(), now, tz, true, -1, null);
    check(week.week.get(5).totalMs == 2 * H && week.week.get(5).offMs == 20 * M && week.week.get(6).offMs == 6 * M,
        "Week bars carry screen time and DNS-off per day");
    check("2h".equals(FocusStats.fmtBar(2 * H)) && "45m".equals(FocusStats.fmtBar(45 * M)), "Compact bar labels");
    FocusStats.View sat = FocusStats.compute(spans, pauses, Collections.emptyList(), now, tz, true, 5, null);
    check(sat.singleDay && sat.totalMs == 2 * H && sat.pauses.size() == 1 && sat.pauses.get(0).drift,
        "Selecting a day narrows the view; X during a docs pause drifted");
  }

  private static void testDeepFocusSessions() {
    TimeZone tz = TimeZone.getTimeZone("UTC");
    Calendar cal = Calendar.getInstance(tz);
    cal.set(2026, Calendar.NOVEMBER, 23, 18, 0, 0);
    cal.set(Calendar.MILLISECOND, 0);
    long now = cal.getTimeInMillis();
    long t0 = InsightsEngine.dayStart(now, tz);
    long M = FocusStats.MIN, S = 1000L;

    List<InsightsEngine.SpanRecord> spans = Arrays.asList(
        // T3 Code: 15m + 30s glance to Terminal + 15m -> 30m unbroken focus
        new InsightsEngine.SpanRecord(t0 + 9 * 60 * M, t0 + (9 * 60 + 15) * M, "T3 Code", "T3 Code", "", "laptop", "Laptop"),
        new InsightsEngine.SpanRecord(t0 + (9 * 60 + 15) * M, t0 + (9 * 60 + 15) * M + 30 * S, "Terminal", "Terminal", "", "laptop", "Laptop"),
        new InsightsEngine.SpanRecord(t0 + (9 * 60 + 15) * M + 30 * S, t0 + (9 * 60 + 30) * M + 30 * S, "T3 Code", "T3 Code", "", "laptop", "Laptop"),
        // NotebookLM: 16m on phone overlapping T3 Code -> qualifies (15m learning min), union deduplicates overlap
        new InsightsEngine.SpanRecord(t0 + (9 * 60 + 10) * M, t0 + (9 * 60 + 26) * M, "com.android.chrome", "NotebookLM", "notebooklm.google.com", "phone", "Phone"),
        // 20m Notion interrupted by 10s X -> breaks immediately, neither side hits 25m
        new InsightsEngine.SpanRecord(t0 + 11 * 60 * M, t0 + (11 * 60 + 20) * M, "Notion", "Notion", "", "laptop", "Laptop"),
        new InsightsEngine.SpanRecord(t0 + (11 * 60 + 20) * M, t0 + (11 * 60 + 20) * M + 10 * S, "com.android.chrome", "X", "x.com", "laptop", "Laptop"),
        new InsightsEngine.SpanRecord(t0 + (11 * 60 + 20) * M + 10 * S, t0 + (11 * 60 + 30) * M + 10 * S, "Notion", "Notion", "", "laptop", "Laptop"),
        // 60m YouTube -> never eligible
        new InsightsEngine.SpanRecord(t0 + 13 * 60 * M, t0 + 14 * 60 * M, "com.google.android.youtube", "YouTube", "", "phone", "Phone"),
        // 40m T3 Code split by a 5m DNS pause -> 18m + 17m, neither qualifies
        new InsightsEngine.SpanRecord(t0 + 15 * 60 * M, t0 + (15 * 60 + 40) * M, "T3 Code", "T3 Code", "", "laptop", "Laptop"));
    List<InsightsEngine.PauseRecord> pauses = Collections.singletonList(
        new InsightsEngine.PauseRecord("laptop", "laptop", "Laptop",
            t0 + (15 * 60 + 18) * M, t0 + (15 * 60 + 23) * M, false, 5, "quick check", ""));

    FocusStats.View v = FocusStats.compute(spans, pauses, Collections.emptyList(), now, tz, false, -1, null);
    check(v.focusRuns.size() == 2, "Expected 2 focus runs (T3 Code 30m, NotebookLM 16m), got " + v.focusRuns.size());
    check("T3 Code".equals(v.focusRuns.get(0).app) && v.focusRuns.get(0).ms == 30 * M, "T3 Code 30m run");
    check("NotebookLM".equals(v.focusRuns.get(1).app) && v.focusRuns.get(1).ms == 16 * M, "NotebookLM 16m run");
    check(v.focusMs == 46 * M, "Total focus 46m");
    check(v.focusUnionMs == 30 * M + 30 * S, "Union focus deduplicates phone/laptop overlap: " + v.focusUnionMs);
    check(v.barCategories().get(0).fine.id.equals("focus") && v.barCategories().get(0).ms == 46 * M,
        "barCategories prepends Ice Deep focus");
    for (FocusStats.AppStat a : v.apps) {
      if ("YouTube".equals(a.name)) check(a.focusMs == 0, "YouTube never gets focus credit");
      if ("Notion".equals(a.name)) check(a.focusMs == 0, "Notion broken by X gets 0 focus");
    }
  }
}
