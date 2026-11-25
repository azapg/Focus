#!/usr/bin/env node
// Signs and verifies FocusDNS nightly release manifests using Ed25519 + SHA-256.
// Uses only Node's built-in node:crypto so CI needs zero extra dependencies.

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

const ED25519_SPKI_PREFIX = Buffer.from("302a300506032b6570032100", "hex");

export function sha256File(filePath) {
  const buf = fs.readFileSync(filePath);
  return {
    name: path.basename(filePath),
    size: buf.length,
    sha256: crypto.createHash("sha256").update(buf).digest("hex"),
  };
}

/** Reads release notes and keeps only the shape the apps render: up to 3 [title, sentence] items. */
export function readNotes(file) {
  try {
    const n = JSON.parse(fs.readFileSync(file, "utf8"));
    const items = (Array.isArray(n.items) ? n.items : [])
      .filter((i) => Array.isArray(i) && typeof i[0] === "string")
      .slice(0, 3)
      .map((i) => [String(i[0]).slice(0, 40), String(i[1] || "").slice(0, 140)]);
    return { items, more: typeof n.more === "string" ? n.more.slice(0, 60) : "" };
  } catch {
    return { items: [], more: "" };
  }
}

export function canonicalPayload(manifest) {
  const app = manifest.assets?.app;
  if (!app?.name || !Number.isInteger(app.size) || !app.sha256) {
    throw new Error("Manifest missing required app asset metadata");
  }
  const bridge = manifest.assets?.bridge;
  const bridgeLine =
    bridge && bridge.name && Number.isInteger(bridge.size) && bridge.sha256
      ? `${bridge.name}:${bridge.size}:${bridge.sha256}`
      : "none";
  return [
    "focus-nightly-v1",
    `build_id=${manifest.build_id}`,
    `commit_sha=${manifest.commit_sha}`,
    `published_at=${manifest.published_at}`,
    `app=${app.name}:${app.size}:${app.sha256}`,
    `bridge=${bridgeLine}`,
  ].join("\n");
}

export function generateKeypair() {
  const { privateKey, publicKey } = crypto.generateKeyPairSync("ed25519");
  const privPem = privateKey.export({ type: "pkcs8", format: "pem" });
  const pubSpki = publicKey.export({ type: "spki", format: "der" });
  if (
    pubSpki.length !== 44 ||
    !pubSpki.subarray(0, 12).equals(ED25519_SPKI_PREFIX)
  ) {
    throw new Error("Unexpected Ed25519 SPKI encoding");
  }
  return {
    privateKeyBase64: Buffer.from(privPem, "utf8").toString("base64"),
    publicKeyHex: pubSpki.subarray(12).toString("hex"),
  };
}

function loadPrivateKey(raw) {
  const trimmed = raw.trim();
  if (trimmed.includes("BEGIN PRIVATE KEY")) {
    return crypto.createPrivateKey(trimmed);
  }
  const decoded = Buffer.from(trimmed, "base64");
  const asText = decoded.toString("utf8");
  if (asText.includes("BEGIN PRIVATE KEY")) {
    return crypto.createPrivateKey(asText);
  }
  return crypto.createPrivateKey({ key: decoded, format: "der", type: "pkcs8" });
}

function loadPublicKeyFromHex(pubkeyHex) {
  const raw = Buffer.from(pubkeyHex.trim(), "hex");
  if (raw.length !== 32) {
    throw new Error("Ed25519 public key must be 32 bytes (64 hex chars)");
  }
  return crypto.createPublicKey({
    key: Buffer.concat([ED25519_SPKI_PREFIX, raw]),
    format: "der",
    type: "spki",
  });
}

export function signManifest(manifest, privateKeyInput) {
  const key = loadPrivateKey(privateKeyInput);
  const payload = Buffer.from(canonicalPayload(manifest), "utf8");
  const sig = crypto.sign(null, payload, key).toString("hex");
  return { ...manifest, signature: sig };
}

