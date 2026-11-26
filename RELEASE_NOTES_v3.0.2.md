# Nexa Mobile v3.0.2

- In Buyer, fingerprint sign-in refreshes after you return from Android settings. Available credit stays visible if receivables fail to load. Both screens use the app's language for error messages.
- In Operations, document lists recognize generated and replaced documents, and CSV downloads accept UTF-8 content types.

## Android downloads

| App | Version | Minimum Android | APK |
| --- | --- | --- | --- |
| Nexa Buyer (Flutter) | 2.0.1 (build 6) | Android 7.0 (API 24) | `nexa-buyer-v2.0.1.apk` |
| Nexa Operations (Kotlin) | 3.0.2 (build 16) | Android 10 (API 29) | `nexa-operations-v3.0.2.apk` |

Both release-mode APKs use the existing Android validation certificate and
can update an earlier installation signed with the same certificate.

| APK | SHA-256 |
| --- | --- |
| `nexa-buyer-v2.0.1.apk` | `bf0ca0074620e9af1170b9fe2827605ce2c37233dcd615e977cdfe4659580371` |
| `nexa-operations-v3.0.2.apk` | `788ae639871c22430579973bd8b8470b79329d391f0d8d2d315d5684ed94f3b7` |

Use `shasum -a 256 <apk>` and `apksigner verify <apk>` to check a download.

## Validate with ICISA

1. Open the [API readiness page](https://nexa-api-69bj.onrender.com/actuator/health/readiness).
   Render Free may need to start after a period of inactivity.
2. Install the APK for your assigned test role, sign in and review the terms
   if prompted.
3. In Buyer, open Orders, available credit and receivables. Open an existing
   order to check delivery progress and summary downloads. Enable biometric
   access in Account and reopen the app.
4. In Operations, check the work available to the assigned role. Sales can
   inspect its assigned account documents. Warehouse can check transfer history.
   Logistics can open Dispatch, Deliveries and Loads.
5. To check offline reading, open a supported view online, then go offline and
   reopen it. Reconnect to confirm the current server state.

Buyer and Operations share the ICISA API. See the
[API v3.0.3 release](https://github.com/nexa-suite/api/releases/tag/v3.0.3)
for the corresponding cloud changes.
