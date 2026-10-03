# Operations Android validation — 2026-10-02

## Scope and source

This report records validation of source commit `7e3d4e37ec1edcd57b10531bf6bfc305d22bf669` in the Operations Android project. No application or test source changes were made for this run. Validation used OpenJDK 17.0.19 and Gradle 9.7.1 with strict dependency verification.

The automated checks passed for architecture, ktlint, debug lint, JVM tests, debug/release packaging, release-origin validation, and R8 minification. Android Studio Gradle Sync also completed successfully, with the `app`, `core`, and `feature` modules loaded.

## Automated build and JVM checks

The following command completed successfully:

```sh
./gradlew --dependency-verification strict --console=plain \
  verifyAndroidArchitecture ktlintCheck lintDebug testDebugUnitTest \
  :app:assembleDebug :app:assembleRelease \
  -PnexaReleaseApiBaseUrl=https://github.com/
```

The build passed all 456 JVM tests across 93 result files: 0 failures, 0 errors, and 0 skipped. Debug and release APK assembly passed. The release build ran `validateReleaseEndpoint` and `minifyReleaseWithR8` successfully. The supplied `https://github.com/` value exercised the non-local HTTPS origin guard; it is not a Nexa service endpoint or production configuration.

The compiler still reports non-blocking warnings, including an always-true condition at `MainActivity.kt:1952:29` and unnecessary safe-call/non-null-assertion warnings in other Kotlin sources.

## Emulator instrumentation

Instrumentation ran on API 29 (`emulator-5556`) and API 37 (`emulator-5554`), with each device selected explicitly. For each API level, `:core:auth` and `:core:local` connected instrumentation contributed 22 passing tests. The application suite ran each of these nine test classes individually with `:app:connectedDebugAndroidTest` and `-Pandroid.testInstrumentationRunnerArguments.class=<class>`:

```text
com.nexa.mobile.operations.DebugNetworkSecurityTest
com.nexa.mobile.operations.DebugReviewActivityTest
com.nexa.mobile.operations.MainActivitySmokeTest
com.nexa.mobile.operations.ProductScreensAccessibilityTest
com.nexa.mobile.operations.ProductScreensUiTest
com.nexa.mobile.operations.RootCapabilityInvalidationTest
com.nexa.mobile.operations.RootNavigationSecurityTest
com.nexa.mobile.operations.RootNavigationStateTest
com.nexa.mobile.operations.ScannerScreensUiTest
```

The application suite passed 31 tests per API level. Combined with the core suites, each API level passed 53 instrumentation tests with 0 failures, 0 errors, and 0 skipped. The test classes were explicitly allowlisted one class at a time; no broad class exclusion was used for the reported totals.

The command form used for each application class was:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew --dependency-verification strict \
  --console=plain --info :app:connectedDebugAndroidTest \
  -Pandroid.injected.device.serial=emulator-5554 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.nexa.mobile.operations.ProductScreensUiTest
```

For API 29, the same command used `emulator-5556`. The class argument was changed to each class listed above. The core suites were run on the same explicit serials through `:core:auth:connectedDebugAndroidTest` and `:core:local:connectedDebugAndroidTest`.

## Screen and user-flow coverage

The Compose tests cover semantic screen content and deterministic state transitions:

| Area | Covered behavior |
| --- | --- |
| Access and client context | Application graph and access screen render; identity controls expose labels and errors; password visibility is accessible; context selection shows the current marker and pending choices. |
| Work entry and product identification | The accepted product task is shown; search requires explicit submission; candidates can be selected; empty results are explicit; unavailable permission is explained. |
| Product confirmation | A confirmed SKU is displayed as read-only, with an explicit statement that inventory was not changed. |
| Scanner fallback | Camera denial leaves manual identification and settings available; resolving or ambiguous codes do not become a confirmed identity. |
| Navigation and session security | Signed-out or mismatched context does not render protected work; capability loss clears the deep route and confirmed identity; commercial routes return to Work Entry through navigation. |
| Accessibility and long content | Large-font tests cover below-fold access, context selection, search results, confirmed details, long candidates, and merged accessible actions. |
| Review scenarios | Debug review cases render the production confirmation screen for optional attributes and a long product name. |

These are Compose semantic and state assertions, including local test dependencies. They establish screen and navigation behavior for the asserted states; they do not establish screenshot fidelity, live-role journeys, or server-backed business acceptance.

## Physical-device smoke and debug API origin

A separate debug APK was built from a clean snapshot of the source commit with strict dependency verification and:

```sh
./gradlew --dependency-verification strict --console=plain \
  :app:assembleDebug \
  -PnexaDebugApiBaseUrl=http://127.0.0.1:8080/
```

The generated `BuildConfig.API_BASE_URL` was verified as `http://127.0.0.1:8080/`. The APK SHA-256 is:

```text
abbe9b6656c2c8fcc9c8710f9e275a8c8cd264597820f555bae63a5989768044
```

On a Samsung SM-S908E running API 36, the host-to-device `adb reverse tcp:8080 tcp:8080` command passed, APK installation for user 0 succeeded, and launching `com.nexa.mobile.operations/.MainActivity` returned `Status: ok` (`WaitTime: 3033 ms`, `Complete`). A follow-up app-scoped 200-entry logcat review found no fatal exception and the application process remained running. No sign-in, role traversal, permission grant, or screenshot was performed; these observations do not establish a complete authenticated workflow.

The normal debug default remains `http://10.0.2.2:8080/`, which is intended for the Android emulator. For a physical-device local-backend session, build with `-PnexaDebugApiBaseUrl=http://127.0.0.1:8080/`, start the API on host port 8080, then run `adb reverse tcp:8080 tcp:8080` for the selected device. The debug network security policy explicitly allows these development hosts. The build property is a per-build override; the shared project default and IDE settings were not changed.

## Exclusions and open evidence

- `RoleWireflowCaptureTest` was intentionally not run because it creates screenshots and requires the capture workflow. No screenshots or document images were generated.
- `LiveCandidateIdentityIntegrationTest` is not part of the passing totals. The preliminary API 37 invocation supplied two comma-separated names to `notClass`; the runner applied the first exclusion but still selected this live class. It reported `AssumptionViolatedException` because candidate credentials were unavailable; no authenticated journey ran. This is recorded as an unverified live test, not a pass or a skip. It was not run on API 29. The passing deterministic suite was rerun using the explicit class allowlist above.
- No authenticated live roles, warehouse business writes, or end-to-end API acceptance were exercised. Product Acceptance, visual review, System Acceptance, and production readiness remain open.
- Release packaging and the non-local URL guard do not verify a live production endpoint or deployment.

## Evidence record

Raw Gradle logs and XML results were retained in the external Operations Mobile validation evidence bundle; generated evidence is not included in this source change. The recorded run used source `7e3d4e37ec1edcd57b10531bf6bfc305d22bf669` with no application or test source diff. This report preserves the test scope, exclusions and limitations; existing untracked artifacts were preserved.
