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
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerRead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedCommercialCustomerQueryTest {
    @Test
    fun detailPreservesServerRelationshipFactsAndUsesTheScopedProtectedRoute() = runTest {
        val sessions = activeSessions(backgroundScope)
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, customerBody(status = "SUSPENDED", version = 7)))
            val query = query(server, sessions)

            val result = query.detail(CUSTOMER_ID)
            val request = server.takeRequest()

            assertEquals("GET", request.method)
            assertEquals("/api/v1/client-accounts/$CUSTOMER_ID", request.path)
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            assertTrue(result is CommercialCustomerRead.Detail)
            val facts = (result as CommercialCustomerRead.Detail).value
            assertEquals("North Star Foods", facts.businessName)
            assertEquals("SUSPENDED", facts.status)
            assertEquals(7L, facts.version)
        }
    }

    @Test
    fun mismatchedCustomerAndMalformedOrInvalidDetailsDoNotBecomeCustomerFacts() = runTest {
        val sessions = activeSessions(backgroundScope)
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(200, customerBody(id = OTHER_CUSTOMER_ID)))
            server.enqueue(jsonResponse(200, "not-json"))
            server.enqueue(jsonResponse(200, customerBody(status = "UNKNOWN")))
            server.enqueue(jsonResponse(200, customerBody(version = -1)))
            val query = query(server, sessions)

            repeat(4) {
                assertEquals(CommercialCustomerRead.Unavailable, query.detail(CUSTOMER_ID))
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun authenticationAuthorizationNotFoundAndServerErrorsNeverReturnCustomerFacts() = runTest {
        val sessions = activeSessions(backgroundScope)
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(jsonResponse(200, "not-json")) // successful replay after the 401
            server.enqueue(problemResponse(403, "PERMISSION_DENIED"))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(problemResponse(500, "INTERNAL_ERROR"))
            val query = query(server, sessions)

            repeat(4) {
                assertEquals(CommercialCustomerRead.Unavailable, query.detail(CUSTOMER_ID))
            }
            assertEquals(SessionState.Active, sessions.sessionState.value)
            assertEquals(5, server.requestCount)
        }
    }

    @Test
    fun invalidAccessContextIsPropagatedAndInvalidatesTheObservedSession() = runTest {
        val sessions = activeSessions(backgroundScope)
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(403, "ACCESS_CONTEXT_INVALID"))

            assertEquals(
                CommercialCustomerRead.ContextInvalidated,
                query(server, sessions).detail(CUSTOMER_ID)
            )
            assertEquals(SessionState.ContextRequired, sessions.sessionState.value)
            assertEquals(null, sessions.currentAccess())
        }
    }

    @Test
    fun invalidCustomerIdentifierDoesNotDispatchAProtectedRead() = runTest {
        val sessions = activeSessions(backgroundScope)
        MockWebServer().use { server ->
            server.start()

            assertEquals(
                CommercialCustomerRead.Unavailable,
                query(server, sessions).detail("../customer")
            )
            assertEquals(0, server.requestCount)
        }
    }

    private fun query(
        server: MockWebServer,
        sessions: SessionCoordinator
    ): AuthorizedCommercialCustomerQuery {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return AuthorizedCommercialCustomerQuery(
            sessions,
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), sessions)
        )
    }

    private suspend fun activeSessions(scope: CoroutineScope): SessionCoordinator {
        val sessions = SessionCoordinator(TestRefreshStore(), TestAuthGateway(), scope)
        assertTrue(sessions.signIn(NativeSignIn("synthetic", "synthetic", "workspace")))
        return sessions
    }

    private fun customerBody(
        id: String = CUSTOMER_ID,
        status: String = "ACTIVE",
        version: Long = 4
    ): String = """
        {
          "id":"$id",
          "code":"C-2048",
          "businessName":"North Star Foods",
          "commercialName":"North Star",
          "status":"$status",
          "version":$version
        }
    """.trimIndent()

    private fun jsonResponse(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun problemResponse(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/problem+json")
        .setBody("""{"status":$status,"code":"$code","category":"CLIENT_ERROR"}""")

    private class TestRefreshStore : RefreshCredentialStore {
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
        override suspend fun signIn(input: NativeSignIn) =
            IssuedNativeSession("access-1", "refresh-1")

        override suspend fun refresh(credential: String) =
            IssuedNativeSession("access-2", "refresh-2")

        override suspend fun currentSession(accessToken: String) = VerifiedSession(
            hasAuthorizedContext = true,
            userId = "user",
            tenantId = "tenant",
            workspaceId = "workspace",
            membershipId = "membership",
            permissions = setOf("client.read")
        )

        override suspend fun signOut(accessToken: String) = Unit
    }

    private companion object {
        const val CUSTOMER_ID = "12345678-1234-1234-1234-123456789012"
        const val OTHER_CUSTOMER_ID = "12345678-1234-1234-1234-123456789013"
    }
}
