# HarolHome TV

A personalized, remote-first home screen for Android TV and Google TV: app favorites, Continue Watching, themes, location-aware weather, wallpaper personalization, and consistent focus feedback.

- [Español](README.es.md) · [Build and install](docs/INSTALLATION.md) · [TV brand adaptation](docs/TV_BRAND_ADAPTATION.md) · [Contributing](CONTRIBUTING.md) · [Notices](NOTICE.md)

## Project origin

HarolHome TV is a customized continuation and rebrand of the public **Tv-Personalize** project created by Harol Ardila: [github.com/harolito1995/Tv-Personalize](https://github.com/harolito1995/Tv-Personalize). This repository keeps that source history and acknowledges inherited work. The new name identifies this customized version; it does not claim a clean-room rewrite or conceal the earlier public source.

The Android application ID remains `com.gathin.tvhome` so existing installations can be updated without uninstalling or losing their data. It is a technical identifier, not the product name.

## Features

- Remote-friendly app dock and app grid, configurable for each TV.
- Android TV Continue Watching entries when a streaming app publishes compatible Watch Next data.
- Four visual themes, focus feedback, search, app filters, theme and wallpaper previews.
- Shortcuts for TV inputs, Wi-Fi, and system settings where the device exposes compatible activities.
- Pause active media sessions on return to Home; configured music apps can be exempted. Session-aware pause requires Android notification-listener access.
- Weather from a recent Android location, IP-based fallback, or a manually selected city; cached forecasts from Open-Meteo.

Physically reviewed on a Caixun 4K Android TV 14. The TV outputs 3840×2160 at 60 Hz but reports a 1920×1080 logical app viewport and density 320. HarolHome does not change display modes, upscale video, or guarantee another TV’s output. Video quality is selected in each streaming app and depends on that app, content, subscription, network, and display.

## Build

Requirements: JDK 17 and Android SDK platform 34. The Gradle wrapper pins the build versions.

```powershell
cd launcher
.\gradlew.bat :app:assembleDebug
```

On macOS/Linux use `./gradlew :app:assembleDebug`. Output: `launcher/app/build/outputs/apk/debug/app-debug.apk`. Generated APKs and device-specific captures/backups are not published.

## Install and adapt

Follow [the install guide](docs/INSTALLATION.md) and [the brand adaptation guide](docs/TV_BRAND_ADAPTATION.md). Updating uses `adb install -r`, retaining data. Setup does not clear app data or disable the manufacturer’s launcher. Per-TV package IDs and grid options live in `launcher/app/src/main/res/values/config.xml`.

Only the Caixun configuration above has been physically checked. Contributions for other TVs should report the exact model/OS, changes, and checks performed, with personal network details removed.

## Privacy

No account or analytics is included. Weather can send coordinates to Open-Meteo and may ask ipapi.co for IP-based location when no recent device location is available. Wallpaper browsing can contact its configured provider. The media listener is used to inspect active media sessions for pause-on-Home, not to read notification text. See [NOTICE.md](NOTICE.md).

## License

MIT; see [LICENSE](LICENSE). The included Outfit font has a separate SIL Open Font License notice in [launcher/FONT-LICENSE-OFL.txt](launcher/FONT-LICENSE-OFL.txt).