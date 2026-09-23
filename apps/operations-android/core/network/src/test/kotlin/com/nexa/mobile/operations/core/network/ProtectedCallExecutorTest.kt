package com.nexa.mobile.operations.core.network

import com.nexa.mobile.operations.core.auth.credentials.RefreshCredentialStore
import com.nexa.mobile.operations.core.auth.credentials.StoredRefreshCredential
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.NativeSignIn
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.mockwebserver.Dispatcher as ServerDispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedCallExecutorTest {
    @Test
    fun commandReplayRetainsIdentityPayloadAndOpaqueEtag() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("{}")
                    .addHeader("ETag", "\"7\"").addHeader("X-Correlation-ID", "server-correlation")
            )
            val endpoint = ApiEndpoint(server.url("/").toString())
            val source = FakeSource()
            val executor = ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), source)
            val command =
                ProtectedRequest(
                    ProtectedMethod.POST,
                    "/api/v1/technical-test",
                    "{\"value\":1}",
                    "opaque-key",
                    "\"7\""
                )

            val result = executor.execute(command) as ProtectedResult.Success

            assertEquals(200, result.status)
            assertEquals("\"7\"", result.etag)
            assertEquals("server-correlation", result.serverCorrelationId)
            assertEquals(1, source.recoverCount)
            val first = server.takeRequest()
            val replay = server.takeRequest()
            assertEquals("Bearer access-1", first.getHeader("Authorization"))
            assertEquals("Bearer access-2", replay.getHeader("Authorization"))
            assertEquals("opaque-key", first.getHeader("Idempotency-Key"))
            assertEquals(first.getHeader("Idempotency-Key"), replay.getHeader("Idempotency-Key"))
            assertEquals("\"7\"", first.getHeader("If-Match"))
            assertEquals(first.getHeader("If-Match"), replay.getHeader("If-Match"))
            assertEquals(first.body.readUtf8(), replay.body.readUtf8())
            assertNull(first.getHeader("X-Nexa-Client"))
            assertNull(first.getHeader("X-Nexa-Surface"))
            assertNull(first.getHeader("X-Nexa-Refresh-Token"))
            assertFalse(first.getHeader("X-Correlation-ID").isNullOrBlank())
        }
    }

    @Test
    fun replayed401NeverStartsAnotherRefresh() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(401))
            val endpoint = ApiEndpoint(server.url("/").toString())
            val source = FakeSource()
            val result = ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), source)
                .execute(
                    ProtectedRequest(ProtectedMethod.GET, "/api/v1/technical-test")
                ) as ProtectedResult.Failure
            assertEquals(FailureKind.AuthenticationRequired, result.error.kind)
            assertEquals(1, source.recoverCount)
            assertEquals(1, source.rejectCount)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun commandConnectionLossIsUnknownOutcomeWithoutAuthReplay() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val endpoint = ApiEndpoint(server.url("/").toString())
            val source = FakeSource()
            val result = ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), source)
                .execute(
                    ProtectedRequest(
                        ProtectedMethod.POST,
                        "/api/v1/technical-test",
                        "{}",
                        "opaque-key"
                    )
                ) as ProtectedResult.Failure
            assertEquals(FailureKind.UnknownOutcome, result.error.kind)
            assertEquals(0, source.recoverCount)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun twentyObservedHttp401sShareOneActualRefresh() = runBlocking {
        MockWebServer().use { server ->
            val oldArrivals = CountDownLatch(20)
            val oldRequests = AtomicInteger()
            val replayRequests = AtomicInteger()
            val refreshRequests = AtomicInteger()
            val dispatchedR1 = mutableListOf<String>()
            server.dispatcher = object : ServerDispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path) {
                        "/api/v1/authentication/sign-in" -> issued("access-1", "refresh-1")

                        "/api/v1/authentication/refresh" -> {
                            refreshRequests.incrementAndGet()
                            synchronized(dispatchedR1) {
                                dispatchedR1.add(
                                    request.getHeader("X-Nexa-Refresh-Token") ?: "missing"
                                )
                            }
                            issued("access-2", "refresh-2")
                        }

                        "/api/v1/session" -> MockResponse().setBody(
                            SESSION_JSON
                        ).addHeader("Content-Type", "application/json")

                        "/api/v1/technical-test" -> if (request.getHeader("Authorization") ==
                            "Bearer access-1"
                        ) {
                            oldRequests.incrementAndGet()
                            oldArrivals.countDown()
                            if (!oldArrivals.await(15, TimeUnit.SECONDS)) {
                                MockResponse().setResponseCode(500)
                            } else {
                                MockResponse().setResponseCode(401)
                            }
                        } else {
                            replayRequests.incrementAndGet()
                            MockResponse().setBody(
                                "{}"
                            ).addHeader("Content-Type", "application/json")
                        }

                        else -> MockResponse().setResponseCode(404)
                    }
            }
            server.start()
            val endpoint = ApiEndpoint(server.url("/").toString())
            val client = ApiHttpClient.create(endpoint).newBuilder()
                .dispatcher(
                    Dispatcher().apply {
                        maxRequestsPerHost = 32
                        maxRequests = 64
                    }
                )
                .build()
            val store = MemoryStore()
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val coordinator =
                    SessionCoordinator(store, NexaAuthGateway.create(endpoint, client), appScope)
                assertTrue(
                    coordinator.signIn(NativeSignIn("synthetic", "synthetic-password", "synthetic"))
                )
                val initial = coordinator.currentAccess()!!
                val executor = ProtectedCallExecutor(endpoint, client, coordinator)
                val calls = List(20) {
                    async(Dispatchers.IO) {
                        executor.execute(
                            ProtectedRequest(ProtectedMethod.GET, "/api/v1/technical-test")
                        )
                    }
                }
                val outcomes = calls.awaitAll()

                assertTrue(outcomes.all { it is ProtectedResult.Success })
                assertEquals(20, oldRequests.get())
                assertEquals(20, replayRequests.get())
                assertEquals(1, refreshRequests.get())
                assertEquals(
                    listOf("refresh-1"),
                    synchronized(dispatchedR1) {
                        dispatchedR1.toList()
                    }
                )
                assertEquals(StoredRefreshCredential.Ready("refresh-2"), store.value)
                assertEquals(1, store.takeCount)
                assertEquals(initial.generation + 1, coordinator.currentAccess()!!.generation)
                assertEquals(SessionState.Active, coordinator.sessionState.value)
            } finally {
                appScope.cancel()
            }
        }
    }

    private class FakeSource : AccessTokenSource {
        private val state = MutableStateFlow<SessionState>(SessionState.Active)
        override val sessionState: StateFlow<SessionState> = state
        private var lease = AccessTokenLease("access-1", 1, 1)
        var recoverCount = 0
        var rejectCount = 0

        override suspend fun currentAccess(): AccessTokenLease = lease

        override suspend fun recoverAfterUnauthorized(
            observed: AccessTokenLease
        ): AccessTokenLease {
            recoverCount++
            lease = AccessTokenLease("access-2", 2, 1)
            return lease
        }

        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) {
            rejectCount++
        }

        override suspend fun isEpochCurrent(epoch: Long): Boolean = epoch == 1L
    }

    private class MemoryStore : RefreshCredentialStore {
        var value: StoredRefreshCredential = StoredRefreshCredential.Missing
        var takeCount = 0

        override suspend fun read(): StoredRefreshCredential = value

        override suspend fun takeForRefresh(): StoredRefreshCredential {
            takeCount++
            return value.also { value = StoredRefreshCredential.InFlight }
        }

        override suspend fun writeReady(credential: String) {
            value =
                StoredRefreshCredential.Ready(credential)
        }

        override suspend fun clear() {
            value = StoredRefreshCredential.Missing
        }
    }
}
