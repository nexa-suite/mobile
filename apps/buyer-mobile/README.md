# Nexa Buyer

Buyer is our Flutter app for ICISA customers. It brings the catalog, purchase requests, orders, payment information, and delivery tracking into one app. When a driver shows the handoff QR, the buyer can scan it and confirm what arrived.

## Try the app

Use the [Buyer APK from Mobile v3.0.2](https://github.com/nexa-suite/mobile/releases/download/v3.0.2/nexa-buyer-v2.0.1.apk) or accept your invitation in Firebase App Distribution. Sign in with your Nexa Buyer account. For development, install Flutter and the SDK for your target platform, then run the following from this directory.

```sh
flutter pub get --enforce-lockfile
flutter run --dart-define=NEXA_API_BASE_URL=https://nexa-api-69bj.onrender.com
```

Use the commands below for the local API on port 8080.

| Target | API origin |
| --- | --- |
| Android emulator | `http://10.0.2.2:8080` |
| Android phone over USB | Run `scripts/reverse-local-api.sh "$ANDROID_SERIAL"`, then use `http://127.0.0.1:8080` |
| iPhone | Use a trusted HTTPS origin that the phone can reach |

For an Android debug APK, run `flutter build apk --debug` with the same `--dart-define=NEXA_API_BASE_URL=...` argument. The API's local Compose setup binds HTTP to the host loopback.

## Deliveries, offline access, and notifications

Open **Orders → Tracking** while online to save delivery summaries. When the connection drops, Buyer can display those summaries and keep a local receipt draft for the active delivery. The server confirms orders, payments, and receipts when the device is online.

Buyer listens for Firebase Cloud Messaging on configured Android builds. Tapping a supported notification opens the inbox, where the app loads the notice through the signed-in session.

Opt-in fingerprint or Face ID access renews the session using a rotating credential kept in secure storage. The app keeps access tokens in memory. Passwords are used at sign-in and kept out of local storage.

Debug delivery maps use OpenStreetMap tiles. Use synthetic coordinates while developing the map. A release build needs an approved `NEXA_MAP_TILE_URL_TEMPLATE` and `NEXA_MAP_ATTRIBUTION` to request tiles.

## Checks

```sh
python3 scripts/test-context-architecture.py
python3 scripts/verify-context-architecture.py
flutter analyze
flutter test
```

The optional API integration suite uses a disposable local Buyer account, since one case creates and submits a purchase request. It needs a local API, the account's active relationship, and catalog and address fixtures. The account requires `buyer.tracking.read`, `payment.read`, and `notification.read`.

Set `NEXA_DEV_BUYER_EMAIL`, `NEXA_DEV_BUYER_PASSWORD`, `NEXA_DEV_TENANT_SLUG`, and `NEXA_DEV_WORKSPACE_SLUG` in the environment or a `0600` file outside the repo at `~/.config/nexa/buyer-local-api.env`. Set `NEXA_BUYER_LOCAL_API_ENV_FILE` to use a different private file. `NEXA_LOCAL_SALES_ORDER_ID` is optional. The runner defaults to `http://localhost:8080`; use `NEXA_API_BASE_URL` to select a different HTTP loopback address.

```sh
python3 scripts/run-buyer-local-api-tests.py
```
