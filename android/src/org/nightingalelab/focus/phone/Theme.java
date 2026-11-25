package org.nightingalelab.focus.phone;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.regex.*;

/** Visual design tokens, pure OLED surfaces, charts, and crisp stroke icons. */
public final class Theme {
  public static final int BG = 0xFF000000;
  public static final int FG = 0xFFFAFAFA;
  public static final int STORY_GRAY = 0xFF5A5A62;
  public static final int EXPLAIN = 0xFFB4B4B4;
  public static final int MUTED = 0xFF8A8A8A;
  public static final int DIM = 0xFF5C5C5C;
  public static final int LINE = 0xFF1F1F23;
  public static final int DIVIDER = 0xFF141417;
  public static final int SEC = 0xFF161619;
  public static final int DARK_ACTIVE = 0xFF222225;
  public static final int CARD = 0xFF060607;
  public static final int SHEET = 0xFF0A0A0C;
  public static final int GLASS_FILL = 0xD908080A;
  public static final int GLASS_SHEET_FILL = 0xF208080B;
  public static final int GLASS_PILL_ACTIVE = 0xFF222225;
  public static final int GLASS_ICON_BG = 0x14FFFFFF;
  public static final int HOLD_FILL = 0xFF222225;
  public static final int CREATE = 0xFFD4D4D8;
  public static final int CONSUME = 0xFF46464D;
  public static final int BAR_GRAY = 0xFF52525B;
  public static final int TRACK = 0xFF1A1A1E;
  public static final int TOGGLE_ON = 0xFF3A3A40;
  public static final int TOGGLE_OFF = 0xFF1C1C20;
  public static final int KNOB_ON = 0xFFD4D4D8;
  public static final int KNOB_OFF = 0xFF71717A;
  public static final int WARN = 0xFFF59E0B; // Orange for slips, drift, and approaching limits
  public static final int BAD = 0xFFEF4444; // Red for rings, bars, and hard breaches
  public static final int BAD_TEXT = 0xFFF87171; // Red text on pure OLED black
  public static final int ICE = 0xFFCBE2FF; // Ice blue-white for earned long single-app focus
  public static final int ICE_SOFT = 0xFF9BB8DC; // Muted ice for secondary focus text
  public static final int GREEN = 0xFF8A8A8A; // Keep monochrome on OLED
  /** Neutral emphasis for non-breach notes. */
  public static final int ATTN = 0xFFD4D4D8;
  public static final int SUB = 0xFFA1A1AA;
  public static final int SEL_BORDER = 0xFF3A3A3F;
  public static final int[] HEAT = {0xFF0F0F11, 0xFF26262B, 0xFF3F3F46, 0xFF71717A, 0xFFA1A1AA};

  public static int dp(Context c, float n) {
    return Math.round(n * c.getResources().getDisplayMetrics().density);
  }

  public static void applySystemBars(Activity a) {
    Window w = a.getWindow();
    w.setStatusBarColor(BG);
    w.setNavigationBarColor(BG);
    w.setBackgroundDrawableResource(android.R.color.black);
  }

  public static void applySheetBlur(Window w) {
    if (w == null) return;
    if (Build.VERSION.SDK_INT >= 31) {
      try {
        w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.setBlurBehindRadius(28);
        w.setAttributes(lp);
        w.setBackgroundBlurRadius(32);
      } catch (RuntimeException ignored) {}
    }
  }

