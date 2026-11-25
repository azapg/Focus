#!/usr/bin/env bash
set -euo pipefail
# Release notes for the update sheet: NOTES_FILE (resolved before cd) or android/build/notes.json.
if [ -n "${NOTES_FILE:-}" ]; then
  NOTES_FILE="$(realpath -m "$NOTES_FILE")"
fi
cd "$(dirname "$0")"

if [ "${1:-}" = "--screenshot-ui" ] && [ -e build ]; then
  echo "Screenshot builds require a fresh build directory" >&2
  exit 1
fi

WIN_SDK="${FOCUS_PHONE_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}}"
SIGNING="${FOCUS_PHONE_SIGNING:-${XDG_DATA_HOME:-$HOME/.local/share}/focus-phone/signing}"

find_linux_bt() {
  local sdk dir
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${HOME}/Android/Sdk" "/usr/local/lib/android/sdk"; do
    [ -n "$sdk" ] && [ -d "$sdk/build-tools" ] || continue
    while IFS= read -r dir; do
      if [ -x "$dir/aapt" ] && [ -x "$dir/zipalign" ] && [ -f "$dir/lib/d8.jar" ] && [ -f "$dir/lib/apksigner.jar" ]; then
        printf '%s\n' "$dir"
        return 0
      fi
    done < <(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -r)
  done
  return 1
}

find_android_jar() {
  local sdk platform
  if [ -n "${FOCUS_PHONE_ANDROID:-}" ] && [ -f "${FOCUS_PHONE_ANDROID}" ]; then
    printf '%s\n' "${FOCUS_PHONE_ANDROID}"
    return 0
  fi
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${HOME}/Android/Sdk" "/usr/local/lib/android/sdk" "$WIN_SDK"; do
    [ -n "$sdk" ] && [ -d "$sdk/platforms" ] || continue
    for platform in android-36 android-36.1; do
      if [ -f "$sdk/platforms/$platform/android.jar" ]; then
        printf '%s\n' "$sdk/platforms/$platform/android.jar"
        return 0
      fi
    done
  done
  return 1
}

ANDROID_JAR="$(find_android_jar || true)"
if [ -z "$ANDROID_JAR" ]; then
  echo "Install Android SDK Platform 36 or set FOCUS_PHONE_ANDROID to its android.jar" >&2
  exit 1
fi

LINUX_BT="${FOCUS_ANDROID_BUILD_TOOLS:-$(find_linux_bt || true)}"
if [ -n "$LINUX_BT" ]; then
  AAPT="$LINUX_BT/aapt"
  ZIPALIGN="$LINUX_BT/zipalign"
  D8_JAR="$LINUX_BT/lib/d8.jar"
  APKSIGNER_JAR="$LINUX_BT/lib/apksigner.jar"
  LAMBDA_STUBS="$LINUX_BT/core-lambda-stubs.jar"
  USE_WIN=0
else
  WIN_BT="$WIN_SDK/build-tools/35.0.0"
  AAPT="$WIN_BT/aapt.exe"
  ZIPALIGN="$WIN_BT/zipalign.exe"
  D8_JAR="$WIN_BT/lib/d8.jar"
  APKSIGNER_JAR="$WIN_BT/lib/apksigner.jar"
  LAMBDA_STUBS="$WIN_BT/core-lambda-stubs.jar"
  USE_WIN=1
fi

BUILD_ID="${FOCUS_BUILD_ID:-$(git rev-parse --short=7 HEAD 2>/dev/null || echo dev)}"
COMMIT_SHA="${FOCUS_COMMIT_SHA:-$(git rev-parse HEAD 2>/dev/null || echo dev)}"
COMMIT_SUBJECT="${FOCUS_COMMIT_SUBJECT:-$(git log -1 --pretty=%s 2>/dev/null || echo '')}"
FOCUS_UPDATER_REPO="$(bash ../scripts/updater-repo.sh)"
export FOCUS_UPDATER_REPO
BUILT_AT="${FOCUS_BUILT_AT:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
EPOCH="$(date -u +%s)"
VERSION_CODE="${FOCUS_ANDROID_VERSION_CODE:-$((EPOCH - 1700000000))}"
if [ "$VERSION_CODE" -lt 2 ]; then
  VERSION_CODE=2
fi
VERSION_NAME="${FOCUS_ANDROID_VERSION_NAME:-0.1.0-${BUILD_ID}}"

TMP_SIGNING=""
cleanup() {
  if [ -n "$TMP_SIGNING" ] && [ -d "$TMP_SIGNING" ]; then
    rm -rf "$TMP_SIGNING"
  fi
}
trap cleanup EXIT

