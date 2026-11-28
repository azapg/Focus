package org.nightingalelab.focus.phone;

import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.*;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.List;
import java.util.Locale;

import static org.nightingalelab.focus.phone.FocusEventCodes.*;

final class JudgeSheetDialog {
  private final Activity activity;
  private final PhonePauseJudge judge;
  private final DnsController dns;
  private final Runnable onApplied;
  private final Runnable onDismiss;
  private final DeviceHub.PairedDevice laptop;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Dialog dialog;

  // Asking capsule (keyboard open)
  private final LinearLayout composer;
  private final EditText request;
  private final Button ask;

  // Asked / Checking capsule
  private final FrameLayout checkingHost;
  private final TextView checkingPrompt;
  private final TextView checkingLabel;
  private final TextView cancelAsk;
  private final ProgressSweepView progressSweep;

  // Unified response card (Offer, Full pause offer, Counter-offer, Rejection, Offline)
  private final LinearLayout card;
  private final LinearLayout cardHeader;
  private final LinearLayout singleIconHost;
  private final TextView cardTitle;
  private final Button headerAction;
  private final Button startButton;
  private final ChipFlowLayout chipsRow;
  private final TextView cardBody;
  private final EditText afterPlan;
  private final Button primary;
  private final LinearLayout footerActions;
  private final Button dismissButton;
  private final Button editRequestButton;
  private final Button retryButton;

  private final HoldPillDrawable holdBackground;
  private PhonePauseJudge.Decision decision;
  private PhonePauseJudge.Budget budget;
  private String transientError;
  private boolean busy;
  private String pendingAsk;
  private long unlockAt;
  private Runnable holding;
  private String laptopHoldId;
  private final Runnable tick = this::updateControls;

  static void show(Activity activity, Runnable onApplied) {
    show(activity, null, onApplied, null);
  }

  static void show(Activity activity, Runnable onApplied, Runnable onDismiss) {
    show(activity, null, onApplied, onDismiss);
  }

  static void show(Activity activity, DeviceHub.PairedDevice laptop, Runnable onApplied) {
    show(activity, laptop, onApplied, null);
  }

  static void show(Activity activity, DeviceHub.PairedDevice laptop, Runnable onApplied, Runnable onDismiss) {
    new JudgeSheetDialog(activity, laptop, onApplied, onDismiss).show();
  }

  private JudgeSheetDialog(Activity activity, DeviceHub.PairedDevice laptop, Runnable onApplied) {
    this(activity, laptop, onApplied, null);
  }

