package org.nightingalelab.focus.tv;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;

public final class BlockActivity extends Activity {
  public static volatile boolean visible;
  public static volatile String presentedPackage = "";
  private String pkg;

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    Ui.fullscreen(this);
    show(getIntent());
  }

  @Override
  public void onNewIntent(Intent i) {
    super.onNewIntent(i);
    setIntent(i);
    show(i);
  }

  private void show(Intent i) {
    pkg = i.getStringExtra("package");
    if (pkg == null || !AppCatalog.allowed(this, pkg) || Store.get(this).restricted(pkg) == null) {
      finish();
      return;
    }
    presentedPackage = pkg;
    LinearLayout root = Ui.screen(this);
    root.setGravity(Gravity.CENTER);
    TextView title = Ui.text(this, AppCatalog.label(this, pkg), 38, Ui.FG);
    title.setGravity(Gravity.CENTER);
    root.addView(title);
    Ui.gap(root, 10);
    TextView status = Ui.text(this, Store.get(this).restricted(pkg), 18, Ui.MUTED);
    status.setGravity(Gravity.CENTER);
    root.addView(status);
    Ui.gap(root, 30);
    Button allow =
        Ui.button(
            this,
            "Allow temporarily",
            () ->
                PauseForm.show(
                    this,
                    pkg,
                    () -> {
                      PauseForm.openApp(this, pkg);
                      finish();
                    }));
    Ui.add(root, allow, 250, 48);
    Ui.gap(root, 10);
    Ui.add(root, Ui.button(this, "Home", this::home), 250, 48);
    setContentView(root);
    allow.requestFocus();
    Store.get(this).diagnostic("blocked_app_presented");
  }

  private void home() {
    startActivity(
        new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    finish();
  }

  @Override
  public void onResume() {
    super.onResume();
    visible = true;
    Ui.fullscreen(this);
  }

  @Override
  public void onPause() {
    visible = false;
    super.onPause();
  }

  @Override
  public void onBackPressed() {
    home();
  }
}
