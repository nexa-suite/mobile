# Mobile API contract ledger — v0.17.0

Status: read-only integration evidence captured from `nexa-suite/api` tag
`v0.17.0` at commit `4c955ca35331fd3afc58c01f666fc7b0cec9c755`.

This file records observed API behavior. It is not a Product or Domain
decision, and it does not authorize changes to the API repository.

## Scope used by the AV1 preview

The preview consumes only the authentication/session, access-context and SKU
identification surfaces needed for `MOB-US-001`, `MOB-US-002`, `MOB-US-003`,
`MOB-US-011` and `MOB-US-012`. The API remains authoritative for identity,
tenant/workspace scope, authorization and SKU resolution.

## Current client implementation evidence

The connected preview contains typed adapters for the observed contracts:

- `core/network/NativeAccessClient.kt` models sign-in, refresh, current-session
  and sign-out requests. Its base URL and `ClientSurface` are explicit build
  inputs; the client does not infer a native surface from the app name.
- `core/network/NativeCatalogClient.kt` calls the observed SKU resolver with a
  bearer token and preserves the server response outcome without selecting a
  local SKU.
- `feature/warehouse` accepts camera or manual identifiers, then delegates
  resolution only when a locally stored session is present. CameraX and ML Kit
  decode input; the API remains the source of SKU truth.

The adapter tests use deterministic request executors and fakes. They prove
serialization, headers, state transitions and failure mapping; they do not
prove a live API, camera/barcode runtime or physical-device run. A separate
Android API 36 emulator smoke run proves installation, launch and the
fail-closed access-preview state only.

## Authentication and session

### Workspace preview

`POST /api/v1/auth/workspace-previews`

Request JSON: `workspaceSlug`, constrained by the observed API validation to
3–80 characters matching `[a-zA-Z0-9-]+`.

Observed response fields: `recognized`, `displayName`, `workspaceUrl`,
`logoUrl` and `loginAvailable`. Unknown workspaces return a non-recognized
preview without protected data. This route is the public context-discovery
step before sign-in; it does not establish a session.

### Sign in

`POST /api/v1/authentication/sign-in`

Required JSON fields:

- `identifier`
- `password`
- `workspaceSlug`
- `surface`

Observed `ClientSurface` values are `PLATFORM` and `PORTAL` only. A native
transport may send `X-Nexa-Client: NATIVE`, but that header is a transport
marker and is not an observed `ClientSurface` value.

Observed successful response: HTTP 200 with `AuthenticationResponse` fields
`accessToken`, `tokenType`, `expiresIn` and `session`. The nested session shape
observed in the tag is `userId`, `displayName`, `email`, `preferredLanguage`,
tenant/workspace IDs and slugs, `membershipId`, `roles`, `permissions`,
`roleDefinitionIds`, `authorizationVersion` and `surface`.

For explicit native transport, the controller writes the opaque refresh token
to the `X-Nexa-Refresh-Token` response header.

Source evidence in the API tag:

- `src/main/java/com/nexa/api/tenantaccessgovernance/iam/presentation/rest/AuthenticationController.java`
- `src/main/java/com/nexa/api/tenantaccessgovernance/iam/application/model/WorkspacePreview.java`
- `src/main/java/com/nexa/api/tenantaccessgovernance/iam/domain/model/access/ClientSurface.java`
- `src/test/java/com/nexa/api/tenantaccessgovernance/iam/infrastructure/AuthenticationFlowIT.java`

### Refresh

`POST /api/v1/authentication/refresh`

Observed request rules:

- `X-Nexa-Surface: PLATFORM|PORTAL` is required.
- Browser refresh uses the corresponding refresh cookie.
- Native transport uses `X-Nexa-Client: NATIVE` and
  `X-Nexa-Refresh-Token`.
- The request has no body.

Observed successful response: HTTP 200 with `AuthenticationResponse`; native
refresh returns a replacement `X-Nexa-Refresh-Token` header. Refresh rotation
and reuse detection are server-owned; the client must treat a failed refresh
as session invalidation and purge local session material.

### Sign out and current session

- `POST /api/v1/authentication/sign-out` — optional
  `X-Nexa-Surface`, no body, observed HTTP 204. Native transport does not use
  a browser cookie.
- `GET /api/v1/session` — Bearer token required; observed HTTP 200 response
  contains `user`, `tenant`, `workspace`, `membership` and `surface`.

Source evidence in the API tag:

