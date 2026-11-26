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
type FreezeView = { freezeReady: boolean; canBuy: boolean; priceCoins: number; balanceCoins: number };
type FreezePurchase = { status: "bought" | "already_bought" | "slot_full" | "insufficient_coins" | "unavailable" };
type Quote = { kind: "time"; day: number; price: number; requestId: string }
  | { kind: "freeze"; price: number; requestId: string };

const minutes = 15;
const coins = (amount: number) => `${amount} ${amount === 1 ? "coin" : "coins"}`;

export function WalletStore({ invoke }: { invoke: Invoke }) {
  const [wallet, setWallet] = useState<WalletView | null>(null);
  const [freeze, setFreeze] = useState<FreezeView | null>(null);
  const [screen, setScreen] = useState<"closed" | "store" | "confirm" | "receipt">("closed");
  const [quote, setQuote] = useState<Quote>();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const trigger = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLDivElement>(null);
  const version = useRef(0);

  async function load() {
    const next = ++version.current;
    try {
      const [value, streak]: [WalletView, FreezeView | null] = await Promise.all([
        invoke("wallet_view"),
        invoke("streak_view").catch(() => null),
      ]);
      if (version.current === next) {
        setWallet(value);
        setFreeze(streak);
      }
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

  function confirm(next: Quote) {
    setQuote(next);
    setMessage("");
    setScreen("confirm");
  }

  async function buy() {
    if (!quote || busy) return;
    setBusy(true);
    setMessage("");
    try {
      if (quote.kind === "freeze") {
        const result: FreezePurchase = await invoke("streak_buy_freeze", { requestId: quote.requestId });
        if (result.status === "bought" || result.status === "already_bought") {
          setScreen((current) => current === "confirm" ? "receipt" : current);
        } else {
          setMessage(result.status === "slot_full" ? "You already have a freeze."
            : result.status === "insufficient_coins" ? "More coins are needed for a Streak Freeze."
              : "Streak Freeze is unavailable right now.");
          setScreen((current) => current === "confirm" ? "store" : current);
        }
        await load();
        return;
      }
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
      if (quote.kind === "time" && latest && latest.day !== quote.day) {
        setMessage("A new day started. Check today’s allowance before buying.");
        setScreen((current) => current === "confirm" ? "store" : current);
      } else {
        setMessage("This purchase couldn’t be confirmed. Try confirming again.");
      }
    } finally {
      setBusy(false);
    }
  }

  const isFreeze = quote?.kind === "freeze";
  const title = screen === "confirm" ? (isFreeze ? "Buy a Streak Freeze?" : "Buy 15 minutes?")
    : screen === "receipt" ? (isFreeze ? "Freeze purchased" : "15 minutes added") : "Store";

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
        <b id="focus-store-title">{title}</b>
        <button type="button" className="focus-update-popover-close" aria-label="Close Store" disabled={busy} onClick={close}><X className="size-3.5" /></button>
      </div>
      {screen === "store" && <>
        <div className="focus-update-popover-sub">{coins(wallet.balance)}</div>
        <div className="focus-wallet-item">
          <span><b>Time off</b><small>15 minutes for today</small></span>
          <Button size="xs" variant="outline" disabled={remaining < minutes || wallet.balance < price}
            onClick={() => confirm({ kind: "time", day: wallet.day, price, requestId: crypto.randomUUID() })}>{coins(price)}</Button>
        </div>
        {freeze && <div className="focus-wallet-item">
          <span><b>Streak Freeze</b><small>{freeze.freezeReady ? "One freeze held"
            : freeze.balanceCoins < freeze.priceCoins ? `${coins(freeze.priceCoins - freeze.balanceCoins)} needed`
              : freeze.canBuy ? "Protect one future Drift day" : "Unavailable right now"}</small></span>
          {!freeze.freezeReady && <Button size="xs" variant="outline" aria-label={`Streak Freeze, ${coins(freeze.priceCoins)}`}
            disabled={!freeze.canBuy}
            onClick={() => confirm({ kind: "freeze", price: freeze.priceCoins, requestId: crypto.randomUUID() })}>{coins(freeze.priceCoins)}</Button>}
        </div>}
        <div className="focus-update-popover-sub">
          {remaining < minutes ? "Today’s purchase limit leaves fewer than 15 minutes."
            : wallet.balance < price ? `${coins(price - wallet.balance)} needed for 15 minutes off.`
              : `${remaining} extra minutes available to buy today.`}
        </div>
        {wallet.needs_upgrade.length > 0 && <small>Update paired devices to share purchases.</small>}
      </>}
      {screen === "confirm" && quote && <>
        <div className="focus-update-popover-sub">{isFreeze
          ? "Starting the day after purchase, a confirmed Drift day can use this freeze automatically and preserve your streak."
          : "These minutes expire at midnight. Request a pause as usual."}</div>
        {isFreeze && <div className="focus-update-popover-sub">You can hold one freeze. A clean day must follow before another freeze can apply.</div>}
        <div className="focus-wallet-actions">
          <Button size="xs" variant="outline" disabled={busy} onClick={buy}>Confirm · {coins(quote.price)}</Button>
          <Button size="xs" variant="text" disabled={busy} onClick={() => setScreen("store")}>Cancel</Button>
        </div>
      </>}
      {screen === "receipt" && <>
        <div className="focus-update-popover-sub" role="status">{isFreeze
          ? "A freeze protects one eligible Drift day starting the day after purchase."
          : wallet.day === quote?.day
          ? "Your pause limit is higher for the rest of today."
          : "This purchase applied to the previous day."}</div>
        <div className="focus-wallet-actions"><Button size="xs" variant="outline" onClick={close}>Done</Button></div>
      </>}
      {message && <div className="focus-update-popover-sub" role="status">{message}</div>}
    </div>}
  </>;
}
