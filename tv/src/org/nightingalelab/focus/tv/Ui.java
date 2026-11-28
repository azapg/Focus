package org.nightingalelab.focus.tv;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class Ui {
  public static final int BG = Color.BLACK,
      FG = 0xfffafafa,
      SUB = 0xffb8b8c2,
      MUTED = 0xff8a8a8a,
      SECONDARY = 0xff161619,
      BORDER = 0xff262629,
      GLASS = 0x16ffffff,
      GLASS_HI = 0x24ffffff,
      GLASS_BORDER = 0x1effffff,
      SHEET_BG = 0xf216161a,
      ORANGE = 0xfff59e0b,
      RED = 0xffef4444;

  public static GradientDrawable tvBackground() {
    GradientDrawable bg = new GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        new int[] {0xff141418, 0xff050507, 0xff000000});
    return bg;
  }

  public static void applyFocusLift(View v, float scale) {
    final View.OnFocusChangeListener previous = v.getOnFocusChangeListener();
    v.setOnFocusChangeListener(
        (view, focused) -> {
          if (previous != null) previous.onFocusChange(view, focused);
          view.animate()
              .scaleX(focused ? scale : 1f)
              .scaleY(focused ? scale : 1f)
              .translationZ(focused ? dp(view.getContext(), 8) : 0f)
              .setDuration(140)
              .start();
        });
  }

  public static Button glassButton(Context c, String label, Runnable action) {
    Button b = new Button(c);
    b.setText(label);
    b.setTextSize(15);
    b.setTypeface(c.getResources().getFont(R.font.geist_regular));
    b.setAllCaps(false);
    b.setMinHeight(dp(c, 46));
    b.setMinimumHeight(dp(c, 46));
    b.setMinWidth(0);
    b.setPadding(dp(c, 22), dp(c, 8), dp(c, 22), dp(c, 8));
    StateListDrawable bg = new StateListDrawable();
    bg.addState(new int[] {android.R.attr.state_focused}, shape(FG, FG, 999, c));
    bg.addState(new int[] {android.R.attr.state_pressed}, shape(FG, FG, 999, c));
    bg.addState(new int[] {}, shape(GLASS_HI, GLASS_BORDER, 999, c));
    b.setBackground(bg);
    b.setTextColor(
        new ColorStateList(
            new int[][] {
              new int[] {android.R.attr.state_focused},
              new int[] {android.R.attr.state_pressed},
              new int[] {}
            },
            new int[] {BG, BG, FG}));
    b.setOnClickListener(v -> action.run());
    b.setFocusable(true);
    applyFocusLift(b, 1.03f);
    return b;
  }

  public static Button dockTab(Context c, String label, boolean selected, Runnable action) {
    Button b = new Button(c);
    b.setText(label);
    b.setTextSize(14);
    b.setTypeface(c.getResources().getFont(R.font.geist_regular));
    b.setAllCaps(false);
    b.setMinHeight(dp(c, 38));
    b.setMinimumHeight(dp(c, 38));
    b.setMinWidth(0);
    b.setPadding(dp(c, 16), dp(c, 6), dp(c, 16), dp(c, 6));
    StateListDrawable bg = new StateListDrawable();
    bg.addState(new int[] {android.R.attr.state_focused}, shape(FG, FG, 999, c));
    bg.addState(new int[] {android.R.attr.state_pressed}, shape(FG, FG, 999, c));
    bg.addState(new int[] {android.R.attr.state_selected}, shape(0x22ffffff, 0, 999, c));
    bg.addState(new int[] {}, shape(0x00000000, 0, 999, c));
    b.setBackground(bg);
    b.setSelected(selected);
    b.setTextColor(
        new ColorStateList(
            new int[][] {
              new int[] {android.R.attr.state_focused},
              new int[] {android.R.attr.state_pressed},
              new int[] {android.R.attr.state_selected},
              new int[] {}
            },
            new int[] {BG, BG, FG, SUB}));
    b.setOnClickListener(v -> action.run());
    b.setFocusable(true);
    applyFocusLift(b, 1.03f);
    return b;
  }

  public static LinearLayout controlTile(
      Context c, String title, String value, int valueColor, boolean active, Runnable action) {
    LinearLayout tile = column(c);
    tile.setFocusable(true);
    tile.setClickable(true);
    tile.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
    tile.setContentDescription(title + ", " + value);
    TextView t = text(c, title, 14, FG);
    TextView v = text(c, value, 12, valueColor);
    t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    tile.addView(t);
    gap(tile, 3);
    tile.addView(v);
    StateListDrawable bg = new StateListDrawable();
    bg.addState(new int[] {android.R.attr.state_focused}, shape(FG, FG, 14, c));
    bg.addState(new int[] {android.R.attr.state_pressed}, shape(FG, FG, 14, c));
    bg.addState(new int[] {}, shape(active ? GLASS_HI : GLASS, active ? 0x44ffffff : GLASS_BORDER, 14, c));
    tile.setBackground(bg);
    final int focusedValueColor =
        valueColor == RED ? 0xffb91c1c : valueColor == ORANGE ? 0xffb45309 : 0xff444444;
    tile.setOnFocusChangeListener(
        (view, focused) -> {
          t.setTextColor(focused ? BG : FG);
          v.setTextColor(focused ? focusedValueColor : valueColor);
          view.animate()
              .scaleX(focused ? 1.03f : 1f)
              .scaleY(focused ? 1.03f : 1f)
              .translationZ(focused ? dp(c, 8) : 0f)
              .setDuration(140)
              .start();
        });
    tile.setOnClickListener(view -> action.run());
    buttonSemantics(tile);
    return tile;
  }

  public static int dp(Context c, float n) {
    return Math.round(n * c.getResources().getDisplayMetrics().density);
  }

  public static GradientDrawable shape(int color, int border, int radius, Context c) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radius));
    if (border != 0) d.setStroke(dp(c, 1), border);
    return d;
  }

  public static LinearLayout column(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  public static LinearLayout row(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  public static TextView text(Context c, String value, int size, int color) {
    TextView v = new TextView(c);
    v.setText(value);
    v.setTextColor(color);
    v.setTextSize(size);
    v.setTypeface(c.getResources().getFont(R.font.geist_regular));
    v.setFontFeatureSettings("tnum");
    return v;
  }

  public static Button button(Context c, String label, Runnable action) {
    Button b = new Button(c);
    b.setText(label);
    b.setTextSize(16);
    b.setTypeface(c.getResources().getFont(R.font.geist_regular));
    b.setAllCaps(false);
    b.setMinHeight(dp(c, 44));
    b.setMinimumHeight(dp(c, 44));
    b.setMinWidth(0);
    b.setPadding(dp(c, 18), dp(c, 7), dp(c, 18), dp(c, 7));
    StateListDrawable bg = new StateListDrawable();
    bg.addState(new int[] {android.R.attr.state_focused}, shape(FG, FG, 24, c));
    bg.addState(new int[] {android.R.attr.state_pressed}, shape(FG, FG, 24, c));
    bg.addState(new int[] {android.R.attr.state_selected}, shape(0xff262629, 0xff3a3a3f, 24, c));
    bg.addState(new int[] {}, shape(SECONDARY, BORDER, 24, c));
    b.setBackground(bg);
    b.setTextColor(
        new ColorStateList(
            new int[][] {
              new int[] {android.R.attr.state_focused},
              new int[] {android.R.attr.state_pressed},
              new int[] {}
            },
            new int[] {BG, BG, FG}));
    b.setOnClickListener(v -> action.run());
    b.setFocusable(true);
    return b;
  }

  public static void gap(LinearLayout l, int n) {
    Space s = new Space(l.getContext());
    l.addView(s, new LinearLayout.LayoutParams(dp(l.getContext(), n), dp(l.getContext(), n)));
  }

  static void buttonSemantics(View view) {
    view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
      @Override public void onInitializeAccessibilityNodeInfo(
          View host, android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(host, info);
        info.setClassName(Button.class.getName());
      }
    });
  }

  public static void add(LinearLayout l, View v, int width, int height) {
    l.addView(
        v,
        new LinearLayout.LayoutParams(
            width < 0 ? width : dp(l.getContext(), width),
            height < 0 ? height : dp(l.getContext(), height)));
  }

  public static void fullscreen(Activity a) {
    a.getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    a.getWindow().setStatusBarColor(BG);
    a.getWindow().setNavigationBarColor(BG);
    a.getWindow().setBackgroundDrawableResource(android.R.color.black);
  }

  public static String time(long millis) {
    long mins = millis / 60000;
    if (millis > 0 && mins == 0) return Math.max(1, millis / 1000) + "s";
    return mins >= 60 ? (mins / 60) + "h " + (mins % 60) + "m" : mins + "m";
  }

  public static String countdown(long deadline) {
    long sec = Math.max(0, (deadline - android.os.SystemClock.elapsedRealtime() + 999) / 1000);
    return (sec / 60) + ":" + String.format(Locale.ROOT, "%02d", sec % 60);
  }

  public static LinearLayout screen(Activity a) {
    LinearLayout l = column(a);
    l.setBackgroundColor(BG);
    l.setPadding(dp(a, 42), dp(a, 25), dp(a, 42), dp(a, 25));
    return l;
  }

  public static Dialog dialog(Activity a, String title, LinearLayout body) {
    Dialog d = new Dialog(a);
    LinearLayout root = column(a);
    root.setPadding(dp(a, 28), dp(a, 22), dp(a, 28), dp(a, 22));
    root.setBackground(shape(BG, BORDER, 20, a));
    root.addView(text(a, title, 25, FG));
    gap(root, 18);
    root.addView(body);
    gap(root, 18);
    root.addView(button(a, "Close", d::dismiss));
    d.setContentView(root);
    Window w = d.getWindow();
    w.setBackgroundDrawableResource(android.R.color.transparent);
    w.setLayout(dp(a, 540), WindowManager.LayoutParams.WRAP_CONTENT);
    d.setOnShowListener(
        x -> {
          w.setLayout(dp(a, 540), WindowManager.LayoutParams.WRAP_CONTENT);
          root.setFocusableInTouchMode(false);
          View first = root.getFocusables(View.FOCUS_FORWARD).stream().findFirst().orElse(null);
          if (first != null) first.requestFocus();
        });
    return d;
  }

}
