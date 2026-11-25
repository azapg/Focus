package org.nightingalelab.focus.phone;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.animation.LayoutTransition;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.util.List;
import java.util.Locale;

import static org.nightingalelab.focus.phone.FocusEventCodes.*;

final class JudgeSheetDialog {
  private final Activity activity;
  private final PhonePauseJudge judge;
  private final DnsController dns;
  private final Runnable onApplied;
  private final DeviceHub.PairedDevice laptop;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Dialog dialog;
  private final EditText request;
  private final LinearLayout composer;
  private final LinearLayout review;
  private final LinearLayout logos;
  private final TextView durationLabel;
  private final Button edit;
  private final Button scopedOverride;
  private final ScrollView message;
  private final Button ask;
  private final TextView answer;
  private final Button primary;
  private final Button secondary;
  private final EditText afterPlan;
  private PhonePauseJudge.Decision decision;
  private PhonePauseJudge.Budget budget;
  private boolean busy;
  private boolean override;
  private int manualMinutes;
  private String manualCorrelation;
  private String pendingAsk;
  private long unlockAt;
  private Runnable holding;
  private String laptopHoldId;
  private final Runnable tick = this::updateControls;

  static void show(Activity activity, Runnable onApplied) {
    show(activity, null, onApplied);
  }

  static void show(Activity activity, DeviceHub.PairedDevice laptop, Runnable onApplied) {
    new JudgeSheetDialog(activity, laptop, onApplied).show();
  }

