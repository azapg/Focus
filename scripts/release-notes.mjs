#!/usr/bin/env node
// Writes short, plain-language release notes for a nightly build.
//
//   node scripts/release-notes.mjs --from <prev-sha> --to HEAD --out notes.json
//
// A small model (Claude Haiku via Vercel AI Gateway, key in AI_GATEWAY_API_KEY) turns the merged
// commits into at most three bullets. Without a key, or if the model fails, the notes fall back to
// the commit subjects, so a release never blocks on the summary. Cost is well under a cent per build.

import { execFileSync } from "node:child_process";
import fs from "node:fs";

const MODEL = process.env.RELEASE_NOTES_MODEL || "anthropic/claude-haiku-5.5";
const ENDPOINT = "https://ai-gateway.vercel.sh/v1/chat/completions";
const MAX_ITEMS = 3;

function arg(name, fallback = "") {
  const i = process.argv.indexOf(`--${name}`);
  const v = i > 0 ? process.argv[i + 1] : undefined;
  // PowerShell drops empty arguments, so "--from" may be followed directly by the next flag.
  return v && !v.startsWith("--") ? v : fallback;
}

export function readCommits(from, to) {
  const range = from ? `${from}..${to}` : `-n 20 ${to}`;
  let out = "";
  try {
    out = execFileSync("git", ["log", "--first-parent", "--no-merges", "--format=%s%x1f%b%x1e", ...range.split(" ")], {
      encoding: "utf8",
      maxBuffer: 4 * 1024 * 1024,
    });
  } catch {
    // An unknown previous commit (force-push, first release) falls back to the latest commits.
    out = execFileSync("git", ["log", "--first-parent", "--no-merges", "-n", "20", "--format=%s%x1f%b%x1e", to], { encoding: "utf8" });
  }
  return out
    .split("\x1e")
    .map((r) => r.trim())
    .filter(Boolean)
    .map((r) => {
      const [subject, body = ""] = r.split("\x1f");
      return { subject: subject.trim(), body: body.trim().split("\n").slice(0, 6).join(" ").slice(0, 400) };
    });
}

/** "feat: add built-in nightly updater for Android phone (#13)" -> ["Add built-in nightly updater for Android phone", ""] */
export function fallbackNotes(commits) {
  const clean = (s) => s.replace(/^\w+(\([^)]*\))?!?:\s*/, "").replace(/\s*\(#\d+\)\s*$/, "").trim();
  const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);
  const items = commits.slice(0, MAX_ITEMS).map((c) => [cap(clean(c.subject)), ""]);
  const rest = commits.length - items.length;
  return { items, more: rest > 0 ? `Plus ${rest} more ${rest === 1 ? "change" : "changes"}.` : "", source: "commits" };
}

export function validNotes(n) {
  return (
    n &&
    Array.isArray(n.items) &&
    n.items.length >= 1 &&
    n.items.length <= MAX_ITEMS &&
    n.items.every((i) => Array.isArray(i) && i.length === 2 && typeof i[0] === "string" && typeof i[1] === "string" && i[0].length <= 40 && i[1].length <= 140)
  );
}

async function summarize(commits, key) {
  const list = commits.map((c, i) => `${i + 1}. ${c.subject}${c.body ? ` — ${c.body}` : ""}`).join("\n");
  const prompt = `These commits are going out in a nightly update of Focus, a screen-time and DNS blocker app for Windows, Android phone and TV.

${list}

Write release notes for the person who uses the app. Rules:
- At most ${MAX_ITEMS} items, the most noticeable changes first. Skip tests, CI and internal refactors.
- Each item: a title of 1-4 words in sentence case (only the first word capitalized), and one plain sentence of at most 12 words saying what changed for them.
- No jargon, no file names, no PR numbers, no emojis.
- "more": "Plus N more fixes." counting the changes you left out, or "" if none.

Reply with only JSON: {"items": [["Title", "Sentence."]], "more": ""}`;

  const res = await fetch(ENDPOINT, {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ model: MODEL, max_tokens: 2500, messages: [{ role: "user", content: prompt }] }),
    signal: AbortSignal.timeout(30_000),
  });
  if (!res.ok) throw new Error(`gateway ${res.status}: ${(await res.text()).slice(0, 200)}`);
  const data = await res.json();
  const text = data.choices?.[0]?.message?.content || "";
  const json = JSON.parse(text.slice(text.indexOf("{"), text.lastIndexOf("}") + 1));
  if (!validNotes(json)) throw new Error(`model returned unusable notes: ${text.slice(0, 200)}`);
  return { items: json.items, more: typeof json.more === "string" ? json.more : "", source: MODEL, cost: data.usage };
}

async function main() {
  const from = arg("from");
  const to = arg("to", "HEAD");
  const out = arg("out", "notes.json");
  const commits = readCommits(from, to);
  let notes = fallbackNotes(commits);
  const key = process.env.AI_GATEWAY_API_KEY;
  if (key && commits.length) {
    try {
      notes = await summarize(commits, key);
    } catch (e) {
      console.warn(`release notes: falling back to commit subjects (${e.message})`);
    }
  }
  const { cost, ...clean } = notes;
  fs.writeFileSync(out, JSON.stringify(clean, null, 2) + "\n", "utf8");
  console.log(`release notes from ${commits.length} commits via ${notes.source}${cost ? ` (${cost.prompt_tokens} in / ${cost.completion_tokens} out tokens)` : ""}`);
  console.log(JSON.stringify(clean, null, 2));
}

if (import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith("release-notes.mjs")) {
  main().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