- `src/main/java/com/nexa/api/shared/infrastructure/security/CurrentAccessContextFilter.java`
- `src/main/java/com/nexa/api/tenantaccessgovernance/iam/application/service/CurrentSessionService.java`
- `src/main/java/com/nexa/api/tenantaccessgovernance/iam/application/service/RefreshSessionService.java`

## Authentication semantics blocker

`BLOCKED — AUTH SURFACE SEMANTICS`

The tag proves that `ClientSurface` accepts only `PLATFORM` and `PORTAL`, and
that `NATIVE` is a transport marker. It does not prove which accepted surface
represents Nexa Operations Mobile. The preview must not invent `MOBILE` or
`OPERATIONS`, nor silently assume that Operations maps to `PLATFORM`.

Allowed work while blocked:

- typed request/response adapters with an explicit surface supplied by the
  caller;
- fake and contract tests that verify headers and serialization;
- launch, loading, error, permission and navigation states that do not claim
  real authenticated access.

Disallowed claim until Product/Architecture/API authority resolves the mapping:

- real Operations Mobile sign-in, session restoration or server-confirmed
  access using an unapproved surface mapping.

## SKU identification

`GET /api/v1/skus/resolve?identifier=<value>`

Observed successful response fields:

- `outcome`
- `identifierType`
- `normalizedIdentifier`
- `candidateCount`
- `skuId`
- `skuCode`
- `gtin`
- `presentation`
- `unitOfMeasure`
- `status`

Observed outcomes are `RESOLVED`, `NOT_FOUND` and `AMBIGUOUS`. An ambiguous
response has no selected `skuId`. The endpoint requires `catalog:read` and
tenant/workspace visibility; the API, not the scanner or client, decides the
result.

Manual entry uses this same resolver. No separate manual lookup endpoint was
observed. The sales-order creation surface is not a substitute for
identification and is outside this preview.

Source evidence in the API tag:

- `src/main/java/com/nexa/api/catalogcommercialpolicy/presentation/rest/SkuIdentifierResolutionController.java`
- the tagged SKU resolution integration tests and DTOs

## Catalog lookup evidence

`GET /api/v1/catalog-items`

Observed query parameters include `q`, `brand`, `category`, `coldChain`,
`page`, `size`, `sort` and `direction`. `coldChain` observed values are
`NONE`, `REFRIGERATED` and `FROZEN`; `size` defaults to 20 and is capped at
100. The response contains `items`, paging totals and sort metadata.

`GET /api/v1/catalog-items/{catalogItemId}` was also observed. It is not
needed for the AV1 stop point unless the resolver result requires a separately
authorized detail call.

Known contract drift to preserve as caveat rather than infer around:

- the published OpenAPI represents catalog parameters differently from the
  controller's individual query arguments;
- several OpenAPI status descriptions differ from implementation responses;
- the generic error schema differs from the observed Nexa Problem Details
  shape.

## Errors, authorization and tenancy

Observed Problem Details media type: `application/problem+json`.

Observed safe fields include `type`, `title`, `status`, `detail`, `instance`,
`code`, `correlationId`, `category`, `retryable`, `traceId` and `errors`.
Client mapping must preserve only safe status/code/category/retry metadata;
it must never expose bearer tokens, refresh tokens, authorization headers or
raw sensitive response bodies.

Observed status meanings relevant to this slice:

- `401` — unauthenticated or invalid access token;
- `403` — forbidden or invalid access context;
- `409` — conflict/idempotency failure;
- `412` — stale state;
- `428` — missing precondition;
- `429` — throttled, with observed retry-after guidance;
- `500` — internal server failure.

Protected `/api/**` routes require authentication except explicitly public
routes. Server-side tenant/workspace predicates and capability checks remain
authoritative. A missing, stale or unconfirmed scope fails closed in the
client projection.

## Local runtime evidence boundary

The API tag contains local bootstrap and Testcontainers fixtures, including an
`icisa-test` tenant/workspace and catalog data. The bootstrap is disabled by
default and fixture coverage does not prove that every SKU has a sellable GTIN.
This ledger therefore does not claim a successful live Mobile authentication
or SKU-resolution run; the emulator smoke evidence does not change that
boundary.

## Reproduction commands

Run from the API checkout; all commands are read-only:

```bash
git show v0.17.0:README.md >/dev/null
git describe --tags --exact-match v0.17.0
git status --short --branch
```

Observed baseline at capture time:

```text
v0.17.0
## develop...origin/develop
```
