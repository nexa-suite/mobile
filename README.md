# Nexa Operations Android

This repository contains the technical foundation for the Nexa Operations Android application. The Android project is in [`apps/operations-android`](apps/operations-android). It boots a Compose shell, observes server-confirmed session state, and provides native authentication transport and protected HTTP request boundaries. Product workflows are not implemented.

## Toolchain

- JDK 17, Gradle 9.6.0, Android Gradle Plugin 9.4.1, Kotlin 2.4.20, KSP 2.3.12
- Android SDK 37 for compilation and target behavior; minSdk 29
- Compose BOM 2026.09.00, Navigation 3 1.1.7, Hilt 2.60.1
- Retrofit 3.0.0, OkHttp 4.12.0, kotlinx serialization 1.11.0

Install JDK 17, Android SDK platform 37, and the SDK build tools. The checked in Gradle wrapper downloads Gradle 9.6.0. Use an API 37 emulator for feature verification and an API 29 emulator for promotion verification.

## Modules

| Module | Responsibility |
| --- | --- |
| `:app` | Hilt composition, session driven Navigation 3 root, technical shell |
| `:core:auth` | Protected refresh credential store and session coordinator |
| `:core:network` | Native auth transport, origin guard, problem mapping, protected calls |
| `:core:designsystem` | Minimal Compose theme for the technical shell |

The API owns authentication, Tenant and Workspace authorization, and business outcomes. Client state and permission hints never grant server authority. See [foundation architecture](docs/android-foundation.md) for boundaries and [native session security](docs/native-session.md) for rotation behavior.

## Build and verify

From `apps/operations-android` with `JAVA_HOME` pointing to JDK 17:

```sh
./gradlew :app:assembleDebug
./gradlew verifyAndroidArchitecture ktlintCheck lintDebug
./gradlew :core:auth:testDebugUnitTest :core:network:testDebugUnitTest
./gradlew :core:auth:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

Gradle dependency verification is enabled in strict mode through `gradle/verification-metadata.xml`. Release assembly requires an explicitly supplied non-local HTTPS API origin:

```sh
./gradlew :app:assembleRelease -PnexaReleaseApiBaseUrl="$APPROVED_NEXA_API_ORIGIN"
```

The variable must contain an approved non-local HTTPS root origin; none is configured in this repository. A verification build can use a separate HTTPS origin to exercise R8 and resource shrinking; that artifact is not a production distribution. Do not publish or install it as a production client.

The CI workflow in [android-verify.yml](.github/workflows/android-verify.yml) runs applicable Android checks and publishes the stable `verify` status. Feature verification includes API 37 instrumentation. Promotion verification also includes API 29 instrumentation and release shrinking.

## Current limits

There are no Product screens, Buyer client changes, live account fixtures, production API endpoint, distribution signing configuration, or physical device acceptance in this foundation. Local sign-out clears protected state even when server revocation cannot be confirmed. System and production readiness remain separate gates.
