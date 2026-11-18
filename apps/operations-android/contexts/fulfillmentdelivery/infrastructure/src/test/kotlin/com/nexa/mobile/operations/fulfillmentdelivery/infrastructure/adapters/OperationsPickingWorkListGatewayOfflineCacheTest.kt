package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import com.nexa.mobile.operations.core.auth.credentials.RefreshCredentialStore
import com.nexa.mobile.operations.core.auth.credentials.StoredRefreshCredential
import com.nexa.mobile.operations.core.auth.session.AuthRemoteGateway
import com.nexa.mobile.operations.core.auth.session.IssuedNativeSession
import com.nexa.mobile.operations.core.auth.session.NativeSignIn
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.ApiEndpoint
import com.nexa.mobile.operations.core.network.ApiHttpClient
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingWorkListCacheItem
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingWorkListCacheProjection
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingWorkListResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse.PickingWorkListCache
import com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse.PickingWorkListCacheRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse.PickingWorkListCacheWrite
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.PickingWorkItem
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.PickingWorkPage
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.NexaPickingGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.PickingWorkListItemProjection
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.PickingWorkListNetworkOutcome
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.PickingWorkListProjection
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.PickingWorkListTransport
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationsPickingWorkListGatewayOfflineCacheTest {
    @Test
    fun onlyServerConfirmedSummaryIsCachedAndNetworkFailureReturnsStaleReadOnlyProjection() =
        runTest {
            val cache = FakeCache()
            val gateway = gateway(
                FakePickingWorkListTransport(
                    PickingWorkListNetworkOutcome.Loaded(networkPage()),
                    PickingWorkListNetworkOutcome.NetworkUnavailable
                ),
                cache,
                activeSessions(backgroundScope)
            )
            val authority = authority()

            val online = gateway.list(authority, page = 0, size = 25)
            assertTrue(online is PickingWorkListResult.Loaded)
            assertEquals(1, cache.savedPages.size)

            val offline = gateway.list(authority, page = 0, size = 25)

            assertTrue(offline is PickingWorkListResult.Cached)
            val projection = (offline as PickingWorkListResult.Cached).value
            assertEquals(FULFILLMENT_ID, projection.items.single().fulfillmentId)
            assertEquals("ORDER-2048", projection.items.single().orderNumber)
            assertEquals(Instant.parse("2026-11-16T12:00:00Z"), projection.asOf)
            assertTrue(cache.loads.all { it.first == authority })
        }

    @Test
    fun permissionDenialFencesSubsequentNetworkFallbackUntilAnAuthoritativeRead() = runTest {
        val authority = authority()
        val cache = FakeCache().apply { values[authority to 0] = cachedPage() }
        val gateway = gateway(
            FakePickingWorkListTransport(
                PickingWorkListNetworkOutcome.ServiceUnavailable,
                PickingWorkListNetworkOutcome.PermissionDenied,
                PickingWorkListNetworkOutcome.NetworkUnavailable,
                PickingWorkListNetworkOutcome.Loaded(networkPage()),
                PickingWorkListNetworkOutcome.ServiceUnavailable,
                PickingWorkListNetworkOutcome.SessionInvalidated
            ),
            cache,
            activeSessions(backgroundScope)
        )

        assertTrue(gateway.list(authority, 0, 25) is PickingWorkListResult.Cached)
        assertEquals(PickingWorkListResult.PermissionDenied, gateway.list(authority, 0, 25))
        assertEquals(PickingWorkListResult.NetworkUnavailable, gateway.list(authority, 0, 25))
        assertEquals(1, cache.loads.size)
        assertEquals(1, cache.denials.size)
        assertTrue(gateway.list(authority, 0, 25) is PickingWorkListResult.Loaded)
        assertTrue(gateway.list(authority, 0, 25) is PickingWorkListResult.Cached)
        assertEquals(PickingWorkListResult.SessionInvalidated, gateway.list(authority, 0, 25))
        assertEquals(2, cache.loads.size)
    }

    @Test
    fun processFenceBlocksFallbackWhenDurableDenialWriteFails() = runTest {
        val authority = authority()
        val cache = FakeCache().apply {
            values[authority to 0] = cachedPage()
            denialWritesFail = true
        }
        val gateway = gateway(
            FakePickingWorkListTransport(
                PickingWorkListNetworkOutcome.PermissionDenied,
                PickingWorkListNetworkOutcome.NetworkUnavailable
            ),
            cache,
            activeSessions(backgroundScope)
        )

        assertEquals(PickingWorkListResult.PermissionDenied, gateway.list(authority, 0, 25))
        assertEquals(PickingWorkListResult.NetworkUnavailable, gateway.list(authority, 0, 25))
        assertTrue(cache.loads.isEmpty())
    }

    @Test
    fun authorityDriftDuringCacheWriteCannotReturnServerPageAsCurrent() = runTest {
        val sessions = activeSessions(backgroundScope)
        val cache = FakeCache().apply { afterSave = { sessions.logout() } }
        val gateway = gateway(
            FakePickingWorkListTransport(PickingWorkListNetworkOutcome.Loaded(networkPage())),
            cache,
            sessions
        )

        assertEquals(
            PickingWorkListResult.SessionInvalidated,
            gateway.list(authority(), 0, 25)
        )
        assertEquals(1, cache.savedPages.size)
    }

    @Test
    fun protectedReadClassifiesDisconnectAfterSuccessfulReadWithActiveSession() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, networkPageJson()))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val sessions = activeSessions(backgroundScope)
            val endpoint = ApiEndpoint(server.url("/").toString())
            val calls = ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), sessions)
            val request = ProtectedRequest(
                ProtectedMethod.GET,
                "/api/v1/fulfillments?page=0&size=25"
            )

            assertTrue(calls.execute(request) is ProtectedResult.Success)
            val result = calls.execute(request)
            val description = when (result) {
                is ProtectedResult.Failure ->
                    "${result.error.kind}/${result.error.httpStatus}/${result.error.problemCode}"

                is ProtectedResult.Success -> "success/${result.status}"
            }
            assertTrue(
                "unexpected protected read classification: $description",
                result is ProtectedResult.Failure
            )
            val failure = (result as ProtectedResult.Failure).error
            assertEquals(description, FailureKind.NetworkUnavailable, failure.kind)
            assertNull(failure.httpStatus)
            assertNull(failure.problemCode)
        }
    }

    @Test
    fun protectedReadClassifiesDisconnectAtStartWithActiveSession() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(200, networkPageJson()).addHeader("Connection", "close")
            )
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val sessions = activeSessions(backgroundScope)
            val endpoint = ApiEndpoint(server.url("/").toString())
            val calls = ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), sessions)
            val request = ProtectedRequest(
                ProtectedMethod.GET,
                "/api/v1/fulfillments?page=0&size=25"
            )

            assertTrue(calls.execute(request) is ProtectedResult.Success)
            val result = calls.execute(request)
            val description = when (result) {
                is ProtectedResult.Failure ->
                    "${result.error.kind}/${result.error.httpStatus}/${result.error.problemCode}"

                is ProtectedResult.Success -> "success/${result.status}"
            }
            assertTrue(
                "unexpected protected read classification for disconnect-at-start: $description",
                result is ProtectedResult.Failure
            )
            val failure = (result as ProtectedResult.Failure).error
            assertEquals(description, FailureKind.NetworkUnavailable, failure.kind)
            assertNull(failure.httpStatus)
            assertNull(failure.problemCode)
        }
    }

    @Test
    fun nexaTransportClassifiesDisconnectAfterSuccessfulReadWithActiveSession() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, networkPageJson()))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val sessions = activeSessions(backgroundScope)
            val endpoint = ApiEndpoint(server.url("/").toString())
            val transport = NexaPickingGateway(
                ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), sessions)
            )

            assertTrue(transport.workList() is PickingWorkListNetworkOutcome.Loaded)
            val outcome = transport.workList()
            assertTrue(
                "unexpected work-list transport outcome: $outcome; requests=${server.requestCount}",
                outcome is PickingWorkListNetworkOutcome.NetworkUnavailable
            )
        }
    }

    @Test
    fun nexaTransportClassifiesDisconnectAtStartWithActiveSession() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(200, networkPageJson()).addHeader("Connection", "close")
            )
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val sessions = activeSessions(backgroundScope)
            val endpoint = ApiEndpoint(server.url("/").toString())
            val transport = NexaPickingGateway(
                ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), sessions)
            )

            assertTrue(transport.workList() is PickingWorkListNetworkOutcome.Loaded)
            val outcome = transport.workList()
            assertTrue(
                "unexpected work-list transport outcome for disconnect-at-start: $outcome; requests=${server.requestCount}",
                outcome is PickingWorkListNetworkOutcome.NetworkUnavailable
            )
        }
    }

    private fun gateway(
        transport: PickingWorkListTransport,
        cache: PickingWorkListCache,
        sessions: SessionCoordinator
    ) = OperationsPickingWorkListGateway(sessions, transport, cache)

    private suspend fun activeSessions(scope: CoroutineScope): SessionCoordinator =
        SessionCoordinator(TestStore(), TestAuthGateway(), scope).also {
            assertTrue(it.signIn(NativeSignIn("synthetic-user", "synthetic-password", "workspace")))
        }

    private fun authority() = PickingAuthority(
        userId = USER_ID,
        tenantId = TENANT_ID,
        workspaceId = WORKSPACE_ID,
        membershipId = MEMBERSHIP_ID,
        permissions = setOf("fulfillment.read"),
        authorityEpoch = 1
    )

    private fun networkPage() = PickingWorkListProjection(
        items = listOf(
            PickingWorkListItemProjection(
                fulfillmentId = FULFILLMENT_ID,
                salesOrderId = SALES_ORDER_ID,
                status = "PICKING",
                version = 12,
                physicalAllocationId = ALLOCATION_ID,
                allocationVersion = 8,
                lineCount = 2,
                orderNumber = "ORDER-2048"
            )
        ),
        page = 0,
        size = 25,
        totalItems = 1,
        asOf = Instant.parse("2026-11-16T12:00:00Z")
    )

    private fun networkPageJson() =
        """{"items":[{"fulfillmentId":"$FULFILLMENT_ID","salesOrderId":"$SALES_ORDER_ID","status":"PICKING","version":12,"physicalAllocationId":"$ALLOCATION_ID","allocationVersion":8,"lineCount":2,"orderNumber":"ORDER-2048"}],"page":0,"size":25,"totalItems":1,"asOf":"2026-11-16T12:00:00Z"}"""

    private class FakePickingWorkListTransport(vararg outcomes: PickingWorkListNetworkOutcome) :
        PickingWorkListTransport {
        private val remainingOutcomes = outcomes.toMutableList()

        override suspend fun workList(page: Int, size: Int): PickingWorkListNetworkOutcome =
            remainingOutcomes.removeAt(0)
    }

    private class FakeCache : PickingWorkListCache {
        val values = mutableMapOf<Pair<PickingAuthority, Int>, PickingWorkListCacheProjection>()
        val loads = mutableListOf<Pair<PickingAuthority, Int>>()
        val savedPages = mutableListOf<PickingWorkPage>()
        val denials = mutableListOf<Pair<PickingAuthority, Long>>()
        val deniedAuthorities = mutableSetOf<PickingAuthority>()
        var denialWritesFail = false
        var afterSave: (suspend () -> Unit)? = null

        override suspend fun load(
            authority: PickingAuthority,
            page: Int
        ): PickingWorkListCacheRead {
            loads += authority to page
            return if (authority in deniedAuthorities) {
                PickingWorkListCacheRead.Unavailable
            } else {
                PickingWorkListCacheRead.Available(values[authority to page])
            }
        }

        override suspend fun saveServerConfirmedPage(
            authority: PickingAuthority,
            sessionEpoch: Long,
            page: PickingWorkPage
        ): PickingWorkListCacheWrite {
            savedPages += page
            values[authority to page.page] = cachedPage().copy(page = page.page)
            deniedAuthorities.remove(authority)
            afterSave?.invoke()
            return PickingWorkListCacheWrite.Saved
        }

        override suspend fun markDenied(
            authority: PickingAuthority,
            sessionEpoch: Long
        ): PickingWorkListCacheWrite {
            denials += authority to sessionEpoch
            if (denialWritesFail) return PickingWorkListCacheWrite.Unavailable
            deniedAuthorities += authority
            values.keys.removeAll { it.first == authority }
            return PickingWorkListCacheWrite.Saved
        }
    }

    private class TestStore : RefreshCredentialStore {
        private var credential: StoredRefreshCredential = StoredRefreshCredential.Missing

        override suspend fun read(): StoredRefreshCredential = credential

        override suspend fun takeForRefresh(): StoredRefreshCredential = credential.also {
            credential = StoredRefreshCredential.Missing
        }

        override suspend fun writeReady(credential: String) {
            this.credential = StoredRefreshCredential.Ready(credential)
        }

        override suspend fun clear() {
            credential = StoredRefreshCredential.Missing
        }
    }

    private class TestAuthGateway : AuthRemoteGateway {
        override suspend fun signIn(input: NativeSignIn) =
            IssuedNativeSession("synthetic-access", "synthetic-refresh")

        override suspend fun refresh(credential: String) =
            IssuedNativeSession("synthetic-access-refreshed", "synthetic-refresh-refreshed")

        override suspend fun currentSession(accessToken: String) = VerifiedSession(
            hasAuthorizedContext = true,
            userId = USER_ID,
            tenantId = TENANT_ID,
            workspaceId = WORKSPACE_ID,
            membershipId = MEMBERSHIP_ID,
            permissions = setOf("fulfillment.read")
        )

        override suspend fun signOut(accessToken: String) = Unit
    }

    private companion object {
        const val USER_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413001"
        const val TENANT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413002"
        const val WORKSPACE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413003"
        const val MEMBERSHIP_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413004"
        const val FULFILLMENT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413005"
        const val SALES_ORDER_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413006"
        const val ALLOCATION_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413007"

        fun cachedPage() = PickingWorkListCacheProjection(
            items = listOf(
                PickingWorkListCacheItem(
                    FULFILLMENT_ID,
                    SALES_ORDER_ID,
                    "ORDER-2048",
                    "PICKING",
                    12,
                    2
                )
            ),
            page = 0,
            size = 25,
            totalItems = 1,
            asOf = Instant.parse("2026-11-16T12:00:00Z")
        )

        fun jsonResponse(status: Int, body: String) = MockResponse()
            .setResponseCode(status)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)
    }
}