export function verifyManifest(manifest, pubkeyHex, files = {}) {
  if (!manifest?.signature || !/^[0-9a-f]{128}$/i.test(manifest.signature)) {
    throw new Error("Manifest signature missing or malformed");
  }
  const key = loadPublicKeyFromHex(pubkeyHex);
  const payload = Buffer.from(canonicalPayload(manifest), "utf8");
  const sig = Buffer.from(manifest.signature, "hex");
  if (!crypto.verify(null, payload, key, sig)) {
    throw new Error("Ed25519 signature verification failed");
  }
  if (files.app) {
    const actual = sha256File(files.app);
    if (
      actual.size !== manifest.assets.app.size ||
      actual.sha256 !== manifest.assets.app.sha256
    ) {
      throw new Error("App binary hash or size mismatch");
    }
  }
  if (files.bridge && manifest.assets.bridge) {
    const actual = sha256File(files.bridge);
    if (
      actual.size !== manifest.assets.bridge.size ||
      actual.sha256 !== manifest.assets.bridge.sha256
    ) {
      throw new Error("Bridge binary hash or size mismatch");
    }
  }
  return true;
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith("--")) {
      out[a.slice(2)] = argv[i + 1];
      i++;
    }
  }
  return out;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
) {
  const [cmd, ...rest] = process.argv.slice(2);
  const args = parseArgs(rest);
  if (cmd === "keygen") {
    const pair = generateKeypair();
    console.log(JSON.stringify(pair, null, 2));
  } else if (cmd === "sign") {
    const priv = process.env.FOCUS_UPDATER_SIGNING_KEY;
    if (!priv) {
      console.error("FOCUS_UPDATER_SIGNING_KEY env var is required");
      process.exit(1);
    }
    if (!args.app || !args.commit || !args["build-id"] || !args.out) {
      console.error(
        "Usage: sign-nightly.mjs sign --app <exe> [--bridge <exe>] --commit <sha> --build-id <id> [--subject <msg>] [--notes <notes.json>] [--published-at <iso>] --out <nightly.json>",
      );
      process.exit(1);
    }
    const appAsset = sha256File(args.app);
    const bridgeAsset = args.bridge ? sha256File(args.bridge) : null;
    const subject = args.subject ?? process.env.FOCUS_COMMIT_SUBJECT ?? "";
    const manifest = signManifest(
      {
        version: 1,
        channel: "nightly",
        build_id: args["build-id"],
        commit_sha: args.commit.toLowerCase(),
        commit_subject: subject.slice(0, 140),
        // Plain-language release notes (scripts/release-notes.mjs). Display-only: not part of the
        // signed payload, so older clients that don't know this field keep verifying updates.
        ...(args.notes ? { notes: readNotes(args.notes) } : {}),
        published_at: args["published-at"] || new Date().toISOString(),
        assets: {
          app: appAsset,
          ...(bridgeAsset ? { bridge: bridgeAsset } : {}),
        },
      },
      priv,
    );
    fs.writeFileSync(args.out, JSON.stringify(manifest, null, 2) + "\n", "utf8");
    console.log(`Signed ${args.out} (${manifest.build_id})`);
  } else if (cmd === "verify") {
    if (!args.manifest || !args.pubkey) {
      console.error(
        "Usage: sign-nightly.mjs verify --manifest <nightly.json> --pubkey <hex> [--app <exe>] [--bridge <exe>]",
      );
      process.exit(1);
    }
    const manifest = JSON.parse(fs.readFileSync(args.manifest, "utf8"));
    verifyManifest(manifest, args.pubkey, {
      app: args.app,
      bridge: args.bridge,
    });
    console.log(`Verified ${manifest.build_id} (${manifest.commit_sha})`);
  } else {
    console.error("Unknown command. Use: keygen | sign | verify");
    process.exit(1);
  }
}
