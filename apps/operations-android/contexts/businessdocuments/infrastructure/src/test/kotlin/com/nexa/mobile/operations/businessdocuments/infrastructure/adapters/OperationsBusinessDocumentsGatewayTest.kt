package com.nexa.mobile.operations.businessdocuments.infrastructure.adapters

import com.nexa.mobile.operations.businessdocuments.application.commercial.BusinessDocumentsResult
import com.nexa.mobile.operations.core.auth.credentials.RefreshCredentialStore
import com.nexa.mobile.operations.core.auth.credentials.StoredRefreshCredential
import com.nexa.mobile.operations.core.auth.session.AuthRemoteGateway
import com.nexa.mobile.operations.core.auth.session.IssuedNativeSession
import com.nexa.mobile.operations.core.auth.session.NativeSignIn
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.ApiEndpoint
import com.nexa.mobile.operations.core.network.ApiHttpClient
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationsBusinessDocumentsGatewayTest {
    private val permissions = setOf("document.read", "document.download")
    private val authority =
        CommercialAuthority("user", "tenant", "workspace", "membership", permissions, 1)
    private val id = "00000000-0000-0000-0000-000000000001"
    private val bytes = "a,b\n1,2\n".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }

    @Test
    fun listRequestsGeneratedDocumentsAndAcceptsCsvMetadata() = runTest {
        val sessions = sessions()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody("""{"items":[${metadata("GENERATED")}],"total":1}""")
            )
            val result = gateway(server, sessions).list(authority, 0)
            assertTrue(result is BusinessDocumentsResult.Page)
            assertEquals(
                "/api/v1/business-documents?status=GENERATED&page=0&size=25",
                server.takeRequest().path
            )
            assertEquals(
                "text/csv",
                (result as BusinessDocumentsResult.Page).items.single().contentType
            )
        }
    }

    @Test
    fun generatedAndSupersededCsvDownloadsAcceptCharsetAndVerifyIntegrity() = runTest {
        val sessions = sessions()
        MockWebServer().use { server ->
            server.start()
            for (status in listOf("GENERATED", "SUPERSEDED")) {
                server.enqueue(MockResponse().setBody(metadata(status)))
                server.enqueue(download(hash, "text/csv; charset=UTF-8"))
                assertTrue(
                    gateway(
                        server,
                        sessions
                    ).content(authority, id) is BusinessDocumentsResult.Content
                )
            }
            server.enqueue(MockResponse().setBody(metadata("GENERATED")))
            server.enqueue(download("0".repeat(64), "text/csv; charset=UTF-8"))
            assertEquals(
                BusinessDocumentsResult.Unavailable,
                gateway(server, sessions).content(authority, id)
            )
            server.enqueue(MockResponse().setBody(metadata("GENERATED")))
            server.enqueue(download(hash, "application/pdf"))
            assertEquals(
                BusinessDocumentsResult.Unavailable,
                gateway(server, sessions).content(authority, id)
            )
            server.enqueue(MockResponse().setBody(metadata("VOIDED")))
            assertEquals(
                BusinessDocumentsResult.Unavailable,
                gateway(server, sessions).content(authority, id)
            )
        }
    }

    private fun metadata(status: String) = """{
        "id":"$id","clientAccountId":"$id","subjectType":"SALES_ORDER","subjectId":"$id",
        "documentNumber":null,"documentType":"ORDER_SUMMARY","version":1,"generatedAt":null,
        "checksumSha256":"$hash","byteSize":${bytes.size},"format":"CSV",
        "contentType":"text/csv; charset=UTF-8","status":"$status"
    }"""

    private fun download(checksum: String, type: String) = MockResponse()
        .addHeader("Content-Type", type).addHeader("X-Content-SHA256", checksum)
        .setBody(okio.Buffer().write(bytes))

    private fun gateway(
        server: MockWebServer,
        sessions: SessionCoordinator
    ): OperationsBusinessDocumentsGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return OperationsBusinessDocumentsGateway(
            sessions,
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), sessions)
        )
    }

    private suspend fun kotlinx.coroutines.test.TestScope.sessions(): SessionCoordinator =
        SessionCoordinator(Store(), Auth(), backgroundScope).also {
            assertTrue(it.signIn(NativeSignIn("user", "password", "workspace")))
        }

    private class Store : RefreshCredentialStore {
        private var value: StoredRefreshCredential = StoredRefreshCredential.Missing
        override suspend fun read() = value
        override suspend fun takeForRefresh() = value.also {
            value =
                StoredRefreshCredential.InFlight
        }
        override suspend fun writeReady(credential: String) {
            value =
                StoredRefreshCredential.Ready(credential)
        }
        override suspend fun clear() {
            value = StoredRefreshCredential.Missing
        }
    }

    private inner class Auth : AuthRemoteGateway {
        override suspend fun signIn(input: NativeSignIn) = IssuedNativeSession("access", "refresh")
        override suspend fun refresh(credential: String) = IssuedNativeSession("access", "refresh")
        override suspend fun currentSession(accessToken: String) = VerifiedSession(
            hasAuthorizedContext = true,
            userId = "user",
            tenantId = "tenant",
            workspaceId = "workspace",
            membershipId = "membership",
            permissions = permissions
        )
        override suspend fun signOut(accessToken: String) = Unit
    }
}
