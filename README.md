# Nexa Operations Android

This repository contains the Nexa Operations Android client. The Android project is in [`apps/operations-android`](apps/operations-android). It preserves the native session and protected HTTP foundation and implements the client UI for access, context selection, Operations work entry, manual product search, and confirmed SKU review.

## Toolchain

- JDK 17, Gradle 9.6.0, Android Gradle Plugin 9.4.1, Kotlin 2.4.20, KSP 2.3.12
- Android SDK 37 for compilation and target behavior; minSdk 29
- Compose BOM 2026.09.00, Navigation 3 1.1.7, Hilt 2.60.1
- Retrofit 3.0.0, OkHttp 4.12.0, kotlinx serialization 1.11.0

Install JDK 17, Android SDK package `platforms;android-37.0`, and `build-tools;36.0.0`. The checked in Gradle wrapper downloads Gradle 9.6.0. Use an API 37 emulator for feature verification and an API 29 emulator for promotion verification. See the [verification guide](docs/verification.md) for exact commands and evidence locations.

## Modules

| Module | Responsibility |
| --- | --- |
| `:app` | Hilt composition, session-driven Navigation 3 root, authority-gated destinations, debug review entry |
| `:core:auth` | Protected refresh credential store and session coordinator |
| `:core:network` | Native auth transport, origin guard, problem mapping, protected calls |
| `:core:designsystem` | Nexa Compose theme and the reusable primitives used by Operations |
| `:feature:access` | Access and workforce-context UI state, ViewModel, and screens |
| `:feature:warehouse` | Operations work entry, manual search, and confirmed SKU UI state and screens |

The API owns authentication, Tenant and Workspace authorization, and business outcomes. Client state and permission hints never grant server authority. Identity sign-in and workforce-context selection use the released Nexa API v0.18.0 contract; every issued session is checked through `GET /api/v1/session` before the client enters `Active`. Warehouse search and SKU confirmation still return `IntegrationUnavailable`; there is no local stock authority or fabricated server result. The debug-only review activity uses visibly synthetic fixtures. These flows remain technical continuation work, not Product Acceptance. See [foundation architecture](docs/android-foundation.md) for boundaries and [native session security](docs/native-session.md) for rotation behavior.

## Build and verify

From `apps/operations-android` with `JAVA_HOME` pointing to JDK 17:

```sh
./gradlew :app:assembleDebug
./gradlew verifyAndroidArchitecture ktlintCheck lintDebug
./gradlew :core:auth:testDebugUnitTest :core:network:testDebugUnitTest
./gradlew :feature:access:testDebugUnitTest :feature:warehouse:testDebugUnitTest
./gradlew :core:auth:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

Gradle dependency verification uses `apps/operations-android/gradle/verification-metadata.xml`. Run the gates with `--dependency-verification strict`; the [verification guide](docs/verification.md) lists the complete command. Release assembly requires an explicitly supplied non-local HTTPS API origin:

```sh
./gradlew :app:assembleRelease -PnexaReleaseApiBaseUrl="$APPROVED_NEXA_API_ORIGIN"
```

The variable must contain an approved non-local HTTPS root origin; none is configured in this repository. A verification build can use a separate HTTPS origin to exercise R8 and resource shrinking; that artifact is not a production distribution. Do not publish or install it as a production client.

The CI workflow in [android-verify.yml](.github/workflows/android-verify.yml) runs applicable Android checks and publishes the stable `verify` status. Feature verification includes API 37 instrumentation. Promotion verification also includes API 29 instrumentation and release shrinking.

## Current limits

No scanner, dashboard, inventory workflow, future module, API change, live account fixture, production API endpoint, or distribution signing configuration is included. No physical-device acceptance is claimed. Local sign-out clears protected state even when server revocation cannot be confirmed. System and production readiness remain separate gates.
