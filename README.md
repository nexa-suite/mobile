# Nexa Mobile

This repository contains two independently versioned Nexa clients. Operations
Android lives in [`apps/operations-android`](apps/operations-android); Buyer
Mobile lives in [`apps/buyer-mobile`](apps/buyer-mobile). Each client projects
API capabilities into its own workflows. The API remains authoritative for
authentication, Tenant and Workspace authorization, and business outcomes.
Neither client implements server aggregates or business authority.

## Applications

| Client | Source | Platforms | Guide |
| --- | --- | --- | --- |
| Operations Android | [`apps/operations-android`](apps/operations-android) | Android, Kotlin and Compose | [Toolchain](#operations-android-toolchain) and [verification](#build-and-verify-operations-android) |
| Buyer Mobile | [`apps/buyer-mobile`](apps/buyer-mobile) | Android and iOS, Flutter | [Buyer flows, setup and verification](apps/buyer-mobile/README.md) |

Operations source includes client screens and adapters for workforce access,
commercial and warehouse workflows, dispatch and delivery. Buyer source includes
catalog, purchase requests, credit and payments, documents, notifications,
delivery tracking, handoff and receipt. Availability in a connected app depends
on the deployed API capabilities, permissions and configured providers; source
presence does not establish a complete cloud journey.
The clients have separate source trees, release versions and local verification
commands; a release may update one without changing the other.

## Operations Android toolchain

- JDK 17, Gradle 9.7.1, Android Gradle Plugin 9.4.1, Kotlin 2.4.20, KSP 2.3.12
- Android SDK 37 for compilation and target behavior; minSdk 29
- Compose BOM 2026.09.00, Navigation 3 1.1.7, Hilt 2.60.1
- Retrofit 3.0.0, OkHttp 4.12.0, kotlinx serialization 1.11.0

Install JDK 17, Android SDK package `platforms;android-37.0`, and
`build-tools;36.0.0`. The checked-in Gradle wrapper downloads Gradle 9.7.1.
Use an API 37 emulator for feature verification and an API 29 emulator for
promotion verification.

## Operations Android module layout

| Area | Responsibility |
| --- | --- |
| `:app` | Hilt composition, ViewModel factories, Navigation 3 root, Android entry points and manifests |
| `:core:*` | Shared auth, generic scoped local storage, network, device and design-system foundations |
| `:contexts:<root>:<layer>` | Code aligned to a canonical Bounded Context and one of its implemented client layers |

Operations Gradle settings declare **44 projects**: **38 context-layer
modules** across **10 runtime roots** out of **11 canonical context roots**,
plus **6 app/core projects**. `businesstraceability` is the sole root without
runtime modules.

| Canonical root | Runtime Gradle layers |
| --- | --- |
| `tenantaccessgovernance` | `domain`, `application`, `infrastructure`, `presentation` |
| `customerbuyerrelationships` | `domain`, `application`, `infrastructure`, `presentation` |
| `catalogcommercialpolicy` | `domain`, `application`, `infrastructure`, `presentation` |
| `salescommitment` | `domain`, `application`, `infrastructure`, `presentation` |
| `inventoryavailability` | `domain`, `application`, `infrastructure`, `presentation` |
| `fulfillmentdelivery` | `domain`, `application`, `infrastructure`, `presentation` |
| `creditreceivables` | `domain`, `application`, `infrastructure` |
| `payments` | `domain`, `application`, `infrastructure`, `presentation` |
| `businessdocuments` | `domain`, `application`, `infrastructure`, `presentation` |
| `notifications` | `application`, `infrastructure`, `presentation` |
| `businesstraceability` | documentation only; no runtime module |

[`settings.gradle.kts`](apps/operations-android/settings.gradle.kts) is the source
for runtime module membership. This inventory reflects the published v2.0.1
source; the architecture validator lists the canonical roots separately.

The four context-layer names are `domain`, `application`, `infrastructure`
and `presentation`; a module exists only where this client has code. Context
roots, Gradle modules and screens describe client structure; they do not create
business Bounded Contexts or transfer server authority.

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
required before a client projection can be treated as confirmed.

## Active-delivery maps

Debug builds of Operations and Buyer use OpenStreetMap raster tiles by default
for local workflow development; both show OSM attribution. Operations uses the
WebView's HTTP tile cache, and Buyer uses flutter_map's built-in persistent tile
cache. Release builds do not inherit the development tile source: configure an
approved provider and its attribution explicitly before enabling production
maps. Use synthetic delivery coordinates during local map work because external
tile requests reveal the approximate viewed map area.

## Build and verify Operations Android

From `apps/operations-android` with `JAVA_HOME` pointing to JDK 17:

```sh
python3 scripts/verify-context-architecture.py
./gradlew verifyAndroidArchitecture ktlintCheck lintDebug testDebugUnitTest \
  :app:assembleDebug --dependency-verification strict --console=plain
```

The Python check enforces the 11 canonical roots and context-layer source
boundaries. The Gradle gates cover Android module architecture, formatting,
lint, JVM tests and debug assembly. The aggregate `testDebugUnitTest` covers
configured Android modules and Kotlin/JVM domain/application modules.

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

## Buyer Flutter verification

From `apps/buyer-mobile`, run the repository-provided source checks:

```sh
python3 scripts/test-context-architecture.py
python3 scripts/verify-context-architecture.py
flutter analyze
flutter test
```

The Buyer guide documents API-origin setup, opt-in local API fixtures, assets
and platform-specific limits. Source checks do not establish Product
Acceptance, System Acceptance or Production Readiness.

## Latest published source release — v2.0.1

Signed tag [`v2.0.1`](https://github.com/nexa-suite/mobile/releases/tag/v2.0.1),
tagged 2026-11-04, points to `main` commit
`ea9b4369ba3a5cc9c86c3d6c8b7c280a1f631056`. This Operations biometric
reentry patch sets Operations Android to `1.3.1` (`versionCode 13`); Buyer
Mobile remains `1.2.1+4` and reuses its v2.0.0 APK bytes. See
[v2.0.1 release notes](RELEASE_NOTES_v2.0.1.md) for scope and artifact limits.

Publication records source/tag state. It does not establish complete cloud
workflow coverage, Product Acceptance, System Acceptance, physical-device
acceptance or Production Readiness.

For the academic delivery, use the APKs attached to that release. The compatible
[API v2.0.1](https://github.com/nexa-suite/api/releases/tag/v2.0.1) documents the
bounded cloud composition: catalog/SKU reads, Buyer own-account reads, authorized
Sales customer reads and independently enabled personal notifications. Complete
Purchase Request, order, inventory mutation, payment and delivery journeys are
outside that cloud checkpoint. Installation and login do not imply those
workflows are available.

## Historical v1.1.0 release status — 2026-10-08

Operations Mobile v1.1.0 was published as the [GitHub
Release](https://github.com/nexa-suite/mobile/releases/tag/v1.1.0) from tag
`v1.1.0`, pointing to `main` commit
`6cdb4318fa2e41a0268cce8900c138caffc93aec`. Candidate CI run
[37817216074](https://github.com/nexa-suite/mobile/actions/runs/37817216074)
passed. Local verification recorded 524 JVM tests and 64 instrumentation
tests on each API 29 and API 37 AVD, with no failures, errors or skips. The
published APK SHA-256 is
`a5e27394de8fa422599ca463dd1d6ee3f6f80d8a06c157303234dd1b4e91f769`; the
[GitHub Release](https://github.com/nexa-suite/mobile/releases/tag/v1.1.0)
contains the published artifact.

Technical verification does not establish Product/UX Acceptance, System
Acceptance or production readiness.

## Operations Android limits

Automatic IoT and advanced routing remain outside approved V1 scope. The app
uses existing API contracts and does not contain live account credentials, a
production API endpoint, or distribution signing configuration. No
physical-device acceptance is claimed. Local sign-out clears protected state
even when server revocation cannot be confirmed. Product Acceptance, System
Acceptance and production readiness remain separate gates.

## v1.3.0 source checkpoint

Source-only release for controlled Nexa Suite validation. Operations Android
is version `1.3.0` (`versionCode 12`); Buyer Mobile is independently versioned
`1.2.0+3`. No signed Android APK, Buyer distribution artifact or iOS package
is included. Local technical verification passed for the recorded Android
and Flutter source checks; CI and release promotion checks must pass before
publication. Full cross-client Product/System Acceptance, physical-device
acceptance and Production Readiness remain unverified. Publication requires
the signed tag and GitHub Release under the release policy.

## v1.2.0 source release

Local technical verification passed for the v1.2.0 application source: strict
architecture, formatting, lint, JVM tests, debug assembly, and API 37/API 29
instrumentation. This source release sets Operations Android to `1.2.0`
(`versionCode 11`) and the separate Buyer Mobile app to `1.1.0+2`. It does not
include a signed Android APK, a Buyer distribution artifact, or an iOS package.
The unsigned Operations R8 build uses the Render validation origin; it does not
establish live API compatibility or production distribution. The full
cross-client order-to-delivery flow, Product Acceptance, System Acceptance and
Production Readiness remain unverified. See the [GitHub Release](https://github.com/nexa-suite/mobile/releases/tag/v1.2.0)
for the published source boundary and limits.

## Historical release checkpoint — 2026-10-05

The following evidence belongs only to the earlier `release/v1.0.0` candidate;
it is not validation of the current DDD refactor. That source candidate set the
Operations Android app to version `1.0.0` (`versionCode 8`). The recorded local
gates covered its then-current feature/data module layout. GitHub Actions run
[37394456318](https://github.com/nexa-suite/mobile/actions/runs/37394456318)
passed on candidate commit
`2508b2f18a52ccbad55e50c96c5ce0aca2f474e1`. The signed APK and smoke-test
scope are recorded in the [v1.0.0 GitHub Release](https://github.com/nexa-suite/mobile/releases/tag/v1.0.0).
That record did not establish Product Acceptance, System Acceptance or
production readiness and must not be treated as evidence for v1.1.0.
