# Changelog

## [0.6.0] - 2026-10-03

Operations Android academic candidate, versionCode 7.

- Build the debug APK against the approved Render HTTPS origin for academic validation; no credentials or distribution signing material are included.
- Re-run strict architecture, ktlint, debug lint and JVM contract checks for the candidate.
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
