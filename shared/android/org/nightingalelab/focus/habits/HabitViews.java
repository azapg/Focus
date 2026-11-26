package org.nightingalelab.focus.habits;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.LinearInterpolator;
import android.widget.*;

public final class HabitViews {
  public static final int WHITE = 0xFFFAFAFA;
  public static final int GREY = 0xFFA1A1AA;
  public static final int MUTED = 0xFF8A8A8A;
  public static final int ORANGE = 0xFFF59E0B;
  public static final int RED = 0xFFF87171;
  public static final int RED_STROKE = 0xFFEF4444;
  public static final int CARD = 0xFF0A0A0C;
  public static final int BORDER = 0xFF26262A;
  public static final long HOLD_MS = 650;
  private static final long UNDO_MS = 5000;

  public static int dp(Context context, float value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  public static TextView text(Context context, String value, float size, int color) {
    TextView text = new TextView(context);
    text.setText(value);
    text.setTextColor(color);
    text.setTextSize(size);
    text.setFontFeatureSettings("tnum");
    return text;
  }

  public static TextView action(Context context, String label, Runnable run) {
    TextView button = text(context, label, 13, WHITE);
    button.setGravity(Gravity.CENTER_VERTICAL);
    button.setPadding(dp(context, 8), dp(context, 9), dp(context, 8), dp(context, 9));
    button.setOnClickListener(v -> run.run());
    button.setFocusable(true);
    return button;
  }

  public static LinearLayout row(Context context) {
    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    return row;
  }

  public static LinearLayout column(Context context) {
    LinearLayout column = new LinearLayout(context);
    column.setOrientation(LinearLayout.VERTICAL);
    return column;
  }

  public static GradientDrawable shape(Context context, int color, int border, int radius) {
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(color);
    shape.setCornerRadius(dp(context, radius));
    shape.setStroke(dp(context, 1), border);
    return shape;
  }

  public static final class Hold extends FrameLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable completed;
    private ValueAnimator animator;
    private float fill;
    private int key = KeyEvent.KEYCODE_UNKNOWN;
    private boolean settling;
    private static int activeHolds;

    public static boolean active() { return activeHolds > 0; }

    public Hold(Context context, Habits.Status status, float size, boolean coins, Runnable completed, Runnable showStreak) {
      super(context);
      this.completed = completed;
      setBackground(shape(context, CARD, BORDER, 11));
      setClipToOutline(true);
      setFocusable(true);
      setClickable(true);
      setContentDescription(status.habit.name + ", " + status.dueLabel());
      LinearLayout row = row(context);
      row.setPadding(dp(context, 12), dp(context, 9), dp(context, 12), dp(context, 9));
      LinearLayout label = column(context);
      TextView name = text(context, status.habit.name, size, WHITE);
      name.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
      label.addView(name);
      String due = status.dueLabel();
      int dueColor = status.overdue || status.grace ? RED : ORANGE;
      SpannableString detail = new SpannableString(due + " · " + status.habit.cadence());
      detail.setSpan(new ForegroundColorSpan(dueColor), 0, due.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      TextView caption = text(context, "", size - 2, MUTED);
      caption.setText(detail);
      label.addView(caption);
      row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
      String streakLabel = status.habit.streakLabel(status.streak);
      TextView streak = showStreak == null ? text(context, streakLabel, size - 1, GREY)
          : action(context, streakLabel, showStreak);
      if (showStreak != null) streak.setContentDescription("Streak for " + status.habit.name + " " + status.streak);
      row.addView(streak);
      TextView credit = text(context, rewardLabel(status.habit.reward, coins), size - 1, GREY);
      credit.setPadding(dp(context, 9), 0, 0, 0);
      row.addView(credit);
      addView(row, new LayoutParams(-1, -2));
      setOnFocusChangeListener((view, focused) -> {
        setBackground(shape(context, CARD, focused ? WHITE : BORDER, 11));
        if (!focused) cancel();
      });
    }

    @Override protected void dispatchDraw(Canvas canvas) {
      if (fill > 0) {
        paint.setColor(settling ? 0x38FFFFFF : 0x24FFFFFF);
        canvas.drawRect(0, 0, getWidth() * fill, getHeight(), paint);
      }
      super.dispatchDraw(canvas);
    }

    private void start() {
      if (animator != null || settling) return;
      activeHolds++;
      animator = ValueAnimator.ofFloat(0, 1);
      animator.setDuration(HOLD_MS);
      animator.setInterpolator(new LinearInterpolator());
      animator.addUpdateListener(value -> { fill = (float) value.getAnimatedValue(); invalidate(); });
      animator.start();
      postDelayed(held, HOLD_MS);
    }

    private final Runnable held = () -> {
      if (animator == null) return;
      animator.cancel();
      animator = null;
      finish();
    };

    private void finish() {
      settling = true;
      fill = 1;
      invalidate();
      animate().translationX(dp(getContext(), 18)).scaleX(.97f).alpha(0).setStartDelay(80)
          .setDuration(230).withEndAction(() -> {
            settling = false;
            activeHolds--;
            completed.run();
          }).start();
    }

    private void cancel() {
      if (animator == null) return;
      removeCallbacks(held);
      ValueAnimator current = animator;
      animator = null;
      current.cancel();
      activeHolds--;
      fill = 0;
      invalidate();
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent e) {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          getParent().requestDisallowInterceptTouchEvent(true);
          start();
          return true;
        case MotionEvent.ACTION_MOVE:
          if (e.getX() < 0 || e.getX() > getWidth() || e.getY() < 0 || e.getY() > getHeight()) cancel();
          return true;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          cancel();
          return true;
        default: return true;
      }
    }

    private static boolean confirm(int code) {
      return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
          || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_SPACE;
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
      if (!confirm(code)) return super.onKeyDown(code, event);
      if (event.getRepeatCount() == 0) { key = code; start(); }
      return true;
    }

    @Override public boolean onKeyUp(int code, KeyEvent event) {
      if (!confirm(code)) return super.onKeyUp(code, event);
      if (key == code) { key = KeyEvent.KEYCODE_UNKNOWN; cancel(); }
      return true;
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo node) {
      super.onInitializeAccessibilityNodeInfo(node);
      node.setClassName(Button.class.getName());
      node.addAction(new AccessibilityNodeInfo.AccessibilityAction(
          AccessibilityNodeInfo.ACTION_LONG_CLICK, "Complete habit"));
      node.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
      if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) { start(); return true; }
      return super.performAccessibilityAction(action, arguments);
    }

