#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
FOCUS_TV_SDK="${FOCUS_TV_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}}"
FOCUS_TV_BT="${FOCUS_ANDROID_BUILD_TOOLS:-$FOCUS_TV_SDK/build-tools/35.0.0}"
FOCUS_TV_ANDROID="${FOCUS_TV_ANDROID:-$FOCUS_TV_SDK/platforms/android-35/android.jar}"
FOCUS_TV_SIGNING="${FOCUS_TV_SIGNING:-${XDG_DATA_HOME:-$HOME/.local/share}/focus-tv/signing}"
for variant in fixture device; do
  mkdir -p "build/test-$variant/classes" "build/test-$variant/dex" "build/test-$variant/staging"
  manifest=FixtureManifest.xml
  [ "$variant" = device ] && manifest=TestManifest.xml
  cp "tests/$manifest" "build/test-$variant/staging/AndroidManifest.xml"
  if [ -x "$FOCUS_TV_BT/aapt" ]; then
    "$FOCUS_TV_BT/aapt" package -f -M "build/test-$variant/staging/AndroidManifest.xml" -I "$FOCUS_TV_ANDROID" -F "build/test-$variant/unsigned.apk"
  else
    "$FOCUS_TV_BT/aapt.exe" package -f -M "$(wslpath -w "$PWD/build/test-$variant/staging/AndroidManifest.xml")" -I "$(wslpath -w "$FOCUS_TV_ANDROID")" -F "$(wslpath -w "$PWD/build/test-$variant/unsigned.apk")"
  fi
  find "tests/$variant" -name '*.java' -print0 | xargs -0 javac -source 8 -target 8 -bootclasspath "$FOCUS_TV_ANDROID:$FOCUS_TV_BT/core-lambda-stubs.jar" -classpath build/classes.jar -d "build/test-$variant/classes"
  jar cf "build/test-$variant/classes.jar" -C "build/test-$variant/classes" .
  java -cp "$FOCUS_TV_BT/lib/d8.jar" com.android.tools.r8.D8 --min-api 30 --lib "$FOCUS_TV_ANDROID" --classpath build/classes.jar --output "build/test-$variant/dex" "build/test-$variant/classes.jar"
  python3 - "$variant" <<'PY'
import sys,zipfile
base='build/test-'+sys.argv[1]
with zipfile.ZipFile(base+'/unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:z.write(base+'/dex/classes.dex','classes.dex')
PY
  if [ -x "$FOCUS_TV_BT/zipalign" ]; then
    "$FOCUS_TV_BT/zipalign" -f 4 "build/test-$variant/unsigned.apk" "build/test-$variant/aligned.apk"
  else
    "$FOCUS_TV_BT/zipalign.exe" -f 4 "$(wslpath -w "$PWD/build/test-$variant/unsigned.apk")" "$(wslpath -w "$PWD/build/test-$variant/aligned.apk")"
  fi
  java -jar "$FOCUS_TV_BT/lib/apksigner.jar" sign --ks "$FOCUS_TV_SIGNING/release.jks" --ks-pass "file:$FOCUS_TV_SIGNING/password" --out "build/test-$variant.apk" "build/test-$variant/aligned.apk"
  java -jar "$FOCUS_TV_BT/lib/apksigner.jar" verify "build/test-$variant.apk"
done
