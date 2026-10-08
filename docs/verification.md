# Android foundation verification

The [DDD client execution record](ddd-client-verification.md) contains the
current refactor status. As of 2026-10-08, only the Python check for 11 context
roots has passed; all Gradle and device gates for the current refactor remain
pending. Earlier observations below retain their original candidate scope and
do not describe the current refactor.

Run Gradle commands from `apps/operations-android` with JDK 17. Install Android SDK packages `platforms;android-37.0`, `build-tools;36.0.0`, and `platform-tools`. Keep the Gradle wrapper and dependency verification metadata in the repository; review the bytes and source of a new dependency artifact before accepting its checksum. The wrapper pins the official SHA-256 for the Gradle 9.7.1 binary distribution.

## Local static and JVM gates

```sh
cd apps/operations-android
python3 scripts/verify-context-architecture.py
./gradlew verifyAndroidArchitecture ktlintCheck lintDebug \
  testDebugUnitTest :app:assembleDebug \
  --dependency-verification strict --console=plain
```

The Python script checks the canonical roots and context-layer source
boundaries. Gradle `testDebugUnitTest` aggregates tests from every configured
Android module and the Kotlin/JVM `domain` and `application` modules. Android
JVM XML files are in each Android module's
`build/test-results/testDebugUnitTest`; Kotlin/JVM XML files are in
`contexts/<root>/{domain,application}/build/test-results/test`. Lint reports
are under each Android module's `build/reports/lint-results-debug.html`. A zero
exit status is necessary, but inspect XML test and failure counts before
recording a result.

## Emulator gates

Run one booted emulator at a time and confirm its API level before each connected test run:

```sh
adb devices -l
adb shell getprop ro.build.version.sdk
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.nexa.mobile.operations.LiveCandidateIdentityIntegrationTest \
  --dependency-verification strict
```

The unqualified connected task includes instrumentation from every configured
Android module. Modules without instrumentation sources contribute no tests.

Use an API 37.0 image for feature verification and an API 29 image for promotion verification. On Apple Silicon the local image ABI is `arm64-v8a`; the Linux CI job uses `x86_64`. Install the matching `google_apis` system image for the host architecture. Check the generated XML in each module's `build/outputs/androidTest-results/connected/debug` directory after each run. Gradle can replace results from an earlier emulator run, so retain the API level, command, timestamp, and test counts in the execution record.

## Opt-in local integration

`LiveCandidateIdentityIntegrationTest` uses the production native identity gateway and production screens against an actual local API. Supply `nexaLiveIdentifier`, `nexaLivePassword`, `nexaLiveQuery` and `nexaLiveSku` as instrumentation arguments from private local configuration. Keep credential values out of source, reports, command transcripts and screenshots. The fixture must establish one currently authorized context with Catalog read capability and a query returning the expected SKU.

The test waits for server-confirmed work entry, submits the query explicitly, verifies that the candidate is not already confirmed, selects it, and checks the resulting detail-confirmed SKU and inventory disclaimer. Deterministic debug scenarios and MockWebServer tests remain separate evidence. Ordinary CI excludes the opt-in test; missing local credentials do not prove integration.

