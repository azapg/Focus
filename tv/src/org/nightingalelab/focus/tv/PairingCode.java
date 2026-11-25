package org.nightingalelab.focus.tv;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Locale;

/** Short, local-presence pairing window; monotonic time, five guesses, single use. */
public final class PairingCode {
  private final SecureRandom random = new SecureRandom();
  private String code;
  private long expires;
  private int attempts;

  public synchronized String issue(long elapsed) {
    code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    expires = elapsed + 300_000;
    attempts = 0;
    return code;
  }

  public synchronized boolean consume(String supplied, long elapsed) {
    if (code == null || elapsed >= expires || attempts >= 5) return false;
    attempts++;
    if (supplied == null || !MessageDigest.isEqual(code.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII))) return false;
    code = null;
    return true;
  }

  public synchronized void cancel() { code = null; }

  public static String token() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return hex(bytes);
  }

  public static String hash(String token) {
    try { return hex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }

  public static String hex(byte[] bytes) {
    StringBuilder text = new StringBuilder();
    for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b & 255));
    return text.toString();
  }
}