if [ -n "${FOCUS_ANDROID_KEYSTORE_BASE64:-}" ] && [ -n "${FOCUS_ANDROID_KEYSTORE_PASS:-}" ]; then
  TMP_SIGNING="$(mktemp -d)"
  chmod 700 "$TMP_SIGNING"
  python3 - "$TMP_SIGNING/release.jks" "$TMP_SIGNING/password" <<'PY'
import base64, os, sys
raw = os.environ["FOCUS_ANDROID_KEYSTORE_BASE64"].strip()
with open(sys.argv[1], "wb") as f:
    f.write(base64.b64decode(raw))
with open(sys.argv[2], "w", encoding="utf-8") as f:
    f.write(os.environ["FOCUS_ANDROID_KEYSTORE_PASS"].strip())
PY
  chmod 600 "$TMP_SIGNING/release.jks" "$TMP_SIGNING/password"
  KS_FILE="$TMP_SIGNING/release.jks"
  KS_PASS_FILE="$TMP_SIGNING/password"
else
  mkdir -p "$SIGNING"
  chmod 700 "$SIGNING"
  if [ ! -f "$SIGNING/release.jks" ]; then
    umask 077
    python3 -c 'import secrets,sys;open(sys.argv[1],"w").write(secrets.token_urlsafe(32))' "$SIGNING/password"
    keytool -genkeypair -keystore "$SIGNING/release.jks" \
      -storepass:file "$SIGNING/password" -keypass:file "$SIGNING/password" \
      -alias focus-phone -dname 'CN=Focus Phone Local Release' \
      -keyalg RSA -validity 10000 >/dev/null 2>&1
  fi
  KS_FILE="$SIGNING/release.jks"
  KS_PASS_FILE="$SIGNING/password"
fi

rm -rf build/classes build/generated build/dex
mkdir -p build/classes build/generated/org/nightingalelab/focus/phone build/dex

VERSION_CODE="$VERSION_CODE" VERSION_NAME="$VERSION_NAME" python3 - <<'PY'
import os, pathlib, re
src = pathlib.Path("AndroidManifest.xml").read_text(encoding="utf-8")
src = re.sub(r'android:versionCode="[^"]*"', f'android:versionCode="{os.environ["VERSION_CODE"]}"', src, count=1)
src = re.sub(r'android:versionName="[^"]*"', f'android:versionName="{os.environ["VERSION_NAME"]}"', src, count=1)
pathlib.Path("build/AndroidManifest.xml").write_text(src, encoding="utf-8")
PY

if [ "$USE_WIN" -eq 0 ]; then
  "$AAPT" package -f -m \
    -M "$PWD/build/AndroidManifest.xml" -S "$PWD/res" \
    -I "$ANDROID_JAR" -J "$PWD/build/generated" -F "$PWD/build/unsigned.apk"
else
  "$AAPT" package -f -m \
    -M "$(wslpath -w "$PWD/build/AndroidManifest.xml")" -S "$(wslpath -w "$PWD/res")" \
    -I "$(wslpath -w "$ANDROID_JAR")" -J "$(wslpath -w "$PWD/build/generated")" -F "$(wslpath -w "$PWD/build/unsigned.apk")"
fi

BUILD_ID="$BUILD_ID" COMMIT_SHA="$COMMIT_SHA" BUILT_AT="$BUILT_AT" \
VERSION_CODE="$VERSION_CODE" VERSION_NAME="$VERSION_NAME" \
python3 - <<'PY'
import json, os, pathlib

def jstr(v):
    return json.dumps(v)

out = pathlib.Path("build/generated/org/nightingalelab/focus/phone/BuildInfo.java")
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(f"""package org.nightingalelab.focus.phone;

public final class BuildInfo {{
  public static final String BUILD_ID = {jstr(os.environ["BUILD_ID"])};
  public static final String COMMIT_SHA = {jstr(os.environ["COMMIT_SHA"].lower())};
  public static final String BUILT_AT = {jstr(os.environ["BUILT_AT"])};
  public static final long VERSION_CODE = {int(os.environ["VERSION_CODE"])}L;
  public static final String VERSION_NAME = {jstr(os.environ["VERSION_NAME"])};
  public static final String DEFAULT_REPO = {jstr(os.environ.get("FOCUS_UPDATER_REPO", "").strip())};
  public static final String DEFAULT_TAG = "nightly";
}}
""", encoding="utf-8")
PY

python3 generate-ask-policy.py build/generated

BOOTCP="$ANDROID_JAR"
if [ -f "$LAMBDA_STUBS" ]; then
  BOOTCP="$ANDROID_JAR:$LAMBDA_STUBS"
fi

