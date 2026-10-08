# Mobile contract parity spike

This isolated Dart CLI experiment checks a synthetic fixture against the typed
outcomes and projection fields exposed by the current Android
`NexaSkuIdentifierGateway`. It uses only Dart SDK libraries and does not call
the API, read credentials, add a Flutter runtime, or define new Product rules.

The JSON fixture is also read by the current Kotlin
`MockWebServer` test in
`apps/operations-android/contexts/catalogcommercialpolicy/infrastructure/src/test/kotlin/com/nexa/mobile/operations/catalogcommercialpolicy/infrastructure/transport/NexaSkuIdentifierGatewayTest.kt`.
The Kotlin test source is present and uses this same fixture. It passed in the
resumed aggregate JVM gate on 2026-10-08: 524 tests, 0 failures, errors, or
skips across 114 XML suites in configured modules. That result confirms the
current Kotlin test run only; it does not establish Android/Dart semantic
parity, server API conformance, authentication, or Product acceptance.

Run these commands from the repository root:

```sh
dart format --set-exit-if-changed experiments/mobile-contract-parity/bin/verify_contract_parity.dart
dart analyze experiments/mobile-contract-parity/bin/verify_contract_parity.dart
dart experiments/mobile-contract-parity/bin/verify_contract_parity.dart
```

The executable performs explicit fixture checks and sets a non-zero process
exit code on failure. No `pubspec.yaml`, Flutter package, or third-party Dart
dependency is required.
