package org.nightingalelab.focus.phone;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Base64;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

public final class PeekActivity extends Activity {
  private static final AtomicBoolean FETCHING = new AtomicBoolean();
  private static final Pattern LINK = Pattern.compile("(?i)\\bhttps?://[^\\s<>\"\\p{Cntrl}]+");
  private static final int MAX_INPUT = 4096;
  private static final String ENABLE_DNS = "Turn on your Focus NextDNS profile to Peek.";
  private EditText input;
  private Button submit;
  private TextView status;
  private Dialog sharedDrawer;
  private int generation;

  private static native String fetchNative(String url, String profile, BooleanSupplier protection);

  private static final class Native {
    static { System.loadLibrary("focus_phone_peek"); }
    static String fetch(String url, String profile, BooleanSupplier protection) {
      return fetchNative(url, profile, protection);
    }
  }

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
    else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    Theme.applySystemBars(this);
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(Theme.BG);
    scroll.setFillViewport(true);
    LinearLayout column = Theme.column(this);
    int padding = Theme.dp(this, 18);
    column.setPadding(padding, padding, padding, padding);
    scroll.setOnApplyWindowInsetsListener((view, insets) -> {
      android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      return insets;
    });
    scroll.addView(column);
    setContentView(scroll);

    LinearLayout header = Theme.row(this);
    header.addView(Theme.text(this, "Peek a link", 25, Theme.FG, true),
        new LinearLayout.LayoutParams(0, -2, 1));
    Button done = button("Done");
    done.setOnClickListener(v -> finish());
    header.addView(done);
    column.addView(header);
    Theme.gap(column, 18);

    LinearLayout form = Theme.row(this);
    input = new EditText(this);
    input.setSaveEnabled(false);
    input.setSingleLine(true);
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
    input.setTextColor(Theme.FG);
    input.setHintTextColor(Theme.MUTED);
    input.setHint("Post link");
    input.setFilters(new InputFilter[] { new InputFilter.LengthFilter(MAX_INPUT) });
    form.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
    submit = button("Peek");
    submit.setOnClickListener(v -> submit(input.getText().toString()));
    input.setOnEditorActionListener((v, action, event) -> {
      if (action != android.view.inputmethod.EditorInfo.IME_ACTION_GO) return false;
      submit(input.getText().toString());
      return true;
    });
    form.addView(submit);
    column.addView(form);

    status = Theme.text(this, "", 14, Theme.EXPLAIN, false);
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    status.setVisibility(View.GONE);
    column.addView(status);

