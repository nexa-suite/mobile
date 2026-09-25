package com.nexa.mobile.operations.core.network

import com.nexa.mobile.operations.core.auth.session.AuthGatewayFailure
import com.nexa.mobile.operations.core.auth.session.NativeSignIn
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaAuthGatewayTest {
    @Test
    fun nativeRoutesUseOnlyTheirVerifiedHeaders() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = NexaAuthGateway.create(ApiEndpoint(server.url("/").toString()))
            server.enqueue(issued("access-1", "refresh-1"))
            val signIn = gateway.signIn(
                NativeSignIn("person@example.test", "secret-password", "workspace")
            )
            assertEquals("access-1", signIn.accessToken)
            val login = server.takeRequest()
            assertEquals("POST", login.method)
            assertEquals("/api/v1/authentication/sign-in", login.path)
            assertEquals("NATIVE", login.getHeader("X-Nexa-Client"))
            assertEquals(
                "PLATFORM",
                Json.parseToJsonElement(
                    login.body.readUtf8()
                ).jsonObject["surface"]?.jsonPrimitive?.content
            )
            assertNull(login.getHeader("Authorization"))
            assertNull(login.getHeader("X-Nexa-Surface"))
            assertNull(login.getHeader("X-Nexa-Refresh-Token"))

            server.enqueue(issued("access-2", "refresh-2"))
            gateway.refresh("refresh-1")
            val refresh = server.takeRequest()
            assertEquals("POST", refresh.method)
            assertEquals("/api/v1/authentication/refresh", refresh.path)
            assertEquals("NATIVE", refresh.getHeader("X-Nexa-Client"))
            assertEquals("PLATFORM", refresh.getHeader("X-Nexa-Surface"))
            assertEquals("refresh-1", refresh.getHeader("X-Nexa-Refresh-Token"))
            assertNull(refresh.getHeader("Authorization"))
            assertEquals(0L, refresh.bodySize)

            server.enqueue(
                MockResponse().setBody(SESSION_JSON).addHeader("Content-Type", "application/json")
            )
            val verifiedSession = gateway.currentSession("access-2")
            assertTrue(verifiedSession.hasAuthorizedContext)
            assertEquals("Tenant Name", verifiedSession.tenantName)
            assertEquals("Workspace Name", verifiedSession.workspaceName)
            val session = server.takeRequest()
            assertEquals("GET", session.method)
            assertEquals("/api/v1/session", session.path)
            assertEquals("Bearer access-2", session.getHeader("Authorization"))
            assertNull(session.getHeader("X-Nexa-Client"))
            assertNull(session.getHeader("X-Nexa-Surface"))
            assertNull(session.getHeader("X-Nexa-Refresh-Token"))

            server.enqueue(MockResponse().setResponseCode(204))
            gateway.signOut("access-2")
            val signOut = server.takeRequest()
            assertEquals("POST", signOut.method)
            assertEquals("/api/v1/authentication/sign-out", signOut.path)
            assertEquals("Bearer access-2", signOut.getHeader("Authorization"))
            assertEquals("NATIVE", signOut.getHeader("X-Nexa-Client"))
            assertNull(signOut.getHeader("X-Nexa-Surface"))
            assertNull(signOut.getHeader("X-Nexa-Refresh-Token"))
        }
    }

    @Test
    fun missingNativeRefreshHeaderFailsClosed() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    "{\"accessToken\":\"access\"}"
                ).addHeader("Content-Type", "application/json")
            )
            val gateway = NexaAuthGateway.create(ApiEndpoint(server.url("/").toString()))
            assertThrows(AuthGatewayFailure.ProtocolFailure::class.java) {
                kotlinx.coroutines.runBlocking {
                    gateway.signIn(NativeSignIn("id", "password", "workspace"))
                }
            }
        }
    }

    @Test
    fun sessionWithoutUserIdentityCannotAuthorizeContext() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(SESSION_JSON.replace("\"user\":{\"userId\":\"u\"},", ""))
                    .addHeader("Content-Type", "application/json")
            )
            val gateway = NexaAuthGateway.create(ApiEndpoint(server.url("/").toString()))
            assertFalse(gateway.currentSession("access-1").hasAuthorizedContext)
        }
    }

    @Test
    fun ambiguousRefreshServerErrorIsNeverDefinitiveRejection() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))
            val gateway = NexaAuthGateway.create(ApiEndpoint(server.url("/").toString()))
            assertThrows(AuthGatewayFailure.AmbiguousOutcome::class.java) {
                kotlinx.coroutines.runBlocking { gateway.refresh("refresh-1") }
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun originGuardBlocksSecretsBeforeForeignRequestAndRedirectsStayDisabled() {
        MockWebServer().use { trusted ->
            MockWebServer().use { foreign ->
                trusted.start()
                foreign.start()
                val endpoint = ApiEndpoint(trusted.url("/").toString())
                val client = ApiHttpClient.create(endpoint)
                assertThrows(IOException::class.java) {
                    client.newCall(
                        Request.Builder().url(foreign.url("/api/v1/secret"))
                            .header("Authorization", "Bearer synthetic-secret")
                            .header("X-Nexa-Refresh-Token", "synthetic-refresh")
                            .build()
                    ).execute().close()
                }
                assertEquals(0, foreign.requestCount)
                trusted.enqueue(
                    MockResponse().setResponseCode(
                        302
                    ).addHeader("Location", foreign.url("/api/v1/secret"))
                )
                client.newCall(
                    Request.Builder().url(trusted.url("/api/v1/test"))
                        .header("Authorization", "Bearer synthetic-secret").build()
                ).execute().use {
                    assertEquals(302, it.code)
                }
                assertEquals(0, foreign.requestCount)
                assertFalse(client.followRedirects)
            }
        }
    }

    @Test
    fun endpointRequiresTrustedSchemeAndRoot() {
        assertThrows(IllegalArgumentException::class.java) { ApiEndpoint("http://example.test/") }
        assertThrows(IllegalArgumentException::class.java) {
            ApiEndpoint("https://example.test/path/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ApiEndpoint("https://user:password@example.test/")
        }
    }
}

internal val SESSION_JSON =
    """{"user":{"userId":"u"},"tenant":{"tenantId":"t","tenantName":"Tenant Name"},
       "workspace":{"workspaceId":"w","workspaceName":"Workspace Name"},"membership":{"membershipId":"m"},
       "surface":"PLATFORM"}"""

internal fun issued(access: String, refresh: String): MockResponse = MockResponse()
    .setBody("""{"accessToken":"$access","tokenType":"Bearer","expiresIn":300}""")
    .addHeader("Content-Type", "application/json")
    .addHeader("X-Nexa-Refresh-Token", refresh)
