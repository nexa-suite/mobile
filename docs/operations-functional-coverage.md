# Operations Mobile functional coverage

## Scope and evidence boundary

This implementation follows the authorized functional-skeleton scope of 54 Operations stories. Buyer Mobile is excluded. The client uses current API authority, scoped encrypted local records and explicit manual recovery of uncertain commands. No story status below constitutes Product Acceptance, System Acceptance or Production Readiness.

Closure work continues on `feature/w4-mobile-contract-closure` and `feature/w4-backend-contract-closure`; previous implementation branches remain preserved. No new pull request, merge, release or history rewrite is part of this delivery.

## Story coverage

| Story | Accepted catalog title | Implementation status |
| --- | --- | --- |
| MOB-US-001 | Continue authorized work safely after returning to Nexa | Implementation integrated; acceptance unverified |
| MOB-US-002 | Work in the intended company and business context | Implementation integrated; acceptance unverified |
| MOB-US-003 | See only work permitted for the person's role | Implementation integrated; acceptance unverified |
| MOB-US-004 | Review operational work at a glance | Implementation integrated; acceptance unverified. Owner accepted bounded prepared-Fulfillment coverage on 2026-10-01. Context, freshness and missing coverage are explicit; no enterprise totals. |
| MOB-US-005 | Notice critical operational exceptions | Typed incidents, server-derived severity, immutable responsibility transitions and Driver WARNING resolution/closure implemented in source. BLOCKING/CRITICAL cannot use the Driver WARNING completion path. Explicit BOM coordination was approved separately from underlying domain authority; explicit role, current authorized assignees, coordination history and Android screen integrated. Focused BOM HTTP closure passed. |
| MOB-US-006 | Find a customer and buyer relationship | Implementation integrated; acceptance unverified |
| MOB-US-007 | Review products, prices and availability | Implementation integrated; acceptance unverified |
| MOB-US-008 | Prepare a customer request | Implementation integrated; acceptance unverified |
| MOB-US-009 | Submit a purchase request from field work | Implementation integrated; acceptance unverified |
| MOB-US-010 | Follow customer commitments and credit | Implementation integrated; acceptance unverified |
| MOB-US-011 | Identify a product from a package or label code | Implementation integrated; acceptance unverified |
| MOB-US-012 | Find a product manually when scanning is unavailable | Implementation integrated; acceptance unverified |
| MOB-US-013 | Record stock that has just arrived | Implementation integrated; acceptance unverified |
| MOB-US-014 | Record the actual lot, expiry and quantity | Implementation integrated; acceptance unverified |
| MOB-US-015 | Check current lot and stock condition before physical work | Implementation integrated; acceptance unverified |
| MOB-US-016 | Pick the correct lot and quantity for prepared work | Implementation integrated; acceptance unverified |
| MOB-US-017 | Report a physical discrepancy or authorized stock disposition | Implementation integrated; acceptance unverified |
| MOB-US-018 | Move stock between warehouse locations | Implementation integrated; acceptance unverified |
| MOB-US-019 | Record temperature evidence for relevant stock | Implementation integrated; acceptance unverified |
| MOB-US-020 | See deliveries ready for dispatch preparation | Implementation integrated; acceptance unverified |
| MOB-US-021 | Assign a driver to a ready delivery | Implementation integrated; acceptance unverified |
| MOB-US-022 | Check outgoing goods against the prepared delivery | Implementation integrated; acceptance unverified |
| MOB-US-023 | Preserve warehouse-to-driver handoff evidence | Implementation integrated; acceptance unverified |
| MOB-US-024 | Reliably identify a dispatch handoff | Mobile and API implementation integrated; compilation and generated OpenAPI contract passed. Explicit DISPATCH_HANDOFF binds current assignment, Driver and Delivery version; validation returns no secret. Identity is separate from handoff acceptance and Buyer Receipt; acceptance unverified. |
| MOB-US-025 | Confirm goods left warehouse control | Implementation integrated; acceptance unverified |
| MOB-US-026 | See deliveries assigned to the driver | Implementation integrated; acceptance unverified |
| MOB-US-027 | Begin an assigned delivery | Implementation integrated; acceptance unverified |
| MOB-US-028 | Open directions to the authorized delivery destination | Implementation integrated; acceptance unverified |
| MOB-US-029 | Share a delivery location during an active delivery | Backend active-workday tracking and 24-hour maximum coordinate TTL implemented with focused PostgreSQL integration evidence. Android foreground capture, permission-loss stop, close-before-END, encrypted command recovery and GET-before-capture implemented in source; focused gateway/ViewModel checks passed; physical-device tracking remains unverified. Buyer access is restricted to its active dispatched Delivery. |
| MOB-US-030 | Contact the buyer during delivery | Driver-to-Buyer communication excluded/deferred by the direct Owner correction. Sales/Buyer contextual chat is a separate authorized capability; it must not be exposed to Driver. |
| MOB-US-031 | Record the delivery attempt outcome | Implementation integrated; acceptance unverified |
| MOB-US-032 | Record a partial or rejected delivery and what remains | Implementation integrated; acceptance unverified |
| MOB-US-033 | Preserve proof of delivery | Implementation integrated; acceptance unverified. Proof creation, private evidence staging, upload/scan and exact-subject attachment. Required-proof policy projection remains unavailable. |
| MOB-US-034 | Present a bounded delivery handoff code | Implementation integrated; app compilation passed. Issue/display uses current logistics:write contract and exact active attempt; secret remains process-memory only, replay without secret is explicit. Approved fallback coverage remains a contract gap; acceptance unverified. |
| MOB-US-035 | Continue delivery evidence after connection loss | Implementation integrated; acceptance unverified. Private returned-picker retention and encrypted active-incident media staging integrated; app compilation passed; acceptance unverified. |
| MOB-US-050 | Handle an inbound receiving discrepancy with evidence | Implementation integrated; app and API compilation passed. Immutable observation and AVAILABLE evidence support review readiness, not approval or stock correction; acceptance unverified. |
| MOB-US-051 | Place stock on hold or quarantine and resolve it | Implementation integrated; acceptance unverified |
| MOB-US-052 | Confirm destination receipt for an internal warehouse transfer | Implementation integrated; acceptance unverified |
| MOB-US-053 | Perform a cycle count and request a stock correction | Implementation integrated; acceptance unverified |
| MOB-US-054 | Apply a reasoned lot substitution when FEFO cannot fulfill work | Implementation integrated; acceptance unverified |
| MOB-US-055 | Use richer product, package and storage identity information | Implementation integrated; acceptance unverified |
| MOB-US-056 | Prepare a batch warehouse operation | Implementation integrated; acceptance unverified |
| MOB-US-057 | Resolve a dispatch discrepancy before handoff | Implementation integrated; acceptance unverified |
| MOB-US-058 | Reassign a driver or reschedule dispatch safely | Implementation integrated; acceptance unverified |
| MOB-US-059 | Prepare grouped and multi-stop delivery loads | Backend versioned grouped-load planning and explicit Dispatch compatibility attestation implemented in source. Server validates Warehouse scope, readiness, thermal ranges and authoritative windows. Owner approved explicit operational planning only when a window is absent; append-only window planning and grouped-load client controls integrated. Final load HTTP checks pending. |
| MOB-US-060 | Complete a carrier handoff with traceable responsibility | Backend independent Dispatch confirmation and assigned-Driver whole-load acceptance implemented in source; responsibility transfer requires both facts and current readiness. Planned Delivery reuse preserves physical-handover stock authority. Whole-load Android confirmation and assigned-Driver acceptance integrated; final load HTTP checks pending. External 3PL direct access deferred. |
| MOB-US-061 | Record temperature evidence at dispatch | Preparation excursion source now includes AVAILABLE exact-Warehouse photo, expected lot version and preventive BC-05 HOLD. In-transit evidence/disposition source adds a distinct BC-06 execution HOLD without recreating inventory; transit client and explicit-capability disposition routes integrated. No stage requirement is inferred from SKU range. Focused thermal checks passed; acceptance remains unverified. |
| MOB-US-062 | Signal arrival for an active delivery | Implementation integrated; acceptance unverified |
| MOB-US-063 | Follow delivery instructions and authorized contact details | Current Driver instruction read/critical version ACK implemented. Customer/Buyer authoring before readiness, Sales source provenance, immutable projection into Delivery and encrypted Customer command recovery implemented in source; Dispatch authoring client source integrated. Contact remains scoped authorized destination; Driver chat excluded. Focused backend and client closure checks passed; acceptance remains unverified. |
| MOB-US-065 | Record a richer delivery incident | Implementation integrated; app compilation passed; acceptance unverified. Incident facts and separately reviewed AVAILABLE evidence append without changing Delivery outcome. |
| MOB-US-066 | Recover an active delivery through selective offline operation | Implementation integrated; acceptance unverified. Protected POD and active-incident draft restoration integrated; app compilation passed; acceptance unverified. |
| MOB-US-070 | View business documents linked to a request or order | Implementation integrated; acceptance unverified |
| MOB-US-072 | Work with a customer through an authorized field visit | Implementation integrated; acceptance unverified |
| MOB-US-073 | Use advanced warehouse automation evidence in controlled work | Manual receiving Celsius and AVAILABLE exact-Warehouse photo implemented in source. Excursion creates preventive inventory HOLD and thermal exception atomically; focused receiving client checks passed. Automatic IoT remains deferred. Owner approved separate BC-06 in-transit execution HOLD; source and Android integration implemented. |

