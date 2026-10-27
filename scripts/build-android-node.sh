#!/bin/bash
# Cross-compile upstream Node as an executable, separate from nodejs-mobile.
set -euo pipefail

GRAIN_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRAIN_NODE_VERSION=22.23.4
GRAIN_SOURCE="$GRAIN_ROOT/.runtime-build/node-v$GRAIN_NODE_VERSION"
GRAIN_NDK="${GRAIN_NDK_ROOT:-$GRAIN_ROOT/.android-sdk/ndk/27.0.12077973}"
GRAIN_TOOLCHAIN="$GRAIN_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"

if [[ ! -x "$GRAIN_TOOLCHAIN/aarch64-linux-android24-clang++" ]]; then
  echo "Missing Android NDK 27: $GRAIN_NDK" >&2
  exit 1
fi
if [[ ! -f "$GRAIN_SOURCE/android-configure" ]]; then
  echo "Missing verified Node source: $GRAIN_SOURCE" >&2
  exit 1
fi

export CC="$GRAIN_TOOLCHAIN/aarch64-linux-android24-clang"
export CXX="$GRAIN_TOOLCHAIN/aarch64-linux-android24-clang++"
export AR="$GRAIN_TOOLCHAIN/llvm-ar"
export CC_host="$(xcrun --find clang)"
export CXX_host="$(xcrun --find clang++)"
export GRAIN_HOST_AR="$(xcrun --find ar)"
GRAIN_MAC_SDK="$(xcrun --sdk macosx --show-sdk-path)"
export CPPFLAGS_host="-isysroot $GRAIN_MAC_SDK"
export LDFLAGS_host="-isysroot $GRAIN_MAC_SDK"
export PATH="$GRAIN_TOOLCHAIN:$PATH"
export GYP_DEFINES="target_arch=arm64 v8_target_arch=arm64 android_target_arch=arm64 host_os=mac OS=android android_ndk_path=$GRAIN_NDK"

cd "$GRAIN_SOURCE"
# Select macOS sources only for libuv's host toolset. Android target sources stay intact.
python3 - "$GRAIN_SOURCE" <<'PATCH'
from pathlib import Path
import sys, tarfile, ast, pprint
root = Path(sys.argv[1])
archive = root.parent / (root.name + '.tar.xz')
with tarfile.open(archive) as source:
    text = source.extractfile(root.name + '/deps/uv/uv.gyp').read().decode()
marker = "'target_name': 'libuv',"
assert text.count(marker) == 1
host_condition = """
      'target_conditions': [
        ['_toolset=="host" and host_os=="mac"', {
          'sources!': [
            'src/unix/linux.c', 'src/unix/procfs-exepath.c',
            'src/unix/random-getrandom.c', 'src/unix/random-sysctl-linux.c',
          ],
          'sources': [ '<@(uv_sources_apple)', '<@(uv_sources_bsd_common)' ],
          'defines': [ '_DARWIN_USE_64_BIT_INODE=1', '_DARWIN_UNLIMITED_SELECT=1' ],
        }],
      ],
"""
(root / 'deps/uv/uv.gyp').write_text(text.replace(marker, marker + host_condition))
with tarfile.open(archive) as source:
    v8 = ast.literal_eval(source.extractfile(root.name + '/tools/v8_gypfiles/v8.gyp').read().decode())
base = next(t for t in v8['targets'] if t['target_name'] == 'v8_libbase')
base.setdefault('target_conditions', []).append([
    '_toolset=="host" and host_os=="mac"',
    {'sources!': ['<(V8_ROOT)/src/base/platform/platform-linux.cc']},
])
(root / 'tools/v8_gypfiles/v8.gyp').write_text(pprint.pformat(v8, width=120, sort_dicts=False))
gyp = root / 'node.gyp'
text = gyp.read_text()
for dependency in ('uv-grain-host.gyp:libuv#host', 'uv-grain-host.gyp:grain_host_libuv#host'):
    text = text.replace(dependency, 'uv.gyp:libuv#host')
gyp.write_text(text)
PATCH
python3 - "$GRAIN_SOURCE" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1]) / "deps/v8/src/codegen/arm64/assembler-arm64.cc"
text = path.read_text()
needle = "constexpr unsigned CpuFeaturesFromCompiler() {\n  unsigned features = 0;\n"
insert = needle + """#if defined(V8_TARGET_OS_ANDROID)
  // The snapshot is generated on the host. Apple Silicon defines JSCVT,
  // DotProd and LSE, and baking those into Android builtins SIGILLs on
  // older tablets. Runtime detection still runs on the device.
  return features;
#endif
"""
if "older tablets" in text:
    print("Android snapshot CPU baseline already applied")
