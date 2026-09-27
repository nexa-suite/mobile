# Mobile design adoption

Operations Android exposes five current Product screens: A-01 Access, C-01 Context Chooser, P-01 Operations Work Entry, W-01 Manual Product Search and W-02 Confirmed SKU. This document identifies presentation seams for a later, separately reviewed Design Lab wave. It does not define new Product behavior or reproduce the Owner's Style Guidelines.

## Entry points

`OperationsTheme` supplies semantic `NexaColors`, `NexaTypography`, `NexaShapes`, `NexaSpacing` and `NexaSizes`. `NexaComponents.kt` owns reusable visual primitives. Feature screens in `:feature:access` and `:feature:warehouse` receive immutable UI state and event callbacks; `:app` composes them behind the current session, context and authority-sensitive navigation. A client feature module is not a Bounded Context.

| Screen | Composition seam | Shared components |
| --- | --- | --- |
| A-01 | Identity form and feedback | `NexaTextField`, `NexaPasswordField`, `NexaPrimaryButton`, `NexaStatePanel` |
| C-01 | Context header, choices and pending/error states | `NexaTopAppBar`, `NexaContextChoiceRow`, `NexaStatePanel` |
| P-01 | Active context and permitted task | `NexaTopAppBar`, `NexaActiveContextBar`, `NexaTaskRow`, `NexaStatePanel` |
| W-01 | Explicit query, results and candidate states | `NexaSearchField`, `NexaProductCandidateRow`, `NexaFeedbackBanner`, `NexaStatePanel` |
| W-02 | Confirmed Product hierarchy | `NexaTopAppBar`, `NexaActiveContextBar`, `NexaConfirmedSkuSummary` |

## Adoption boundary

Replace semantic tokens and shared component styling from Owner-approved local Design Lab evidence in the next wave. Keep identity/session handling, context selection, `catalog.read` gating, explicit search, candidate selection, authoritative Catalog detail, authority epochs, protected navigation and W-02 display-only behavior unchanged. Design Lab evidence does not grant business authority. Do not expose internal Catalog IDs, add stock actions to W-02 or move API parsing into Composables. Revalidate accessibility and Android gates after any visual change.

The debug-only `DebugReviewActivity` scenario picker renders production Composables for normal, loading, error, multiple-context, Product search and confirmed-SKU variants, including missing optional fields and long Product names. Use it for visual review without treating synthetic data as server confirmation. Its scenarios never enter the release build. The [verification guide](verification.md) covers the emulator and release checks.