    column.setFocusableInTouchMode(true);
    column.requestFocus();
    accept(getIntent());
  }

  private Button button(String label) {
    Button button = new Button(this);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextColor(Theme.FG);
    button.setMinHeight(Theme.dp(this, 44));
    button.setBackground(Theme.roundRect(this, Theme.SEC, Theme.LINE, 10));
    return button;
  }

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    accept(intent);
  }

  @Override protected void onDestroy() {
    closeSharedDrawer();
    super.onDestroy();
  }

  private void closeSharedDrawer() {
    if (sharedDrawer == null) return;
    sharedDrawer.dismiss();
    sharedDrawer = null;
  }

  private void accept(Intent intent) {
    setIntent(new Intent());
    if (intent == null) return;
    String action = intent.getAction();
    if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_VIEW.equals(action)) return;
    generation++;
    closeSharedDrawer();
    String sharedLink = null;
    showStatus("");
    input.setText("");
    submit.setEnabled(true);
    try {
      CharSequence value = Intent.ACTION_SEND.equals(action)
          ? intent.getCharSequenceExtra(Intent.EXTRA_TEXT) : intent.getDataString();
      sharedLink = singleLink(value);
    } catch (RuntimeException ignored) {
      // Other apps can send malformed extras. Treat them as an invalid link.
    }
    if (sharedLink == null) showStatus("Paste a single post link.");
    else input.setText(sharedLink);
  }

  static String singleLink(CharSequence text) {
    if (text == null || text.length() > MAX_INPUT * 2) return null;
    Matcher match = LINK.matcher(text);
    if (!match.find()) return null;
    String link = match.group();
    return link.length() <= MAX_INPUT && !match.find() ? link : null;
  }

  private void submit(String text) {
    if (!submit.isEnabled()) return;
    String url = singleLink(text);
    if (url == null) {
      showStatus("Paste a single post link.");
      return;
    }
    int request = ++generation;
    submit.setEnabled(false);
    getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
    showStatus("Loading…");
    fetch(this, url, reply -> {
      if (request != generation) return;
      submit.setEnabled(true);
      JSONObject post = reply.optJSONObject("snapshot");
      if (post == null) {
        showStatus(refusalMessage(reply));
        return;
      }
      input.setText("");
      showStatus("");
      sharedDrawer = showPost(this, post);
      sharedDrawer.setOnDismissListener(dismissed -> {
        if (sharedDrawer == dismissed) sharedDrawer = null;
      });
    });
  }

  static void fetch(Activity activity, String url, Consumer<JSONObject> done) {
    if (url.length() > MAX_INPUT) {
      done.accept(refusal("too_long"));
      return;
    }
    DnsController dns = DnsController.get(activity);
    String profile = dns.profile();
    Network network = dns.activePrivateDnsNetwork();
    if (network == null || !protectedOn(dns, profile, network)) {
      done.accept(refusal("protection_off"));
      return;
    }
    if (!FETCHING.compareAndSet(false, true)) {
      done.accept(refusal("busy"));
      return;
    }
    new Thread(() -> {
      JSONObject reply = refusal("unavailable");
      try {
        if ((dns.verifiedOn(network) || dns.verify()) && protectedOn(dns, profile, network)) {
          String response = Native.fetch(url, profile, () -> protectedOn(dns, profile, network));
          if (response != null) reply = new JSONObject(response);
        } else {
          reply = refusal("protection_off");
        }
      } catch (JSONException | LinkageError | RuntimeException ignored) {
        // Leave the link and post out of logs.
      } finally {
        FETCHING.set(false);
      }
      JSONObject result = reply;
      activity.runOnUiThread(() -> {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        done.accept(protectedOn(dns, profile, network) ? result : refusal("protection_off"));
      });
    }, "focus-peek").start();
  }

  private static JSONObject refusal(String reason) {
    try {
      return new JSONObject().put("refusal", reason);
    } catch (JSONException impossible) {
      throw new AssertionError(impossible);
    }
  }

  static String refusalMessage(JSONObject reply) {
    switch (reply.optString("refusal")) {
      case "permalink": return "Use a direct link to a public post.";
      case "unsupported": return "Peek supports public posts on X and Facebook.";
      case "protection_off": return ENABLE_DNS;
      case "busy": return "Peek is busy. Try again.";
      case "too_long": return "That link is too long.";
      default: return "Snapshot unavailable.";
    }
  }

  private static boolean protectedOn(DnsController dns, String profile, Network network) {
    return dns.deadline() == 0 && profile.equals(dns.profile())
        && network.equals(dns.activePrivateDnsNetwork());
  }

  private void showStatus(String message) {
    status.setText(message);
    status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
  }

  static Dialog showPost(Activity activity, JSONObject post) {
    Dialog drawer = new Dialog(activity);
    drawer.requestWindowFeature(Window.FEATURE_NO_TITLE);
    drawer.setCanceledOnTouchOutside(true);

    LinearLayout sheet = Theme.column(activity);
    sheet.setBackground(Theme.topRounded(activity, Theme.BG, Theme.LINE, 20));
    FrameLayout handle = new FrameLayout(activity);
    View bar = new View(activity);
    bar.setBackground(Theme.roundRect(activity, Theme.DIM, 0, 4));
    handle.addView(bar, new FrameLayout.LayoutParams(
        Theme.dp(activity, 30), Theme.dp(activity, 3), Gravity.CENTER));
    handle.setContentDescription("Dismiss post");
    handle.setOnClickListener(v -> drawer.dismiss());
    handle.setOnTouchListener(new View.OnTouchListener() {
      private float startY;

      @Override public boolean onTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) startY = event.getRawY();
        if (event.getAction() == MotionEvent.ACTION_MOVE
            && event.getRawY() - startY > Theme.dp(activity, 28)) {
          drawer.dismiss();
          return true;
        }
        return false;
      }
    });
    sheet.addView(handle, new LinearLayout.LayoutParams(-1, Theme.dp(activity, 32)));

    int maxHeight = Math.round(activity.getWindowManager().getCurrentWindowMetrics()
        .getBounds().height() * 0.65f);
    ScrollView scroll = new ScrollView(activity) {
      @Override protected void onMeasure(int width, int height) {
        int available = MeasureSpec.getMode(height) == MeasureSpec.UNSPECIFIED
            ? maxHeight : Math.min(MeasureSpec.getSize(height), maxHeight);
        super.onMeasure(width, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST));
      }
    };
    LinearLayout card = Theme.column(activity);
    int pad = Theme.dp(activity, 14);
    card.setPadding(pad, 0, pad, pad);
    scroll.addView(card);
    sheet.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    renderPost(activity, card, post);

    drawer.setContentView(sheet);
    Window window = drawer.getWindow();
    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_SECURE);
    window.setDimAmount(0.5f);
    window.setGravity(Gravity.BOTTOM);
    drawer.show();
    window.setLayout(-1, -2);
    Store.get(activity).recordPeek();
    return drawer;
  }

  private static void renderPost(Activity activity, LinearLayout card, JSONObject post) {
    Bitmap bitmap = postImage(post);
    boolean imageOnly = bitmap != null && "X".equals(post.optString("site"))
        && post.isNull("title") && post.isNull("text");
    if (!imageOnly) {
      addText(activity, card, post, "site", 12, Theme.MUTED, true);
      addText(activity, card, post, "title", 17, Theme.FG, true);
      addText(activity, card, post, "text", 14, Theme.FG, false);
    }
    if (bitmap != null) {
      ImageView picture = new ImageView(activity);
      picture.setAdjustViewBounds(true);
      picture.setImageBitmap(bitmap);
      picture.setContentDescription(post.optString("image_alt", ""));
      if (card.getChildCount() > 0) Theme.gap(card, 10);
      card.addView(picture, new LinearLayout.LayoutParams(-1, -2));
    }
  }

  private static Bitmap postImage(JSONObject post) {
    String data = post.optString("image");
    if (data.length() >= 960_000
        || !(data.startsWith("data:image/png;base64,") || data.startsWith("data:image/jpeg;base64,"))) {
      return null;
    }
    try {
      byte[] bytes = Base64.decode(data.substring(data.indexOf(',') + 1), Base64.DEFAULT);
      return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private static void addText(Activity activity, LinearLayout card, JSONObject post,
      String key, int size, int color, boolean medium) {
    if (post.isNull(key)) return;
    String value = post.optString(key);
    if (value.isEmpty()) return;
    if (card.getChildCount() > 0) Theme.gap(card, 6);
    card.addView(Theme.text(activity, value, size, color, medium));
  }
}
