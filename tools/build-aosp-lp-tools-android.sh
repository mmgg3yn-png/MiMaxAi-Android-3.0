#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" ]]; then NDK="$ANDROID_HOME/ndk/27.2.12479018"; fi
if [[ -z "$NDK" && -n "${ANDROID_SDK_ROOT:-}" ]]; then NDK="$ANDROID_SDK_ROOT/ndk/27.2.12479018"; fi
if [[ -z "$NDK" ]]; then
  echo "ANDROID_NDK_HOME or ANDROID_NDK_ROOT is required" >&2
  exit 2
fi
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -x "$TOOLCHAIN/llvm-ar" ]]; then
  echo "Android NDK toolchain not found: $TOOLCHAIN" >&2
  exit 2
fi

# Pinned AOSP-derived implementation. Do not float this ref.
SOURCE_REPO="https://github.com/itsNileshHere/android-lptools.git"
SOURCE_COMMIT="7ec860cfa95ed83dec579ab0459aad1c35ad48e4"
WORK="${RUNNER_TEMP:-/tmp}/mimax-aosp-lptools"
rm -rf "$WORK"
git clone --filter=blob:none "$SOURCE_REPO" "$WORK"
git -C "$WORK" checkout --detach "$SOURCE_COMMIT"

patch_makefile() {
  python3 - "$WORK/make.sh" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
replacements = {
    "CC=clang": 'CC="${CC:-clang}"',
    "CPP=clang++": 'CPP="${CXX:-clang++}"',
    "AR=ar": 'AR="${AR:-ar}"',
    "STRIP=strip": 'STRIP="${STRIP:-strip}"',
    "CFLAGS=-static": 'CFLAGS="${CFLAGS:--static}"',
    'if [ -z "$OS"];then': 'if [ -z "$OS" ]; then',
    "$HOSTTYPE": "$TARGET_ARCH",
    "-lpthread ${LDFLAGS}": "-pthread ${LDFLAGS}",
    "-std=c++17": "-std=c++17 -D_LIBCPP_ENABLE_CXX17_REMOVED_UNARY_BINARY_FUNCTION -D__ANDROID_SDK_VERSION__=29",
}
for old, new in replacements.items():
    if old not in s:
        raise SystemExit(f"Expected upstream make.sh token missing: {old}")
    s = s.replace(old, new)
p.write_text(s)
PY
}

build_abi() {
  local abi="$1" triple="$2" arch="$3"
  local out="$ROOT/app/src/main/assets/lp-tools/$abi"
  mkdir -p "$out"

  export CC="$TOOLCHAIN/${triple}26-clang"
  export CXX="$TOOLCHAIN/${triple}26-clang++"
  export AR="$TOOLCHAIN/llvm-ar"
  export STRIP="$TOOLCHAIN/llvm-strip"
  export TARGET_ARCH="$arch"
  export CFLAGS="-O2 -fPIE -D_FILE_OFFSET_BITS=64 -fno-strict-aliasing -D_LIBCPP_ENABLE_CXX17_REMOVED_UNARY_BINARY_FUNCTION -D__ANDROID_SDK_VERSION__=29"
  export LDFLAGS="-pie -llog -ldl"

  (cd "$WORK" && bash ./make.sh)

  for tool in lpunpack lpmake lpdump; do
    test -s "$WORK/bin/$tool"
    install -m 0755 "$WORK/bin/$tool" "$out/$tool"
  done
  printf '%s\n' "$SOURCE_COMMIT" > "$out/SOURCE_COMMIT"
}

rm -rf "$ROOT/app/src/main/assets/lp-tools"
export TARGET_ARCH="arm"
patch_makefile
build_abi "armeabi-v7a" "armv7a-linux-androideabi" "arm"
build_abi "arm64-v8a" "aarch64-linux-android" "aarch64"
echo "AOSP logical-partition tools staged for armeabi-v7a and arm64-v8a."
