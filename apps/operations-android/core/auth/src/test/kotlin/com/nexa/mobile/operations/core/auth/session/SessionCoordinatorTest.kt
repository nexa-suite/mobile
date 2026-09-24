package com.nexa.mobile.operations.core.auth.session

import com.nexa.mobile.operations.core.auth.credentials.RefreshCredentialStore
import com.nexa.mobile.operations.core.auth.credentials.StoredRefreshCredential
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCoordinatorTest {
    @Test
    fun concurrentUnauthorizedCallersShareOneRotationAndAdvanceGenerationOnce() = runTest {
        val store = FakeStore()
        val gateway = FakeGateway()
        val coordinator = SessionCoordinator(store, gateway, backgroundScope)
        assertTrue(coordinator.signIn(NativeSignIn("synthetic", "synthetic-password", "synthetic")))
        val old = coordinator.currentAccess()!!
        gateway.refreshGate = CompletableDeferred()

        val waiting = List(20) { async { coordinator.recoverAfterUnauthorized(old) } }
        runCurrent()
        assertEquals(1, gateway.refreshCalls)
        assertEquals(listOf("synthetic-r1"), gateway.dispatchedRefreshCredentials)
        assertEquals(StoredRefreshCredential.InFlight, store.value)
        assertEquals(1, store.takeCalls)

        gateway.refreshGate!!.complete(IssuedNativeSession("synthetic-access-2", "synthetic-r2"))
        runCurrent()
        val results = waiting.awaitAll()
        assertTrue(results.all { it?.generation == old.generation + 1 })
        assertTrue(results.all { it?.value == "synthetic-access-2" })
        assertEquals(1, gateway.refreshCalls)
        assertEquals(StoredRefreshCredential.Ready("synthetic-r2"), store.value)
        assertEquals(1, store.takeCalls)
        assertEquals(SessionState.Active, coordinator.sessionState.value)
    }

    @Test
    fun cancellingAWaiterDoesNotCancelTheApplicationRefresh() = runTest {
        val gateway = FakeGateway().apply { refreshGate = CompletableDeferred() }
        val coordinator = SessionCoordinator(FakeStore(), gateway, backgroundScope)
        coordinator.signIn(NativeSignIn("synthetic", "synthetic-password", "synthetic"))
        val old = coordinator.currentAccess()!!

        val cancelled = async { coordinator.recoverAfterUnauthorized(old) }
        val waiting = async { coordinator.recoverAfterUnauthorized(old) }
        runCurrent()
        cancelled.cancel()
        gateway.refreshGate!!.complete(IssuedNativeSession("synthetic-access-2", "synthetic-r2"))
        runCurrent()

        assertEquals(1, gateway.refreshCalls)
        assertEquals(old.generation + 1, waiting.await()!!.generation)
        assertEquals(SessionState.Active, coordinator.sessionState.value)
    }

    @Test
    fun ambiguousRefreshNeverReusesPreviousCredential() = runTest {
        val store = FakeStore()
        val gateway = FakeGateway().apply { refreshFailure = AuthGatewayFailure.AmbiguousOutcome() }
        val coordinator = SessionCoordinator(store, gateway, backgroundScope)
        coordinator.signIn(NativeSignIn("synthetic", "synthetic-password", "synthetic"))
        val old = coordinator.currentAccess()!!

        assertNull(coordinator.recoverAfterUnauthorized(old))
        assertNull(coordinator.recoverAfterUnauthorized(old))
        assertEquals(1, gateway.refreshCalls)
        assertEquals(listOf("synthetic-r1"), gateway.dispatchedRefreshCredentials)
        assertEquals(StoredRefreshCredential.Missing, store.value)
        assertEquals(SessionState.ReauthenticationRequired, coordinator.sessionState.value)
    }

    @Test
    fun definitiveRefreshRejectionClearsLocalMaterial() = runTest {
        val store = FakeStore()
        val gateway = FakeGateway().apply {
            refreshFailure =
                AuthGatewayFailure.DefinitiveRejection()
        }
        val coordinator = SessionCoordinator(store, gateway, backgroundScope)
        coordinator.signIn(NativeSignIn("synthetic", "synthetic-password", "synthetic"))
        val old = coordinator.currentAccess()!!

        assertNull(coordinator.recoverAfterUnauthorized(old))
        assertEquals(1, gateway.refreshCalls)
        assertEquals(StoredRefreshCredential.Missing, store.value)
        assertEquals(SessionState.ReauthenticationRequired, coordinator.sessionState.value)
    }

    @Test
    fun logoutDiscardsLateRefreshAndDoesNotResurrectSession() = runTest {
        val store = FakeStore()
        val gateway = FakeGateway().apply { refreshGate = CompletableDeferred() }
        val coordinator = SessionCoordinator(store, gateway, backgroundScope)
        coordinator.signIn(NativeSignIn("synthetic", "synthetic-password", "synthetic"))
        val old = coordinator.currentAccess()!!
        val waiting = async { coordinator.recoverAfterUnauthorized(old) }
        runCurrent()

        val logout = coordinator.logout()
        assertTrue(logout.localMaterialCleared)
        gateway.refreshGate!!.complete(
            IssuedNativeSession("synthetic-late-access", "synthetic-late-r2")
        )
        runCurrent()

        assertNull(waiting.await())
        assertNull(coordinator.currentAccess())
        assertEquals(StoredRefreshCredential.Missing, store.value)
        assertEquals(SessionState.SignedOut, coordinator.sessionState.value)
    }

    @Test
    fun contextEpochChangeDiscardsLateRefresh() = runTest {
        val store = FakeStore()
        val gateway = FakeGateway().apply { refreshGate = CompletableDeferred() }
        val coordinator = SessionCoordinator(store, gateway, backgroundScope)
        coordinator.signIn(NativeSignIn("synthetic", "synthetic-password", "synthetic"))
        val old = coordinator.currentAccess()!!
        val waiting = async { coordinator.recoverAfterUnauthorized(old) }
        runCurrent()

        assertTrue(coordinator.invalidateContext())
        gateway.refreshGate!!.complete(
            IssuedNativeSession("synthetic-late-access", "synthetic-late-r2")
        )
        runCurrent()

        assertNull(waiting.await())
        assertEquals(SessionState.ContextRequired, coordinator.sessionState.value)
        assertEquals(StoredRefreshCredential.Missing, store.value)
    }

    @Test
    fun inFlightColdStartRequiresReauthenticationWithoutDispatch() = runTest {
        val store = FakeStore(StoredRefreshCredential.InFlight)
        val gateway = FakeGateway()
        val coordinator = SessionCoordinator(store, gateway, backgroundScope)

        coordinator.restore()

        assertEquals(0, gateway.refreshCalls)
        assertEquals(StoredRefreshCredential.Missing, store.value)
        assertEquals(SessionState.ReauthenticationRequired, coordinator.sessionState.value)
    }

    @Test
    fun readyColdStartRestoresOnlyAfterServerSessionConfirmation() = runTest {
        val store = FakeStore(StoredRefreshCredential.Ready("synthetic-r1"))
        val gateway = FakeGateway()
        val coordinator = SessionCoordinator(store, gateway, backgroundScope)

        coordinator.restore()

        assertEquals(1, gateway.refreshCalls)
        assertEquals(listOf("synthetic-r1"), gateway.dispatchedRefreshCredentials)
        assertEquals(StoredRefreshCredential.Ready("synthetic-r2"), store.value)
        assertEquals(1, gateway.sessionCalls)
        assertEquals(SessionState.Active, coordinator.sessionState.value)
        assertEquals(1L, coordinator.currentAccess()!!.generation)
    }

    @Test
    fun unavailableSessionCheckKeepsProtectedContentHiddenUntilRetry() = runTest {
        val store = FakeStore(StoredRefreshCredential.Ready("synthetic-r1"))
        val gateway = FakeGateway().apply { sessionUnavailable = true }
        val coordinator = SessionCoordinator(store, gateway, backgroundScope)

        coordinator.restore()
        assertNull(coordinator.currentAccess())
        assertEquals(
            SessionState.Restoring(canRetryConnection = true),
            coordinator.sessionState.value
        )
        assertEquals(StoredRefreshCredential.Ready("synthetic-r2"), store.value)

        gateway.sessionUnavailable = false
        assertTrue(coordinator.retrySessionValidation())
        assertEquals(1, gateway.refreshCalls)
        assertEquals(SessionState.Active, coordinator.sessionState.value)
    }

    @Test
    fun explicitLogoutWithAccessSignsOutOnceWithoutRefreshing() = runTest {
        val store = FakeStore()
        val gateway = FakeGateway()
        val coordinator = SessionCoordinator(store, gateway, backgroundScope)
        coordinator.signIn(NativeSignIn("synthetic", "synthetic-password", "synthetic"))

        val outcome = coordinator.logout()

        assertTrue(outcome.localMaterialCleared)
        assertTrue(outcome.serverSignOutAcknowledged)
        assertEquals(1, gateway.signOutCalls)
        assertEquals(0, gateway.refreshCalls)
        assertNull(coordinator.currentAccess())
        assertEquals(StoredRefreshCredential.Missing, store.value)
    }

    @Test
    fun secondUnauthorizedResponseRejectsOnlyTheObservedGeneration() = runTest {
        val store = FakeStore()
        val coordinator = SessionCoordinator(store, FakeGateway(), backgroundScope)
        coordinator.signIn(NativeSignIn("synthetic", "synthetic-password", "synthetic"))
        val old = coordinator.currentAccess()!!
        val replacement = coordinator.recoverAfterUnauthorized(old)!!

        coordinator.rejectCurrentAccess(old)
        assertEquals(SessionState.Active, coordinator.sessionState.value)
        coordinator.rejectCurrentAccess(replacement)

        assertEquals(SessionState.ReauthenticationRequired, coordinator.sessionState.value)
        assertEquals(StoredRefreshCredential.Missing, store.value)
        assertNull(coordinator.currentAccess())
    }

    @Test
    fun secretBearingValuesUseRedactedStringRepresentation() {
        assertFalse(AccessTokenLease("secret-access", 1, 1).toString().contains("secret-access"))
        assertFalse(
            NativeSignIn(
                "synthetic",
                "secret-password",
                "synthetic"
            ).toString().contains("secret-password")
        )
        assertFalse(
            IssuedNativeSession(
                "secret-access",
                "secret-refresh"
            ).toString().contains("secret-refresh")
        )
        assertFalse(
            StoredRefreshCredential.Ready("secret-refresh").toString().contains("secret-refresh")
        )
    }

    private class FakeStore(initial: StoredRefreshCredential = StoredRefreshCredential.Missing) :
        RefreshCredentialStore {
        var value = initial
        var takeCalls = 0

        override suspend fun read(): StoredRefreshCredential = value

        override suspend fun takeForRefresh(): StoredRefreshCredential {
            takeCalls++
            return value.also {
                if (it is StoredRefreshCredential.Ready) value = StoredRefreshCredential.InFlight
            }
        }

        override suspend fun writeReady(credential: String) {
            value = StoredRefreshCredential.Ready(credential)
        }

        override suspend fun clear() {
            value = StoredRefreshCredential.Missing
        }
    }

    private class FakeGateway : AuthRemoteGateway {
        var refreshCalls = 0
        var sessionCalls = 0
        var signOutCalls = 0
        var refreshGate: CompletableDeferred<IssuedNativeSession>? = null
        var refreshFailure: Exception? = null
        var sessionUnavailable = false
        val dispatchedRefreshCredentials = mutableListOf<String>()

        override suspend fun signIn(input: NativeSignIn): IssuedNativeSession =
            IssuedNativeSession("synthetic-access-1", "synthetic-r1")

        override suspend fun refresh(credential: String): IssuedNativeSession {
            refreshCalls++
            dispatchedRefreshCredentials.add(credential)
            refreshFailure?.let { throw it }
            return refreshGate?.await() ?: IssuedNativeSession("synthetic-access-2", "synthetic-r2")
        }

        override suspend fun currentSession(accessToken: String): VerifiedSession {
            sessionCalls++
            if (sessionUnavailable) throw AuthGatewayFailure.NetworkUnavailable()
            return VerifiedSession(hasAuthorizedContext = true)
        }

        override suspend fun signOut(accessToken: String) {
            signOutCalls++
        }
    }
}
