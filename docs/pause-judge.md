# Ask to pause

A five-minute Discord check can turn into twenty minutes on X if a pause lifts
every restriction. **Ask** lets you describe the task and open only
the services you need.

## Try it

1. On Windows, open **Settings → Laptop → Pause assistant**. Save a Vercel AI
   Gateway key to turn on the Ask pill. Save a NextDNS API key with write
   access to the profile configured in Focus to open individual services.
2. On the phone, tap **Ask**. A paired laptop can answer over the local
   network. On the Windows laptop, open **Settings → Network & internet →
   Wi-Fi**, select your connected home network, and choose **Private network**
   under **Network profile type**. Keep both devices on that network. Public
   Wi-Fi support is a follow-up. To get a decision when the laptop is
   unreachable, save your Gateway key under **Settings → Protection → Pause
   assistant** on the phone.
   [Microsoft network settings](https://support.microsoft.com/en-us/windows/experience/connectivity-networking/essential-network-settings-and-tasks-in-windows)
3. Type “I need to share a link on Discord” and tap **Ask**. The composer
   becomes a review pill with the proposed service logos and duration.
   **Edit** restores your request. **Start** opens the services and shows the
   timer. Check that Discord opens and an unrelated blocked site stays blocked.
4. Try a request for a full pause, a counter-offer, or a deny. A full pause
   requires a hold. The **⋯** button beside a scoped offer opens the full-pause
   override. Other decisions show the override below the pill. Overrides are
   recorded. Follow-up ratings appear after full pauses.
5. Submit a single post link in **Ask** to open [Peek](browser-activity.md#peek-at-a-blocked-post).
   This path works without a Gateway key.

The default model on both devices is `anthropic/claude-haiku-5.5`. Enter a
different model ID in the same settings, such as `openai/gpt-6-luna`. Luna can
take longer to respond. Windows protects stored keys with DPAPI and Android
protects its Gateway key with Android Keystore.
[Vercel model catalog](https://vercel.com/ai-gateway/models)

## What the assistant sees

The Gateway receives your request, local time, today's pause count, minutes
and reasons, the last hour's top apps and hostnames, and outcomes from similar
pauses when available. Check-ins and numeric ratings help provide context.
It receives hostnames from links. Window titles, URL paths and credentials,
and notes you add to ratings stay on your devices.

Windows records decisions and applications in `pauses.jsonl`. Android records
Ask stages, decision actions, and manual overrides in its own app data without
request text in those diagnostic events.
Scoped access stays separate from DNS-off time. On Android, choose **Full pause**
in the Ask sheet to see the durations your pause policy allows. A reason, wait,
return plan, and three-second hold still apply. On Windows, a failed request
offers a one-minute pause with its existing checks. Windows keeps the Ask pill
after you remove a saved Gateway key.

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

The phone uses a reachable paired laptop to apply scoped access when both are
configured for the same NextDNS profile and the phone has DNS enabled with no
active pause. Scoped access can proceed while Android is validating Private
DNS. When the phone asks the Gateway directly, it can receive a full-pause
proposal of up to five minutes or a suggestion for another way to proceed.
The TV has no request sheet.

## When the assistant is slow or sends an invalid answer

Ask can make up to three model calls per route. It starts a second sample after 2.5 s
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
| Android Ask across discovery, laptop, and direct Gateway | 15 s total, with 3 s reserved for direct fallback when asking for the phone |

The [shared policy](../shared/ask-retry.json) supplies both builds. The phone
advertises its remaining read budget after connecting, and the laptop leaves
450 ms for the response. A phone can resend an Ask to the same laptop after
a connection failure before any command bytes were written. After a possible
send, the phone can use its direct Gateway for its own pause. The phone
records the abandoned route with the Ask ID and ignores late laptop replies.
New laptops return the same live ticket for the same Ask ID and reject an
expired resend. An older laptop may keep an unused ticket until it expires. Only the decision shown on the phone
can be applied there. Apply, begin-hold, and cancel use a single route and
attempt. A refusal stops the Ask. Older laptops can report a refusal as an
invalid reply, so the phone keeps that result on the laptop route.

The laptop reuses its Gateway connection pool. Android can warm its pool with
an unauthenticated HEAD request when opening a local Ask sheet.

Normalization removes irrelevant fields, collapses repeated services, and
repairs message text. Positive durations and scoped services must come from
the model and pass the existing caps and service catalog. If Ask fails, the
request stays in the composer and Ask is ready immediately. Selecting Full
pause brings up the usual confirmation and any required wait and plan.

## What can open

Scoped access uses only the configured service catalog. The defaults are:

| Category | Services |
| --- | --- |
| Messaging and collaboration | Discord, WhatsApp Web, Telegram, Slack |
| Code | GitHub |
| Video and social | YouTube, Reddit, X, Facebook, Instagram |

An approved service opens the mapped domains for that service, including their
subdomains. For example, YouTube access covers the service as a whole. Scope does not change separate
feed locks or unblock native apps individually. The judge cannot add an
unlisted domain to a decision. Tailscale and arbitrary sites have no built-in
scoped entry. [Default domain mapping](../app/src/services.json)

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

The [benchmark](../scripts/benchmark-pause-judge.py) sends invented requests to
the configured Gateway model and reports the decisions, latency and cost.
Run it with an AI_GATEWAY_API_KEY when you want a live evaluation. It exercises
the model response only. DNS propagation and expiry need separate verification.

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
commands return their own results. Existing controls still return a snapshot.
[Model prompt](../app/src/judge_prompt.txt) · [Structured outputs](https://vercel.com/docs/ai-gateway/sdks-and-apis/openai-chat-completions/structured-outputs)
