import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  canonicalPayload,
  generateKeypair,
  sha256File,
  signManifest,
  verifyManifest,
} from "../scripts/sign-nightly.mjs";

test("sign and verify nightly manifest with app and bridge binaries", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "focus-nightly-"));
  try {
    const appPath = path.join(dir, "FocusDNS.exe");
    const bridgePath = path.join(dir, "focus-browser-host.exe");
    fs.writeFileSync(appPath, Buffer.from("fake-focus-dns-binary-v1"));
    fs.writeFileSync(bridgePath, Buffer.from("fake-bridge-binary-v1"));

    const { privateKeyBase64, publicKeyHex } = generateKeypair();
    assert.match(publicKeyHex, /^[0-9a-f]{64}$/);

    const base = {
      version: 1,
      channel: "nightly",
      build_id: "abc1234",
      commit_sha: "abc1234567890abcdef1234567890abcdef12345",
      commit_subject: "fix: keep overlay above taskbar",
      published_at: "2026-11-19T05:30:00Z",
      assets: {
        app: sha256File(appPath),
        bridge: sha256File(bridgePath),
      },
    };

    const signed = signManifest(base, privateKeyBase64);
    assert.match(signed.signature, /^[0-9a-f]{128}$/);
    assert.equal(
      verifyManifest(signed, publicKeyHex, { app: appPath, bridge: bridgePath }),
      true,
    );

    // Tampering with commit_sha must fail signature verification.
    assert.throws(
      () => verifyManifest({ ...signed, build_id: "zzz9999" }, publicKeyHex),
      /signature verification failed/i,
    );

    // Wrong public key must fail.
    const other = generateKeypair();
    assert.throws(
      () => verifyManifest(signed, other.publicKeyHex),
      /signature verification failed/i,
    );

    // Tampered binary must fail hash verification.
    fs.writeFileSync(appPath, Buffer.from("tampered-binary"));
    assert.throws(
      () => verifyManifest(signed, publicKeyHex, { app: appPath }),
      /hash or size mismatch/i,
    );
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("canonical payload stays deterministic with and without bridge", () => {
  const withoutBridge = canonicalPayload({
    build_id: "1234567",
    commit_sha: "a".repeat(40),
    published_at: "2026-11-19T00:00:00Z",
    assets: { app: { name: "FocusDNS.exe", size: 42, sha256: "b".repeat(64) } },
  });
  assert.equal(
    withoutBridge,
    [
      "focus-nightly-v1",
      "build_id=1234567",
      `commit_sha=${"a".repeat(40)}`,
      "published_at=2026-11-19T00:00:00Z",
      `app=FocusDNS.exe:42:${"b".repeat(64)}`,
      "bridge=none",
    ].join("\n"),
  );
});

test("release notes are display-only and never change the signature", async () => {
  const { readNotes } = await import("../scripts/sign-nightly.mjs");
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "focus-notes-"));
  try {
    const file = path.join(dir, "notes.json");
    fs.writeFileSync(file, JSON.stringify({ items: [["Simpler stats", "One calm view."], ["x"], "bad", ["a", "b"], ["c", "d"]], more: "Plus 2 more fixes." }));
    const notes = readNotes(file);
    assert.equal(notes.items.length, 3);
    assert.deepEqual(notes.items[0], ["Simpler stats", "One calm view."]);
    assert.deepEqual(notes.items[1], ["x", ""]);
    assert.equal(notes.more, "Plus 2 more fixes.");
    assert.deepEqual(readNotes(path.join(dir, "missing.json")), { items: [], more: "" });

    const m = { version: 1, channel: "nightly", build_id: "abc", commit_sha: "a".repeat(40), published_at: "2026-11-22T00:00:00Z", assets: { app: { name: "FocusDNS.exe", size: 1, sha256: "b".repeat(64) } } };
    assert.equal(canonicalPayload(m), canonicalPayload({ ...m, notes }));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("release notes fall back to cleaned commit subjects", async () => {
  const { fallbackNotes, validNotes } = await import("../scripts/release-notes.mjs");
  const n = fallbackNotes([
    { subject: "feat: add built-in nightly updater for Android phone (#13)" },
    { subject: "fix(ui): improve Android browser site tracking (#14)" },
    { subject: "chore: bump deps" },
    { subject: "docs: readme" },
  ]);
  assert.deepEqual(n.items[0], ["Add built-in nightly updater for Android phone", ""]);
  assert.deepEqual(n.items[1], ["Improve Android browser site tracking", ""]);
  assert.equal(n.more, "Plus 1 more change.");
  assert.ok(validNotes({ items: [["Simpler stats", "One calm view."]], more: "" }));
  assert.ok(!validNotes({ items: [] }));
});
