package org.nightingalelab.focus.tv;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Locale;

/** One first-time approval at a time. All clocks are monotonic; no permission is implicit. */
public final class ApprovalPairing {
  public static final class Session {
    public final String id, secret, serverNonce, commitment, manager;
    public final long expires;
    public String code = "", status = "committed", token = "";
    public long approvedUntil;
    Session(String commitment, String manager, long now) {
      id = PairingCode.token(); secret = PairingCode.token(); serverNonce = PairingCode.token();
      this.commitment = commitment; this.manager = manager; expires = now + 120_000;
    }
  }
  private Session current;
  private long window;
  private int attempts;

  public synchronized Session begin(String commitment, String manager, long now) {
    if (!hex(commitment) || manager == null || manager.trim().isEmpty() || manager.length() > 80)
      throw new IllegalArgumentException("Invalid pairing request");
    if (current != null && now < current.expires && (current.status.equals("committed") || current.status.equals("waiting")))
      throw new IllegalStateException("Another laptop is waiting for approval on the TV");
    if (attempts == 0 || now < window || now - window >= 300_000) { window = now; attempts = 0; }
    if (++attempts > 5) throw new IllegalStateException("Too many pairing attempts. Try again in a few minutes.");
    current = new Session(commitment, manager.trim(), now);
    return current;
  }
  public synchronized Session reveal(String id, String secret, String nonce, String fingerprint, long now) {
    Session session = authenticated(id, secret, now);
    if (!session.status.equals("committed")) throw new IllegalStateException("Pairing request already revealed");
    if (!hex(nonce) || !PairingCode.hash(nonce).equals(session.commitment)) {
      session.status = "declined";
      throw new IllegalArgumentException("Pairing identity check failed");
    }
    session.code = comparison(fingerprint, nonce, session.serverNonce);
    session.status = "waiting";
    return session;
  }
  public synchronized Session authenticated(String id, String secret, long now) {
    if (current == null || !current.id.equals(id) || secret == null || !MessageDigest.isEqual(
        current.secret.getBytes(StandardCharsets.US_ASCII), secret.getBytes(StandardCharsets.US_ASCII)))
      throw new IllegalArgumentException("Unknown pairing request");
    if (now >= (current.status.equals("approved") ? current.approvedUntil : current.expires)) { current.status = "expired"; current.token = ""; }
    return current;
  }
  public synchronized Session pending(long now) {
    if (current == null || now >= current.expires || !current.status.equals("waiting")) return null;
    return current;
  }
  public synchronized Session allow(String id, long now) {
    Session session = pending(now);
    if (session == null || !session.id.equals(id)) throw new IllegalStateException("Pairing request expired");
    session.approvedUntil = now + 120_000;
    return session;
  }
  public synchronized void approved(Session session, String token) {
    if (current != session || !session.status.equals("waiting")) throw new IllegalStateException("Pairing request changed");
    session.token = token; session.status = "approved";
  }
  public synchronized void decline(String id) {
    if (current != null && current.id.equals(id)) { current.status = "declined"; current.token = ""; }
  }
  public synchronized void cancelAll() { current = null; }
  public static boolean hex(String s) { return s != null && s.matches("[a-f0-9]{64}"); }
  public static String comparison(String fingerprint, String clientNonce, String serverNonce) {
    if (!hex(fingerprint) || !hex(clientNonce) || !hex(serverNonce)) throw new IllegalArgumentException("Invalid pairing identity");
    String digest = PairingCode.hash("focus-approval-v2\n" + fingerprint + "\n" + clientNonce + "\n" + serverNonce);
    return String.format(Locale.ROOT, "%06d", Long.parseLong(digest.substring(0, 8), 16) % 1_000_000);
  }
}
