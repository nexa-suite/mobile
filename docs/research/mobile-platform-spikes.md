# Mobile platform spikes: scanner baseline and contract parity

**Observed:** 2026-10-08. These are engineering research artifacts and a
fixture-only Dart CLI PoC. They add no runtime Buyer or Operations capability,
API route, authorization rule, or business decision.

## SPIKE-001 — Android barcode decoder choice

### Scope and authority

The live Nexa Blueprint checkout was `main` at
`9034f4857b45224832f61af3e088f8a29143b187`,
`03-mobile/requirements/mobile-v1-catalog.md` (`MOB-US-011`). The story is
**PLANNED**, with research **PENDING**. It requires an authoritative
server resolution before a stock action, rejects unknown or ambiguous codes,
and retains manual product search if scanning is unavailable. The spike's
engineering recommendation does not change that Product state or claim
Product acceptance.

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
The Android gateway's typed mapping is recorded in SPIKE-002 below.

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

It passed on the `Nexa_DDD_API37(AVD)` emulator (API 37), with 2 tests,
0 failures, and 0 skipped. This covers synthetic bitmap decoding and a blank
image only; it is not CameraX, camera-hardware, or physical-label validation.

Follow-up backlog:

1. Run the synthetic QR instrumentation test on an assigned device and record
   the exact task, device/API, output, and limitations.
2. Validate manual search, denied permission, camera unavailability, lifecycle
   unbind, and repeated-frame behavior with an accessibility service enabled.
3. Measure a clean APK size delta against the same baseline build if bundle
   size is a decision criterion.
4. Test representative physical package/label formats and lighting on approved
   devices before making any recognition or readiness claim.

## SPIKE-002 — Android/Flutter toolchain and fixture parity

### Observed toolchain matrix

Local versions were queried on 2026-10-08. Repository values are distinguished
from tools installed on this workstation.

Installed tool versions were captured with `java -version`, the configured
Flutter binary's `--version --machine`, the configured Dart binary's
`--version`, `/Users/diegosandoval284/Library/Android/sdk/cmdline-tools/latest/bin/android --version`,
`/Users/diegosandoval284/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager --list_installed`,
and `/Users/diegosandoval284/Library/Android/sdk/platform-tools/adb version`.
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
No Gradle task or Android device test was run for this spike.

### Dart contract-parity PoC

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

Run from the repository root with the installed SDK:

```sh
/Users/diegosandoval284/Library/Developer/Flutter/flutter/bin/dart format --set-exit-if-changed experiments/mobile-contract-parity/bin/verify_contract_parity.dart
/Users/diegosandoval284/Library/Developer/Flutter/flutter/bin/dart analyze experiments/mobile-contract-parity/bin/verify_contract_parity.dart
/Users/diegosandoval284/Library/Developer/Flutter/flutter/bin/dart experiments/mobile-contract-parity/bin/verify_contract_parity.dart
```

Observed output:

```text
Analyzing verify_contract_parity.dart...
No issues found!
PASS: 12 synthetic SKU resolver contract fixtures
```

The Kotlin `MockWebServer` counterpart using the same JSON file remains a
parent integration task and has not been run. The Dart result establishes only
that the standalone parser matches these synthetic expected outcomes; it does
not establish Android parity, API conformance, authentication, Product
behavior, or device behavior.

## Primary sources

- [ML Kit Barcode Scanning for Android](https://developers.google.com/ml-kit/vision/barcode-scanning/android) — supported formats, bundled/unbundled setup, model sizes and API level.
- [ML Kit model installation paths](https://developers.google.com/ml-kit/tips/installation-paths) — bundled availability/offline trade-off and model update behavior.
- [CameraX ML Kit Analyzer](https://developer.android.com/media/camera/camerax/mlkitanalyzer) — CameraX analyzer and coordinate-transform integration.
- [Official ZXing repository](https://github.com/zxing/zxing) — formats, project status and standalone scanner-app status.
- [ZXing `Reader` API](https://github.com/zxing/zxing/blob/master/core/src/main/java/com/google/zxing/Reader.java) — core decoder image input contract.
- [ZXing license](https://github.com/zxing/zxing/blob/master/LICENSE) — Apache License 2.0 terms.
- [Android Compose accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/composables) — semantics, descriptions and traversal structure.
