# Nexa Mobile v2.0.1 — candidate

## Scope

Correct Operations biometric reentry that remained locked after a valid fingerprint because API session verification temporarily cleared the authorized UI context. Retain the local cryptographic proof through that verification only; unlock requires a successful server result and restoration of the same user, Tenant, Workspace and membership. Backgrounding, context changes, terminal sessions and new attempts invalidate pending proofs. Password fallback remains available. No server authentication or biometric strength is weakened.

## Versions and artifacts

- Repository: v2.0.1 candidate until a signed verified tag and GitHub Release exist.
- Operations Android: 1.3.1, versionCode 13.
- Buyer Flutter: unchanged 1.2.1+4; reuse the immutable v2.0.0 APK bytes.
- Academic artifacts are debug APKs targeting the approved Render HTTPS origin. They are not production distribution builds.

## Verification and limits

Focused regression, architecture and formatting checks apply to the biometric transition. Applicable CI, builds and physical-device reentry must be recorded before stable publication. Source publication does not prove complete cloud business workflows, Product Acceptance, System Acceptance or Production Readiness. QR product identification requires the compatible API deployment; order, payment, inventory-mutation and delivery journeys retain their existing cloud limits.
