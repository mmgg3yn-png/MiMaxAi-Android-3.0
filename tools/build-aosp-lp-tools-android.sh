#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" ]]; then NDK="$ANDROID_HOME/ndk/27.3.13750724"; fi
if [[ -z "$NDK" && -n "${ANDROID_SDK_ROOT:-}" ]]; then NDK="$ANDROID_SDK_ROOT/ndk/27.3.13750724"; fi
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
import re
p = Path(sys.argv[1])
s = p.read_text()
# The old bundled liblog sources reference private APIs removed from modern NDK headers.
# Use the NDK's public liblog.so at link/runtime; base/logging.cpp has a dlsym + stable-API fallback.
pattern = r'cd lib\s+cd liblog\s+.*?unset src\s+cd \.\./zlib'
replacement = """cd lib
mkdir -p lib
$AR rcs lib/liblog.a

cd zlib"""
s, count = re.subn(pattern, replacement, s, count=1, flags=re.S)
if count != 1:
    raise SystemExit('Could not replace obsolete bundled liblog build block')
replacements = {
    "CC=clang": 'CC="${CC:-clang}"',
    "CPP=clang++": 'CPP="${CXX:-clang++}"',
    "AR=ar": 'AR="${AR:-ar}"',
    "STRIP=strip": 'STRIP="${STRIP:-strip}"',
    "CFLAGS=-static": 'CFLAGS="${CFLAGS:--static}"',
    'if [ -z "$OS"];then': 'if [ -z "$OS" ]; then',
    "$HOSTTYPE": "$TARGET_ARCH",
    "-lpthread ${LDFLAGS}": "-pthread ${LDFLAGS}",
    "-std=c++17": "-std=c++17 -D_LIBCPP_ENABLE_CXX17_REMOVED_UNARY_BINARY_FUNCTION -D__ANDROID_SDK_VERSION__=30 -DMIMAX_LP_TOOLS_NO_CONTROL_FD -DMIMAX_LP_TOOLS_IMAGE_ONLY",
}
for old, new in replacements.items():
    if old not in s:
        raise SystemExit(f"Expected upstream make.sh token missing: {old}")
    s = s.replace(old, new)
p.write_text(s)

# These are standalone userspace image tools, not init/recovery services.
# The control-fd hook is only for inherited descriptors of protected device
# nodes; the app passes ordinary image-file paths and uses open() instead.
util = Path(sys.argv[1]).parent / "lib" / "liblp" / "utility.cpp"
u = util.read_text()
old_include = "#ifdef __ANDROID__\n#include <cutils/android_get_control_file.h>\n#endif"
new_include = "#if defined(__ANDROID__) && !defined(MIMAX_LP_TOOLS_NO_CONTROL_FD)\n#include <cutils/android_get_control_file.h>\n#endif"
old_call = "#if defined(__ANDROID__)\n    int fd = android_get_control_file(path.data());"
new_call = "#if defined(__ANDROID__) && !defined(MIMAX_LP_TOOLS_NO_CONTROL_FD)\n    int fd = android_get_control_file(path.data());"
if old_include not in u or old_call not in u:
    raise SystemExit("Could not find expected control-fd code in liblp/utility.cpp")
u = u.replace(old_include, new_include, 1).replace(old_call, new_call, 1)
util.write_text(u)

# lpdump is also built for Android, but this app only inspects image files.
# Exclude device-only fs_mgr/fstab integration; retain metadata and JSON dumping.
dump = Path(sys.argv[1]).parent / "partition_tools" / "lpdump.cc"
d = dump.read_text()
# MergeMetadata uses BlockDeviceInfo, exposed by liblp's public opener header.
include_anchor = "#include <liblp/liblp.h>"
if include_anchor not in d:
    raise SystemExit("Could not find liblp include in lpdump.cc")
d = d.replace(include_anchor, include_anchor + "\n#include <liblp/partition_opener.h>", 1)
d = d.replace("#ifdef __ANDROID__\n#include <cutils/android_get_control_file.h>\n#include <fs_mgr.h>\n#endif",
              "#if defined(__ANDROID__) && !defined(MIMAX_LP_TOOLS_IMAGE_ONLY)\n#include <cutils/android_get_control_file.h>\n#include <fs_mgr.h>\n#endif", 1)
# Keep the liblp namespace available: its public metadata API is still used for image files.
if "using namespace android::fs_mgr;" not in d:
    raise SystemExit("Expected liblp namespace import missing")
d = d.replace("#ifdef __ANDROID__\nstatic DynamicPartitionsDeviceInfoProto::Partition* FindPartition(",
              "#if defined(__ANDROID__) && !defined(MIMAX_LP_TOOLS_IMAGE_ONLY)\nstatic DynamicPartitionsDeviceInfoProto::Partition* FindPartition(", 1)
