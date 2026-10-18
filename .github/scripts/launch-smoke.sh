#!/usr/bin/env bash
# Launch smoke (like the Mac CI's): install the debug APK on the running emulator, start it with the
# fake-API flag, and fail if it isn't still running and resumed after 5 s, or logged a crash or ANR.
# Usage: .github/scripts/launch-smoke.sh [apk]   (needs adb and one booted device)
set -euo pipefail

APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
PKG=com.mekari.project
ACTIVITY=$PKG/.MainActivity

adb install -r "$APK"
adb logcat -c
adb shell am start -W -n "$ACTIVITY" --ez com.mekari.project.UI_TEST_FAKE_API true
sleep 5

fail() {
  echo "launch smoke failed: $1"
  echo "--- crash buffer"; adb logcat -b crash -d || true
  echo "--- app log (last 200 lines)"; adb logcat -d | grep -E "$PKG|AndroidRuntime|ActivityManager" | tail -200 || true
  exit 1
}

pid=$(adb shell pidof "$PKG" | tr -d '\r' || true)
[ -n "$pid" ] || fail "$PKG is not running after 5 s"
adb shell dumpsys activity activities | tr -d '\r' | grep -E "(topResumedActivity|mResumedActivity).*$ACTIVITY" > /dev/null \
  || fail "$ACTIVITY is not the resumed activity"
if adb logcat -b crash -d | grep -q "$PKG"; then fail "crash logged"; fi
if adb logcat -d | grep -qE "FATAL EXCEPTION|ANR in $PKG"; then fail "fatal exception or ANR logged"; fi

echo "app stayed up for 5 s (pid $pid)"
adb shell am force-stop "$PKG"
