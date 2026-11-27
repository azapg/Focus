package org.nightingalelab.focus.phone;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Sets and manages the intention. It uses the same capsule as Ask: one line with a Set pill,
 * keyboard up. After reading the sentence it shows a short read-back card to Start or Edit.
 */
final class IntentionSheet {
  private final Activity activity;
  private final Runnable onChanged;
  private final Dialog dialog;
  private final LinearLayout sheet;
  private EditText field;
  private Intention.Parsed parsed;

  static void show(Activity activity, Runnable onChanged) {
    new IntentionSheet(activity, onChanged).open();
  }

  private IntentionSheet(Activity activity, Runnable onChanged) {
    this.activity = activity;
    this.onChanged = onChanged;
    dialog = new Dialog(activity);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    dialog.setCanceledOnTouchOutside(true);
    sheet = Theme.column(activity);
    sheet.setPadding(dp(12), dp(4), dp(12), dp(12));
    dialog.setContentView(sheet);
  }

  private void open() {
    Intention current = IntentionStore.get(activity).current();
    if (current == null) compose("");
    else manage(current);
    dialog.show();
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      Theme.applyAskBackdrop(window);
      WindowManager.LayoutParams layout = window.getAttributes();
      layout.y = dp(4);
      window.setAttributes(layout);
      window.setLayout(-1, -2);
      window.setGravity(Gravity.BOTTOM);
      window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
          | (current == null ? WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
              : WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN));
    }
    if (current == null) showKeyboard();
  }

  // --- 1. One line, like Ask ----------------------------------------------------------------------

  private void compose(String text) {
    sheet.removeAllViews();
    LinearLayout capsule = Theme.row(activity);
    capsule.setGravity(Gravity.CENTER_VERTICAL);
    JudgeSheetDialog.styleCapsule(activity, capsule);
    capsule.setMinimumHeight(dp(48));
    capsule.setPadding(dp(14), dp(4), dp(5), dp(4));
    capsule.setElevation(dp(12));

    field = new EditText(activity);
    field.setHint("What are you on?");
    field.setContentDescription("What are you on?");
    field.setHintTextColor(Theme.MUTED);
    field.setTextColor(Theme.FG);
    field.setTextSize(14);
    field.setSingleLine(true);
    field.setBackgroundColor(Color.TRANSPARENT);
    field.setPadding(0, dp(8), dp(8), dp(8));
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    field.setImeOptions(EditorInfo.IME_ACTION_DONE);
    field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(Intention.MAX_RAW)});
    field.setText(text);
    field.setSelection(field.getText().length());
    capsule.addView(field, new LinearLayout.LayoutParams(0, -2, 1));

    Button set = pill("Set", true);
    capsule.addView(set, new LinearLayout.LayoutParams(-2, dp(38)));
    Runnable submit = () -> read(field.getText().toString().trim(), set);
    set.setOnClickListener(v -> submit.run());
    field.setOnEditorActionListener((v, action, event) -> {
      submit.run();
      return true;
    });
    sheet.addView(capsule, new LinearLayout.LayoutParams(-1, -2));
  }

  private void read(String text, Button set) {
    if (text.isEmpty()) return;
    set.setEnabled(false);
    set.setText("Reading…");
    field.setEnabled(false);
    new Thread(() -> {
      Intention.Parsed result = IntentionStore.get(activity).read(text);
      activity.runOnUiThread(() -> {
        if (!dialog.isShowing()) return;
        parsed = result;
        hideKeyboard();
        readBack(text);
      });
    }, "focus-intention-read").start();
  }

  // --- 2. Read-back -------------------------------------------------------------------------------

  private void readBack(String text) {
    sheet.removeAllViews();
    LinearLayout card = card();
    LinearLayout head = Theme.row(activity);
    head.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = Theme.text(activity, parsed.label, 14f, Theme.FG, true);
    head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(Theme.text(activity, duration(parsed.minutes), 12f, Theme.MUTED, false));
    card.addView(head);

    JudgeSheetDialog.ChipFlowLayout chips = new JudgeSheetDialog.ChipFlowLayout(activity, dp(6), dp(6));
    long until = System.currentTimeMillis() + parsed.minutes * Intention.MINUTE;
    chips.addView(chip("Until", IntentionMonitor.clock(activity, until)));
    if (!parsed.doneWhen.isEmpty()) chips.addView(chip("Done when", parsed.doneWhen));
    LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(-1, -2);
    chipsLp.topMargin = dp(9);
    card.addView(chips, chipsLp);

    LinearLayout actions = Theme.row(activity);
    actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    Button edit = pill("Edit", false);
    edit.setOnClickListener(v -> {
      compose(text);
      showKeyboard();
    });
    Button start = pill("Start", true);
    start.setOnClickListener(v -> {
      Intention.Parsed chosen = parsed;
      new Thread(() -> {
        IntentionStore.get(activity).start(chosen);
        IntentionStore.get(activity).flushAsync();
      }, "focus-intention-start").start();
      done();
    });
    actions.addView(edit, new LinearLayout.LayoutParams(-2, dp(36)));
    LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(-2, dp(36));
    startLp.leftMargin = dp(6);
    actions.addView(start, startLp);
    LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
    actionsLp.topMargin = dp(12);
    card.addView(actions, actionsLp);
    sheet.addView(card, new LinearLayout.LayoutParams(-1, -2));
  }

  // --- 3. Running or time's up --------------------------------------------------------------------

  private void manage(Intention current) {
    sheet.removeAllViews();
    long now = System.currentTimeMillis();
    boolean up = current.timeUp(now);
    LinearLayout card = card();
    LinearLayout head = Theme.row(activity);
    head.setGravity(Gravity.CENTER_VERTICAL);
    head.addView(Theme.text(activity, current.label, 14f, Theme.FG, true), new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(Theme.text(activity, up ? "time's up" : Intention.left(current.remainingMs(now)), 12f,
        Theme.MUTED, false));
    card.addView(head);
    Intention.Drift drift = IntentionMonitor.drift;
    String line = up ? current.question()
        : drift.showing() ? drift.text()
        : current.doneWhen.isEmpty() ? "Until " + IntentionMonitor.clock(activity, current.until)
        : "Done when " + current.doneWhen;
    TextView body = Theme.text(activity, line, 12.5f, !up && drift.showing() ? Theme.WARN : Theme.SUB, false);
    LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, -2);
    bodyLp.topMargin = dp(6);
    card.addView(body, bodyLp);

    LinearLayout actions = Theme.row(activity);
    actions.setGravity(Gravity.CENTER_VERTICAL);
    if (up) {
      addAction(actions, pill("Done", true), () -> end("done"));
      addAction(actions, pill("Partly", false), () -> end("partly"));
      addAction(actions, pill("10 more min", false), this::extend);
      addAction(actions, ghost("No"), () -> end("no"));
    } else {
      addAction(actions, pill("Done", true), () -> end("done"));
      addAction(actions, pill("+10 min", false), this::extend);
      addAction(actions, ghost("Change"), () -> {
        compose(current.raw.isEmpty() ? current.label : current.raw);
        showKeyboard();
      });
      addAction(actions, ghost("Clear"), () -> end("cleared"));
    }
    LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
    actionsLp.topMargin = dp(12);
    card.addView(actions, actionsLp);
    sheet.addView(card, new LinearLayout.LayoutParams(-1, -2));
  }

  private void end(String outcome) {
    new Thread(() -> {
      IntentionStore.get(activity).end(outcome);
      IntentionStore.get(activity).flushAsync();
    }, "focus-intention-end").start();
    done();
  }

  private void extend() {
    new Thread(() -> {
      IntentionStore.get(activity).extend(Intention.EXTEND_MINUTES);
      IntentionStore.get(activity).flushAsync();
    }, "focus-intention-extend").start();
    done();
  }

  private void done() {
    hideKeyboard();
    dialog.dismiss();
    // The store updates on a background thread; render once it has.
    activity.getWindow().getDecorView().postDelayed(onChanged, 120);
  }

  // --- Pieces -------------------------------------------------------------------------------------

  private LinearLayout card() {
    LinearLayout card = Theme.column(activity);
    card.setBackground(Theme.askSurface(activity, Theme.ASK_BORDER, 20));
    card.setPadding(dp(14), dp(13), dp(14), dp(13));
    card.setElevation(dp(14));
    return card;
  }

  private void addAction(LinearLayout row, Button button, Runnable action) {
    button.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
    if (row.getChildCount() > 0) lp.leftMargin = dp(6);
    row.addView(button, lp);
  }

  private View chip(String key, String value) {
    LinearLayout chip = Theme.row(activity);
    chip.setGravity(Gravity.CENTER_VERTICAL);
    chip.setBackground(Theme.roundRect(activity, Theme.ASK_CHIP_FILL, Theme.ASK_CHIP_BORDER, 8));
    chip.setPadding(dp(9), dp(4), dp(10), dp(4));
    chip.addView(Theme.text(activity, key + " ", 12f, Theme.SUB, false));
    chip.addView(Theme.text(activity, value, 12f, 0xFFE4E4E7, true));
    return chip;
  }

  private Button pill(String text, boolean primary) {
    Button button = new Button(activity);
    button.setAllCaps(false);
    button.setTextSize(primary ? 13 : 12.5f);
    button.setTextColor(primary ? Theme.FG : 0xFFE4E4E7);
    button.setText(text);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setPadding(dp(primary ? 15 : 14), 0, dp(primary ? 15 : 14), 0);
    button.setBackground(primary
        ? Theme.roundRect(activity, Theme.ASK_PRIMARY_FILL, Theme.ASK_PRIMARY_BORDER, 999)
        : Theme.roundRect(activity, Theme.ASK_SECONDARY_FILL, Theme.ASK_SECONDARY_BORDER, 999));
    button.setStateListAnimator(null);
    return button;
  }

  private Button ghost(String text) {
    Button button = pill(text, false);
    button.setTextColor(Theme.SUB);
    button.setPadding(dp(10), 0, dp(10), 0);
    button.setBackgroundColor(Color.TRANSPARENT);
    return button;
  }

  private static String duration(int minutes) {
    if (minutes < 60) return minutes + " min";
    int h = minutes / 60;
    int m = minutes % 60;
    return m == 0 ? h + (h == 1 ? " hour" : " hours") : h + "h " + m + "m";
  }

  private void showKeyboard() {
    if (field == null || !field.requestFocus()) return;
    field.post(() -> {
      InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
      if (keyboard != null) keyboard.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
    });
  }

  private void hideKeyboard() {
    InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
    if (keyboard != null && field != null && field.getWindowToken() != null) {
      keyboard.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }
  }

  private int dp(int size) { return Theme.dp(activity, size); }
}
