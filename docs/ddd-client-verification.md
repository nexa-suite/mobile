# DDD client refactor verification — 2026-10-08

## Current state

The current refactor organizes Operations Android under 11 canonical context
roots and 31 runtime modules, with `domain`, `application`, `infrastructure`
and `presentation` modules only where code exists. BC-08 Payments, BC-10
Notifications and BC-11 Business Traceability have no runtime modules. The
[DDD alignment](client-ddd-alignment.md) records the per-context layer map and
authority boundaries.

The refactor resumed on 2026-10-08 from signed checkpoint
`af2558bb5b2f57c3b30e74ed80c5576332802e1b`, on branch
`feature/mobile-context-ddd-v1.1.0`. The current verification below applies to
the resumed working tree, not an immutable release or Product acceptance.

| Command, from `apps/operations-android` | Observed result |
| --- | --- |
| `python3 scripts/verify-context-architecture.py` | PASS: 11 canonical context roots and layer boundaries |
| `./gradlew :core:device:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nexa.mobile.operations.core.device.scanner.BarcodeDecodingPoCTest --dependency-verification strict --console=plain` | PASS on API 37: 2 tests, 0 failures, 0 skipped; static synthetic QR/EAN-13 decoding only |
| `./gradlew :app:compileDebugKotlin testDebugUnitTest --dependency-verification strict --console=plain` | FAILED: strict verification rejected missing metadata for `androidx.collection:collection:1.4.2`, `collection-1.4.2.module`; aggregate execution did not complete |
| `./gradlew ktlintFormat :app:compileDebugKotlin testDebugUnitTest --dependency-verification strict --console=plain` | FAILED: ktlint requires `ScannerModels.kt` in BC-03 application to be named `ProductScannerResolution.kt`; formatting and subsequent checks did not complete |

The Dart experiment's `dart analyze` and fixture runner passed (12 synthetic
SKU resolver cases); the Kotlin counterpart also passed in the resumed aggregate JVM gate. These checks do not establish physical camera operation or
Product Acceptance. Earlier failed compilations and behavior checks remain
negative evidence; subsequent source corrections require a full rerun.

## Resumed execution

The missing dependency artifacts were compared byte-for-byte against official
Google Maven or Maven Central downloads and their published SHA1 checksums
before SHA256 metadata was added. Strict verification remains enabled.
Formatting corrections, context read-contract refinement, protected PDF
rendering adapters and Spanish resource coverage resolved the observed blockers.

| Command | Observed result on the resumed tree |
| --- | --- |
| `scripts/verify-connected-local.sh emulator-5554 -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.nexa.mobile.operations.RequiresPrivateFixture verifyAndroidArchitecture ktlintCheck lintDebug testDebugUnitTest :app:assembleDebug --console=plain --continue` | PASS on API 37, with strict dependencies enforced by the runner: 64 native tests and all requested static gates |
| `scripts/verify-connected-local.sh emulator-5554 -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.nexa.mobile.operations.RequiresPrivateFixture --console=plain --continue` | PASS on API 29, strict dependencies: 64 native tests, no failures/errors/skips |
| `python3 scripts/verify-context-architecture.py` | PASS: 11 context roots and checked client layers |
| `python3 scripts/test-context-architecture.py` | PASS: baseline plus 6 isolated negative dependency/import/IO probes |
| `./gradlew verifyAndroidArchitecture ktlintCheck lintDebug testDebugUnitTest :app:assembleDebug --dependency-verification strict --console=plain` | PASS: 524 JVM tests, 0 failures/errors/skips across 114 XML suites in configured modules |
| Six `:app:validateReleaseEndpoint` cases from the CI workflow | PASS: missing, cleartext local, HTTPS local, example.com, example.org and example subdomain origins rejected for their expected validation reason |
| `dart format --set-exit-if-changed`, `dart analyze`, and the shared-fixture experiment runner | PASS: no formatting change, no analysis issues, 12 synthetic SKU contract fixtures |
| `python3 -m unittest discover -s experiments/mobile-autonomous-learning -p 'test_*.py' -v` and `python3 experiments/mobile-autonomous-learning/run_experiment.py` (repository root) | PASS: 6 experiment checks and 20 synthetic cases; no model output or human-learning measurement |

