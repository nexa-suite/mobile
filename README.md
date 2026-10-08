# Nexa Operations Android

This repository contains the Nexa Operations Android client. The Android
project is in [`apps/operations-android`](apps/operations-android). The client
projects accepted Nexa API capabilities into native workflows; the API remains
authoritative for authentication, Tenant and Workspace authorization, and
business outcomes. The client does not implement server aggregates or business
authority.

## Toolchain

- JDK 17, Gradle 9.7.1, Android Gradle Plugin 9.4.1, Kotlin 2.4.20, KSP 2.3.12
- Android SDK 37 for compilation and target behavior; minSdk 29
- Compose BOM 2026.09.00, Navigation 3 1.1.7, Hilt 2.60.1
- Retrofit 3.0.0, OkHttp 4.12.0, kotlinx serialization 1.11.0

Install JDK 17, Android SDK package `platforms;android-37.0`, and
`build-tools;36.0.0`. The checked-in Gradle wrapper downloads Gradle 9.7.1.
Use an API 37 emulator for feature verification and an API 29 emulator for
promotion verification. See the [verification guide](docs/verification.md) for
commands and evidence locations.

## Module layout

| Area | Responsibility |
| --- | --- |
| `:app` | Hilt composition, ViewModel factories, Navigation 3 root, Android entry points and manifests |
| `:core:*` | Shared auth, generic scoped local storage, network, device and design-system foundations |
| `:contexts:<root>:<layer>` | Code aligned to a canonical Bounded Context and one of its implemented client layers |

The Android source has 11 canonical context roots and 31 runtime layer modules.
The four layer names are `domain`, `application`, `infrastructure` and
`presentation`; a module exists only where this client has code. BC-08 Payments,
BC-10 Notifications and BC-11 Business Traceability currently have ownership
documentation but no runtime module. The [DDD alignment](docs/client-ddd-alignment.md)
lists every root and implemented layer. Context roots, Gradle modules and screens
do not create business Bounded Contexts or transfer server authority.

In these client modules, `domain` contains non-authoritative projections and
local value constraints; `application` contains ports and selective workflow
coordination; `infrastructure` adapts network, serialization, context-owned
encrypted metadata and platform capabilities; and `presentation` owns Compose
UI state and screens. `:core:local` supplies generic scoped-storage mechanics;
receiving, disposition, temperature and picking metadata are owned by BC-05 and
BC-06 infrastructure.
The conceptual layering follows Blueprint ADR-0020. `:app` is the composition
root, and `:core:*` modules provide technical capabilities rather than business
ownership.

The API owns Tenant and Workspace authorization, catalog and commercial
decisions, inventory, credit, payments, fulfillment and delivery outcomes.
Permission hints only shape navigation. A successful current server response is
required before a client projection can be treated as confirmed. See [product
boundaries](docs/operations-product-boundaries.md), [foundation
architecture](docs/android-foundation.md) and [native session
security](docs/native-session.md).

## Build and verify

From `apps/operations-android` with `JAVA_HOME` pointing to JDK 17:

```sh
python3 scripts/verify-context-architecture.py
./gradlew verifyAndroidArchitecture ktlintCheck lintDebug testDebugUnitTest \
  :app:assembleDebug --dependency-verification strict --console=plain
```

The Python check enforces the 11 canonical roots and context-layer source
boundaries. The Gradle gates cover Android module architecture, formatting,
lint, JVM tests and debug assembly. The aggregate `testDebugUnitTest` covers
configured Android modules and Kotlin/JVM domain/application modules. See the
[verification guide](docs/verification.md) for test-result locations and
connected-device commands.

Release assembly requires an explicitly supplied non-local HTTPS API origin:

```sh
./gradlew :app:assembleRelease -PnexaReleaseApiBaseUrl="$APPROVED_NEXA_API_ORIGIN" \
  --dependency-verification strict
```

The variable must contain an approved non-local HTTPS root origin; none is
configured in this repository. A verification build can use a separate HTTPS
origin to exercise R8 and resource shrinking; that artifact is not a production
distribution. Do not publish or install it as a production client.

The CI workflow in [android-verify.yml](.github/workflows/android-verify.yml)
runs applicable Android checks and publishes the stable `verify` status. Feature
verification includes API 37 instrumentation. Promotion verification also
includes API 29 instrumentation and release shrinking. The manually triggered
[academic APK workflow](.github/workflows/android-academic-apk.yml) builds a
debug artifact against the approved Render HTTPS origin and retains it for
seven days.

## Current refactor status — 2026-10-08

The current working tree passed the 11-root architecture check, six negative
boundary probes, ktlint, debug lint, debug assembly and 524 JVM tests. Complete API 37 and API 29 instrumentation each passed 64 tests without
failures, errors or skips; R8 release assembly also passed. Final signing,
integration and publication remain pending.
This is implementation and technical-verification status only; it is not
Product/UX Acceptance, System Acceptance, a published release or production
readiness. See the [DDD execution record](docs/ddd-client-verification.md).

## Current limits

Automatic IoT and advanced routing remain outside approved V1 scope. The app
uses existing API contracts and does not contain live account credentials, a
production API endpoint, or distribution signing configuration. No
physical-device acceptance is claimed. Local sign-out clears protected state
even when server revocation cannot be confirmed. Product Acceptance, System
Acceptance and production readiness remain separate gates.

## Historical release checkpoint — 2026-10-05

The following evidence belongs only to the earlier `release/v1.0.0` candidate;
it is not validation of the current DDD refactor. That source candidate set the
Operations Android app to version `1.0.0` (`versionCode 8`). The recorded local
gates covered its then-current feature/data module layout. GitHub Actions run
[37394456318](https://github.com/nexa-suite/mobile/actions/runs/37394456318)
passed on candidate commit
`2508b2f18a52ccbad55e50c96c5ce0aca2f474e1`. The signed APK and smoke-test
scope are recorded in the [v1.0.0 release notes](docs/releases/v1.0.0.md).
That record did not establish Product Acceptance, System Acceptance or
production readiness and must not be treated as evidence for v1.1.0.
