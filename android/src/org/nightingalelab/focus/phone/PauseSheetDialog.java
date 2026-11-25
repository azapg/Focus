package org.nightingalelab.focus.phone;

import android.app.*;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/**
 * Pause bottom sheet matching the approved v8 OLED design:
 * - Single device only (offline or already-paused devices disabled)
 * - 1/5/10/30m capped by device max pause AND remaining daily shared DNS-off limit (1m always allowed)
 * - Names the pause number, wait, and limit left; starts with empty reason; unlocks after wait + reason
 * - Dark #222225 selected states (no bright white fills)
 */
public final class PauseSheetDialog {
  static final int RATING_NOTE_ID = View.generateViewId();
  public static final class DeviceOption {
    public final String id;
    public final String kind; // "phone", "tv", "laptop"
    public final String label;
    public final boolean paused;
    public final boolean online;

    public DeviceOption(String id, String kind, String label, boolean paused, boolean online) {
      this.id = id;
      this.kind = kind;
      this.label = label;
      this.paused = paused;
      this.online = online;
    }
  }

  public interface Listener {
    void onSubmit(DeviceOption device, int minutes, String reason, String afterPlan,
        PausePolicy.CheckIn checkIn, Runnable onDone, java.util.function.Consumer<String> onError);
  }

  public static Dialog show(
      Activity activity,
      List<DeviceOption> options,
      PausePolicy.Config policy,
      int nextPauseNumber,
      long dnsOffUsedTodayMs,
      Listener listener) {
    Dialog dialog = new Dialog(activity);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

    final int totalWaitSec = PausePolicy.waitSecondsFor(policy, nextPauseNumber);
    final long unlockAtElapsed = SystemClock.elapsedRealtime() + totalWaitSec * 1000L;

    final DeviceOption[] selectedDevice = new DeviceOption[1];
    final int[] selectedMinutes = new int[] {1};
    for (DeviceOption opt : options) {
      if (!opt.paused && opt.online) {
        selectedDevice[0] = opt;
        selectedMinutes[0] = PausePolicy.defaultChoice(
            PausePolicy.pauseChoices(policy, opt.kind, dnsOffUsedTodayMs));
        break;
      }
    }

    LinearLayout sheet = Theme.column(activity);
    sheet.setBackground(Theme.obsidianGlassTop(activity, 24));
    sheet.setPadding(Theme.dp(activity, 18), Theme.dp(activity, 10), Theme.dp(activity, 18), Theme.dp(activity, 20));

    // Grab handle
    View grab = new View(activity);
    grab.setBackground(Theme.roundRect(activity, 0xFF333338, 0, 9));
    LinearLayout.LayoutParams grabLp = new LinearLayout.LayoutParams(Theme.dp(activity, 36), Theme.dp(activity, 4));
    grabLp.gravity = Gravity.CENTER_HORIZONTAL;
    grabLp.bottomMargin = Theme.dp(activity, 12);
    sheet.addView(grab, grabLp);

    sheet.addView(Theme.sheetHeader(activity, "Pause DNS", dialog::dismiss));
    Theme.gap(sheet, 2);

    String note = PausePolicy.sheetNote(policy, nextPauseNumber, dnsOffUsedTodayMs);
    sheet.addView(Theme.text(activity, note, 12f, Theme.MUTED, false));

    // Device picker
    Theme.gap(sheet, 14);
    sheet.addView(Theme.text(activity, "Device", 11, Theme.MUTED, false));
    Theme.gap(sheet, 7);
    LinearLayout devRow = Theme.row(activity);
    sheet.addView(devRow);

    // Duration picker
    Theme.gap(sheet, 14);
    TextView durLabel = Theme.text(activity, "Duration", 11, Theme.MUTED, false);
    sheet.addView(durLabel);
    Theme.gap(sheet, 7);
    LinearLayout durRow = Theme.row(activity);
    sheet.addView(durRow);

    Theme.gap(sheet, 14);
    sheet.addView(Theme.text(activity, "Check-in", 11, Theme.MUTED, false));
    final PausePolicy.CheckIn[] checkIn = {null};
    Map<PausePolicy.CheckIn, TextView> checkInOptions = new EnumMap<>(PausePolicy.CheckIn.class);
    LinearLayout feelings = Theme.row(activity);
    sheet.addView(feelings);
    for (PausePolicy.CheckIn feeling : PausePolicy.CheckIn.values()) {
      if (feeling == PausePolicy.CheckIn.SPECIFIC) {
        feelings = Theme.row(activity);
        sheet.addView(feelings);
      }
      TextView option = feedbackButton(activity, feeling.label);
      checkInOptions.put(feeling, option);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      lp.setMargins(0, Theme.dp(activity, 5), Theme.dp(activity, 5), 0);
      feelings.addView(option, lp);
    }

    // Why textarea (starts empty)
    Theme.gap(sheet, 14);
    sheet.addView(Theme.text(activity, "Why?", 11, Theme.MUTED, false));
    Theme.gap(sheet, 7);
    // Starts empty on purpose: every pause needs a freshly typed reason.
    EditText reasonInput = makeTextArea(activity, "Why do you need it?");
    sheet.addView(reasonInput, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(activity, 46)));

