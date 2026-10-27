#!/bin/bash
# Cross-compile Git for Android arm64 with HTTPS (OpenSSL + curl) and package
# it as app native libraries: libgrain_git.so + libgrain_git_remote_http.so.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/.runtime-build"
SRC="$BUILD/git-2.51.0"
CURL_VER="8.11.1"
CURL_SRC="$BUILD/curl-$CURL_VER"
CURL_PREFIX="$BUILD/curl-android-prefix"
OPENSSL_PREFIX="$BUILD/Python-3.13.7/cross-build/aarch64-linux-android/prefix"
NDK="${GRAIN_NDK_ROOT:-$ROOT/.android-sdk/ndk/27.0.12077973}"
TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
OUT_DIR="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
OUT_GIT="$OUT_DIR/libgrain_git.so"
OUT_HTTP="$OUT_DIR/libgrain_git_remote_http.so"
CA_ASSET="$ROOT/android/app/src/main/assets/cacert.pem"
API=24
HOST=aarch64-linux-android
CC="$TC/${HOST}${API}-clang"

if [[ ! -x "$CC" ]]; then
  echo "缺少 Android NDK clang：$CC" >&2
  exit 1
fi
if [[ ! -f "$SRC/Makefile" ]]; then
  echo "缺少 Git 源码：$SRC" >&2
  exit 1
fi
if [[ ! -f "$OPENSSL_PREFIX/include/openssl/ssl.h" || ! -f "$OPENSSL_PREFIX/lib/libssl.a" ]]; then
  echo "缺少 Android OpenSSL（可先跑 scripts/build-android-python.sh）：$OPENSSL_PREFIX" >&2
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

if [[ ! -f "$CURL_SRC/configure" ]]; then
  mkdir -p "$BUILD"
  if [[ ! -f "$BUILD/curl-${CURL_VER}.tar.gz" ]]; then
    curl -fL --retry 3 --retry-delay 2 \
      "https://github.com/curl/curl/releases/download/curl-8_11_1/curl-${CURL_VER}.tar.gz" \
      -o "$BUILD/curl-${CURL_VER}.tar.gz"
  fi
  tar -xzf "$BUILD/curl-${CURL_VER}.tar.gz" -C "$BUILD"
fi

if [[ ! -f "$CURL_PREFIX/lib/libcurl.a" ]]; then
  rm -rf "$CURL_PREFIX"
  mkdir -p "$CURL_PREFIX"
  pushd "$CURL_SRC" >/dev/null
  make distclean >/dev/null 2>&1 || true
  ./configure \
    --host="$HOST" \
    --prefix="$CURL_PREFIX" \
    --with-ssl="$OPENSSL_PREFIX" \
    --with-zlib \
    --disable-shared \
    --enable-static \
    --disable-ldap \
    --disable-ldaps \
    --disable-rtsp \
    --disable-dict \
    --disable-telnet \
    --disable-tftp \
    --disable-pop3 \
    --disable-imap \
    --disable-smb \
    --disable-smtp \
    --disable-gopher \
    --disable-mqtt \
    --without-libpsl \
    --without-brotli \
    --without-zstd \
    --without-libidn2 \
    --without-nghttp2 \
    --without-libssh2 \
    CC="$CC" \
    AR="$TC/llvm-ar" \
    RANLIB="$TC/llvm-ranlib" \
    STRIP="$TC/llvm-strip" \
    CFLAGS="-O2 -fPIC" \
    CPPFLAGS="-I$OPENSSL_PREFIX/include" \
    LDFLAGS="-L$OPENSSL_PREFIX/lib -Wl,-z,max-page-size=16384" \
    LIBS="-lssl -lcrypto -lz -ldl"
  make -j"$(sysctl -n hw.ncpu)"
  make install
  popd >/dev/null
fi

CURL_STATIC_LIBS="$("$CURL_PREFIX/bin/curl-config" --static-libs)"
echo "curl static libs: $CURL_STATIC_LIBS"

cd "$SRC"
make clean >/dev/null 2>&1 || true
make -j"$(sysctl -n hw.ncpu)" \
  CC="$CC" \
  AR="$TC/llvm-ar" \
  RANLIB="$TC/llvm-ranlib" \
  HOSTCC="$(xcrun --find clang)" \
  uname_S=Linux \
  uname_M=aarch64 \
  uname_R=android \
  CFLAGS="-O2 -fPIC" \
  LDFLAGS="-Wl,-z,max-page-size=16384" \
  OPENSSLDIR="$OPENSSL_PREFIX" \
  CURLDIR="$CURL_PREFIX" \
  CURL_CONFIG="$CURL_PREFIX/bin/curl-config" \
  CURL_CFLAGS="-I$CURL_PREFIX/include" \
  CURL_LDFLAGS="$CURL_STATIC_LIBS" \
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
  git git-remote-http

python3 - "$SRC/git" "$SRC/git-remote-http" <<'PY'
import struct, sys
from pathlib import Path
for path in map(Path, sys.argv[1:]):
    data = path.read_bytes()[:20]
    assert data[:4] == b'\x7fELF' and data[4:6] == bytes([2, 1]), f'{path}: 需要 ELF64 小端'
    assert struct.unpack_from('<H', data, 16)[0] == 3, f'{path}: 需要 PIE 可执行文件'
    assert struct.unpack_from('<H', data, 18)[0] == 183, f'{path}: 需要 Android arm64'
    print('Android arm64 Git candidate:', path)
PY

mkdir -p "$OUT_DIR"
"$TC/llvm-strip" --strip-unneeded -o "$OUT_GIT" "$SRC/git"
"$TC/llvm-strip" --strip-unneeded -o "$OUT_HTTP" "$SRC/git-remote-http"
chmod 755 "$OUT_GIT" "$OUT_HTTP"
"$TC/llvm-readelf" -d "$OUT_GIT" | awk '/NEEDED/'
"$TC/llvm-readelf" -d "$OUT_HTTP" | awk '/NEEDED/'

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

if [[ ! -f "$CA_ASSET" ]]; then
  curl -fL --retry 3 "https://curl.se/ca/cacert.pem" -o "$CA_ASSET"
fi
ls -lh "$OUT_GIT" "$OUT_HTTP" "$CA_ASSET"
echo "Stripped Android Git with HTTPS is ready"
