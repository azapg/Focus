import { useEffect, useRef, useState } from "react";
import { ChevronDown, X } from "lucide-react";
import { Button } from "@/components/ui/button";

type WalletView = {
  active: boolean;
  day: number;
  balance: number;
  time_off_bought: number;
  time_off_limit: number;
  time_off_price_per_minute: number;
  needs_upgrade: string[];
};

type Purchase = { status: "bought" | "already_bought" | "limit_reached" }
  | { status: "not_enough"; missing: number };

const minutes = 15;
const coins = (amount: number) => `${amount} ${amount === 1 ? "coin" : "coins"}`;

export function WalletStore({ invoke }: { invoke: Invoke }) {
  const [wallet, setWallet] = useState<WalletView | null>(null);
  const [screen, setScreen] = useState<"closed" | "store" | "confirm" | "receipt">("closed");
  const [quote, setQuote] = useState<{ day: number; price: number; requestId: string }>();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const trigger = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLDivElement>(null);
  const version = useRef(0);

  async function load() {
    const next = ++version.current;
    try {
      const value: WalletView = await invoke("wallet_view");
      if (version.current === next) setWallet(value);
      return value;
    } catch {
      // An older desktop backend has no wallet command.
      return null;
    }
  }

  useEffect(() => {
    const refresh = () => void load();
    refresh();
    window.addEventListener("focus-wallet-updated", refresh);
    const timer = window.setInterval(refresh, 30_000);
    return () => {
      ++version.current;
      window.removeEventListener("focus-wallet-updated", refresh);
      window.clearInterval(timer);
    };
  }, [invoke]);

  useEffect(() => {
    dialog.current?.focus();
  }, [screen]);

  useEffect(() => {
    if (screen === "closed") return;
    const dismiss = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!busy && !dialog.current?.contains(target) && !trigger.current?.contains(target)) {
        setScreen("closed");
      }
    };
    const reset = () => setScreen("closed");
    document.addEventListener("pointerdown", dismiss);
    window.addEventListener("focus-store-dismiss", reset);
    return () => {
      document.removeEventListener("pointerdown", dismiss);
      window.removeEventListener("focus-store-dismiss", reset);
    };
  }, [screen, busy]);

  if (!wallet?.active) return null;
  const remaining = Math.max(0, wallet.time_off_limit - wallet.time_off_bought);
  const price = wallet.time_off_price_per_minute * minutes;

  function close() {
    if (busy) return;
    setScreen("closed");
    trigger.current?.focus();
  }

  async function buy() {
    if (!quote || busy) return;
    setBusy(true);
    setMessage("");
    try {
      const purchase: Purchase = await invoke("wallet_buy_time", {
        requestId: quote.requestId, minutes, expectedDay: quote.day,
      });
      if (purchase.status === "bought" || purchase.status === "already_bought") {
        setScreen((current) => current === "confirm" ? "receipt" : current);
      } else {
        setMessage(purchase.status === "not_enough"
          ? `${coins(purchase.missing)} needed to complete this purchase.`
          : "Today’s purchase limit has been reached.");
        setScreen((current) => current === "confirm" ? "store" : current);
      }
      await load();
    } catch {
      const latest = await load();
      if (latest && latest.day !== quote.day) {
        setMessage("A new day started. Check today’s allowance before buying.");
        setScreen((current) => current === "confirm" ? "store" : current);
      } else {
        setMessage("This purchase couldn’t be confirmed. Try confirming again.");
      }
    } finally {
      setBusy(false);
    }
  }

  return <>
    <Button ref={trigger} variant="text" size="xs" className="dev-trigger-btn"
      aria-label={`Store, ${coins(wallet.balance)}`} aria-expanded={screen !== "closed"} aria-haspopup="dialog"
      onClick={() => {
        if (busy) return;
        if (screen !== "closed") { close(); return; }
        setMessage("");
        setScreen("store");
        void load();
      }}>
      {coins(wallet.balance)}<ChevronDown data-icon="inline-end" />
    </Button>
    {screen !== "closed" && <div ref={dialog} tabIndex={-1} className="focus-update-popover focus-wallet-popover" role="dialog" aria-labelledby="focus-store-title"
      onKeyDown={(event) => {
        if (event.key !== "Escape") return;
        event.preventDefault();
        event.stopPropagation();
        close();
      }}>
      <div className="focus-update-popover-top">
        <b id="focus-store-title">{screen === "confirm" ? "Buy 15 minutes?" : screen === "receipt" ? "15 minutes added" : "Store"}</b>
        <button type="button" className="focus-update-popover-close" aria-label="Close Store" disabled={busy} onClick={close}><X className="size-3.5" /></button>
      </div>
      {screen === "store" && <>
        <div className="focus-update-popover-sub">{coins(wallet.balance)}</div>
        <div className="focus-wallet-item">
          <span><b>Time off</b><small>15 minutes for today</small></span>
          <Button size="xs" variant="outline" disabled={remaining < minutes || wallet.balance < price}
            onClick={() => {
              setQuote({ day: wallet.day, price, requestId: crypto.randomUUID() });
              setMessage("");
              setScreen("confirm");
            }}>{coins(price)}</Button>
        </div>
        <div className="focus-update-popover-sub">
          {remaining < minutes ? "Today’s purchase limit leaves fewer than 15 minutes."
            : wallet.balance < price ? `${coins(price - wallet.balance)} needed for 15 minutes off.`
              : `${remaining} extra minutes available to buy today.`}
        </div>
        {wallet.needs_upgrade.length > 0 && <small>Update paired devices to share purchases.</small>}
      </>}
      {screen === "confirm" && quote && <>
        <div className="focus-update-popover-sub">These minutes expire at midnight. Request a pause as usual.</div>
        <div className="focus-wallet-actions">
          <Button size="xs" variant="outline" disabled={busy} onClick={buy}>Confirm · {coins(quote.price)}</Button>
          <Button size="xs" variant="text" disabled={busy} onClick={() => setScreen("store")}>Cancel</Button>
        </div>
      </>}
      {screen === "receipt" && <>
        <div className="focus-update-popover-sub" role="status">{wallet.day === quote?.day
          ? "Your pause limit is higher for the rest of today."
          : "This purchase applied to the previous day."}</div>
        <div className="focus-wallet-actions"><Button size="xs" variant="outline" onClick={close}>Done</Button></div>
      </>}
      {message && <div className="focus-update-popover-sub" role="status">{message}</div>}
    </div>}
  </>;
}
