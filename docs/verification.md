# Android foundation verification

Run Gradle commands from `apps/operations-android` with JDK 17. Install Android SDK packages `platforms;android-37.0`, `build-tools;36.0.0`, and `platform-tools`. Keep the Gradle wrapper and dependency verification metadata in the repository; review the bytes and source of a new dependency artifact before accepting its checksum.

## Local static and JVM gates

```sh
cd apps/operations-android
./gradlew verifyAndroidArchitecture ktlintCheck lintDebug \
  :core:auth:testDebugUnitTest :core:network:testDebugUnitTest \
  :app:assembleDebug --dependency-verification strict
```

The JUnit XML files are in `core/auth/build/test-results/testDebugUnitTest` and `core/network/build/test-results/testDebugUnitTest`. Lint reports are under each module's `build/reports/lint-results-debug.html`. A zero exit status is necessary, but inspect the XML test and failure counts before recording a result.

## Emulator gates

Run one booted emulator at a time and confirm its API level before each connected test run:

```sh
adb devices -l
adb shell getprop ro.build.version.sdk
./gradlew :core:auth:connectedDebugAndroidTest :app:connectedDebugAndroidTest \
  --dependency-verification strict
```

Use an API 37.0 image for feature verification and an API 29 image for promotion verification. On Apple Silicon the local image ABI is `arm64-v8a`; the Linux CI job uses `x86_64`. Install the matching `google_apis` system image for the host architecture. Check the generated XML in each module's `build/outputs/androidTest-results/connected/debug` directory after each run. Gradle can replace results from an earlier emulator run, so retain the API level, command, timestamp, and test counts in the execution record.

## Release build safety

Release assembly requires a non-local HTTPS root origin supplied at build time. The example below exercises shrinking with a verification-only origin; its APK is not configured for Nexa production service or distribution.

```sh
./gradlew :app:assembleRelease \
  -PnexaReleaseApiBaseUrl=https://github.com/ \
  --dependency-verification strict
```

Also verify that `:app:validateReleaseEndpoint` fails when the property is omitted or set to a cleartext or local origin. The release build uses R8 code and resource shrinking. No production endpoint or distribution signing material is stored here.

## CI status

The workflow `.github/workflows/android-verify.yml` emits one stable `verify` status. For Android changes on a feature branch it requires architecture, formatting, lint, JVM contract tests, debug assembly, and API 37 instrumentation. For a pull request to `main` or a push to `develop` or `main`, it additionally requires release assembly and API 29 instrumentation. Read the individual job conclusions along with `verify`; a skipped promotion job is expected on a feature push. A green local run does not substitute for the workflow result on the pushed commit.
