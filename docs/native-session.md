# Native session security

The access token exists only in the application-scoped session coordinator. It is not saved in navigation, Compose state, a Bundle, or local storage. Sign-in is a transport boundary, not a Product sign-in screen.

The refresh credential is stored by `AndroidRefreshCredentialStore` in `noBackupFilesDir`. A versioned record is encrypted with an AndroidKeyStore AES-256-GCM key, a provider-generated 12-byte IV, a 128-bit authentication tag, and authenticated record framing. `AtomicFile` commits complete records. Corruption, missing keys, unknown versions, and failed authentication yield unusable state. A reinstall or device move requires reauthentication; this code does not move credentials through backup.

The stable `com.nexa.mobile.operations.native-session.v1` alias belongs only to this store. Local clear deletes the alias and the AtomicFile record, then verifies both are absent. Deleting the key makes old ciphertext unusable even if a record survives or is restored; an incomplete clear raises a local protection error and keeps protected content closed. A later sign-in creates a new key. A future record format or key migration must use an explicit version change; no automatic key rotation is implemented here.

Before refresh dispatch, the store atomically replaces `READY(R1)` with `IN_FLIGHT`, which contains no reusable R1. A successful response supplies R2; the coordinator persists `READY(R2)` before it publishes the new access generation. Any ambiguous refresh response leaves R1 unusable and requires reauthentication. Cold start with `IN_FLIGHT` also requires reauthentication. The API has no rotation recovery endpoint, so a committed server rotation with a lost response cannot be repaired by replaying R1.

One application-scoped coroutine flight serves callers observing the same access-token generation. The epoch invalidates late results after logout or context replacement. Cancelling a caller does not cancel the shared refresh. Local logout clears protected material and navigation state before a bounded best-effort sign-out call. The API can return 204 without proving server revocation; only local sign-out is guaranteed in this client flow.

Auth headers are route-specific. `Authorization` is used for session, sign-out, and protected requests. `X-Nexa-Refresh-Token` is used only for refresh. `X-Nexa-Client: NATIVE` is used only for sign-in, refresh, and sign-out; `X-Nexa-Surface: PLATFORM` is used on refresh. The origin guard compares exact scheme, host, and port and redirects are disabled. No BODY HTTP logger is installed.

Protected response bodies are consumed and closed on the OkHttp callback thread
before the caller resumes. Reading a body after resuming a UI coroutine can block
Main even when headers arrived asynchronously. The cancellable exchange remains
active during body consumption, so caller cancellation also cancels the HTTP call
after headers have arrived. Epoch checks and the single eligible 401 replay remain
unchanged.

Warehouse invalidation records the authority epoch from which it arose. The
activity propagates a session or context failure only while that same epoch is
the authorized access context. A failure left by an earlier session cannot
log out a newly selected context; a current scoped failure still clears access.

JVM tests cover state transitions, 20 concurrent callers, cancellation, generation, epoch races, and ambiguous outcomes. MockWebServer tests cover actual 20x401 replay, header placement, origin rejection, command identity, unsafe mutation replay denial, stale ETag responses, and Problem Details. Android instrumentation exercises Keystore encryption, unique IVs, tamper rejection, old ciphertext rejection after clear, atomic write recovery, and in-flight state on emulator APIs 37 and 29.

## Foreground authority verification

Returning an active activity to the foreground immediately closes protected
content and clears the current access lease and verified context. One
application-scoped flight checks the current session with the API before
reopening access. A definitive expired-access rejection may use the existing
safe refresh rotation; an ambiguous rotation requires reauthentication. Offline
verification remains retryable without presenting old context as authority.
Caller cancellation does not cancel the shared flight. Epoch checks reject
late verification after logout or context replacement.

The access projection retains current user, Tenant, Workspace, Membership and
permissions separately from display labels. Changes to any authority component
invalidate scoped state even when company and Workspace names remain identical.
Capability routes require a currently verified context and their own permission
hint. Losing capability removes deep entries, searches and confirmed SKU state;
regaining permission requires new identification. Unknown permission does not
open a protected route.
