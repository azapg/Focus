import { createHash } from "node:crypto";
import { readFile, readdir, stat, writeFile } from "node:fs/promises";
import { basename, dirname, relative, resolve, sep } from "node:path";
import { execFileSync } from "node:child_process";

const root = resolve(import.meta.dirname, "..");
const build = resolve(root, "ui-dist");
const output = resolve(root, "docs/product/index.html");
const check = process.argv.includes("--check");
const mime = { ".png": "image/png", ".svg": "image/svg+xml", ".woff2": "font/woff2", ".woff": "font/woff", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp" };

async function filesIn(path) {
  const found = [];
  for (const entry of await readdir(path, { withFileTypes: true })) {
    const full = resolve(path, entry.name);
    if (entry.isDirectory()) found.push(...await filesIn(full));
    else if (entry.isFile()) found.push(full);
  }
  return found.sort();
}

async function fingerprint(paths, base) {
  const hash = createHash("sha256");
  for (const path of paths) {
    hash.update(relative(base, path).split(sep).join("/")).update("\0");
    hash.update(await readFile(path)).update("\0");
  }
  return hash.digest("hex");
}

async function dataUri(path) {
  const bytes = await readFile(path);
  return `data:${mime[resolve(path).slice(resolve(path).lastIndexOf("."))] ?? "application/octet-stream"};base64,${bytes.toString("base64")}`;
}

async function inlineCssAssets(css, cssPath) {
  const matches = [...css.matchAll(/url\(([^)]+)\)/g)];
  for (const match of matches.reverse()) {
    let ref = match[1].trim().replace(/^['"]|['"]$/g, "");
    if (/^(data:|https?:|#)/i.test(ref)) continue;
    ref = decodeURIComponent(ref.split(/[?#]/, 1)[0]);
    const asset = ref.startsWith("/") ? resolve(build, ref.slice(1)) : resolve(dirname(cssPath), ref);
    if (!asset.startsWith(build + sep)) throw new Error(`CSS asset escapes ui-dist: ${ref}`);
    const value = await dataUri(asset);
    css = css.slice(0, match.index) + `url("${value}")` + css.slice(match.index + match[0].length);
  }
  return css;
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);
}

async function nativeWalks() {
  const output = [];
  for (const platform of ["phone", "tv"]) {
    const dir = resolve(root, `docs/product/native/${platform}`);
    const manifest = JSON.parse(await readFile(resolve(dir, "manifest.json"), "utf8"));
    if (manifest.schema !== 1 || manifest.platform !== platform || !Array.isArray(manifest.steps)) {
      throw new Error(`Invalid native manifest: ${platform}`);
    }
    if (manifest.source_commit !== null && typeof manifest.source_commit !== "string") {
      throw new Error(`Invalid source_commit in ${platform} manifest`);
    }
    const steps = [];
    for (const step of manifest.steps) {
      if (typeof step.title !== "string" || typeof step.image !== "string" || !step.image || step.image.includes("/") || step.image.includes("\\")) {
        throw new Error(`Native images must be relative filenames (${platform})`);
      }
      const image = resolve(dir, step.image);
      const imageData = await dataUri(image);
      steps.push({ title: step.title, image: imageData });
    }
    let status = steps.length === 0 || !manifest.source_commit ? "missing" : "captured";
    if (status === "captured") {
      try {
        execFileSync("git", ["merge-base", "--is-ancestor", manifest.source_commit, "HEAD"], { cwd: root, stdio: "ignore" });
        execFileSync("git", ["diff", "--quiet", manifest.source_commit, "HEAD", "--", "android", "shared/android", ...(platform === "tv" ? ["tv"] : [])], { cwd: root, stdio: "ignore" });
      } catch {
        status = "stale";
      }
    }
    output.push({ platform, sourceCommit: manifest.source_commit, status, steps });
  }
  return output;
}

const builtIndex = resolve(build, "index.html");
try { await stat(builtIndex); }
catch { throw new Error("Vite output is missing. Run npm ci && npm run build before refreshing the product entry."); }

const uiSourcePaths = [
  ...(await filesIn(resolve(root, "ui"))),
  resolve(root, "vite.config.ts"),
  resolve(root, "package.json"),
  resolve(root, "package-lock.json"),
];
const builtFiles = [builtIndex, ...(await filesIn(build)).filter((file) => file !== builtIndex)];
const sourceHash = await fingerprint(uiSourcePaths, root);
const buildHash = await fingerprint(builtFiles, build);

let html = await readFile(builtIndex, "utf8");
const scripts = [...html.matchAll(/<script\b[^>]*type="module"[^>]*src="([^"]+)"[^>]*>\s*<\/script>/g)];
const styles = [...html.matchAll(/<link\b[^>]*rel="stylesheet"[^>]*href="([^"]+)"[^>]*>/g)];
if (scripts.length !== 1 || styles.length !== 1) {
  throw new Error(`Expected one built JS entry and one stylesheet in ${builtIndex}; found ${scripts.length} JS and ${styles.length} CSS files.`);
}

const scriptPath = resolve(build, scripts[0][1]);
let script = await readFile(scriptPath, "utf8");
for (const asset of builtFiles.filter((file) => !file.endsWith(".js") && !file.endsWith(".css") && file !== builtIndex)) {
  const uri = await dataUri(asset);
  const assetPath = relative(build, asset).split(sep).join("/");
  for (const ref of [`./${assetPath}`, assetPath, `/${assetPath}`, basename(asset)]) script = script.replaceAll(ref, uri);
}
const cssPath = resolve(build, styles[0][1]);
const css = await inlineCssAssets(await readFile(cssPath, "utf8"), cssPath);

const icon = resolve(build, "favicon.svg");
html = html.replace(/<link\b[^>]*rel="icon"[^>]*>/, `<link rel="icon" type="image/svg+xml" href="${await dataUri(icon)}">`);
html = html.replace(styles[0][0], `<style>${css}</style>`);
const sampleMode = `window.__FOCUS_PRODUCT_SAMPLE__ = new URLSearchParams(location.search).get("wallet") === "funded" ? "funded" : "zero";`;
const deviceFixture = await readFile(resolve(root, "tests/fixtures/device-panel.mock.js"), "utf8");
const walletFixture = await readFile(resolve(root, "tests/fixtures/wallet-store.mock.js"), "utf8");
// Keep the built bundle out of HTML's special raw-script parsing context. The
// app bundle contains literal script tags in a template, so inline JS would be
// truncated by the browser even if those tags are inside a JS string.
const builtModule = Buffer.from(script).toString("base64");
html = html.replace(scripts[0][0], `<script>${sampleMode}\n${deviceFixture}\n${walletFixture}</script><script type="module">import("data:text/javascript;base64,${builtModule}").catch((error) => { document.body.dataset.productError = String(error); console.error(error); });</script>`);
html = html.replace(/<link\b[^>]*rel="modulepreload"[^>]*>/g, "");

const native = await nativeWalks();
const nativeMarkup = native.map((walk) => {
  const name = walk.platform === "phone" ? "Phone" : "TV";
  const status = walk.status === "captured" ? `captured from ${escapeHtml(walk.sourceCommit)}` : walk.status;
  const shots = walk.steps.map((step) => `<figure><img src="${step.image}" alt="${escapeHtml(step.title)}"><figcaption>${escapeHtml(step.title)}</figcaption></figure>`).join("");
  return `<section><b>${name} evidence · ${status}</b>${shots}</section>`;
}).join("");

const evidence = `<aside id="focus-product-entry" style="position:fixed;z-index:9999;right:10px;top:10px;color:#fafafa;background:#09090b;border:1px solid #ffffff24;border-radius:10px;padding:7px 10px;font:11px/1.45 'Segoe UI',system-ui,sans-serif;max-width:min(390px,calc(100vw - 20px))"><details><summary style="cursor:pointer">Preview data · clean wallet by default</summary><p>Offline fixture · Fictional activity · no real purchase</p><p><a style="color:inherit" href="./index.html">Clean wallet · 0 coins</a> · <a style="color:inherit" href="./index.html?wallet=funded">Funded sample · 65 coins</a></p><p>Built Vite assets · SHA-256 <code>${buildHash}</code></p><p>UI source snapshot · SHA-256 <code>${sourceHash}</code></p><p>Shipped desktop package: not verified by this preview.</p><p>Today is the default Stats range. Store is shown on Today while the wallet is active.</p><nav><a style="color:inherit" href="../../ui/widget.js#L45">Today rule</a> · <a style="color:inherit" href="../../ui/src/devices.tsx#L546">Store visibility</a> · <a style="color:inherit" href="../../ui/src/wallet-store.tsx#L25">Store source</a> · <a style="color:inherit" href="../../tests/product-entry.py">Entry smoke test</a> · <a style="color:inherit" href="../../tests/wallet-store.py">Store test</a></nav><p><a style="color:inherit" href="../../docs/product/native/phone/manifest.json">Phone manifest</a> · <a style="color:inherit" href="../../docs/product/native/tv/manifest.json">TV manifest</a> · <a style="color:inherit" href="../../scripts/product-evidence.mjs">Generator</a></p>${nativeMarkup}</details></aside>`;
html = html.replace("<body>", `<body>\n${evidence}`);

if (check) {
  const current = await readFile(output, "utf8").catch(() => "");
  if (current !== html) {
    throw new Error("docs/product/index.html is stale relative to ui-dist and its fixtures. Run npm run product:entry and commit the result.");
  }
  console.log(`Product entry is current: UI source ${sourceHash}; Vite build ${buildHash}; native walks ${native.map(({ platform, status }) => `${platform} ${status}`).join(", ")}.`);
} else {
  await writeFile(output, html);
  console.log(`Wrote ${relative(root, output)} from ui-dist: UI source ${sourceHash}; Vite build ${buildHash}; native walks ${native.map(({ platform, status }) => `${platform} ${status}`).join(", ")}.`);
}
