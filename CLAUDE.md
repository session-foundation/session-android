# Session Android — build & run notes

Standard Gradle/Android project. See `ARCHITECTURE.md` for architecture and
`RELEASE.md` for the full build/release process.

## Toolchain

- JDK 17+ (JDK 21 works).
- Android SDK via `ANDROID_HOME` (set here to `~/Android/Sdk`) or `sdk.dir` in
  `local.properties`.
- `./gradlew` wrapper; `adb` at `/usr/bin/adb`; the emulator binary is at
  `$ANDROID_HOME/emulator/emulator` (not on PATH).

## Flavors and build types

- Distribution flavors: `play`, `fdroid`, `website`, `huawei`.
  - `play` and `fdroid` are the Firebase-enabled variants (`firebaseEnabledVariants`
    in `app/build.gradle.kts`): both include Firebase Cloud Messaging for push,
    but exclude firebase core/analytics/measurement. `huawei` uses HMS push;
    `website` uses no-op push wiring.
- Build types: `debug`, `release`, `releaseWithDebugMenu`, `qa`, `automaticQa`.
- CI (`.github/workflows/build_and_test.yml`) builds the `qa` type across all
  flavors plus a `test` job.

## Build / install / run (play, for local testing)

```sh
./gradlew :app:assemblePlayDebug        # build APK only (no device needed)
./gradlew :app:installPlayDebug         # build + install to a running device/emulator
```

Huawei builds need the flag, e.g. `./gradlew assembleHuaweiDebug -Phuawei`.

### Emulator

Available AVDs (`$ANDROID_HOME/emulator/emulator -list-avds`) include
`Pixel_6_API_33`. Start one detached, then install:

```sh
"$ANDROID_HOME/emulator/emulator" -avd Pixel_6_API_33 &
adb wait-for-device                     # then poll sys.boot_completed
./gradlew :app:installPlayDebug
```

After install, confirm the package id (it is suffixed per flavor/build type):
`adb shell pm list packages | grep loki`, then launch it.

## Localization / strings — IMPORTANT

`app/src/main/res/values*/strings.xml` and `NonTranslatableStringConstants.kt`
are **generated from Crowdin — do not hand-edit them.** Text changes are made in
Crowdin and synced in by the `session-foundation/session-shared-scripts`
pipeline. See `ARCHITECTURE.md` §13 (Localization and Translations) for the full
flow, the approved-only export gate, and the ordering to ship a text change.

## Branching

- `master` = production; `dev` = integration branch; PRs target `dev`.
- Crowdin translation PRs land on branch `feature/update-crowdin-translations` → `dev`.
