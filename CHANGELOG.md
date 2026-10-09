# Changelog

## [Unreleased]

- Publish verified workforce scope and commercial permission hints through BC-01
  application APIs; translate Catalog scope at its adapter boundary and reject
  dependencies between context domains.
- Allow a new Direct Order decision after recovered permission or unavailable
  terminal outcomes while retaining same-key replay for uncertain outcomes.
- Update published v1.1.0 verification and distribution records.

## [1.1.0] - 2026-10-08

Published Operations Android release, versionCode 10. The [GitHub
Release](https://github.com/nexa-suite/mobile/releases/tag/v1.1.0) uses tag
`v1.1.0` at source commit
`6cdb4318fa2e41a0268cce8900c138caffc93aec`. The attached APK SHA-256 is
`a5e27394de8fa422599ca463dd1d6ee3f6f80d8a06c157303234dd1b4e91f769`; see the
[release notes](docs/releases/v1.1.0.md) and [execution
record](docs/ddd-client-verification.md) for verification and limits. PR #41
contains follow-up changes outside the published tag; its local technical
validation passed, while updated-head CI, review and integration remain pending.

- Organize client code by the eleven canonical bounded contexts, with explicit
  JVM domain/application and Android infrastructure/presentation modules.
- Move workflow metadata and payload encoding into their owning contexts and
  remove legacy feature/data source roots.
- Align field ordering to the accepted Direct Order scope, preserving confirmed
  versus pending-prepaid server outcomes and exact explicit replay.
- Separate Inventory and Fulfillment through public read contracts; keep joins
  outside domain and server decisions authoritative.
- Move protected PDF platform rendering into infrastructure and bound preview
  memory and transient-file cleanup.
- Map the original Sprint 1/2 Mobile backlog to source and verification evidence
  without converting implementation into Product acceptance.

## [1.0.0] - 2026-10-05

Operations Android v1.0.0 source candidate, versionCode 8. A signed APK for
controlled direct distribution is recorded in the
[release notes](docs/releases/v1.0.0.md), along with its validation boundaries.

- Set the Android app version to 1.0.0 while retaining the Operations client scope in this repository.
- Keep Buyer Mobile outside this repository as a separate TARGET.
- Pass strict architecture, ktlint, debug lint, dependency verification, debug assembly and 467 JVM tests with zero failures, errors or skips.
- Reject all six documented unsafe release origins and pass verification-origin release assembly with R8 and resource shrinking.
- Build and externally sign an R8 release APK against the approved Render validation origin for controlled direct distribution; its checksum and signing verification are recorded in the release notes.
- Use API 37 CI evidence from the base source commit; connected instrumentation was not rerun for this version/documentation change, and API 29 instrumentation was not run for this candidate.
- Keep the reported Warehouse/Picking visibility issue open pending a reproducible runtime case with account scope, order and server response.
- Preserve the separation between technical verification, Product Acceptance, System Acceptance, physical-device validation and production readiness. No Play Store upload or deployment is recorded.

## [0.6.0] - 2026-10-04 (published academic checkpoint)

Published academic checkpoint. Operations Android versionCode is 7. The GitHub
Release includes the debug APK built against the approved HTTPS API origin.

- Build the debug APK against the approved Render HTTPS origin for academic validation; no credentials or distribution signing material are included.
- Re-run strict architecture, ktlint, debug lint and JVM contract checks for this checkpoint.
- Render the canonical catalog product images through the shared design system with bounded image loading and focused UI/JVM coverage.
- Add a manually triggered GitHub Actions workflow that uploads the debug APK as a short-lived academic artifact.
- Keep Product Acceptance, System Acceptance, physical-device acceptance and production readiness as separate gates.

## [0.5.0] - 2026-10-03

Operations Android checkpoint, versionCode 6.

- The current API verification record reports 675 tests with zero failures; the Android Studio validation record for the recorded implementation snapshot reports 456 JVM tests with zero failures, errors or skips.
- Strict architecture verification and debug assembly passed locally. This checkpoint has no production distribution or Product, System or physical-device acceptance claim.

## [0.4.0] - 2026-10-02

Operations functional remediation and local Android delivery. See
[release notes](docs/releases/v0.4.0.md) for verified scope and distribution limits.

- Integrate authorized Warehouse, Dispatch, Delivery, Sales and exception coordination workflows.
- Fix scoped inventory pagination, native navigation, Android 17 local-network recovery and receiving selection layout.
- Preserve strict dependency verification, protected session storage, server authorization and explicit command replay.
- Add opt-in native integration and role capture evidence without embedding credentials.

## [0.3.0-alpha.1] - 2026-10-01

Earlier source checkpoint, published as prerelease with known validation failures.
See [release notes](docs/releases/v0.3.0-alpha.1.md). No stable readiness claim.

## 0.1.1 - 2026-09-28

### Added

- Consolidated the earlier implementation and design-readiness presentation baseline for Operations Android.
- Authoritative warehouse identification, root navigation context routing, and debug review harness.
- Protected `NexaCatalogGateway` with product search, detail gateway, and epoch-scoped warehouse invalidation.
- Design-ready theme tokens and visual foundations aligned with Nexa Design Lab (Navy `#082846` palette, Knox biometric, high-contrast states).
- Source reconciliation unified the canonical implementation branches used by the earlier baseline.

## 0.1.0 - 2026-09-23

### Added

- Operations Android technical foundation in four modules: `:app`, `:core:auth`, `:core:network`, and `:core:designsystem`.
- Compose application shell with a session-gated Navigation 3 root.
- Native session coordination with memory-only access tokens, Android Keystore protected refresh credentials, bounded refresh, and local sign-out protection.
- Native API transport with exact origin checks, Problem Details mapping, explicit command idempotency keys, opaque ETag handling, and bounded replay after eligible HTTP 401 responses.
- Strict Gradle dependency verification and GitHub Actions gates for static checks, JVM tests, API 37 and API 29 emulator tests, endpoint validation, and release assembly with R8 minification and resource shrinking.

### Limitations

- Product workflows, Buyer Mobile, camera or scanner flows, and offline operation are outside this technical foundation.
- No production API endpoint or production Android distribution signing identity is configured. No APK or AAB is published for this source release.
- Product and UX acceptance, system acceptance, physical device acceptance, and production readiness are not claimed.
