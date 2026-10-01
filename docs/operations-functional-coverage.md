# Operations Mobile functional coverage

## Scope and evidence boundary

This implementation follows the authorized functional-skeleton scope of 54 Operations stories. Buyer Mobile is excluded. The client uses current API authority, scoped encrypted local records and explicit manual recovery of uncertain commands. No story status below constitutes Product Acceptance, System Acceptance or Production Readiness.

The work remains on `feature/w4-operations-mobile` and `feature/w4-api-capabilities`. No new pull request, merge, release or history rewrite is part of this delivery.

## Story coverage

| Story | Accepted catalog title | Implementation status |
| --- | --- | --- |
| MOB-US-001 | Continue authorized work safely after returning to Nexa | Implementation integrated; acceptance unverified |
| MOB-US-002 | Work in the intended company and business context | Implementation integrated; acceptance unverified |
| MOB-US-003 | See only work permitted for the person's role | Implementation integrated; acceptance unverified |
| MOB-US-004 | Review operational work at a glance | Implementation integrated; acceptance unverified. Owner accepted bounded prepared-Fulfillment coverage on 2026-10-01. Context, freshness and missing coverage are explicit; no enterprise totals. |
| MOB-US-005 | Notice critical operational exceptions | Accepted Owner decision; backend contract gap / explicit deferred mechanism |
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
| MOB-US-024 | Reliably identify a dispatch handoff | Mobile implementation integrated and compiled; backend purpose/assignment contract integration pending. Identity is separate from handoff acceptance and Buyer Receipt. |
| MOB-US-025 | Confirm goods left warehouse control | Implementation integrated; acceptance unverified |
| MOB-US-026 | See deliveries assigned to the driver | Implementation integrated; acceptance unverified |
| MOB-US-027 | Begin an assigned delivery | Implementation integrated; acceptance unverified |
| MOB-US-028 | Open directions to the authorized delivery destination | Implementation integrated; acceptance unverified |
| MOB-US-029 | Share a delivery location during an active delivery | Accepted Owner decision; backend contract gap / explicit deferred mechanism |
| MOB-US-030 | Contact the buyer during delivery | Accepted Owner decision; backend contract gap / explicit deferred mechanism |
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
| MOB-US-059 | Prepare grouped and multi-stop delivery loads | Accepted Owner decision; backend contract gap / explicit deferred mechanism |
| MOB-US-060 | Complete a carrier handoff with traceable responsibility | Accepted Owner decision; backend contract gap / explicit deferred mechanism |
| MOB-US-061 | Record temperature evidence at dispatch | Implementation integrated and compiled. Optional in-range manual Celsius against current Fulfillment/lot versions; durable explicit recovery. No stage requirement is inferred from SKU range. Excursion photo/exception/HOLD contracts remain absent; affected excursions reject before persistence. Acceptance unverified. |
| MOB-US-062 | Signal arrival for an active delivery | Implementation integrated; acceptance unverified |
| MOB-US-063 | Follow delivery instructions and authorized contact details | Accepted Owner decision; backend contract gap / explicit deferred mechanism |
| MOB-US-065 | Record a richer delivery incident | Implementation integrated; app compilation passed; acceptance unverified. Incident facts and separately reviewed AVAILABLE evidence append without changing Delivery outcome. |
| MOB-US-066 | Recover an active delivery through selective offline operation | Implementation integrated; acceptance unverified. Protected POD and active-incident draft restoration integrated; app compilation passed; acceptance unverified. |
| MOB-US-070 | View business documents linked to a request or order | Implementation integrated; acceptance unverified |
| MOB-US-072 | Work with a customer through an authorized field visit | Implementation integrated; acceptance unverified |
| MOB-US-073 | Use advanced warehouse automation evidence in controlled work | Accepted Owner decision; backend contract gap / explicit deferred mechanism. Manual authorized observations remain available; no selected automation provider or authoritative sensor policy. |

## Current validation

Affected Android application compilation passed after integrating count correction, confirmed-SKU stock lookup, discrepancy resolution, POD evidence, protected returned-picker retention, lot-substitution requests and dispatch-plan changes. Focused local tests cover immutable command replay, scoped metadata, evidence subject binding, stale versions and the new server mutations. Detailed final results will accompany the final artifact references.

No full native/device/design validation, screenshots, final-SHA CI, visual acceptance or production deployment is claimed. An attempted dispatch/network formatting check reported failures and was not classified as passing.

## Local artifacts and access

APK, candidate API executable and private existing local credentials are delivered outside Git. Warehouse access requires explicit grants under the accepted Membership + Warehouse policy and current Tenant/Workspace permissions. Configured credentials must not be described as login-verified until an actual login succeeds.

## Owner closure and direct correction — 2026-10-01

Owner accepts 004 bounded overview, exception severity/lifecycle, operational-hours Driver tracking, simple compatible loads, bilateral whole-load handoff, current scoped instructions and manual Celsius evidence with excursion photo/exception/preventive HOLD. Chat is exclusively Sales ↔ Buyer; the subsequent direct correction supersedes the earlier Driver/Buyer chat proposal. Driver-to-Buyer chat, WhatsApp/SMS, direct external 3PL Mobile access, automatic IoT and advanced route optimization are outside this scope.

Product closure does not establish missing API contracts. Raw-location retention duration remains a Privacy/Security/Data Governance decision. No location, chat, bilateral load acceptance, critical-instruction acknowledgment or thermal safeguard is claimed implemented without a real current API and attributable facts.
