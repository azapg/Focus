package org.nightingalelab.focus.tv;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;

public final class PauseForm {
  public static void show(Activity a, String pkg, Runnable done) {
    boolean dns = DnsController.KEY.equals(pkg);
    String verb = dns ? "Pause" : "Allow";
    LinearLayout body = Ui.column(a);
    final int[] options = dns ? DnsController.get(a).pauseChoices(System.currentTimeMillis())
        : new int[] {1, 5, 10, 30};
    final int[] duration = {options[0]};
    LinearLayout choices = Ui.row(a);
    Button submit = Ui.button(a, verb + " for " + (options[0] < 60 ? options[0] + " minutes" : (options[0] / 60) + " hour"), () -> {});
    for (int minutes : options) {
      String chipLabel = minutes < 60 ? minutes + "m" : (minutes / 60) + "h";
      String fullLabel = minutes < 60
          ? minutes + " minute" + (minutes == 1 ? "" : "s")
          : (minutes / 60) + " hour" + (minutes == 60 ? "" : "s");
      Button b =
          Ui.button(
              a,
              chipLabel,
              () -> {
                duration[0] = minutes;
                submit.setText(verb + " for " + fullLabel);
                for (int i = 0; i < choices.getChildCount(); i++)
                  choices
                      .getChildAt(i)
                      .setAlpha(
                          ((Button) choices.getChildAt(i)).getText().equals(chipLabel)
                              ? 1f
                              : .5f);
              });
      b.setAlpha(minutes == options[0] ? 1f : .5f);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 46), 1);
      lp.setMargins(0, 0, Ui.dp(a, 8), 0);
      choices.addView(b, lp);
    }
    body.addView(choices);
    Rules.CheckIn[] checkIn = {null};
    if (dns) {
      Ui.gap(body, 12);
      Button feeling = Ui.button(a, "Check-in", () -> {});
      Rules.CheckIn[] feelings = Rules.CheckIn.values();
      String[] labels = new String[feelings.length];
      for (int i = 0; i < labels.length; i++) labels[i] = feelings[i].label;
      feeling.setOnClickListener(v -> new AlertDialog.Builder(a)
          .setItems(labels, (dialog, index) -> {
            checkIn[0] = feelings[index];
            feeling.setText(checkIn[0].label);
            submit.setEnabled(true);
          })
          .show());
      body.addView(feeling);
      submit.setEnabled(false);
    }
    Ui.gap(body, 18);
    EditText input = new EditText(a);
    input.setSingleLine(true);
    input.setHint("Why?");
    input.setContentDescription("Reason");
    input.setTextColor(Ui.FG);
    input.setHintTextColor(Ui.MUTED);
    input.setTextSize(18);
    input.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
    input.setBackground(Ui.shape(Ui.BG, Ui.BORDER, 12, a));
    body.addView(input, new LinearLayout.LayoutParams(-1, Ui.dp(a, 50)));
    Ui.gap(body, 16);
    body.addView(submit);
    Dialog d = Ui.dialog(a, dns ? "Pause NextDNS" : "Allow " + AppCatalog.label(a, pkg), body);
    submit.setOnClickListener(
        v -> {
          try {
            if (dns) {
              DnsController.get(a).pause(duration[0] * 60_000L, input.getText().toString(), checkIn[0]);
            } else {
              Store.get(a).pause(pkg, duration[0] * 60_000L, input.getText().toString());
            }
            d.dismiss();
            done.run();
          } catch (IllegalArgumentException e) {
            input.setError(e.getMessage());
            input.requestFocus();
          } catch (RuntimeException e) {
            input.setError(dns ? e.getMessage() : "Could not save pause");
            Store.get(a).diagnostic("pause_save_failed_" + e.getClass().getSimpleName());
          }
        });
    d.show();
  }

  public static void openApp(Activity a, String pkg) {
    Intent launch = a.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
    if (launch == null) {
      Toast.makeText(a, "App unavailable", Toast.LENGTH_SHORT).show();
      return;
    }
    try {
      a.startActivity(launch);
    } catch (RuntimeException e) {
      Toast.makeText(a, "Could not open app", Toast.LENGTH_SHORT).show();
    }
  }
}
