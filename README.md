# Nexa Operations Android

This repository contains the Nexa Operations Android client. The Android project is in [`apps/operations-android`](apps/operations-android). It preserves the native session and protected HTTP foundation and implements the client UI for access and context selection, permission-gated warehouse and dispatch work, and current Driver delivery operations.

## Toolchain

- JDK 17, Gradle 9.7.1, Android Gradle Plugin 9.4.1, Kotlin 2.4.20, KSP 2.3.12
- Android SDK 37 for compilation and target behavior; minSdk 29
- Compose BOM 2026.09.00, Navigation 3 1.1.7, Hilt 2.60.1
- Retrofit 3.0.0, OkHttp 4.12.0, kotlinx serialization 1.11.0

Install JDK 17, Android SDK package `platforms;android-37.0`, and `build-tools;36.0.0`. The checked in Gradle wrapper downloads Gradle 9.7.1. Use an API 37 emulator for feature verification and an API 29 emulator for promotion verification. See the [verification guide](docs/verification.md) for exact commands and evidence locations.

## Modules

| Module | Responsibility |
| --- | --- |
| `:app` | Hilt composition, session-driven Navigation 3 root, authority-gated destinations, debug review entry |
| `:core:auth` | Protected refresh credential store and session coordinator |
| `:core:network` | Native auth transport, origin guard, problem mapping, protected calls |
| `:core:designsystem` | Nexa Compose theme and the reusable primitives used by Operations |
| `:feature:access` | Access and workforce-context UI state, ViewModel, and screens |
| `:feature:warehouse` | Operations work entry, manual search, and confirmed SKU UI state and screens |
| `:feature:dispatch` | Dispatch assignment, readiness, handover, and outbound operational screens |
| `:feature:delivery` | Current Driver delivery, attempt, arrival, outcome, proof, incident, and handoff UI |

The API owns authentication, Tenant and Workspace authorization, and business outcomes. Client state and permission hints never grant server authority. Every issued session is checked through `GET /api/v1/session` before the client enters `Active`. Production gateways also consume protected Catalog list/detail reads. Manual identification requires explicit search and candidate selection; only a current successful detail response creates a display-only confirmed SKU. The debug-only review activity uses visibly synthetic fixtures. Bounded validation evidence and release limits are recorded in the [technical verification](docs/technical-verification.md) record; they do not establish Product Acceptance. See [product boundaries](docs/operations-product-boundaries.md), [design adoption seams](docs/mobile-design-adoption.md), [foundation architecture](docs/android-foundation.md) and [native session security](docs/native-session.md).

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

Automatic IoT and advanced routing remain outside approved V1 scope. The app uses existing API contracts and does not contain live account credentials, a production API endpoint, or distribution signing configuration. No physical-device acceptance is claimed. Local sign-out clears protected state even when server revocation cannot be confirmed. Product Acceptance, System Acceptance and production readiness remain separate gates.

## Current checkpoint — 2026-10-03

The current API verification record reports 675 tests with zero failures. The Android Studio validation record for the recorded implementation snapshot reports 456 JVM tests with zero failures, errors or skips. This Android checkpoint is version `0.5.0` (`versionCode 6`). A local strict run passed `verifyAndroidArchitecture` and `:app:assembleDebug`; the version bump has not been revalidated on a device. These results are technical evidence only and do not establish Product Acceptance.

The latest recorded Android validation passed 53 instrumentation tests on each API 29 and API 37 emulator. A Samsung API 36 smoke installed and launched the debug APK, but did not perform sign-in, role traversal, permission grant or screenshot capture. Product Acceptance, System Acceptance, physical-device workflow validation and production readiness remain open.
