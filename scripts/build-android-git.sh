#!/bin/bash
# Cross-compile Git for Android arm64 and package it as an app native library.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/.runtime-build/git-2.51.0"
NDK="${GRAIN_NDK_ROOT:-$ROOT/.android-sdk/ndk/27.0.12077973}"
TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
OUT="$ROOT/android/app/src/main/jniLibs/arm64-v8a/libgrain_git.so"

if [[ ! -x "$TC/aarch64-linux-android24-clang" ]]; then
  echo "缺少 Android NDK 27：$NDK" >&2
  exit 1
fi
if [[ ! -f "$SRC/Makefile" ]]; then
  echo "缺少 Git 源码：$SRC" >&2
  exit 1
fi

python3 - "$SRC/run-command.c" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
text = path.read_text()
old = '''\tCHECK_BUG(pthread_setcancelstate(PTHREAD_CANCEL_DISABLE, &as->cs),
\t\t"disabling cancellation");'''
new = '''\t/* Bionic has no thread cancellation. */
\t(void)as->cs;'''
old2 = '''\tCHECK_BUG(pthread_setcancelstate(as->cs, NULL),
\t\t"re-enabling cancellation");'''
new2 = '''\t(void)as->cs;'''
if old not in text or old2 not in text:
    if "Bionic has no thread cancellation" not in text:
        raise SystemExit("run-command.c no longer matches the Android cancel patch")
else:
    path.write_text(text.replace(old, new, 1).replace(old2, new2, 1))
PY

cd "$SRC"
make -j"$(sysctl -n hw.ncpu)" \
  CC="$TC/aarch64-linux-android24-clang" \
  AR="$TC/llvm-ar" \
  RANLIB="$TC/llvm-ranlib" \
  HOSTCC="$(xcrun --find clang)" \
  uname_S=Linux \
  uname_M=aarch64 \
  uname_R=android \
  CFLAGS="-O2" \
  LDFLAGS="-Wl,-z,max-page-size=16384 -static-libstdc++" \
  NO_OPENSSL=YesPlease \
  NO_CURL=YesPlease \
  NO_EXPAT=YesPlease \
  NO_GETTEXT=YesPlease \
  NO_TCLTK=YesPlease \
  NO_ICONV=YesPlease \
  NO_PERL=YesPlease \
  NO_PYTHON=YesPlease \
  NO_INSTALL_HARDLINKS=YesPlease \
  CSPRNG_METHOD= \
  PTHREAD_LIBS= \
  FREAD_READS_DIRECTORIES= \
  HAVE_SYNC_FILE_RANGE= \
  HAVE_SYSINFO= \
  git

python3 - "$SRC/git" <<'PY'
import struct, sys
from pathlib import Path
data = Path(sys.argv[1]).read_bytes()[:20]
assert data[:4] == b'\x7fELF' and data[4:6] == bytes([2, 1]), '需要 ELF64 小端'
assert struct.unpack_from('<H', data, 16)[0] == 3, '需要 PIE 可执行文件'
assert struct.unpack_from('<H', data, 18)[0] == 183, '需要 Android arm64'
print('Android arm64 Git candidate:', sys.argv[1])
PY
"$TC/llvm-readelf" --program-headers --dynamic "$SRC/git"
mkdir -p "$(dirname "$OUT")"
"$TC/llvm-strip" --strip-unneeded -o "$OUT" "$SRC/git"
echo "Stripped Android Git is at android/app/src/main/jniLibs/arm64-v8a/libgrain_git.so"
python3 - "$SRC/templates" "$ROOT/android/app/src/main/assets/git-templates.zip" <<'PY'
import sys, zipfile
from pathlib import Path
root = Path(sys.argv[1])
dest = Path(sys.argv[2])
names = ["description", "info/exclude"]
names.extend(f"hooks/{path.name}" for path in sorted((root / "hooks").glob("*.sample")))
dest.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(dest, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for name in names:
        archive.write(root / name, name)
print("git templates:", dest)
PY
