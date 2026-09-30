# Wave 4 scanner verification

MOB-US-011 adds CameraX preview with bundled ML Kit barcode recognition, protected server SKU-code/GTIN resolution, denied-permission recovery and manual identification fallback. Scanned candidates remain transient and cannot confirm stock or a receiving command. Modern SKU UUIDs remain distinct from legacy Catalog item identifiers.

The camera permission continuation waits for explicit completion of foreground session validation and a matching fresh verified context. Each validation has an ephemeral continuation identifier; completion from an older validation cannot reopen a cleared or newer request. Logout and context change clear the continuation immediately.

## Verified source

Source: `5777efc` on `feature/w4-operations-mobile`. Device dependency provenance is recorded in [android-device-dependencies.md](android-device-dependencies.md).

On 2026-09-30, JDK 17 with strict Gradle dependency verification passed:

- All-module `testDebugUnitTest`: 117 tests, zero failures, errors or skips.
- `verifyAndroidArchitecture`, `ktlintCheck`, `lintDebug`, and `assembleDebug`.
- All-module `connectedDebugAndroidTest` on API 37, excluding the opt-in live test: 36 tests, zero failures, errors or skips. Two scanner UI tests cover permanent denial/manual fallback and resolving/ambiguous results without confirmed identity.
- The opt-in `LiveCandidateIdentityIntegrationTest`: one test, zero failures, errors or skips. Native authentication, manual Catalog selection/detail, and foreground revalidation use the isolated real API fixture built from `68fb494`; API implementation matches released `v0.19.0` (`89ff511`) and Flyway V107.

The live test verifies manual identification and foreground revalidation after scanner integration. It does not exercise physical barcode recognition. JVM resolver tests use MockWebServer; they are transport-contract evidence, not live camera evidence.

## Remaining evidence

MOB-US-011 requires physical-device camera validation. API 29, process-death recovery, later Wave 4 capabilities and complete integration remain pending. Functional verification does not establish Product Acceptance, System Acceptance, design freeze or Production Readiness.

CI runs all-module JVM and instrumentation tasks, including newly registered modules. The live fixture remains opt-in; credentials and signing material are excluded from repository artifacts.
