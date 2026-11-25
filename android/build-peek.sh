#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
NDK_VERSION=28.2.13676358
NDK="${FOCUS_PHONE_NDK:-}"
if [ -z "$NDK" ]; then
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" /usr/local/lib/android/sdk; do
    if [ -n "$sdk" ] && [ -d "$sdk/ndk/$NDK_VERSION" ]; then
      NDK="$sdk/ndk/$NDK_VERSION"
      break
    fi
  done
fi
LLVM="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -x "$LLVM/aarch64-linux-android30-clang" ]; then
  echo "Install the Linux Android NDK $NDK_VERSION or set FOCUS_PHONE_NDK (also on WSL)." >&2
  exit 1
fi

build_target="${CARGO_TARGET_DIR:-$PWD/peek-native/target}"
rm -rf build/peek-lib
for abi in ${FOCUS_PEEK_ABIS:-arm64-v8a}; do
  case "$abi" in
    arm64-v8a) target=aarch64-linux-android; clang=aarch64-linux-android30-clang ;;
    armeabi-v7a) target=armv7-linux-androideabi; clang=armv7a-linux-androideabi30-clang ;;
    x86_64) target=x86_64-linux-android; clang=x86_64-linux-android30-clang ;;
    x86) target=i686-linux-android; clang=i686-linux-android30-clang ;;
    *) echo "Unsupported Android ABI: $abi" >&2; exit 1 ;;
  esac
  underscored="${target//-/_}"
  upper="${underscored^^}"
  env "CARGO_TARGET_${upper}_LINKER=$LLVM/$clang" \
    "CC_${underscored}=$LLVM/$clang" "AR_${underscored}=$LLVM/llvm-ar" \
    "CARGO_TARGET_${upper}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384" \
    cargo build --locked --release --target-dir "$build_target" \
      --manifest-path peek-native/Cargo.toml --target "$target"
  install -D "$build_target/$target/release/libfocus_phone_peek.so" "build/peek-lib/lib/$abi/libfocus_phone_peek.so"
done
