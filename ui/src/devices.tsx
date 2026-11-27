import React, {
  memo,
  useEffect,
  useId,
  useRef,
  useState,
  useSyncExternalStore,
} from "react";
import { createRoot } from "react-dom/client";
import { flushSync } from "react-dom";
import {
  ArrowUpCircle,
  ChevronDown,
  CornerDownLeft,
  Laptop,
  LockKeyhole,
  RotateCw,
  Search,
  Settings,
  ShieldOff,
  Smartphone,
  TvMinimal,
  AlertCircle,
  LoaderCircle,
  X,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuCheckboxItem,
  DropdownMenuSeparator,
} from "@/components/ui/dropdown-menu";
import {
  Field,
  FieldGroup,
  FieldLabel,
  FieldSet,
  FieldLegend,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { WalletStore } from "./wallet-store";
import { EmergencyShieldSettings } from "./emergency-shield";
import { PauseJudgeSettings, PauseJudgeSheet, serviceImage, serviceName } from "./pause-judge";
import {
  Select,
  SelectTrigger,
  SelectValue,
  SelectContent,
  SelectGroup,
  SelectItem,
} from "@/components/ui/select";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { Alert, AlertDescription } from "@/components/ui/alert";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";

let call: Invoke,
  changed: (range?: string) => void = () => {},
  resized: () => void = () => {};
let ready = Promise.resolve(),
  renderVersion = 0;
let saved: string[] = [];
try {
  const v = JSON.parse(localStorage.getItem("focus-excluded-devices") || "[]");
  if (Array.isArray(v)) saved = v.filter((x) => typeof x === "string");
} catch {}

let state = {
  devices: [] as Device[],
  excluded: saved,
  settings: false,
  settingsTab: "protection", // protection | categories | laptop | device:<id> | connect | updates | about
  settingsId: "laptop",
  settingsSearch: "",
  range: "today",
  menu: false,
  busy: false,
  error: "",
  registryError: "",
  forget: false,
  update: null as UpdateState | null,
  updateBusy: false,
  updateError: "",
  updatePopover: false,
  incomingPairing: null as IncomingApproval | null,
  localPausedUntil: 0,
  judgeEnabled: false,
};
const listeners = new Set<() => void>();
function patch(next: Partial<typeof state>) {
  state = { ...state, ...next };
  listeners.forEach((fn) => fn());
}
const subscribe = (fn: () => void) => {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
};
const view = () => state;
const all = () => [{ id: "laptop", name: "Laptop" }, ...state.devices];
const deviceKind = (d?: { kind?: string; snapshot?: { kind?: string } | null }) =>
  d?.snapshot?.kind || d?.kind || "tv";
const isPhone = (d?: { kind?: string; snapshot?: { kind?: string } | null }) =>
  deviceKind(d) === "phone";
const dnsPauseChoices = (d?: Device) =>
  isPhone(d) ? [1, 5, 10, 30] : d?.snapshot?.dns?.pause_choices ?? [30, 60, 120, 300];
const fresh = (d?: Device) =>
  Boolean(d?.online && Date.now() - d.last_seen < 75000);
const date = (t: number) =>
  new Date(t).toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
const clock = (n: number) =>
  `${String(Math.floor(n / 60)).padStart(2, "0")}:${String(n % 60).padStart(2, "0")}`;
const minutes = (s: string) => {
  const [h, m] = s.split(":").map(Number);
  return h * 60 + m;
};
function receive(devices: Device[]) {
  if (!Array.isArray(devices)) return;
  patch({ devices });
  changed();
}
function receiveUpdate(update: UpdateState | null) {
  if (!update || typeof update !== "object") return;
  patch({ update, updateError: update.error || "" });
  resized();
}
function receiveIncomingPairing(incoming: IncomingApproval | null) {
  patch({ incomingPairing: incoming && incoming.request_id ? incoming : null });
  resized();
}
async function checkUpdates() {
  if (state.updateBusy) return;
  patch({ updateBusy: true, updateError: "" });
  try {
    const next = await call("updater_check");
    if (next) receiveUpdate(next);
  } catch (e) {
    patch({ updateError: String(e) });
  } finally {
    patch({ updateBusy: false });
    resized();
  }
}
async function installUpdate() {
  if (state.updateBusy) return;
  patch({ updateBusy: true, updateError: "" });
  try {
    const next = await call("updater_install");
    if (next) receiveUpdate(next);
  } catch (e) {
    patch({ updateError: String(e) });
  } finally {
    patch({ updateBusy: false });
    resized();
  }
}
async function saveUpdateToken(token: string | null) {
  if (state.updateBusy) return false;
  patch({ updateBusy: true, updateError: "" });
  try {
    const next = await call("updater_set_token", { token });
    if (next) receiveUpdate(next);
    if (token) {
      const checked = await call("updater_check");
      if (checked) receiveUpdate(checked);
    }
    return true;
  } catch (e) {
    patch({ updateError: String(e) });
    return false;
  } finally {
    patch({ updateBusy: false });
    resized();
  }
}
async function toggleAutoUpdates(enabled: boolean) {
  try {
    const next = await call("updater_set_enabled", { enabled });
    if (next) receiveUpdate(next);
  } catch (e) {
    patch({ updateError: String(e) });
  }
}
async function load() {
  try {
    receive(await call("device_list"));
    patch({ registryError: "" });
  } catch (e) {
    patch({ registryError: String(e) });
  }
}
async function refresh() {
  if (state.busy) return;
  patch({ busy: true, error: "" });
  try {
    const devTab = state.settingsTab.startsWith("device:")
      ? state.settingsTab.slice(7)
      : null;
    receive(
      await call("device_refresh", {
        id: state.settings && devTab ? devTab : null,
      }),
    );
  } catch (e) {
    patch({ error: String(e) });
  } finally {
    patch({ busy: false });
    resized();
  }
}
async function command(command: Record<string, unknown>, local = false, deviceId?: string) {
  if (state.busy) return false;
  patch({ busy: true, error: "" });
  try {
    if (local)
      await call(
        String(command.action),
        (command.args as Record<string, unknown>) || {},
      );
    else
      receive(
        await call("device_command", {
          id: deviceId || state.settingsId,
          command,
        }),
      );
    return true;
  } catch (e) {
    patch({
      error: `${String(e)} Refresh before retrying if the device did not confirm the change.`,
    });
    await load();
    return false;
  } finally {
    patch({ busy: false });
    changed();
    resized();
  }
}
function settings(open: boolean, tab?: string) {
  ++renderVersion;
  const nextTab = tab || state.settingsTab || "protection";
  const nextSettingsId = nextTab.startsWith("device:")
    ? nextTab.slice(7)
    : "laptop";
  patch({
    settings: open,
    settingsTab: nextTab,
    settingsId: nextSettingsId,
    settingsSearch: "",
    menu: false,
    updatePopover: false,
    error: "",
    forget: false,
  });
  if (!open) changed();
  requestAnimationFrame(() => {
    document.getElementById(open ? "backToStats" : "deviceSettings")?.focus();
    resized();
  });
}
function selectSettingsTab(tab: string) {
  const nextSettingsId = tab.startsWith("device:") ? tab.slice(7) : "laptop";
  patch({
    settingsTab: tab,
    settingsId: nextSettingsId,
    settingsSearch: "",
    error: "",
    forget: false,
  });
  requestAnimationFrame(resized);
}
function toggle(id: string) {
  const next = new Set(state.excluded);
  if (id === "all") next.clear();
  else if (next.has(id)) next.delete(id);
  else next.add(id);
  patch({ excluded: [...next] });
  try {
    localStorage.setItem("focus-excluded-devices", JSON.stringify([...next]));
  } catch {}
  changed();
}

function IconButton({
  label,
  children,
  onClick,
  id,
  disabled = false,
}: {
  label: string;
  children: React.ReactNode;
  onClick: () => void;
  id?: string;
  disabled?: boolean;
}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          id={id}
          size="icon-sm"
          variant="ghost"
          aria-label={label}
          disabled={disabled}
          onClick={onClick}
        >
          {children}
        </Button>
      </TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  );
}
function SmallButton(props: React.ComponentProps<typeof Button>) {
  return <Button size="xs" variant="outline" type="button" {...props} />;
}
function OledSwitch({
  id,
  checked,
  disabled,
  label,
  onChange,
}: {
  id?: string;
  checked: boolean;
  disabled?: boolean;
  label: string;
  onChange: (next: boolean) => void;
}) {
  return (
    <button
      id={id}
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      className={`t3-toggle${checked ? " on" : ""}`}
      onClick={() => onChange(!checked)}
    >
      <i />
    </button>
  );
}

