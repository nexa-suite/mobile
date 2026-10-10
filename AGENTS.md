# Repository working agreement

- Preserve accepted authority. The live Nexa Blueprint is the canonical source
  for Product, Domain, C4, data and architecture. The API contract and
  implementation are current API evidence; this repository is Operations
  Android implementation evidence. The Mobile Report is academic evidence only.
  Keep AS-IS, TARGET, OPEN, technical verification and Product Acceptance
  distinct. Do not infer an aggregate, business owner, or Bounded Context from a
  package, screen, endpoint, schema or module.
- Align client code to the eleven canonical contexts under
  `apps/operations-android/contexts/<context-root>/`. Current roots and their
  implemented layers are listed in [the DDD alignment](docs/client-ddd-alignment.md).
  Runtime modules exist only where code exists: BC-10 has application,
  infrastructure and presentation source; BC-08 and BC-11 have no runtime
  module. Never add empty layers to make the folder structure appear uniform.
- Keep the client layers explicit. `domain` is limited to non-authoritative
  projections and local value constraints; it does not contain server
  aggregates, authorization, inventory or other business authority. `application`
  defines client ports and selective workflow coordination. `infrastructure`
  adapts HTTP, serialization, context-owned protected metadata and platform
  APIs. `:core:local` provides generic scoped-storage mechanics only; receiving,
  disposition, temperature and picking records belong to BC-05/BC-06
  infrastructure.
  `presentation` owns screens, UI state and navigation. The `app` module is the
  composition root, not a Bounded Context. `core:*` modules are technical
  foundations, not business contexts.
- Keep context dependencies pointed at narrow application public APIs. Do not
  import another context's infrastructure or presentation. Translate external
  projections at the adapter boundary; do not duplicate Nexa aggregates or
  business rules in the client. Do not create one wrapper use case per
  repository method. Add a coordinator only for a demonstrated workflow
  sequence, such as durable intent, explicit retry or authority-epoch
  correlation.
- The API remains authoritative for authentication, Tenant and Workspace
  authorization, catalog and commercial decisions, inventory, credit,
  payments, fulfillment and delivery outcomes. Missing or changed scope fails
  closed. Preserve user, Tenant, Workspace and membership correlation where the
  accepted contract requires it. Do not claim exactly-once transport; use the
  accepted idempotency and deduplication behavior for at-least-once delivery.
- Keep access tokens in process memory. Protect refresh credentials with
  AndroidKeyStore, AES-GCM and an atomic record in `noBackupFilesDir`. Persist
  `IN_FLIGHT` without the old credential before dispatching a refresh. Never
  retry an ambiguous rotation with the old credential. Attach secret headers
  only to the configured API origin; do not weaken authentication, scope,
  encryption or validation to make a test pass.
- Keep UI code away from HTTP and secure storage. `:core:auth` must not import
  Retrofit, OkHttp or `:core:network`; `:core:network` owns transport and does
  not implement Product permission decisions. Foundation modules must not
  depend on a context.
- Run the Python context-boundary check from `apps/operations-android`:
  `python3 scripts/verify-context-architecture.py`. Before integration, also
  run the applicable Gradle architecture, ktlint, lint, JVM, assembly and
  emulator gates from [the verification guide](docs/verification.md). Report
  exact commands and results; a structural check alone does not verify a build
  or feature behavior.
- Preserve user changes and authorship. Do not force-push, rewrite shared
  history, change Git identity or signing configuration, or publish a release
  without explicit Owner authorization. A current authorization for a named
  release scope governs that scope only; authorization is not evidence that the
  release was built, published, accepted or deployed. Do not add AI or agent
  attribution to repository artifacts.
