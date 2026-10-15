# Nexa Buyer Mobile

Flutter client for Buyer sign-in, server-selected access context, catalog,
orders, purchase requests, credit exposure, receivables, wallet checkout and
payment history, notification inbox and preferences, bank-transfer reporting,
business documents, and delivery tracking, handoff and receipt.
Access credentials remain in process memory. The API remains authoritative for
Buyer scope, catalog and commercial decisions, credit, payment status, order
outcomes, and delivery status. A bank-transfer report is not a payment
confirmation or wallet balance. Delivery tracking shows Buyer-visible status
and event summaries. Active delivery details can request the server's latest
location; the Buyer app does not provide driver assignment or background
tracking.
Wallet checkout uses the Stripe PaymentSheet and scoped retry identity; the API
remains authoritative for payment status and wallet credit. Notifications
project the server inbox and accepted `IN_APP`/`EMAIL` preferences; this client
does not implement push delivery or store raw push tokens.
Document bytes are verified and kept in memory for the detail view; the app does
not save or open them as files. Payment retry metadata stores only scoped
idempotency identities and a reference fingerprint; it does not store the
bank reference or a payment credential.

The Buyer workspace includes submitted-request history and resumable server
drafts. A draft can contain multiple products, delivery preferences and a
server review before submission. Material changes show the original and
proposed terms before an explicit Buyer decision. Mutations retain current
versions and scoped command identities; an uncertain response requires a
server-state check. Navigation exposes only current server-granted options.
Catalog product, brand and category filters are applied by the API before
pagination; brand and category choices come from Buyer-visible catalog
taxonomy. Product family and variety remain separate metadata.
The visual composition uses Nexa typography, spacing, surfaces and status
tokens across phones and tablets. Construction and static analysis do not
establish visual or connected-flow acceptance.

Buyer delivery views poll only while their order or delivery route is visible.
The delivery detail can show an active delivery's latest server location on a
map, verify a handoff code, and submit a separate receipt or discrepancy. Code
verification is not a receipt. A disputed receipt can receive optional image or
PDF evidence after it is recorded; the API owns receipt scope, immutable
evidence metadata and scanning. The app keeps selected bytes in memory only and
persists scoped retry keys and payload fingerprints. The map has no default tile provider; configure
`NEXA_MAP_TILE_URL_TEMPLATE` with an approved HTTPS template and
`NEXA_MAP_ATTRIBUTION` to render map tiles. Without both values, no map tile
requests are made.

Run on an Android emulator against a local API listening on host port 8080:

```sh
flutter run --dart-define=NEXA_API_BASE_URL=http://10.0.2.2:8080
```

For a physical Android device, connect only that device through ADB reverse and
use its loopback origin:

```sh
scripts/reverse-local-api.sh "$ANDROID_SERIAL"
flutter run --dart-define=NEXA_API_BASE_URL=http://127.0.0.1:8080
```

For an iOS device, set `NEXA_API_BASE_URL` to a trusted HTTPS origin reachable
from the device, then pass it with `--dart-define=NEXA_API_BASE_URL="$NEXA_API_BASE_URL"`.
The local `modern-up` API is HTTP on host loopback only; it does not expose a
LAN HTTPS origin.

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
python3 scripts/test-context-architecture.py
python3 scripts/verify-context-architecture.py
flutter analyze
flutter test
```

The architecture check scans Dart source imports and exports. It keeps domain
and application code independent of platform implementations, presentation
independent of HTTP and persistence adapters, and cross-context references
limited to application contracts. The accompanying negative probes exercise
forbidden dependencies; this check does not establish Product Acceptance.

The four local API integration tests are opt-in. They require a ready local
API plus an active Buyer `PORTAL` membership for the selected Tenant and
Workspace. That membership needs `buyer.tracking.read`, `payment.read`, and
`notification.read`; its Buyer account relationship and local catalog/address
data must also be provisioned. One test creates and submits a purchase request,
so use a disposable local fixture.

The runner reads only `NEXA_DEV_BUYER_EMAIL`, `NEXA_DEV_BUYER_PASSWORD`,
`NEXA_DEV_TENANT_SLUG`, and `NEXA_DEV_WORKSPACE_SLUG` from the current process
environment or a private env file at
`~/.config/nexa/buyer-local-api.env`. The file must be owned by the current user,
mode `0600`, and outside Git worktrees. Its simple `KEY=VALUE` format is parsed
as data; it is never shell-sourced. `NEXA_LOCAL_SALES_ORDER_ID` is optional.
The runner prints no fixture values and passes no credentials as command-line
arguments. It defaults to `http://localhost:8080`; `NEXA_API_BASE_URL` may
override that only with an HTTP loopback origin. Set
`NEXA_BUYER_LOCAL_API_ENV_FILE` to use a different private file path.

Run the four opt-in tests when the local Tenant runtime and fixtures are ready:

```sh
python3 scripts/run-buyer-local-api-tests.py
```
