# Connected receiving verification

MOB-US-013/014 provide a protected inbound receipt form with current warehouse/zone lookup, confirmed manual Catalog selection, actual batch/expiry/decimal quantity, optional temperature reading, and immutable idempotent command identity. Product references restored from drafts require fresh confirmation. Physical stock facts appear only from a matching successful API response.

The receiving metadata port is implemented by BC-05 infrastructure storage at
`contexts/inventoryavailability/infrastructure/.../storage/receiving`. It uses
generic scoped-storage mechanics from `:core:local` plus Android Keystore
AES-GCM and `AtomicFile` in `noBackupFilesDir`. Records contain scope-bound
draft and frozen intent metadata; they contain no credentials, permissions,
confirmed authority or stock facts. Corrupt/unreadable records fail closed;
unresolved key/payload replacements and stale clears are rejected. A
reconstructed Pending intent becomes UnknownOutcome and requires explicit
same-key replay. No background dispatch or generic queue exists.

Receiving entry uses the current `inventory.receive` permission independently of Catalog read capability. Selecting a product still requires current authorized Catalog identification. Context/session changes discard visible authority and fence late results. Durable metadata cannot authorize a command.

## Current storage migration status — 2026-10-08

The receiving store, codecs, models and core tests now live in BC-05
infrastructure. `AndroidReceivingMetadataStoreTest` lives in that module's
`src/androidTest`; `ReceivingMetadataStoreCoreTest` lives in its `src/test`.
The current post-migration unit and device checks are pending. The historical
results below predate this move and do not verify the current storage paths.

Run the current checks from `apps/operations-android`:

```sh
./gradlew :contexts:inventoryavailability:infrastructure:testDebugUnitTest \
  --dependency-verification strict --console=plain
./gradlew :contexts:inventoryavailability:infrastructure:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.receiving.AndroidReceivingMetadataStoreTest \
  --dependency-verification strict --console=plain
```

## Historical validation checkpoint — 2026-09-30

These results were executed before the storage migration, against the former
`core:local` source layout. They are historical evidence only, not a pass for
the moved BC-05 files.

Source `a601b5d`, 2026-09-30, JDK 17 and strict dependency verification:

- 144 all-module JVM tests: zero failures, errors or skips. New receiving tests cover stable replay, rapid repeat taps, context changes during persistence, decimal numeric transport, and same-key 401 replay.
- Architecture, ktlint, lint and debug assembly passed during integration.
- 40 all-module API 37 instrumentation tests: zero failures, errors or skips. Four tests in the former `core:local` layout used real Android Keystore and AtomicFile for scope isolation, corrupt ciphertext, exact intent reconstruction and stale clear handling; this historical run predates their move into BC-05 infrastructure.

Store-instance reconstruction is not process-death proof. Complete native receiving against the updated real API, API 29, process-death recovery and later functional capabilities remain pending. This record does not establish Product Acceptance, System Acceptance or Production Readiness.

## Historical native API integration

The opt-in `LiveCandidateIdentityIntegrationTest` passed on API 37 against the V108 API candidate (`a4dc692`) with real PostgreSQL and a least-privilege runtime role. The fixture Warehouse grant and active Zone were created through the API, and credentials were refreshed after the grant changed authorization version. The native flow verified login, protected Catalog confirmation, foreground authority renewal, Warehouse and Zone selection, a new receipt with quantity `1.25`, and matching server-confirmed Warehouse, batch and on-hand facts. One test ran with zero failures, errors or skips. No screenshots were used.

The test remains opt-in and takes fixture credentials only from private local Gradle properties. This evidence does not establish physical-device scanner behavior, API 29, or whole-process recovery. PR CI run `36759104722` also passed static and API 37 gates on checkpoint `8696c17`.
