package org.nightingalelab.focus.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Base64;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class PauseGatewaySettings {
  static final String DEFAULT_MODEL = "anthropic/claude-haiku-5.5";
  static final String LUNA = "openai/gpt-6-luna";
  private static final String ALIAS = "focus-pause-gateway";

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(ALIAS, Context.MODE_PRIVATE);
  }

  static boolean configured(Context context) {
    return prefs(context).contains("credential");
  }

  static String model(Context context) {
    return prefs(context).getString("model", DEFAULT_MODEL);
  }

  private static SecretKey key(boolean create) throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
    if (existing != null || !create) return existing;
    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(new KeyGenParameterSpec.Builder(ALIAS,
        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
    return generator.generateKey();
  }

  static String credential(Context context) throws Exception {
    String saved = prefs(context).getString("credential", null);
    if (saved == null) return "";
    String[] parts = saved.split(":", 2);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
  }

  private static void save(Context context, String model, String credential, boolean remove) throws Exception {
    if (!model.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*/[a-zA-Z0-9][a-zA-Z0-9_.-]*")) {
      throw new IllegalArgumentException("Enter a Gateway model ID");
    }
    SharedPreferences.Editor edit = prefs(context).edit().putString("model", model);
    if (remove) edit.remove("credential");
    if (!remove && !credential.isEmpty()) {
      if (!credential.matches("[!-~]+")) throw new IllegalArgumentException("Enter a valid Gateway key");
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key(true));
      byte[] bytes = cipher.doFinal(credential.getBytes(StandardCharsets.UTF_8));
      edit.putString("credential", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
          + ":" + Base64.encodeToString(bytes, Base64.NO_WRAP));
    }
    if (!edit.commit()) throw new IllegalStateException("Could not save Gateway settings");
  }

  static void show(Activity activity, Runnable changed) {
    LinearLayout fields = Theme.column(activity);
    int pad = Theme.dp(activity, 20);
    fields.setPadding(pad, pad, pad, 0);
    EditText model = new EditText(activity);
    model.setHint("Gateway model ID");
    model.setSingleLine(true);
    model.setFilters(new InputFilter[] {new InputFilter.LengthFilter(120)});
    model.setText(model(activity));
    fields.addView(model);
    EditText credential = new EditText(activity);
    credential.setHint(configured(activity) ? "Saved · enter to replace" : "Vercel AI Gateway key");
    credential.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    credential.setFilters(new InputFilter[] {new InputFilter.LengthFilter(2048)});
    credential.setImportantForAutofill(EditText.IMPORTANT_FOR_AUTOFILL_NO);
    credential.setSaveEnabled(false);
    fields.addView(credential);
    AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Pause assistant").setView(fields)
        .setNegativeButton("Cancel", null).setNeutralButton("Remove key", null)
        .setPositiveButton("Save", null).create();
    dialog.show();
    dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(configured(activity));
    for (int button : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL}) {
      dialog.getButton(button).setOnClickListener(v -> {
        try {
          save(activity, model.getText().toString().trim(), credential.getText().toString().trim(),
              button == AlertDialog.BUTTON_NEUTRAL);
          dialog.dismiss();
          changed.run();
        } catch (IllegalArgumentException error) {
          model.setError(error.getMessage());
        } catch (Exception error) {
          credential.setError("Could not save Gateway settings");
        }
      });
    }
  }
}
