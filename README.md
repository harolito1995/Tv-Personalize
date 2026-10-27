# HarolHome TV

A personalized, remote-first home screen for Android TV and Google TV: app favorites, Continue Watching, themes, location-aware weather, wallpaper personalization, and consistent focus feedback.

- [Español](README.es.md) · [Build and install](docs/INSTALLATION.md) · [TV brand adaptation](docs/TV_BRAND_ADAPTATION.md) · [Caixun screenshots](docs/SCREENSHOTS.md) · [Contributing](CONTRIBUTING.md) · [Notices](NOTICE.md)

## Project origin

HarolHome TV is a customized continuation and rebrand of the public **Tv-Personalize** project created by Harol Ardila: [github.com/harolito1995/Tv-Personalize](https://github.com/harolito1995/Tv-Personalize). This repository keeps that source history and acknowledges inherited work. The new name identifies this customized version; it does not claim a clean-room rewrite or conceal the earlier public source.

The Java source namespace and folders use `com.harolhome.tv`. The app ID and Java namespace are `com.harolhome.tv`. Version 1.0 installs as a fresh app; it does not import data from the earlier `com.gathin.tvhome` package. The old app is left installed until the new one is checked.

## Caixun TV screenshots

Real screenshots from the Caixun 4K Android TV 14 installation, showing the earlier C1.3 Plan 4 baseline and the current HarolHome TV screen. Personal Continue Watching artwork and titles are masked in these public copies. See [capture notes](docs/SCREENSHOTS.md).

| Before: C1.3 Plan 4 | After: HarolHome TV |
| --- | --- |
| ![Caixun TV before the HarolHome visual update](docs/images/caixun-before.jpg) | ![Caixun TV running HarolHome TV](docs/images/caixun-after.jpg) |

## Features

- Remote-friendly app dock and app grid, configurable for each TV.
- Cinematic and 100% dynamic Continue Watching: dual real-time automatic synchronization via AOSP Watch Next (`content://android.media.tv/watch_next_program`) and live `MediaSession` capture on return to Home (title, official scene artwork, season/episode subtitle, and real progress percentage without app-icon pollution), persistent cinematic cache, and portrait aspect ratio detection (`FIT_CENTER`).
- Verified support for Kalley 43" TV (Android TV 14): dock app cards enlarged to 108dp (+20%), dynamic ambient backlight glow matching focused app brand colors, clean instant Home button navigation, and stock screensaver deactivation (see [Kalley adaptation guide](docs/KALLEY_ADAPTATION.md)).
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

On macOS/Linux use `./gradlew :app:assembleDebug`. Output: `launcher/app/build/outputs/apk/debug/app-debug.apk`. Generated APKs, backups, and raw device data are not published. The two curated, redacted Caixun screenshots above are included as project documentation.

## Install and adapt

Follow [the install guide](docs/INSTALLATION.md) and [the brand adaptation guide](docs/TV_BRAND_ADAPTATION.md). Updating uses `adb install -r`, retaining data. Setup does not clear app data or disable the manufacturer’s launcher. Per-TV package IDs and grid options live in `launcher/app/src/main/res/values/config.xml`.

Only the Caixun configuration above has been physically checked. Contributions for other TVs should report the exact model/OS, changes, and checks performed, with personal network details removed.

## Privacy

No account or analytics is included. Weather can send coordinates to Open-Meteo and may ask ipapi.co for IP-based location when no recent device location is available. Wallpaper browsing can contact its configured provider. The media listener is used to inspect active media sessions for pause-on-Home, not to read notification text. See [NOTICE.md](NOTICE.md).

## License

MIT; see [LICENSE](LICENSE). The included Outfit font has a separate SIL Open Font License notice in [launcher/FONT-LICENSE-OFL.txt](launcher/FONT-LICENSE-OFL.txt).
