package org.nightingalelab.focus.phone;

import android.os.SystemClock;
import java.util.*;
import org.json.*;

/** A laptop grant projected onto the phone's monotonic clock. */
final class ScopedAccessWindow {
  final String laptopId;
  final String grantId;
  final long remoteDeadline;
  final List<String> services;
  final long deadline;
  final long duration;
  final long appliedAt;

  private ScopedAccessWindow(String laptopId, String grantId, long remoteDeadline,
      List<String> services, long deadline, long duration, long appliedAt) {
    this.laptopId = laptopId;
    this.grantId = grantId;
    this.remoteDeadline = remoteDeadline;
    this.services = Collections.unmodifiableList(services);
    this.deadline = deadline;
    this.duration = duration;
    this.appliedAt = appliedAt;
  }

  boolean sameGrant(ScopedAccessWindow other) {
    return other != null && laptopId.equals(other.laptopId) && grantId.equals(other.grantId)
        && (!grantId.isEmpty() || remoteDeadline == other.remoteDeadline);
  }

  long remaining() {
    return Math.max(0, deadline - SystemClock.elapsedRealtime());
  }

  ScopedAccessWindow appliedNow() {
    return new ScopedAccessWindow(laptopId, grantId, remoteDeadline, services, deadline, duration,
        SystemClock.elapsedRealtimeNanos());
  }

  ScopedAccessWindow withoutTime() {
    return new ScopedAccessWindow(laptopId, grantId, remoteDeadline, services, 0, duration, appliedAt);
  }

  static ScopedAccessWindow sample(String id, JSONObject snapshot, long clockAnchor, ScopedAccessWindow previous) {
    JSONObject dns = snapshot.optJSONObject("dns");
    long now = snapshot.optLong("now");
    long expires = dns == null ? 0 : dns.optLong("scoped_until");
    if (now <= 0 || expires <= 0 || dns.optLong("pause_until") > now) return null;
    List<String> services = services(dns.optJSONArray("scoped_services"));
    if (services.isEmpty()) return null;
    JSONObject grant = dns.optJSONObject("scoped_grant");
    String grantId = grant == null ? "" : grant.optString("id");
    long reportedRemaining = Math.max(0, expires - now);
    long duration = grant == null ? 0 : grant.optLong("duration_ms");
    long deadline = reportedRemaining == 0 ? 0
        : clockAnchor + (duration > 0 ? Math.min(duration, reportedRemaining) : reportedRemaining);
    ScopedAccessWindow next = new ScopedAccessWindow(id, grantId, expires, services, deadline, duration, 0);
    if (next.sameGrant(previous)) {
      // Keep an expired bound until the laptop reports that the grant is gone or replaced.
      return new ScopedAccessWindow(id, grantId, expires, services, Math.min(deadline, previous.deadline),
          duration, previous.appliedAt);
    }
    return next;
  }

  JSONObject save(int boot) throws JSONException {
    return new JSONObject().put("grant_id", grantId).put("remote_deadline", remoteDeadline)
        .put("services", new JSONArray(services))
        .put("boot", boot).put("deadline", deadline).put("expired", remaining() == 0).put("duration", duration)
        .put("applied_at", appliedAt);
  }

  static ScopedAccessWindow restore(String id, JSONObject saved, int boot) {
    if (saved == null) return null;
    // Wait for a fresh laptop clock sample after reboot, while retaining known expiry
    // so a laptop clock correction can't restart the same grant.
    boolean sameBoot = boot >= 0 && saved.optInt("boot", -1) == boot;
    if (!sameBoot && !saved.optBoolean("expired")) return null;
    return new ScopedAccessWindow(id, saved.optString("grant_id"), saved.optLong("remote_deadline"),
        services(saved.optJSONArray("services")), sameBoot ? saved.optLong("deadline") : 0,
        saved.optLong("duration"), sameBoot ? saved.optLong("applied_at") : 0);
  }

  private static List<String> services(JSONArray names) {
    List<String> result = new ArrayList<>();
    if (names != null) for (int i = 0; i < Math.min(3, names.length()); i++) {
      String name = names.optString(i).trim();
      if (!name.isEmpty()) result.add(name);
    }
    return result;
  }
}
