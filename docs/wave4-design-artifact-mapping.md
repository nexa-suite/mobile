# Operations Mobile Wave 4 design artifact mapping

Status: TARGET construction mapping; Human UX Acceptance OPEN.

Product authority: Blueprint `main@9034f4857b45224832f61af3e088f8a29143b187`,
`03-mobile/requirements/mobile-v1-catalog.md`. Visual evidence is frozen to
Design Lab `main@05b42a142481e45f8a624d1b2a83e4fded88cb7d`, principally
`mobile-style/src/components/kotlin-mocks/KotlinMocksSection.astro` and the
existing Mobile Style screen specifications. This mapping does not update the
historical Wave 3 baseline in [mobile-design-adoption.md](mobile-design-adoption.md).

Production destinations below are proposed responsibility locations, not
claims that those screens or contracts have been implemented. `Mock*` names
identify reference compositions in `core:designsystem/MockMobileScreens.kt`;
production features must not depend on those screens.

| Product story/state | Canonical Product outcome | Design Lab artifact | Reusable visual structure | Forbidden/mock-only semantics | Production screen/component destination (TARGET) | Human review status |
| --- | --- | --- | --- | --- | --- | --- |
| MOB-US-001 return/reauthentication | Confirm identity before exposing permitted work | Auth workspace mock; LoginScreenSpec | Identity canopy, labeled form, pending/error feedback | Biometric shortcut, role prefill, local session authority | `feature:access` return/access; `app` protected root | OPEN |
| MOB-US-002 context selection/replacement | Confirm intended context; prevent previous-scope information reuse | Auth workspace mock | Context rows, active-context hierarchy | Local workspace decision, retained old-context back stack | `feature:access` context chooser; `app` authority composition | OPEN |
| MOB-US-003 permitted work | Expose only currently permitted work; reject unconfirmed permission | Auth workspace mock; OperationsScreenSpec | Task cards, top bar, navigation treatment | Universal five-item menu, role-string authorization, Buyer destination | `app` capability route registry and Work Entry | OPEN |
| MOB-US-011 scanning/candidate/fallback | Resolve one permitted Product without creating receipt or pick facts | Scanner mock; ScannerScreenSpec | Reticle, status, result panel, manual fallback | Scanner as Product authority; retained raw frames | `feature:warehouse` identification; `core:device` scanner adapter | OPEN |
| MOB-US-012 manual identification | Confirm explicit Product choice without stock mutation | Scanner fallback; existing manual search composition | Search field, candidate rows, confirmed summary | Guessing ambiguous results, offline confirmation | `feature:warehouse` search/detail | OPEN |
| MOB-US-013 receiving/Pending/UnknownOutcome | Record one valid arrival atomically after connected confirmation | New W4 composition using MobileComponentsSection | Labeled form, summary, confirmation and recovery panel | Local draft increases stock; uncertain request shown successful | `feature:warehouse` receiving | OPEN |
| MOB-US-014 lot/expiry/quantity draft | Preserve actual valid lot facts; duplicate arrival does not double quantity | New W4 composition using MobileComponentsSection | Persistent labels, field errors, quantity controls | Draft creates sellable stock; invalid expiry silently accepted | `feature:warehouse` receiving lot fields | OPEN |
| MOB-US-015 stock condition/freshness | Distinguish stock quantities and condition only within authorized warehouse scope | New W4 composition; warehouse card language | Lot cards, cold-chain badges, freshness feedback | UI filtering as object authorization; stale stock permits work | `feature:warehouse` stock-condition view | OPEN |
| MOB-US-016 picking | Record eligible allocated FEFO pick once without over-consumption | Warehouse picking mock | Allocation card, stepper geometry, progress hierarchy | Mock story labels override Blueprint; scanner required as AC; client FEFO authority | `feature:warehouse` picking | OPEN |
| MOB-US-017 discrepancy/disposition | Preserve physical difference and stock history with authority/reason/evidence | New W4 composition; warehouse card language | Reason form, separate quantities, evidence and recovery feedback | Generic stock adjustment; disconnected draft changes stock | `feature:warehouse` discrepancy/disposition | OPEN |
| MOB-US-019 manual temperature | Preserve attributable value/unit/time/person/subject without silent release | New W4 composition; cold-chain semantic roles | Reading form, subject card, concerning-state feedback | Continuous telemetry; BC-05 competing temperature truth | `feature:warehouse` temperature evidence consuming BC-06 | OPEN |
| MOB-US-020 dispatch readiness | Show only authorized deliveries meeting current preparation gates | Dispatch kanban mock; DispatchBoardStory | Readiness cards, grouping, freshness hierarchy | RESERVED implies ready; final dispatch/handoff authorization | `feature:dispatch` readiness list/detail | OPEN |
| MOB-US-021 assignment/Candidate/Pending/Stale | Record one eligible Driver assignment against current ready Delivery | Dispatch shell plus new W4 assignment composition | Candidate list, current summary, explicit confirmation | Local assignment authority; stale overwrite; handoff completion | `feature:dispatch` assignment | OPEN |
| MOB-US-022 outgoing match/mismatch | Persist check against current physical allocation without another stock movement | Dispatch shell plus new W4 check composition | Allocation comparison, mismatch feedback | Read-only validation presented as durable fact; check completes handoff | `feature:dispatch` outgoing-goods check | OPEN |
| MOB-US-026 assigned deliveries | Expose only Driver's current authorized deliveries | Driver route mock; DriverRouteStory | Assignment cards, list hierarchy, freshness feedback | Client-only Driver filtering; tracking/geofence authority | `feature:delivery` assigned list/detail | OPEN |
| MOB-US-027 start/current Attempt | Confirm one active assigned Delivery Attempt; uncertainty remains explicit | Driver route shell plus new W4 Attempt composition | Delivery context, start confirmation, current Attempt panel | IN_TRANSIT substitutes for Attempt; navigation starts Attempt | `feature:delivery` Attempt start/current | OPEN |
| MOB-US-028 directions/return | Hand authorized destination to external navigation without Delivery mutation | Driver route mock; DriverRouteStory | Destination card, directions action, return feedback | Continuous/background location, geofence, automatic arrival, Sensirion telemetry | `feature:delivery` directions action; `core:device` external navigator | OPEN |
| MOB-US-031 outcome/Pending/UnknownOutcome | Preserve one allowed active assigned Attempt outcome with required evidence | New W4 composition; POD visual shell | Outcome form, evidence summary, confirmation/recovery | Local completion, outcome for inactive Attempt, blind retry after 412 | `feature:delivery` outcome | OPEN |
| MOB-US-032 partial/rejected/remaining | Preserve separate quantities/reason; continuation remains server-owned | New W4 composition; POD visual shell | Separate quantity fields, remaining-obligation panel | Client creates continuation; rejected delivery labeled complete | `feature:delivery` partial/rejected outcome | OPEN |
| MOB-US-033 proof/unresolved | Preserve required proof identity/person/time bound to authorized active Attempt | POD mock composition | Evidence panel, proof status, explicit unresolved feedback | MOB-US-034 code semantics; media presence implies accepted proof/completion | `feature:delivery` proof; `core:device` capture only if canonically selected | OPEN |

