# Changelog

## [1.3.1] - local source release candidate, unpublished

Prepared on 2026-10-29 from `main` at `22748321730b1992092064537fd4c24497b6f935`. Repository source tag candidate is `v1.3.1`; app versions remain independent. Operations Android stays at `1.3.0` (`versionCode` 12); Buyer Mobile advances from `1.2.0+3` to patch version `1.2.1+4`.

- Fix Buyer catalog recovery after `403`: clear stale results and update time, reset pagination, and restore Spanish fallback text for unexpected failures.
- Cover later-page permission recovery and unexpected catalog failures in Buyer unit tests.
- Stabilize the Operations profile/logout instrumentation test by scrolling the profile sheet before selecting logout.
- Make the manually triggered Operations academic APK artifact name use built version metadata and attach a generated SHA-256 sidecar.

See [v1.3.1 release notes](RELEASE_NOTES_v1.3.1.md). This is a source-only candidate; it does not claim Product/System Acceptance, physical-device acceptance, signed distribution, or Production Readiness.

## Academic checkpoint changes

- Filter Buyer catalog by brand and category; show API-provided cold-chain
  requirement and sellable availability.
- Verify and record Buyer delivery receipt using the manual handoff flow.
  Family and variant filters and atomic QR handoff remain deferred on #60.
- Complete password reset through a server-issued reset token.

The checkpoint was validated against the API academic candidate using the
Buyer catalog and manual handoff flows. It does not include device acceptance
or signed distribution packages.

- Refine Buyer and Operations access layouts across phones, tablets, keyboard
  and enlarged text while retaining secure session and recovery adapters.
- Group Buyer navigation into Catalog, Orders and Account; connect personal
  favorites and current credit/order summaries to existing server contracts.
- Show real catalog images and server-side availability/cold-chain filters.
- Read each product's saved Favorite state from the API, refresh visible
  favorites and recover pagination after the last item on a page is removed.
- Encode Driver-issued delivery tokens as QR and submit Buyer scans to the
  receipt endpoint, displaying the authoritative POD and order result.
- Align Buyer delivery dates with the accepted five-business-day policy in
  America/Lima, without holiday exclusions, and use the current Buyer account
  response while preserving scoped reads and retries.
- Add editable personal profiles to Buyer and Operations; add Buyer personal
  addresses with controlled geography and an optional map pin.
- Show actual order history, order-specific documents and native PDF/XML/CSV
  export. Add versioned delivery instructions using the existing API.
- Present dispatch preparation as actionable cards with server-backed stock,
  picking and evidence status, accessible labels and English/Spanish text.
- Keep scanning and dispatch handoff controls accessible on short screens and
  with larger text; consume shared system insets once. Prevent blank Sales
  customer-progress queries and reject incomplete delivery addresses.
- Distinguish API-confirmed receipt quantity from current lot stock so a safe
  retry can confirm an earlier stock receipt after subsequent movements. Keep
  conservative behavior when the API cannot provide verified receipt metadata.
- Separate Favorite read/write grants and reject product requests when the
  server supplies no sales unit. End-to-end and Product acceptance remain
  pending; this section describes unreleased source changes.

## [1.3.0] - 2026-10-22

Source-only release for controlled Nexa Suite validation. Operations Android
source is version `1.3.0` (`versionCode` 12); Buyer Mobile source is version
`1.2.0+3`. No signed distribution package is included.

- Add Buyer biometric sign-in and password recovery, searchable brand/category
  catalog filters, resumable purchase drafts, request/order history and active
  delivery tracking.
- Scope Buyer destinations to current server grants and refresh visible work
  from the server change feed. Preserve server authority for credit, order
  decisions, receipt evidence and payment status.
- Organize Operations navigation by role permissions and operational tasks;
  improve cold-chain receiving and stock capture with product catalog
  selection, lot dates and authoritative units.
- Refresh dispatch state from server changes and share driver location only
  during an eligible active delivery with required consent and permission.
- Keep the mobile flows within their bounded-context and platform boundaries.

### Verification

- Buyer context-boundary scripts, formatting, analysis, full Flutter test suite
  and debug APK build passed. Five opt-in local API tests were skipped because
  the integration environment was not enabled.
- Operations architecture checks, ktlint, lint, JVM tests, debug APK assembly
  and Android instrumented-test compilation passed. No local emulator/device
  run was performed.
- Full cross-client Product/System Acceptance, physical-device acceptance and
  Production Readiness remain unverified. This source release makes none of
  those claims.

## [1.2.0] - 2026-10-12

Source-only release for controlled local Nexa Suite testing. Application source merged through [PR #46](https://github.com/nexa-suite/mobile/pull/46), repository cleanup through [PR #43](https://github.com/nexa-suite/mobile/pull/43), and release evidence updates through [PR #47](https://github.com/nexa-suite/mobile/pull/47). Operations Android is `1.2.0` (`versionCode 11`); Buyer Mobile is independently `1.1.0+2`. The release does not include a signed Android APK, a Buyer distribution artifact, or an iOS package. The full cross-client order-to-delivery flow, Product Acceptance, System Acceptance, and Production Readiness remain unverified. See the [GitHub Release](https://github.com/nexa-suite/mobile/releases/tag/v1.2.0) for the published source boundary and limits.

- Add scoped Operations and Buyer notification inbox, read state and
  preferences for accepted `IN_APP`/`EMAIL` channels; push delivery remains
  outside the implemented scope.
- Add Buyer wallet PaymentSheet checkout with scoped retry identity; payment
  status remains server-confirmed and wallet credit is not inferred by the app.
- Publish verified workforce scope and commercial permission hints through BC-01
  application APIs; translate Catalog scope at its adapter boundary and reject
  dependencies between context domains.
- Allow a new Direct Order decision after recovered permission or unavailable
  terminal outcomes while retaining same-key replay for uncertain outcomes.
- Update published v1.1.0 verification and distribution records.

## [1.2.0-alpha.1] - 2026-10-10

Source checkpoint prerelease; incomplete integration and distribution gates. See the [GitHub prerelease](https://github.com/nexa-suite/mobile/releases/tag/v1.2.0-alpha.1).

## [1.1.0] - 2026-10-08

Published Operations Android release, versionCode 10. The [GitHub
Release](https://github.com/nexa-suite/mobile/releases/tag/v1.1.0) uses tag
`v1.1.0` at source commit
`6cdb4318fa2e41a0268cce8900c138caffc93aec`. The attached APK SHA-256 is
`a5e27394de8fa422599ca463dd1d6ee3f6f80d8a06c157303234dd1b4e91f769`; the
[GitHub Release](https://github.com/nexa-suite/mobile/releases/tag/v1.1.0)
contains the published artifact.

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
controlled direct distribution is available from the
[GitHub Release](https://github.com/nexa-suite/mobile/releases/tag/v1.0.0).

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

Operations functional remediation and local Android delivery checkpoint.

- Integrate authorized Warehouse, Dispatch, Delivery, Sales and exception coordination workflows.
- Fix scoped inventory pagination, native navigation, Android 17 local-network recovery and receiving selection layout.
- Preserve strict dependency verification, protected session storage, server authorization and explicit command replay.
- Add opt-in native integration and role capture evidence without embedding credentials.

## [0.3.0-alpha.1] - 2026-10-01

Earlier source checkpoint, published as prerelease with known validation failures.
See the [GitHub prerelease](https://github.com/nexa-suite/mobile/releases/tag/v0.3.0-alpha.1).
No stable readiness claim.

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