    // Error text (neutral: amber is only for drifting and DNS-off)
    TextView errorView = Theme.text(activity, "", 11.5f, Theme.ATTN, false);
    errorView.setVisibility(View.GONE);
    Theme.gap(sheet, 8);
    sheet.addView(errorView);

    // Submit button (dark accent #222225 when ready, no bright white fill)
    TextView submitBtn = Theme.text(activity, "Pause", 13.5f, Theme.DIM, true);
    submitBtn.setGravity(Gravity.CENTER);
    submitBtn.setPadding(Theme.dp(activity, 16), Theme.dp(activity, 12), Theme.dp(activity, 16), Theme.dp(activity, 12));
    submitBtn.setBackground(Theme.roundRect(activity, Theme.SEC, Theme.LINE, 999));
    sheet.addView(submitBtn, new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

    final boolean[] submitting = {false};

    Runnable updateButton = () -> {
      DeviceOption dev = selectedDevice[0];
      long waitRem = Math.max(0, (unlockAtElapsed - SystemClock.elapsedRealtime() + 999) / 1000);
      if (dev != null) {
        if (submitting[0]) {
          submitBtn.setText("Pausing\u2026");
        } else if (waitRem > 0) {
          submitBtn.setText("Wait " + waitRem + "s");
        } else {
          submitBtn.setText("Pause " + dev.label + " for " + PausePolicy.formatDurationChip(selectedMinutes[0]));
        }
      } else {
        submitBtn.setText("No device available");
      }
      boolean reasonOk = PausePolicy.reasonOk(reasonInput.getText().toString());
      boolean ready = !submitting[0] && dev != null && !dev.paused && dev.online
          && reasonOk && checkIn[0] != null && waitRem == 0;
      submitBtn.setEnabled(ready);
      submitBtn.setBackground(Theme.roundRect(
          activity, ready ? Theme.DARK_ACTIVE : Theme.CARD, ready ? 0xFF3A3A40 : Theme.LINE, 999));
      submitBtn.setTextColor(ready ? Theme.FG : Theme.DIM);
    };
    checkInOptions.forEach((feeling, button) -> button.setOnClickListener(v -> {
      checkIn[0] = feeling;
      checkInOptions.forEach((value, option) -> {
        boolean selected = value == feeling;
        option.setSelected(selected);
        option.setTextColor(selected ? Theme.FG : Theme.MUTED);
        int flags = option.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG;
        option.setPaintFlags(selected ? flags | Paint.UNDERLINE_TEXT_FLAG : flags);
      });
      updateButton.run();
    }));

    final Runnable[] rebuildChips = new Runnable[1];
    rebuildChips[0] = () -> {
      devRow.removeAllViews();
      for (int i = 0; i < options.size(); i++) {
        DeviceOption opt = options.get(i);
        boolean active = selectedDevice[0] != null && selectedDevice[0].id.equals(opt.id);
        boolean disabled = opt.paused || !opt.online;
        LinearLayout chip = Theme.row(activity);
        chip.setPadding(Theme.dp(activity, 11), Theme.dp(activity, 7), Theme.dp(activity, 11), Theme.dp(activity, 7));
        chip.setBackground(Theme.roundRect(
            activity, active ? Theme.DARK_ACTIVE : Color.TRANSPARENT, active ? 0xFF3A3A40 : Theme.LINE, 999));
        if (disabled) chip.setAlpha(0.36f);
        Theme.IconView.Kind iconKind = "tv".equals(opt.kind)
            ? Theme.IconView.Kind.TV
            : "laptop".equals(opt.kind) ? Theme.IconView.Kind.LAPTOP : Theme.IconView.Kind.PHONE;
        chip.addView(new Theme.IconView(activity, iconKind, active ? Theme.FG : Theme.MUTED, 13));
        Theme.gap(chip, 5);
        String suffix = opt.paused ? " · paused" : !opt.online ? " · offline" : "";
        chip.addView(Theme.text(activity, opt.label + suffix, 12.5f, active ? Theme.FG : Theme.MUTED, active));
        if (!disabled) {
          chip.setOnClickListener(v -> {
            selectedDevice[0] = opt;
            List<PausePolicy.Choice> ch = PausePolicy.pauseChoices(policy, opt.kind, dnsOffUsedTodayMs);
            boolean keep = false;
            for (PausePolicy.Choice c : ch) if (c.minutes == selectedMinutes[0] && c.allowed) keep = true;
            if (!keep) selectedMinutes[0] = PausePolicy.defaultChoice(ch);
            rebuildChips[0].run();
          });
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (i > 0) clp.leftMargin = Theme.dp(activity, 6);
        devRow.addView(chip, clp);
      }

      durRow.removeAllViews();
      DeviceOption dev = selectedDevice[0];
      if (dev != null) {
        List<PausePolicy.Choice> choices = PausePolicy.pauseChoices(policy, dev.kind, dnsOffUsedTodayMs);
        for (int i = 0; i < choices.size(); i++) {
          PausePolicy.Choice c = choices.get(i);
          boolean active = selectedMinutes[0] == c.minutes;
          TextView chip = Theme.text(activity, PausePolicy.formatDurationChip(c.minutes), 12.5f,
              active ? Theme.FG : Theme.MUTED, active);
          chip.setPadding(Theme.dp(activity, 13), Theme.dp(activity, 7), Theme.dp(activity, 13), Theme.dp(activity, 7));
          chip.setBackground(Theme.roundRect(
              activity, active ? Theme.DARK_ACTIVE : Color.TRANSPARENT, active ? 0xFF3A3A40 : Theme.LINE, 999));
          if (!c.allowed) {
            chip.setAlpha(0.32f);
          } else {
            chip.setOnClickListener(v -> {
              selectedMinutes[0] = c.minutes;
              rebuildChips[0].run();
            });
          }
          LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
          if (i > 0) dlp.leftMargin = Theme.dp(activity, 6);
          durRow.addView(chip, dlp);
        }
      }
      updateButton.run();
    };
    rebuildChips[0].run();

    TextWatcher watcher = new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
      @Override public void afterTextChanged(Editable s) { updateButton.run(); }
    };
    reasonInput.addTextChangedListener(watcher);

    submitBtn.setOnClickListener(v -> {
      if (!submitBtn.isEnabled() || selectedDevice[0] == null) return;
      submitting[0] = true;
      errorView.setVisibility(View.GONE);
      updateButton.run();
      listener.onSubmit(
          selectedDevice[0],
          selectedMinutes[0],
          reasonInput.getText().toString().trim(),
          "",
          checkIn[0],
          () -> dialog.dismiss(),
          err -> {
            submitting[0] = false;
            errorView.setText(err);
            errorView.setVisibility(View.VISIBLE);
            updateButton.run();
          });
    });

    Handler handler = new Handler(Looper.getMainLooper());
    Runnable ticker = new Runnable() {
      @Override
      public void run() {
        if (!dialog.isShowing()) return;
        updateButton.run(); // "Wait 15s" counts down on the button, then unlocks with a reason
        if (SystemClock.elapsedRealtime() < unlockAtElapsed + 1000) handler.postDelayed(this, 250);
      }
    };

    ScrollView scroller = new ScrollView(activity);
    scroller.addView(sheet);
    dialog.setContentView(scroller);

    Window w = dialog.getWindow();
    if (w != null) {
      w.setBackgroundDrawableResource(android.R.color.transparent);
      w.setGravity(Gravity.BOTTOM);
      w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
      w.setDimAmount(0.72f);
      Theme.applySheetBlur(w);
    }
    dialog.setOnShowListener(d -> handler.post(ticker));
    dialog.setOnDismissListener(d -> handler.removeCallbacksAndMessages(null));
    dialog.show();
    return dialog;
  }

