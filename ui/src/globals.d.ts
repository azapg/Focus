declare const Stats: { dayStart: (t: number) => number; DAY: number; checkIns: Readonly<Record<string, string>> };
declare const Panel: {
  render: (call: Invoke, range: string) => Promise<void>;
  renderDevices: (
    call: Invoke,
    range: string,
    devices: Device[],
    excluded: string[],
  ) => Promise<void>;
  exitExplorer?: () => boolean;
};
type Invoke = (command: string, args?: Record<string, unknown>) => Promise<any>;
type AppRule = {
  package: string;
  label: string;
  blocked: boolean;
  daily_limit_ms: number;
  scheduled: boolean;
  from_minute: number;
  to_minute: number;
  pause_until: number;
};
type Device = {
  id: string;
  kind?: "tv" | "phone";
  name: string;
  online: boolean;
  last_seen: number;
  error: string | null;
  connection: { address: string; fingerprint: string };
  snapshot: null | {
    kind?: "tv" | "phone";
    now: number;
    permissions: { usage: boolean; restrictions: boolean; dns: boolean };
    tracking_age_ms: number;
    dns: {
      enabled: boolean;
      profile: string;
      status: string;
      pause_until: number;
      pause_choices?: number[];
    };
    apps: AppRule[];
    time_zone: string;
  };
};

type NearbyDevice = {
  key: string;
  id: string;
  kind?: "tv" | "phone";
  name: string;
  paired: boolean;
};
type Approval = {
  request_id: string;
  device_id: string;
  kind?: "tv" | "phone";
  name: string;
  code: string;
  status: "waiting" | "approved" | "declined" | "expired";
};
type IncomingApproval = {
  request_id: string;
  manager_name: string;
  code: string;
};

type AvailableUpdate = {
  build_id: string;
  commit_sha: string;
  commit_subject: string;
  notes?: { items: [string, string][]; more: string };
  published_at: string;
  size_bytes: number;
  dismissed: boolean;
};

type UpdateState = {
  status:
    | "idle"
    | "checking"
    | "up_to_date"
    | "available"
    | "downloading"
    | "applying"
    | "needs_auth"
    | "error";
  current_build_id: string;
  current_commit_sha: string;
  built_at: string;
  dry_run: boolean;
  dns_configured: boolean;
  enabled: boolean;
  repo: string;
  tag: string;
  has_token: boolean;
  available: AvailableUpdate | null;
  last_checked_at: number | null;
  error: string | null;
  message: string | null;
};

declare const FocusLimits: {
  DEF: { dnsLimitMin: number; waitSeconds: number; maxPauseMin: number };
  get(now?: number): { dnsLimitMin: number; waitSeconds: number; maxPauseMin: number; pending: Record<string, { value: number; from: number }> };
  set(key: "dnsLimitMin" | "waitSeconds" | "maxPauseMin", value: number, now?: number): ReturnType<typeof FocusLimits.get>;
  undo(key: string): void;
};
