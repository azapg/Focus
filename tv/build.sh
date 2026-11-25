#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
FOCUS_TV_SDK="${FOCUS_TV_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}}"
LINUX_BT="${FOCUS_ANDROID_BUILD_TOOLS:-${HOME}/Android/Sdk/build-tools/36.1.0}"
FOCUS_TV_ANDROID="${FOCUS_TV_ANDROID:-$FOCUS_TV_SDK/platforms/android-35/android.jar}"
FOCUS_TV_SIGNING="${FOCUS_TV_SIGNING:-${XDG_DATA_HOME:-$HOME/.local/share}/focus-tv/signing}"
if [ -x "$LINUX_BT/aapt" ]; then
  FOCUS_TV_BT="$LINUX_BT"
  USE_WIN=0
else
  FOCUS_TV_BT="$FOCUS_TV_SDK/build-tools/35.0.0"
  USE_WIN=1
fi
mkdir -p build/classes build/generated build/dex "$FOCUS_TV_SIGNING"
chmod 700 "$FOCUS_TV_SIGNING"
if [ ! -f "$FOCUS_TV_SIGNING/release.jks" ]; then
  umask 077
  python3 -c 'import secrets,sys;open(sys.argv[1],"w").write(secrets.token_urlsafe(32))' "$FOCUS_TV_SIGNING/password"
  keytool -genkeypair -keystore "$FOCUS_TV_SIGNING/release.jks" -storepass:file "$FOCUS_TV_SIGNING/password" -keypass:file "$FOCUS_TV_SIGNING/password" -alias focus-tv -dname 'CN=Focus TV Local Release' -keyalg RSA -validity 10000 >/dev/null 2>&1
fi
rm -rf build/classes build/generated build/dex
mkdir -p build/classes build/generated build/dex
if [ "$USE_WIN" -eq 0 ]; then
  "$FOCUS_TV_BT/aapt" package -f -m -M "$PWD/AndroidManifest.xml" -S "$PWD/res" -I "$FOCUS_TV_ANDROID" -J "$PWD/build/generated" -F "$PWD/build/unsigned.apk"
else
  "$FOCUS_TV_BT/aapt.exe" package -f -m -M "$(wslpath -w "$PWD/AndroidManifest.xml")" -S "$(wslpath -w "$PWD/res")" -I "$(wslpath -w "$FOCUS_TV_ANDROID")" -J "$(wslpath -w "$PWD/build/generated")" -F "$(wslpath -w "$PWD/build/unsigned.apk")"
fi
javac -source 8 -target 8 -Xlint:unchecked -bootclasspath "$FOCUS_TV_ANDROID:$FOCUS_TV_BT/core-lambda-stubs.jar" -d build/classes src/org/nightingalelab/focus/tv/*.java ../telemetry/android/LocalTelemetry.java ../shared/android/org/nightingalelab/focus/habits/*.java build/generated/org/nightingalelab/focus/tv/R.java
jar cf build/classes.jar -C build/classes .
java -cp "$FOCUS_TV_BT/lib/d8.jar" com.android.tools.r8.D8 --min-api 30 --lib "$FOCUS_TV_ANDROID" --output build/dex build/classes.jar
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('build/unsigned.apk','a',zipfile.ZIP_DEFLATED) as apk: apk.write('build/dex/classes.dex','classes.dex')
PY
if [ "$USE_WIN" -eq 0 ]; then
  "$FOCUS_TV_BT/zipalign" -f 4 "$PWD/build/unsigned.apk" "$PWD/build/aligned.apk"
else
  "$FOCUS_TV_BT/zipalign.exe" -f 4 "$(wslpath -w "$PWD/build/unsigned.apk")" "$(wslpath -w "$PWD/build/aligned.apk")"
fi
java -jar "$FOCUS_TV_BT/lib/apksigner.jar" sign --ks "$FOCUS_TV_SIGNING/release.jks" --ks-pass "file:$FOCUS_TV_SIGNING/password" --out build/focus-tv.apk build/aligned.apk
java -jar "$FOCUS_TV_BT/lib/apksigner.jar" verify --verbose build/focus-tv.apk
sha256sum build/focus-tv.apk
