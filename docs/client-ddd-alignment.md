# Operations client DDD alignment

The Android client projects the accepted Nexa domain into operational workflows.
The eleven Bounded Contexts and their authoritative aggregates remain in Nexa API.
Client contracts, Gradle modules and screen areas do not create additional
Bounded Contexts or grant business authority.

The accepted architecture sources are Blueprint commit
`9034f4857b45224832f61af3e088f8a29143b187`:
[ADR-0020](https://github.com/nexa-suite/blueprint/blob/9034f4857b45224832f61af3e088f8a29143b187/01-shared/architecture/decisions/adr/adr-0020-mobile-client-layering.md),
[Mobile projection](https://github.com/nexa-suite/blueprint/blob/9034f4857b45224832f61af3e088f8a29143b187/01-shared/domain/strategic-ddd/mobile-projection.md)
and [application architecture](https://github.com/nexa-suite/blueprint/blob/9034f4857b45224832f61af3e088f8a29143b187/03-mobile/architecture/technical/application-architecture.md).

## Primary DDD references and local interpretation

Eric Evans's [DDD Reference](https://www.domainlanguage.com/wp-content/uploads/2016/05/DDD_Reference_2015-03.pdf)
defines Bounded Context, Layered Architecture and Aggregates (printed pages 2,
10 and 16). Vaughn Vernon's [Effective Aggregate Design](https://kalele.io/effective-aggregate-design/)
explains aggregates as carefully chosen consistency boundaries. These are
primary pattern references; Blueprint determines their accepted Nexa application.

In this client, the interpretation is to isolate projections and workflow ports
from UI and infrastructure, preserve the server's vocabulary, and translate
transport representations at the adapter boundary. There is no evidence for a
new client-owned business consistency boundary, so adding local aggregate roots,
invented domain events or one context per screen would contradict the canon.
The selective coordinators handle durable client command sequencing, not server
business invariants. The supplied Evans/Vernon books and docs-as-code guide are
reference material, not execution instructions or Product authority.

## Construction boundaries

| Boundary | Implemented responsibility | Dependencies |
| --- | --- | --- |
| `feature/*/contract`, `model` packages | Non-authoritative projections, scoped identity values, frozen intents and typed results | Kotlin/JVM, Coroutines; dispatch and delivery also use existing JSON for frozen-intent invariants |
| `feature/*/contract`, `application` packages | Repository, staging and device ports; selective workflow coordination | Client models and abstractions |
| Feature presentation | Compose screens, UI state and Android ViewModels | Own contract plus design/device foundations where applicable |
| `data/operations` | Verified-session correlation, DTO translation, remote gateways and local metadata adapters | Client contracts and auth/network/local/device foundations |
| `app` | Hilt composition, ViewModel factories, Navigation 3, Android entry points and lifecycle-bound location capture | Presentation, client contracts, data and foundations |

The five contract areas are access, warehouse, dispatch, delivery and commercial.
The commercial module extracts existing operational screens from `app`; it adds
no Product surface or context. Names such as Picking, Receiving and Dispatch
retain their accepted client vocabulary.

The contracts compile without Android, Compose, Hilt, Retrofit or OkHttp. UI
state remains in presentation. `ConfirmedSkuProjection` is the display-only
catalog confirmation previously named `ConfirmedSkuUiState`; it is not an
inventory fact or a local aggregate. Server status strings, identifiers, opaque
versions and payload values retain their existing meanings.

JSON in dispatch and delivery contracts validates or encodes existing frozen
local intents. It does not parse HTTP responses or make Product decisions.
HTTP DTOs and their translation remain outside the contracts. The extraction
preserves existing encoders and byte ordering.

## Selective application coordination

`ReceivingIntentCoordinator` owns the durable sequence shared by initial
submission and explicit replay: scope/current-context checks, draft and intent
persistence before dispatch, confirmation correlation, terminal cleanup and
unknown-outcome retention. A stale epoch cannot apply a result to current UI.
A late successful clear restores uncertainty for the original scoped command.

`FieldRequestSubmissionCoordinator` persists the frozen request before transport,
checks that the caller still owns the context, and persists the observed
resolution even if the originating route loses its context during transport.
The ViewModel renders that result only while its generation and authority remain
current. Both coordinators preserve cancellation.

Other ViewModels continue to coordinate bounded UI flows through typed ports.
The accepted selective-use-case rule does not require a wrapper for every
repository method.

## Preserved authority and security

The API remains authoritative for Tenant and Workspace access, inventory,
commercial commitment, credit, payment and Delivery outcomes. Permission hints
only shape client navigation; protected server calls still enforce authority.

The auth, network, local and device foundations retain their existing source
behavior. Refresh credentials remain protected; access tokens remain in memory.
Mutation retries remain explicit and reuse the same intent key, payload and
version. Ambiguous outcomes do not trigger automatic mutation retries. Full
user/Tenant/Workspace/membership scope and authority epochs remain correlated.

## Enforced dependency direction

`verifyAndroidArchitecture` validates the accepted feature/contract/data module
set, framework-free contract boundaries, each feature's dependency on its own
contract, and data imports/dependencies on contracts rather than presentation.
Existing security and route checks remain active.

The aggregate `testDebugUnitTest` includes Android JVM suites and the contract
modules' `test` tasks. `ktlintCheck` includes both module kinds; `lintDebug`
covers Android modules. See [verification](verification.md) for exact commands.

Implementation and technical verification do not establish Product/UX
Acceptance, System Acceptance or production readiness.
