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
| `:app` | Hilt composition, ViewModel factories, session-driven Navigation 3 root and Android entry points |
| `:data:operations` | Verified-session correlation, remote projection mapping and protected local metadata adapters |
| `:feature:*:contract` | Framework-free client projections, typed ports, frozen intents and selective application coordinators |
| `:core:auth` | Protected refresh credential store and session coordinator |
| `:core:network` | Native auth transport, origin guard, problem mapping, protected calls |
| `:core:designsystem` | Nexa Compose theme and the reusable primitives used by Operations |
| `:feature:access` | Access and workforce-context UI state, ViewModel, and screens |
| `:feature:warehouse` | Operations work entry, manual search, and confirmed SKU UI state and screens |
| `:feature:dispatch` | Dispatch assignment, readiness, handover, and outbound operational screens |
| `:feature:delivery` | Current Driver delivery, attempt, arrival, outcome, proof, incident, and handoff UI |
| `:feature:commercial` | Existing customer, catalog offer, field request, visit, progress and document UI extracted from app |

The [DDD-aligned client boundaries](docs/client-ddd-alignment.md) separate framework-free contracts and application coordination from presentation and data adapters. Feature modules remain client construction boundaries. The API owns authentication, Tenant and Workspace authorization, and business outcomes. Client state and permission hints never grant server authority. Every issued session is checked through `GET /api/v1/session` before the client enters `Active`. Production gateways also consume protected Catalog list/detail reads. Manual identification requires explicit search and candidate selection; only a current successful detail response creates a display-only confirmed SKU. The debug-only review activity uses visibly synthetic fixtures. Bounded validation evidence and release limits are recorded in the [technical verification](docs/technical-verification.md) record; they do not establish Product Acceptance. See [product boundaries](docs/operations-product-boundaries.md), [design adoption seams](docs/mobile-design-adoption.md), [foundation architecture](docs/android-foundation.md) and [native session security](docs/native-session.md).

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

The CI workflow in [android-verify.yml](.github/workflows/android-verify.yml) runs applicable Android checks and publishes the stable `verify` status. Feature verification includes API 37 instrumentation. Promotion verification also includes API 29 instrumentation and release shrinking. The manually triggered [academic APK workflow](.github/workflows/android-academic-apk.yml) builds a debug artifact against the approved Render HTTPS origin and retains it for seven days.

## Current limits

Automatic IoT and advanced routing remain outside approved V1 scope. The app uses existing API contracts and does not contain live account credentials, a production API endpoint, or distribution signing configuration. No physical-device acceptance is claimed. Local sign-out clears protected state even when server revocation cannot be confirmed. Product Acceptance, System Acceptance and production readiness remain separate gates.

## DDD client refactor checkpoint — 2026-10-08

Client projections and ports now compile in five Kotlin/JVM contract modules.
Presentation uses its own contracts; remote and local adapters are composed
from `:data:operations`. Receiving and field requests use selective durable
command coordinators. The [DDD alignment](docs/client-ddd-alignment.md) explains
the accepted boundaries; the [execution record](docs/ddd-client-verification.md)
records current technical checks and their limits.

## Release checkpoint — 2026-10-05

The `release/v1.0.0` source candidate sets the Operations Android app to version `1.0.0` (`versionCode 8`). This repository covers the Operations Android client across access and context selection, warehouse work, dispatch and current Driver delivery workflows. Buyer Mobile is outside this repository and remains a separate TARGET.

The candidate passed strict architecture, ktlint, debug lint, all-module JVM tests and debug assembly: 467 JVM tests, with zero failures, errors or skips. It also passed the six release-endpoint rejection cases and verification-origin release assembly with R8 and resource shrinking. A signed direct-distribution APK targets the approved Render validation origin; its identity, signature, alignment and checksum are recorded in the [release notes](docs/releases/v1.0.0.md).

GitHub Actions run [37394456318](https://github.com/nexa-suite/mobile/actions/runs/37394456318) passed on this exact candidate commit (`2508b2f18a52ccbad55e50c96c5ce0aca2f474e1`): static verification, API 37 instrumentation, and the promotion job, including API 29 instrumentation and release shrinking. The instrumentation jobs exclude the live-candidate identity test and use no candidate credentials. Android Studio also installed the signed APK on an API 37 emulator; the Warehouse login selected one eligible context, the work screen loaded empty in Ready state, and manual refresh advanced its displayed timestamp. Filtered logcat showed no fatal crash. This smoke did not exercise UF-06/07 or a physical-device workflow. The signed APK was built outside the checked-in signing configuration and points to the approved Render validation origin; it is intended only for controlled direct distribution. No Play Store upload or deployment is recorded. Live read-only checks found one ACTIVE warehouse but no inventory lots, RESERVED inventory reservations, canonical fulfillments, dispatch-readiness items or dispatch orders in the shared Warehouse/Dispatch Tenant and Workspace. The reported visibility issue therefore remains unresolved and could not be reproduced with available live records; no runtime fix or Product acceptance is claimed. System Acceptance and production readiness remain separate gates.
