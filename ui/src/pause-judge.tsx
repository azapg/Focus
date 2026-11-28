import { useEffect, useRef, useState } from "react";
import { Input } from "@/components/ui/input";

type Settings = {
  enabled: boolean;
  has_gateway_key: boolean;
  has_nextdns_key: boolean;
};

type Decision = {
  id: string;
  decision: null | {
    action: "allow_services" | "allow_domains" | "full_pause" | "peek" | "counter_offer" | "deny";
    minutes: number;
    services: string[];
    domains?: string[];
    message: string;
    counter_offer: string | null;
  };
  refused?: boolean;
  failure: null | "configuration" | "timeout" | "unavailable" | "invalid";
  scope_available: boolean;
  friction: { wait_ms: number; hold_ms: number; requires_plan: boolean };
  manual_choices: number[];
};

type Hold = { hold_id: string; hold_ms: number };
type PostSnapshot = { site: string; title?: string; text?: string; image?: string; image_alt?: string };
type PeekReply = { snapshot?: PostSnapshot; refusal?: string };

const peekErrors: Record<string, string> = {
  permalink: "Use a direct link to a public post.",
  unsupported: "Peek supports public posts on X and Facebook.",
  protection_off: "Turn on Focus protection to Peek.",
};
export const serviceName = (service: string) => ({ discord: "Discord", x: "X", youtube: "YouTube", github: "GitHub", whatsapp_web: "WhatsApp Web" }[service] ?? service.replaceAll("_", " "));

const serviceImages = import.meta.glob<string>("../../android/res/drawable-nodpi/judge_*.png", {
  eager: true, import: "default", query: "?url",
});

export function serviceImage(service: string) {
  return serviceImages[`../../android/res/drawable-nodpi/judge_${service}.png`];
}

function ServiceIcons({ services }: { services: string[] }) {
  return <div className="judge-services">{services.map(service => {
    const name = serviceName(service);
    const image = serviceImage(service);
    return <span className="judge-logo" key={service} title={name} role="img" aria-label={name}>
      {image ? <img src={image} alt="" /> : name[0]?.toUpperCase()}
    </span>;
  })}</div>;
}


type ActiveHold = { id: string; token?: string; timer?: ReturnType<typeof setTimeout> };

function publishSettings(settings: Settings) {
  window.dispatchEvent(new CustomEvent("pause-judge-settings", { detail: settings }));
}

