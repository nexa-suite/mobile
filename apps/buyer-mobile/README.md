# Nexa Buyer Mobile

Flutter client for Buyer sign-in, server-selected access context, catalog,
orders, purchase requests, credit exposure, business documents, and read-only
delivery tracking. Access credentials remain in process memory. The API remains
authoritative for Buyer scope, catalog and commercial decisions, credit, order
outcomes, and delivery status. Delivery tracking shows Buyer-visible status and
event summaries; it does not provide driver assignment or live location.
Document bytes are verified and kept in memory for the detail view; the app does
not save or open them as files.

Run on an Android emulator against a local API listening on host port 8080:

```sh
flutter run --dart-define=NEXA_API_BASE_URL=http://10.0.2.2:8080
```

Run against an HTTPS API origin:

```sh
flutter run --dart-define=NEXA_API_BASE_URL=https://api.example.test
```

Build a development APK:

```sh
flutter build apk --debug --dart-define=NEXA_API_BASE_URL=https://api.example.test
```

Run static analysis and tests from this directory:

```sh
flutter analyze
flutter test
```

The local API integration tests are opt-in and require the configured local
Buyer fixture environment. Keep fixture credentials out of source files and
command-line arguments.
