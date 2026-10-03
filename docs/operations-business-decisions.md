# Operations Mobile business decisions and implementation boundaries

## Explicit Owner decisions

The 2026-10-01 Product closure is recorded on Blueprint branch `feature/mobile-owner-decision-closure`, commit `b0ce1d8`. The subsequent direct clarification limits chat to **Sales ↔ Buyer, bidirectionally**. Driver chat is excluded. Product closure and implementation acceptance remain separate.

- Warehouse object access: explicit Workforce Membership + Warehouse grant, default deny, intersected with current permissions and active Tenant/Workspace. Owner approved this policy during execution.
- Initial overview: permitted prepared-Fulfillment work with context, version, fact timestamp and coverage limits. Owner approved this bounded first projection; no enterprise totals.
- Exceptions: WARNING/BLOCKING/CRITICAL, accountable response lifecycle, current owning-process authority; reporting never grants resolution authority.
- Driver location: operational workday only, required during Driver work, no off-duty surveillance, scoped internal visibility and Buyer own-Delivery map. Exact raw retention remains OPEN.
- Communication: only contextual Sales/Buyer chat; no Driver/Buyer chat, WhatsApp/SMS or implicit personal-phone disclosure.
- Loads: simple accepted compatibility rules, manual stop ordering, no automatic optimization or mixed-temperature compartments.
- Responsibility: Dispatch confirmation plus explicit assigned Driver acceptance of the whole load; no Buyer Receipt at handoff. External 3PL references/evidence do not grant Mobile access.
- Instructions: provenance and current active assignment; critical versioned acknowledgment required, normal acknowledgment optional. Chat never updates instructions implicitly.
- Temperature: manual Celsius, attributable context, excursion photo + exception + affected-stock preventive HOLD; no automated final disposition or hardware certification. Automated IoT remains FUTURE.

## Derived implementation choices requiring visibility

| Area | Concrete choice | Meaning not assumed |
| --- | --- | --- |
| Receiving discrepancy | An immutable observation case can become ready for review after complete facts and AVAILABLE exact-subject evidence. Expected facts remain separately reported; receiving prefill represents observed facts. | Review readiness is not resolution/approval or stock truth. Existing connected receipt confirms actual received facts. |
| Lot substitution | Persist a reasoned REQUESTED alternative against current allocation, SKU, UOM, quantity and versions. | No local allocation/reservation/stock replacement or approval is inferred. |
| Cycle count | Preserve count facts; explicit authorized correction uses current stock version and records adjustment history. | No second-person approval rule was invented; authority comes from current server permissions/grants. |
| Dispatch discrepancy | Resolve by immutable linkage to matching current reinspection with reason and current versions. | Original mismatch history is not overwritten; opening a screen is not resolution. |
| Dispatch plan | Record reassignment/scheduling as append-only revisions before dispatch; UTC timestamp is explicit input. | No automatic route optimization, load acceptance or old-driver delivery authority is implied. |
| Dispatch temperature | Record optional manual Celsius against current Fulfillment version and allocated lot; SKU ranges are context. Out-of-range rejects before write while photo/exception/HOLD contracts are absent. | SKU cold-chain applicability does not create a mandatory Fulfillment-stage measurement policy or a dispatch gate. |
| Driver incidents | Keep reason, description and place on current owned active attempt or latest owned final attempt; attach real evidence for review. | Incident is not Delivery outcome, receipt or completed follow-up. Place text is not GPS tracking. |
| Protected evidence | Selected images are copied within a 10 MiB JPEG/PNG/WebP technical bound, encrypted under exact actor/context, restored for manual current-authority review. | Staging never means upload acceptance, scan success or authoritative business success. No generic automatic offline queue. |
| POD | Create immutable proof record, upload/scan evidence and explicitly attach AVAILABLE exact subject. | Missing required-proof policy projection is not fabricated; captured evidence does not imply Buyer Receipt. |
| Handoff identity | Reuse existing bounded token contract and current role/scope. Preserve frozen issue command; ambiguous result is manually recovered. | Validation is not receipt/POD/outcome; a replay without the original secret is not fabricated as a recoverable code. |

## Backend contract gaps after Product closure

Accepted meaning alone does not supply missing API/data. The candidate must report these separately from Product decisions:

- Exception classification/claim/assignment/review/resolution/closure with current process authority.
- Driver operational workday and location ingestion/current projection, scoped internal/Buyer visibility, stop/revocation and approved raw retention handling.
- Sales/Buyer contextual thread/message/read-state contracts and an explicit correction/edit policy.
- Load compatibility facts (windows, capacity, handling/separation constraints), grouping/order history and bilateral whole-load acceptance.
- Current customer/dispatch instructions and critical version/content acknowledgment gates.
- Real thermal photo binding, exception linkage and affected-quantity preventive HOLD where absent in existing manual-temperature contracts.

These gaps do not authorize mock business success, invented permissions, inferred personal data or unverified API claims. Final technical evidence and APK references are recorded in the delivery handoff.
