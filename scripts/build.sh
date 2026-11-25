#!/usr/bin/env bash
# Build on a native Windows disk. No installation or DNS changes.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WINHOME=$(wslpath "$(/mnt/c/Windows/System32/cmd.exe /c 'echo %USERPROFILE%' 2>/dev/null | tr -d '\r')")
OUT="${FOCUS_BUILD_DIR:-$WINHOME/.focusdns-build}"
BUILD_ID=$(git -C "$ROOT" describe --always --dirty)
COMMIT_SHA=$(git -C "$ROOT" rev-parse HEAD)
UPDATER_REPO=$(cd "$ROOT" && bash scripts/updater-repo.sh)
npm --prefix "$ROOT" ci --no-audit --no-fund
npm --prefix "$ROOT" run build
mkdir -p "$OUT"
rsync -a --delete --exclude target --exclude gen \
  "$ROOT/.cargo" "$ROOT/app" "$ROOT/ui-dist" "$ROOT/scripts" "$ROOT/browser-host" "$ROOT/devices" \
  "$ROOT/telemetry" "$ROOT/extension" "$ROOT/tests" "$OUT/"
install -D "$ROOT/ui/activity-classifier.js" "$OUT/ui/activity-classifier.js"
(cd /mnt/c && /mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe -NoProfile -Command \
  "\$env:FOCUS_BUILD_ID = '$BUILD_ID'; \$env:FOCUS_COMMIT_SHA = '$COMMIT_SHA'; \$env:FOCUS_UPDATER_REPO = '$UPDATER_REPO'; Set-Location '$(wslpath -w "$OUT/app")'; cargo build --release --manifest-path ../browser-host/Cargo.toml 2>&1 | % { \"\$_\" }; if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }; \$env:FOCUS_PEEK_WORKER = (Resolve-Path '../browser-host/target/release/focus-browser-host.exe').Path; cargo build --release 2>&1 | % { \"\$_\" }; exit \$LASTEXITCODE" </dev/null)
echo "built: $(wslpath -w "$OUT/app/target/release/focus-dns.exe")"
echo "bridge: $(wslpath -w "$OUT/browser-host/target/release/focus-browser-host.exe")"
