package com.nexa.mobile.operations.core.auth.credentials

/** The credential is only exposed to the session coordinator, never to UI state. */
interface RefreshCredentialStore {
    suspend fun read(): StoredRefreshCredential

    /** Atomically removes the reusable credential before returning it for one refresh dispatch. */
    suspend fun takeForRefresh(): StoredRefreshCredential

    suspend fun writeReady(credential: String)

    suspend fun clear()
}

sealed interface StoredRefreshCredential {
    data object Missing : StoredRefreshCredential

    data object InFlight : StoredRefreshCredential

    data object Unusable : StoredRefreshCredential

    data class Ready(val credential: String) : StoredRefreshCredential
}
