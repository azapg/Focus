# Ask to pause

A five-minute Discord check can turn into twenty minutes on X if a pause lifts
every restriction. **Ask** lets you describe the task and open only
the services you need.

## Try it

1. On the Windows laptop that owns Ask, open **Settings → Laptop → Pause
   assistant**. Save a Vercel AI Gateway key and a NextDNS API key with write
   access to the profile configured in Focus. Keep the NextDNS key on just
   this laptop for that profile. Finish restoring any previous grant before
   moving Ask to another laptop.
2. Pair the phone with that laptop. At first setup, a phone with one paired
   laptop selects it automatically. To switch owners or choose among several
   laptops, select the owner under **Settings →
   Protection → Ask laptop**. Keep the devices on the same network and set
   the Windows network profile to **Private**.
   [Microsoft network settings](https://support.microsoft.com/en-us/windows/experience/connectivity-networking/essential-network-settings-and-tasks-in-windows)
3. Type “I need to share a link on Discord” and tap **Ask**. The review shows
   the proposed services and duration. **Edit** restores your request.
   **Start** opens the services and shows the timer. An unrelated blocked
   site stays blocked.
4. An approved full pause requires a hold and affects the device you asked
   to pause. Follow-up ratings appear after full pauses.
5. Submit a single post link in **Ask** to open [Peek](browser-activity.md#peek-at-a-blocked-post).
   This path works without a Gateway key.

If an ordinary site fails on one device and DNS filtering might be involved,
name the site and describe what failed. The laptop checks recent NextDNS blocks
and can offer access to the named domain while protection stays on. A suspected
block without matching evidence can get a short test. An explicit malware or
phishing warning should result in a denial.

The Windows laptop runs GPT-6 Luna (`openai/gpt-6-luna`) for both devices.
The laptop protects its keys with Windows DPAPI.

## What the assistant sees

The Gateway receives your request, local time, today's pause count, minutes
and reasons, the last hour's top apps and hostnames, and outcomes from similar
pauses when available. Check-ins and numeric ratings help provide context.
It receives hostnames from links. Window titles, URL paths and credentials,
and notes you add to ratings stay on your devices.

Windows records decisions and applications in `pauses.jsonl`. Android records
Ask stages, decision actions, and manual overrides in its own app data without
request text in those diagnostic events.
Scoped access stays separate from DNS-off time. If Android can't reach its
Ask laptop, the sheet keeps the request and offers **Retry**. A model-approved
phone full pause still uses Android's own limit, hold, and automatic restore.
Store and Emergency Shield remain available from their existing screens.
Windows keeps the Ask pill after you remove a saved Gateway key.

## Temporary service access

The laptop keeps NextDNS on while it temporarily adds service domains to the
profile allowlist. Every device using the same profile, including the phone and
TV, gets that access during the same window. To close it early, click
**End** on the laptop or on a paired phone with the countdown visible.

Focus saves the deadline and the prior allowlist entries before making
changes. During cleanup, it removes entries it added and restores entries it
temporarily enabled. It leaves already-active entries in place. It flushes the
Windows DNS cache after changes, and the browser extension rechecks open tabs.
Browser caches and open connections can still delay a DNS change.

NextDNS entries have no server-side timer. The laptop checks for expired or
interrupted changes at startup and retries cleanup when the API is reachable.
A sleeping or powered-off laptop, a network outage, or an invalid API key can
delay cleanup. Keep a valid key until cleanup finishes. If an interrupted grant
is still being checked, leave its allowlist entry alone until reconciliation
finishes. [NextDNS API](https://nextdns.github.io/api/)

To start shared access from the phone, both devices need the same NextDNS
profile and the phone needs DNS on. After a lost Start reply, the phone checks
the matching grant ID. **Check status** reads the existing grant. At expiry,
**Restoration pending** means the phone is still waiting for the laptop to
confirm cleanup. The laptop also retries on its own and records the time past
the scheduled expiry in its private diagnostics. The TV has no Ask sheet.

## When the assistant is slow or sends an invalid answer

Ask can make up to three model calls on the laptop. It starts a second sample after 2.5 s
if the first is still running. The first usable decision wins. A reply that
still fails validation after normalization gets one more sample. Transient
network and HTTP failures can also retry. Authentication and configuration
errors and explicit model refusals stop the current route. Rate limits and
other transient HTTP responses can specify a delay if it fits the time left.

| Timing | Budget |
| --- | ---: |
| Gateway connection and single model call | 1.5 s to connect, 5.5 s total |
| Second sample starts while the first is running | 2.5 s |
| Retry delay | 100–200 ms, or the server’s longer Retry-After |
| Windows laptop Ask | Up to 9 s |
| New Android phone waiting for the laptop | Up to 9.7 s per response |
| Laptop retry budget for old phones | 4.7 s, reserving time within the old 6.6 s read and 9.5 s overall budgets |
| Android Ask across discovery and the laptop response | 15 s total |

The [shared policy](../shared/ask-retry.json) supplies both builds. The phone
advertises its remaining read budget after connecting, and the laptop leaves
450 ms for the response. A phone can resend an Ask to the same laptop after
a connection failure before any command bytes were written. Late decisions
are discarded. The laptop returns the same live ticket for the same Ask ID
and rejects an expired resend. The laptop reuses its Gateway connection pool.

Normalization removes irrelevant fields, collapses repeated services, and
repairs message text. Positive durations and scoped services must come from
the model and pass the existing caps and service catalog. If Ask fails, the
request stays in the composer and Ask is ready immediately. Selecting Full
pause brings up the usual confirmation and any required wait and plan.

## What can open

The laptop can scope access to the configured service catalog or to an ordinary
domain named in the request and eligible after the NextDNS check. The default
catalog contains these services.

| Category | Services |
| --- | --- |
| Messaging and collaboration | Discord, WhatsApp Web, Telegram, Slack |
| Code | GitHub |
| Video and social | YouTube, Reddit, X, Facebook, Instagram |

An approved service opens the mapped domains for that service, including their
subdomains. For example, YouTube access covers the service as a whole. Scope does not change separate
feed locks or unblock native apps individually. The laptop validates each
proposed domain against the eligible names from the current request.
[Default domain mapping](../app/src/services.json)

## Edit the services

On Windows, edit `%LOCALAPPDATA%\FocusDNS\services.json`. Each service ID maps
to the domain names the assistant may open. For example:

```json
{
  "discord": ["discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media"]
}
```

NextDNS allowlist entries cover subdomains. Ask again after changing the
domains for a pending decision. Private Tailscale MagicDNS hostnames need a
**Rewrite** mapping the name to the device's Tailscale IP when using the
NextDNS resolver. An allowlist entry only changes filtering.
[Tailscale MagicDNS](https://tailscale.com/docs/features/magicdns)

## Testing the judge

The [benchmark](../scripts/benchmark-pause-judge.py) ships with 133 fictional
cases. It uses the laptop prompt and wire schema with synthetic contexts. It
reads no Focus logs or app settings. The former phone-direct prompt remains
available with `--route phone` for historical comparisons.

With Python 3.10 or newer and `AI_GATEWAY_API_KEY` set, run the shared judge:

```bash
python3 scripts/benchmark-pause-judge.py --output build/pause-judge-eval/full.json --website
```

Open `build/pause-judge-eval/index.html` in a browser. The page works from disk
and starts with the widest run from the most recent commit in the report directory.
Filter by run, model, route, reasoning and history. Select a case to see
its expected decision, model response, scoring and Gateway context. **Suite**
shows the current fixtures and their expected outcomes before any run.

Give future runs a different JSON filename in the same directory. The
`--website` flag rebuilds the page with all reports in that directory.
To rebuild the website from the current suite and existing reports without
making Gateway calls, run `python3 scripts/benchmark-pause-judge.py --website-only`.
**Open reports** imports additional JSON reports into the current browser tab.

Use the same cases to compare reasoning and the value of prior outcomes:

```bash
python3 scripts/benchmark-pause-judge.py --category history \
  --reasoning default --reasoning none --history both \
  --output build/pause-judge-eval/comparison.json --website
```

- `--dump-payloads` validates fixture expectations and prints request bodies offline.
  Use `--case 'dns-*'` or `--category` to narrow a run.
- `--history off` clears prior reasons, matched outcomes and ratings. Today's
  usage, current check-in, habits, DNS evidence and last-hour activity stay in
  the prompt. The original case expectations stay fixed.
- The report records the checked-out source commit, hashes of the judge
  inputs, every decision, failure, request latency, token counts
  and cost when the Gateway provides them. Passes require the expected
  action, exact scope, minimum and maximum duration, and required Store/habit
  guidance where applicable. The report flags unexpected access, unrelated
  targets and grants above the case's allowed duration as overgrants. It also
  tracks copy and schema conformance and scores estimated app-normalized
  decisions.
- Each sample tests one Gateway response, with optional transport retries.
  The latency comparison uses the app's 5.5-second single-attempt budget.
  Local validation and normalization are modeled from the app code. This run
  does not exercise hedging, phone-to-laptop routing, local Peek, holds, or DNS
  changes. A few deliberate model probes use requests the UI handles locally.

Run the offline checks with `python3 -m unittest discover -s tests -p test_pause_judge_benchmark.py`.

## Developer interface

The Tauri commands are `judge_pause({request: {text, check_in?}})` and
`apply_decision({input: {id, override_full?, manual_minutes?, hold_id?, after_plan?}})`.
A decision ID is short-lived and single-use. Use `begin_pause_hold({id, overrideFull})`
when a hold begins, the returned `hold_id` when applying, and
`cancel_pause_hold({id, holdId})` on early release. The server checks the wait,
hold, plan, and current limits. The Peek response includes the requested
post URL for the static viewer.

On the paired laptop's authenticated `POST /v1/command` endpoint, use the same
command names in `action` and the fields `override_full` and `hold_id`. These
commands return their own results. Android sends `target: "phone"` for a
phone-only pause. A scope Start can be checked with
`{action: "scoped_access_status", id}`. Repeating a successful Start for an
active grant returns the existing deadline. Existing controls still return a
snapshot.
[Model prompt](../app/src/judge_prompt.txt) · [Structured outputs](https://vercel.com/docs/ai-gateway/sdks-and-apis/openai-chat-completions/structured-outputs)
