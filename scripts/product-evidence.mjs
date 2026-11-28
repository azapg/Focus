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

let appHtml = await readFile(builtIndex, "utf8");
const scripts = [...appHtml.matchAll(/<script\b[^>]*type="module"[^>]*src="([^"]+)"[^>]*>\s*<\/script>/g)];
const styles = [...appHtml.matchAll(/<link\b[^>]*rel="stylesheet"[^>]*href="([^"]+)"[^>]*>/g)];
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
appHtml = appHtml.replace(/<link\b[^>]*rel="icon"[^>]*>/, `<link rel="icon" type="image/svg+xml" href="${await dataUri(icon)}">`);
appHtml = appHtml.replace(styles[0][0], `<style>${css}</style>`);
const sampleMode = `window.__FOCUS_PRODUCT_SAMPLE__ = window.parent !== window ? window.parent.__FOCUS_PRODUCT_SAMPLE__ : (new URLSearchParams(location.search).get("wallet") === "funded" ? "funded" : "zero");`;
const deviceFixture = await readFile(resolve(root, "tests/fixtures/device-panel.mock.js"), "utf8");
const walletFixture = await readFile(resolve(root, "tests/fixtures/wallet-store.mock.js"), "utf8");
// Keep the built bundle out of HTML's special raw-script parsing context. The
// app bundle contains literal script tags in a template, so inline JS would be
// truncated by the browser even if those tags are inside a JS string.
const builtModule = Buffer.from(script).toString("base64");
appHtml = appHtml.replace(scripts[0][0], `<script>${sampleMode}\n${deviceFixture}\n${walletFixture}</script><script type="module">import("data:text/javascript;base64,${builtModule}").catch((error) => { document.body.dataset.productError = String(error); console.error(error); });</script>`);
appHtml = appHtml.replace(/<link\b[^>]*rel="modulepreload"[^>]*>/g, "");

const native = await nativeWalks();
const nativeMarkup = native.map((walk) => {
  const name = walk.platform === "phone" ? "Phone" : "TV";
  const status = walk.status === "captured" ? `captured from ${escapeHtml(walk.sourceCommit)}` : walk.status;
  const shots = walk.steps.map((step) => `<figure><img src="${step.image}" alt="${escapeHtml(step.title)}"><figcaption>${escapeHtml(step.title)}</figcaption></figure>`).join("");
  return `<details class="native-evidence"><summary>${name} evidence · ${status}</summary><p><a href="../../docs/product/native/${walk.platform}/manifest.json">Open ${name} manifest</a></p>${shots}</details>`;
}).join("");

const shell = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Focus · Offline product preview</title>
<style>
*{box-sizing:border-box}body{margin:0;padding:20px;color:#18181b;background:#f4f4f5;font:14px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif}main{max-width:960px;margin:0 auto}.heading{display:flex;align-items:flex-end;justify-content:space-between;gap:16px;margin:0 0 12px}.heading h1{margin:0;font-size:18px;font-weight:600}.heading p{margin:2px 0 0;color:#52525b;font-size:12px}.samples{display:flex;align-items:center;gap:6px;flex-wrap:wrap;margin:0 0 10px}.samples button{min-height:34px;padding:6px 12px;border:1px solid #a1a1aa;border-radius:7px;background:#fff;color:#18181b;font:inherit;cursor:pointer}.samples button[aria-pressed="true"]{background:#18181b;color:#fff;border-color:#18181b}.samples button:focus-visible,a:focus-visible,summary:focus-visible{outline:2px solid #52525b;outline-offset:2px}.samples span{margin-left:auto;color:#52525b;font-size:12px}.app-frame{display:block;width:100%;height:660px;border:1px solid #a1a1aa;border-radius:14px;background:#050506}.evidence{display:grid;gap:8px;margin:12px 0 28px;padding:12px;border:1px solid #d4d4d8;border-radius:10px;background:#fff}.evidence summary,.native-evidence summary{cursor:pointer;font-weight:600}.evidence p{margin:8px 0;color:#52525b;font-size:12px}.evidence a{color:#18181b;text-underline-offset:2px}.links{display:flex;gap:8px 14px;flex-wrap:wrap;margin:8px 0}.fingerprint{overflow-wrap:anywhere}.native-evidence{padding-top:8px;border-top:1px solid #e4e4e7}.native-evidence figure{margin:10px 0;padding:10px;background:#f4f4f5;border-radius:8px;max-height:640px;overflow:auto}.native-evidence img{display:block;max-width:100%;height:auto;max-height:600px;object-fit:contain;object-position:top left}.native-evidence figcaption{margin-top:6px;color:#52525b;font-size:12px}
</style></head><body><main><header class="heading"><div><h1>Focus · Desktop preview</h1><p>Production desktop frontend · fictional activity · offline</p></div></header>
<div class="samples" role="group" aria-label="Wallet sample"><button type="button" data-sample="zero" aria-pressed="true">Clean wallet · 0 coins</button><button type="button" data-sample="funded" aria-pressed="false">Funded sample · 65 coins</button><span>No real purchase</span></div>
<iframe class="app-frame" id="focus-desktop-frame" title="Focus desktop Today and Store" loading="eager"></iframe>
<section class="evidence"><details><summary>Source, build and capture evidence</summary><p>Offline fixture. The controls above switch sample balances in this page.</p><p class="fingerprint">Built Vite assets · SHA-256 <code>${buildHash}</code></p><p class="fingerprint">UI source snapshot · SHA-256 <code>${sourceHash}</code></p><p>Checked-out frontend verified. Shipped desktop package: not verified.</p><p>Today is the default Stats range. Store appears on Today while the wallet is active.</p><nav class="links"><a href="../../ui/widget.js#L45">Today range rule</a><a href="../../ui/src/devices.tsx#L546">Store visibility</a><a href="../../ui/src/wallet-store.tsx#L25">Store source</a><a href="../../tests/product-entry.py">Entry smoke test</a><a href="../../tests/wallet-store.py">Store test</a><a href="../../scripts/product-evidence.mjs">Generator</a></nav>${nativeMarkup}</details></section></main>
<script>window.__FOCUS_PRODUCT_SAMPLE__=new URLSearchParams(location.search).get("wallet")==="funded"?"funded":"zero";const frame=document.getElementById("focus-desktop-frame");const appBytes=Uint8Array.from(atob("${Buffer.from(appHtml).toString("base64")}"),char=>char.charCodeAt(0));frame.srcdoc=new TextDecoder().decode(appBytes);function chooseSample(sample){window.__FOCUS_PRODUCT_SAMPLE__=sample;document.querySelectorAll("[data-sample]").forEach(button=>button.setAttribute("aria-pressed",String(button.dataset.sample===sample)));frame.contentWindow?.__FOCUS_SET_SAMPLE__?.(sample)}document.addEventListener("pointerdown",event=>{if(event.target.closest("[data-sample]"))event.preventDefault()});document.querySelectorAll("[data-sample]").forEach(button=>button.addEventListener("click",()=>chooseSample(button.dataset.sample)));frame.addEventListener("load",()=>frame.contentWindow?.__FOCUS_SET_SAMPLE__?.(window.__FOCUS_PRODUCT_SAMPLE__));chooseSample(window.__FOCUS_PRODUCT_SAMPLE__);</script></body></html>`;
const html = shell;
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
