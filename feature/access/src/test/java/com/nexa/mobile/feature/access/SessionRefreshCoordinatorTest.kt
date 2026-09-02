package com.nexa.mobile.feature.access

import com.nexa.mobile.core.network.ApiError
import com.nexa.mobile.core.network.ApiErrorCategory
import com.nexa.mobile.core.network.ApiResult
import com.nexa.mobile.core.storage.SessionMaterial
import com.nexa.mobile.core.storage.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRefreshCoordinatorTest {
    @Test
    fun requestSuccessDoesNotRefresh() = runTest {
        val store = FakeSessionStore(initialSession)
        val refresher = FakeSessionRefresher(ApiResult.Success(rotatedSession))
        val usedTokens = mutableListOf<String>()
        val coordinator = coordinator(store, refresher)

        val result = coordinator.execute(retrySafety = RetrySafety.SAFE) { accessToken ->
            usedTokens += accessToken
            ApiResult.Success("ok")
        }

        assertEquals(ApiResult.Success("ok"), result)
        assertEquals(listOf(initialSession.accessToken), usedTokens)
        assertEquals(0, refresher.calls)
        assertEquals(initialSession, store.material)
    }

    @Test
    fun unauthorizedRequestRefreshesAndRetriesWithRotatedToken() = runTest {
        val store = FakeSessionStore(initialSession)
        val refresher = FakeSessionRefresher(ApiResult.Success(rotatedSession))
        val usedTokens = mutableListOf<String>()
        val coordinator = coordinator(store, refresher)

        val result = coordinator.execute(retrySafety = RetrySafety.SAFE) { accessToken ->
            usedTokens += accessToken
            if (usedTokens.size == 1) unauthorized() else ApiResult.Success("ok")
        }

        assertEquals(ApiResult.Success("ok"), result)
        assertEquals(listOf(initialSession.accessToken, rotatedSession.accessToken), usedTokens)
        assertEquals(1, refresher.calls)
        assertEquals(listOf(initialSession), refresher.received)
    }

    @Test
    fun concurrentUnauthorizedRequestsShareOneRefresh() = runTest {
        val store = FakeSessionStore(initialSession)
        val refresher = FakeSessionRefresher(ApiResult.Success(rotatedSession))
        val usedTokens = mutableListOf<String>()
        val firstAttempts = CompletableDeferred<Unit>()
        val releaseFirstAttempts = CompletableDeferred<Unit>()
        var initialAttempts = 0
        val coordinator = coordinator(store, refresher)

        suspend fun operation(accessToken: String): ApiResult<String> {
            usedTokens += accessToken
            if (accessToken == initialSession.accessToken) {
                initialAttempts += 1
                if (initialAttempts == 2) firstAttempts.complete(Unit)
                releaseFirstAttempts.await()
                return unauthorized()
            }
            return ApiResult.Success("ok")
        }

        coroutineScope {
            val requests = listOf(
                async { coordinator.execute(retrySafety = RetrySafety.SAFE, operation = ::operation) },
                async { coordinator.execute(retrySafety = RetrySafety.SAFE, operation = ::operation) },
            )
            firstAttempts.await()
            releaseFirstAttempts.complete(Unit)

            assertEquals(listOf(ApiResult.Success("ok"), ApiResult.Success("ok")), requests.awaitAll())
        }
        assertEquals(1, refresher.calls)
        assertEquals(rotatedSession, store.material)
        assertEquals(2, usedTokens.count { it == initialSession.accessToken })
        assertEquals(2, usedTokens.count { it == rotatedSession.accessToken })
        val rotationIndex = usedTokens.indexOf(rotatedSession.accessToken)
        assertTrue(rotationIndex >= 0)
        assertTrue(usedTokens.drop(rotationIndex).all { it == rotatedSession.accessToken })
    }

    @Test
    fun rotatedSessionMaterialIsStoredBeforeRetry() = runTest {
        val store = FakeSessionStore(initialSession)
        val refresher = FakeSessionRefresher(ApiResult.Success(rotatedSession))
        val coordinator = coordinator(store, refresher)

        coordinator.execute(retrySafety = RetrySafety.SAFE) { accessToken ->
            if (accessToken == initialSession.accessToken) unauthorized() else {
                assertEquals(rotatedSession, store.material)
                ApiResult.Success(Unit)
            }
        }

        assertEquals(listOf(rotatedSession), store.writes)
        assertEquals(rotatedSession, store.material)
    }

    @Test
    fun refreshFailureClearsLocalSession() = runTest {
        val store = FakeSessionStore(initialSession)
        val refreshFailure = ApiResult.Failure(
            ApiError(category = ApiErrorCategory.SERVER, status = 503, code = "REFRESH_UNAVAILABLE"),
        )
        val refresher = FakeSessionRefresher(refreshFailure)
        val usedTokens = mutableListOf<String>()
        val coordinator = coordinator(store, refresher)

        val result = coordinator.execute(retrySafety = RetrySafety.SAFE) { accessToken ->
            usedTokens += accessToken
            unauthorized()
        }

        assertEquals(refreshFailure, result)
        assertEquals(1, refresher.calls)
        assertEquals(listOf(initialSession.accessToken), usedTokens)
        assertEquals(1, store.clearCalls)
        assertEquals(null, store.material)
    }

    @Test
    fun storageFailureFailsClosed() = runTest {
        val store = FakeSessionStore(initialSession, failOnRead = true)
        val refresher = FakeSessionRefresher(ApiResult.Success(rotatedSession))
        var operationCalls = 0
        val coordinator = coordinator(store, refresher)

        val result = coordinator.execute(retrySafety = RetrySafety.SAFE) {
            operationCalls += 1
            ApiResult.Success("must-not-run")
        }

        assertTrue(result is ApiResult.Failure)
        val failure = result as ApiResult.Failure
        assertEquals(ApiErrorCategory.UNKNOWN, failure.error.category)
        assertEquals("SESSION_STORAGE_FAILURE", failure.error.code)
        assertEquals(0, operationCalls)
        assertEquals(0, refresher.calls)
        assertEquals(1, store.clearCalls)
        assertEquals(null, store.material)
    }

    @Test
    fun storageWriteFailureClearsAndDoesNotRetry() = runTest {
        val store = FakeSessionStore(initialSession, failOnWrite = true)
        val refresher = FakeSessionRefresher(ApiResult.Success(rotatedSession))
        val usedTokens = mutableListOf<String>()
        val coordinator = coordinator(store, refresher)

        val result = coordinator.execute(retrySafety = RetrySafety.SAFE) { accessToken ->
            usedTokens += accessToken
            unauthorized()
        }

        assertTrue(result is ApiResult.Failure)
        val failure = result as ApiResult.Failure
        assertEquals("SESSION_STORAGE_FAILURE", failure.error.code)
        assertEquals(listOf(initialSession.accessToken), usedTokens)
        assertEquals(1, refresher.calls)
        assertEquals(1, store.writeCalls)
        assertEquals(1, store.clearCalls)
        assertEquals(null, store.material)
    }

    @Test
    fun unauthorizedRetryHappensOnlyOnce() = runTest {
        val store = FakeSessionStore(initialSession)
        val refresher = FakeSessionRefresher(ApiResult.Success(rotatedSession))
        val usedTokens = mutableListOf<String>()
        val coordinator = coordinator(store, refresher)

        val result = coordinator.execute(retrySafety = RetrySafety.SAFE) { accessToken ->
            usedTokens += accessToken
            unauthorized()
        }

        assertTrue(result is ApiResult.Failure)
        val failure = result as ApiResult.Failure
        assertEquals(401, failure.error.status)
        assertEquals(1, refresher.calls)
        assertEquals(listOf(initialSession.accessToken, rotatedSession.accessToken), usedTokens)
        assertEquals(2, usedTokens.size)
        assertEquals(1, store.clearCalls)
        assertEquals(null, store.material)
    }

    @Test
    fun unsafeUnauthorizedRequestIsNotRetriedOrRefreshed() = runTest {
        val store = FakeSessionStore(initialSession)
        val refresher = FakeSessionRefresher(ApiResult.Success(rotatedSession))
        val usedTokens = mutableListOf<String>()
        val coordinator = coordinator(store, refresher)

        val result = coordinator.execute(retrySafety = RetrySafety.UNSAFE) { accessToken ->
            usedTokens += accessToken
            unauthorized()
        }

        assertTrue(result is ApiResult.Failure)
        assertEquals(1, usedTokens.size)
        assertEquals(0, refresher.calls)
        assertEquals(initialSession, store.material)
    }

    private fun coordinator(
        store: FakeSessionStore,
        refresher: FakeSessionRefresher,
    ) = SessionRefreshCoordinator(store, refresher::refresh)

    private fun unauthorized(): ApiResult.Failure = ApiResult.Failure(
        ApiError(category = ApiErrorCategory.UNAUTHORIZED, status = 401, code = "UNAUTHORIZED"),
    )

    private class FakeSessionRefresher(
        private val result: ApiResult<SessionMaterial>,
    ) {
        var calls = 0
        val received = mutableListOf<SessionMaterial>()

        suspend fun refresh(session: SessionMaterial): ApiResult<SessionMaterial> {
            calls += 1
            received += session
            return result
        }
    }

    private class FakeSessionStore(
        initial: SessionMaterial?,
        private val failOnRead: Boolean = false,
        private val failOnWrite: Boolean = false,
    ) : SessionStore {
        var material: SessionMaterial? = initial
        val writes = mutableListOf<SessionMaterial>()
        var writeCalls = 0
        var clearCalls = 0

        override suspend fun read(): SessionMaterial? {
            if (failOnRead) error("simulated storage read failure")
            return material
        }

        override suspend fun write(material: SessionMaterial) {
            writeCalls += 1
            if (failOnWrite) error("simulated storage write failure")
            this.material = material
            writes += material
        }

        override suspend fun clear() {
            clearCalls += 1
            material = null
        }
    }

    private companion object {
        val initialSession = SessionMaterial(
            accessToken = "access-token-old",
            refreshToken = "refresh-token-old",
            accessTokenExpiresAtEpochSeconds = 1_900_000_000,
            surface = "PLATFORM",
        )

        val rotatedSession = SessionMaterial(
            accessToken = "access-token-new",
            refreshToken = "refresh-token-new",
            accessTokenExpiresAtEpochSeconds = 1_900_000_600,
            surface = "PLATFORM",
        )
    }
}
