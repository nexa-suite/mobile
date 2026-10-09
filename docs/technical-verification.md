# Technical verification

The current DDD client refactor has a separate
[execution record](ddd-client-verification.md). This earlier checkpoint remains
historical evidence for its cited source and API fixture.

## Protected entry and manual identification checkpoint — 2026-09-30

Tested Android source: `9e6c212659fe91b24205180faa4968b999e0236d`.
The following evidence concerns MOB-US-001, MOB-US-002, MOB-US-003 and
MOB-US-012. It does not establish completion of the wider story scope.

The session coordinator closes protected state on foreground return and
revalidates authority with the API. Verified context comparison includes user,
Tenant, Workspace, Membership and current permissions. Capability routing
rejects unknown or lost permission, clears deep state and requires fresh
identification after a regrant. Manual confirmation requires the selected
catalog identifier and SKU to match current server detail.

| Executed gate | Result | Failures / errors / skips |
| --- | --- | --- |
| Architecture, ktlint, debug lint, strict dependencies and debug assembly | PASS | Not applicable |
| Auth JVM | 21 tests | 0 / 0 / 0 |
| Network JVM | 36 tests | 0 / 0 / 0 |
| Access JVM | 10 tests | 0 / 0 / 0 |
| Warehouse JVM | 15 tests | 0 / 0 / 0 |
| App JVM | 18 tests | 0 / 0 / 0 |
| API 37 auth instrumentation | 6 tests | 0 / 0 / 0 |
| API 37 ordinary app instrumentation | 28 tests | 0 / 0 / 0 |
| API 37 opt-in native API integration | 1 test | 0 / 0 / 0 |

The static and ordinary emulator commands are documented in
[verification](verification.md). Ordinary instrumentation explicitly excludes
`LiveCandidateIdentityIntegrationTest`; the live run explicitly selects that
class and obtains its arguments from private local configuration. Both used
JDK 17 and strict dependency verification. The emulator was `Nexa_API37`,
Android API 37. Results were retained before Gradle replaced the XML files.

The native integration used production identity, context and Catalog adapters
against an isolated local PostgreSQL fixture. It reached a server-confirmed
SKU, moved the activity out of and back into the foreground, then verified a
new authority epoch and removal of the previous confirmed SKU. It performed
no inventory mutation. Credentials and signing material remain outside source.

API provenance: released `v0.19.0`, main commit
`89ff511c30976cbb1734ab16ecd3fadac0651ed4`; the local integration server was
built at `68fb494c7e9121e51cc427d368b716386987c623`, whose API code and immutable
migrations match the released capability baseline. That candidate adds only
the accepted Warehouse-object policy documentation. Schema baseline ends at
Flyway V107. New Warehouse-object grants remain pending implementation and
are not covered by this identity/Catalog checkpoint.

Design reference remains frozen at
`05b42a142481e45f8a624d1b2a83e4fded88cb7d`; this checkpoint does not change visual
acceptance or the open palette decision.

Feature-branch CI must be reconciled against the pushed commit independently
of these local results. API 29 promotion, physical-device camera/permission
evidence, the remaining functional trains, Product/UX Acceptance, System
Acceptance and Production Readiness remain open.
