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
| CI result for the published candidate | PASS: [run 37817216074](https://github.com/nexa-suite/mobile/actions/runs/37817216074), all five jobs passed on candidate commit `044006c1acebbe006a62dd9360686d3b258dfbda`; published tag `v1.1.0` points to `main` commit `6cdb4318fa2e41a0268cce8900c138caffc93aec` |
| PR #41 initial synchronization checkpoint (superseded below) | Historical: at the GitHub PR head observed on 2026-10-08, `f5427857c74fafa041685e22d082bf3af45b8183`, static and API 37 checks passed in [run 37822759683](https://github.com/nexa-suite/mobile/actions/runs/37822759683); API 29 promotion was skipped. At that checkpoint corrective validation had not run; the post-release section below supersedes this status. |

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
the Direct Order client checks are recorded in the resumed and corrective
sections. These checks do not establish end-to-end Product acceptance.

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

## Published v1.1.0 and follow-up work

The resumed execution verified publisher artifacts under strict dependency
verification, corrected source filenames and formatting, tested Direct Order
response enclosure and recovery, checked Inbound Discrepancy frozen payloads,
and passed the static/JVM and full API 37/API 29 gates. The original Sprint
scope is mapped separately from the expanded Jira projection. Release-origin
rejections and R8 assembly also passed.

The APK was signed with the existing official certificate and passed signature
and 16 KB alignment checks. Its published asset SHA-256 is
`a5e27394de8fa422599ca463dd1d6ee3f6f80d8a06c157303234dd1b4e91f769`; the
[v1.1.0 GitHub Release](https://github.com/nexa-suite/mobile/releases/tag/v1.1.0)
was published on 2026-10-08. Tag `v1.1.0` points to `main` commit
`6cdb4318fa2e41a0268cce8900c138caffc93aec`. Candidate CI run 37817216074
passed; signing secrets remain outside this repository.

PR [#41](https://github.com/nexa-suite/mobile/pull/41) remains open to
synchronize v1.1.0 into `develop` and contains follow-up changes after the
published tag. Those changes are not part of v1.1.0. The latest reported PR
checkpoint `e091be0eb737a37ecfe28e44b7b776c96d5dca92` passed
[PR #42 CI run 37852789349](https://github.com/nexa-suite/mobile/actions/runs/37852789349).
The corrective source at `b18337e63accdbd56906ad73b5294345b1727ac0`
passed local static/JVM, API 29/API 37 and release-build gates. The Android
Studio follow-up below changes dependency trust metadata and documentation;
its updated-head CI, review and integration remain pending. The
release is controlled direct distribution against the validation API, not a
production deployment. Blueprint PR #33 is merged and remains the accepted
Direct Order scope source.

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

No Product/UX Acceptance, System Acceptance, production readiness or physical
device acceptance is claimed for the current refactor. The v1.1.0 GitHub
Release is published as controlled direct distribution against a validation
API origin. Publication is not evidence of Product acceptance, System
Acceptance, production readiness or deployment. The post-release changes in
PR #41 and PR #42 remain outside the tag. Local technical validation and CI at
`e091be0` passed; the subsequent Android Studio follow-up still requires
updated-head CI, review and integration.

## Post-release corrective verification — 2026-10-08

Source commit `b18337e63accdbd56906ad73b5294345b1727ac0` moves verified
workforce scope and commercial permission hints to BC-01 application public
APIs, maps BC-03 Catalog scope in its adapter, removes the foreign-domain
exception and restores the new-decision action for terminal Direct Order
failures. Unknown outcomes retain the original idempotency key and body.
These changes are not in the published `v1.1.0` tag.

Executed from `apps/operations-android` with JDK 17 and strict dependency
verification:

- `python3 scripts/verify-context-architecture.py`: PASS, 11 roots.
- `python3 scripts/test-context-architecture.py`: PASS, baseline and eight
  negative probes, including BC-01 domain dependency/import rejection.
- `./gradlew verifyAndroidArchitecture ktlintCheck lintDebug testDebugUnitTest
  :app:assembleDebug assembleDebugAndroidTest --dependency-verification strict
  --console=plain --continue`: PASS; 528 JVM cases in 116 XML suites, zero
  failures, errors or skips. `ktlintFormat` ran before verification.
- `scripts/verify-connected-local.sh <serial>
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.nexa.mobile.operations.RequiresPrivateFixture
  --console=plain`: PASS separately on API 37 and API 29; each recorded 65
  cases in 13 XML suites with zero failures, errors or skips. Serial was
  `emulator-5554` for API 37 and `emulator-5556` for API 29. The BC-04 Compose
  recovery case clicks the new-decision action for both terminal failures and
  checks same-key replay for an unknown outcome. Private-fixture tests remain
  outside the ordinary suite.
- `./gradlew :app:assembleRelease
  -PnexaReleaseApiBaseUrl=https://nexa-api-69bj.onrender.com/
  --dependency-verification strict --console=plain`: PASS, including R8 and
  release lint. This build was not signed or published as another release.
- Documentation scan: 294 relative links, zero broken; `git diff --check`: PASS.

Three earlier aggregate attempts failed on call sites and test fixtures that
still passed BC-01 scope to Catalog, plus an invalid Compose assertion import.
The affected composition calls and fixtures now explicitly map or construct
Catalog-owned scope; the import was removed. The final aggregate above passed.
Remote CI subsequently passed at `e091be0`; required review and integration
remain separate gates.

## Android Studio verification — 2026-10-08

Android Studio Rabbit 1 (2026.2.1) opened the corrective Android project in a
separate window at branch checkpoint `e091be0`, preserving the original
checkout. The first two IDE syncs failed because strict dependency verification
had no trusted hashes for six source/sample JARs requested by the IDE. The
cached bytes were compared with official Google Maven artifacts and published
SHA1 values before adding SHA256 entries. No dependency version changed and
strict verification was not disabled.

- Gradle Sync: finished successfully after the six source hashes were added.
- IDE **Assemble 'app' Run Configuration**, executing `:app:assembleDebug`:
  `BUILD SUCCESSFUL in 7s`.
- IDE **Run 'app'**: `:app:assembleDebug` passed in 6s, installation succeeded,
  and the embedded `Nexa_DDD_API29` emulator displayed the Nexa Operations
  sign-in screen. No account credentials or protected business flows were
  exercised in this manual startup check.
- `python3 scripts/verify-context-architecture.py`: PASS, 11 roots.
- `python3 scripts/test-context-architecture.py`: PASS, baseline and eight
  negative probes.

This IDE check verifies import, debug assembly, installation and startup. It
does not establish architectural completion or Product acceptance. The accompanying source review found composition-root workflow ownership
and Direct Order terminology issues. The later
[audit-driven refinements](client-ddd-alignment.md#audit-driven-ownership-refinements)
record their corrective implementation. Updated-head CI is
required for this dependency-metadata/documentation follow-up.

## Audit correction verification — 2026-10-08

Source commit `dd148bebd0153bbbd2150e1cd2d4d2211869ae4a` includes the
current-context invalidation fence, immutable Credit and Picking public
contracts, BC-03 work entry, BC-05/BC-06 returned evidence ownership, Direct
Order terminology and persisted-record compatibility, and stronger boundary
fitness checks. These corrections remain outside published `v1.1.0`.

Executed with JDK 17 and strict dependency verification:

- `python3 scripts/verify-context-architecture.py`: PASS, 11 canonical roots.
- `python3 scripts/test-context-architecture.py`: PASS, baseline and 13
  negative probes.
- `./gradlew ktlintFormat verifyAndroidArchitecture ktlintCheck lintDebug
  testDebugUnitTest :app:assembleDebug assembleDebugAndroidTest
  --dependency-verification strict --console=plain --continue`: PASS,
  `BUILD SUCCESSFUL in 46s`; 555 JVM cases in 120 XML suites, zero failures,
  errors or skips. Cached unchanged tasks are included in the aggregate.

- `scripts/verify-connected-local.sh emulator-5554
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.nexa.mobile.operations.RequiresPrivateFixture
  --console=plain`: PASS separately on API 29 (`1m 30s`) and API 37
  (`1m 33s`). Each recorded 65 cases in 13 XML suites, zero failures, errors
  or skips. Only the named AVD was connected for each run. Private-fixture
  tests were excluded from this ordinary suite.

- `./gradlew :app:assembleRelease
  -PnexaReleaseApiBaseUrl=https://nexa-api-69bj.onrender.com/
  --dependency-verification strict --console=plain`: PASS in 2m 25s,
  including R8, resource shrinking and release lint. The APK is unsigned;
  this is build evidence, not another published release.
- Documentation scan: 265 relative file links, zero broken;
  `git diff --check`: PASS.

Android Studio opened the corrective project and completed Gradle Sync in 4s.
IDE **Run 'app'** completed `:app:assembleDebug` in 9s, installed successfully
in 4s 88ms, and displayed the Nexa Operations sign-in screen on
`Nexa_DDD_API29`. This manual check exercised startup without credentials.

Earlier targeted runs failed on an extracted codec function reference, a
cross-module test smart cast, fixture line length, and a refresh/replay HTTP
fixture. They were corrected before the aggregate. The first aggregate found
a renamed shared resource reference, a nullable dispatch test reference and
an incorrect expected length-prefixed key. The second aggregate passed its
behavioral tests but failed lint on three `Uri.parse` calls in new adapters.
Those calls now use the required KTX extension; the final aggregate above
passed. No validation or dependency-verification control was disabled.

API `origin/main` at `78a3060cb56796520fcf8e9be36c63f88b4f9f51` already
returns the required `403 ACCESS_CONTEXT_INVALID` contract, so no API code
change was needed. Blueprint implementation status is proposed separately in
[PR #34](https://github.com/nexa-suite/blueprint/pull/34); it distinguishes
published Operations from missing Buyer runtime and pending acceptance.

Product/UX Acceptance, System Acceptance, physical-device verification,
private-fixture integration and production readiness remain separate gates.