## Current validation

Affected Android application compilation passed after integrating count correction, confirmed-SKU stock lookup, discrepancy resolution, POD evidence, protected returned-picker retention, lot-substitution requests and dispatch-plan changes. Focused local tests cover immutable command replay, scoped metadata, evidence subject binding, stale versions and the new server mutations. Final Android architecture check and debug APK assembly passed. Focused dispatch identity/temperature client tests passed. Integrated OpenApiContractIT passed with PostgreSQL migrations, including V126; API024 focused service tests passed 4/4 in its implementation worktree. Delivery thermal guard unit tests passed 2/2 and receiving excursion integration test passed 1/1. These are bounded technical checks, not full story acceptance.

No full native/device/design validation, screenshots, final-SHA CI, visual acceptance or production deployment is claimed. Earlier dispatch/network formatting failures were corrected; the final checks below supersede that attempt.

## Local artifacts and access

APK, candidate API executable and private existing local credentials are delivered outside Git. Warehouse access requires explicit grants under the accepted Membership + Warehouse policy and current Tenant/Workspace permissions. Four configured PLATFORM accounts and Buyer PORTAL authenticated through the local API with HTTP 200. Private credentials remain outside Git.

## Owner closure and direct correction — 2026-10-01

Owner accepts 004 bounded overview, exception severity/lifecycle, operational-hours Driver tracking, simple compatible loads, bilateral whole-load handoff, current scoped instructions and manual Celsius evidence with excursion photo/exception/preventive HOLD. Chat is exclusively Sales ↔ Buyer; the subsequent direct correction supersedes the earlier Driver/Buyer chat proposal. Driver-to-Buyer chat, WhatsApp/SMS, direct external 3PL Mobile access, automatic IoT and advanced route optimization are outside this scope.