    @Override protected void onDetachedFromWindow() {
      cancel();
      if (settling) {
        settling = false;
        animate().withEndAction(null).cancel();
        activeHolds--;
      }
      super.onDetachedFromWindow();
    }
  }

  public static void dismissOnSwipe(ViewGroup view, Runnable dismiss) {
    view.setOnTouchListener(new View.OnTouchListener() {
      float start;
      public boolean onTouch(View target, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
          start = event.getRawX();
          return true;
        }
        float distance = event.getRawX() - start;
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) target.setTranslationX(distance);
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
          if (event.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(distance) > dp(target.getContext(), 56)) dismiss.run();
          else target.animate().translationX(0).setDuration(140).start();
        }
        return true;
      }
    });
  }

  private static final int UNDO_ACTION_ID = View.generateViewId();

  public static String rewardLabel(int amount, boolean coins) {
    return "+" + amount + (coins ? " coins" : "m");
  }

  public static void undo(FrameLayout host, String title, String reward, int margin, Runnable undo) {
    Context context = host.getContext();
    View previous = host.findViewWithTag("habitUndo");
    if (previous != null) host.removeView(previous);
    FrameLayout toast = new FrameLayout(context);
    toast.setTag("habitUndo");
    toast.setBackground(shape(context, 0xFF101013, BORDER, 12));
    toast.setClipToOutline(true);
    LinearLayout content = row(context);
    content.setPadding(dp(context, 12), 0, dp(context, 6), 0);
    content.addView(text(context, title + "  " + reward, 13, GREY), new LinearLayout.LayoutParams(0, -2, 1));
    TextView undoAction = action(context, "Undo", () -> { host.removeView(toast); undo.run(); });
    undoAction.setId(UNDO_ACTION_ID);
    content.addView(undoAction);
    toast.addView(content, new FrameLayout.LayoutParams(-1, -2));
    View timer = new View(context);
    timer.setBackgroundColor(0x80FAFAFA);
    toast.addView(timer, new FrameLayout.LayoutParams(-1, dp(context, 2), Gravity.BOTTOM));
    FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(context, 290), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    p.bottomMargin = dp(context, margin);
    host.addView(toast, p);
    toast.setTranslationY(dp(context, 7));
    toast.setAlpha(0);
    toast.animate().translationY(0).alpha(1).setDuration(180).start();
    timer.setPivotX(0);
    timer.animate().scaleX(0).setInterpolator(new LinearInterpolator()).setDuration(UNDO_MS).start();
    toast.postDelayed(() -> host.removeView(toast), UNDO_MS);
    toast.setOnTouchListener(new View.OnTouchListener() {
      float down;
      public boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
          case MotionEvent.ACTION_DOWN: down = event.getRawX(); return true;
          case MotionEvent.ACTION_MOVE: view.setTranslationX(event.getRawX() - down); return true;
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
            if (event.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(view.getTranslationX()) > dp(context, 56)) {
              view.animate().translationX(Math.copySign(dp(context, 300), view.getTranslationX()))
                  .alpha(0).setDuration(160).withEndAction(() -> host.removeView(toast)).start();
            } else view.animate().translationX(0).setDuration(140).start();
            return true;
          default: return true;
        }
      }
    });
  }
}
