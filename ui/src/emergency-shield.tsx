import { useEffect, useState } from "react";
import { Input } from "@/components/ui/input";

type Status = {
  phrase: string;
  active_until: number;
  offline_cooldown_until: number;
  online_remaining_today: number;
};

type BlockedQuery = {
  domain: string;
  reasons: string[];
  timestamp_ms: number;
  is_security_threat: boolean;
};

export function EmergencyShieldSettings({ invoke }: { invoke: Invoke }) {
  const [status, setStatus] = useState<Status | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [typedPhrase, setTypedPhrase] = useState("");
  const [domain, setDomain] = useState("");
  const [reason, setReason] = useState("");
  const [blocks, setBlocks] = useState<BlockedQuery[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  async function load() {
    try {
      const next: Status = await invoke("emergency_status");
      setStatus(next);
    } catch (error) {
      setMessage(String(error));
    }
  }

  useEffect(() => {
    void load();
  }, [invoke]);

  async function fetchRecentBlocks() {
    try {
      const list: BlockedQuery[] = await invoke("emergency_recent_blocks");
      setBlocks(Array.isArray(list) ? list : []);
    } catch {
      setBlocks([]);
    }
  }

  async function activate(offline: boolean) {
    setBusy(true);
    setMessage("");
    try {
      const res: { mode: string; domain?: string } = await invoke("emergency_activate", {
        phrase: typedPhrase,
        domain: offline ? null : domain.trim() || null,
        reason,
      });
      setTypedPhrase("");
      setDomain("");
      setReason("");
      setMessage(
        res.mode === "scoped_domain"
          ? `${res.domain} opened for 10 min. Feeds stay blocked.`
          : "Offline shield active for 10 min. Feeds stay blocked locally.",
      );
      await load();
    } catch (error) {
      setMessage(String(error));
    } finally {
      setBusy(false);
    }
  }

  const active = Boolean(status && status.active_until > Date.now());
  const offlineLocked = Boolean(status && status.offline_cooldown_until > Date.now());
  const phraseMatches = Boolean(
    status &&
      typedPhrase.trim().replace(/\s+/g, " ").toLowerCase() ===
        status.phrase.trim().replace(/\s+/g, " ").toLowerCase(),
  );

  return (
    <section>
      <div className="t3-h">Emergency shield</div>
      <div className="t3-card">
        <div className="t3-row">
          <div className="t3-row-text">
            <div className="t3-row-title">Emergency shield</div>
            <div className="t3-row-desc">
              {active
                ? "Active · feeds stay blocked"
                : "Unblock a broken work site for 10 min while feeds stay blocked."}
            </div>
          </div>
          <button
            type="button"
            className="t3-btn"
            onClick={() => {
              const next = !expanded;
              setExpanded(next);
              setMessage("");
              if (next) {
                void load();
                void fetchRecentBlocks();
              }
            }}
          >
            {expanded ? "Hide" : "Open"}
          </button>
        </div>

        {expanded && status && (
          <div className="t3-row-expand space-y-3">
            {blocks.length > 0 && (
              <div className="space-y-1">
                <div className="t3-row-desc">Recently blocked (non-entertainment)</div>
                <div className="t3-btn-group" style={{ flexWrap: "wrap" }}>
                  {blocks.map((b) => (
                    <button
                      key={b.domain}
                      type="button"
                      className={`t3-btn${domain === b.domain ? " primary" : ""}`}
                      onClick={() => setDomain(b.domain)}
                    >
                      {b.domain}
                    </button>
                  ))}
                </div>
              </div>
            )}
            <label className="block">
              Broken domain (leave blank for offline shield)
              <Input
                value={domain}
                placeholder="portal.example.com"
                onChange={(e) => setDomain(e.target.value)}
                disabled={busy}
              />
            </label>
            <label className="block">
              What is broken?
              <Input
                value={reason}
                placeholder="Client portal blocked on live call"
                onChange={(e) => setReason(e.target.value)}
                disabled={busy}
              />
            </label>
            <div className="space-y-1">
              <div className="t3-row-desc">Type this phrase to confirm (paste disabled)</div>
              <div
                style={{
                  userSelect: "none",
                  WebkitUserSelect: "none",
                  padding: "8px 10px",
                  borderRadius: "8px",
                  border: "1px solid var(--border)",
                  fontSize: "13px",
                  fontWeight: 500,
                }}
                onCopy={(e) => e.preventDefault()}
              >
                {status.phrase}
              </div>
              <Input
                autoComplete="off"
                value={typedPhrase}
                placeholder="Type the phrase above"
                onChange={(e) => setTypedPhrase(e.target.value)}
                onPaste={(e) => e.preventDefault()}
                onDrop={(e) => e.preventDefault()}
                disabled={busy}
              />
            </div>
            <div className="t3-btn-group">
              <button
                type="button"
                className="t3-btn primary"
                disabled={
                  busy ||
                  !phraseMatches ||
                  reason.trim().length < 5 ||
                  !domain.trim() ||
                  status.online_remaining_today === 0
                }
                onClick={() => void activate(false)}
              >
                Open domain · 10 min ({status.online_remaining_today} left)
              </button>
              <button
                type="button"
                className="t3-btn"
                disabled={busy || !phraseMatches || reason.trim().length < 5 || offlineLocked}
                onClick={() => void activate(true)}
              >
                {offlineLocked ? "Offline shield on cooldown" : "10 min offline shield"}
              </button>
            </div>
            {message && <div className="t3-row-desc">{message}</div>}
          </div>
        )}
      </div>
    </section>
  );
}