Android 17/API 37 requires an OS runtime permission for the emulator's local host
API connection ([Android local network permission](https://developer.android.com/privacy-and-security/local-network-permission)).
Only the debug manifest declares `ACCESS_LOCAL_NETWORK`; the opt-in live test
grants that permission through UiAutomation. For an installed debug APK used for
manual local review on API 37, grant it before launch:

```sh
adb shell pm grant com.nexa.mobile.operations android.permission.ACCESS_LOCAL_NETWORK
```

This OS permission does not grant Catalog authority. Denial leaves requests
unconfirmed. Release neither declares the local permission nor permits a local
service origin.

## Release build safety

Release assembly requires a non-local HTTPS root origin supplied at build time. The example below exercises shrinking with a verification-only origin; its APK is not configured for Nexa production service or distribution.

```sh
./gradlew :app:assembleRelease \
  -PnexaReleaseApiBaseUrl=https://github.com/ \
  --dependency-verification strict
```

Also verify that `:app:validateReleaseEndpoint` fails when the property is omitted or set to a cleartext, local, or obvious placeholder origin. The release build uses R8 code and resource shrinking. No production endpoint or distribution signing material is stored here.

## CI status

The workflow `.github/workflows/android-verify.yml` emits one stable `verify` status. For Android changes on a feature branch it requires architecture, formatting, lint, JVM contract tests, debug assembly, and API 37 instrumentation. For a pull request to `main` or a push to `develop` or `main`, it additionally requires Gradle to reject missing, cleartext local, HTTPS local, and placeholder release origins, then runs release assembly with a verification-only HTTPS origin and API 29 instrumentation. Read the individual job conclusions along with `verify`; a skipped promotion job is expected on a feature push. A green local run does not substitute for the workflow result on the pushed commit.

## Observed local feature run — 2026-09-24

This connected instrumentation command exited 0 on the local `Nexa_API37` AVD:

```sh
./gradlew :core:auth:connectedDebugAndroidTest :app:connectedDebugAndroidTest \
  --dependency-verification strict --no-daemon --console=plain
```

- `:core:auth`: 6 tests, 0 failures, 0 errors, 0 skipped.
- `:app`: 15 tests, 0 failures, 0 errors, 0 skipped.
- The XML reports are under each module's `build/outputs/androidTest-results/connected/debug` directory.
- The run used the local dirty checkout at `0f6c620db8ec`; the results are not bound to an immutable candidate commit.
- These instrumentation tests do not establish a live Android sign-in, access-context selection, or business workflow against the v0.18.0 API candidate. Product Acceptance remains open.

## Observed earlier candidate — 2026-09-27

The final Android source for this earlier checkpoint passed the following local gates with strict dependency verification. The execution record retains the tested file hashes and Android source fingerprint `5286ca57b7be867cabbea380887c3929d37a7c16a525394cfd6088d11f4e1ae3` so that the signed candidate can be compared with the tested bytes.

| Gate | Exit status | Tests | Failures / errors / skips |
| --- | --- | --- | --- |
| Architecture, ktlint, debug lint, JVM tests, debug/test APK assembly and release shrinking | 0 | 75 JVM | 0 / 0 / 0 |
| Ordinary API 37 instrumentation, excluding the opt-in live test | 0 | 28 | 0 / 0 / 0 |
| Ordinary API 29 instrumentation, excluding the opt-in live test | 0 | 28 | 0 / 0 / 0 |
| Opt-in native identity and Catalog integration on API 37 | 0 | 1 | 0 / 0 / 0 |

The static command was the local gate above with `:app:assembleDebugAndroidTest` and `:app:assembleRelease -PnexaReleaseApiBaseUrl=https://github.com/` added. Each ordinary connected run used the emulator command above. The live run selected only `LiveCandidateIdentityIntegrationTest` and supplied its arguments from private local configuration.

JUnit XML recorded 12 auth, 36 network, 7 access, 14 warehouse and 6 app JVM tests. Connected XML recorded 6 auth and 22 app tests on each emulator. The app tests include current-state Navigation 3 regression coverage and production A-01, C-01, P-01, W-01 and W-02 controls in a 320×640dp viewport at 200% font scale.

The native integration reached identity sign-in, a server-confirmed current context, permitted task entry, explicit Catalog search, unconfirmed candidate selection and detail-confirmed W-02. It used existing local development data and performed no inventory operation. This automated fixture had one context; the opt-in test alone does not prove a real multi-context account or physical-device execution.

A separate manual runtime fixture used an isolated PostgreSQL clone with two real authorized contexts. Context A reached detail-confirmed `PROD-0003`; switching to context B cleared A's confirmed identity, recalculated task visibility, and allowed a fresh explicit search and detail confirmation in B. Back navigation returned to B's work entry without exposing A's confirmed SKU. This exercised the production identity, access-context and Catalog gateways through the installed debug APK and local API. The original database was not mutated by the fixture.

This fixture also exposed a stale warehouse failure from an earlier session that could log out a newly selected context. The root navigation now acts on a warehouse invalidation only when its originating authority epoch matches the currently authorized context. Three JVM regressions cover stale, current and unscoped failure routing; the corrected APK was used for the two-context run and both emulator suites.

Repeated native verification exposed response-body reads on Android Main after asynchronous response headers. Protected transport now consumes and closes the response body on the OkHttp callback thread before resuming the caller. Focused regressions cover the consuming thread and cancellation after headers while body consumption is active. The thread regression failed against the previous implementation and passed after the correction. Native timeout diagnostics retain only route, status, failure category, candidate count and confirmation presence; assertions and deadlines remain unchanged.

Live instrumentation arguments were supplied through private Gradle project environment properties. A report audit found no exact credential values in the generated Android test results or reports. Earlier failed diagnostic runs remain separate from the final passing XML evidence.

All six release-origin negative cases in CI were also rejected locally for the expected reason. APK inspection confirmed that the shrunk release excludes debug review classes/resources, cleartext configuration and `ACCESS_LOCAL_NETWORK`. The release origin remains verification-only and the APK is not distribution-signed.

These are technical results. Human Product/UX Acceptance, System Acceptance and Production Readiness remain separate open gates.

## Isolated local instrumented execution

AGP 9.4.1's serial-device filter throws `UnsupportedOperationException` before tests when it removes from an immutable device collection. Local verification must use one connected device without `--serial` or `ANDROID_SERIAL`. The helper refuses to run when another device is connected; it does not stop, disconnect or mutate other devices. It also serializes Gradle workers and provides a bounded 6 GiB daemon heap for Android test dex assembly. CI already provisions a single emulator and does not use this serial filter.

From the Android project, after starting only the chosen API29 or API37 emulator:

```sh
scripts/verify-connected-local.sh emulator-5554 --console=plain
```

Use the actual serial from `adb devices`. A refused invocation or a build failure is not instrumented test evidence. Optional live-identity tests still require private local credentials and exact API provenance.
