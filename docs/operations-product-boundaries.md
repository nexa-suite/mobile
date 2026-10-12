# Operations manual product identification

The accepted slice consumes MOB-US-001, MOB-US-002, MOB-US-003 and MOB-US-012. It establishes current workforce authority, shows the permitted task, performs explicit manual Product search, and displays a SKU only after authoritative detail confirmation. It creates no inventory fact or mutation.

## Ownership and composition

| Client boundary | Responsibility and server authority |
| --- | --- |
| `:contexts:tenantaccessgovernance:presentation` | Identity, safe session recovery and current workforce-context presentation; consumes BC-01 |
| `:contexts:catalogcommercialpolicy:presentation` | Product/SKU search and display-only confirmation based on BC-03 Catalog reads |
| `:core:auth` | Memory-only access token, protected refresh credential, rotation and native session epoch |
| `:core:network` | HTTP wire representation, safe client projections and existing bounded protected-call replay |
| `:core:designsystem` | Android-native reusable components and Nexa visual tokens |
| `:app` | Hilt composition, ViewModel factories and authority-sensitive Navigation 3 root |

Client modules map code to the eleven canonical Bounded Contexts; they do not
create those contexts or decide their ownership. A Warehouse Operator role and
screen do not make this identification workflow part of BC-05: Catalog
identification is a BC-03 projection, while receiving and physical stock
workflows belong to BC-05. Android does not implement server aggregates,
pricing or stock authority.

## Authority and candidate state

The task visibility hint uses server-projected `catalog.read`. The legacy `catalog:read` is supported because the current API session projection emits compatibility codes. Warehouse and Inventory read permissions, role names and wildcards do not establish this hint. An empty projection is unknown. The server remains final authority; a 403 closes the task and removes candidate and confirmed state.

List results are unconfirmed candidates. Search trims explicit input, rejects blank input and values above 120 characters, requests page 0 with size 20, and loads another page only on `Mostrar más`. Candidate keys are deduplicated while preserving order. The current candidate's internal `catalogItemId` is used only to request detail; it is not the operator's identity label.

Successful detail must match both the selected catalog identifier and SKU exactly. A mismatch requires candidate reselection. Matching detail supplies the current Product/family name, variant, presentation, SKU and optional metadata; absent optional fields remain unknown. A 404 leaves the candidate unavailable; network or service failure cannot create confirmation. A 401 uses the shared refresh coordinator with one bounded replay. Context-invalid errors clear scoped state. Client request generations and authority epochs reject late search, page and detail results after query changes, context replacement or logout.

Protected navigation is unsaved and keyed by current authority. Back from confirmation returns only to current-context search. Context selection pending does not expose a protected previous entry. Product results and confirmed SKUs are not persisted as local authority.

Navigation entries retain stable route identity while their content observes current
form, search, session and context state. This prevents Navigation 3 entry caching
from freezing field edits, loading feedback or newly received candidates.

## Design evidence

The adoption source is local Design Lab `mobile-style`, initially inspected at
`2665976f2926aa2289ee50b648cd44cea4b5b24b` and rechecked on clean local `main` at
`40cdbe092b036864da43b4703d92bed98d045e0a`. The latter contains the former and has
no `mobile-style` changes between the two refs. Evidence includes
`src/styles/mobile-tokens.css`, Mobile foundations, typography, the spacing grid
and touch-target guidance. Compose maps primary `#2A67D9`, primary container
`#DBEAFE` / `#1E40AF`, canvas `#F6FAFF`, surface `#FFFFFF`, inset `#F1F5F9`, text
`#0F172A` / `#64748B` and border `#E2E8F0` to semantic roles. Buttons use 12dp
corners and surfaces 16dp; candidate/context rows retain a compact 12dp shape.

The token primary container `#DBEAFE` takes precedence over the Lab's differing example swatch. Platform sans typography preserves native scaling because no approved packaged font asset exists. Secondary text on inset surfaces uses a stronger text role for contrast. Rows expose one merged semantic action, fields keep visible labels, and feedback announces politely. Minimum heights allow text wrapping.

Headline, title and label roles follow the local typography specimen: headline
24/32sp bold, large title 22/28sp semibold, medium title 16/24sp semibold and
large label 14/20sp bold. Work-entry gaps and confirmation-card padding use 16dp.

The root Material surface paints the Nexa canvas and supplies the primary text
role. Confirmation translates only the exact known wire values `UNIT`,
`UNSPECIFIED`, `NONE`, `REFRIGERATED` and `FROZEN` for display. Unknown unit,
packaging or cold-chain values remain unchanged; localization does not alter the
authoritative projection or infer packaging, quantities or temperature ranges.

Lab dashboard, scanner, FEFO, dispatch and future navigation scenes are outside the accepted slice. Visual evidence does not define Product semantics. Automated UI checks, emulator screenshots and integration tests do not award human Product/UX Acceptance or production readiness.