BC-04 verification covers 201 confirmed versus 202 pending-prepaid outcomes,
scope/request/receipt enclosure, unchanged explicit replay and legacy Purchase
Request intent retention. BC-05 golden/tamper tests reject mismatched staged
facts and orphaned body/key pairs. BC-06 exposes a narrow allocation query;
its domain no longer imports BC-05. BC-09 platform rendering is behind a JVM
application port, with bounded pixel results and process-scoped temporary-file
cleanup. If OS deletion fails, a current-process artifact can remain in private cache until the next process cleanup; interruption before unlink has the same residual limit. New native PDF cases remain subject to the connected runs below. The first full API 37 run recorded 36 tests with 10 failures: nine locale-dependent UI assertions and one capture method without its private credentials. Locale assertions now use resource lookups and distinguish titles from actions. Ordinary-run configuration excludes the `RequiresPrivateFixture` annotation; the API 37 rerun app XML confirms 35 cases with no failures/errors/skips and no private-fixture classes. The independent capture-flag regression remains included. These failed runs are not successful full native verification. A later full run reached BC-04 presentation and found Espresso 3.5 incompatible with API 37 (`InputManager.getInstance`); that test module now uses the existing pinned Espresso 3.7 alias and the complete rerun passed.

The [Sprint traceability](sprint-1-2-implementation-traceability.md) maps all 36
original Mobile PBIs. This is coverage of the mapping, not 100% Sprint or
Product acceptance. SPIKE-001 now has a bounded technical investigation with six Python checks and 20 synthetic cases; model execution and runtime adoption remain deferred. The separate Buyer runtime scope is recorded explicitly.

The script verifies the 11 roots, module/layer shape, JVM framework boundaries,
context import direction and removal of source from legacy `feature` and `data`
roots. It is a structural check, not a build or behavior test.

The following table separates completed local gates from remaining gates:

| Gate | Status |
| --- | --- |
| Gradle `verifyAndroidArchitecture` | PASS on resumed working tree |
| `ktlintCheck` | PASS on resumed working tree |
| `lintDebug` | PASS on resumed working tree |
| Aggregate `testDebugUnitTest` | PASS on resumed working tree |
| `:app:assembleDebug` with strict dependency verification | PASS on resumed working tree |
| API 37 connected instrumentation | PASS: 64 tests, 0 failures/errors/skips across 13 XML suites; 35 app cases |
| API 29 promotion instrumentation | PASS: 64 tests, 0 failures/errors/skips across 13 XML suites; 35 app cases |
| BC-05/BC-06 moved storage JVM tests | PASS in the aggregate JVM gate |
| BC-05/BC-06 moved storage Android instrumentation tests | PASS on API 37 and API 29 as part of each complete connected gate |
| Release-origin rejection | PASS: six expected rejections |
| `:app:assembleRelease -PnexaReleaseApiBaseUrl=https://nexa-api-69bj.onrender.com/ --dependency-verification strict` | PASS after final Direct Order response validation refinements |
| CI result for an immutable candidate | PENDING |

The storage tests now belong to the context infrastructure modules. BC-05 JVM
tests are `ReceivingMetadataStoreCoreTest`,
`DispositionMetadataStoreCoreTest` and
`TemperatureEvidenceMetadataStoreCoreTest`; its Android tests are
`AndroidReceivingMetadataStoreTest`, `AndroidDispositionMetadataStoreTest` and
`AndroidTemperatureEvidenceMetadataStoreTest`. BC-06 JVM and Android tests are
`PickingMetadataStoreCoreTest` and `AndroidPickingMetadataStoreTest`. They
reside under `contexts/{inventoryavailability,fulfillmentdelivery}/infrastructure/src/{test,androidTest}`.
The moved JVM storage tests passed in the resumed aggregate. Their Android
instrumentation passed on API 37 and API 29.

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

## Completion and remaining integration

The resumed execution verified publisher artifacts under strict dependency
verification, corrected source filenames and formatting, tested Direct Order
response enclosure and recovery, checked Inbound Discrepancy frozen payloads,
and passed the static/JVM and full API 37/API 29 gates. The original Sprint
scope is mapped separately from the expanded Jira projection. Release-origin
rejections and R8 assembly also passed.

The APK was signed with the existing official certificate and passed signature
and 16 KB alignment checks; its checksum is recorded in the candidate notes.
The corrective implementation and research commits are signed locally.
Remaining work is to require GitHub CI for the immutable candidate,
integrate the corrective branch and publish v1.1.0 with the same official
Android certificate. Close the task's branches only after integration. Signing
secrets remain outside this repository.

The prior invalid Mobile PR #38 is closed. This corrective Mobile branch remains
open for completion. Android version values target 1.1.0 (code 10), but no tag,
release, completed Sprint acceptance or current production gate is claimed.
The locally signed APK is recorded in the [candidate notes](releases/v1.1.0.md). Blueprint PR #33 is merged, its Diego-authored commit is GitHub
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
