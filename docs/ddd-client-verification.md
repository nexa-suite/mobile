# DDD client refactor verification — 2026-10-08

## Source and scope

Implementation baseline: mobile `f3b425867184ca0463e3dbb105ba48e0642ab824`
(v1.0.1). Accepted architecture: Blueprint
`9034f4857b45224832f61af3e088f8a29143b187`, including ADR-0020. See
[DDD alignment](client-ddd-alignment.md) for responsibility and authority mapping.
Mobile Report was consulted as an academic projection; no report content or
Product semantics were changed.

The tested Android source/configuration bytes match mobile commit
`0856efd11956f9b7b4c044e185a9ab1f8016b88e`. Their SHA-256 fingerprint is
`1b37163a547549242eadf3de7ee218c2e4aabc8c3db1d66e6242352c8107b413`
over 705 files. Fingerprinting uses sorted existing repository-relative paths
under `apps/operations-android`, a NUL, file bytes and a NUL for each file;
ignored builds, caches and machine configuration are excluded.

The refactor preserves existing client workflows and moves their projections,
ports, presentation and adapters into enforced layers. Eight additional contract
tests cover the selective receiving and field-request coordinators. Dependency
verification remains strict. Added JVM plugin/scripting and newly resolved
adapter transitive artifact checksums were compared with publisher checksums
from Maven Central and Google Maven; production library versions were retained.

## Executed local gates

Commands below ran from `apps/operations-android` with JDK 17 and SDK 37.0.

```sh
./gradlew verifyAndroidArchitecture ktlintCheck lintDebug testDebugUnitTest \
  :app:assembleDebug --dependency-verification strict --continue --console=plain

scripts/verify-connected-local.sh emulator-5554 --console=plain \
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.nexa.mobile.operations.LiveCandidateIdentityIntegrationTest

./gradlew :app:assembleRelease -PnexaReleaseApiBaseUrl=https://github.com/ \
  --dependency-verification strict --console=plain
```

All three commands exited 0. Release assembly exercised R8 and resource
shrinking with a verification-only origin; its APK is not a production or
distribution artifact.

| JVM module | Tests | Failures / errors / skips |
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
| **Total** | **475** | **0 / 0 / 0** |

Access, dispatch and delivery contracts have no independent test sources; their
existing behavior suites remain in presentation and adapters.

Connected execution used the `Nexa_DDD_API37` arm64 AVD. Boot completion and
`ro.build.version.sdk=37` were checked. XML recorded:

| Instrumented module | XML tests | XML failures / errors / skips |
| --- | ---: | --- |
| core/auth | 6 | 0 / 0 / 0 |
| core/local | 16 | 0 / 0 / 0 |
| app | 36 | 1 / 0 / 0 |

The single app XML failure is `AssumptionViolatedException` from
`RoleWireflowCaptureTest.captureConfiguredRoleHomeAndEntries`: role credentials
and the first entry label were not configured. Gradle treats this as an
assumption and exits 0. Thus 57 cases completed without a reported failure and
one credential-dependent capture did not execute; the raw XML classification
is retained here. The opt-in live identity class was explicitly excluded.

Four temporary negative probes each ran `verifyAndroidArchitecture` with
`--dependency-verification strict --console=plain`. They injected, respectively,
an Android import into a contract, a lifecycle import into data, a data import
into warehouse presentation and a delivery import into warehouse presentation.
Each exited nonzero with the expected boundary message. Probe files were
removed in cleanup; the restored source passed the same command. No probe or
failed-run report is part of the shipped source.

Earlier extraction compile and lint failures were repaired before these final
commands: module imports/visibility, nullable public-property snapshots, Hilt
composition providers, test annotation placement and duplicated commercial
resource ownership. No test assertion or security gate was disabled.

## Limits and integration gates

Local results do not substitute for CI on the pushed candidate. GitHub signature
verification and workflow results must be checked on that exact head separately.
API 29 promotion instrumentation was not run locally. Live API credentials,
role captures, physical devices, Product/UX Acceptance, System Acceptance and
Production Readiness are outside this execution evidence. Existing historical
release and live-fixture records retain their original scope.
