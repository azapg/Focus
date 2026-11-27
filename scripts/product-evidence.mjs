import { cp, mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { execFileSync } from "node:child_process";

const root = resolve(import.meta.dirname, "..");
const output = resolve(process.argv[2] ?? resolve(root, "artifacts/product/index.html"));
const built = process.argv.includes("--built");
const commit = execFileSync("git", ["rev-parse", "HEAD"], { cwd: root, encoding: "utf8" }).trim();
const sourcePaths = [
  "ui/widget.js",
  "ui/src/devices.tsx",
  "ui/src/wallet-store.tsx",
  "tests/wallet-store.py",
  "docs/wallet.md",
  "scripts/product-evidence.mjs",
];
const nativePaths = ["docs/product/native/phone/manifest.json", "docs/product/native/tv/manifest.json"];
const manifests = {};
for (const path of nativePaths) {
  const manifest = JSON.parse(await readFile(resolve(root, path), "utf8"));
  const platform = path.includes("/phone/") ? "phone" : "tv";
  if (manifest.schema !== 1 || manifest.platform !== platform || !Array.isArray(manifest.steps)) {
    throw new Error(`Invalid product walk manifest: ${path}`);
  }
  for (const step of manifest.steps) {
    if (typeof step.title !== "string" || typeof step.image !== "string" || step.image.includes("/") || step.image.includes("\\")) {
      throw new Error(`Invalid step in ${path}; image must be a relative filename`);
    }
    await readFile(resolve(root, `docs/product/native/${platform}/${step.image}`));
  }
  manifests[platform] = manifest;
}

let html = await readFile(resolve(root, "docs/product/index.html"), "utf8");
for (const path of sourcePaths) {
  await readFile(resolve(root, path));
}
const nativeEvidence = Object.values(manifests).some((manifest) => manifest.steps.length > 0);
if (output !== resolve(root, "docs/product/index.html")) {
  for (const path of sourcePaths) {
    html = html.replaceAll(`href="../../${path}`, `href="https://github.com/azapg/Focus/blob/${commit}/${path}`);
  }
}
html = html
  .replace(/(<meta name="product-source-commit" content=")[^"]*(">)/, `$1${commit}$2`)
  .replace(/(<code id="checkedOut">)[^<]*(<\/code>)/, `$1${commit}$2`)
  .replace(/<span id="buildStatus">[^<]*<\/span>/, `<span id="buildStatus">${built ? `Vite asset build passed at ${commit.slice(0, 7)} (CI).` : "Vite asset build not captured locally."}</span>`)
  .replace(/<script type="application\/json" id="native-walks">[\s\S]*?<\/script>/, `<script type="application/json" id="native-walks">${JSON.stringify(manifests).replace(/</g, "\\u003c")}</script>`)
  .replace("Native phone/TV captures are missing; no device build is claimed.", nativeEvidence
    ? "Native walks are embedded from the sibling manifests; source commits and build capture status are shown per walk."
    : "Native phone/TV captures are missing; no device build is claimed.");
await mkdir(dirname(output), { recursive: true });
if (output !== resolve(root, "docs/product/index.html")) {
  for (const platform of ["phone", "tv"]) {
    await cp(resolve(root, `docs/product/native/${platform}`), resolve(dirname(output), `native/${platform}`), { recursive: true });
  }
}
await writeFile(output, html);
console.log(`Product entry refreshed from ${commit}; Vite build ${built ? "passed" : "not captured"}; native walks ${nativeEvidence ? "present" : "missing"}; wrote ${output}`);
