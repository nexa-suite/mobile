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