elif needle not in text:
    raise SystemExit("CpuFeaturesFromCompiler not found")
else:
    path.write_text(text.replace(needle, insert, 1))
    print("Applied Android snapshot CPU baseline")
PY
./configure --dest-cpu=arm64 --dest-os=android --openssl-no-asm --cross-compiling
test -f config.gypi
python3 - "$GRAIN_SOURCE" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
for name in ('v8_base_without_compiler.host.mk', 'v8_base_without_compiler.target.mk'):
    path = root / 'out/tools/v8_gypfiles' / name
    text = path.read_text()
    if 'handler-inside-posix.o' in text:
        continue
    toolset = 'host' if name.endswith('host.mk') else 'target'
    needle = 'src/trap-handler/handler-outside.o \\\n'
    extra = (
        needle
        + f'\t$(obj).{toolset}/$(TARGET)/deps/v8/src/trap-handler/handler-inside-posix.o \\\n'
        + f'\t$(obj).{toolset}/$(TARGET)/deps/v8/src/trap-handler/handler-outside-posix.o \\\n'
    )
    if needle not in text:
        raise SystemExit(f'missing trap-handler objects in {path}')
    path.write_text(text.replace(needle, extra, 1))
PY
# Discard only generated host thin archives from an earlier GNU-archiver attempt.
python3 - "$GRAIN_SOURCE/out/Release/obj.host" <<'PY'
from pathlib import Path
import sys
for archive in Path(sys.argv[1]).rglob('*.a'):
    with archive.open('rb') as stream:
        thin = stream.read(8) == b'!<thin>\n'
    if thin or archive.stat().st_size <= 8:
        archive.unlink()
PY
"$CC" -fPIC -O2 -c "$GRAIN_NDK/sources/android/cpufeatures/cpu-features.c" -o "$GRAIN_SOURCE/out/Release/cpu-features.o"
make -C out -j"${GRAIN_BUILD_JOBS:-4}" BUILDTYPE=Release \
  "LDFLAGS.target=-Wl,-z,max-page-size=16384 -static-libstdc++ $GRAIN_SOURCE/out/Release/cpu-features.o" \
  "LINK.host=python3 $GRAIN_ROOT/scripts/link-macos-host.py" \
  "AR.host=python3 $GRAIN_ROOT/scripts/archive-macos-host.py" node

# Reject a host binary before it can reach Android packaging.
python3 - "$GRAIN_SOURCE/out/Release/node" <<'PY'
import struct, sys
from pathlib import Path
p = Path(sys.argv[1])
data = p.read_bytes()[:20]
assert data[:4] == b'\x7fELF' and data[4:6] == bytes([2, 1]), 'Expected ELF64 little endian'
assert struct.unpack_from('<H', data, 16)[0] == 3, 'Expected a PIE executable'
assert struct.unpack_from('<H', data, 18)[0] == 183, 'Expected Android arm64, not a host binary'
print('Android arm64 ELF candidate:', p)
PY
"$GRAIN_TOOLCHAIN/llvm-readelf" --program-headers --dynamic out/Release/node
mkdir -p "$GRAIN_ROOT/android/app/src/main/jniLibs/arm64-v8a"
"$GRAIN_TOOLCHAIN/llvm-strip" --strip-unneeded \
  -o "$GRAIN_ROOT/android/app/src/main/jniLibs/arm64-v8a/libgrain_node.so" \
  out/Release/node
echo 'Stripped Android Node is at android/app/src/main/jniLibs/arm64-v8a/libgrain_node.so'
python3 - "$GRAIN_SOURCE/deps/npm" "$GRAIN_ROOT/android/app/src/main/assets/npm.zip" <<'PY'
import sys, zipfile
from pathlib import Path
root = Path(sys.argv[1])
dest = Path(sys.argv[2])
dest.parent.mkdir(parents=True, exist_ok=True)
parts = ["package.json", "bin", "lib", "node_modules"]
with zipfile.ZipFile(dest, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for part in parts:
        path = root / part
        files = [path] if path.is_file() else [item for item in path.rglob("*") if item.is_file()]
        for item in files:
            archive.write(item, item.relative_to(root).as_posix())
print("npm archive:", dest)
PY
