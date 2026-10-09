package com.nexa.mobile.operations.customerbuyerrelationships.infrastructure.adapters

import com.nexa.mobile.operations.core.auth.credentials.RefreshCredentialStore
import com.nexa.mobile.operations.core.auth.credentials.StoredRefreshCredential
import com.nexa.mobile.operations.core.auth.session.AuthRemoteGateway
import com.nexa.mobile.operations.core.auth.session.IssuedNativeSession
import com.nexa.mobile.operations.core.auth.session.NativeSignIn
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.ApiEndpoint
import com.nexa.mobile.operations.core.network.ApiHttpClient
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.customerbuyerrelationships.application.commercial.CustomerResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationsCustomerGatewayInvalidationTest {
    @Test
    fun currentInvalidContextResponseInvalidatesTheMatchingSession() = runTest {
        val sessions = activeSessions(backgroundScope, "workspace-b")
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(403, "ACCESS_CONTEXT_INVALID"))
            val gateway = customerGateway(server, sessions)

            assertEquals(
                CustomerResult.ContextInvalidated,
                gateway.search(authority("workspace-b", 2), "customer", 0)
            )
            assertEquals(SessionState.ContextRequired, sessions.sessionState.value)
            assertEquals(null, sessions.currentAccess())
            assertEquals(null, sessions.verifiedSession.value)
        }
    }

    @Test
    fun ordinaryForbiddenResponseDoesNotInvalidateTheActiveSession() = runTest {
        val sessions = activeSessions(backgroundScope, "workspace-b")
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(403, "PERMISSION_DENIED"))
            val gateway = customerGateway(server, sessions)

            assertEquals(
                CustomerResult.PermissionDenied,
                gateway.search(authority("workspace-b", 2), "customer", 0)
            )
            assertEquals(SessionState.Active, sessions.sessionState.value)
            assertNotNull(sessions.currentAccess())
            assertEquals("workspace-b", sessions.verifiedSession.value?.workspaceId)
        }
    }

    @Test
    fun lateInvalidContextResponseFromWorkspaceADoesNotInvalidateWorkspaceB() = runTest {
        val sessions = activeSessions(backgroundScope, "workspace-a")
        val requestReceived = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestReceived.countDown()
                    if (!releaseResponse.await(5, TimeUnit.SECONDS)) {
                        return MockResponse().setSocketPolicy(
                            okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START
                        )
                    }
                    return problemResponse(403, "ACCESS_CONTEXT_INVALID")
                }
            }
            server.start()
            val gateway = customerGateway(server, sessions)
            val responseFromA = async {
                gateway.search(authority("workspace-a", 1), "customer", 0)
            }

            try {
                assertTrue(
                    withContext(Dispatchers.IO) {
                        requestReceived.await(5, TimeUnit.SECONDS)
                    }
                )
                val request = server.takeRequest(1, TimeUnit.SECONDS)
                assertNotNull(request)
                assertEquals("Bearer access-workspace-a", request?.getHeader("Authorization"))

                assertTrue(sessions.signIn(signIn("workspace-b")))
                releaseResponse.countDown()

                assertEquals(CustomerResult.PermissionDenied, responseFromA.await())
                assertEquals(SessionState.Active, sessions.sessionState.value)
                assertEquals("workspace-b", sessions.verifiedSession.value?.workspaceId)
                assertNotNull(sessions.currentAccess())
            } finally {
                releaseResponse.countDown()
            }
        }
    }

    private fun customerGateway(
        server: MockWebServer,
        sessions: SessionCoordinator
    ): OperationsCustomerGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return OperationsCustomerGateway(
            sessions,
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), sessions)
        )
    }

    private suspend fun activeSessions(
        scope: CoroutineScope,
        workspaceId: String
    ): SessionCoordinator = SessionCoordinator(TestStore(), TestAuthGateway(), scope).also {
        assertTrue(it.signIn(signIn(workspaceId)))
    }

    private fun signIn(workspaceId: String) = NativeSignIn(
        identifier = "synthetic-user",
        password = "synthetic-password",
        workspaceSlug = workspaceId
    )

    private fun authority(workspaceId: String, authorityEpoch: Long) = CommercialAuthority(
        userId = "user",
        tenantId = "tenant",
        workspaceId = workspaceId,
        membershipId = "membership-$workspaceId",
        permissions = setOf("client.read"),
        authorityEpoch = authorityEpoch
    )

    private fun problemResponse(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/problem+json")
        .setBody("""{"status":$status,"code":"$code","category":"CLIENT_ERROR"}""")

    private class TestStore : RefreshCredentialStore {
        private var value: StoredRefreshCredential = StoredRefreshCredential.Missing

        override suspend fun read(): StoredRefreshCredential = value

        override suspend fun takeForRefresh(): StoredRefreshCredential = value.also {
            if (it is StoredRefreshCredential.Ready) value = StoredRefreshCredential.InFlight
        }

        override suspend fun writeReady(credential: String) {
            value = StoredRefreshCredential.Ready(credential)
        }

        override suspend fun clear() {
            value = StoredRefreshCredential.Missing
        }
    }

    private class TestAuthGateway : AuthRemoteGateway {
        override suspend fun signIn(input: NativeSignIn) = IssuedNativeSession(
            "access-${input.workspaceSlug}",
            "refresh-${input.workspaceSlug}"
        )

        override suspend fun refresh(credential: String) =
            IssuedNativeSession("access-refreshed", "refresh-refreshed")

        override suspend fun currentSession(accessToken: String): VerifiedSession {
            val workspaceId = accessToken.removePrefix("access-")
            return VerifiedSession(
                hasAuthorizedContext = true,
                userId = "user",
                tenantId = "tenant",
                workspaceId = workspaceId,
                membershipId = "membership-$workspaceId",
                permissions = setOf("client.read")
            )
        }

        override suspend fun signOut(accessToken: String) = Unit
    }
}
