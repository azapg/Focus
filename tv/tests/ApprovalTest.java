import org.nightingalelab.focus.tv.*;
public final class ApprovalTest {
  static void ok(boolean value,String label){if(!value)throw new AssertionError(label);}
  static String repeat(char c){StringBuilder s=new StringBuilder();for(int i=0;i<64;i++)s.append(c);return s.toString();}
  public static void main(String[] args){
    String pin=repeat('a'),nonce=repeat('b');
    ok(ApprovalPairing.comparison(pin,nonce,repeat('c')).equals("973875"),"Cross-platform comparison vector");
    ApprovalPairing p=new ApprovalPairing();
    ApprovalPairing.Session s=p.begin(PairingCode.hash(nonce),"Fixture laptop",1000);
    ok(p.pending(1001)==null,"No prompt before commitment reveal");
    boolean busy=false;try{p.begin(PairingCode.hash(nonce),"Other",1001);}catch(IllegalStateException e){busy=true;}ok(busy,"Concurrent request cannot replace pending one");
    boolean secret=false;try{p.authenticated(s.id,repeat('f'),1002);}catch(IllegalArgumentException e){secret=true;}ok(secret,"Status requires an unguessable secret");
    p.reveal(s.id,s.secret,nonce,pin,1003);ok(p.pending(1004)!=null,"Prompt after valid reveal");
    ok(s.status.equals("waiting")&&s.token.isEmpty(),"Network request never approves itself");
    p.approved(p.allow(s.id,1005),PairingCode.token());ok(s.status.equals("approved")&&!s.token.isEmpty(),"Explicit local approval");
    ok(p.authenticated(s.id,s.secret,121005).status.equals("expired"),"Expiry excludes endpoint and clears result");
    s=p.begin(PairingCode.hash(nonce),"Laptop",122000);
    boolean bad=false;try{p.reveal(s.id,s.secret,repeat('c'),pin,122001);}catch(IllegalArgumentException e){bad=true;}ok(bad&&s.status.equals("declined"),"Commitment cannot be changed after seeing TV nonce");
    s=p.begin(PairingCode.hash(nonce),"Laptop",123000);p.reveal(s.id,s.secret,nonce,pin,123001);p.decline(s.id);ok(p.pending(123002)==null,"Decline removes prompt");
    s=p.begin(PairingCode.hash(nonce),"Laptop",124000);p.cancelAll();ok(p.pending(124001)==null,"Revocation clears approval");
    p.begin(PairingCode.hash(nonce),"Laptop",125000);p.cancelAll();
    boolean rate=false;try{p.begin(PairingCode.hash(nonce),"Laptop",126000);}catch(IllegalStateException e){rate=true;}ok(rate,"Five-attempt rate limit");
    ok(p.begin(PairingCode.hash(nonce),"Laptop",302000)!=null,"Rate window expires");
    System.out.println("PASS: TV approval, nonce commitment, identity binding, expiry, cancellation and attempt limits");
  }
}
