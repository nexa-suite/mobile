# Operations Android foundation

The application starts in `Bootstrapping`, reads only the protected refresh record, and uses the native refresh and current-session routes to establish a server-confirmed context. Protected technical content is shown only in `Active`. Missing, unusable, and in-flight credential records do not open protected content. Navigation 3 holds an unsaved root stack keyed by session state; logout and context invalidation replace that stack.

## Module boundary

`app` composes Hilt singletons and the root ViewModel. `core:auth` defines credential and session ports without an HTTP dependency. `core:network` implements those ports with Retrofit and OkHttp, maps transport failures to safe client errors, and offers explicit protected calls. `core:designsystem` provides a minimal Compose theme. `verifyAndroidArchitecture` checks these boundaries in the current source tree.

No Product services or generated OpenAPI client are included. The manually declared Retrofit methods are limited to the current API contract:

| Route | Native request metadata | Result used by client |
| --- | --- | --- |
| `POST /api/v1/authentication/sign-in` | `X-Nexa-Client: NATIVE`; `surface: PLATFORM` in JSON body | Access token in JSON; refresh credential in response header |
| `POST /api/v1/authentication/refresh` | `X-Nexa-Client: NATIVE`, `X-Nexa-Surface: PLATFORM`, `X-Nexa-Refresh-Token` | Rotated access token and refresh credential |
| `POST /api/v1/authentication/sign-out` | Bearer token and `X-Nexa-Client: NATIVE` | HTTP acknowledgement; revocation is not inferred from 204 alone |
| `GET /api/v1/session` | Bearer token | Server-confirmed Tenant, Workspace, membership, and surface presence |

`X-Correlation-ID` is a random request diagnostic value. The server response header is authoritative for an exchange. Problem Details retain diagnostic fields within the network boundary and map to a safe error taxonomy by HTTP status as well as code/category. Raw `detail` is not shown in the root UI or logged.

An eligible protected request that actually receives HTTP 401 can enter the application-wide refresh coordinator and replay once. A command requires an explicit caller-owned idempotency key for replay. Replay preserves that key, `If-Match`, and payload. ETags stay opaque. Timeouts and connection loss on mutations produce `UnknownOutcome`; they do not enter the 401 path. OkHttp connection retry and redirects are disabled. Secret-bearing requests are rejected before any network call when scheme, host, or port differs from the configured origin.

Debug defaults to the Android emulator loopback API origin `http://10.0.2.2:8080/`, configurable with `nexaDebugApiBaseUrl`. Its debug-only network security configuration permits cleartext for `10.0.2.2`, `localhost`, and `127.0.0.1` only; other configured hosts require HTTPS. Release requires `nexaReleaseApiBaseUrl` with a non-local HTTPS root origin. The release task rejects missing, cleartext, local, and obvious placeholder origins. No production Nexa endpoint is committed. The HTTP implementation uses 10-second connect, 20-second read/write, and 30-second call timeouts as foundation configuration.