export function PauseJudgeSettings({ invoke }: { invoke: Invoke }) {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [gatewayKey, setGatewayKey] = useState("");
  const [nextDnsKey, setNextDnsKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  useEffect(() => {
    let mounted = true;
    invoke("pause_judge_settings").then((value: Settings) => {
      if (mounted) setSettings(value);
    }).catch((error) => { if (mounted) setMessage(String(error)); });
    return () => { mounted = false; };
  }, [invoke]);

  async function save(update: Record<string, string>) {
    setBusy(true);
    setMessage("");
    try {
      const value: Settings = await invoke("pause_judge_settings_save", { update });
      setSettings(value);
      setGatewayKey("");
      setNextDnsKey("");
      publishSettings(value);
    } catch (error) {
      setMessage(String(error));
    } finally {
      setBusy(false);
    }
  }

  return <section>
    <div className="t3-h">Pause assistant</div>
    <div className="t3-card">
        {settings && (
          <form className="t3-row-expand space-y-3" onSubmit={(event) => {
            event.preventDefault();
            void save({
              ...(gatewayKey.trim() ? { gateway_key: gatewayKey } : {}),
              ...(nextDnsKey.trim() ? { nextdns_key: nextDnsKey } : {}),
            });
          }}>
            <label className="block">Vercel Gateway API key
              <Input type="password" autoComplete="off" value={gatewayKey} onChange={(e) => setGatewayKey(e.target.value)}
                placeholder={settings.has_gateway_key ? "Saved · enter to replace" : "Enter key"} disabled={busy} />
            </label>
            <label className="block">NextDNS API key
              <Input type="password" autoComplete="off" value={nextDnsKey} onChange={(e) => setNextDnsKey(e.target.value)}
                placeholder={settings.has_nextdns_key ? "Saved · enter to replace" : "Enter key"} disabled={busy} />
            </label>
            <div className="t3-btn-group">
              <button type="submit" className="t3-btn" disabled={busy}>Save settings</button>
              {settings.has_gateway_key && <button type="button" className="t3-btn" disabled={busy}
                onClick={() => save({ gateway_key: "" })}>Remove Gateway key</button>}
              {settings.has_nextdns_key && <button type="button" className="t3-btn" disabled={busy}
                onClick={() => save({ nextdns_key: "" })}>Remove NextDNS key</button>}
            </div>
          </form>
        )}
      {message && <p className="t3-row-expand" role="status">{message}</p>}
    </div>
  </section>;
}

export function PauseJudgeSheet({ invoke, dismiss }: { invoke: Invoke; dismiss: () => void }) {
  const [request, setRequest] = useState("");
  const [result, setResult] = useState<Decision | null>(null);
  const [plan, setPlan] = useState("");
  const [waitUntil, setWaitUntil] = useState(0);
  const [clock, setClock] = useState(0);
  const [busy, setBusy] = useState(false);
  const [holding, setHolding] = useState(false);
  const [message, setMessage] = useState("");
  const [post, setPost] = useState<PostSnapshot | null>(null);

  const activeHold = useRef<ActiveHold | null>(null);
  const field = useRef<HTMLTextAreaElement>(null);
  const start = useRef<HTMLButtonElement>(null);
  const postPanel = useRef<HTMLElement>(null);

  useEffect(() => {
    if (post) postPanel.current?.focus({ preventScroll: true });
  }, [post]);

  function cancelHold() {
    const pending = activeHold.current;
    activeHold.current = null;
    if (!pending) return;
    clearTimeout(pending.timer);
    if (pending.token) {
      void invoke("cancel_pause_hold", { id: pending.id, holdId: pending.token }).catch(() => {});
    }
  }

  useEffect(() => {
    const abort = () => { cancelHold(); setHolding(false); };
    window.addEventListener("blur", abort);
    return () => { window.removeEventListener("blur", abort); cancelHold(); };
  }, [invoke]);

  useEffect(() => {
    if (clock >= waitUntil) return;
    const timer = setTimeout(() => setClock(performance.now()), 250);
    return () => clearTimeout(timer);
  }, [clock, waitUntil]);

  async function ask() {
    const text = request.trim();
    if (text.length < 3 || busy || holding) return;
    const linkOnly = /^https?:\/\/\S+$/i.test(text);
    setBusy(true);
    setMessage("");
    setResult(null);
    setPlan("");
    try {
      // The native worker validates the permalink. A refusal stays local.
      if (linkOnly) {
        if (text.length > 4096) {
          setMessage("That link is too long.");
          return;
        }
        const reply: PeekReply = await invoke("peek_link", { url: text });
        if (reply.snapshot) {
          setRequest("");
          setPost(reply.snapshot);
        } else {
          setMessage(peekErrors[reply.refusal ?? ""] ?? "Snapshot unavailable.");
        }
        return;
      }
      if ([...text].length > 2000) {
        setMessage("Keep your request within 2,000 characters.");
        return;
      }
      await invoke("pause_judge_limits_sync", { limits: FocusLimits.get() });
      const decision: Decision = await invoke("judge_pause", {
        request: { text },
      });
      const now = performance.now();
      setClock(now);
      setWaitUntil(now + decision.friction.wait_ms);
      setResult(decision);
      requestAnimationFrame(() => (decision.decision ? start : field).current?.focus());
    } catch (error) {
      setMessage(linkOnly ? "Snapshot unavailable." : String(error));
    } finally {
      setBusy(false);
    }
  }

  async function apply(confirmation?: Hold) {
    if (!result) return;
    setBusy(true);
    setMessage("");
    try {
      const applied = await invoke("apply_decision", {
        input: {
          id: result.id,
          override_full: false,
          manual_minutes: null,
          after_plan: plan || null,
          hold_id: confirmation?.hold_id,
        },
      });
      if (applied.action === "peek" && applied.url) {
        const reply: PeekReply = await invoke("peek_link", { url: applied.url });
        if (reply.snapshot) {
          setRequest("");
          setPost(reply.snapshot);
        } else {
          setMessage(peekErrors[reply.refusal ?? ""] ?? "Snapshot unavailable.");
        }
        return;
      }
      if (applied.action === "allow_services" || applied.action === "full_pause") dismiss();
    } catch (error) {
      setMessage(String(error));
    } finally {
      setBusy(false);
    }
  }

  async function beginHold() {
    if (!result || activeHold.current) return;
    const pending: ActiveHold = { id: result.id };
    activeHold.current = pending;
    setHolding(true);
    setMessage("");
    try {
      const confirmation: Hold = await invoke("begin_pause_hold", {
        id: result.id,
        overrideFull: false,
      });
      if (activeHold.current !== pending) {
        await invoke("cancel_pause_hold", { id: pending.id, holdId: confirmation.hold_id });
        return;
      }
      pending.token = confirmation.hold_id;
      pending.timer = setTimeout(() => {
        activeHold.current = null;
        setHolding(false);
        void apply(confirmation);
      }, confirmation.hold_ms);
    } catch (error) {
      if (activeHold.current === pending) {
        activeHold.current = null;
        setHolding(false);
        setMessage(String(error));
      }
    }
  }

  function release() {
    cancelHold();
    setHolding(false);
  }

  const decision = result?.decision;
  const retry = Boolean(result && !decision);
  const needsHold = decision?.action === "full_pause";
  const remaining = Math.max(0, Math.ceil((waitUntil - clock) / 1000));
  const fallbackMessages = {
    configuration: "Check your Gateway settings.",
    timeout: "No reply. Try again.",
    unavailable: "Gateway unavailable. Try again.",
    invalid: "No usable reply. Try again.",
  };

  const scoped = (decision?.action === "allow_services" || decision?.action === "allow_domains") && result?.scope_available;
  const scopedDomains = decision?.domains ?? [];

  function edit() {
    setMessage("");
    setResult(null);
    requestAnimationFrame(() => field.current?.focus());
  }

  if (post) {
    const image = post.image && /^data:image\/(png|jpeg);base64,[A-Za-z0-9+/=]+$/.test(post.image)
      ? post.image : undefined;
    const imageOnly = image && !post.title && !post.text;
    return (
      <article className={imageOnly ? "dock-peek image-only" : "dock-peek"} ref={postPanel}
        aria-label={`${post.site} post snapshot`} tabIndex={0}>
        {!imageOnly && <small>{post.site}</small>}
        {post.title && <h2>{post.title}</h2>}
        {post.text && <p>{post.text}</p>}
        {image && <img src={image} alt={post.image_alt ?? ""} />}
      </article>
    );
  }

  return (
    <section className="judge-sheet" aria-label="Pause request"
      onKeyDown={(event) => { if (event.key === "Escape") { event.stopPropagation(); dismiss(); } }}>
      <div className="judge-capsule">
        {(!result || retry) && <form className="judge-request" onSubmit={(event) => { event.preventDefault(); void ask(); }}>
          <textarea ref={field} aria-label="What do you need?" placeholder="What do you need?" rows={1}
            value={request} maxLength={4097} readOnly={busy}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
                event.preventDefault();
                void ask();
              }
            }} onChange={(event) => {
              setRequest(event.target.value);
              if (retry) setResult(null);
            }} />
          <button type="submit" className="btn primary" disabled={busy || request.trim().length < 3}>{busy ? "Checking…" : "Ask"}</button>
        </form>}
        {scoped && <div className="judge-review" aria-live="polite">
          {decision.action === "allow_domains"
            ? <span className="judge-time">{scopedDomains.join(", ")} · {decision.minutes} min</span>
            : <><ServiceIcons services={decision.services} /><span className="judge-time">{decision.minutes} min</span></>}
          <button type="button" className="btn judge-edit" onClick={edit} disabled={busy}>Edit</button>
          <button type="button" ref={start} className="btn primary" onClick={() => void apply()} disabled={busy}>{busy ? "Opening…" : "Start"}</button>
        </div>}
        {result && !scoped && !retry && <div className="judge-review">
          <span className="judge-time">{needsHold ? "Full pause" : "Ask"}</span>
          <button type="button" className="btn judge-edit" onClick={edit} disabled={busy || holding}>Edit</button>
        </div>}
      </div>
      {((result && !scoped) || message) && (
        <div className="judge-answer" aria-live="polite">
        {result && (
          <>
            {retry && result.failure && <p className={`judge-attention${result.refused ? " bad" : ""}`}>{result.refused
              ? "Assistant declined this request." : fallbackMessages[result.failure]}</p>}
            {decision?.action === "deny" && <p className="judge-attention bad">{decision.message}</p>}
            {(decision?.action === "allow_services" || decision?.action === "allow_domains") && !result.scope_available && <p>Add the NextDNS key in Settings to open this site.</p>}
            {decision?.action === "full_pause" && <p>Full pause · {decision.minutes} min</p>}
            {decision?.counter_offer && <p>{decision.counter_offer}</p>}
            {decision?.action === "peek" && <button type="button" className="t3-btn primary" disabled={busy} onClick={() => void apply()}>Open Peek</button>}

            {needsHold && result.friction.requires_plan && <label className="block">What will you do after the pause?
              <Input value={plan} maxLength={300} disabled={busy || holding} onChange={(e) => setPlan(e.target.value)} />
            </label>}
            {needsHold && <button
              type="button"
              className={`t3-btn primary judge-hold${holding ? " holding" : ""}`}
              style={{ "--hold-ms": `${result.friction.hold_ms}ms` } as React.CSSProperties}
              disabled={busy || remaining > 0 || (result.friction.requires_plan && plan.trim().length < 3)}
              onPointerDown={(e) => {
                if (e.button !== 0 || e.target !== e.currentTarget) return;
                e.currentTarget.setPointerCapture(e.pointerId);
                void beginHold();
              }}
              onPointerUp={release} onPointerCancel={release} onLostPointerCapture={release}
              onKeyDown={(e) => {
                if (e.key === " " || e.key === "Enter") {
                  e.preventDefault();
                  if (!e.repeat) void beginHold();
                }
              }}
              onKeyUp={(e) => { if (e.key === " " || e.key === "Enter") release(); }}
              onBlur={release}
            ><span>{remaining > 0 ? `Wait ${remaining}s` : holding ? "Keep holding…" : `Hold ${result.friction.hold_ms / 1000}s for ${decision?.minutes} min`}</span></button>}
          </>
        )}
        {message && <p role="status">{message}</p>}
        </div>
      )}
    </section>
  );
}
