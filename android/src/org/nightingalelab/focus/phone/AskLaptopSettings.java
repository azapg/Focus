package org.nightingalelab.focus.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;
import java.security.KeyStore;
import java.util.List;

final class AskLaptopSettings {
  private static final String RETIRED_GATEWAY = "focus-pause-gateway";

  private AskLaptopSettings() {}

  static void retireGateway(Context context) {
    Context app = context.getApplicationContext();
    SharedPreferences old = app.getSharedPreferences(RETIRED_GATEWAY, Context.MODE_PRIVATE);
    if (old.getAll().isEmpty()) return;
    new Thread(() -> {
      try {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        keys.deleteEntry(RETIRED_GATEWAY);
        old.edit().clear().apply();
      } catch (Exception error) {
        Store.get(app).diagnostic("ask_gateway_key_cleanup_failed");
      }
    }, "focus-retire-ask-key").start();
  }

  static void show(Activity activity, Runnable changed) {
    DeviceHub hub = DeviceHub.get(activity);
    List<DeviceHub.PairedDevice> laptops = hub.eligibleAskLaptops();
    if (laptops.isEmpty()) {
      new AlertDialog.Builder(activity).setTitle("Ask laptop")
          .setMessage("Pair a Windows laptop in Devices.")
          .setPositiveButton("Done", null).show();
      return;
    }
    String[] names = laptops.stream().map(laptop -> laptop.name).toArray(String[]::new);
    new AlertDialog.Builder(activity).setTitle("Ask laptop")
        .setSingleChoiceItems(names, laptops.indexOf(hub.askLaptop()), (dialog, which) -> {
          if (!hub.selectAskLaptop(laptops.get(which))) {
            dialog.dismiss();
            Toast.makeText(activity, "Check that the current Ask laptop has restored protection before switching.",
                Toast.LENGTH_LONG).show();
            return;
          }
          dialog.dismiss();
          changed.run();
        })
        .setNegativeButton("Cancel", null).show();
  }
}
