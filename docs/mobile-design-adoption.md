# Operations Mobile design adoption

Operations Mobile `v0.2.0` applies the frozen Design Lab `v1.1.1` commit
`7f520cb483f1fa1513c514230fef69fb991504b3` to the five current
Operations Android screens. The app remains native Kotlin and Jetpack Compose.
Design Lab supplies presentation; the existing API, session, context and Catalog
contracts remain authoritative.

## Sources and mappings

Design Lab `shared/tokens` is the semantic color source. The Android theme maps
`primary-600` to `#2563EB`, slate roles to text and neutral surfaces, and the
shared information, warning and danger roles to feedback. The frozen Mobile
Style supplies the composition, brand canopy, wave, typography and component
language. Brand Navy and Celeste are reserved for identity presentation. The
Owner-approved semantic success green remains on genuine success states,
including the confirmed identity cue; it is not the primary brand color.

`OperationsTheme` packages Plus Jakarta Sans SemiBold/Bold for headings and
Inter Regular/SemiBold for body text. These are pinned official Google Fonts
assets with OFL licenses in `apps/operations-android/licenses/`. Native vector
Nexa marks, wordmarks and control icons are bundled locally. No screen fetches
fonts or artwork at runtime.

`NexaComponents.kt` owns the shared top bar, fields, buttons, context and task
rows, search and candidate rows, feedback panels and confirmed SKU summary.
Protected screens use the canonical light canvas, white surfaces, blue actions,
16dp content margins and 12–18dp surface radii. The Access screen uses the
identity navy/blue grid and wave while retaining the identifier, password,
password visibility and sign-in controls required by the server flow.

| Screen | Adopted presentation | Preserved behavior |
| --- | --- | --- |
| A-01 | Native grid, Nexa mark and wordmark, blue wave, compact credential form, stable loading state | Server authentication and session handling |
| C-01 | Protected top bar, prominent current context, scannable alternatives and pending/rejection feedback | Server-confirmed context selection |
| P-01 | Full-width top bar, active context and one permitted task row | `catalog.read`-gated work entry |
| W-01 | Explicit search controls, structured results, candidate rows and local pending state | Manual submit, paging and candidate/detail distinction |
| W-02 | Success cue, prominent Product summary, typed cold-chain color and subordinate inventory disclaimer | Authoritative detail, display-only; no inventory mutation |

## Verification and review boundary

The implementation passed strict dependency verification, Android architecture,
ktlint, Android lint, the scoped JVM suites, debug and release builds. API 37
and API 29 each passed 6 auth and 27 app connected tests. The opt-in local API
smoke passed on API 29 with the existing local warehouse identity and a real
Catalog candidate/detail. The debug-only review activity renders production
Composables with synthetic fixtures and can hide its controls for visual
captures; those captures are not server-integration evidence.

Compact 320dp and 200% text tests cover all five screens, including long
Product names, SKU visibility and the inventory disclaimer. The active context
bar stacks its action below the names when width or text scale requires it.
The local bootstrap
currently provides one workspace, so a live context A-to-B replacement remains
unverified with this fixture. Physical-device review, human Product/UX
acceptance, broader System Acceptance and Production Readiness remain separate
gates. The [verification guide](verification.md) describes the supported
Android commands.

## Human visual review

The following captures render production Composables through the debug-only
synthetic review harness. They contain no authenticated customer data. The
reference for each screen is the frozen Design Lab Mobile Style `v1.1.1`.

| Screen | Capture | Adaptation to current Product slice | Status |
| --- | --- | --- | --- |
| A-01 | [Identity](assets/wave3/a01-api37.png) | Keeps server credential fields; native grid and wave | Ready for human review |
| C-01 | [Context chooser](assets/wave3/c01-api37.png) | Shows only authorized context data and confirmation states | Ready for human review |
| P-01 | [Work entry](assets/wave3/p01-api37.png) | Shows the one accepted Product task | Ready for human review |
| W-01 | [Product search](assets/wave3/w01-api37.png) | Manual search and server-candidate anatomy; no price or stock | Ready for human review |
| W-02 | [Confirmed SKU](assets/wave3/w02-api37.png) | Display-only identity with semantic success cue | Ready for human review |

The [API 29 compact capture at 200%](assets/wave3/p01-compact-200-api29.png)
shows the stacked context bar. These images support visual review; human
Product/UX acceptance has not been granted.
