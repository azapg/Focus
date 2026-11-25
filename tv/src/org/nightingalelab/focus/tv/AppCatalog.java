package org.nightingalelab.focus.tv;

import android.content.*;
import android.content.pm.*;
import android.view.inputmethod.*;
import java.util.*;

public final class AppCatalog {
  public static final class App {
    public final String pkg, label;

    App(String p, String l) {
      pkg = p;
      label = l;
    }
  }

  private static List<App> cached = new ArrayList<>();
  private static long refreshed;

  public static synchronized List<App> apps(Context c) {
    if (android.os.SystemClock.elapsedRealtime() - refreshed < 30_000 && !cached.isEmpty())
      return new ArrayList<>(cached);
    PackageManager pm = c.getPackageManager();
    Set<String> protectedPackages =
        new HashSet<>(
            Arrays.asList(
                c.getPackageName(),
                "com.android.systemui",
                "com.android.settings",
                "com.android.tv.settings",
                "com.google.android.permissioncontroller",
                "com.android.permissioncontroller",
                "com.android.packageinstaller",
                "com.google.android.packageinstaller"));
    Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    for (ResolveInfo r : pm.queryIntentActivities(home, 0))
      protectedPackages.add(r.activityInfo.packageName);
    for (String action :
        new String[] {
          android.provider.Settings.ACTION_SETTINGS,
          android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
          android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS
        }) {
      ResolveInfo r = pm.resolveActivity(new Intent(action), 0);
      if (r != null) protectedPackages.add(r.activityInfo.packageName);
    }
    InputMethodManager im = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
    for (InputMethodInfo i : im.getInputMethodList()) protectedPackages.add(i.getPackageName());
    Map<String, App> found = new TreeMap<>();
    for (ResolveInfo r :
        pm.queryIntentActivities(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
      String p = r.activityInfo.packageName;
      if (!protectedPackages.contains(p)) found.put(p, new App(p, r.loadLabel(pm).toString()));
    }
    cached = new ArrayList<>(found.values());
    cached.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));
    refreshed = android.os.SystemClock.elapsedRealtime();
    return new ArrayList<>(cached);
  }

  public static boolean allowed(Context c, String pkg) {
    for (App a : apps(c)) if (a.pkg.equals(pkg)) return true;
    return false;
  }

  public static String label(Context c, String pkg) {
    for (App a : apps(c)) if (a.pkg.equals(pkg)) return a.label;
    return pkg;
  }

  public static void invalidate() {
    refreshed = 0;
    cached = new ArrayList<>();
  }
}
