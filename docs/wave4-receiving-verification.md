# Connected receiving verification

MOB-US-013/014 provide a protected inbound receipt form with current warehouse/zone lookup, confirmed manual Catalog selection, actual batch/expiry/decimal quantity, optional temperature reading, and immutable idempotent command identity. Product references restored from drafts require fresh confirmation. Physical stock facts appear only from a matching successful API response.

The application binds the feature metadata port to `core:local`: Android Keystore AES-GCM and AtomicFile in noBackupFilesDir. Records contain scope-bound draft and frozen intent metadata; they contain no credentials, permissions, confirmed authority or stock facts. Corrupt/unreadable records fail closed; unresolved key/payload replacements and stale clears are rejected. A reconstructed Pending intent becomes UnknownOutcome and requires explicit same-key replay. No background dispatch or generic queue exists.

Receiving entry uses the current `inventory.receive` permission independently of Catalog read capability. Selecting a product still requires current authorized Catalog identification. Context/session changes discard visible authority and fence late results. Durable metadata cannot authorize a command.

## Completed validation

Source `a601b5d`, 2026-09-30, JDK 17 and strict dependency verification:

- 144 all-module JVM tests: zero failures, errors or skips. New receiving tests cover stable replay, rapid repeat taps, context changes during persistence, decimal numeric transport, and same-key 401 replay.
- Architecture, ktlint, lint and debug assembly passed during integration.
- 40 all-module API 37 instrumentation tests: zero failures, errors or skips. Four new core-local tests use real Android Keystore and AtomicFile for scope isolation, corrupt ciphertext, exact intent reconstruction and stale clear handling.

Store-instance reconstruction is not process-death proof. Complete native receiving against the updated real API, API 29, process-death recovery and later Wave 4 integration remain pending. This record does not establish Product Acceptance, System Acceptance or Production Readiness.

## Native API integration

The opt-in `LiveCandidateIdentityIntegrationTest` passed on API 37 against the V108 API candidate (`a4dc692`) with real PostgreSQL and a least-privilege runtime role. The fixture Warehouse grant and active Zone were created through the API, and credentials were refreshed after the grant changed authorization version. The native flow verified login, protected Catalog confirmation, foreground authority renewal, Warehouse and Zone selection, a new receipt with quantity `1.25`, and matching server-confirmed Warehouse, batch and on-hand facts. One test ran with zero failures, errors or skips. No screenshots were used.

The test remains opt-in and takes fixture credentials only from private local Gradle properties. This evidence does not establish physical-device scanner behavior, API 29, or whole-process recovery. PR CI run `36759104722` also passed static and API 37 gates on checkpoint `8696c17`.