const Statistics = memo(() => (
  <>
    <div id="statsMain" className="stats-body">
      <div className="p-stats" id="pStats" />
      <div className="p-sec-wk" id="pTimelineSec" hidden>
        <div id="pChart" />
        <div className="legend" id="pLegend" hidden />
      </div>
      <div id="habitSection" />
      <div id="pIntentionsSec" className="p-sec-intentions" hidden />
      <div className="bento-grid">
        <div className="bento-card" id="pAppsCard">
          <div className="bento-head" id="pAppsLbl">
            <span className="bento-title">Screen time</span>
          </div>
          <div id="pApps" />
          <div className="card-foot" id="pAppsFoot" hidden />
        </div>
        <div className="bento-card" id="pPausesCard">
          <div className="bento-head" id="pPausesLbl">
            <span className="bento-title">Pauses</span>
          </div>
          <div id="pPauses" />
          <div className="card-foot" id="pPausesFoot" hidden />
        </div>
      </div>
    </div>
    <div id="explorerView" hidden />
  </>
));

function Notice() {
  const s = useSyncExternalStore(subscribe, view);
  const messages = s.devices
    .filter((d) => !s.excluded.includes(d.id))
    .flatMap((d) =>
      [
        !fresh(d)
          ? d.snapshot
            ? `${d.name} offline · saved ${date(d.last_seen)}`
            : `${d.name} · no usage snapshot available`
          : "",
        d.snapshot && !d.snapshot.permissions.usage
          ? `${d.name} · Usage Access is off`
          : fresh(d) && d.snapshot?.tracking_age_ms! > 30000
            ? `${d.name} · tracking needs attention`
            : "",
      ].filter(Boolean),
    );
  if (s.registryError) messages.push(s.registryError);
  return (
    <div
      id="deviceNotice"
      className="focus-notice"
      role="status"
      hidden={!messages.length}
    >
      <span id="deviceNoticeText" className="flex-1">
        {messages.join(" · ")}
      </span>
      <IconButton
        label="Refresh devices"
        id="refreshDevices"
        onClick={refresh}
        disabled={s.busy}
      >
        <RotateCw />
      </IconButton>
    </div>
  );
}
function hasPendingUpdate(u: UpdateState | null) {
  if (!u) return false;
  const active = u.status === "downloading" || u.status === "applying";
  return active || (u.status === "available" && Boolean(u.available));
}
function UpdatePopover() {
  const s = useSyncExternalStore(subscribe, view);
  const u = s.update;
  if (!s.updatePopover || !hasPendingUpdate(u)) return null;
  const active = u!.status === "downloading" || u!.status === "applying";
  return (
    <div id="updatePopover" className="focus-update-popover" role="dialog" aria-label="Update ready">
      <div className="focus-update-popover-top">
        <span className="focus-update-popover-title">
          {active ? u!.message || "Updating Focus..." : "Update available"}
        </span>
        <button
          id="closeUpdatePopover"
          type="button"
          className="focus-update-popover-close"
          aria-label="Close"
          onClick={() => patch({ updatePopover: false })}
        >
          <X className="size-3.5" />
        </button>
      </div>
      <div className="focus-update-popover-sub">
        {s.updateError || (active ? "Verifying and restarting..." : `Nightly ${u!.available?.build_id ?? ""} · restarts cleanly`)}
      </div>
      {!active && <ReleaseNotes u={u!} />}
      {!active && (
        <button
          id="popoverUpdateNow"
          type="button"
          className="focus-update-install"
          disabled={s.updateBusy}
          onClick={installUpdate}
        >
          Update &amp; restart
        </button>
      )}
    </div>
  );
}
function ReleaseNotes({ u }: { u: UpdateState }) {
  const notes = u.available?.notes;
  const items = notes?.items?.length
    ? notes.items
    : u.available?.commit_subject
      ? ([[u.available.commit_subject, ""]] as [string, string][])
      : [];
  if (!items.length) return null;
  return (
    <div className="focus-update-notes">
      <ul>
        {items.map(([title, text], i) => (
          <li key={i}>
            <b>
              {title}
              {text ? "." : ""}
            </b>{" "}
            {text}
          </li>
        ))}
      </ul>
      {notes?.more && <div className="focus-update-more">{notes.more}</div>}
    </div>
  );
}

function Header() {
  const s = useSyncExternalStore(subscribe, view);
  const list = all(),
    selected = list.filter((d) => !s.excluded.includes(d.id));
  const label =
    selected.length === list.length
      ? "All devices"
      : selected.length === 0
        ? "No devices"
        : selected.length === 1
          ? selected[0].name
          : `${selected.length} devices`;
  return (
    <div className="focus-head">
      <ToggleGroup
        className="focus-period"
        type="single"
        size="sm"
        spacing={0}
        value={s.range}
        onValueChange={(r) => {
          if (r) {
            patch({ range: r });
            changed(r);
          }
        }}
        aria-label="Statistics period"
      >
        <ToggleGroupItem value="today" data-r="today">
          Today
        </ToggleGroupItem>
        <ToggleGroupItem value="week" data-r="week">
          Week
        </ToggleGroupItem>
      </ToggleGroup>
      <span id="pDate" hidden />
      <div className="focus-head-actions">
        {s.range === "today" && (
          <WalletStore invoke={call} devices={list.filter((d) => d.id !== "laptop")} />
        )}
        <DropdownMenu
          modal={false}
          open={s.menu}
          onOpenChange={(menu) => {
            patch({ menu });
            requestAnimationFrame(resized);
          }}
        >
          <DropdownMenuTrigger asChild>
            <Button variant="text" size="xs" id="deviceFilter" className="dev-trigger-btn">
              <span id="deviceFilterLabel" className="max-w-44 truncate">
                {label}
              </span>
              <ChevronDown data-icon="inline-end" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent
            id="deviceMenu"
            align="end"
            className="min-w-48"
            onEscapeKeyDown={(e) => e.stopPropagation()}
          >
            <DropdownMenuGroup>
              <DropdownMenuCheckboxItem
                checked={selected.length === list.length}
                onSelect={(e) => e.preventDefault()}
                onCheckedChange={() => toggle("all")}
              >
                All devices
              </DropdownMenuCheckboxItem>
            </DropdownMenuGroup>
            <DropdownMenuSeparator />
            <DropdownMenuGroup>
              {list.map((d) => (
                <DropdownMenuCheckboxItem
                  key={d.id}
                  checked={!s.excluded.includes(d.id)}
                  onSelect={(e) => e.preventDefault()}
                  onCheckedChange={() => toggle(d.id)}
                >
                  {d.id === "laptop" ? (
                    <Laptop />
                  ) : isPhone(d as Device) ? (
                    <Smartphone />
                  ) : (
                    <TvMinimal />
                  )}
                  <span className="max-w-52 truncate">{d.name}</span>
                </DropdownMenuCheckboxItem>
              ))}
            </DropdownMenuGroup>
          </DropdownMenuContent>
        </DropdownMenu>
        {hasPendingUpdate(s.update) && (
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                id="headerUpdateBtn"
                type="button"
                className="focus-update-trigger"
                aria-label="Update available"
                onClick={() => patch({ updatePopover: !s.updatePopover })}
              >
                {s.update?.status === "downloading" || s.update?.status === "applying" ? (
                  <LoaderCircle className="size-3.5 animate-spin" />
                ) : (
                  <>
                    <ArrowUpCircle className="size-4" />
                    <span className="upd-dot" />
                  </>
                )}
              </button>
            </TooltipTrigger>
            <TooltipContent>Update available</TooltipContent>
          </Tooltip>
        )}
        <IconButton
          label="Settings"
          id="deviceSettings"
          onClick={() => settings(true, "protection")}
        >
          <Settings />
        </IconButton>
      </div>
    </div>
  );
}

