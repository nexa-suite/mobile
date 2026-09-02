<div align="center">

<br />

<img src="./docs/assets/nexa.svg" alt="Nexa" width="240" />

# Nexa Mobile

**AV1 native engineering preview for Nexa Operations field experiences.**

![Status](https://img.shields.io/badge/status-engineering--preview-64748B?style=flat-square) ![Scope](https://img.shields.io/badge/scope-AV1-64748B?style=flat-square) ![Native](https://img.shields.io/badge/native-Android-64748B?style=flat-square)

[Changelog](./CHANGELOG.md) · [Release notes](./docs/releases/) · [Contributing](./.github/CONTRIBUTING.md) · [Security](./.github/SECURITY.md)

</div>

---

## Overview

This repository contains a runnable, unmerged AV1 native Android engineering
preview for Nexa Operations. The current preview is experimental and does not
claim Mobile V1, Product Acceptance, production readiness or physical-device
validation.

The implementation is deliberately staged. The current branch includes the
Operations app shell, typed error mapping, Keystore-backed session storage,
Navigation 3 launch states, localized access forms, explicit session recovery,
and a Warehouse identification preview with CameraX, ML Kit barcode decoding,
manual input and a tagged API resolver adapter. Connected API authentication
remains blocked until the tagged API's Operations `ClientSurface` mapping is
approved. Live API, camera, emulator and physical-device evidence remains
unverified.

## Related repositories

The organization profile owns the full public ecosystem map. This repository links to adjacent Nexa surfaces without copying their release state.

- [Nexa API](https://github.com/nexa-suite/api) — business and integration backbone.
- [Nexa Platform](https://github.com/nexa-suite/platform) — internal operational workspace.
- [Nexa Buyer Portal](https://github.com/nexa-suite/portal) — buyer-facing experience.
- [Nexa Website](https://github.com/nexa-suite/website) — public product experience.
- [Canonical Blueprint](https://github.com/nexa-suite/blueprint) — Product, Domain and architecture authority.

## AV1 scope and boundaries

- In scope for the complete AV1 plan: `MOB-US-001`, `MOB-US-002`,
  `MOB-US-003`, `MOB-US-011` and `MOB-US-012`.
- The current branch contains partial implementation slices for that boundary;
  passing tests and builds do not mark those stories accepted.
- `MOB-US-013` and later stories are not started.
- Mobile is a client projection; it creates no Bounded Context and does not
  become an authority for tenant, authorization, catalog, inventory or
  fulfillment facts.
- API integration evidence is pinned to `v0.17.0` and remains read-only.

No offline business authority, generic sync engine, Room database, new backend
endpoint or new infrastructure is introduced by this preview.

## Architecture Boundary

The client consumes approved API contracts. Authorization, tenant/workspace
scope and business rules remain backend responsibilities. The preview maps
access to `BC-01 Tenant & Access Governance` and product identification to
`BC-03 Catalog & Commercial Policy`; it does not reproduce either context.

## Technology Stack

| Concern | Status |
| --- | --- |
| Application framework | Kotlin + Jetpack Compose |
| Navigation | Navigation 3, typed serializable route keys |
| Session material | Android Keystore-backed AES/GCM primitive |
| Warehouse input | CameraX 1.6.1 + bundled ML Kit barcode scanning 17.3.0; runtime not evidenced |
| API evidence | Nexa API `v0.17.0`, read-only |
| Build baseline | Project JDK 21 (selected and locally verified); AGP 9.0.1 / Gradle 9.1.0 / compile-target SDK 36 |
| Backend authority | Nexa API |
| Current repository role | Experimental AV1 engineering preview |

## Getting Started

Use the selected project JDK 21 and an Android SDK containing platform API 36
and build-tools 36.0.0. AGP 9.0.1 upstream minimum/default is JDK 17. CI and
Docker pin JDK 21 for reproducibility; the root build does not reject another
supported JVM solely because its major version differs:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The reproducible Docker environment runs the same gates:

```bash
docker build -t nexa-mobile-av1-build .
docker run --rm nexa-mobile-av1-build
```

No emulator or physical-device result is implied by a successful host build.

## Repository Structure

    operations/             # Android application
    core/                   # network, storage, design system, test support
    feature/access/         # access projection and launch shell
    feature/warehouse/      # camera/manual identification preview
    README.md
    CHANGELOG.md
    .github/

## Documentation

- [Release notes](./docs/releases/)
- [Release policy](./.github/RELEASE_POLICY.md)

## Historical provenance

Earlier UPC repositories remain evidence only. They do not define current Nexa identity, implementation authority or TARGET architecture.

- [nexa-platform](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-platform) — predecessor backend and REST API layer.
- [nexa-webapp](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-webapp) — historical unified Vue application.
- [nexa-website](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-website) — previous public Website lineage.
- [nexa-ecosystem-report](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-ecosystem-report) — historical requirements and architecture evidence.

## Security

Do not report vulnerabilities through public issues. Follow the repository [Security Policy](./.github/SECURITY.md).

## Legal

Copyright © 2026 Nexa. All rights reserved. No open-source license is selected by this README.

<div align="center"><br />Nexa · Current product, explicit evidence boundaries</div>
