# Adapt HarolHome TV to another brand

Package IDs, input shortcuts, permissions, display scaling, and firmware behavior vary by TV. Begin with read-only inspection and reversible changes.

## 1. Record the target device

Record manufacturer, exact model, Android/Google TV version, firmware, display mode, logical app viewport, density, and ADB serial. Identify package IDs for the owner’s apps and TV tuner.

```sh
adb -s SERIAL shell getprop ro.product.manufacturer
adb -s SERIAL shell getprop ro.product.model
adb -s SERIAL shell getprop ro.build.version.release
adb -s SERIAL shell getprop ro.build.version.sdk
adb -s SERIAL shell pm list packages
adb -s SERIAL shell wm size
adb -s SERIAL shell wm density
adb -s SERIAL shell dumpsys display
```

Remove Wi-Fi names, public IPs, account names, pairing codes, and ADB keys from reports and captures.

## 2. Configure the TV

Edit `launcher/app/src/main/res/values/config.xml`:

- `dock_apps`: favorite app package IDs, ordered.
- `hidden_apps`: system apps to omit from the grid; keep this list minimal.
- `live_tv_package`: the tuner/Live TV package, or empty if unsupported.
- `keep_playing_apps`: music app IDs exempted from pause-on-Home.
- `columns`: app-grid columns suitable for the logical viewport.

Find package IDs with `adb shell pm list packages`; inspect launchable activities before changing shortcuts. Do not assume another TV brand uses the same IDs. The Java source namespace, folders, and Android application ID are `com.harolhome.tv`. The earlier Caixun build used `com.gathin.tvhome`; HarolHome 1.0 installs as a separate app and does not migrate its preferences. Keep the old app installed until Home, media pause, weather, and settings have been verified on the new package.

## 3. Verify TV-specific behavior

Check Home navigation, installed apps, dock, Continue Watching, inputs, Wi-Fi/settings shortcuts, search/IME, theme previews, wallpaper sources, weather with/without location permission, and media pause on exit. Confirm configured music apps remain playing. Do not disable or uninstall the stock launcher during routine testing.

Android notification-listener access can inspect notification metadata. HarolHome uses it to identify active media sessions for pause-on-Home; explain this behavior to the owner and enable it only with consent.

## 4. Keep output mode separate from app layout

A panel may output 4K while Android gives applications a lower logical viewport. Compare `dumpsys display`, `wm size`, and `wm density`. HarolHome does not force resolution/density. Do not add a `wm size` or density override without verifying remote input, text, accessibility, performance, and rollback. Streaming video quality is selected in the streaming app; the launcher cannot add 4K support to an app or source.

## Pull request evidence

Include model/OS/firmware, configuration changes, what was tested, build and test results, and limitations. Share only redacted screenshots. Never include APKs, ADB keys, preferences, raw dumps, or backups. See [CONTRIBUTING.md](../CONTRIBUTING.md).
