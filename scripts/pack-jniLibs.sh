#!/bin/bash
# Pack arm64 jniLibs for CI / GitHub Release restore.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
OUT="${1:-$ROOT/native-libs-arm64-v8a.tar.gz}"

if [[ ! -f "$SRC/libgrain_node.so" || ! -f "$SRC/libgrain_python.so" || ! -f "$SRC/libgrain_git.so" ]]; then
  echo "缺少原生库，请先在本机编好 jniLibs：$SRC" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT")"
tar -C "$SRC/.." -czf "$OUT" arm64-v8a
ls -lh "$OUT"
echo "上传示例：gh release upload native-libs $OUT --clobber"