  private JudgeSheetDialog(Activity activity, DeviceHub.PairedDevice laptop, Runnable onApplied, Runnable onDismiss) {
    this.activity = activity;
    this.laptop = laptop;
    this.onApplied = onApplied;
    this.onDismiss = onDismiss;
    dns = DnsController.get(activity);
    judge = new PhonePauseJudge(activity);
    holdBackground = new HoldPillDrawable(activity);

    dialog = new Dialog(activity) {
      @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) cancelHold();
      }
    };
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    dialog.setCanceledOnTouchOutside(true);

    LinearLayout sheet = Theme.column(activity);
    LayoutTransition transition = new LayoutTransition();
    transition.setDuration(150);
    sheet.setLayoutTransition(transition);
    sheet.setPadding(dp(12), dp(4), dp(12), dp(12));

    // --- 02 · Asking capsule ---
    composer = Theme.row(activity);
    composer.setGravity(Gravity.CENTER_VERTICAL);
    styleCapsule(activity, composer);
    composer.setMinimumHeight(dp(48));
    composer.setPadding(dp(14), dp(4), dp(5), dp(4));
    composer.setElevation(dp(12));

    request = input("What do you need?", 4097);
    request.setSaveEnabled(false);
    request.setBackgroundColor(Color.TRANSPARENT);
    request.setPadding(0, dp(8), dp(8), dp(8));
    request.setSingleLine(true);
    request.setImeOptions(EditorInfo.IME_ACTION_SEND);
    request.setContentDescription("What do you need?");
    composer.addView(request, new LinearLayout.LayoutParams(0, -2, 1));

    ask = primaryPillButton("Ask");
    composer.addView(ask, new LinearLayout.LayoutParams(-2, dp(38)));
    sheet.addView(composer, new LinearLayout.LayoutParams(-1, -2));

    // --- 03 · Asked / Checking capsule ---
    checkingHost = new FrameLayout(activity);
    checkingHost.setBackground(Theme.askSurface(activity, Theme.ASK_BORDER, 999));
    checkingHost.setElevation(dp(12));
    checkingHost.setClipToOutline(true);

    LinearLayout checkingRow = Theme.row(activity);
    checkingRow.setGravity(Gravity.CENTER_VERTICAL);
    checkingRow.setPadding(dp(14), dp(5), dp(8), dp(5));

    checkingPrompt = Theme.text(activity, "", 13.5f, Theme.ASK_MUTED_TEXT, false);
    checkingPrompt.setSingleLine(true);
    checkingPrompt.setEllipsize(TextUtils.TruncateAt.END);
    checkingRow.addView(checkingPrompt, new LinearLayout.LayoutParams(0, -2, 1));

    checkingLabel = Theme.text(activity, "Checking\u2026", 12f, 0xFFB4B4BC, true);
    checkingLabel.setPadding(dp(8), 0, dp(8), 0);
    checkingRow.addView(checkingLabel);

    cancelAsk = Theme.text(activity, "\u00d7", 15f, Theme.MUTED, true);
    cancelAsk.setGravity(Gravity.CENTER);
    cancelAsk.setContentDescription("Cancel request");
    cancelAsk.setBackground(Theme.roundRect(activity, 0xFF1C1C21, 0, 999));
    cancelAsk.setOnClickListener(v -> cancelPendingAsk());
    checkingRow.addView(cancelAsk, new LinearLayout.LayoutParams(dp(30), dp(30)));

    checkingHost.addView(checkingRow, new FrameLayout.LayoutParams(-1, dp(48)));
    progressSweep = new ProgressSweepView(activity);
    FrameLayout.LayoutParams sweepLp = new FrameLayout.LayoutParams(-1, dp(2), Gravity.BOTTOM);
    sweepLp.setMargins(dp(18), 0, dp(18), 0);
    checkingHost.addView(progressSweep, sweepLp);
    checkingHost.setVisibility(View.GONE);
    sheet.addView(checkingHost, new LinearLayout.LayoutParams(-1, dp(48)));

    // --- 04/05/06 · Unified response card ---
    card = Theme.column(activity);
    card.setBackground(Theme.askSurface(activity, Theme.ASK_BORDER, 20));
    card.setPadding(dp(14), dp(13), dp(14), dp(13));
    card.setElevation(dp(14));

    cardHeader = Theme.row(activity);
    cardHeader.setGravity(Gravity.CENTER_VERTICAL);

    singleIconHost = Theme.row(activity);
    cardHeader.addView(singleIconHost);

    cardTitle = Theme.text(activity, "", 14f, Theme.FG, true);
    cardTitle.setSingleLine(true);
    cardTitle.setEllipsize(TextUtils.TruncateAt.END);
    cardHeader.addView(cardTitle, new LinearLayout.LayoutParams(0, -2, 1));

    headerAction = ghostButton("Edit");
    cardHeader.addView(headerAction, new LinearLayout.LayoutParams(-2, dp(34)));

    startButton = primaryPillButton("Start");
    LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(-2, dp(36));
    startLp.leftMargin = dp(4);
    cardHeader.addView(startButton, startLp);

    card.addView(cardHeader, new LinearLayout.LayoutParams(-1, -2));

    chipsRow = new ChipFlowLayout(activity, dp(6), dp(6));
    LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(-1, -2);
    chipsLp.topMargin = dp(9);
    card.addView(chipsRow, chipsLp);

    cardBody = Theme.text(activity, "", 12.5f, Theme.SUB, false);
    cardBody.setLineSpacing(dp(2), 1f);
    cardBody.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, -2);
    bodyLp.topMargin = dp(8);
    card.addView(cardBody, bodyLp);

    afterPlan = input("What will you do after the pause?", 300);
    afterPlan.setBackground(Theme.roundRect(activity, 0xFF0D0D10, Theme.ASK_BORDER, 10));
    afterPlan.setSingleLine(true);
    LinearLayout.LayoutParams planLp = new LinearLayout.LayoutParams(-1, dp(42));
    planLp.topMargin = dp(10);
    card.addView(afterPlan, planLp);

    primary = new Button(activity);
    primary.setAllCaps(false);
    primary.setTextSize(13);
    primary.setTextColor(Theme.ASK_HOLD_TEXT);
    primary.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    primary.setBackground(holdBackground);
    primary.setPadding(dp(14), 0, dp(14), 0);
    primary.setStateListAnimator(null);
    LinearLayout.LayoutParams holdLp = new LinearLayout.LayoutParams(-1, dp(40));
    holdLp.topMargin = dp(10);
    card.addView(primary, holdLp);

    footerActions = Theme.row(activity);
    footerActions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(-1, -2);
    footerLp.topMargin = dp(10);

    dismissButton = ghostButton("Dismiss");
    footerActions.addView(dismissButton, new LinearLayout.LayoutParams(-2, dp(34)));

    editRequestButton = secondaryPillButton("Edit request");
    LinearLayout.LayoutParams editReqLp = new LinearLayout.LayoutParams(-2, dp(36));
    editReqLp.leftMargin = dp(6);
    footerActions.addView(editRequestButton, editReqLp);

    retryButton = primaryPillButton("Try again");
    LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(-2, dp(36));
    retryLp.leftMargin = dp(6);
    footerActions.addView(retryButton, retryLp);

    card.addView(footerActions, footerLp);
    card.setVisibility(View.GONE);
    sheet.addView(card, new LinearLayout.LayoutParams(-1, -2));

    // Wire events
    request.setOnEditorActionListener((field, action, event) -> {
      if (action != EditorInfo.IME_ACTION_SEND && action != EditorInfo.IME_ACTION_DONE) return false;
      ask();
      return true;
    });
    ask.setOnClickListener(v -> ask());
    startButton.setOnClickListener(v -> openScoped());
    dismissButton.setOnClickListener(v -> dialog.dismiss());
    editRequestButton.setOnClickListener(v -> editRequest());
    retryButton.setOnClickListener(v -> {
      if (isRefused()) {
        dialog.dismiss();
        StoreSheet.show(activity, onApplied, () -> {});
      } else {
        ask();
      }
    });
    headerAction.setOnClickListener(v -> {
      if (isOfflineHold()) ask();
      else editRequest();
    });

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

    watch(request, () -> {
      if (decision != null || transientError != null) clearAnswer();
      else updateControls();
    });
    watch(afterPlan, this::updateControls);

    dialog.setOnDismissListener(ignored -> {
      pendingAsk = null;
      busy = false;
      cancelHold();
      progressSweep.stop();
      handler.removeCallbacks(tick);
      if (this.onDismiss != null) this.onDismiss.run();
    });
    dialog.setContentView(sheet);
  }

  private void show() {
    dialog.show();
    if (laptop == null) judge.warmGateway();
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
          | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
    updateControls();
    focusComposerAndShowKeyboard();
  }

  private void focusComposerAndShowKeyboard() {
    if (request.requestFocus()) {
      request.setSelection(request.getText().length());
      request.post(() -> {
        if (!dialog.isShowing() || composer.getVisibility() != View.VISIBLE) return;
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.showSoftInput(request, InputMethodManager.SHOW_IMPLICIT);
      });
    }
  }

  private void hideKeyboard() {
    InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
    if (keyboard != null && request.getWindowToken() != null) {
      keyboard.hideSoftInputFromWindow(request.getWindowToken(), 0);
    }
  }

  private void editRequest() {
    clearAnswer();
    focusComposerAndShowKeyboard();
  }

  private void cancelPendingAsk() {
    if (!busy) return;
    pendingAsk = null;
    busy = false;
    updateControls();
    focusComposerAndShowKeyboard();
  }

  private int dp(int size) { return Theme.dp(activity, size); }

  private Button primaryPillButton(String text) {
    Button button = new Button(activity);
    button.setAllCaps(false);
    button.setTextSize(13);
    button.setTextColor(Theme.FG);
    button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    button.setText(text);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(dp(15), 0, dp(15), 0);
    button.setBackground(Theme.roundRect(activity, Theme.ASK_PRIMARY_FILL, Theme.ASK_PRIMARY_BORDER, 999));
    button.setStateListAnimator(null);
    return button;
  }

  private Button secondaryPillButton(String text) {
    Button button = new Button(activity);
    button.setAllCaps(false);
    button.setTextSize(12.5f);
    button.setTextColor(0xFFE4E4E7);
    button.setText(text);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(dp(14), 0, dp(14), 0);
    button.setBackground(Theme.roundRect(activity, Theme.ASK_SECONDARY_FILL, Theme.ASK_SECONDARY_BORDER, 999));
    button.setStateListAnimator(null);
    return button;
  }

  private Button ghostButton(String text) {
    Button button = new Button(activity);
    button.setAllCaps(false);
    button.setTextSize(12.5f);
    button.setTextColor(Theme.SUB);
    button.setText(text);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(dp(10), 0, dp(10), 0);
    button.setBackgroundColor(Color.TRANSPARENT);
    button.setStateListAnimator(null);
    return button;
  }

  private EditText input(String hint, int limit) {
    EditText field = new EditText(activity);
    field.setHint(hint);
    field.setHintTextColor(Theme.MUTED);
    field.setTextColor(Theme.FG);
    field.setTextSize(14);
    field.setPadding(dp(12), dp(8), dp(12), dp(8));
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    field.setGravity(Gravity.CENTER_VERTICAL);
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
    transientError = null;
    pendingAsk = null;
    afterPlan.setText("");
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
    checkingPrompt.setText(reason);
    hideKeyboard();
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
        PhonePauseJudge.Budget resolvedBudget = reply.phoneBudget != null
            ? reply.phoneBudget : (laptop == null ? judge.budget() : null);
        activity.runOnUiThread(() -> {
          if (!dialog.isShowing() || !correlation.equals(pendingAsk)) return;
          pendingAsk = null;
          busy = false;
          decision = reply;
          budget = resolvedBudget;
          transientError = null;
          startWait();
          updateControls();
        });
      } catch (RuntimeException error) {
        judge.event("ask", "decision", GATEWAY_INVALID_REPLY, correlation, "local", started, null);
        PhonePauseJudge.Budget fallbackBudget = laptop == null ? judge.budget() : null;
        activity.runOnUiThread(() -> {
          if (!dialog.isShowing() || !correlation.equals(pendingAsk)) return;
          pendingAsk = null;
          busy = false;
          PhonePauseJudge.Decision offline = new PhonePauseJudge.Decision(
              FocusEvents.newId(), "fallback", java.util.Collections.emptyList(), 0,
              "The pause assistant is unavailable. Try again.", "", "", null);
          offline.correlationId = correlation;
          offline.route = "local";
          offline.cause = GATEWAY_INVALID_REPLY;
          decision = offline;
          budget = fallbackBudget;
          startWait();
          updateControls();
        });
      }
    }, "focus-pause-ask").start();
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
    row.setBackground(Theme.askSurface(activity, Theme.ASK_BORDER, 999));
    row.setPadding(Theme.dp(activity, 10), 0, Theme.dp(activity, 5), 0);
  }

  static void setServiceLogos(Activity activity, LinearLayout row, List<String> services) {
    row.removeAllViews();
    boolean stacked = services.size() > 1;
    int tileSize = Theme.dp(activity, 24);
    for (int i = 0; i < services.size(); i++) {
      String service = services.get(i);
      if (service.isEmpty()) continue;
      View tile = makeServiceTile(activity, service, stacked);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileSize, tileSize);
      if (stacked && i > 0) lp.leftMargin = -Theme.dp(activity, 6);
      else if (!stacked) lp.rightMargin = Theme.dp(activity, 6);
      row.addView(tile, lp);
    }
  }

  private static View makeServiceTile(Activity activity, String service, boolean circular) {
    String name = serviceName(service);
    int res = activity.getResources().getIdentifier("judge_" + service, "drawable", activity.getPackageName());
    View tile;
    if (res != 0) {
      ImageView icon = new ImageView(activity);
      icon.setImageResource(res);
      int pad = Theme.dp(activity, 5);
      icon.setPadding(pad, pad, pad, pad);
      icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
      tile = icon;
    } else {
      TextView icon = Theme.text(activity, name.substring(0, 1), 11.5f, Theme.FG, true);
      icon.setGravity(Gravity.CENTER);
      tile = icon;
    }
    tile.setContentDescription(name);
    tile.setTooltipText(name);
    if (circular) {
      tile.setBackground(Theme.roundRect(activity, 0xFF222229, Theme.ASK_SURFACE, 999));
    } else {
      tile.setBackground(Theme.roundRect(activity, 0xFF1C1C22, Theme.ASK_BORDER, 7));
    }
    return tile;
  }

  private View makeServiceChip(String service) {
    String name = serviceName(service);
    LinearLayout chip = Theme.row(activity);
    chip.setGravity(Gravity.CENTER_VERTICAL);
    chip.setBackground(Theme.roundRect(activity, Theme.ASK_CHIP_FILL, Theme.ASK_CHIP_BORDER, 8));
    chip.setPadding(dp(7), dp(4), dp(10), dp(4));
    int res = activity.getResources().getIdentifier("judge_" + service, "drawable", activity.getPackageName());
    if (res != 0) {
      ImageView icon = new ImageView(activity);
      icon.setImageResource(res);
      icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
      LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(13), dp(13));
      iconLp.rightMargin = dp(5);
      chip.addView(icon, iconLp);
    }
    chip.addView(Theme.text(activity, name, 12f, 0xFFE4E4E7, true));
    chip.setContentDescription(name);
    return chip;
  }

  private boolean isScopedOffer() {
    return decision != null && (decision.is("allow_services") || decision.is("allow_domains"))
        && OK.equals(decision.scopeEligibility);
  }

  private boolean isFullPauseOffer() {
    return decision != null && decision.is("full_pause");
  }

  private boolean isRefused() {
    return decision != null && (decision.is("deny")
        || (decision.is("fallback") && GATEWAY_REFUSED.equals(decision.cause)));
  }

  private boolean isOfflineHold() {
    if (decision == null || !decision.is("fallback") || GATEWAY_REFUSED.equals(decision.cause)) return false;
    if (laptop != null) return decision.remoteLimits != null;
    return dns.canPause() && budget != null && budget.allowsManual(1);
  }

  private boolean needsHold() {
    return isFullPauseOffer() || isOfflineHold();
  }

  private long requiredWait(PhonePauseJudge.Budget current) {
    if (laptop != null) return PhonePauseJudge.waitForLaptop(decision);
    if (current == null) return 0;
    return 1000L * PausePolicy.waitSecondsFor(current.config, current.next);
  }

  private long holdMs(PhonePauseJudge.Budget current) {
    if (laptop != null) return PhonePauseJudge.holdForLaptop(decision);
    if (isOfflineHold()) return 3000L;
    if (current == null) return 2000L;
    return PausePolicy.holdDurationMs(current.config, current.next);
  }

  private boolean needsPlan(PhonePauseJudge.Budget current) {
    if (laptop != null) return PhonePauseJudge.planForLaptop(decision);
    if (current == null) return false;
    return PausePolicy.requiresAfterPlan(current.config, current.next);
  }

  private int minutes(PhonePauseJudge.Budget current) {
    if (isOfflineHold()) return 1;
    if (laptop != null) return decision.minutes;
    if (current == null) return Math.max(1, decision.minutes);
    return Math.min(decision.minutes, current.cap());
  }

  private void startWait() {
    unlockAt = needsHold() ? SystemClock.elapsedRealtime() + requiredWait(budget) : 0;
  }

  private void updateControls() {
    handler.removeCallbacks(tick);
    if (!dialog.isShowing()) return;

    boolean showingAsking = decision == null && transientError == null && !busy;
    boolean showingChecking = decision == null && transientError == null && busy;
    boolean showingCard = decision != null || transientError != null;

    composer.setVisibility(showingAsking ? View.VISIBLE : View.GONE);
    checkingHost.setVisibility(showingChecking ? View.VISIBLE : View.GONE);
    card.setVisibility(showingCard ? View.VISIBLE : View.GONE);

    if (showingChecking) progressSweep.start();
    else progressSweep.stop();

    boolean reasonValid = PausePolicy.reasonOk(text());
    ask.setEnabled(!busy && reasonValid);
    ask.setAlpha(ask.isEnabled() ? 1f : 0.42f);
    request.setEnabled(!busy);

    if (!showingCard) return;

    boolean editable = !busy && holding == null;
    boolean scoped = isScopedOffer();
    boolean fullOffer = isFullPauseOffer();
    boolean offlineHold = isOfflineHold();
    boolean hold = needsHold();
    boolean counterOffer = decision != null && decision.is("counter_offer");
    boolean refused = isRefused();
    boolean offlineNoHold = decision != null && decision.is("fallback") && !offlineHold && !refused;
    boolean errorOnly = decision == null && transientError != null;
    boolean plan = hold && needsPlan(budget);
    long seconds = Math.max(0, (unlockAt - SystemClock.elapsedRealtime() + 999) / 1000);

    // Card border tone: monochrome for scoped offer, red for denial, orange for warning/nudge/offline
    int borderColor = scoped ? Theme.ASK_BORDER : refused ? Theme.ASK_BORDER_BAD : Theme.ASK_BORDER_WARN;
    card.setBackground(Theme.askSurface(activity, borderColor, 20));

    // Reset dynamic slots
    singleIconHost.removeAllViews();
    singleIconHost.setVisibility(View.GONE);
    chipsRow.removeAllViews();
    chipsRow.setVisibility(View.GONE);

    if (scoped) {
      List<String> items = decision.is("allow_domains") ? decision.domains : decision.services;
      int count = items.size();
      cardTitle.setTextColor(Theme.FG);
      if (decision.is("allow_domains")) {
        String label = count == 1 ? items.get(0) : count + " sites";
        cardTitle.setText(label + " \u00b7 " + decision.minutes + " min");
        String body = nonEmpty(decision.message)
            ? decision.message
            : "Only " + label + " opens; DNS protection stays on.";
        cardBody.setText(body);
      } else if (count == 1) {
        String only = items.get(0);
        singleIconHost.setVisibility(View.VISIBLE);
        setServiceLogos(activity, singleIconHost, items);
        cardTitle.setText(serviceName(only) + " \u00b7 " + decision.minutes + " min");
        String body = nonEmpty(decision.message)
            ? decision.message
            : "Only " + serviceName(only) + " opens; DNS protection stays on for everything else.";
        cardBody.setText(body);
      } else {
        cardTitle.setText(count + " apps \u00b7 " + decision.minutes + " min");
        chipsRow.setVisibility(View.VISIBLE);
        for (String svc : items) {
          if (!svc.isEmpty()) chipsRow.addView(makeServiceChip(svc));
        }
        String body = nonEmpty(decision.message)
            ? decision.message
            : "DNS protection stays on for everything else.";
        cardBody.setText(body);
      }
      cardBody.setVisibility(View.VISIBLE);
      headerAction.setText("Edit");
      headerAction.setVisibility(View.VISIBLE);
      headerAction.setEnabled(editable);
      startButton.setText(busy ? "Opening\u2026" : "Start");
      startButton.setVisibility(View.VISIBLE);
      startButton.setEnabled(!busy);
      startButton.setAlpha(startButton.isEnabled() ? 1f : 0.5f);
    } else if (fullOffer) {
      cardTitle.setTextColor(Theme.WARN);
      cardTitle.setText("Full pause \u00b7 " + minutes(budget) + " min");
      String body = nonEmpty(transientError) ? transientError
          : nonEmpty(decision.message) ? decision.message
          : "Counts against your daily DNS-off limit.";
      cardBody.setText(body);
      cardBody.setVisibility(View.VISIBLE);
      headerAction.setText("Edit");
      headerAction.setVisibility(View.VISIBLE);
      headerAction.setEnabled(editable);
      startButton.setVisibility(View.GONE);
    } else if (offlineHold) {
      cardTitle.setTextColor(Theme.WARN);
      cardTitle.setText("Assistant unreachable");
      String body = nonEmpty(transientError) ? transientError
          : "Offline fallback only. Counts against your daily DNS-off limit.";
      cardBody.setText(body);
      cardBody.setVisibility(View.VISIBLE);
      headerAction.setText("Retry");
      headerAction.setVisibility(View.VISIBLE);
      headerAction.setEnabled(editable);
      startButton.setVisibility(View.GONE);
    } else if (counterOffer) {
      cardTitle.setTextColor(Theme.WARN);
      cardTitle.setText("Try this first");
      cardBody.setText(nonEmpty(decision.alternative) ? decision.alternative : decision.message);
      cardBody.setVisibility(View.VISIBLE);
      headerAction.setVisibility(View.GONE);
      startButton.setVisibility(View.GONE);
    } else if (refused) {
      cardTitle.setTextColor(Theme.BAD_TEXT);
      cardTitle.setText("Not approved");
      cardBody.setText(nonEmpty(decision.message) ? decision.message : "No task needs an exception right now.");
      cardBody.setVisibility(View.VISIBLE);
      headerAction.setVisibility(View.GONE);
      startButton.setVisibility(View.GONE);
    } else {
      // Offline without hold, ineligible scoped decision, peek unavailable, or transient error
      cardTitle.setTextColor(Theme.WARN);
      cardTitle.setText(offlineNoHold ? "Assistant unreachable" : "Couldn\u2019t continue");
      String msg = transientError;
      if (!nonEmpty(msg) && decision != null) {
        if (decision.is("peek")) msg = "Peek is unavailable in this build.";
        else if (decision.is("allow_services") || decision.is("allow_domains")) msg = PhonePauseJudge.causeMessage(decision.scopeEligibility);
        else msg = decision.message;
      }
      cardBody.setText(nonEmpty(msg) ? msg : "Try again.");
      cardBody.setVisibility(View.VISIBLE);
      headerAction.setVisibility(View.GONE);
      startButton.setVisibility(View.GONE);
    }

    afterPlan.setVisibility(plan ? View.VISIBLE : View.GONE);
    afterPlan.setEnabled(editable);

    primary.setVisibility(hold ? View.VISIBLE : View.GONE);
    if (hold) {
      boolean ready = !busy && seconds == 0 && reasonValid
          && (!offlineHold || laptop != null || (budget != null && budget.allowsManual(1)))
          && (!plan || PausePolicy.reasonOk(afterPlan.getText().toString()));
      primary.setEnabled(ready);
      primary.setAlpha(ready || holding != null ? 1f : 0.48f);
      int holdSec = (int) (holdMs(budget) / 1000);
      if (holding != null) {
        primary.setText("Keep holding\u2026");
      } else if (seconds > 0) {
        primary.setText("Wait " + seconds + "s");
        handler.postDelayed(tick, 250);
      } else if (offlineHold) {
        primary.setText("Hold " + holdSec + "s for 1 min pause");
      } else {
        primary.setText("Hold " + holdSec + "s to pause");
      }
    }

    boolean showFooter = !scoped && !hold;
    footerActions.setVisibility(showFooter ? View.VISIBLE : View.GONE);
    if (showFooter) {
      dismissButton.setEnabled(editable);
      editRequestButton.setVisibility(View.VISIBLE);
      editRequestButton.setEnabled(editable);
      boolean canRetry = (offlineNoHold || errorOnly) && reasonValid;
      boolean showStore = refused && org.nightingalelab.focus.habits.HabitStore.get(activity)
          .read().wallet.active(org.nightingalelab.focus.habits.Habits.day(System.currentTimeMillis()));
      retryButton.setVisibility((canRetry || showStore) ? View.VISIBLE : View.GONE);
      retryButton.setText(showStore ? "Store" : "Try again");
      retryButton.setEnabled(editable);
      editRequestButton.setText((canRetry || showStore) ? "Edit" : "Edit request");
    }
  }

  private static boolean nonEmpty(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private void beginHold() {
    if (holding != null || !primary.isEnabled() || !needsHold()) return;
    if (laptop != null) {
      beginLaptopHold();
      return;
    }
    final long duration = holdMs(budget);
    final boolean offline = isOfflineHold();
    final String correlation = decision.correlationId;
    final String route = decision.route;
    final long started = SystemClock.elapsedRealtime();
    holdBackground.animateFill(duration);
    holding = () -> {
      holding = null;
      holdBackground.reset();
      if (!dialog.isShowing() || !dialog.getWindow().getDecorView().hasWindowFocus()) return;
      String cause = OK;
      try {
        PhonePauseJudge.Budget current = judge.budget();
        if (!offline && decision.expired()) {
          cause = DECISION_EXPIRED;
          showError(PhonePauseJudge.causeMessage(cause));
          return;
        }
        if (dns.deadline() > 0) {
          cause = DNS_PAUSED;
          showError(PhonePauseJudge.causeMessage(cause));
          return;
        }
        if (offline && !current.allowsManual(1)) {
          cause = CAP_EXCEEDED;
          budget = current;
          showError("Your daily pause allowance changed.");
          return;
        }
        if (requiredWait(current) > requiredWait(budget) || holdMs(current) > duration
            || needsPlan(current) && !needsPlan(budget) || minutes(current) < minutes(budget)) {
          cause = CAP_EXCEEDED;
          budget = current;
          startWait();
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
        judge.recordFullPause(cause, correlation, route, started, offline);
        updateControls();
      }
    };
    handler.postDelayed(holding, duration);
    updateControls();
  }

  private void beginLaptopHold() {
    PhonePauseJudge.Decision selected = decision;
    boolean offline = selected.is("fallback");
    Runnable pending = () -> {};
    holding = pending;
    updateControls();
    new Thread(() -> {
      try {
        org.json.JSONObject token = judge.beginLaptopHold(selected, false);
        String id = token.getString("hold_id");
        long duration = token.getLong("hold_ms");
        activity.runOnUiThread(() -> {
          if (holding != pending || !dialog.isShowing()) {
            cancelLaptopHold(selected, id);
            return;
          }
          laptopHoldId = id;
          holdBackground.animateFill(duration);
          holding = () -> finishLaptopHold(selected, id, offline);
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

  private void finishLaptopHold(PhonePauseJudge.Decision selected, String id, boolean offline) {
    if (!dialog.isShowing() || !dialog.getWindow().getDecorView().hasWindowFocus()) {
      cancelHold();
      return;
    }
    holding = null;
    laptopHoldId = null;
    holdBackground.reset();
    int duration = offline ? 1 : minutes(budget);
    String plan = afterPlan.getText().toString().trim();
    runApply(() -> judge.applyLaptopHold(selected, id, false, duration, plan));
  }

  private void cancelHold() {
    holdBackground.reset();
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
    transientError = message == null ? "Could not pause phone DNS" : message;
    updateControls();
  }

  /** Monochrome wrapping flow layout for 2–3 service chips. */
  static final class ChipFlowLayout extends ViewGroup {
    private final int hGap;
    private final int vGap;

    ChipFlowLayout(Context c, int hGap, int vGap) {
      super(c);
      this.hGap = hGap;
      this.vGap = vGap;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      int maxWidth = MeasureSpec.getSize(widthSpec);
      int x = 0;
      int y = 0;
      int rowHeight = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        if (child.getVisibility() == GONE) continue;
        measureChild(child, widthSpec, heightSpec);
        int cw = child.getMeasuredWidth();
        int ch = child.getMeasuredHeight();
        if (x > 0 && x + cw > maxWidth) {
          x = 0;
          y += rowHeight + vGap;
          rowHeight = 0;
        }
        x += cw + hGap;
        rowHeight = Math.max(rowHeight, ch);
      }
      setMeasuredDimension(maxWidth, resolveSize(y + rowHeight, heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
      int maxWidth = r - l;
      int x = 0;
      int y = 0;
      int rowHeight = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        if (child.getVisibility() == GONE) continue;
        int cw = child.getMeasuredWidth();
        int ch = child.getMeasuredHeight();
        if (x > 0 && x + cw > maxWidth) {
          x = 0;
          y += rowHeight + vGap;
          rowHeight = 0;
        }
        child.layout(x, y, x + cw, y + ch);
        x += cw + hGap;
        rowHeight = Math.max(rowHeight, ch);
      }
    }
  }

  /** Calm monochrome sweeping progress indicator along the bottom of the Checking pill. */
  private static final class ProgressSweepView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator animator;
    private float phase = 0f;

    ProgressSweepView(Context c) {
      super(c);
      track.setColor(0xFF232328);
    }

    void start() {
      if (animator != null && animator.isRunning()) return;
      animator = ValueAnimator.ofFloat(0f, 1f);
      animator.setDuration(1200);
      animator.setRepeatCount(ValueAnimator.INFINITE);
      animator.setInterpolator(new LinearInterpolator());
      animator.addUpdateListener(a -> {
        phase = (float) a.getAnimatedValue();
        invalidate();
      });
      animator.start();
    }

    void stop() {
      if (animator != null) {
        animator.cancel();
        animator = null;
      }
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float w = getWidth();
      float h = getHeight();
      if (w <= 0 || h <= 0) return;
      float r = h / 2f;
      canvas.drawRoundRect(0, 0, w, h, r, r, track);
      float barW = w * 0.36f;
      float left = -barW + (w + barW) * phase;
      sweep.setShader(new LinearGradient(left, 0, left + barW, 0,
          new int[] {Color.TRANSPARENT, 0xFFA1A1AA, Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
      canvas.drawRoundRect(Math.max(0, left), 0, Math.min(w, left + barW), h, r, r, sweep);
    }
  }

  /** Orange hold-to-pause pill drawable with smooth left-to-right progress fill. */
  private static final class HoldPillDrawable extends Drawable {
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float strokePx;
    private ValueAnimator animator;
    private float fraction = 0f;

    HoldPillDrawable(Context c) {
      strokePx = Math.max(1f, Theme.dp(c, 1));
      bg.setColor(Theme.ASK_HOLD_FILL);
      fill.setColor(Theme.ASK_HOLD_PROGRESS);
      border.setStyle(Paint.Style.STROKE);
      border.setStrokeWidth(strokePx);
      border.setColor(Theme.ASK_HOLD_BORDER);
    }

    void animateFill(long durationMs) {
      reset();
      animator = ValueAnimator.ofFloat(0f, 1f);
      animator.setDuration(Math.max(100L, durationMs));
      animator.setInterpolator(new LinearInterpolator());
      animator.addUpdateListener(a -> {
        fraction = (float) a.getAnimatedValue();
        invalidateSelf();
      });
      animator.start();
    }

    void reset() {
      if (animator != null) {
        animator.cancel();
        animator = null;
      }
      if (fraction != 0f) {
        fraction = 0f;
        invalidateSelf();
      }
    }

    @Override
    public void draw(Canvas canvas) {
      Rect b = getBounds();
      if (b.isEmpty()) return;
      float half = strokePx / 2f;
      rect.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
      float r = rect.height() / 2f;
      canvas.drawRoundRect(rect, r, r, bg);
      if (fraction > 0.005f) {
        canvas.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawRect(rect.left, rect.top, rect.left + rect.width() * fraction, rect.bottom, fill);
        canvas.restore();
      }
      canvas.drawRoundRect(rect, r, r, border);
    }

    @Override public void setAlpha(int alpha) { bg.setAlpha(alpha); fill.setAlpha(alpha); border.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { bg.setColorFilter(cf); fill.setColorFilter(cf); border.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
  }
}
