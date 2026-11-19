# Nexa Mobile v3.0.0

Nexa Mobile v3.0.0 includes two signed Android APKs for ICISA. Both connect to
Nexa API v3.0.0 on Render and use the Nexa ICISA Firebase project.

## What changed

- Buyer Mobile adds English and Spanish sign-in, terms and workspace navigation.
  The delivery screen places receipt confirmation and retry next to the handoff QR.
- Operations Android updates access and delivery labels and refreshes biometric
  availability when the app resumes. The Logistics profile opens Dispatch,
  Deliveries and Loads. Loads uses cards to show orders and delivery windows.
- Both clients show the current mobile terms at sign-in. The API records
  acceptance for each account and document version.

## APKs

| App | File | App version | Minimum Android |
| --- | --- | --- | --- |
| Buyer Mobile (Flutter) | `nexa-buyer-v3.0.0.apk` | `1.2.1` (build 4) | Android 7.0 (API 24) |
| Operations Android (Kotlin) | `nexa-operations-v3.0.0.apk` | `1.3.1` (build 13) | Android 10 (API 29) |

The APKs were built in release mode and signed with the Android validation key.
The repository and the two Android apps track their versions independently.
The application source in the tag matches commit
`6d1944cc56438a94438e832350c34add84cdd7bf`. Both files are available in the
[GitHub release](https://github.com/nexa-suite/mobile/releases/tag/v3.0.0) and
to invited testers in Firebase App Distribution.

| Package | SHA-256 |
| --- | --- |
| `com.nexa.nexa_buyer_mobile` | `5f8df54e036627ad879d4d6efaeff30a40a3bdfa16a65293c781ca34b76505e7` |
| `com.nexa.mobile.operations` | `4eca01dee832e27a503247a35aeda16bc5e31f46b730475de20bc378b0ba56f6` |

Run `shasum -a 256 <apk>` to check a download and `apksigner verify <apk>` to
verify its signature.

## Install and validate

1. Download the APK for your role or open your Firebase App Distribution invitation.
2. Install it on a supported Android phone or emulator. Android requires a matching
   signing certificate for an in-place update. A separate test device or emulator
   preserves data from an installation signed with another certificate.
3. Sign in with your assigned ICISA Buyer or Logistics test account. Review and
   accept the terms if prompted, then open the workspace.
4. In Buyer, open the workspace and notification inbox. In Operations, open
   Dispatch, Deliveries or Loads with a Logistics account. The app shows actions
   according to your role and the deployed API capabilities.

After a cold start on Render Free, sign-in and terms may need a retry once the
API reports `UP`. [API readiness](https://nexa-api-69bj.onrender.com/actuator/health/readiness) ·
[API documentation](https://nexa-api-69bj.onrender.com/swagger-ui/index.html)

## Verification and scope

| Environment | Result |
| --- | --- |
| Public Render API v3.0.0 | Readiness, OpenAPI version, mobile terms and document digest passed. The protected notification endpoint returned `401` without a session. |
| Android phone emulator and Render | Both signed APKs installed and signed in. The API stored exactly one acceptance of mobile terms version 1 for each of the two test accounts. Logistics saw Dispatch, Deliveries and Loads. Both apps loaded the terms again after reconnection. |
| Local ICISA fixture | The connected API run passed through Buyer, Sales, Warehouse, Driver and Buyer receipt. Separate device checks showed a completed delivery and read-only cached delivery and picking lists. |
| Firebase Cloud Messaging | FCM returned HTTP `200` in validation-only mode. Live event-to-device delivery still needs the cloud scope-reader change and the ICISA Workspace attestation. [API #157](https://github.com/nexa-suite/api/pull/157) |

The deployed API v3.0.0 supports the configured reads, terms acceptance and
notification interfaces. Purchase-to-delivery writes still need cloud runtime
support. Physical-device testing and iOS distribution remain open. [API v3.0.0](https://github.com/nexa-suite/api/releases/tag/v3.0.0)
