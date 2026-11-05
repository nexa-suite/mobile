# Nexa Mobile v2.0.1 — published source release

## Release record

The signed tag [`v2.0.1`](https://github.com/nexa-suite/mobile/releases/tag/v2.0.1),
tagged 2026-11-04, points to `main` commit
`ea9b4369ba3a5cc9c86c3d6c8b7c280a1f631056`. This release follows v2.0.0 and
contains the Operations biometric reentry correction below; Buyer source and
APK bytes remain unchanged.

The candidate wording retained in the immutable tagged source records the
pre-publication state. This documentation update does not modify the tag,
GitHub Release or its APK assets.

## Scope

Correct Operations biometric reentry that remained locked after a valid fingerprint because API session verification temporarily cleared the authorized UI context. Retain the local cryptographic proof through that verification only; unlock requires a successful server result and restoration of the same user, Tenant, Workspace and membership. Backgrounding, context changes, terminal sessions and new attempts invalidate pending proofs. Password fallback remains available. No server authentication or biometric strength is weakened.

## Versions and artifacts

- Repository: v2.0.1 source release published with the signed tag and GitHub Release.
- Operations Android: 1.3.1, versionCode 13.
- Buyer Flutter: unchanged 1.2.1+4; reuse the immutable v2.0.0 APK bytes.
- Academic artifacts are debug APKs targeting the approved Render HTTPS origin. They are not production distribution builds.

## Verification and limits

The focused change scope covered biometric regression, architecture and
formatting checks. This note does not record complete cloud business workflows,
physical-device acceptance, Product Acceptance, System Acceptance or Production
Readiness; publication does not imply those gates. QR product identification
requires the compatible API deployment. Order, payment, inventory mutation and
delivery journeys retain their existing cloud limits.
