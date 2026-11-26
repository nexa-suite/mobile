<div align="center">

<img src="./apps/buyer-mobile/assets/brand/nexa-wordmark.png" alt="Nexa" width="220" />

# Nexa Mobile

[![Latest Git tag](https://img.shields.io/github/v/tag/nexa-suite/mobile?sort=semver&style=flat-square&label=latest%20release)](https://github.com/nexa-suite/mobile/releases)

[Android apps](https://github.com/nexa-suite/mobile/releases/latest) · [Test builds](https://appdistribution.firebase.google.com/testerapps) · [Project report](https://github.com/nexa-suite/mobile-report)

</div>

We built two apps for the same ICISA workflow. **Buyer** is the Flutter app for browsing products, sending purchase requests, and confirming deliveries. **Operations** is the Kotlin app Sales, Warehouse, and Logistics use to process the request, prepare the order, and deliver it.

## Install the Android apps

Get both APKs from [the latest release](https://github.com/nexa-suite/mobile/releases/latest). Invited testers can also install them through [Firebase App Distribution](https://appdistribution.firebase.google.com/testerapps).

| App | Minimum Android |
| --- | --- |
| Buyer · Flutter | Android 7.0 |
| Operations · Kotlin | Android 10 |

Both builds use the [ICISA API on Render](https://nexa-api-69bj.onrender.com/actuator/health/readiness). Check readiness before starting a demo if Render Free has been idle. Use your assigned Nexa account and review the terms at first sign-in. The release page lists the APK versions, checksums, and test steps.

Buyer also has an iOS target. The downloads above are Android packages.

## How the apps are organized

- [`apps/buyer-mobile`](apps/buyer-mobile) contains the Flutter app. Its [developer guide](apps/buyer-mobile/README.md) covers device setup and testing.
- [`apps/operations-android`](apps/operations-android) contains the Kotlin and Jetpack Compose app. `app` wires navigation and dependencies, `core` holds shared Android code, and `contexts` holds the screens and adapters for each area of work.

Both clients follow the layers in [Nexa Blueprint](https://github.com/nexa-suite/blueprint). Domain and application modules hold client models and use cases. Infrastructure connects them to the API or device services. Presentation handles screens and state. The API checks permissions and decides outcomes such as available stock, credit, and order status.

Buyer saves delivery summaries for viewing offline. Operations saves the Warehouse Picking list. Open the corresponding view while connected, then reopen it offline to see the saved copy. Reconnect to refresh or submit a change.

## Build and verify Operations Android

Install JDK 17, Android SDK `platforms;android-37.0`, and `build-tools;36.0.0`. Run these checks from `apps/operations-android`.

```sh
python3 scripts/verify-context-architecture.py
./gradlew verifyAndroidArchitecture ktlintCheck lintDebug testDebugUnitTest \
  :app:assembleDebug --dependency-verification strict --console=plain
```

To build in release mode, pass an approved HTTPS API origin.

```sh
./gradlew :app:assembleRelease -PnexaReleaseApiBaseUrl="$APPROVED_NEXA_API_ORIGIN" \
  --dependency-verification strict
```

The [Android workflow](.github/workflows/android-verify.yml) runs checks on API 37 and adds API 29 and release shrinking checks for promotions. The [academic APK workflow](.github/workflows/android-academic-apk.yml) builds a debug APK against Render for testing.

## Run and verify Buyer

From `apps/buyer-mobile`, install dependencies and use a reachable HTTPS API origin. The [Buyer guide](apps/buyer-mobile/README.md) also shows how to connect to a local API from an emulator or phone.

```sh
flutter pub get --enforce-lockfile
flutter run --dart-define=NEXA_API_BASE_URL=https://nexa-api-69bj.onrender.com
flutter analyze
flutter test
```

See the [Flutter workflow](.github/workflows/buyer-mobile-verify.yml), [changelog](CHANGELOG.md), and [older releases](https://github.com/nexa-suite/mobile/releases).
