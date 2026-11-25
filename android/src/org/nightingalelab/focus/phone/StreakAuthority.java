package org.nightingalelab.focus.phone;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Native Store bridge. Call on a worker thread using the pinned, reciprocal laptop link. */
public final class StreakAuthority {
  private StreakAuthority() {}

  static String trialAuthority(Context context) {
    File marker = new File(context.getFilesDir(), "streak-trial");
    if (!marker.isFile() || marker.length() > 128) return "";
    try {
      String id = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim();
      return id.matches("[A-Za-z0-9_.:-]{1,100}") ? id : "";
    } catch (IOException ignored) {
      return "";
    }
  }

  public static JSONObject view(Context context) throws Exception {
    JSONObject response = request(context, new JSONObject().put("action", "streak_view"));
    return response == null ? null : response.optJSONObject("streak");
  }

  static FocusStats.CleanStreak history(Context context) throws Exception {
    JSONObject data = view(context);
    if (data == null) return null;
    java.util.Map<java.time.LocalDate, FocusStats.CleanStreak.Status> statuses = new java.util.HashMap<>();
    org.json.JSONArray days = data.getJSONArray("days");
    for (int i = 0; i < days.length(); i++) {
      JSONObject day = days.getJSONObject(i);
      java.time.LocalDate date = java.time.LocalDate.ofEpochDay(day.getLong("day"));
      statuses.put(date, FocusStats.CleanStreak.Status.valueOf(day.getString("status").toUpperCase(java.util.Locale.ROOT)));
    }
    if (!data.isNull("pendingDay")) {
      statuses.put(java.time.LocalDate.ofEpochDay(data.getLong("pendingDay")), FocusStats.CleanStreak.Status.PENDING);
    }
    return new FocusStats.CleanStreak(data.getInt("count"), data.getBoolean("freezeReady"), statuses);
  }

  public static JSONObject buyFreeze(Context context, String requestId) throws Exception {
    JSONObject response = request(context, new JSONObject()
        .put("action", "streak_buy_freeze").put("request_id", requestId));
    return response == null ? new JSONObject().put("status", "unavailable") : response;
  }

  private static JSONObject request(Context context, JSONObject command) throws Exception {
    String authority = trialAuthority(context);
    if (authority.isEmpty()) return null;
    for (DeviceHub.PairedDevice device : DeviceHub.get(context).list()) {
      if (authority.equals(device.id) && "laptop".equals(device.kind) && !device.relayed
          && !device.address.isEmpty() && !device.fingerprint.isEmpty() && !device.token.isEmpty()) {
        return LanClient.request(device.address, device.fingerprint, device.token, "/v1/command", command).json;
      }
    }
    return null;
  }
}
