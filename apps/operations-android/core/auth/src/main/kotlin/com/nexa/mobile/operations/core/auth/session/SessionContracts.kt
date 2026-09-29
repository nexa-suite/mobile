package com.nexa.mobile.operations.core.auth.session

import kotlinx.coroutines.flow.StateFlow

sealed interface SessionState {
    data object Bootstrapping : SessionState

    data object SignedOut : SessionState

    data class Restoring(val canRetryConnection: Boolean = false) : SessionState

    data object ContextRequired : SessionState

    data object Active : SessionState

    data object ReauthenticationRequired : SessionState

    data object LocalProtectionError : SessionState
}

/** This lease is transport-only. It must never enter saved state, UI state, or diagnostics. */
data class AccessTokenLease(val value: String, val generation: Long, val epoch: Long) {
    override fun toString(): String =
        "AccessTokenLease(generation=$generation, epoch=$epoch, value=REDACTED)"
}

interface AccessTokenSource {
    val sessionState: StateFlow<SessionState>

    suspend fun currentAccess(): AccessTokenLease?

    suspend fun recoverAfterUnauthorized(observed: AccessTokenLease): AccessTokenLease?

    suspend fun rejectCurrentAccess(observed: AccessTokenLease)

    suspend fun isEpochCurrent(epoch: Long): Boolean
}

data class NativeSignIn(val identifier: String, val password: String, val workspaceSlug: String) {
    override fun toString(): String =
        "NativeSignIn(identifier=REDACTED, password=REDACTED, workspaceSlug=REDACTED)"
}

data class IssuedNativeSession(val accessToken: String, val refreshCredential: String) {
    init {
        require(accessToken.isNotBlank() && refreshCredential.isNotBlank())
        require('\r' !in refreshCredential && '\n' !in refreshCredential)
    }

    override fun toString(): String =
        "IssuedNativeSession(accessToken=REDACTED, refreshCredential=REDACTED)"
}

data class VerifiedSession(
    val hasAuthorizedContext: Boolean,
    val userId: String? = null,
    val tenantId: String? = null,
    val tenantName: String? = null,
    val tenantSlug: String? = null,
    val workspaceId: String? = null,
    val workspaceName: String? = null,
    val workspaceSlug: String? = null,
    val membershipId: String? = null,
    val permissions: Set<String> = emptySet()
) {
    override fun toString(): String =
        "VerifiedSession(hasAuthorizedContext=$hasAuthorizedContext, " +
            "membershipId=REDACTED, permissions=${permissions.size})"
}

interface AuthRemoteGateway {
    suspend fun signIn(input: NativeSignIn): IssuedNativeSession

    suspend fun refresh(credential: String): IssuedNativeSession

    suspend fun currentSession(accessToken: String): VerifiedSession

    suspend fun signOut(accessToken: String)
}

sealed class AuthGatewayFailure : Exception() {
    class DefinitiveRejection : AuthGatewayFailure()

    class AmbiguousOutcome : AuthGatewayFailure()

    class NetworkUnavailable : AuthGatewayFailure()

    class ProtocolFailure : AuthGatewayFailure()
}

/** A successful HTTP sign-out response does not prove that a server session was revoked. */
data class LocalLogoutResult(
    val serverSignOutAcknowledged: Boolean,
    val localMaterialCleared: Boolean
)
