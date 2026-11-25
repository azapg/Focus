import org.nightingalelab.focus.tv.PairingCode;
public final class PairingTest {
  static void ok(boolean value, String message) { if (!value) throw new AssertionError(message); }
  public static void main(String[] args) {
    PairingCode p = new PairingCode();
    String code = p.issue(1000);
    ok(code.matches("[0-9]{6}"), "six-digit code");
    ok(!p.consume("wrong", 1001), "wrong code refused");
    ok(p.consume(code, 1002), "correct code accepted");
    ok(!p.consume(code, 1003), "code single use");
    code = p.issue(2000);
    ok(!p.consume(code, 302000), "expiry uses monotonic time and excludes endpoint");
    code = p.issue(4000);
    for (int i = 0; i < 5; i++) ok(!p.consume("wrong", 4001), "failed guess");
    ok(!p.consume(code, 4002), "five failures exhaust window");
    code = p.issue(5000); p.cancel();
    ok(!p.consume(code, 5001), "cancelled window rejected");
    String token = PairingCode.token();
    ok(token.matches("[a-f0-9]{64}"), "256-bit token");
    ok(!token.equals(PairingCode.token()), "fresh token");
    ok(PairingCode.hash(token).length() == 64 && !PairingCode.hash(token).equals(token), "only hash stored on TV");
    System.out.println("PASS: pairing expiry, five-guess lockout, cancellation, single use, random credentials");
  }
}
