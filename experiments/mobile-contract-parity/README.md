# Mobile contract parity spike

This isolated Dart CLI experiment checks a synthetic fixture against the typed
outcomes and projection fields exposed by the current Android
`NexaSkuIdentifierGateway`. It uses only Dart SDK libraries and does not call
the API, read credentials, add a Flutter runtime, or define new Product rules.

The JSON fixture is the shared parity input for a future Kotlin
`MockWebServer` test in
`apps/operations-android/contexts/catalogcommercialpolicy/infrastructure/src/test/kotlin/com/nexa/mobile/operations/catalogcommercialpolicy/infrastructure/transport/NexaSkuIdentifierGatewayTest.kt`.
The Kotlin counterpart has not been added or run by this spike.

Run these commands from the repository root:

```sh
/Users/diegosandoval284/Library/Developer/Flutter/flutter/bin/dart format --set-exit-if-changed experiments/mobile-contract-parity/bin/verify_contract_parity.dart
/Users/diegosandoval284/Library/Developer/Flutter/flutter/bin/dart analyze experiments/mobile-contract-parity/bin/verify_contract_parity.dart
/Users/diegosandoval284/Library/Developer/Flutter/flutter/bin/dart experiments/mobile-contract-parity/bin/verify_contract_parity.dart
```

The executable performs explicit fixture checks and sets a non-zero process
exit code on failure. No `pubspec.yaml`, Flutter package, or third-party Dart
dependency is required.
