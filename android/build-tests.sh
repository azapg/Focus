#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

LINUX_BT="${FOCUS_ANDROID_BUILD_TOOLS:-${HOME}/Android/Sdk/build-tools/36.1.0}"
WIN_SDK="${FOCUS_PHONE_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}}"
ANDROID_JAR="${FOCUS_PHONE_ANDROID:-$WIN_SDK/platforms/android-36/android.jar}"
SIGNING="${FOCUS_PHONE_SIGNING:-${XDG_DATA_HOME:-$HOME/.local/share}/focus-phone/signing}"

if [ -x "$LINUX_BT/aapt" ]; then
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

if [ ! -f build/classes.jar ]; then
  bash ./build.sh
fi

rm -rf build/test-classes build/test-dex build/test-staging
mkdir -p build/test-classes build/test-dex build/test-staging
cp tests/TestManifest.xml build/test-staging/AndroidManifest.xml

if [ "$USE_WIN" -eq 0 ]; then
  "$AAPT" package -f -M "$PWD/build/test-staging/AndroidManifest.xml" -I "$ANDROID_JAR" -F "$PWD/build/test-unsigned.apk"
else
  "$AAPT" package -f -M "$(wslpath -w "$PWD/build/test-staging/AndroidManifest.xml")" -I "$(wslpath -w "$ANDROID_JAR")" -F "$(wslpath -w "$PWD/build/test-unsigned.apk")"
fi

javac -source 8 -target 8 -Xlint:unchecked \
  -bootclasspath "$ANDROID_JAR:$LAMBDA_STUBS" \
  -cp build/classes.jar \
  -d build/test-classes \
  tests/device/org/nightingalelab/focus/phone/*.java \
  tests/device/org/nightingalelab/focus/phone/tests/*.java

jar cf build/test-classes.jar -C build/test-classes .
java -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 30 --lib "$ANDROID_JAR" --classpath build/classes.jar --output build/test-dex build/test-classes.jar

python3 - <<'PY'
import zipfile
with zipfile.ZipFile('build/test-unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
    apk.write('build/test-dex/classes.dex', 'classes.dex')
PY

if [ "$USE_WIN" -eq 0 ]; then
  "$ZIPALIGN" -f 4 "$PWD/build/test-unsigned.apk" "$PWD/build/test-aligned.apk"
else
  "$ZIPALIGN" -f 4 "$(wslpath -w "$PWD/build/test-unsigned.apk")" "$(wslpath -w "$PWD/build/test-aligned.apk")"
fi

java -jar "$APKSIGNER_JAR" sign \
  --ks "$SIGNING/release.jks" \
  --ks-pass "file:$SIGNING/password" \
  --out build/test-phone.apk \
  build/test-aligned.apk

java -jar "$APKSIGNER_JAR" verify --verbose build/test-phone.apk >/dev/null
echo "Built android/build/test-phone.apk"
