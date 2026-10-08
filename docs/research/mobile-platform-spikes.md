# Mobile spike research and evidence

**Observed:** 2026-10-08. This record separates the original Mobile Report
spike IDs from the supporting research artifacts. The resumed static/JVM gates
passed. Connected Android instrumentation also passed on API 37 and API 29
AVDs: each run recorded 64 tests with zero failures, errors, or skips across
13 module XML reports (eight reports contained tests; the others were empty).
These runs do not establish physical-device behavior, live integrations, or
Product/System acceptance. See [the verification
record](../ddd-client-verification.md). These notes add no runtime Buyer or
Operations capability, API route, authorization rule, or business decision.

## SPIKE-001 — autonomous-learning feature investigation

The original Mobile Report defines SPIKE-001 as technical R&D about an
autonomous-learning feature for a representative Mobile flow. Its acceptance
criteria require a bounded question and data/business-rule limits, comparison
of at least two alternatives, a reproducible experiment using synthetic or
non-sensitive inputs, failure isolation, and a conclusion with backlog
refinement. The story is not evidence of human training, a model feature, or
Product acceptance. See the [original story and acceptance
criteria](https://github.com/nexa-suite/mobile-report/blob/55f959441fb4d5422519db31c380631e95ed72e3/report/02-requirements-and-software-solution-design/2.4-requirements-specification/2.4.1-user-stories/spike-stories.md).

The current accepted [Mobile projection in Blueprint commit
`3574accc8962346a824a043ef8ea5564600c08b0`](https://github.com/nexa-suite/blueprint/blob/3574accc8962346a824a043ef8ea5564600c08b0/01-shared/domain/strategic-ddd/mobile-projection.md)
preserves server authority and states that Mobile projects existing domain
capabilities rather than creating business authority. That is a relevant
guardrail, not a selection of a learning feature, permitted learner data, or
expected learning outcome.

The representative flow is the existing manual SKU search in Operations
Android. [`ProductSearchStatus`](../../apps/operations-android/contexts/catalogcommercialpolicy/presentation/src/main/kotlin/com/nexa/mobile/operations/catalogcommercialpolicy/presentation/warehouse/ProductSearchUiState.kt)
already has typed states and the screen already references localized guidance
resources and explicit search, retry, context-change, and candidate-selection
actions. The [bounded experiment](../../experiments/mobile-autonomous-learning/README.md)
maps only synthetic enum values to those existing resource keys and actions.
It does not add text, execute a model, persist learning data, or change app
behavior.

Two alternatives were compared: a deterministic mapping to existing
resource/action identifiers, and Google's ML Kit GenAI Prompt API with Gemini
Nano. The Prompt API is beta, supports custom text/structured generation, and
needs app-specific prompt evaluation; it relies on AICore and a supported
device. Its documented minimum API level is 26, which is below the app's
`minSdk 29`, but SDK eligibility does not establish AICore availability. The
current API 37 emulator (`sdk_gphone64_arm64`) returned no package path for
`com.google.android.aicore`; the later API 29 promotion emulator also returned
no package path. Neither image executed the Prompt API. The published supported-device list does not
name the emulator. No Prompt API dependency or call was added. These details
and the comparison limits are recorded in the experiment README and its
[official ML Kit sources](https://developers.google.com/ml-kit/genai).

The local standard-library runner produced 20 structured outcomes: one for
each of the 17 current status enum values plus three synthetic fault-condition
cases (`ERRONEOUS_OUTPUT`, `INCOMPLETE_OUTPUT`, and `UNAVAILABLE`). All retain
the deterministic baseline and its server-authority invariants. Six Python
unit tests passed, including enum/resource coverage, allowed-action checks,
strict input shape, and fault fallback. Fault labels are not observed model
outputs, and the test does not establish model isolation or rollback.

| Original acceptance criterion | Evidence located | Assessment |
|---|---|---|
| Pregunta y límites documentados | The experiment bounds its input to an existing search-status enum, its resource keys and its existing next-step identifiers. Server confirmation remains authoritative; query, candidate, tenant/workspace and learning-history data are excluded. | Partial: the technical question and non-delegable authority boundary are explicit. Owner interpretation of “learning” and permitted adaptive data remains open. |
| Fuentes y alternativas comparadas | The README compares deterministic resource/action mapping with the official ML Kit Prompt API across utility, privacy, operational cost, compatibility, accessibility, and dependencies. It distinguishes documented capability from unmeasured quality and cost. | Comparison documented; utility, generated-output quality, performance, accessibility, and monetary cost are not measured. |
| Experimento mínimo reproducible | Python standard-library runner plus JSON fixture covers all 17 current status enum values and three fault labels. The test checks enum and existing-resource coverage and the runner's deterministic results. | Reproducible technical experiment executed: 20 cases; 6 tests passed. No model, app runtime, human participant, or learning outcome was tested. |
| Fallo y aislamiento | The three synthetic fault labels retain the same status-based baseline; unknown statuses, fault labels, and unexpected fields fail closed. No external provider is called. | Partial containment evidence for the standalone runner only; actual erroneous/incomplete model output and feature rollback were not exercised. |
| Conclusión y backlog | The research disposition defers runtime/model adoption and proposes an Owner clarification plus a bounded supported-device Prompt API evaluation as follow-up. | Technical recommendation and backlog refinement are recorded. They are not Owner acceptance, a Product decision, or spike closure. |

**Evidence status: PARTIAL TECHNICAL INVESTIGATION — NOT CLOSED.** The bounded
question, alternatives, reproducible synthetic experiment, failure labels,
recommendation, and follow-up are recorded. The meaning of learning and
permitted data remain open; model execution, human learning, Product
acceptance, and system acceptance are not evidenced. Do not close SPIKE-001
from this artifact alone.

## SPIKE-002 — Android/Flutter toolchain and fixture parity

### Observed toolchain matrix

Local versions were queried on 2026-10-08. Repository values are distinguished
from tools installed on this workstation.

Installed tool versions were captured with `java -version`, the configured
Flutter binary's `--version --machine`, the configured Dart binary's
`--version`, `android --version`,
`sdkmanager --list_installed`,
and `adb version`.
Repository plugin, SDK, and wrapper values were read from the evidence files in
the table.

| Area | Repository / installed value | Evidence |
|---|---|---|
| Android app SDK | `minSdk 29`, `compileSdk 37`, `targetSdk 37` | `apps/operations-android/app/build.gradle.kts` |
| Android build plugins | AGP `9.4.1`; Kotlin `2.4.20`; Compose BOM `2026.09.00` | `apps/operations-android/gradle/libs.versions.toml` |
| Gradle wrapper | `9.7.1` | `apps/operations-android/gradle/wrapper/gradle-wrapper.properties` |
| Java target and local runtime | Repository Java source/target compatibility `17`; local Temurin OpenJDK runtime `25.0.4.1` | `app/build.gradle.kts`; `java -version` |
| Android SDK platforms | Installed `android-29` and `android-37.0` | `sdkmanager --list_installed` |
| Android SDK build tools | Installed `29.0.3` and `36.0.0` | `sdkmanager --list_installed` |
| Android SDK tools | Android CLI `1.0.16500706`; SDK platform-tools / `adb` `37.0.1`; emulator `37.2.12`; Google APIs ARM64 system image `android-37.0`, revision `6.0.0` | `android --version`; `adb version`; `sdkmanager --list_installed` |
| Flutter | Stable `3.47.2`, framework revision `d3b14c876900e553bc736ca19295fc09e3853e8e` | configured Flutter binary `--version --machine` |
| Dart | Stable `3.13.2` (`macos_arm64`) | configured Dart binary `--version` |

The repository's Java target and the installed Java runtime are separate facts.
The static/JVM gate recorded later on 2026-10-08 passed; no SPIKE-002-specific
Android device test was run.

### Dart and Kotlin contract-parity artifacts

[`experiments/mobile-contract-parity`](../../experiments/mobile-contract-parity)
contains a pure Dart CLI fixture runner with no `pubspec.yaml`, Flutter
dependency, network call, credential, or external package. The synthetic
fixtures exercise the current Android `/api/v1/skus/resolve` projection and
typed outcome names: `Resolved`, `NotFound`, `Ambiguous`, `InvalidIdentifier`,
`NetworkUnavailable`, `ServiceUnavailable`, `PermissionDenied`,
`ContextInvalidated`, and `SessionExpired`. The projection fields are `skuId`,
`skuCode`, nullable `gtin`, `presentation`, nullable `unitOfMeasure`, `status`,
and `identifierType`.

The HTTP status mapping mirrors
[`NexaSkuIdentifierGateway.kt`](../../apps/operations-android/contexts/catalogcommercialpolicy/infrastructure/src/main/kotlin/com/nexa/mobile/operations/catalogcommercialpolicy/infrastructure/transport/NexaSkuIdentifierGateway.kt):
401 becomes `SessionExpired`; 403 with `ACCESS_CONTEXT_INVALID` becomes
`ContextInvalidated`; other 403 authorization failures become
`PermissionDenied`; and 409 currently falls through to `ServiceUnavailable`.
There is no typed `Conflict` outcome in that gateway today. The 409 fixture
preserves that observed client mapping rather than defining a new server or
Product rule.

The Kotlin
[`NexaSkuIdentifierGatewayTest`](../../apps/operations-android/contexts/catalogcommercialpolicy/infrastructure/src/test/kotlin/com/nexa/mobile/operations/catalogcommercialpolicy/infrastructure/transport/NexaSkuIdentifierGatewayTest.kt)
also reads the same
[`sku-resolver-contract.json`](../../experiments/mobile-contract-parity/fixtures/sku-resolver-contract.json)
fixture. The Kotlin counterpart is present in the current source tree and was
included in the resumed aggregate JVM gate, which passed 524 tests with zero
failures, errors, or skips across 114 XML suites in configured modules. This
does not establish API conformance, authentication, Product behavior, or
Android/Dart semantic parity.

Run the Dart experiment from the repository root with the installed SDK:

```sh
dart format --set-exit-if-changed experiments/mobile-contract-parity/bin/verify_contract_parity.dart
dart analyze experiments/mobile-contract-parity/bin/verify_contract_parity.dart
dart experiments/mobile-contract-parity/bin/verify_contract_parity.dart
```

The recorded Dart output was:

```text
Analyzing verify_contract_parity.dart...
No issues found!
PASS: 12 synthetic SKU resolver contract fixtures
```

That Dart result establishes only that the standalone parser matches these
synthetic expected outcomes. Separately, the Kotlin test passed in the
aggregate JVM gate as noted above. Neither result establishes API conformance,
authentication, Product behavior, or device behavior.

## SPIKE-003 — Android barcode decoder choice (supporting evidence)

### Scope and authority

The original Mobile Report SPIKE-003 delimits barcode, QR and GS1 product
identifiers, ambiguity, and manual fallback. This decoder-choice comparison is
supporting technical evidence for that spike, not its full acceptance or
Product acceptance. The current accepted Blueprint source is `main` at
`3574accc8962346a824a043ef8ea5564600c08b0`,
[`03-mobile/requirements/mobile-v1-catalog.md`](https://github.com/nexa-suite/blueprint/blob/3574accc8962346a824a043ef8ea5564600c08b0/03-mobile/requirements/mobile-v1-catalog.md)
(`MOB-US-011`). That story remains **PLANNED**, with research **PENDING**. It
requires authoritative server resolution before a stock action, rejection of
unknown or ambiguous codes, and manual product search when scanning is
unavailable. The research recommendation does not change those states.

The companion [identifier and local-recovery evidence pack](mobile-identifiers-and-local-recovery.md)
contains the format/GS1 analysis and decoder-only bitmap PoC, also under
SPIKE-003. Together, these files are research artifacts; they do not close the
original spike's full set of criteria.

The Android source currently pins CameraX `1.6.2` and bundled ML Kit barcode
scanning `17.3.0`. The adapter uses CameraX `MlKitAnalyzer`, a back camera,
latest-frame backpressure, and a one-candidate-per-bind guard. Its public
boundary emits a transient candidate or typed scanner state; it does not pass
camera frames or ML Kit types to feature code. See
[`ProductCodeScanner.kt`](../../apps/operations-android/core/device/src/main/kotlin/com/nexa/mobile/operations/core/device/scanner/ProductCodeScanner.kt)
and [`android-device-dependencies.md`](../android-device-dependencies.md).

### Comparison

| Concern | Existing CameraX + bundled ML Kit | Official ZXing core alternative |
|---|---|---|
| Utility | The current formats include common product and label codes: Code 128/39/93, Codabar, Data Matrix, EAN-8/13, ITF, PDF417, QR, UPC-A/E. The official ML Kit guide lists those formats and Aztec. CameraX's `MlKitAnalyzer` integrates detector results with `PreviewView` coordinates. | ZXing core supports multi-format 1D/2D decoding, including the current product-code families and additional formats such as Aztec and RSS. Its `Reader` API accepts a `BinaryBitmap`; adapting CameraX frames to that input and keeping orientation/cropping correct would be app-owned work. This integration-cost comparison is an inference from the APIs, not a benchmark. |
| Privacy | ML Kit documents barcode decoding as on-device and offline. The inspected adapter returns only a candidate value; the separate protected resolver receives the identifier after a scan. The adapter contains no image upload or image logging path. | ZXing core is an image decoder API, not a network resolver. Keeping frames local is possible if the app supplies them locally; that privacy property depends on the app integration. |
| Cost | Bundling makes the model available after app installation and avoids first-use model download. Google documents an approximately 2.4 MB app-size increase for the bundled barcode model. The repository's actual APK delta was not measured. Updating the bundled model requires updating the app. | The core project is Apache-2.0 licensed and does not require a downloaded ML model. No artifact/APK size comparison was measured. Choosing it would add a decoder dependency and require maintaining the CameraX-to-`BinaryBitmap` path. |
| Operability | The bundled model works without a model-fetch step. Existing code emits `PermissionRequired` or `Unavailable`, closes scanner resources on unbind, and prevents repeated delivery in one binding. | The official ZXing repository describes the project as maintenance mode and says its standalone Barcode Scanner app is unavailable and does not work on Android 14. This applies to that app; the core decoder remains available, but a production integration would be DIY. |
| Accessibility | The decoder does not supply an accessible screen. The app must label camera controls and status, expose logical traversal, announce changes, support camera denial, and retain manual entry/search. Existing manual-identification and permission-recovery behavior is documented in [`scanner-verification.md`](../scanner-verification.md). | The same app-level requirements apply. A different decoder does not provide labels, traversal, permission recovery, or a non-camera alternative. |
| Compatibility | ML Kit barcode scanning requires Android API 23 or higher; the Operations app's current `minSdk` is 29. CameraX supports API 21 and higher. The project's current compile/target SDK is 37. | ZXing core is a Java image-decoding library. This spike did not compile it against the current Android toolchain or validate camera-frame behavior on devices. The official standalone scanner app is not a suitable Android 14 compatibility proxy for the core library. |

No decoding accuracy, latency, power, memory, or APK-size comparison was run.
The recommendation uses documented capabilities and the repository's existing
integration, not an unobserved performance result.

### Recommendation and failure boundary

Keep the existing CameraX `1.6.2` plus bundled ML Kit `17.3.0` combination as
the Android scanner engineering baseline. Do not add ZXing or change dependency
versions in this spike. CameraX already supplies the camera lifecycle and
preview integration; ML Kit's analyzer handles its detector frame and
coordinate integration. ZXing's core could be reconsidered if a measured
device, format, size, license, or maintenance requirement favors it.

Scanning remains identification only. A candidate is not a Catalog fact and
does not create a receipt or picking action. The protected SKU resolver remains
the authority for resolution; an unrecognized, ambiguous, unavailable, or
out-of-scope result must stay explicit, and manual search remains available.
The Android gateway's typed mapping and parity fixtures are recorded under
SPIKE-002 above.

### Device evidence and follow-up

The Warehouse workstream has prepared a decoder-only Android instrumentation
PoC at
[`BarcodeDecodingPoCTest.kt`](../../apps/operations-android/core/device/src/androidTest/kotlin/com/nexa/mobile/operations/core/device/scanner/BarcodeDecodingPoCTest.kt).
It covers a synthetic QR candidate, synthetic EAN-13, and a blank image. The
assets are `core/device/src/androidTest/assets/fixtures/synthetic-qr.png` and
`core/device/src/androidTest/assets/fixtures/synthetic-ean13.png`. It calls the
ML Kit decoder with static bitmaps; it does not exercise CameraX, camera
hardware, or physical labels.

The proposed command was run on 2026-10-08:

```sh
cd apps/operations-android && ./gradlew :core:device:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nexa.mobile.operations.core.device.scanner.BarcodeDecodingPoCTest --dependency-verification strict --console=plain
```

The targeted command passed on the `Nexa_DDD_API37(AVD)` emulator (API 37),
with 2 tests, 0 failures, and 0 skipped. Later connected-instrumentation runs
also exercised this same decoder test class on API 29 and API 37 AVDs; each
report recorded 2 tests, 0 failures, and 0 skips. This covers synthetic bitmap
decoding and a blank image only; it is not CameraX, camera-hardware, or
physical-label validation.

Follow-up backlog:

1. Run the synthetic QR instrumentation test on an assigned device and record
   the exact task, device/API, output, and limitations.
2. Validate manual search, denied permission, camera unavailability, lifecycle
   unbind, and repeated-frame behavior with an accessibility service enabled.
3. Measure a clean APK size delta against the same baseline build if bundle
   size is a decision criterion.
4. Test representative physical package/label formats and lighting on approved
   devices before making any recognition or readiness claim.

## Primary sources

- [ML Kit Barcode Scanning for Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android) — supported formats, bundled/unbundled setup, model sizes and API level.
- [ML Kit model installation paths](https://developers.google.com/ml-kit/tips/installation-paths) — bundled availability/offline trade-off and model update behavior.
- [CameraX ML Kit Analyzer](https://developer.android.com/media/camera/camerax/mlkitanalyzer) — CameraX analyzer and coordinate-transform integration.
- [Official ZXing repository](https://github.com/zxing/zxing) — formats, project status and standalone scanner-app status.
- [ZXing `Reader` API](https://github.com/zxing/zxing/blob/master/core/src/main/java/com/google/zxing/Reader.java) — core decoder image input contract.
- [ZXing license](https://github.com/zxing/zxing/blob/master/LICENSE) — Apache License 2.0 terms.
- [Android Compose accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/composables) — semantics, descriptions and traversal structure.
