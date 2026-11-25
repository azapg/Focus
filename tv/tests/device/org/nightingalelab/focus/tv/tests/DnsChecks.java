package org.nightingalelab.focus.tv.tests;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.view.accessibility.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.json.*;
import org.nightingalelab.focus.tv.*;

/** Real system resolver and NextDNS checks, explicitly invoked only on the authorized TV. */
public final class DnsChecks extends Instrumentation {
  private Bundle args;
  private final JSONObject result = new JSONObject();
  private DnsController dns;
  private boolean profileConfirmed;
  private Context c;
  private CountDownLatch stageAck;
  private String stageToken;
  private boolean receiverRegistered;
  private final BroadcastReceiver acknowledge =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (stageToken != null && stageToken.equals(intent.getStringExtra("token")))
            stageAck.countDown();
        }
      };

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    args = b;
    start();
  }

  private void check(boolean ok, String label) throws Exception {
    if (!ok) throw new AssertionError(label);
    result.put(label, true);
  }

  private void stage(String label) {
    stageAck = new CountDownLatch(1);
    stageToken = UUID.randomUUID().toString();
    Bundle b = new Bundle();
    b.putString("stage", label + ":" + stageToken);
    sendStatus(1, b);
    try {
      if (!stageAck.await(60, TimeUnit.SECONDS))
        throw new AssertionError("Controller did not acknowledge stage " + label);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private void connected() throws Exception {
    long end = SystemClock.elapsedRealtime() + 20000;
    while (!dns.active() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(200);
    check(dns.active(), "validated_private_dns_active");
    boolean verified = false;
    for (int i = 0; i < 3 && !verified; i++) {
      verified = dns.verify();
      if (!verified) SystemClock.sleep(1000);
    }
    check(verified, "real_nextdns_dot_endpoint_verified");
  }

  private List<InetAddress> lookup(String host, int type) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    List<InetAddress> addresses = new ArrayList<>();
    final Exception[] failure = {null};
    CancellationSignal cancel = new CancellationSignal();
    Network network = c.getSystemService(ConnectivityManager.class).getActiveNetwork();
    DnsResolver.getInstance()
        .query(
            network,
            host,
            type,
            DnsResolver.FLAG_NO_CACHE_LOOKUP | DnsResolver.FLAG_NO_CACHE_STORE,
            Runnable::run,
            cancel,
            new DnsResolver.Callback<List<InetAddress>>() {
              public void onAnswer(List<InetAddress> answer, int rcode) {
                if (rcode != 0 && rcode != 3) failure[0] = new IOException("DNS rcode " + rcode);
                addresses.addAll(answer);
                latch.countDown();
              }

              public void onError(DnsResolver.DnsException e) {
                failure[0] = e;
                latch.countDown();
              }
            });
    if (!latch.await(8, TimeUnit.SECONDS)) {
      cancel.cancel();
      throw new AssertionError("DNS lookup timeout");
    }
    if (failure[0] != null) throw failure[0];
    return addresses;
  }

  private boolean blocked(String host) throws Exception {
    for (int type : new int[] {DnsResolver.TYPE_A, DnsResolver.TYPE_AAAA}) {
      for (InetAddress ip : lookup(host, type))
        if (!ip.isLoopbackAddress() && !ip.isAnyLocalAddress()) return false;
    }
    return true;
  }

  private void open() {
    c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    SystemClock.sleep(2000);
  }

  private AccessibilityNodeInfo root() {
    AccessibilityNodeInfo r =
        getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .getRootInActiveWindow();
    if (r == null || !c.getPackageName().contentEquals(r.getPackageName()))
      throw new AssertionError("Expected only Focus UI");
    return r;
  }

  private void click(String text, boolean exact) {
    for (AccessibilityNodeInfo n : root().findAccessibilityNodeInfosByText(text)) {
      if (exact && !text.contentEquals(n.getText())) continue;
      while (n != null && !n.isClickable()) n = n.getParent();
      if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        SystemClock.sleep(500);
        return;
      }
    }
    throw new AssertionError("Control not found: " + text);
  }

  private AccessibilityNodeInfo reason(AccessibilityNodeInfo n) {
    if (n.isEditable() && "Reason".contentEquals(n.getContentDescription())) return n;
    for (int i = 0; i < n.getChildCount(); i++) {
      AccessibilityNodeInfo child = n.getChild(i);
      if (child != null) {
        AccessibilityNodeInfo found = reason(child);
        if (found != null) return found;
      }
    }
    return null;
  }

  @Override
  public void onStart() {
    super.onStart();
    boolean keepPause = false;
    try {
      c = getTargetContext();
      new File(c.getExternalFilesDir(null), "dns-test-stage").delete();
      IntentFilter filter = new IntentFilter("org.nightingalelab.focus.tv.tests.DNS_STAGE_ACK");
      if (Build.VERSION.SDK_INT >= 33)
        c.registerReceiver(
            acknowledge, filter, "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
      else c.registerReceiver(acknowledge, filter, "android.permission.DUMP", null);
      receiverRegistered = true;
      result.put("process_id", android.os.Process.myPid());
      dns = DnsController.get(c);
      String phase = args.getString("phase", "runtime");
      String profile = args.getString("profile", "").toLowerCase(Locale.ROOT);
      if (!profile.matches("[a-f0-9]{6}") || !profile.equals(dns.profile()))
        throw new IllegalArgumentException("Supply the profile already configured for the test TV");
      profileConfirmed = true;
      if ("diagnose".equals(phase)) {
        JSONArray connections = new JSONArray();
        Network n = c.getSystemService(ConnectivityManager.class).getActiveNetwork();
        String[] servers = args.getString("diagnostic_servers", "").split(",");
        for (int i = 0; i < servers.length; i++) {
          InetAddress server = InetAddresses.parseNumericAddress(servers[i].trim());
          JSONObject outcome = new JSONObject().put("server_index", i);
          try (Socket raw = new Socket()) {
            n.bindSocket(raw);
            raw.connect(new InetSocketAddress(server, 853), 4000);
            try (SSLSocket tls =
                (SSLSocket)
                    ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(raw, profile + ".dns.nextdns.io", 853, true)) {
              tls.setSoTimeout(5000);
              SSLParameters p = tls.getSSLParameters();
              p.setEndpointIdentificationAlgorithm("HTTPS");
              tls.setSSLParameters(p);
              tls.startHandshake();
              ByteArrayOutputStream bytes = new ByteArrayOutputStream();
              DataOutputStream q = new DataOutputStream(bytes);
              q.writeShort(1451);
              q.writeShort(256);
              q.writeShort(1);
              q.writeShort(0);
              q.writeShort(0);
              q.writeShort(0);
              for (String label : new String[] {"example", "com"}) {
                q.writeByte(label.length());
                q.writeBytes(label);
              }
              q.writeByte(0);
              q.writeShort(1);
              q.writeShort(1);
              DataOutputStream out = new DataOutputStream(tls.getOutputStream());
              out.writeShort(bytes.size());
              out.write(bytes.toByteArray());
              out.flush();
              DataInputStream in = new DataInputStream(tls.getInputStream());
              byte[] answer = new byte[in.readUnsignedShort()];
              in.readFully(answer);
              outcome.put("tls", tls.getSession().getProtocol()).put("dns_rcode", answer[3] & 15);
            }
          } catch (Exception e) {
            outcome
                .put("error", e.getClass().getSimpleName())
                .put("message", String.valueOf(e.getMessage()));
          }
          connections.put(outcome);
        }
        result.put("direct_tv_dot", connections);
        result.put("passed", true);
      } else if ("ui".equals(phase)) {
        connected();
        if (!RestrictionService.connected) stage("access_rebind");
        for (String screen : new String[] {"nextdns_on", "nextdns_reopened", "nextdns_final"}) {
          open();
          click("Settings", true);
          click("NextDNS ·", false);
          SystemClock.sleep(2500);
          stage(screen);
          click("Close", true);
          if (!"nextdns_final".equals(screen)) {
            Intent fixture =
                c.getPackageManager()
                    .getLeanbackLaunchIntentForPackage(c.getPackageName() + ".fixture");
            if (fixture == null) throw new AssertionError("Missing synthetic fixture");
            c.startActivity(fixture.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1000);
          }
        }
        check(true, "native_dns_dialog_reopens_after_background_verification");
      } else if ("youtube".equals(phase)) {
        connected();
        String pkg = "com.google.android.youtube.tv";
        check(Store.get(c).restricted(pkg) == null, "youtube_has_no_local_app_block_rule");
        Intent launch = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) throw new AssertionError("YouTube not installed");
        c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        boolean error = false;
        long until = SystemClock.elapsedRealtime() + 25000;
        while (!error && SystemClock.elapsedRealtime() < until) {
          AccessibilityNodeInfo r =
              getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                  .getRootInActiveWindow();
          if (r != null && pkg.contentEquals(r.getPackageName())) {
            for (String message :
                new String[] {
                  "Can't connect",
                  "No connection",
                  "You're offline",
                  "Connect to the internet",
                  "Network error",
                  "Something went wrong"
                }) if (!r.findAccessibilityNodeInfosByText(message).isEmpty()) error = true;
          }
          if (!error) SystemClock.sleep(500);
        }
        check(error, "fresh_youtube_app_shows_network_error_under_nextdns");
        check(blocked("www.youtube.com"), "youtube_error_with_system_dns_block");
        open();
      } else if ("cleanup".equals(phase)) {
        Store.get(c)
            .getWritableDatabase()
            .delete(
                "pauses",
                "pkg=? AND reason IN (?,?,?)",
                new String[] {
                  DnsController.KEY,
                  "Synthetic NextDNS integration test",
                  "Synthetic early resume test",
                  "Synthetic DNS process restart"
                });
        check(true, "synthetic_dns_pause_history_removed");
        String fixture = c.getPackageName() + ".fixture";
        Store.get(c).getWritableDatabase().delete("spans", "pkg=?", new String[] {fixture});
        Store.get(c).getWritableDatabase().delete("pauses", "pkg=?", new String[] {fixture});
        check(true, "synthetic_fixture_history_removed");
      } else if ("denied".equals(phase)) {
        String mode = Settings.Global.getString(c.getContentResolver(), "private_dns_mode");
        boolean refused = false;
        try {
          dns.pause(5000, "Synthetic permission test");
        } catch (IllegalStateException e) {
          refused = true;
        }
        check(
            !dns.permitted()
                && refused
                && Objects.equals(
                    mode, Settings.Global.getString(c.getContentResolver(), "private_dns_mode")),
            "missing_grant_refuses_pause_without_dns_change");
      } else if ("restart-start".equals(phase)) {
        connected();
        dns.pause(45000, "Synthetic DNS process restart");
        check(dns.deadline() > 0, "dns_pause_saved_for_restart");
        keepPause = true;
      } else if ("restart-check".equals(phase)) {
        open();
        check(dns.deadline() > 0, "dns_pause_survives_process_restart");
        long until = dns.deadline();
        while (SystemClock.elapsedRealtime() < until + 500) SystemClock.sleep(200);
        connected();
        check(blocked("www.youtube.com"), "restart_alarm_restores_real_filtering");
      } else {
        check(dns.permitted(), "settings_permission_granted");
        dns.connect(profile);
        connected();
        check(blocked("www.youtube.com"), "youtube_blocked_A_and_AAAA");
        check(!blocked("example.com"), "allowed_control_resolves");
        boolean invalid = false;
        try {
          dns.connect("invalid.example");
        } catch (IllegalArgumentException e) {
          invalid = true;
        }
        check(invalid && dns.configured(), "invalid_profile_rejected_without_dns_change");
        if (!RestrictionService.connected) stage("access_rebind");
        open();
        click("Settings", true);
        click("NextDNS ·", false);
        stage("nextdns_on");
        click("Pause filtering", true);
        click("1m", true);
        click("Pause for 1 minute", true);
        check(dns.deadline() == 0, "empty_dns_reason_rejected_in_native_UI");
        AccessibilityNodeInfo input = reason(root());
        check(input != null, "dns_reason_field_present");
        Bundle text = new Bundle();
        text.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            "Synthetic NextDNS integration test");
        check(
            input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text), "dns_reason_entered");
        click("Pause for 1 minute", true);
        long until = dns.deadline();
        check(
            until > SystemClock.elapsedRealtime() + 50000
                && until <= SystemClock.elapsedRealtime() + 60000,
            "one_minute_native_dns_pause");
        SystemClock.sleep(1800);
        check(!blocked("www.youtube.com"), "youtube_resolves_during_real_dns_pause");
        check(!blocked("example.com"), "allowed_control_during_pause");
        stage("nextdns_paused");
        click("Close", true);
        Intent fixture =
            c.getPackageManager()
                .getLeanbackLaunchIntentForPackage(c.getPackageName() + ".fixture");
        if (fixture == null) throw new AssertionError("Missing synthetic fixture");
        c.startActivity(fixture.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        SystemClock.sleep(2200);
        check(!RestrictionService.timerPackage.isEmpty(), "dns_corner_countdown_over_fixture");
        stage("dns_countdown");
        while (SystemClock.elapsedRealtime() < until + 500) SystemClock.sleep(200);
        connected();
        check(
            dns.deadline() == 0 && blocked("www.youtube.com"),
            "automatic_expiry_restores_real_youtube_block");
        check(RestrictionService.timerPackage.isEmpty(), "dns_countdown_removed_on_expiry");
        dns.pause(15000, "Synthetic early resume test");
        SystemClock.sleep(1500);
        dns.resume();
        connected();
        check(dns.deadline() == 0 && blocked("www.youtube.com"), "early_resume_restores_filtering");
        SharedPreferences settings = c.getSharedPreferences("nextdns", Context.MODE_PRIVATE);
        String beforeMode = settings.getString("before_mode", null);
        String beforeHost = settings.getString("before_host", null);
        dns.disconnect();
        SystemClock.sleep(1800);
        check(
            Objects.equals(beforeMode, Settings.Global.getString(c.getContentResolver(), "private_dns_mode"))
                && Objects.equals(beforeHost, Settings.Global.getString(c.getContentResolver(), "private_dns_specifier")),
            "original_dns_settings_restored");
        dns.connect(profile);
        connected();
        check(blocked("www.youtube.com"), "final_nextdns_reconnected_and_filtering");
        open();
        click("Settings", true);
        click("NextDNS ·", false);
        stage("nextdns_final");
      }
      result.put("passed", true);
    } catch (Throwable e) {
      try {
        result.put("passed", false).put("failure", e.toString());
      } catch (Exception ignored) {
      }
    } finally {
      if (receiverRegistered) c.unregisterReceiver(acknowledge);
      if (profileConfirmed && !keepPause && dns.enabled() && !"denied".equals(args.getString("phase")))
        try {
          dns.resume();
        } catch (RuntimeException e) {
          try {
            result.put("cleanup_error", e.toString()).put("passed", false);
          } catch (Exception ignored) {
          }
        }
      Bundle out = new Bundle();
      out.putString("result", result.toString());
      finish(result.optBoolean("passed") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, out);
    }
  }
}
