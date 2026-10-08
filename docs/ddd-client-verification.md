# DDD client refactor verification — 2026-10-08

## Current state

The current refactor organizes Operations Android under 11 canonical context
roots and 31 runtime modules, with `domain`, `application`, `infrastructure`
and `presentation` modules only where code exists. BC-08 Payments, BC-10
Notifications and BC-11 Business Traceability have no runtime modules. The
[DDD alignment](client-ddd-alignment.md) records the per-context layer map and
authority boundaries.

The refactor is paused at the Owner's request on 2026-10-08, on branch
`feature/mobile-context-ddd-v1.1.0`. This checkpoint preserves work in progress;
it is not the v1.1.0 release or a fully verified candidate.

| Command, from `apps/operations-android` | Observed result |
| --- | --- |
| `python3 scripts/verify-context-architecture.py` | PASS: 11 canonical context roots and layer boundaries |
| `./gradlew :core:device:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nexa.mobile.operations.core.device.scanner.BarcodeDecodingPoCTest --dependency-verification strict --console=plain` | PASS on API 37: 2 tests, 0 failures, 0 skipped; static synthetic QR/EAN-13 decoding only |
| `./gradlew :app:compileDebugKotlin testDebugUnitTest --dependency-verification strict --console=plain` | FAILED: strict verification rejected missing metadata for `androidx.collection:collection:1.4.2`, `collection-1.4.2.module`; aggregate execution did not complete |
| `./gradlew ktlintFormat :app:compileDebugKotlin testDebugUnitTest --dependency-verification strict --console=plain` | FAILED: ktlint requires `ScannerModels.kt` in BC-03 application to be named `ProductScannerResolution.kt`; formatting and subsequent checks did not complete |

The Dart experiment's `dart analyze` and fixture runner passed (12 synthetic
SKU resolver cases); the Kotlin counterpart is added but aggregate verification
has not completed. These checks do not establish physical camera operation or
Product Acceptance. Earlier failed compilations and behavior checks remain
negative evidence; subsequent source corrections require a full rerun.

The script verifies the 11 roots, module/layer shape, JVM framework boundaries,
context import direction and removal of source from legacy `feature` and `data`
roots. It is a structural check, not a build or behavior test.

The following gates remain **PENDING** for the current tree:

| Gate | Status |
| --- | --- |
| Gradle `verifyAndroidArchitecture` | PENDING |
| `ktlintCheck` | PENDING |
| `lintDebug` | PENDING |
| Aggregate `testDebugUnitTest` | PENDING |
| `:app:assembleDebug` with strict dependency verification | PENDING |
| API 37 connected instrumentation | PENDING |
| API 29 promotion instrumentation | PENDING |
| BC-05/BC-06 moved storage unit and Android instrumentation tests | PENDING |
| Release-origin rejection and R8 verification | PENDING |
| CI result for an immutable candidate | PENDING |

The storage tests now belong to the context infrastructure modules. BC-05 JVM
tests are `ReceivingMetadataStoreCoreTest`,
`DispositionMetadataStoreCoreTest` and
`TemperatureEvidenceMetadataStoreCoreTest`; its Android tests are
`AndroidReceivingMetadataStoreTest`, `AndroidDispositionMetadataStoreTest` and
`AndroidTemperatureEvidenceMetadataStoreTest`. BC-06 JVM and Android tests are
`PickingMetadataStoreCoreTest` and `AndroidPickingMetadataStoreTest`. They
reside under `contexts/{inventoryavailability,fulfillmentdelivery}/infrastructure/src/{test,androidTest}`.
No current pass is recorded for these moved tests.

The reproducible commands and XML report locations are in the
[verification guide](verification.md). The owner decision dated 2026-10-08 for
MOB-US-009 (direct order) is recorded in the [alignment](client-ddd-alignment.md);
no check in this record verifies that API or client implementation.

## Authority references