  private static TextView feedbackButton(Context c, String label) {
    TextView button = Theme.text(c, label, 13f, Theme.MUTED, true);
    button.setGravity(Gravity.CENTER);
    button.setMinHeight(Theme.dp(c, 44));
    button.setPadding(Theme.dp(c, 10), 0, Theme.dp(c, 10), 0);
    android.util.TypedValue background = new android.util.TypedValue();
    if (c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true)) {
      button.setBackgroundResource(background.resourceId);
    }
    button.setFocusable(true);
    return button;
  }

  static Dialog showRating(Activity activity, long started, long ended, String savedNote, Runnable onSaved) {
    Store store = Store.get(activity);
    if (activity.isFinishing() || !store.canRateDnsPause(started, ended)) return null;
    FocusNotifications.dismissRating(activity, started, ended);
    Dialog dialog = new Dialog(activity);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout sheet = Theme.column(activity);
    sheet.setPadding(Theme.dp(activity, 18), Theme.dp(activity, 12), Theme.dp(activity, 18), Theme.dp(activity, 20));
    sheet.setBackground(Theme.obsidianGlassTop(activity, 24));
    sheet.addView(Theme.sheetHeader(activity, "Was that worth it?", dialog::dismiss));
    Theme.gap(sheet, 10);
    EditText note = makeTextArea(activity, "Note");
    note.setId(RATING_NOTE_ID);
    note.setFilters(new InputFilter[] {new InputFilter.LengthFilter(PausePolicy.RATING_NOTE_LIMIT)});
    note.setContentDescription("Optional rating note");
    note.setText(savedNote);
    sheet.addView(note);
    Theme.gap(sheet, 10);
    LinearLayout scores = Theme.row(activity);
    sheet.addView(scores);
    for (int score = 1; score <= 5; score++) {
      final int value = score;
      TextView button = feedbackButton(activity, String.valueOf(score));
      button.setTextColor(Theme.FG);
      button.setContentDescription(score + " out of 5");
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Theme.dp(activity, 44), 1f);
      if (score > 1) lp.leftMargin = Theme.dp(activity, 6);
      scores.addView(button, lp);
      button.setOnClickListener(v -> {
        boolean saved = store.rateDnsPause(started, ended, value, note.getText().toString());
        dialog.dismiss();
        if (saved) onSaved.run();
      });
    }
    sheet.setFocusableInTouchMode(true);
    sheet.requestFocus();
    dialog.setContentView(sheet);
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawableResource(android.R.color.transparent);
      window.setGravity(Gravity.BOTTOM);
      window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
      window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
      Theme.applySheetBlur(window);
    }
    Handler handler = new Handler(Looper.getMainLooper());
    dialog.setOnDismissListener(d -> handler.removeCallbacksAndMessages(null));
    dialog.show();
    handler.postDelayed(dialog::dismiss, Math.max(0, ended + PausePolicy.RATING_WINDOW_MS - System.currentTimeMillis()));
    return dialog;
  }

  private static EditText makeTextArea(Context c, String hint) {
    EditText e = new EditText(c);
    e.setHint(hint);
    e.setHintTextColor(Theme.DIM);
    e.setTextColor(Theme.FG);
    e.setTextSize(13.5f);
    e.setGravity(Gravity.TOP | Gravity.START);
    e.setPadding(Theme.dp(c, 12), Theme.dp(c, 10), Theme.dp(c, 12), Theme.dp(c, 10));
    e.setBackground(Theme.roundRect(c, Theme.BG, 0xFF2A2A2E, 12));
    e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    e.setSingleLine(true);
    e.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    return e;
  }
}
