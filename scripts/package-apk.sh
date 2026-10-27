#!/bin/bash
# Build the current web assets into a debug APK. Does not install it.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="$ROOT/.android-sdk/jdk21/Contents/Home"
export ANDROID_HOME="$ROOT/.android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "缺少 JDK：$JAVA_HOME" >&2
  exit 1
fi
if [[ ! -x "$ANDROID_HOME/platform-tools/adb" ]]; then
  echo "缺少 Android SDK：$ANDROID_HOME" >&2
  exit 1
fi

cd "$ROOT"
npm run cap:sync
cd "$ROOT/android"
./gradlew :app:assembleDebug

APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
  echo "打包结束但没有找到 APK：$APK" >&2
  exit 1
fi
echo "APK: $APK"
ls -lh "$APK"
