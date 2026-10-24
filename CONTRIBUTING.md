# Contributing to HarolHome TV

Developer pull requests are welcome. Keep each change focused and include the information needed to reproduce it.

## Before submitting

1. Read the [project guide](README.md), [installation](docs/INSTALLATION.md), and [TV adaptation](docs/TV_BRAND_ADAPTATION.md) docs.
2. Describe the user-visible behavior and exact devices tested.
3. Do not commit APKs, build output, local.properties, signing keys, ADB keys, preference backups, raw dumps, or unredacted screenshots.
4. Include licenses/notices for new fonts, images, icons, and other third-party assets. Do not add streaming-service artwork.
5. Preserve application ID unless a migration plan is included. Do not clear data, disable the stock launcher, or change display mode/density as routine setup.
6. Keep English and Spanish strings in sync where relevant.

## Build and tests

Use JDK 17 and Android SDK platform 34.

```sh
cd launcher
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

On Windows use `gradlew.bat`. State which commands you ran; distinguish physical-TV and emulator results.

## Pull request checklist

Include the change summary, affected screens/configuration, build/test results, TV model and firmware for device claims, privacy/permission/network changes, and limitations. Screenshots must have personal information removed.

Contributors confirm they have the right to submit their work and agree that accepted contributions may be distributed under the repository’s MIT License. Project provenance is documented in [NOTICE.md](NOTICE.md).