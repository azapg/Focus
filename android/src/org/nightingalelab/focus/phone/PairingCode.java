package org.nightingalelab.focus.phone;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Locale;

public final class PairingCode {
  private static final SecureRandom RANDOM = new SecureRandom();
  private String code = "";
  private long expires;
  private int failures;

  public synchronized String issue(long now) {
    code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    expires = now + 120_000;
    failures = 0;
    return code;
  }

  public synchronized boolean consume(String candidate, long now) {
    if (code.isEmpty() || now >= expires) {
      code = "";
      return false;
    }
    if (candidate != null
        && candidate.length() == 6
        && MessageDigest.isEqual(
            code.getBytes(StandardCharsets.US_ASCII), candidate.getBytes(StandardCharsets.US_ASCII))) {
      code = "";
      failures = 0;
      return true;
    }
    if (++failures >= 5) code = "";
    return false;
  }

  public synchronized void cancel() {
    code = "";
    expires = 0;
  }

  public static String token() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return hex(bytes);
  }

  public static String hash(String value) {
    try {
      return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
    return out.toString();
  }
}
