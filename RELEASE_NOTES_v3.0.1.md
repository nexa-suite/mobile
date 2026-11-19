# Nexa Mobile v3.0.1

This release includes the Nexa Buyer and Nexa Operations validation APKs for
ICISA. Operations displays the current mobile terms in a padded, scrollable
layout that accommodates larger text. The API records acceptance by account and
document version.

## Android downloads

| App | Version | Minimum Android | APK |
| --- | --- | --- | --- |
| Nexa Buyer (Flutter) | 2.0.0 (build 5) | Android 7.0 (API 24) | `nexa-buyer-v2.0.0.apk` |
| Nexa Operations (Kotlin) | 3.0.1 (build 15) | Android 10 (API 29) | `nexa-operations-v3.0.1.apk` |

The [GitHub release](https://github.com/nexa-suite/mobile/releases/tag/v3.0.1)
contains both APKs in release mode, signed with the Android validation
certificate. The repository tag and Android application versions are tracked
independently.

| APK | SHA-256 |
| --- | --- |
| `nexa-buyer-v2.0.0.apk` | `3f700980fab7c71c194049018c9b7ca3c6ee2a35f3da5b7dc73ece66a3cb6c25` |
| `nexa-operations-v3.0.1.apk` | `cbc0a9f53c7e78c2418ad37e1afa19138199d2831ef14b9f17b325bf96e9beca` |

Use `shasum -a 256 <apk>` to verify the file and `apksigner verify <apk>` to
verify the signature. An in-place Android update requires the existing
installation's signing certificate to match.

## Validate with ICISA

1. Install the APK for your test role and use the assigned ICISA account.
2. Review and accept the mobile terms if prompted. The published document is
   also available from the welcome screen.
3. In Buyer, open Catalog and Orders. In Operations, sign in as Logistics and
   open Dispatch, Deliveries and Loads.

On [Render API v3.0.1](https://github.com/nexa-suite/api/releases/tag/v3.0.1),
authenticated Buyer requests, orders and delivery history returned HTTP 200.
Credit exposure and the Operations warehouse, dispatch order and load lists
returned HTTP 503. Both APKs opened their authenticated navigation in an
Android 15 emulator. Full cloud checkout-to-receipt, live event-to-device
notification and physical phone verification are pending.

Use [API readiness](https://nexa-api-69bj.onrender.com/actuator/health/readiness)
before starting a session if Render Free has gone idle. The
[Swagger UI](https://nexa-api-69bj.onrender.com/swagger-ui/index.html) lists the
API routes and their request requirements.