## Reuse constraints

- Buyer Catalog and receipt mocks are outside Operations navigation. Neutral
  card hierarchy, stepper geometry, badges and button treatment may be reused
  only for accepted Operations work.
- Offline Sync mock is not a Product blueprint. No generic mutation queue,
  force-sync action, last-write-wins behavior or generic WorkManager replay.
  Any `core:local` record is justified per flow, scope-bound and non-authoritative.
- COD, RMA, incident GPS, industrial lockout, cycle count and Buyer receipt
  semantics remain excluded. MOB-US-023/024/025/034 are outside Wave 4.
- New compositions are required for receiving, lot facts, stock condition,
  discrepancy, manual temperature, assignment, outgoing check, active Attempt
  and delivery outcomes. An absent exact mock does not justify invented rules.
- Reusable components must be domain-neutral; Product state remains within
  feature responsibility. Navigation items are data-driven and guarded by
  current authority; visibility does not authorize server operations.
- Use current Nexa semantic roles without local success-color hardcoding.
  Shared token JSON and current Mobile Style disagree on success green versus
  cyan/blue. Palette resolution remains OPEN for Design Adoption; no final
  visual acceptance is claimed.
- Controls require at least 48dp targets, persistent labels and readable
  Loading/Pending/UnknownOutcome/Stale/Conflict semantics. Scanner status,
  instructions, result and fallback remain outside preview. Final visual and
  TalkBack review require human evidence.