function Disclosure({
  title,
  children,
  defaultOpen = false,
}: {
  title: string;
  children: React.ReactNode;
  defaultOpen?: boolean;
}) {
  return (
    <Collapsible className="focus-disclosure" defaultOpen={defaultOpen}>
      <CollapsibleTrigger>
        <ChevronDown />
        {title}
      </CollapsibleTrigger>
      <CollapsibleContent className="focus-note pt-3">
        {children}
      </CollapsibleContent>
    </Collapsible>
  );
}

function PauseForm({
  action,
  deviceId,
  pkg = "",
  initial = 1,
  onClose,
}: {
  action: string;
  deviceId?: string;
  pkg?: string;
  initial?: number;
  onClose: () => void;
}) {
  const s = useSyncExternalStore(subscribe, view);
  const reasonId = useId();
  const [reason, setReason] = useState(""),
    [duration, setDuration] = useState(String(initial));
  const [checkIn, setCheckIn] = useState("");
  const local = action === "pause";
  const dnsPause = local || action === "dns_pause";
  const d = s.devices.find((d) => d.id === (deviceId || s.settingsId));
  const disabled = s.busy || (!local && !fresh(d));
  const choices = action === "dns_pause" ? dnsPauseChoices(d) : [1, 5, 10, 30];
  const durationAvailable = choices.includes(Number(duration));
  return (
    <form
      className="pause-editor"
      onSubmit={async (e) => {
        e.preventDefault();
        if (!durationAvailable || reason.trim().length < 3 || (dnsPause && !checkIn)) return;
        const ok = await command(
          local
            ? { action, args: { minutes: +duration, reason: reason.trim(), checkIn } }
            : {
                action,
                minutes: +duration,
                reason: reason.trim(),
                ...(dnsPause ? { check_in: checkIn } : {}),
                ...(pkg ? { package: pkg } : {}),
              },
          local,
          d?.id,
        );
        if (ok) onClose();
      }}
    >
      <FieldGroup className="gap-3">
        <Field orientation="horizontal">
          <FieldLabel>Duration</FieldLabel>
          <Select
            value={durationAvailable ? duration : ""}
            onValueChange={setDuration}
            disabled={disabled}
          >
            <SelectTrigger size="sm" aria-label="Pause duration">
              <SelectValue placeholder="Choose a duration" />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {choices.map((m) => (
                  <SelectItem key={m} value={String(m)}>
                    {m < 60 ? `${m}m` : `${m / 60}h`}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </Field>
        {dnsPause && (
          <div className="check-in-options flex-wrap" role="group" aria-label="How are you feeling?">
            {Object.entries(Stats.checkIns).map(([value, label]) => (
              <button
                type="button"
                key={value}
                aria-pressed={checkIn === value}
                disabled={disabled}
                onClick={() => setCheckIn(value)}
              >
                {label}
              </button>
            ))}
          </div>
        )}
        <Field orientation="horizontal">
          <FieldLabel htmlFor={reasonId}>Reason</FieldLabel>
          <Input
            id={reasonId}
            name="reason"
            aria-label="Reason for pause"
            placeholder="Why?"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            minLength={3}
            required
            disabled={disabled}
            autoFocus
          />
          <button type="submit" className="t3-btn primary" disabled={disabled || !durationAvailable || (dnsPause && !checkIn)}>
            Pause
          </button>
          <button type="button" className="t3-btn" onClick={onClose}>
            Cancel
          </button>
        </Field>
      </FieldGroup>
    </form>
  );
}

// Soft daily limits (limits.js). Stricter values apply now; looser ones start tomorrow.
function ProtectionTab() {
  const [, force] = useState(0);
  const L = FocusLimits.get();
  const mins = (m: number) =>
    m < 60 ? `${m}m` : m % 60 ? `${Math.floor(m / 60)}h${m % 60}` : `${m / 60}h`;

  const row = (
    key: "dnsLimitMin" | "waitSeconds" | "maxPauseMin",
    title: string,
    desc: string,
    options: number[],
    label: (n: number) => string,
  ) => {
    const pending = L.pending[key];
    return (
      <div className="t3-row" key={key}>
        <div className="t3-row-text">
          <div className="t3-row-title">{title}</div>
          <div className="t3-row-desc">{desc}</div>
          {pending && (
            <div className="t3-ratchet">
              <span>{label(pending.value)} from tomorrow</span>
              <button
                type="button"
                onClick={() => {
                  FocusLimits.undo(key);
                  void call("pause_judge_limits_sync", { limits: FocusLimits.get() }).catch((e) => patch({ error: String(e) }));
                  force((n) => n + 1);
                }}
              >
                Undo
              </button>
            </div>
          )}
        </div>
        <div className="t3-seg" role="group" aria-label={title}>
          {options.map((o) => (
            <button
              key={o}
              type="button"
              data-limit={`${key}:${o}`}
              className={L[key] === o ? "on" : ""}
              onClick={() => {
                const limits = FocusLimits.set(key, o);
                void call("pause_judge_limits_sync", { limits }).catch((e) => patch({ error: String(e) }));
                force((n) => n + 1);
                changed?.();
              }}
            >
              {label(o)}
            </button>
          ))}
        </div>
      </div>
    );
  };

  return (
    <>
      <div>
        <div className="t3-h">Limits</div>
        <div className="t3-card">
          {row("dnsLimitMin", "Daily DNS-off limit", "Shared by all your devices.", [30, 45, 60, 90], mins)}
          {row("waitSeconds", "Wait before pausing", "From your 3rd pause each day.", [0, 10, 15, 30], (n) => (n ? `${n}s` : "Off"))}
          {row("maxPauseMin", "Phone & laptop max pause", "Longest pause chip on laptop and phone.", [5, 10, 30], (n) => `${n}m`)}
        </div>
        <p className="t3-footnote">Stricter limits start now; looser ones start tomorrow.</p>
      </div>
      <LocalDnsCard />
      <EmergencyShieldSettings invoke={call} />
    </>
  );
}

function LocalDnsCard() {
  const [configured, setConfigured] = useState(true);
  const s = useSyncExternalStore(subscribe, view);
  const [pause, setPause] = useState<number | null>(null);
  const [localStatus, setLocalStatus] = useState("Checking…");

  useEffect(() => {
    call("get_state")
      .then(updateLocalDns)
      .catch((e) => patch({ error: String(e) }));
  }, [s.busy]);

  function updateLocalDns(v: { paused_until: number; scoped_until: number | null; dns_configured?: boolean }) {
    setConfigured(v.dns_configured !== false);
    patch({ localPausedUntil: v.paused_until });
    setLocalStatus(v.dns_configured === false ? "NextDNS setup required · See the Focus installation guide"
      : v.paused_until > Date.now() ? "Paused"
      : v.paused_until ? "Restoring"
      : v.scoped_until != null ? v.scoped_until > Date.now() ? "Scoped access · DNS on" : "Restoring scoped access"
      : "On");
  }

  useEffect(() => {
    const update = (event: Event) => updateLocalDns((event as CustomEvent).detail);
    window.addEventListener("focus-state", update);
    return () => window.removeEventListener("focus-state", update);
  }, []);

  return (
    <div>
      <div className="t3-h">This PC · NextDNS</div>
      <div className="t3-card">
        <div className="t3-row">
          <div className="t3-row-text">
            <div className="t3-row-title">DNS protection</div>
            <div className="t3-row-desc" id="localDnsStatus">
              {localStatus === "On" ? "Protected · Windows DNS active" : localStatus}
            </div>
          </div>
          {configured && <div className="t3-btn-group">
            {s.judgeEnabled ? <button type="button" className="t3-btn"
              onClick={() => {
                DevicePanel.openPauseJudge();
              }}>Ask</button> : [1, 5, 10, 30].map((m) => (
              <button
                key={m}
                type="button"
                className="t3-btn"
                disabled={s.busy}
                onClick={() => setPause(m)}
              >
                {m}m
              </button>
            ))}
            <button
              type="button"
              className="t3-btn"
              disabled={s.busy}
              onClick={() => command({ action: "resume" }, true)}
            >
              Turn on
            </button>
          </div>}
        </div>
        {configured && !s.judgeEnabled && pause !== null && (
          <div className="t3-row-expand" id="pauseFormSlot">
            <PauseForm
              key={pause}
              action="pause"
              initial={pause}
              onClose={() => setPause(null)}
            />
          </div>
        )}
      </div>
    </div>
  );
}

function CategoriesTab() {
  return (
    <div>
      <div className="t3-h">Categories</div>
      <div className="t3-card">
        <div className="t3-row">
          <div className="t3-row-text">
            <div className="t3-row-title">Sorted automatically</div>
            <div className="t3-row-desc">
              Apps and websites are sorted into Coding, Writing, Design, Learning, Reading, Messaging, Errands, Social feeds, Videos, TV &amp; streams, or Not sure.
            </div>
          </div>
        </div>
        <div className="t3-row">
          <div className="t3-row-text">
            <div className="t3-row-title">Uncategorized apps</div>
            <div className="t3-row-desc">
              Anything unfamiliar counts as Not sure instead of Drifting until a rule matches it.
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function LaptopTab() {
  return (
    <>
      <LocalDnsCard />
      <PauseJudgeSettings invoke={call} />
      <div>
        <div className="t3-h">Tracking</div>
        <div className="t3-card">
          <div className="t3-row">
            <div className="t3-row-text">
              <div className="t3-row-title">Browser URL tracking</div>
              <div className="t3-row-desc">
                Chrome and Edge extension reports active tab domains and page titles locally.
              </div>
            </div>
            <span className="t3-val">Active</span>
          </div>
        </div>
      </div>
    </>
  );
}

function RemoteDeviceTab({ device }: { device: Device }) {
  const s = useSyncExternalStore(subscribe, view);
  const dns = device.snapshot?.dns;
  const [profile, setProfile] = useState(dns?.profile || "");
  const [dirty, setDirty] = useState(false);
  const [pause, setPause] = useState<number | null>(null);
  const [restore, setRestore] = useState(false);
  useEffect(() => {
    if (!dirty) setProfile(dns?.profile || "");
  }, [dns?.profile, dirty]);

  const noun = isPhone(device) ? "phone" : "TV";
  const online = fresh(device);
  const connText = online
    ? "Connected over Wi-Fi"
    : device.snapshot
      ? `Offline · saved ${date(device.last_seen)}`
      : "Offline · no saved data";
  const disabled =
    s.busy || !online || !device.snapshot?.permissions.dns;

  return (
    <>
      <div>
        <div className="t3-h">{device.name}</div>
        <div className="t3-card">
          <div className="t3-row">
            <div className="t3-row-text">
              <div className="t3-row-title">Connection</div>
              <div className="t3-row-desc" id="connectionStatus">
                {connText}
              </div>
            </div>
            <button
              type="button"
              className="t3-btn"
              disabled={s.busy}
              onClick={refresh}
            >
              Refresh
            </button>
          </div>
        </div>
      </div>

      {device.snapshot ? (
        <>
          <div>
            <div className="t3-h">NextDNS</div>
            <div className="t3-card">
              {!device.snapshot.permissions.dns && (
                <div className="t3-row">
                  <div className="t3-row-desc">
                    DNS controls need the one-time setup on the {noun}.
                  </div>
                </div>
              )}
              <form
                id="dnsProfileForm"
                className="t3-row"
                onSubmit={async (e) => {
                  e.preventDefault();
                  if (
                    await command(
                      { action: "dns_connect", profile: profile.trim() },
                      false,
                      device.id,
                    )
                  )
                    setDirty(false);
                }}
              >
                <div className="t3-row-text">
                  <div className="t3-row-title">DNS profile</div>
                  <div className="t3-row-desc" id="remoteDnsStatus">
                    {dns?.status || "Off"}
                    {!online && " · at last update"}
                  </div>
                </div>
                <div className="t3-btn-group">
                  <input
                    id="dnsProfile"
                    className="t3-input"
                    aria-label="Profile"
                    value={profile}
                    onChange={(e) => {
                      setProfile(e.target.value);
                      setDirty(true);
                    }}
                    pattern="[a-fA-F0-9]{6}"
                    maxLength={6}
                    required
                    disabled={disabled}
                  />
                  <button
                    type="submit"
                    className="t3-btn"
                    disabled={disabled}
                  >
                    {dns?.enabled ? "Save" : "Connect"}
                  </button>
                </div>
              </form>

              <div className="t3-row">
                <div className="t3-row-text">
                  <div className="t3-row-title">Pause DNS</div>
                </div>
                <div className="t3-btn-group">
                  {dnsPauseChoices(device).map((m) => (
                    <button
                      key={m}
                      type="button"
                      className="t3-btn"
                      disabled={disabled || !dns?.enabled}
                      onClick={() => setPause(m)}
                    >
                      {m < 60 ? `${m}m` : `${m / 60}h`}
                    </button>
                  ))}
                  <button
                    type="button"
                    className="t3-btn"
                    disabled={disabled || !dns?.enabled}
                    onClick={() =>
                      command({ action: "dns_resume" }, false, device.id)
                    }
                  >
                    Turn on
                  </button>
                </div>
              </div>

              {pause !== null && (
                <div className="t3-row-expand" id="pauseFormSlot">
                  <PauseForm
                    key={pause}
                    action="dns_pause"
                    deviceId={device.id}
                    initial={pause}
                    onClose={() => setPause(null)}
                  />
                </div>
              )}

              <div className="t3-row-expand">
                <Disclosure title="More DNS options">
                  <div className="focus-control-row">
                    <button
                      type="button"
                      className="t3-btn"
                      disabled={s.busy || !online}
                      onClick={() =>
                        command({ action: "dns_verify" }, false, device.id)
                      }
                    >
                      Verify connection
                    </button>
                    <button
                      type="button"
                      className="t3-btn"
                      disabled={disabled || !dns?.enabled}
                      onClick={() => setRestore(true)}
                    >
                      Restore previous DNS
                    </button>
                  </div>
                  {restore && (
                    <div className="focus-control-row mt-3">
                      <span>Restore the {noun}’s previous DNS settings?</span>
                      <button
                        type="button"
                        className="t3-btn primary"
                        onClick={async () => {
                          if (
                            await command(
                              { action: "dns_disconnect" },
                              false,
                              device.id,
                            )
                          )
                            setRestore(false);
                        }}
                      >
                        Restore
                      </button>
                      <button
                        type="button"
                        className="t3-btn"
                        onClick={() => setRestore(false)}
                      >
                        Cancel
                      </button>
                    </div>
                  )}
                  <p className="mt-3">
                    Cached content and existing streams may continue after filtering
                    resumes.
                  </p>
                </Disclosure>
              </div>
            </div>
          </div>

          {!isPhone(device) && (
            <div>
              <div className="t3-h">App rules</div>
              {(!device.snapshot.permissions.usage ||
                !device.snapshot.permissions.restrictions) && (
                <p className="t3-footnote mb-2">
                  Enable Usage Access and Accessibility on the TV to enforce app
                  rules.
                </p>
              )}
              <div className="t3-card">
                {device.snapshot.apps.map((app) => (
                  <AppSettings
                    key={device.id + app.package}
                    app={app}
                    device={device}
                  />
                ))}
              </div>
            </div>
          )}

          <div>
            <div className="t3-card">
              <div className="t3-row-expand">
                {isPhone(device) ? (
                  <Disclosure title="About phone measurements and rules">
                    <p>
                      Phone time is personal foreground use, including Chrome and
                      Samsung Internet sites. Simultaneous use counts for each
                      device.
                    </p>
                    <p className="mt-2">
                      Private DNS rules use the phone’s timezone (
                      {device.snapshot.time_zone}) and keep running when the laptop
                      disconnects.
                    </p>
                  </Disclosure>
                ) : (
                  <Disclosure title="About TV measurements and rules">
                    <p>
                      TV app time is household foreground use. Simultaneous use
                      counts for each device. DNS pauses and app exceptions are
                      separate.
                    </p>
                    <p className="mt-2">
                      Rules use the TV’s timezone ({device.snapshot.time_zone}).
                      Statistics use the laptop’s calendar. Rules keep running
                      when the laptop disconnects.
                    </p>
                  </Disclosure>
                )}
              </div>
            </div>
          </div>
        </>
      ) : (
        <div className="t3-card">
          <div className="t3-row">
            <div className="t3-row-desc">
              No saved {noun} data yet. Check that the {noun} is on and on the
              same Wi-Fi, then refresh.
            </div>
          </div>
        </div>
      )}

      <div>
        <div className="t3-card">
          <div className="t3-row">
            <div className="t3-row-text">
              <div className="t3-row-title">Forget {device.name}</div>
              <div className="t3-row-desc">
                Removes its saved history here; {noun} rules stay active.
              </div>
            </div>
            {!s.forget ? (
              <button
                type="button"
                className="t3-btn"
                onClick={() => patch({ forget: true })}
              >
                Forget device
              </button>
            ) : (
              <div className="t3-btn-group">
                <button
                  type="button"
                  id="confirmForget"
                  className="t3-btn primary"
                  disabled={s.busy}
                  onClick={async () => {
                    patch({ busy: true });
                    try {
                      receive(await call("device_forget", { id: device.id }));
                      patch({
                        settingsTab: "protection",
                        settingsId: "laptop",
                        forget: false,
                      });
                    } catch (e) {
                      patch({ error: String(e) });
                    } finally {
                      patch({ busy: false });
                    }
                  }}
                >
                  Forget
                </button>
                <button
                  type="button"
                  className="t3-btn"
                  onClick={() => patch({ forget: false })}
                >
                  Cancel
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
    </>
  );
}

function AppSettings({ app, device }: { app: AppRule; device: Device }) {
  const s = useSyncExternalStore(subscribe, view);
  const [draft, setDraft] = useState(app),
    [dirty, setDirty] = useState(false),
    [open, setOpen] = useState(false),
    [pause, setPause] = useState(false);
  useEffect(() => {
    if (!dirty) setDraft(app);
  }, [app, dirty]);
  const update = (v: Partial<AppRule>) => {
    setDraft({ ...draft, ...v });
    setDirty(true);
  };
  const disabled =
    s.busy ||
    !fresh(device) ||
    !device.snapshot?.permissions.usage ||
    !device.snapshot?.permissions.restrictions;
  const id = useId();
  return (
    <Collapsible
      className="t3-app-item"
      open={open}
      onOpenChange={(o) => {
        setOpen(o);
        requestAnimationFrame(resized);
      }}
      data-package={app.package}
    >
      <CollapsibleTrigger className="focus-app-row">
        <span>
          {app.label}
          <small>
            {app.blocked
              ? "Blocked"
              : app.scheduled || app.daily_limit_ms
                ? "Rules set"
                : "Allowed"}
            {app.pause_until > (device.snapshot?.now || 0) &&
              " · temporarily allowed"}
          </small>
        </span>
        <ChevronDown />
      </CollapsibleTrigger>
      <CollapsibleContent className="focus-app-rule">
        <form
          data-rule-package={app.package}
          onSubmit={async (e) => {
            e.preventDefault();
            if (
              await command(
                {
                  action: "set_rule",
                  package: app.package,
                  blocked: draft.blocked,
                  daily_limit_ms: draft.daily_limit_ms,
                  scheduled: draft.scheduled,
                  from_minute: draft.from_minute,
                  to_minute: draft.to_minute,
                },
                false,
                device.id,
              )
            )
              setDirty(false);
          }}
        >
          <FieldGroup className="gap-3">
            <FieldSet>
              <FieldLegend className="sr-only">
                App restrictions for {app.label}
              </FieldLegend>
              <Field orientation="horizontal">
                <Checkbox
                  id={`${id}-block`}
                  name="blocked"
                  checked={draft.blocked}
                  onCheckedChange={(v) => update({ blocked: v === true })}
                  disabled={disabled}
                />
                <FieldLabel htmlFor={`${id}-block`}>Block app</FieldLabel>
              </Field>
              <Field orientation="horizontal">
                <FieldLabel htmlFor={`${id}-limit`}>Daily limit</FieldLabel>
                <Input
                  id={`${id}-limit`}
                  name="limit"
                  className="w-20"
                  type="number"
                  min={0}
                  max={1440}
                  step={1}
                  value={draft.daily_limit_ms / 60000}
                  onChange={(e) =>
                    update({ daily_limit_ms: Number(e.target.value) * 60000 })
                  }
                  disabled={disabled}
                />
                <span className="focus-note">minutes · 0 = no limit</span>
              </Field>
              <Field orientation="horizontal">
                <Checkbox
                  id={`${id}-schedule`}
                  name="scheduled"
                  checked={draft.scheduled}
                  onCheckedChange={(v) => update({ scheduled: v === true })}
                  disabled={disabled}
                />
                <FieldLabel htmlFor={`${id}-schedule`}>
                  Block on schedule
                </FieldLabel>
                <Input
                  name="from"
                  aria-label={`Block ${app.label} from`}
                  className="w-28"
                  type="time"
                  value={clock(draft.from_minute)}
                  onChange={(e) =>
                    update({ from_minute: minutes(e.target.value) })
                  }
                  disabled={disabled}
                  required
                />
                <span className="focus-note">to</span>
                <Input
                  name="to"
                  aria-label={`Block ${app.label} until`}
                  className="w-28"
                  type="time"
                  value={clock(draft.to_minute)}
                  onChange={(e) =>
                    update({ to_minute: minutes(e.target.value) })
                  }
                  disabled={disabled}
                  required
                />
              </Field>
            </FieldSet>
            <Field orientation="horizontal">
              <button type="submit" className="t3-btn primary" disabled={disabled}>
                Save
              </button>
              <button
                type="button"
                className="t3-btn"
                disabled={disabled}
                onClick={() => setPause(true)}
              >
                Allow temporarily
              </button>
              {app.pause_until > (device.snapshot?.now || 0) && (
                <button
                  type="button"
                  className="t3-btn"
                  disabled={disabled}
                  onClick={() =>
                    command(
                      { action: "app_resume", package: app.package },
                      false,
                      device.id,
                    )
                  }
                >
                  End exception
                </button>
              )}
            </Field>
          </FieldGroup>
        </form>
        <div className="app-pause-slot mt-3">
          {pause && (
            <PauseForm
              action="app_pause"
              deviceId={device.id}
              pkg={app.package}
              onClose={() => setPause(false)}
            />
          )}
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
}

function Pairing() {
  const s = useSyncExternalStore(subscribe, view);
  const [nearby, setNearby] = useState<NearbyDevice[]>([]);
  const [searching, setSearching] = useState(true),
    [searchError, setSearchError] = useState("");
  const [approval, setApproval] = useState<Approval | null>(null),
    [starting, setStarting] = useState<string | null>(null);
  const [connectionError, setConnectionError] = useState("");
  const current = useRef<Approval | null>(null),
    alive = useRef(true),
    generation = useRef(0);
  useEffect(() => {
    alive.current = true;
    let stopped = false,
      timer: ReturnType<typeof setTimeout>;
    const scan = async () => {
      try {
        const list = await call("device_nearby");
        if (!stopped) {
          setNearby(list);
          setSearchError("");
        }
      } catch (e) {
        if (!stopped) setSearchError(String(e));
      }
      if (!stopped) {
        setSearching(false);
        timer = setTimeout(scan, 1500);
      }
    };
    scan();
    return () => {
      stopped = true;
      alive.current = false;
      ++generation.current;
      clearTimeout(timer);
      const pending = current.current;
      current.current = null;
      if (pending)
        call("device_cancel_approval", { requestId: pending.request_id }).catch(
          () => {},
        );
    };
  }, []);
  useEffect(() => {
    if (!approval || approval.status !== "waiting") return;
    let stopped = false,
      timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const result: Approval = await call("device_approval_status", {
          requestId: approval.request_id,
        });
        if (stopped) return;
        setApproval(result);
        current.current = result.status === "waiting" ? result : null;
        setConnectionError("");
        if (result.status === "approved") {
          receive(await call("device_list"));
          if (!stopped)
            patch({
              settingsTab: `device:${result.device_id}`,
              settingsId: result.device_id,
              error: "",
            });
          return;
        }
        if (result.status !== "waiting") return;
      } catch (e) {
        if (!stopped) setConnectionError(String(e));
      }
      if (!stopped) timer = setTimeout(poll, 1000);
    };
    timer = setTimeout(poll, 650);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [approval?.request_id, approval?.status]);
  async function connect(peer: NearbyDevice) {
    const version = ++generation.current;
    setStarting(peer.key);
    setConnectionError("");
    try {
      const result: Approval = await call("device_request_approval", {
        key: peer.key,
      });
      if (!alive.current || version !== generation.current) {
        call("device_cancel_approval", { requestId: result.request_id }).catch(
          () => {},
        );
        return;
      }
      current.current = result;
      setApproval(result);
    } catch (e) {
      if (alive.current) setConnectionError(String(e));
    } finally {
      if (alive.current) {
        setStarting(null);
        resized();
      }
    }
  }
  function cancel() {
    const pending = current.current;
    current.current = null;
    setApproval(null);
    setConnectionError("");
    ++generation.current;
    if (pending)
      call("device_cancel_approval", { requestId: pending.request_id }).catch(
        () => {},
      );
  }
  async function searchAgain() {
    setSearching(true);
    setSearchError("");
    try {
      const list = await call("device_nearby");
      if (alive.current) setNearby(list);
    } catch (e) {
      if (alive.current) setSearchError(String(e));
    } finally {
      if (alive.current) setSearching(false);
    }
  }
  const peers = nearby.filter((peer) => !peer.paired);
  return (
    <section aria-label="Connect a nearby device">
      <div className="t3-h flex items-center justify-between">
        <span>Nearby devices</span>
        <button type="button" className="t3-btn" onClick={searchAgain}>
          Scan
        </button>
      </div>
      <div className="t3-card">
        {!approval && peers.length > 0 &&
          peers.map((peer) => (
            <div key={peer.key} className="t3-row">
              <div className="flex items-center gap-2.5 min-w-0 flex-1">
                {peer.kind === "phone" ? (
                  <Smartphone className="size-4 text-muted-foreground shrink-0" />
                ) : (
                  <TvMinimal className="size-4 text-muted-foreground shrink-0" />
                )}
                <span className="t3-row-title truncate">{peer.name}</span>
              </div>
              <button
                type="button"
                className="t3-btn primary"
                disabled={Boolean(starting) || s.busy}
                onClick={() => connect(peer)}
              >
                {starting === peer.key ? "Connecting…" : "Connect"}
              </button>
            </div>
          ))}
        {!approval && !peers.length && (
          <div className="t3-row">
            <div className="t3-row-text">
              <div className="t3-row-title">
                {searching
                  ? "Looking for devices…"
                  : nearby.length
                    ? "All nearby devices are connected"
                    : "No nearby devices found yet"}
              </div>
              <div className="t3-row-desc">
                Open Focus on another Android phone or Google TV on this Wi-Fi to pair with a 6-digit code.
              </div>
            </div>
          </div>
        )}
        {approval?.status === "waiting" && (
          <div className="t3-row-expand flex flex-col items-start gap-2.5">
            <span className="t3-row-desc">
              {approval.name} is waiting for approval
            </span>
            <span className="pairing-number">
              {approval.code.slice(0, 3)} {approval.code.slice(3)}
            </span>
            <p className="t3-row-desc">
              Check that this number matches {approval.name}, then choose Allow.
            </p>
            <button type="button" className="t3-btn" onClick={cancel}>
              Cancel
            </button>
          </div>
        )}
        {approval &&
          (approval.status === "declined" || approval.status === "expired") && (
            <div className="t3-row">
              <span className="t3-row-desc">
                {approval.status === "declined"
                  ? `Connection declined on ${approval.name}.`
                  : "Approval timed out. Try connecting again."}
              </span>
              <button type="button" className="t3-btn" onClick={cancel}>
                Try again
              </button>
            </div>
          )}
        {(searchError || connectionError) && (
          <div className="t3-row">
            <p role="alert" className="t3-row-desc">
              {searchError || connectionError}
            </p>
          </div>
        )}
      </div>
    </section>
  );
}

function UpdatesTab() {
  const s = useSyncExternalStore(subscribe, view);
  const u = s.update;
  const [tokenInput, setTokenInput] = useState("");
  const [showTokenForm, setShowTokenForm] = useState(false);

  if (!u) {
    return (
      <div>
        <div className="t3-h">Updates</div>
        <div className="t3-card">
          <div className="t3-row">
            <div className="t3-row-desc">Updater status unavailable.</div>
          </div>
        </div>
      </div>
    );
  }

  const working =
    s.updateBusy ||
    u.status === "checking" ||
    u.status === "downloading" ||
    u.status === "applying";
  const statusLine = (() => {
    if (u.status === "checking") return "Checking GitHub for a new nightly build...";
    if (u.status === "downloading" || u.status === "applying")
      return u.message || "Installing nightly update...";
    if (u.status === "available" && u.available)
      return `Nightly ${u.available.build_id} ready`;
    if (u.status === "up_to_date")
      return u.message || "Up to date with the latest nightly build.";
    if (u.status === "needs_auth")
      return u.error || "Private repo: add a read-only GitHub token below.";
    if (u.status === "error") return u.error || "Update check failed.";
    return "Checks GitHub nightly releases automatically.";
  })();

  return (
    <div>
      <div className="t3-h">Updates</div>
      <div className="t3-card">
        <div className="t3-row">
          <div className="t3-row-text">
            <div className="t3-row-title" id="updaterStatus">
              {statusLine}
            </div>
            <div className="t3-row-desc">
              <span id="updaterBuild">Build {u.current_build_id}</span>
              {u.last_checked_at && <> · Checked {date(u.last_checked_at)}</>}
            </div>
            {s.updateError && u.status !== "error" && u.status !== "needs_auth" && (
              <p role="alert" className="t3-row-desc mt-1">
                {s.updateError}
              </p>
            )}
          </div>
          <div className="t3-btn-group">
            {u.available && (
              <button
                id="settingsUpdateNow"
                type="button"
                className="t3-btn primary"
                disabled={working}
                onClick={installUpdate}
              >
                {working ? "Updating…" : "Update & restart"}
              </button>
            )}
            <button
              id="checkUpdates"
              type="button"
              className="t3-btn"
              disabled={working}
              onClick={checkUpdates}
            >
              {u.status === "checking" ? "Checking…" : "Check"}
            </button>
          </div>
        </div>

        {u.available && (
          <div className="t3-row-expand">
            <ReleaseNotes u={u} />
          </div>
        )}

        <div className="t3-row">
          <div className="t3-row-text">
            <div className="t3-row-title">Check automatically</div>
            <div className="t3-row-desc">
              Quietly checks GitHub nightly releases in the background.
            </div>
          </div>
          <OledSwitch
            id="autoUpdatesSwitch"
            checked={u.enabled}
            disabled={working}
            label="Check for nightly updates automatically"
            onChange={(next) => toggleAutoUpdates(next)}
          />
        </div>

        <div className="t3-row">
          <div className="t3-row-text">
            <div className="t3-row-title">GitHub token</div>
            <div className="t3-row-desc">
              {u.has_token
                ? `Saved with Windows DPAPI for ${u.repo}.`
                : `Only needed while ${u.repo} is private (Contents: Read-only).`}
            </div>
          </div>
          {u.has_token ? (
            <button
              id="removeUpdaterToken"
              type="button"
              className="t3-btn"
              disabled={working}
              onClick={() => saveUpdateToken(null)}
            >
              Remove
            </button>
          ) : (
            <button
              type="button"
              className="t3-btn"
              aria-expanded={showTokenForm}
              onClick={() => setShowTokenForm((v) => !v)}
            >
              {showTokenForm ? "Close" : "Add token"}
            </button>
          )}
        </div>

        {!u.has_token && showTokenForm && (
          <div className="t3-row-expand">
            <form
              id="updaterTokenForm"
              className="focus-control-row"
              onSubmit={async (e) => {
                e.preventDefault();
                if (!tokenInput.trim()) return;
                if (await saveUpdateToken(tokenInput)) {
                  setTokenInput("");
                  setShowTokenForm(false);
                }
              }}
            >
              <Input
                id="updaterToken"
                type="password"
                className="flex-1 min-w-44"
                placeholder="github_pat_..."
                value={tokenInput}
                onChange={(e) => setTokenInput(e.target.value)}
                disabled={working}
                autoFocus
              />
              <button
                id="saveUpdaterToken"
                type="submit"
                className="t3-btn primary"
                disabled={working || !tokenInput.trim()}
              >
                Save
              </button>
            </form>
          </div>
        )}
      </div>
    </div>
  );
}

function AboutTab() {
  const s = useSyncExternalStore(subscribe, view);
  return (
    <div>
      <div className="t3-h">About Focus</div>
      <div className="t3-card">
        <div className="t3-row">
          <div className="t3-row-text">
            <div className="t3-row-title">Local-first</div>
            <div className="t3-row-desc">
              Logs stay on your devices and sync only over your own Wi-Fi.
            </div>
          </div>
        </div>
        <div className="t3-row">
          <div className="t3-row-text">
            <div className="t3-row-title">Ratchet rule</div>
            <div className="t3-row-desc">
              Stricter limits start now; looser ones start tomorrow.
            </div>
          </div>
        </div>
        {s.update?.current_build_id && (
          <div className="t3-row">
            <div className="t3-row-text">
              <div className="t3-row-title">Build</div>
              <div className="t3-row-desc">{s.update.current_build_id}</div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function SettingsPage() {
  const s = useSyncExternalStore(subscribe, view);
  const searchRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (
        e.key === "/" &&
        state.settings &&
        document.activeElement?.tagName !== "INPUT" &&
        document.activeElement?.tagName !== "TEXTAREA"
      ) {
        e.preventDefault();
        searchRef.current?.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  const activeDevice = s.settingsTab.startsWith("device:")
    ? s.devices.find((d) => d.id === s.settingsTab.slice(7))
    : undefined;

  const q = s.settingsSearch.trim().toLowerCase();
  const searchItems = [
    { tab: "protection", sec: "Protection", title: "Daily DNS-off limit", desc: "Shared daily cap across all your devices.", kw: "limit budget daily dns off cap" },
    { tab: "protection", sec: "Protection", title: "Wait before pausing", desc: "Short wait from your 3rd pause each day.", kw: "wait friction pause ladder ratchet" },
    { tab: "protection", sec: "Protection", title: "Phone & laptop max pause", desc: "Longest pause chip on laptop and phone.", kw: "max pause duration phone laptop" },
    { tab: "habits", sec: "Habits", title: "Daily credit cap", desc: "", kw: "routine streak reward" },
    { tab: "categories", sec: "Categories", title: "Sorted automatically", desc: "Apps and sites are sorted automatically.", kw: "categories sort automatic not sure" },
    { tab: "laptop", sec: "Laptop", title: "This PC DNS & browser tracking", desc: "Windows DNS status, pause controls, Chrome & Edge extension.", kw: "laptop windows dns chrome edge extension" },
    ...s.devices.map((d) => ({
      tab: `device:${d.id}`,
      sec: d.name,
      title: `${d.name} DNS & rules`,
      desc: fresh(d) ? "Connected over Wi-Fi." : "Offline · saved history.",
      kw: `${d.name.toLowerCase()} dns profile pause rules tv phone`,
    })),
    { tab: "connect", sec: "Devices", title: "Connect a nearby device", desc: "Pair an Android phone or Google TV on this Wi-Fi.", kw: "pair connect nearby phone tv wifi" },
    { tab: "updates", sec: "System", title: "Nightly updates", desc: "Auto-check, release notes, GitHub token.", kw: "update nightly build github token" },
    { tab: "about", sec: "System", title: "About Focus", desc: "Local-first storage and ratchet rule.", kw: "about local privacy ratchet" },
  ];
  const matches = q
    ? searchItems.filter(
        (m) =>
          m.title.toLowerCase().includes(q) ||
          m.desc.toLowerCase().includes(q) ||
          m.kw.includes(q),
      )
    : [];

  const crumbTitle = q
    ? "Search"
    : s.settingsTab === "protection"
      ? "Protection"
      : s.settingsTab === "habits" ? "Habits"
      : s.settingsTab === "categories"
        ? "Categories"
        : s.settingsTab === "laptop"
          ? "Laptop"
          : s.settingsTab === "connect"
            ? "Connect device"
            : s.settingsTab === "updates"
              ? "Updates"
              : s.settingsTab === "about"
                ? "About"
                : activeDevice?.name || "Device";

  const navItem = (tab: string, label: string, extra?: string, id?: string) => (
    <button
      key={tab}
      id={id}
      type="button"
      className={`t3-nav${!q && s.settingsTab === tab ? " on" : ""}`}
      data-stab={tab}
      onClick={() => selectSettingsTab(tab)}
    >
      <span className="truncate">{label}</span>
      {extra && <span className="t3-nav-end">{extra}</span>}
    </button>
  );

  const laptopState = s.localPausedUntil > Date.now() ? "Paused" : "Protected";

  return (
    <div id="settingsPage" className="t3-set" hidden={!s.settings}>
      <aside className="t3-rail" aria-label="Settings navigation">
        <div className="t3-search">
          <Search className="size-3 text-muted-foreground shrink-0" />
          <input
            ref={searchRef}
            id="setSearchIn"
            placeholder="Search"
            value={s.settingsSearch}
            onChange={(e) => patch({ settingsSearch: e.target.value })}
          />
          <span className="t3-kbd">/</span>
        </div>
        {navItem("protection", "Protection")}
        {navItem("habits", "Habits")}
        {navItem("categories", "Categories")}
        <div className="t3-sec-lbl">Devices</div>
        {navItem("laptop", "Laptop", laptopState)}
        {s.devices.map((d) =>
          navItem(
            `device:${d.id}`,
            d.name,
            fresh(d)
              ? d.snapshot?.dns?.pause_until && d.snapshot.dns.pause_until > Date.now()
                ? "Paused"
                : "Protected"
              : "Offline",
          ),
        )}
        {navItem("connect", "+ Connect", undefined, "connectTv")}
        <div className="t3-sec-lbl">System</div>
        {navItem(
          "updates",
          "Updates",
          hasPendingUpdate(s.update) ? "Ready" : undefined,
        )}
        {navItem("about", "About")}
      </aside>

      <div className="t3-main">
        <div className="t3-crumb">
          <span>
            Settings &nbsp;/&nbsp; <b>{crumbTitle}</b>
          </span>
          <div className="flex items-center gap-1">
            <IconButton
              label="Refresh settings"
              id="refreshSettings"
              onClick={refresh}
              disabled={s.busy}
            >
              <RotateCw />
            </IconButton>
            <IconButton
              label="Close settings"
              id="backToStats"
              onClick={() => settings(false)}
            >
              <X />
            </IconButton>
          </div>
        </div>

        <div className="t3-pane" id="settingsBody">
          {q ? (
            <div>
              <div className="t3-h">Matching settings ({matches.length})</div>
              <div className="t3-card">
                {matches.length ? (
                  matches.map((m) => (
                    <button
                      key={m.tab + m.title}
                      type="button"
                      className="t3-row t3-row-btn"
                      onClick={() => selectSettingsTab(m.tab)}
                    >
                      <div className="t3-row-text">
                        <div className="t3-row-title">{m.title}</div>
                        <div className="t3-row-desc">
                          {m.sec} · {m.desc}
                        </div>
                      </div>
                      <span className="t3-val">›</span>
                    </button>
                  ))
                ) : (
                  <div className="t3-row">
                    <div className="t3-row-desc">
                      No settings matching "{s.settingsSearch}".
                    </div>
                  </div>
                )}
              </div>
            </div>
          ) : s.settingsTab === "protection" ? (
            <ProtectionTab />
          ) : s.settingsTab === "habits" ? (
            <div id="habitSettings" />
          ) : s.settingsTab === "categories" ? (
            <CategoriesTab />
          ) : s.settingsTab === "laptop" ? (
            <LaptopTab />
          ) : s.settingsTab === "connect" ? (
            <Pairing />
          ) : s.settingsTab === "updates" ? (
            <UpdatesTab />
          ) : s.settingsTab === "about" ? (
            <AboutTab />
          ) : activeDevice ? (
            <RemoteDeviceTab key={activeDevice.id} device={activeDevice} />
          ) : (
            <ProtectionTab />
          )}

          {(s.error || s.registryError) && (
            <Alert id="settingsError">
              <AlertCircle />
              <AlertDescription>{s.error || s.registryError}</AlertDescription>
            </Alert>
          )}
        </div>
      </div>
    </div>
  );
}

function IncomingPairingBanner() {
  const s = useSyncExternalStore(subscribe, view);
  const [working, setWorking] = useState(false);
  const req = s.incomingPairing;
  if (!req) return null;
  async function respond(allow: boolean) {
    if (!req || working) return;
    setWorking(true);
    try {
      await call(allow ? "laptop_approve_pairing" : "laptop_decline_pairing", {
        requestId: req.request_id,
      });
      patch({ incomingPairing: null });
    } catch (e) {
      patch({ error: String(e) });
    } finally {
      setWorking(false);
      resized();
    }
  }
  return (
    <div
      id="incomingPairingBanner"
      className="focus-notice flex flex-col gap-2"
      role="region"
      aria-label="Incoming device pairing request"
    >
      <div className="focus-control-row">
        <Smartphone className="size-4 text-muted-foreground" />
        <span className="flex-1">
          <strong>{req.manager_name}</strong> wants to pair with this laptop
        </span>
        <span className="pairing-number text-base">
          {req.code.slice(0, 3)} {req.code.slice(3)}
        </span>
      </div>
      <div className="focus-control-row">
        <span className="focus-note flex-1">
          Check that this code matches your device.
        </span>
        <SmallButton disabled={working} onClick={() => respond(true)}>
          Allow
        </SmallButton>
        <SmallButton disabled={working} onClick={() => respond(false)}>
          Decline
        </SmallButton>
      </div>
    </div>
  );
}

function App() {
  const s = useSyncExternalStore(subscribe, view);
  useEffect(() => {
    requestAnimationFrame(resized);
  }, [s]);
  return (
    <TooltipProvider>
      <IncomingPairingBanner />
      <div id="statsPage" hidden={s.settings} className="relative">
        <Header />
        <UpdatePopover />
        <Notice />
        <Statistics />
      </div>
      <SettingsPage />
    </TooltipProvider>
  );
}

let judgeRoot: ReturnType<typeof createRoot>;
const DevicePanel = {
  init(invoke: Invoke, onChange: (r?: string) => void, onResize: () => void) {
    call = invoke;
    changed = onChange;
    resized = onResize;
    window.addEventListener("pause-judge-settings", (event) => {
      patch({ judgeEnabled: Boolean((event as CustomEvent).detail?.enabled) });
    });
    flushSync(() =>
      createRoot(document.getElementById("devicePanelRoot")!).render(<App />),
    );
    judgeRoot = createRoot(document.getElementById("dockPauseJudge")!);
    ready = load();
    return ready;
  },
  receive,
  receiveUpdate,
  receiveIncomingPairing,
  refresh,
  scopedServices(container: HTMLElement, services: string[]) {
    container.replaceChildren(...services.map(service => {
      const logo = document.createElement("span");
      logo.className = "judge-logo";
      logo.title = serviceName(service);
      logo.setAttribute("role", "img");
      logo.setAttribute("aria-label", logo.title);
      const url = serviceImage(service);
      if (url) {
        const image = document.createElement("img");
        image.src = url;
        image.alt = "";
        logo.append(image);
      } else {
        logo.textContent = logo.title[0]?.toUpperCase() ?? "";
      }
      return logo;
    }));
  },
  openPauseJudge() {
    const container = document.getElementById("dockPauseJudge")!;
    if (!container.hidden) {
      container.querySelector("textarea")?.focus();
      return;
    }
    container.hidden = false;
    window.dispatchEvent(new Event("dock-pause-open"));
    flushSync(() => judgeRoot.render(<PauseJudgeSheet invoke={call} dismiss={DevicePanel.closePauseJudge} />));
    resized();
    void call("grab_focus").then(() => container.querySelector("textarea")?.focus());
  },
  closePauseJudge(restoreFocus = true) {
    const container = document.getElementById("dockPauseJudge")!;
    if (container.hidden) return;
    container.hidden = true;
    flushSync(() => judgeRoot.render(null));
    window.dispatchEvent(new CustomEvent("dock-pause-closed", { detail: { restoreFocus } }));
    resized();
  },
  openSettings(tab?: string) {
    settings(true, tab || "protection");
  },
  openUpdatePopover() {
    if (state.settings) settings(false);
    if (hasPendingUpdate(state.update)) patch({ updatePopover: true });
  },
  async render(invoke: Invoke, range: string) {
    await ready;
    if (state.settings) return;
    if (state.range !== range) patch({ range });
    const version = ++renderVersion;
    const guarded: Invoke = async (c, a) => {
      const result = await invoke(c, a);
      if (version !== renderVersion || state.settings)
        throw Error("stale-render");
      return result;
    };
    try {
      if (
        !state.devices.some((d) => !state.excluded.includes(d.id)) &&
        !state.excluded.includes("laptop")
      )
        await Panel.render(guarded, range);
      else
        await Panel.renderDevices(
          guarded,
          range,
          state.devices,
          state.excluded,
        );
    } catch (e) {
      if (!(e instanceof Error) || e.message !== "stale-render") throw e;
    }
    resized();
  },
  escape() {
    if (
      document.querySelector(
        "[data-slot=select-content],[data-slot=dropdown-menu-content]",
      )
    )
      return true;
    if (state.updatePopover) {
      patch({ updatePopover: false });
      return true;
    }
    if (state.menu) {
      patch({ menu: false });
      return true;
    }
    if (state.settings) {
      settings(false);
      return true;
    }
    if (Panel.exitExplorer?.()) {
      resized();
      return true;
    }
    return false;
  },
  close() {
    ++renderVersion;
    window.dispatchEvent(new Event("focus-store-dismiss"));
    Panel.exitExplorer?.();
    if (state.settings) settings(false);
    patch({ menu: false, updatePopover: false });
  },
  get settings() {
    return state.settings;
  },
};
Object.assign(window, { DevicePanel });

// Replace the dock's existing hand-written SVGs with the same icon family.
for (const el of document.querySelectorAll<HTMLElement>("[data-dock-icon]")) {
  const Icon = {
    lock: LockKeyhole,
    submit: CornerDownLeft,
    shield: ShieldOff,
    update: ArrowUpCircle,
    close: X,
  }[el.dataset.dockIcon as "lock"];
  flushSync(() => createRoot(el).render(<Icon className="ico" aria-hidden />));
}
