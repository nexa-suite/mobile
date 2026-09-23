package com.nexa.mobile.operations.core.auth.session

import com.nexa.mobile.operations.core.auth.credentials.RefreshCredentialStore
import com.nexa.mobile.operations.core.auth.credentials.StoredRefreshCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class SessionCoordinator(
    private val credentialStore: RefreshCredentialStore,
    private val remote: AuthRemoteGateway,
    private val applicationScope: CoroutineScope,
) : AccessTokenSource {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Bootstrapping)
    override val sessionState = mutableState.asStateFlow()

    private var epoch = 0L
    private var generation = 0L
    private var access: AccessTokenLease? = null
    private var pendingSessionAccess: String? = null
    private var refreshFlight: RefreshFlight? = null

    override suspend fun currentAccess(): AccessTokenLease? = mutex.withLock {
        access.takeIf { mutableState.value == SessionState.Active }
    }

    override suspend fun isEpochCurrent(epoch: Long): Boolean = mutex.withLock { this.epoch == epoch }

    override suspend fun rejectCurrentAccess(observed: AccessTokenLease) {
        mutex.withLock {
            if (epoch != observed.epoch || access?.generation != observed.generation) return
            epoch++
            access = null
            pendingSessionAccess = null
            refreshFlight = null
            try {
                credentialStore.clear()
                mutableState.value = SessionState.ReauthenticationRequired
            } catch (_: Exception) {
                mutableState.value = SessionState.LocalProtectionError
            }
        }
    }

    suspend fun restore() {
        val expectedEpoch = mutex.withLock {
            if (mutableState.value != SessionState.Bootstrapping) return
            mutableState.value = SessionState.Restoring()
            epoch
        }
        val durable = try {
            credentialStore.read()
        } catch (_: Exception) {
            setStateIfCurrent(expectedEpoch, SessionState.LocalProtectionError)
            return
        }
        when (durable) {
            StoredRefreshCredential.Missing -> setStateIfCurrent(expectedEpoch, SessionState.SignedOut)
            StoredRefreshCredential.InFlight,
            StoredRefreshCredential.Unusable,
            -> failReauthentication(expectedEpoch)
            is StoredRefreshCredential.Ready -> beginRefresh(expectedEpoch, generation).await()
        }
    }

    /** User-driven sign-in only; the foundation exposes no sign-in Product UI. */
    suspend fun signIn(input: NativeSignIn): Boolean {
        val expectedEpoch = mutex.withLock {
            epoch++
            access = null
            pendingSessionAccess = null
            mutableState.value = SessionState.Restoring()
            try {
                credentialStore.clear()
            } catch (_: Exception) {
                mutableState.value = SessionState.LocalProtectionError
                return false
            }
            epoch
        }
        val issued = try {
            remote.signIn(input)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            setStateIfCurrent(expectedEpoch, SessionState.SignedOut)
            return false
        }
        if (!persistPendingSession(expectedEpoch, issued)) return false
        return verifyPendingSession(expectedEpoch, issued.accessToken) != null
    }

    override suspend fun recoverAfterUnauthorized(observed: AccessTokenLease): AccessTokenLease? {
        val flight = mutex.withLock {
            if (observed.epoch != epoch) return null
            access?.let { current ->
                if (current.generation > observed.generation && mutableState.value == SessionState.Active) {
                    return current
                }
            }
            refreshFlight?.takeIf { it.epoch == observed.epoch && it.generation == observed.generation }?.let {
                return@withLock it.result
            }
            if (access?.generation != observed.generation) return null
            beginRefreshLocked(observed.epoch, observed.generation)
        }
        return flight.await()
    }

    suspend fun retrySessionValidation(): Boolean {
        val pair = mutex.withLock {
            if (mutableState.value !is SessionState.Restoring) return false
            (pendingSessionAccess ?: return false) to epoch
        }
        return verifyPendingSession(pair.second, pair.first) != null
    }

    /** Context replacement invalidates all late work from the previous epoch. */
    suspend fun invalidateContext(): Boolean = clearLocal(SessionState.ContextRequired)

    suspend fun logout(): LocalLogoutResult {
        val oldAccess = mutex.withLock { access?.value }
        val cleared = clearLocal(SessionState.SignedOut)
        if (!cleared) return LocalLogoutResult(false, false)
        val serverAcknowledged = if (oldAccess == null) {
            false
        } else {
            try {
                // Local state is already invalidated; remote revocation is bounded and best effort.
                withTimeout(5_000) { remote.signOut(oldAccess) }
                true
            } catch (_: TimeoutCancellationException) {
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
        return LocalLogoutResult(serverAcknowledged, true)
    }

    private suspend fun clearLocal(target: SessionState): Boolean = mutex.withLock {
        epoch++
        access = null
        pendingSessionAccess = null
        refreshFlight = null
        return try {
            credentialStore.clear()
            mutableState.value = target
            true
        } catch (_: Exception) {
            mutableState.value = SessionState.LocalProtectionError
            false
        }
    }

    private suspend fun beginRefresh(expectedEpoch: Long, observedGeneration: Long): Deferred<AccessTokenLease?> =
        mutex.withLock { beginRefreshLocked(expectedEpoch, observedGeneration) }

    private fun beginRefreshLocked(expectedEpoch: Long, observedGeneration: Long): Deferred<AccessTokenLease?> {
        refreshFlight?.takeIf { it.epoch == expectedEpoch && it.generation == observedGeneration }?.let {
            return it.result
        }
        val result = applicationScope.async(start = CoroutineStart.LAZY) {
            performRefresh(expectedEpoch)
        }
        refreshFlight = RefreshFlight(expectedEpoch, observedGeneration, result)
        result.start()
        return result
    }

    private suspend fun performRefresh(expectedEpoch: Long): AccessTokenLease? {
        val taken = try {
            mutex.withLock {
                if (epoch != expectedEpoch) return null
                access = null
                mutableState.value = SessionState.Restoring()
                // This commits IN_FLIGHT before R1 can be dispatched.
                credentialStore.takeForRefresh()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failReauthentication(expectedEpoch)
            return null
        }
        val oldCredential = (taken as? StoredRefreshCredential.Ready)?.credential ?: run {
            failReauthentication(expectedEpoch)
            return null
        }
        if (!isCurrent(expectedEpoch)) return null

        val issued = try {
            remote.refresh(oldCredential)
        } catch (cancelled: CancellationException) {
            // The durable IN_FLIGHT record remains unusable after process death.
            throw cancelled
        } catch (_: Exception) {
            // A lost response may follow committed server rotation. Never dispatch R1 again.
            failReauthentication(expectedEpoch)
            return null
        }
        if (!persistPendingSession(expectedEpoch, issued)) return null
        return verifyPendingSession(expectedEpoch, issued.accessToken)
    }

    private suspend fun persistPendingSession(expectedEpoch: Long, issued: IssuedNativeSession): Boolean {
        return try {
            mutex.withLock {
                if (epoch != expectedEpoch) return false
                credentialStore.writeReady(issued.refreshCredential)
                pendingSessionAccess = issued.accessToken
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failReauthentication(expectedEpoch)
            false
        }
    }

    private suspend fun verifyPendingSession(expectedEpoch: Long, token: String): AccessTokenLease? {
        val verified = try {
            remote.currentSession(token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AuthGatewayFailure.NetworkUnavailable) {
            setStateIfCurrent(expectedEpoch, SessionState.Restoring(canRetryConnection = true))
            return null
        } catch (_: Exception) {
            failReauthentication(expectedEpoch)
            return null
        }
        return mutex.withLock {
            if (epoch != expectedEpoch || pendingSessionAccess != token) return null
            if (!verified.hasAuthorizedContext) {
                mutableState.value = SessionState.ContextRequired
                return null
            }
            generation++
            AccessTokenLease(token, generation, epoch).also {
                access = it
                pendingSessionAccess = null
                mutableState.value = SessionState.Active
            }
        }
    }

    private suspend fun failReauthentication(expectedEpoch: Long) {
        mutex.withLock {
            if (epoch != expectedEpoch) return
            epoch++
            access = null
            pendingSessionAccess = null
            refreshFlight = null
            try {
                credentialStore.clear()
                mutableState.value = SessionState.ReauthenticationRequired
            } catch (_: Exception) {
                mutableState.value = SessionState.LocalProtectionError
            }
        }
    }

    private suspend fun setStateIfCurrent(expectedEpoch: Long, target: SessionState) {
        mutex.withLock { if (epoch == expectedEpoch) mutableState.value = target }
    }

    private suspend fun isCurrent(expectedEpoch: Long): Boolean = mutex.withLock { epoch == expectedEpoch }

    private data class RefreshFlight(
        val epoch: Long,
        val generation: Long,
        val result: Deferred<AccessTokenLease?>,
    )
}