- Blueprint accepted architecture and decisions: commit
  `3574accc8962346a824a043ef8ea5564600c08b0` (includes the accepted
  Direct Order scope correction in [PR #33](https://github.com/nexa-suite/blueprint/pull/33)).
- API implementation map: `origin/main` commit
  `78a3060cb56796520fcf8e9be36c63f88b4f9f51`.
- Mobile Report academic DDD projection: commit
  `55f959441fb4d5422519db31c380631e95ed72e3`.

The Blueprint is canonical Product/Domain/C4 authority. API documentation maps
current implementation contracts. Mobile Report is academic evidence only.
Implementation status does not become Product Acceptance, System Acceptance
or production readiness.

## Resume checklist

1. Verify the missing collection metadata against official Google Maven bytes
   and publisher checksum before adding its digest; keep strict verification.
2. Correct the BC-03 application filename and run formatting across all modules.
3. Complete Direct Order verification: current API response enclosure,
   confirmed versus prepaid pending UI/receipt, explicit same-key recovery,
   legacy Purchase Request intent reconciliation, coordinator and ViewModel tests.
4. Review the extracted Inbound Discrepancy encoder and add golden/tamper tests;
   validate restored bodies against their staged fields.
5. Run full architecture, lint, JVM tests and debug assembly. Recheck moved
   BC-05/BC-06 encrypted storage, API 37 instrumentation and API 29 promotion.
6. Complete Sprint 1/2 PBI-to-acceptance-criterion traceability against Mobile
   Report commit `55f959441fb4d5422519db31c380631e95ed72e3`; report gaps and
   distinguish original scope from the expanded Jira projection.
7. Run release-origin rejection and R8 checks, create the remaining coherent
   signed commits, require GitHub CI, integrate the corrective branch and
   publish v1.1.0 with the same official Android certificate. Close the task's
   branches only after integration. Signing secrets stay outside this repository.

The prior invalid Mobile PR #38 is closed. This corrective Mobile branch remains
open for completion. Android version values target 1.1.0 (code 10), but no tag,
release, final APK, completed Sprint acceptance or current production gate is
claimed. Blueprint PR #33 is merged, its Diego-authored commit is GitHub
Verified, and its branch has been removed.

## Historical baseline — not current validation

The following results belong to the earlier feature/data contract extraction
and do not validate this 11-context refactor. The prior record used mobile
baseline `f3b425867184ca0463e3dbb105ba48e0642ab824`; tested Android source bytes
matched `0856efd11956f9b7b4c044e185a9ab1f8016b88e` and had fingerprint
`1b37163a547549242eadf3de7ee218c2e4aabc8c3db1d66e6242352c8107b413` over 705
files. Its 475 JVM tests and module names below describe that earlier layout
only.

| Earlier module | Tests | Failures / errors / skips |
| --- | ---: | --- |
| app | 34 | 0 / 0 / 0 |
| core/auth | 21 | 0 / 0 / 0 |
| core/network | 164 | 0 / 0 / 0 |
| core/local | 32 | 0 / 0 / 0 |
| core/device | 2 | 0 / 0 / 0 |
| core/designsystem | 6 | 0 / 0 / 0 |
| data/operations | 24 | 0 / 0 / 0 |
| feature/access | 10 | 0 / 0 / 0 |
| feature/warehouse | 76 | 0 / 0 / 0 |
| feature/dispatch | 28 | 0 / 0 / 0 |
| feature/delivery | 52 | 0 / 0 / 0 |
| feature/commercial | 18 | 0 / 0 / 0 |
| warehouse/contract | 4 | 0 / 0 / 0 |
| commercial/contract | 4 | 0 / 0 / 0 |
| **Earlier total** | **475** | **0 / 0 / 0** |

That earlier execution also reported 57 completed connected cases across auth,
local and app XML, plus one credential-dependent role capture that raised an
`AssumptionViolatedException` and did not execute; the opt-in live identity
class was excluded. Four temporary negative dependency probes passed on that
earlier source. These facts remain historical and must not be used as current
emulator, integration, DDD-boundary or acceptance evidence.

## Acceptance and release limits

No Product/UX Acceptance, System Acceptance, production readiness, physical
device acceptance or release result is claimed for the current refactor. The
Owner has authorized the v1.1.0 refactor release scope; that authorization is
not evidence that an artifact was built, published, accepted or deployed. This
record does not mark the release complete.