Product closure does not establish missing API contracts. Owner subsequently approved raw-coordinate TTL of at most 24 hours from capture, independently of active-workday tracking. Operational instruction read/ACK and the new-attempt critical gate now have concrete BC-06 contracts and focused backend/client tests. The source increments described above supersede earlier contract-gap notes. Later closure evidence is recorded below. Sales/Buyer chat authority and underlying domain permissions remain separate; Driver chat is excluded.

## Operational instruction increment

MOB-US-063 focused checks passed: 31 Delivery tests (including six new instruction ViewModel cases), four instruction gateway tests, strict application Kotlin compilation, three API domain tests and one PostgreSQL workflow integration test applying V130. Debug APK assembly passed for this increment. These checks do not establish full US-063 coverage, physical-device execution or Product/System Acceptance.

## Final local technical closure — 2026-10-01

BOM coordination, missing-window Dispatch planning, compatible whole-load handoff,
Customer instruction provenance, receiving/preparation evidence and BC-06 transit
execution HOLD/disposition are connected to the native application. Commands retain
current server authority, explicit Warehouse grants, versions, encrypted exact replay
and manual recovery; coordination never grants underlying domain authority.

Observed Android checks: `ktlintCheck`, `verifyAndroidArchitecture`,
`:app:assembleDebug`, `:app:lintDebug` passed. Selected JVM checks passed 87 tests
with zero failures/errors across network (29), Dispatch (10), Delivery (10),
Warehouse (10) and app metadata/commercial workflows (28). After the final exact-body
formatting correction, the three outgoing-goods cases passed again. Compose
`MainActivitySmokeTest` passed 1/1 on Nexa_API37 using connected instrumentation,
without screenshots. This proves graph resolution and the access screen, not an
end-to-end business scenario. Android requests fine and coarse location together;
only a granted precise location permits Driver workday capture.

Observed API checks: selected BOM, Driver incident/instruction and compatible-load
HTTP tests plus modern PostgreSQL migrations passed 11/11. Architecture (19),
persistence ownership (5) and RLS classification (1) passed; Maven packaging passed.
The least-privilege runtime validator passed 5 tests after registering the narrow,
fixed-search-path coordinate retention function. Docker runtime health is UP;
four PLATFORM logins and Buyer PORTAL login returned HTTP 200 using existing local
configuration. Raw coordinates retain the approved maximum 24-hour TTL.

The default debug build uses `http://10.0.2.2:8080/` for the Android emulator.
The delivered APK is assembled with `-PnexaDebugApiBaseUrl=http://127.0.0.1:8080/`
and uses `adb reverse tcp:8080 tcp:8080` for USB devices or an emulator. Android Studio
Rabbit 1 2026.2.1 reports READY through Android CLI; GUI Sync is not claimed.
Source branches remain local and unmerged. Full-suite/final-SHA CI, physical-device
tracking, provider readiness and Product/System Acceptance remain unverified.
Automatic IoT, advanced routing and Driver chat remain outside the approved V1.
