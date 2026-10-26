#!/usr/bin/env bash
# Build and update HarolHome on one explicitly selected Android TV.
# The stock launcher remains enabled; app data is retained with adb install -r.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_SERIAL:?Set TV_SERIAL to the exact serial from adb devices -l}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
if ! command -v "$ADB" >/dev/null 2>&1; then
  echo "ADB not found. Set ADB or ANDROID_HOME to your Android SDK." >&2
  exit 1
fi
[ -f local.properties ] || printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug --console=plain
state="$("$ADB" -s "$TV_SERIAL" get-state 2>/dev/null || true)"
[ "$state" = "device" ] || { echo "TV is not connected/authorized: $TV_SERIAL" >&2; exit 1; }
APK="app/build/outputs/apk/debug/app-debug.apk"
"$ADB" -s "$TV_SERIAL" install -r "$APK"
"$ADB" -s "$TV_SERIAL" shell cmd package set-home-activity --user 0 com.harolhome.tv/com.harolhome.tv.MainActivity
if [ "${GRANT_LOCATION:-0}" = 1 ]; then
  "$ADB" -s "$TV_SERIAL" shell pm grant com.harolhome.tv android.permission.ACCESS_FINE_LOCATION
fi
if [ "${ENABLE_AUDIO_PAUSE:-0}" = 1 ]; then
  "$ADB" -s "$TV_SERIAL" shell cmd notification allow_listener com.harolhome.tv/com.harolhome.tv.MediaSessionListener
fi
echo "HarolHome updated. App data and the stock launcher were preserved."