d = d.replace("#ifdef __ANDROID__\n    if (!MergeFsUsage(&proto, cerr)) {",
              "#if defined(__ANDROID__) && !defined(MIMAX_LP_TOOLS_IMAGE_ONLY)\n    if (!MergeFsUsage(&proto, cerr)) {", 1)
d = d.replace("#ifdef __ANDROID__\n    // Use the current slot as a default for A/B devices.",
              "#if defined(__ANDROID__) && !defined(MIMAX_LP_TOOLS_IMAGE_ONLY)\n    // Use the current slot as a default for A/B devices.", 1)
d = d.replace("#ifdef __ANDROID__\n        super_path = GetSuperPartitionName(slot);",
              "#if defined(__ANDROID__) && !defined(MIMAX_LP_TOOLS_IMAGE_ONLY)\n        super_path = GetSuperPartitionName(slot);", 1)
d = d.replace("#ifdef __ANDROID__\n    uint32_t num_slots = current_slot_suffix.empty() ? 1 : 2;",
              "#if defined(__ANDROID__) && !defined(MIMAX_LP_TOOLS_IMAGE_ONLY)\n    uint32_t num_slots = current_slot_suffix.empty() ? 1 : 2;", 1)
# This wrapper is unused by the standalone image-file CLI. It depends on
# device-only PartitionOpener and android_get_control_file APIs unavailable
# in the public NDK. ReadMetadata() supports ordinary image paths directly.
d, removed = re.subn(r"class FileOrBlockDeviceOpener final : public PartitionOpener [{].*?[\n][}];[\n]", "", d, count=1, flags=re.S)
if removed != 1:
    raise SystemExit("Could not remove unused device-only FileOrBlockDeviceOpener")
d = d.replace("if (!override_super_name) {\n                super_path = GetSuperPartitionName(i);",
              "if (!override_super_name) {\n                super_path = GetSuperPartitionName(i);", 1)
if d == dump.read_text() or d.count("MIMAX_LP_TOOLS_IMAGE_ONLY") < 6:
    raise SystemExit("Could not apply all expected lpdump image-only guards")
if "using namespace android::fs_mgr;" not in d:
    raise SystemExit("lpdump lost the public liblp namespace import")
if "class FileOrBlockDeviceOpener" in d or "fd.reset(android_get_control_file" in d:
    raise SystemExit("lpdump image-only build still contains device control-fd integration")
dump.write_text(d)
PY
}

build_abi() {
  local abi="$1" triple="$2" arch="$3"
  local out="$ROOT/app/src/main/assets/lp-tools/$abi"
  mkdir -p "$out"

  export CC="$TOOLCHAIN/${triple}30-clang"
  export CXX="$TOOLCHAIN/${triple}30-clang++"
  export AR="$TOOLCHAIN/llvm-ar"
  export STRIP="$TOOLCHAIN/llvm-strip"
  export TARGET_ARCH="$arch"
  export CFLAGS="-O2 -fPIE -D_FILE_OFFSET_BITS=64 -fno-strict-aliasing -D_LIBCPP_ENABLE_CXX17_REMOVED_UNARY_BINARY_FUNCTION -D__ANDROID_SDK_VERSION__=30 -DMIMAX_LP_TOOLS_NO_CONTROL_FD -DMIMAX_LP_TOOLS_IMAGE_ONLY"
  export LDFLAGS="-static-libstdc++ -pie -llog -ldl"

  (cd "$WORK" && bash ./make.sh)

  case "$abi" in
    armeabi-v7a) expected_machine="ARM" ;;
    arm64-v8a) expected_machine="AArch64" ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 2 ;;
  esac
  for tool in lpunpack lpmake lpdump; do
    test -s "$WORK/bin/$tool"
    elf_header="$("$TOOLCHAIN/llvm-readelf" -h "$WORK/bin/$tool")"
    grep -q "Machine:.*$expected_machine" <<< "$elf_header" || { echo "$tool has wrong ELF architecture for $abi" >&2; exit 1; }
    grep -q "Type:.*DYN" <<< "$elf_header" || { echo "$tool is not a PIE executable" >&2; exit 1; }
    if "$TOOLCHAIN/llvm-readelf" -d "$WORK/bin/$tool" | grep -q 'libc++_shared\\.so'; then
      echo "$tool still depends on libc++_shared.so; standalone assets must be self-contained" >&2
      exit 1
    fi
    install -m 0755 "$WORK/bin/$tool" "$out/$tool"
    test -s "$out/$tool"
  done
  printf '%s\n' "$SOURCE_COMMIT" > "$out/SOURCE_COMMIT"
}

rm -rf "$ROOT/app/src/main/assets/lp-tools"
export TARGET_ARCH="arm"
patch_makefile
build_abi "armeabi-v7a" "armv7a-linux-androideabi" "arm"
build_abi "arm64-v8a" "aarch64-linux-android" "aarch64"
echo "AOSP logical-partition tools staged for armeabi-v7a and arm64-v8a."
