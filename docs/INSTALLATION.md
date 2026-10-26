# Build and install HarolHome TV

For a developer or coding agent starting from a clean checkout. The steps update the app without clearing its data or disabling the manufacturer’s launcher.

## Requirements

- Android TV/Google TV, Android 8.0 (API 26) or newer.
- JDK 17; Android SDK platform 34; Android Platform Tools (ADB).
- USB debugging or network debugging enabled on the TV.

On Windows, Android Studio usually provides the JDK and SDK. Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to `%LOCALAPPDATA%\Android\Sdk`. On macOS/Linux, set them to the local Android Studio JDK/SDK paths. Never commit `local.properties`, signing keys, ADB keys, device backups, or personal screenshots.

## Connect and identify the TV

Enable debugging on the TV. For wireless debugging, pair with the TV’s displayed pairing code and address. For legacy ADB network mode, use the IP and port shown by the TV; do not assume port 5555.

PowerShell example:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb connect "TV_IP:TV_PORT"
& $adb devices -l
```

Use the exact serial shown by `adb devices -l`. If more than one device appears, always use `-s SERIAL`.

## Build

From the repository root:

```powershell
cd launcher
.\gradlew.bat :app:assembleDebug
```

macOS/Linux: `./gradlew :app:assembleDebug`. Output: `launcher/app/build/outputs/apk/debug/app-debug.apk`.

## Back up and update

HarolHome TV 1.0 uses the new package ID `com.harolhome.tv` and installs as a fresh app. It does not replace or migrate data from the earlier `com.gathin.tvhome` build. Leave that old app installed until the new Home screen and optional features are verified. Store any APK backup outside the public repository and verify its hash.

```powershell
& $adb -s "SERIAL" shell pm path com.harolhome.tv
& $adb -s "SERIAL" shell dumpsys package com.harolhome.tv
& $adb -s "SERIAL" install -r ".\app\build\outputs\apk\debug\app-debug.apk"
```

Check the serial and APK before installing. The `-r` update retains app data when package ID and signing key match. Never use `pm clear` or uninstall for a routine update. Press Home and choose HarolHome when Android asks. To select it explicitly:

```powershell
& $adb -s "SERIAL" shell cmd package set-home-activity --user 0 com.harolhome.tv/com.harolhome.tv.MainActivity
```

The stock launcher stays installed and enabled. Keep a route back to it in Android’s Home-app settings.

## Optional access

Enable only features the TV owner accepts:

- **Weather location:** allow location access in Android app settings. Without it, HarolHome tries IP-based geolocation, then uses the saved/default city.
- **Pause media on Home:** `adb -s SERIAL shell cmd notification allow_listener com.harolhome.tv/com.harolhome.tv.MediaSessionListener`. Confirm the TV’s prompt if shown. This listener is used to identify active playback sessions, not to read notification text.
- **Local/USB wallpapers:** grant storage access only when needed.

## Recovery

If the Home key does not open HarolHome, choose the previous launcher from Android’s Home-app settings. The stock launcher was not disabled or removed. If an update fails, verify the APK signature; do not clear data to work around a signing mismatch.
