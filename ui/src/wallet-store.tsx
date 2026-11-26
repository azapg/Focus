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
type Quote = { kind: "time"; minutes: number; target: string; targetLabel: string; day: number; price: number; requestId: string }
  | { kind: "freeze"; price: number; requestId: string };

const PACKS = [5, 10] as const;
const coins = (amount: number) => `${amount} ${amount === 1 ? "coin" : "coins"}`;

export function WalletStore({ invoke, devices = [] }: { invoke: Invoke; devices?: Array<{ id: string; name: string; kind?: string }> }) {
  const [wallet, setWallet] = useState<WalletView | null>(null);
  const [freeze, setFreeze] = useState<FreezeView | null>(null);
  const [screen, setScreen] = useState<"closed" | "store" | "confirm" | "receipt">("closed");
  const [target, setTarget] = useState<string>("laptop");
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
  const targets = [{ id: "laptop", label: "Laptop" }, ...devices.map((d) => ({ id: d.id, label: d.name }))];
  const selectedTarget = targets.find((t) => t.id === target) ?? targets[0];

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
        requestId: quote.requestId,
        minutes: quote.minutes,
        expectedDay: quote.day,
        targetDevice: quote.target,
      });
      if (purchase.status === "bought" || purchase.status === "already_bought") {
        setScreen((current) => current === "confirm" ? "receipt" : current);
      } else {
        setMessage(purchase.status === "not_enough"
          ? `${coins(purchase.missing)} needed.`
          : "Today’s purchase limit has been reached.");
        setScreen((current) => current === "confirm" ? "store" : current);
      }
      await load();
    } catch {
      const latest = await load();
      if (quote.kind === "time" && latest && latest.day !== quote.day) {
        setMessage("A new day started. Check today’s limit before buying.");
        setScreen((current) => current === "confirm" ? "store" : current);
      } else {
        setMessage("This purchase couldn’t be confirmed. Try again.");
      }
    } finally {
      setBusy(false);
    }
  }

  const isFreeze = quote?.kind === "freeze";
  const title = screen === "confirm"
    ? (isFreeze ? "Buy a Streak Freeze?" : `Are you sure?`)
    : screen === "receipt"
      ? (isFreeze ? "Freeze purchased" : `${quote?.kind === "time" ? quote.minutes : 5} min free started`)
      : "Store";

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
        {targets.length > 1 && <div className="t3-btn-group" style={{ marginBottom: 8 }}>
          {targets.map((t) => (
            <button key={t.id} type="button" className={`t3-btn${selectedTarget.id === t.id ? " primary" : ""}`}
              onClick={() => setTarget(t.id)}>{t.label}</button>
          ))}
        </div>}
        {PACKS.map((mins) => {
          const price = wallet.time_off_price_per_minute * mins;
          return <div className="focus-wallet-item" key={mins}>
            <span><b>{mins} min free</b><small>Starts now on {selectedTarget.label}</small></span>
            <Button size="xs" variant="outline" disabled={remaining < mins || wallet.balance < price}
              onClick={() => confirm({ kind: "time", minutes: mins, target: selectedTarget.id, targetLabel: selectedTarget.label, day: wallet.day, price, requestId: crypto.randomUUID() })}>{coins(price)}</Button>
          </div>;
        })}
        {freeze && <div className="focus-wallet-item">
          <span><b>Streak Freeze</b><small>{freeze.freezeReady ? "One freeze held"
            : freeze.balanceCoins < freeze.priceCoins ? `${coins(freeze.priceCoins - freeze.balanceCoins)} needed`
              : freeze.canBuy ? "Protect one future Drift day" : "Unavailable right now"}</small></span>
          {!freeze.freezeReady && <Button size="xs" variant="outline" aria-label={`Streak Freeze, ${coins(freeze.priceCoins)}`}
            disabled={!freeze.canBuy}
            onClick={() => confirm({ kind: "freeze", price: freeze.priceCoins, requestId: crypto.randomUUID() })}>{coins(freeze.priceCoins)}</Button>}
        </div>}
        <div className="focus-update-popover-sub">
          {remaining < PACKS[0] ? "Today’s free-time limit has been reached."
            : `${remaining}m left to buy today.`}
        </div>
        {wallet.needs_upgrade.length > 0 && <small>Update paired devices to share purchases.</small>}
      </>}
      {screen === "confirm" && quote && <>
        <div className="focus-update-popover-sub">{isFreeze
          ? "Starting tomorrow, one confirmed Drift day uses this freeze automatically."
          : `Start ${quote.minutes} min free on ${quote.targetLabel} now for ${coins(quote.price)}?`}</div>
        {isFreeze && <div className="focus-update-popover-sub">Hold one freeze at a time. A clean day must follow before another applies.</div>}
        <div className="focus-wallet-actions">
          <Button size="xs" variant="outline" disabled={busy} onClick={buy}>Confirm · {coins(quote.price)}</Button>
          <Button size="xs" variant="text" disabled={busy} onClick={() => setScreen("store")}>Cancel</Button>
        </div>
      </>}
      {screen === "receipt" && <>
        <div className="focus-update-popover-sub" role="status">{isFreeze
          ? "Freeze ready for a future Drift day."
          : quote?.kind === "time"
            ? `${quote.minutes} min free started on ${quote.targetLabel}. No drift penalty.`
            : "Done."}</div>
        <div className="focus-wallet-actions"><Button size="xs" variant="outline" onClick={close}>Done</Button></div>
      </>}
      {message && <div className="focus-update-popover-sub" role="status">{message}</div>}
    </div>}
  </>;
}