  private JudgeSheetDialog(Activity activity, DeviceHub.PairedDevice laptop, Runnable onApplied) {
    this.activity = activity;
    this.laptop = laptop;
    this.onApplied = onApplied;
    dns = DnsController.get(activity);
    judge = new PhonePauseJudge(activity);
    dialog = new Dialog(activity) {
      @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) cancelHold();
      }
    };
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    dialog.setCanceledOnTouchOutside(false);
    dialog.setOnDismissListener(ignored -> {
      cancelHold();
      handler.removeCallbacks(tick);
    });

    LinearLayout sheet = Theme.column(activity);
    sheet.setLayoutTransition(new LayoutTransition());
    sheet.setPadding(dp(18), dp(4), dp(18), dp(12));
    composer = Theme.row(activity);
    composer.setGravity(Gravity.CENTER_VERTICAL);
    styleCapsule(activity, composer);
    sheet.addView(composer, new LinearLayout.LayoutParams(-1, dp(46)));
    request = input("What do you need?", 4097, false);
    request.setSaveEnabled(false);
    request.setBackgroundColor(Color.TRANSPARENT);
    request.setImeOptions(EditorInfo.IME_ACTION_SEND);
    request.setContentDescription("What do you need?");
    composer.addView(request, new LinearLayout.LayoutParams(0, -1, 1));
    ask = button("Ask");
    composer.addView(ask, new LinearLayout.LayoutParams(dp(64), dp(38)));
    request.setOnEditorActionListener((field, action, event) -> {
      if (action != EditorInfo.IME_ACTION_SEND) return false;
      ask();
      return true;
    });

    review = Theme.row(activity);
    review.setGravity(Gravity.CENTER_VERTICAL);
    styleCapsule(activity, review);
    sheet.addView(review, new LinearLayout.LayoutParams(-1, dp(46)));
    logos = Theme.row(activity);
    review.addView(logos);
    durationLabel = Theme.text(activity, "", 14, Theme.FG, true);
    durationLabel.setPadding(dp(6), 0, 0, 0);
    review.addView(durationLabel, new LinearLayout.LayoutParams(0, -2, 1));
    edit = button("Edit");
    edit.setBackgroundColor(Color.TRANSPARENT);
    review.addView(edit, new LinearLayout.LayoutParams(dp(54), dp(38)));
    edit.setOnClickListener(v -> {
      clearAnswer();
      if (request.requestFocus()) {
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.showSoftInput(request, InputMethodManager.SHOW_IMPLICIT);
      }
    });
    scopedOverride = button("⋯");
    scopedOverride.setContentDescription("Full pause override");
    scopedOverride.setBackgroundColor(Color.TRANSPARENT);
    review.addView(scopedOverride, new LinearLayout.LayoutParams(dp(38), dp(38)));
    scopedOverride.setOnClickListener(v -> startOverride());
    primary = button("Start");
    review.addView(primary, new LinearLayout.LayoutParams(-2, dp(38)));

    answer = Theme.text(activity, "", 14, Theme.FG, false);
    answer.setPadding(dp(12), dp(8), dp(12), dp(8));
    answer.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    message = new ScrollView(activity);
    message.setBackground(Theme.roundRect(activity, Theme.SHEET, Theme.LINE, 10));
    message.setVerticalScrollBarEnabled(false);
    message.addView(answer);
    sheet.addView(message, new LinearLayout.LayoutParams(-1, dp(69)));
    afterPlan = input("What will you do after the pause?", 300, false);
    sheet.addView(afterPlan, new LinearLayout.LayoutParams(-1, dp(44)));
    secondary = button("Full pause");
    sheet.addView(secondary, new LinearLayout.LayoutParams(-1, dp(40)));
    sheet.setFocusableInTouchMode(true);
    sheet.requestFocus();

    ask.setOnClickListener(v -> ask());
    primary.setOnClickListener(v -> { if (!needsHold()) openScoped(); });
    primary.setOnTouchListener((v, event) -> {
      if (!needsHold()) return false;
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN: beginHold(); break;
        case MotionEvent.ACTION_MOVE:
          if (event.getX() < 0 || event.getX() > v.getWidth() || event.getY() < 0 || event.getY() > v.getHeight()) cancelHold();
          break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_DOWN:
        case MotionEvent.ACTION_POINTER_UP:
        case MotionEvent.ACTION_CANCEL: cancelHold(); break;
        default: break;
      }
      return true;
    });
    primary.setOnKeyListener((v, key, event) -> {
      if (!needsHold() || (key != KeyEvent.KEYCODE_ENTER && key != KeyEvent.KEYCODE_DPAD_CENTER
          && key != KeyEvent.KEYCODE_SPACE)) return false;
      if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) beginHold();
      if (event.getAction() == KeyEvent.ACTION_UP) cancelHold();
      return true;
    });
    primary.setOnFocusChangeListener((v, focused) -> { if (!focused) cancelHold(); });
    secondary.setOnClickListener(v -> startOverride());
    watch(request, () -> {
      if (manualMinutes > 0) {
        cancelHold();
        updateControls();
      } else {
        clearAnswer();
      }
    });
    watch(afterPlan, this::updateControls);
    dialog.setContentView(sheet);
  }

  private void show() {
    dialog.show();
    if (laptop == null) judge.warmGateway();
    Window window = dialog.getWindow();
    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    WindowManager.LayoutParams layout = window.getAttributes();
    layout.y = dp(12);
    window.setAttributes(layout);
    window.setLayout(-1, -2);
    window.setGravity(Gravity.BOTTOM);
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    updateControls();
  }

  private int dp(int size) { return Theme.dp(activity, size); }

  private Button button(String text) {
    Button button = new Button(activity);
    button.setAllCaps(false);
    button.setTextSize(13);
    button.setTextColor(Theme.FG);
    button.setText(text);
    button.setPadding(dp(8), 0, dp(8), 0);
    button.setBackground(Theme.roundRect(activity, Theme.DARK_ACTIVE, Theme.LINE, 999));
    return button;
  }

  private EditText input(String hint, int limit, boolean longText) {
    EditText field = new EditText(activity);
    field.setHint(hint);
    field.setHintTextColor(Theme.MUTED);
    field.setTextColor(Theme.FG);
    field.setTextSize(14);
    field.setBackground(Theme.roundRect(activity, Theme.SEC, Theme.LINE, 8));
    field.setPadding(dp(12), dp(8), dp(12), dp(8));
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        | (longText ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
    field.setGravity(longText ? Gravity.TOP : Gravity.CENTER_VERTICAL);
    field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(limit)});
    return field;
  }

  private void watch(EditText field, Runnable callback) {
    field.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) { callback.run(); }
      @Override public void afterTextChanged(Editable s) {}
    });
  }

  private void clearAnswer() {
    if (busy) return;
    cancelHold();
    decision = null;
    override = false;
    pendingAsk = null;
    manualMinutes = 0;
    manualCorrelation = null;
    afterPlan.setText("");
    answer.setText("");
    updateControls();
  }

  private String text() { return request.getText().toString().trim(); }

  private void ask() {
    long started = SystemClock.elapsedRealtime();
    if (busy || !PausePolicy.reasonOk(text())) return;
    clearAnswer();
    String correlation = FocusEvents.newId();
    final String reason = text();
    String link = PausePolicy.peekLink(reason);
    if (link == null && reason.codePointCount(0, reason.length()) > 2000) {
      judge.event("ask", "eligibility", GATEWAY_INVALID_REPLY, correlation, "local", started, null);
      showError("Keep your request within 2,000 characters.");
      updateControls();
      return;
    }
    pendingAsk = correlation;
    busy = true;
    InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
    if (keyboard != null) keyboard.hideSoftInputFromWindow(request.getWindowToken(), 0);
    updateControls();
    if (link != null) {
      PeekActivity.fetch(activity, link, reply -> {
        org.json.JSONObject post = reply.optJSONObject("snapshot");
        boolean protectionOff = "protection_off".equals(reply.optString("refusal"));
        judge.event("ask", protectionOff ? "eligibility" : "decision",
            post != null ? OK : protectionOff ? PRIVATE_DNS_INACTIVE : GATEWAY_REFUSED,
            correlation, "local", started, null);
        if (!dialog.isShowing() || !correlation.equals(pendingAsk)) return;
        pendingAsk = null;
        busy = false;
        if (post == null) {
          showError(protectionOff ? PhonePauseJudge.causeMessage(PRIVATE_DNS_INACTIVE) : PeekActivity.refusalMessage(reply));
          updateControls();
          return;
        }
        request.setText("");
        dialog.dismiss();
        PeekActivity.showPost(activity, post);
        onApplied.run();
      });
      return;
    }
    new Thread(() -> {
      try {
        PhonePauseJudge.Decision reply = judge.askUntil(
            reason, null, laptop, correlation, started, started + AskPolicy.PHONE_OVERALL_MS);
        activity.runOnUiThread(() -> {
          if (!dialog.isShowing() || !correlation.equals(pendingAsk)) return;
          pendingAsk = null;
          busy = false;
          decision = reply;
          budget = reply.phoneBudget;
          startWait();
          renderAnswer();
          updateControls();
        });
      } catch (RuntimeException error) {
        judge.event("ask", "decision", GATEWAY_INVALID_REPLY, correlation, "local", started, null);
        activity.runOnUiThread(() -> {
          if (!dialog.isShowing() || !correlation.equals(pendingAsk)) return;
          pendingAsk = null;
          busy = false;
          showError("The pause assistant is unavailable. Try again.");
          updateControls();
        });
      }
    }, "focus-pause-ask").start();
  }

  private void renderAnswer() {
    answer.setTextColor(decision.is("fallback") ? Theme.ATTN : Theme.FG);
    String message = decision.message;
    if (decision.is("peek")) message = "Peek is unavailable in this build.";
    if (decision.is("allow_services")) {
      String cause = decision.scopeEligibility;
      message = OK.equals(cause) ? "" : PhonePauseJudge.causeMessage(cause);
    }
    if (decision.is("full_pause")) message = "Full pause · " + minutes(budget) + " min";
    if (decision.is("counter_offer")) message = decision.alternative;
    answer.setText(message);
  }

  static String serviceName(String service) {
    switch (service) {
      case "discord": return "Discord";
      case "youtube": return "YouTube";
      case "whatsapp_web": return "WhatsApp Web";
      case "github": return "GitHub";
      case "x": return "X";
      default: return service.substring(0, 1).toUpperCase(Locale.ROOT) + service.substring(1).replace('_', ' ');
    }
  }

  static void styleCapsule(Activity activity, LinearLayout row) {
    row.setBackground(Theme.obsidianGlass(activity, 999));
    row.setPadding(Theme.dp(activity, 10), 0, Theme.dp(activity, 4), 0);
  }

  static void setServiceLogos(Activity activity, LinearLayout row, List<String> services) {
    row.removeAllViews();
    for (String service : services) {
      if (service.isEmpty()) continue;
      String name = serviceName(service);
      int res = activity.getResources().getIdentifier("judge_" + service, "drawable", activity.getPackageName());
      View tile;
      if (res != 0) {
        ImageView icon = new ImageView(activity);
        icon.setImageResource(res);
        icon.setPadding(Theme.dp(activity, 4), Theme.dp(activity, 4), Theme.dp(activity, 4), Theme.dp(activity, 4));
        tile = icon;
      } else {
        TextView icon = Theme.text(activity, name.substring(0, 1), 12, Theme.FG, true);
        icon.setGravity(Gravity.CENTER);
        tile = icon;
      }
      tile.setContentDescription(name);
      tile.setTooltipText(name);
      tile.setBackground(Theme.roundRect(activity, Theme.SEC, Theme.LINE, 6));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Theme.dp(activity, 24), Theme.dp(activity, 24));
      lp.rightMargin = Theme.dp(activity, 3);
      row.addView(tile, lp);
    }
  }

  private boolean needsHold() {
    return manualMinutes > 0 || decision != null
        && (override || decision.is("full_pause"));
  }

  private long requiredWait(PhonePauseJudge.Budget current) {
    if (laptop != null) return PhonePauseJudge.waitForLaptop(decision);
    int wait = PausePolicy.waitSecondsFor(current.config, current.next);
    // Manual access keeps the existing offline fallback's minimum friction.
    return 1000L * (manualMinutes > 0 ? Math.max(wait, Math.max(5, current.config.waitSeconds)) : wait);
  }

  private long holdMs(PhonePauseJudge.Budget current) {
    if (laptop != null) return PhonePauseJudge.holdForLaptop(decision);
    return manualMinutes > 0 ? 3000L : PausePolicy.holdDurationMs(current.config, current.next);
  }

  private boolean needsPlan(PhonePauseJudge.Budget current) {
    if (laptop != null) return PhonePauseJudge.planForLaptop(decision);
    return manualMinutes > 0 || PausePolicy.requiresAfterPlan(current.config, current.next);
  }

  private int minutes(PhonePauseJudge.Budget current) {
    if (manualMinutes > 0) return manualMinutes;
    if (decision.is("fallback")) return 1;
    if (laptop != null) return override ? PhonePauseJudge.manualForLaptop(decision) : decision.minutes;
    return Math.min(decision.minutes, current.cap());
  }

  private void startOverride() {
    if (laptop != null) {
      if (decision == null || decision.remoteLimits == null) return;
      override = true;
      startWait();
      updateControls();
      return;
    }
    View anchor = secondary.getVisibility() == View.VISIBLE ? secondary : scopedOverride;
    PhonePauseJudge.Budget current = judge.budget();
    PopupMenu menu = new PopupMenu(activity, anchor);
    for (PausePolicy.Choice choice : current.manualChoices()) {
      menu.getMenu().add(0, choice.minutes, choice.minutes, choice.minutes + " min").setEnabled(choice.allowed);
    }
    menu.setOnMenuItemClickListener(item -> {
      pendingAsk = null;
      busy = false;
      decision = null;
      manualMinutes = item.getItemId();
      manualCorrelation = FocusEvents.newId();
      budget = current;
      answer.setText("");
      afterPlan.setText("");
      startWait();
      updateControls();
      return true;
    });
    menu.show();
  }

  private void startWait() {
    unlockAt = needsHold() ? SystemClock.elapsedRealtime() + requiredWait(budget) : 0;
  }

  private void updateControls() {
    handler.removeCallbacks(tick);
    if (!dialog.isShowing()) return;
    boolean hold = needsHold();
    boolean manual = manualMinutes > 0;
    boolean scoped = decision != null && decision.is("allow_services") && !override
        && OK.equals(decision.scopeEligibility);
    boolean plan = hold && needsPlan(budget);
    long seconds = Math.max(0, (unlockAt - SystemClock.elapsedRealtime() + 999) / 1000);
    boolean editable = !busy && holding == null;
    boolean retry = decision != null && decision.is("fallback") && !override;
    composer.setVisibility((decision == null || retry) ? View.VISIBLE : View.GONE);
    review.setVisibility((decision != null && !retry) || manual ? View.VISIBLE : View.GONE);
    message.setVisibility(answer.length() == 0 ? View.GONE : View.VISIBLE);
    ask.setText(busy ? "…" : "Ask");
    ask.setVisibility(manual ? View.GONE : View.VISIBLE);
    ask.setEnabled(editable && PausePolicy.reasonOk(text()));
    edit.setText(manual ? "Ask" : "Edit");
    edit.setEnabled(editable);
    scopedOverride.setVisibility(scoped && laptop != null ? View.VISIBLE : View.GONE);
    scopedOverride.setEnabled(editable);
    durationLabel.setText(scoped ? decision.minutes + " min" : hold ? "" : "Ask");
    if (scoped && logos.getChildCount() == 0) setServiceLogos(activity, logos, decision.services);
    if (!scoped && logos.getChildCount() > 0) logos.removeAllViews();
    request.setEnabled(editable);
    afterPlan.setVisibility(plan ? View.VISIBLE : View.GONE);
    afterPlan.setEnabled(editable);
    primary.setVisibility(hold || scoped ? View.VISIBLE : View.GONE);
    primary.setEnabled(!busy && (!hold || seconds == 0 && PausePolicy.reasonOk(text())
        && (!manual || budget.allowsManual(manualMinutes))
        && (!plan || PausePolicy.reasonOk(afterPlan.getText().toString()))));
    secondary.setVisibility(laptop == null
        || decision != null && decision.remoteLimits != null && !hold && !scoped ? View.VISIBLE : View.GONE);
    secondary.setEnabled(holding == null && (!busy || pendingAsk != null && laptop == null));
    secondary.setText(manual ? "Full pause · " + manualMinutes + " min" : "Full pause");
    if (hold) {
      primary.setText(holding != null ? "Keep holding…" : seconds > 0 ? "Wait " + seconds + "s"
          : "Hold " + (holdMs(budget) / 1000) + "s for " + minutes(budget) + " min");
      if (seconds > 0) handler.postDelayed(tick, 250);
    } else if (scoped) {
      primary.setText(busy ? "Opening…" : "Start");
    }
  }

  private void beginHold() {
    if (holding != null || !primary.isEnabled() || !needsHold()) return;
    if (laptop != null) {
      beginLaptopHold();
      return;
    }
    final long duration = holdMs(budget);
    final boolean manual = manualMinutes > 0;
    final String correlation = manual ? manualCorrelation : decision.correlationId;
    final String route = manual ? "local" : decision.route;
    final long started = SystemClock.elapsedRealtime();
    holding = () -> {
      holding = null;
      if (!dialog.isShowing() || !dialog.getWindow().getDecorView().hasWindowFocus()) return;
      String cause = OK;
      try {
        PhonePauseJudge.Budget current = judge.budget();
        if (!manual && decision.expired()) {
          cause = DECISION_EXPIRED;
          showError(PhonePauseJudge.causeMessage(cause));
          return;
        }
        if (dns.deadline() > 0) {
          cause = DNS_PAUSED;
          showError(PhonePauseJudge.causeMessage(cause));
          return;
        }
        if (manual && !current.allowsManual(manualMinutes)) {
          cause = CAP_EXCEEDED;
          budget = current;
          showError("Choose a shorter pause. Your allowance changed.");
          return;
        }
        if (requiredWait(current) > requiredWait(budget) || holdMs(current) > duration
            || needsPlan(current) && !needsPlan(budget) || minutes(current) < minutes(budget)) {
          cause = CAP_EXCEEDED;
          budget = current;
          startWait();
          if (!manual) renderAnswer();
          return;
        }
        String plan = afterPlan.getText().toString().trim();
        if (needsPlan(current) && !PausePolicy.reasonOk(plan)) return;
        dns.pause(minutes(budget) * 60_000L, text(), plan, null, correlation, route);
        applied();
      } catch (RuntimeException error) {
        cause = DNS_APPLY_FAILED;
        showError(error.getMessage());
      } finally {
        judge.recordFullPause(cause, correlation, route, started, manual);
        updateControls();
      }
    };
    handler.postDelayed(holding, duration);
    updateControls();
  }

  private void beginLaptopHold() {
    PhonePauseJudge.Decision selected = decision;
    boolean overridden = override && !selected.is("fallback");
    Runnable pending = () -> {};
    holding = pending;
    updateControls();
    new Thread(() -> {
      try {
        org.json.JSONObject token = judge.beginLaptopHold(selected, overridden);
        String id = token.getString("hold_id");
        long duration = token.getLong("hold_ms");
        activity.runOnUiThread(() -> {
          if (holding != pending || !dialog.isShowing()) {
            cancelLaptopHold(selected, id);
            return;
          }
          laptopHoldId = id;
          holding = () -> finishLaptopHold(selected, id, overridden);
          handler.postDelayed(holding, duration);
          updateControls();
        });
      } catch (Exception error) {
        activity.runOnUiThread(() -> {
          if (holding != pending || !dialog.isShowing()) return;
          cancelHold();
          showError(PhonePauseJudge.causeMessage(PhonePauseJudge.laptopCause(error)));
        });
      }
    }, "focus-laptop-hold").start();
  }

  private void finishLaptopHold(PhonePauseJudge.Decision selected, String id, boolean overridden) {
    if (!dialog.isShowing() || !dialog.getWindow().getDecorView().hasWindowFocus()) {
      cancelHold();
      return;
    }
    holding = null;
    laptopHoldId = null;
    int duration = minutes(budget);
    String plan = afterPlan.getText().toString().trim();
    runApply(() -> judge.applyLaptopHold(selected, id, overridden, duration, plan));
  }

  private void cancelHold() {
    if (holding == null) return;
    handler.removeCallbacks(holding);
    holding = null;
    if (laptopHoldId != null) {
      cancelLaptopHold(decision, laptopHoldId);
      laptopHoldId = null;
    }
    updateControls();
  }

  private void cancelLaptopHold(PhonePauseJudge.Decision selected, String id) {
    new Thread(() -> judge.cancelLaptopHold(selected, id), "focus-cancel-laptop-hold").start();
  }

  private void openScoped() {
    if (busy || decision == null) return;
    PhonePauseJudge.Decision accepted = decision;
    runApply(() -> judge.open(accepted, laptop != null));
  }

  private interface ApplyAction {
    void run() throws Exception;
  }

  private void runApply(ApplyAction action) {
    busy = true;
    updateControls();
    new Thread(() -> {
      String error = null;
      try {
        action.run();
      } catch (Exception failure) {
        error = failure.getMessage() == null ? "Could not apply this request. Ask again." : failure.getMessage();
      }
      String message = error;
      activity.runOnUiThread(() -> {
        if (!dialog.isShowing()) return;
        busy = false;
        if (message == null) applied();
        else showError(message);
        updateControls();
      });
    }, "focus-open-services").start();
  }

  private void applied() {
    dialog.dismiss();
    onApplied.run();
  }

  private void showError(String message) {
    answer.setTextColor(Theme.ATTN);
    answer.setText(message == null ? "Could not pause phone DNS" : message);
  }
}
