# Operations client DDD alignment

The Operations Android client projects accepted Nexa capabilities into native
workflows. It does not create Bounded Contexts, server aggregates, business
authority or a second Product model. The eleven canonical contexts and their
business ownership remain in Nexa API and Blueprint.

## Authority and pinned sources

The accepted architecture source is Blueprint commit
`3574accc8962346a824a043ef8ea5564600c08b0`:
[ADR-0020: Mobile Client Layering](https://github.com/nexa-suite/blueprint/blob/3574accc8962346a824a043ef8ea5564600c08b0/01-shared/architecture/decisions/adr/adr-0020-mobile-client-layering.md),
[Mobile projection](https://github.com/nexa-suite/blueprint/blob/3574accc8962346a824a043ef8ea5564600c08b0/01-shared/domain/strategic-ddd/mobile-projection.md),
[application architecture](https://github.com/nexa-suite/blueprint/blob/3574accc8962346a824a043ef8ea5564600c08b0/03-mobile/architecture/technical/application-architecture.md)
and [accepted current decisions](https://github.com/nexa-suite/blueprint/blob/3574accc8962346a824a043ef8ea5564600c08b0/01-shared/product/current-decisions.md).
Blueprint is the canonical Product, Domain and C4 authority.

The API implementation map and contract reference is pinned to
`origin/main` commit `78a3060cb56796520fcf8e9be36c63f88b4f9f51`:
[canonical bounded-context map](https://github.com/nexa-suite/api/blob/78a3060cb56796520fcf8e9be36c63f88b4f9f51/docs/architecture/bounded-context-module-map.md)
and [logical layering](https://github.com/nexa-suite/api/blob/78a3060cb56796520fcf8e9be36c63f88b4f9f51/docs/architecture/logical-layering.md).
These API sources identify current implementation/contract boundaries; they do
not supersede Blueprint's DDD authority. The current Mobile Report is pinned at
`55f959441fb4d5422519db31c380631e95ed72e3`; its [tactical DDD chapter](https://github.com/nexa-suite/mobile-report/blob/55f959441fb4d5422519db31c380631e95ed72e3/report/02-requirements-and-software-solution-design/2.6-tactical-level-domain-driven-design/2.6-tactical-level-domain-driven-design.md)
is academic evidence, not Product or Domain authority.

Eric Evans's [DDD Reference](https://www.domainlanguage.com/wp-content/uploads/2016/05/DDD_Reference_2015-03.pdf)
defines Bounded Context, layered architecture and aggregates. Vaughn Vernon's
[Effective Aggregate Design](https://kalele.io/effective-aggregate-design/)
explains aggregates as carefully chosen consistency boundaries. These are
primary pattern references; Blueprint determines the accepted Nexa application.

## Canonical context roots and current code

The project has 11 roots under
`apps/operations-android/contexts/<canonical-root>`. It currently has 31
runtime modules across four possible client layers. Module counts below are
AS-IS source structure, not business-ownership claims. A layer exists only when
the client has code in it.

| Canonical context | Root | Runtime layers present |
| --- | --- | --- |
| BC-01 Tenant & Access Governance | `tenantaccessgovernance` | `domain`, `application`, `infrastructure`, `presentation` |
| BC-02 Customer & Buyer Relationships | `customerbuyerrelationships` | `domain`, `application`, `infrastructure`, `presentation` |
| BC-03 Catalog & Commercial Policy | `catalogcommercialpolicy` | `domain`, `application`, `infrastructure`, `presentation` |
| BC-04 Sales Commitment | `salescommitment` | `domain`, `application`, `infrastructure`, `presentation` |
| BC-05 Inventory Availability | `inventoryavailability` | `domain`, `application`, `infrastructure`, `presentation` |
| BC-06 Fulfillment & Delivery | `fulfillmentdelivery` | `domain`, `application`, `infrastructure`, `presentation` |
| BC-07 Credit & Receivables | `creditreceivables` | `domain`, `application`, `infrastructure` |
| BC-08 Payments | `payments` | No runtime module |
| BC-09 Business Documents | `businessdocuments` | `domain`, `application`, `infrastructure`, `presentation` |
| BC-10 Notifications | `notifications` | No runtime module |
| BC-11 Business Traceability | `businesstraceability` | No runtime module |

The seven four-layer roots contribute 28 modules; BC-07 contributes three.
BC-08, BC-10 and BC-11 have context README files but no runtime Gradle modules
or client capability. Their inclusion in the directory map does not imply
implementation. `:app` is the composition root, and `:core:*` modules are
technical foundations; neither is an extra Bounded Context.

## Client layers

ADR-0020 describes the conceptual flow as Presentation/UI, Application/Use Cases,
Data/Repositories, and Data Sources & Platform Adapters. The Android code
represents those responsibilities in four module layers: `presentation`,
`application`, `infrastructure`, and a `domain` package/module for client-side
values. `infrastructure` contains the repository, remote/local data-source and
platform-adapter responsibilities. The client `domain` layer is not a second
server Domain Model.

| Client layer | Implemented responsibility | Boundary |
| --- | --- | --- |
| `domain` | Immutable server projections and local value constraints used by a workflow | No server aggregates, authorization, inventory or Product decisions |
| `application` | Narrow ports, typed client outcomes and selective workflow coordination | No Android, Compose, Hilt, HTTP or persistence framework |
| `infrastructure` | HTTP/serialization adapters, protected local metadata and platform adapters | Translates to and from client contracts; does not own server decisions |
| `presentation` | Compose screens, UI state, ViewModels and user actions | Uses its own context contracts and foundations; no direct HTTP or secure storage |
| `app` composition | Hilt bindings/factories, Navigation 3 and Android entry points | Wires context capabilities without becoming a context |

The JVM `domain` and `application` modules do not depend on Android. Context
application code may expose a narrow `application.publicapi` when another
context needs an explicit client projection or port. Consumers do not import
another context's infrastructure or presentation. `verify-context-architecture.py`
checks the canonical roots, module shape, framework-free modules, import
direction and removal of source from the former `feature` and `data` roots.

BC-06 domain does not import BC-05 domain. The physical-allocation read
projection is published by BC-05 `application.publicapi`; BC-06 application
joins that projection with its own Fulfillment facts. Lot substitution uses
BC-05-owned scope and authority values and consumes BC-06 through a narrow
current-allocation query. These joins guide client selection only; the API
still decides whether a picking or substitution command is valid.

BC-09 PDF presentation consumes a JVM application renderer port. Android
`PdfRenderer`, file descriptors and transient parser input belong to BC-09
infrastructure; presentation receives a bounded pixel result. This is a local
preview of already-authorized bytes, not a durable document cache or a new
source of authorization.

The shared BC-01 identity projections are the explicitly checked exception to
domain isolation. Application evidence ports may carry a JDK file handle;
filesystem checks and IO execute in infrastructure, not in the application
model constructors. The architecture check also runs isolated negative probes
for forbidden context dependencies, framework imports and file IO.

The generic `:core:local` module now supplies scoped-storage mechanics only.
Receiving, disposition and temperature metadata are stored by BC-05 adapters
under `contexts/inventoryavailability/infrastructure/src/main/.../storage/`;
picking metadata is stored by BC-06 under
`contexts/fulfillmentdelivery/infrastructure/src/main/.../storage/picking/`.
The context-owned store and codec files were migrated while preserving their
existing filenames and key aliases. The new BC-05/BC-06 unit and Android
instrumentation tests have not yet passed on the migrated source; their current
status is recorded below as pending.

The commercial module from the former feature layout is now BC-03
Catalog & Commercial Policy, BC-02 Customer & Buyer Relationships, or BC-04
Sales Commitment according to the existing capability's ownership. Warehouse
work is split between BC-03 identification, BC-05 physical inventory workflows
and BC-06 picking/fulfillment execution. A screen location does not decide its
Bounded Context.

`ConfirmedSkuProjection` is a display-only Catalog projection created from a
current successful detail response. It is not an inventory fact or local
aggregate. Server status strings, identifiers, opaque versions and payload
values retain their existing meanings.

## Selective application coordination

`ReceivingIntentCoordinator` in BC-05 application owns the durable sequence
shared by initial submission and explicit replay: scope/current-context checks,
draft and intent persistence before dispatch, confirmation correlation,
terminal cleanup and unknown-outcome retention. A stale epoch cannot apply a
result to current UI. A late successful clear restores uncertainty for the
original scoped command. The coordinator stages client intent; the API remains
authoritative for receipt and inventory outcomes.

`FieldRequestSubmissionCoordinator` in BC-04 application persists a frozen
request before transport, checks that the caller still owns the context, and
persists the observed resolution even if the originating route loses its
context during transport. The ViewModel renders that result only while its
generation and authority remain current. Both coordinators preserve
cancellation. Other ViewModels continue to coordinate bounded UI flows through
typed ports. The accepted selective-use-case rule does not require a wrapper
for every repository method.

JSON encoders for frozen local intents belong at their serialization boundary,
not in domain or presentation. Exact request bytes, idempotency keys, scope and
authority-epoch correlation must survive explicit retries where the existing
contract requires them. Durable client intent does not claim exactly-once
transport or imply server acceptance.

## Owner decision for MOB-US-009

On 2026-10-08, the Owner authorized the direct-order request for MOB-US-009 and
clarified its API direction. This decision supersedes only the original request
wording in that story. The route to capture is `POST /api/v1/direct-orders`,
including the `201` outcome and the `202` pending-prepaid outcome. It is a
current Owner decision, not evidence that the API contract update, client
implementation, technical verification or Product Acceptance is complete.
Those implementation and contract changes remain with their owning agents.

## Preserved authority and security

The API remains authoritative for Tenant and Workspace access, catalog and
commercial commitment, credit, payment, inventory, fulfillment and Delivery
outcomes. Permission hints only shape client navigation; protected server calls
still enforce authority. Client projections and role labels do not grant access.

The auth, network, local and device foundations retain their existing security
responsibilities. Refresh credentials remain protected; access tokens remain in
memory. Mutation retries remain explicit and reuse the same intent key, payload
and version when the accepted contract requires that behavior. Ambiguous
outcomes do not trigger automatic mutation retries. Full user/Tenant/Workspace/
membership scope and authority epochs remain correlated. Missing or uncertain
scope fails closed.

## Verification and acceptance

From `apps/operations-android`,
`python3 scripts/verify-context-architecture.py` passed on the current
uncommitted working tree. This is a structural check only. The Gradle
architecture, ktlint, lint, JVM, assembly and emulator checks remain pending;
see [the DDD execution record](ddd-client-verification.md) for current status
and [the verification guide](verification.md) for commands.

Implementation and technical verification do not establish Product/UX
Acceptance, System Acceptance or production readiness. Blueprint's current
decisions state that implementation evidence does not silently change TARGET
and that Mobile acceptance gates remain distinct.