javac -source 8 -target 8 -Xlint:unchecked \
  -bootclasspath "$BOOTCP" \
  -d build/classes \
  src/org/nightingalelab/focus/phone/*.java ../telemetry/android/LocalTelemetry.java \
  ../shared/android/org/nightingalelab/focus/habits/*.java \
  build/generated/org/nightingalelab/focus/phone/R.java \
  build/generated/org/nightingalelab/focus/phone/BuildInfo.java \
  build/generated/org/nightingalelab/focus/phone/AskPolicy.java

jar cf build/classes.jar -C build/classes .
java -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 30 --lib "$ANDROID_JAR" --output build/dex build/classes.jar

if [ "${1:-}" != "--screenshot-ui" ]; then
  bash build-peek.sh
fi

python3 - <<'PY'
from pathlib import Path
import zipfile
with zipfile.ZipFile('build/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
    apk.write('build/dex/classes.dex', 'classes.dex')
    root = Path('build/peek-lib')
    for library in sorted(root.glob('lib/*/*.so')):
        apk.write(library, library.relative_to(root), compresslevel=9)
    for name in ('judge_prompt.txt', 'services.json'):
        apk.write('../app/src/' + name, 'assets/' + name)
PY

if [ "$USE_WIN" -eq 0 ]; then
  "$ZIPALIGN" -f -P 16 4 "$PWD/build/unsigned.apk" "$PWD/build/aligned.apk"
else
  "$ZIPALIGN" -f -P 16 4 "$(wslpath -w "$PWD/build/unsigned.apk")" "$(wslpath -w "$PWD/build/aligned.apk")"
fi

java -jar "$APKSIGNER_JAR" sign \
  --ks "$KS_FILE" \
  --ks-pass "file:$KS_PASS_FILE" \
  --out build/focus-phone.apk \
  build/aligned.apk

java -jar "$APKSIGNER_JAR" verify --verbose build/focus-phone.apk >/dev/null
if [ "$USE_WIN" -eq 0 ]; then
  "$ZIPALIGN" -c -P 16 4 "$PWD/build/focus-phone.apk"
else
  "$ZIPALIGN" -c -P 16 4 "$(wslpath -w "$PWD/build/focus-phone.apk")"
fi

if [ "${1:-}" = "--screenshot-ui" ]; then
  exit 0
fi

BUILD_ID="$BUILD_ID" COMMIT_SHA="$COMMIT_SHA" COMMIT_SUBJECT="$COMMIT_SUBJECT" \
BUILT_AT="$BUILT_AT" VERSION_CODE="$VERSION_CODE" VERSION_NAME="$VERSION_NAME" \
NOTES_PATH="${NOTES_FILE:-build/notes.json}" NOTES_EXPLICIT="${NOTES_FILE:+1}" \
python3 - <<'PY'
import hashlib, json, os, pathlib, sys

apk = pathlib.Path("build/focus-phone.apk")
data = apk.read_bytes()
manifest = {
    "version": 1,
    "channel": "nightly",
    "platform": "android",
    "package_name": "org.nightingalelab.focus.phone",
    "version_code": int(os.environ["VERSION_CODE"]),
    "version_name": os.environ["VERSION_NAME"],
    "build_id": os.environ["BUILD_ID"],
    "commit_sha": os.environ["COMMIT_SHA"].lower(),
    "commit_subject": os.environ["COMMIT_SUBJECT"][:140],
    "published_at": os.environ["BUILT_AT"],
    "apk": {
        "name": apk.name,
        "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    },
}

def load_notes(path):
    """Returns {"items": [[title, sentence], ...], "more": str} or None. Never fails the build."""
    p = pathlib.Path(path)
    if not p.is_file():
        if os.environ.get("NOTES_EXPLICIT"):
            print(f"warning: NOTES_FILE {path} not found; publishing without notes", file=sys.stderr)
        return None
    try:
        raw = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        print(f"warning: ignoring unreadable notes {path}: {e}", file=sys.stderr)
        return None
    if not isinstance(raw, dict):
        print(f"warning: ignoring notes {path}: expected an object", file=sys.stderr)
        return None
    items = []
    for it in raw.get("items") or []:
        if isinstance(it, list) and it and all(isinstance(x, str) for x in it[:2]):
            title = it[0].strip()[:80]
            body = (it[1] if len(it) > 1 else "").strip()[:240]
            if title or body:
                items.append([title, body])
    more = raw.get("more") if isinstance(raw.get("more"), str) else ""
    if not items and not more.strip():
        return None
    return {"items": items[:3], "more": more.strip()[:120]}

notes = load_notes(os.environ["NOTES_PATH"])
if notes:
    manifest["notes"] = notes
pathlib.Path("build/android-nightly.json").write_text(
    json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
)
print(f"Built {apk} (v{manifest['version_code']}, {manifest['build_id']}, {len(data)} bytes"
      + (f", {len(notes['items'])} release notes" if notes else "") + ")")
PY
sha256sum build/focus-phone.apk
