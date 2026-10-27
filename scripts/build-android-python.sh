#!/bin/bash
# Cross-compile CPython 3.13 for Android arm64 and package it into the app.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/.runtime-build/Python-3.13.7"
NDK_REAL="$ROOT/.android-sdk/ndk/27.0.12077973"
NDK_LINK="$ROOT/.android-sdk/ndk/27.3.13750724"
OUT_DIR="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
ASSET="$ROOT/android/app/src/main/assets/python-stdlib.zip"
HOST="aarch64-linux-android"

export ANDROID_HOME="$ROOT/.android-sdk"
export JAVA_HOME="$ROOT/.android-sdk/jdk21/Contents/Home"
export ANDROID_API_LEVEL=24

if [[ ! -x "$NDK_REAL/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android24-clang" ]]; then
  echo "缺少 Android NDK：$NDK_REAL" >&2
  exit 1
fi
if [[ ! -e "$NDK_LINK" ]]; then
  ln -s "$NDK_REAL" "$NDK_LINK"
fi
if [[ ! -f "$SRC/Android/android.py" ]]; then
  echo "缺少 CPython 源码：$SRC" >&2
  exit 1
fi

python3 - "$SRC/Python/dynload_shlib.c" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
text = path.read_text()
if "libpyext_" in text:
    raise SystemExit(0)
old = """    handle = dlopen(pathname, dlopenflags);

    if (handle == NULL) {"""
new = """    handle = dlopen(pathname, dlopenflags);
#ifdef __ANDROID__
    if (handle == NULL && shortname && shortname[0]) {
        Dl_info info;
        if (dladdr((void *)_PyImport_FindSharedFuncptr, &info) && info.dli_fname) {
            char dirbuf[1024];
            char alt[1200];
            size_t len = strlen(info.dli_fname);
            char *slash;
            if (len >= sizeof(dirbuf)) len = sizeof(dirbuf) - 1;
            memcpy(dirbuf, info.dli_fname, len);
            dirbuf[len] = '\\0';
            slash = strrchr(dirbuf, '/');
            if (slash) {
                *slash = '\\0';
                PyOS_snprintf(alt, sizeof(alt), "%s/libpyext_%s.so", dirbuf, shortname);
                handle = dlopen(alt, dlopenflags);
            }
        }
    }
#endif

    if (handle == NULL) {"""
if old not in text:
    raise SystemExit("dynload_shlib.c no longer matches the Android extension patch")
text = text.replace(old, new, 1)
needle = "#ifdef HAVE_DLFCN_H\n#include <dlfcn.h>\n#endif\n"
insert = needle + "#ifdef __ANDROID__\n#include <string.h>\n#endif\n"
if needle not in text:
    raise SystemExit("dlfcn include not found")
if "#ifdef __ANDROID__\n#include <string.h>" not in text:
    text = text.replace(needle, insert, 1)
path.write_text(text)
print("patched", path)
PY

cd "$SRC"
python3 Android/android.py build "$HOST"

PREFIX="$SRC/cross-build/$HOST/prefix"
PYBIN="$(find "$PREFIX/bin" -name 'python3.13' -type f | head -1)"
if [[ -z "$PYBIN" ]]; then
  echo "没有找到 Android Python：$PREFIX/bin" >&2
  exit 1
fi
STDLIB="$(echo "$PREFIX"/lib/python3.13)"
if [[ ! -d "$STDLIB" ]]; then
  echo "没有找到标准库：$STDLIB" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
cp -f "$PYBIN" "$OUT_DIR/libgrain_python.so"
chmod 755 "$OUT_DIR/libgrain_python.so"

for name in libpython3.13.so libssl_python.so libcrypto_python.so libsqlite3_python.so; do
  if [[ ! -f "$PREFIX/lib/$name" ]]; then
    echo "缺少依赖库：$name" >&2
    exit 1
  fi
  cp -f "$PREFIX/lib/$name" "$OUT_DIR/$name"
done

DYNLOAD="$STDLIB/lib-dynload"
if [[ -d "$DYNLOAD" ]]; then
  for so in "$DYNLOAD"/*.so; do
    base="$(basename "$so")"
    short="${base%%.cpython-*}"
    if [[ "$short" == "$base" ]]; then
      short="${base%.so}"
    fi
    cp -f "$so" "$OUT_DIR/libpyext_${short}.so"
  done
fi

python3 - "$STDLIB" "$ASSET" <<'PY'
import sys, zipfile
from pathlib import Path
stdlib = Path(sys.argv[1])
dest = Path(sys.argv[2])
root = stdlib.parent.parent  # prefix, so zip entries are lib/python3.13/...
skip_dirs = {"test", "tests", "idle_test", "__pycache__", "ensurepip", "turtledemo"}
dest.parent.mkdir(parents=True, exist_ok=True)
count = 0
with zipfile.ZipFile(dest, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for path in stdlib.rglob("*"):
        if not path.is_file():
            continue
        if any(part in skip_dirs for part in path.relative_to(stdlib).parts):
            continue
        if path.suffix in {".pyc", ".pyo"}:
            continue
        rel = path.relative_to(root).as_posix()
        archive.write(path, rel)
        count += 1
print(f"python stdlib: {dest} ({count} files)")
PY

STRIP="$NDK_REAL/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"
"$STRIP" --strip-debug "$OUT_DIR/libgrain_python.so" "$OUT_DIR"/libpython3.13.so "$OUT_DIR"/libssl_python.so "$OUT_DIR"/libcrypto_python.so "$OUT_DIR"/libsqlite3_python.so "$OUT_DIR"/libpyext_*.so

echo "Android Python is at $OUT_DIR/libgrain_python.so"