  public static GradientDrawable roundRect(Context c, int fill, int border, float radiusDp) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(dp(c, radiusDp));
    if (border != 0) d.setStroke(Math.max(1, dp(c, 1)), border);
    return d;
  }

  public static GradientDrawable topRounded(Context c, int fill, int border, float radiusDp) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    float r = dp(c, radiusDp);
    d.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
    if (border != 0) d.setStroke(Math.max(1, dp(c, 1)), border);
    return d;
  }

  /** Obsidian glass surface: near-black translucent fill + subtle specular rim. */
  public static Drawable obsidianGlass(Context c, float radiusDp) {
    return new ObsidianGlassDrawable(c, GLASS_FILL, radiusDp, false);
  }

  /** Top-rounded obsidian glass surface for modal bottom sheets. */
  public static Drawable obsidianGlassTop(Context c, float radiusDp) {
    return new ObsidianGlassDrawable(c, GLASS_SHEET_FILL, radiusDp, true);
  }

  /** Pure-black bottom fade scrim so scrolling rows never bleed behind floating dock or search pill. */
  public static Drawable bottomScrim() {
    return new GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        new int[] {0x00000000, 0xD9000000, 0xFF000000});
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

  public static TextView text(Context c, CharSequence value, float sp, int color, boolean medium) {
    TextView v = new TextView(c);
    v.setText(value);
    v.setTextSize(sp);
    v.setTextColor(color);
    v.setFontFeatureSettings("tnum");
    v.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
    return v;
  }

  static EditText appSearchInput(Context c) {
    EditText input = new EditText(c);
    input.setHint("Search apps");
    input.setHintTextColor(MUTED);
    input.setTextColor(FG);
    input.setTextSize(14f);
    input.setSingleLine(true);
    input.setBackground(null);
    input.setPadding(0, dp(c, 10), 0, dp(c, 10));
    return input;
  }

  public static void gap(LinearLayout l, float dpSize) {
    Space s = new Space(l.getContext());
    int px = dp(l.getContext(), dpSize);
    l.addView(s, new LinearLayout.LayoutParams(px, px));
  }

  public static View divider(Context c, int leftDp, int rightDp) {
    View v = new View(c);
    v.setBackgroundColor(DIVIDER);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.75f)));
    lp.leftMargin = dp(c, leftDp);
    lp.rightMargin = dp(c, rightDp);
    v.setLayoutParams(lp);
    return v;
  }

  /** Bottom-sheet header: title on the left, a quiet close button on the right. */
  public static View sheetHeader(Context c, String title, Runnable onClose) {
    LinearLayout row = row(c);
    row.addView(text(c, title, 15.5f, FG, true),
        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    FrameLayout close = new FrameLayout(c);
    close.setContentDescription("Close");
    close.addView(new IconView(c, IconView.Kind.CLOSE, SUB, 15),
        new FrameLayout.LayoutParams(dp(c, 15), dp(c, 15), Gravity.CENTER));
    close.setOnClickListener(v -> onClose.run());
    row.addView(close, new LinearLayout.LayoutParams(dp(c, 30), dp(c, 30)));
    return row;
  }

  /** Small selectable chip used in sheets: dark #222225 when selected, outline otherwise. */
  public static TextView pickChip(Context c, String label, boolean on) {
    TextView chip = text(c, label, 12.5f, on ? FG : SUB, on);
    chip.setPadding(dp(c, 13), dp(c, 7), dp(c, 13), dp(c, 7));
    chip.setBackground(roundRect(c, on ? DARK_ACTIVE : Color.TRANSPARENT, on ? SEL_BORDER : LINE, 999));
    return chip;
  }

  /** Dark OLED primary pill button (no bright white fill). */
  public static TextView primaryPillButton(Context c, String label, Runnable onClick) {
    TextView b = text(c, label, 13.5f, FG, true);
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
    b.setBackground(roundRect(c, DARK_ACTIVE, 0xFF333338, 999));
    b.setOnClickListener(v -> onClick.run());
    return b;
  }

  /** Dark-theme translucent pill action button for popovers and cards. */
  public static TextView glassPillButton(Context c, String label, Runnable onClick) {
    TextView b = text(c, label, 13, FG, true);
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(c, 16), dp(c, 11), dp(c, 16), dp(c, 11));
    b.setBackground(roundRect(c, DARK_ACTIVE, 0xFF333338, 999));
    b.setOnClickListener(v -> onClick.run());
    return b;
  }

  public static TextView outlinePillButton(Context c, String label, Runnable onClick) {
    TextView b = text(c, label, 13, FG, false);
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
    b.setBackground(roundRect(c, Color.TRANSPARENT, LINE, 999));
    b.setOnClickListener(v -> onClick.run());
    return b;
  }

  /** Highlights percentages and durations inside explanation copy in white medium weight. */
  public static SpannableString highlightStats(String text, String... boldPhrases) {
    SpannableString ss = new SpannableString(text);
    Matcher m = Pattern.compile("\\b\\d+%\\s+creating|\\b\\d+%|\\b\\d+\\s+of\\s+\\d+").matcher(text);
    while (m.find()) {
      ss.setSpan(new ForegroundColorSpan(FG), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      ss.setSpan(new StyleSpan(Typeface.BOLD), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    for (String phrase : boldPhrases) {
      if (phrase == null || phrase.isEmpty()) continue;
      int idx = text.indexOf(phrase);
      if (idx >= 0) {
        ss.setSpan(new ForegroundColorSpan(FG), idx, idx + phrase.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new StyleSpan(Typeface.BOLD), idx, idx + phrase.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
    }
    return ss;
  }

  /** Custom drawable painting translucent obsidian fill + subtle diagonal specular rim. */
  public static final class ObsidianGlassDrawable extends Drawable {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radiusPx;
    private final float strokePx;
    private final boolean topOnly;
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private int lastW = -1;
    private int lastH = -1;

    ObsidianGlassDrawable(Context c, int fillColor, float radiusDp, boolean topOnly) {
      this.radiusPx = dp(c, radiusDp);
      this.strokePx = Math.max(1f, c.getResources().getDisplayMetrics().density);
      this.topOnly = topOnly;
      fillPaint.setStyle(Paint.Style.FILL);
      fillPaint.setColor(fillColor);
      rimPaint.setStyle(Paint.Style.STROKE);
      rimPaint.setStrokeWidth(strokePx);
    }

    @Override
    public void draw(Canvas canvas) {
      Rect b = getBounds();
      int w = b.width();
      int h = b.height();
      if (w <= 0 || h <= 0) return;

      float half = strokePx / 2f;
      rect.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
      float r = Math.min(radiusPx, Math.min(w, h) / 2f);

      if (w != lastW || h != lastH) {
        lastW = w;
        lastH = h;
        rimPaint.setShader(new LinearGradient(
            b.left, b.top, b.right, b.bottom,
            new int[] {0x2BFFFFFF, 0x0CFFFFFF, 0x07FFFFFF, 0x1EFFFFFF},
            new float[] {0f, 0.36f, 0.66f, 1f},
            Shader.TileMode.CLAMP));
      }

      if (topOnly) {
        path.reset();
        float[] radii = new float[] {r, r, r, r, 0, 0, 0, 0};
        path.addRoundRect(rect, radii, Path.Direction.CW);
        canvas.drawPath(path, fillPaint);
        canvas.drawPath(path, rimPaint);
      } else {
        canvas.drawRoundRect(rect, r, r, fillPaint);
        canvas.drawRoundRect(rect, r, r, rimPaint);
      }
    }

    @Override
    public void getOutline(Outline outline) {
      Rect b = getBounds();
      if (b.isEmpty()) return;
      float r = Math.min(radiusPx, Math.min(b.width(), b.height()) / 2f);
      outline.setRoundRect(b, topOnly ? 0f : r);
    }

    @Override public void setAlpha(int alpha) { fillPaint.setAlpha(alpha); rimPaint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { fillPaint.setColorFilter(cf); rimPaint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
  }

  /** Horizontal proportional bar for breathing progress or simple splits. */
  public static final class SplitBarView extends View {
    private long creating;
    private long consuming;

    public SplitBarView(Context c) {
      super(c);
    }

    public void setValues(long creating, long consuming) {
      this.creating = Math.max(0, creating);
      this.consuming = Math.max(0, consuming);
      invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float w = getWidth();
      float h = getHeight();
      float r = h / 2f;
      Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
      p.setColor(TRACK);
      canvas.drawRoundRect(0, 0, w, h, r, r, p);
      long sum = creating + consuming;
      if (sum <= 0) return;
      float gap = (creating > 0 && consuming > 0) ? dp(getContext(), 2) : 0;
      float avail = Math.max(0, w - gap);
      float cw = avail * creating / (float) sum;
      float kw = avail - cw;
      float segR = dp(getContext(), 3);
      if (cw > 0.5f) {
        p.setColor(CREATE);
        canvas.drawRoundRect(0, 0, cw, h, segR, segR, p);
      }
      if (kw > 0.5f) {
        p.setColor(CONSUME);
        canvas.drawRoundRect(cw + gap, 0, cw + gap + kw, h, segR, segR, p);
      }
    }
  }

  /** Thin rounded proportional bar of automatic categories (grays). */
  public static final class CategoryBarView extends View {
    private List<FocusStats.Category> categories = Collections.emptyList();
    private String selectedId;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();

    public CategoryBarView(Context c) {
      super(c);
    }

    public void setData(List<FocusStats.Category> categories, String selectedId) {
      this.categories = categories == null ? Collections.emptyList() : categories;
      this.selectedId = selectedId;
      invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float w = getWidth();
      float h = getHeight();
      if (w <= 0 || h <= 0) return;
      float r = h / 2f;
      paint.setColor(TRACK);
      canvas.drawRoundRect(0, 0, w, h, r, r, paint);
      long total = 0;
      for (FocusStats.Category c : categories) total += Math.max(0, c.ms);
      if (total <= 0) return;

      canvas.save();
      clip.reset();
      rect.set(0, 0, w, h);
      clip.addRoundRect(rect, r, r, Path.Direction.CW);
      canvas.clipPath(clip);

      float gap = dp(getContext(), 2);
      int n = categories.size();
      float avail = Math.max(1f, w - gap * Math.max(0, n - 1));
      float x = 0f;
      float segR = dp(getContext(), 2);
      for (int i = 0; i < n; i++) {
        FocusStats.Category c = categories.get(i);
        float segW = Math.max(dp(getContext(), 3), avail * (c.ms / (float) total));
        if (i == n - 1) segW = Math.max(segW, w - x);
        int col = c.fine.color;
        if (selectedId != null && !selectedId.equals(c.fine.id)) {
          col = (col & 0x00FFFFFF) | 0x40000000;
        }
        paint.setColor(col);
        canvas.drawRoundRect(x, 0, Math.min(w, x + segW), h, segR, segR, paint);
        x += segW + gap;
        if (x >= w) break;
      }
      canvas.restore();
    }
  }

  /** Circular DNS-off soft limit ring (neutral within limit, orange near limit, red at/over limit). */
  public static final class DnsRingView extends View {
    private float fraction;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    public DnsRingView(Context c) {
      super(c);
      float stroke = dp(c, 5);
      trackPaint.setStyle(Paint.Style.STROKE);
      trackPaint.setStrokeWidth(stroke);
      trackPaint.setColor(TRACK);
      fillPaint.setStyle(Paint.Style.STROKE);
      fillPaint.setStrokeWidth(stroke);
      fillPaint.setStrokeCap(Paint.Cap.ROUND);
      fillPaint.setColor(0xFF71717A);
    }

    public void setFraction(float f) {
      setFraction(f, f >= 1f ? BAD : f >= 0.75f ? WARN : 0xFF71717A);
    }

    public void setFraction(float f, int color) {
      this.fraction = Math.max(0f, Math.min(1f, f));
      fillPaint.setColor(color);
      invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float w = getWidth();
      float h = getHeight();
      float pad = trackPaint.getStrokeWidth() / 2f + 1f;
      oval.set(pad, pad, w - pad, h - pad);
      canvas.drawOval(oval, trackPaint);
      if (fraction > 0.005f) {
        canvas.drawArc(oval, -90f, 360f * fraction, false, fillPaint);
      }
    }
  }

  /** Minimal 24x24 stroked icon view. */
  public static final class IconView extends View {
    public enum Kind {
      PHONE, TV, LAPTOP, GEAR, BACK, CHEVRON, PLUS, UPDATE_CIRCLE, CLOSE,
      GLOBE, SEARCH, UPDOWN, CARET_RIGHT, CARET_DOWN
    }

    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public IconView(Context c, Kind kind, int color, float sizeDp) {
      super(c);
      this.kind = kind;
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
      paint.setColor(color);
      int px = dp(c, sizeDp);
      setLayoutParams(new LinearLayout.LayoutParams(px, px));
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float s = Math.min(getWidth(), getHeight()) / 24f;
      paint.setStrokeWidth(1.9f * s);
      canvas.save();
      canvas.scale(s, s);
      switch (kind) {
        case PHONE:
          canvas.drawRoundRect(7, 2.5f, 17, 21.5f, 2.5f, 2.5f, paint);
          canvas.drawLine(11, 18.5f, 13, 18.5f, paint);
          break;
        case TV:
          canvas.drawRoundRect(2.5f, 5, 21.5f, 17.5f, 2, 2, paint);
          canvas.drawLine(8, 21, 16, 21, paint);
          break;
        case LAPTOP:
          canvas.drawRoundRect(4, 5, 20, 16, 1.5f, 1.5f, paint);
          canvas.drawLine(2, 19.5f, 22, 19.5f, paint);
          break;
        case GEAR:
          canvas.drawCircle(12, 12, 3.2f, paint);
          canvas.drawCircle(12, 12, 7.6f, paint);
          for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            canvas.drawLine(
                (float) (12 + Math.cos(a) * 7.6), (float) (12 + Math.sin(a) * 7.6),
                (float) (12 + Math.cos(a) * 9.8), (float) (12 + Math.sin(a) * 9.8), paint);
          }
          break;
        case BACK: {
          Path p = new Path();
          p.moveTo(15, 6);
          p.lineTo(9, 12);
          p.lineTo(15, 18);
          canvas.drawPath(p, paint);
          break;
        }
        case CHEVRON: {
          Path p = new Path();
          p.moveTo(9, 6);
          p.lineTo(15, 12);
          p.lineTo(9, 18);
          canvas.drawPath(p, paint);
          break;
        }
        case PLUS:
          canvas.drawLine(12, 5, 12, 19, paint);
          canvas.drawLine(5, 12, 19, 12, paint);
          break;
        case UPDATE_CIRCLE: {
          canvas.drawCircle(12, 12, 9f, paint);
          canvas.drawLine(12, 16, 12, 8.2f, paint);
          Path p = new Path();
          p.moveTo(8.5f, 11.5f);
          p.lineTo(12, 8f);
          p.lineTo(15.5f, 11.5f);
          canvas.drawPath(p, paint);
          break;
        }
        case CLOSE:
          canvas.drawLine(7, 7, 17, 17, paint);
          canvas.drawLine(17, 7, 7, 17, paint);
          break;
        case GLOBE:
          canvas.drawCircle(12, 12, 9f, paint);
          canvas.drawLine(3.2f, 12, 20.8f, 12, paint);
          canvas.drawOval(8f, 3.2f, 16f, 20.8f, paint);
          break;
        case SEARCH:
          canvas.drawCircle(11, 11, 6.5f, paint);
          canvas.drawLine(16, 16, 20.5f, 20.5f, paint);
          break;
        case UPDOWN: {
          Path p = new Path();
          p.moveTo(8, 9);
          p.lineTo(12, 5);
          p.lineTo(16, 9);
          p.moveTo(8, 15);
          p.lineTo(12, 19);
          p.lineTo(16, 15);
          canvas.drawPath(p, paint);
          break;
        }
        case CARET_RIGHT: {
          Path p = new Path();
          p.moveTo(10, 8);
          p.lineTo(14, 12);
          p.lineTo(10, 16);
          canvas.drawPath(p, paint);
          break;
        }
        case CARET_DOWN: {
          Path p = new Path();
          p.moveTo(8, 10);
          p.lineTo(12, 14);
          p.lineTo(16, 10);
          canvas.drawPath(p, paint);
          break;
        }
      }
      canvas.restore();
    }
  }
}
