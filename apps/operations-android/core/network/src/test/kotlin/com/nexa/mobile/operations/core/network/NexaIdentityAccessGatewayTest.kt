package com.nexa.mobile.operations.core.network

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaIdentityAccessGatewayTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun multiContextSignInUsesNativeIdentityAndTicketSelectionIsOneUse() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = NexaIdentityAccessGateway.create(
                ApiEndpoint(server.url("/").toString()),
                clock = clock
            )
            server.enqueue(
                jsonResponse(
                    200,
                    """{"outcome":"CONTEXT_SELECTION_REQUIRED","session":null,"ticketExpiresAt":"2026-09-24T12:05:00Z"}"""
                ).addHeader("X-Nexa-Context-Ticket", "opaque-ticket")
            )

            assertEquals(
                IdentitySignInOutcome.SelectionRequired,
                gateway.identitySignIn("person@example.test", "secret-password")
            )
            val signIn = server.takeRequest()
            assertEquals("POST", signIn.method)
            assertEquals("/api/v1/authentication/identity-sign-in", signIn.path)
            assertEquals("NATIVE", signIn.getHeader("X-Nexa-Client"))
            assertNull(signIn.getHeader("Authorization"))
            assertNull(signIn.getHeader("X-Nexa-Surface"))
            assertNull(signIn.getHeader("X-Nexa-Refresh-Token"))
            val signInBody = Json.parseToJsonElement(signIn.body.readUtf8()).jsonObject
            assertEquals("person@example.test", signInBody["identifier"]?.jsonPrimitive?.content)
            assertEquals("secret-password", signInBody["password"]?.jsonPrimitive?.content)
            assertEquals("PLATFORM", signInBody["surface"]?.jsonPrimitive?.content)
            assertFalse("workspaceSlug" in signInBody)

            server.enqueue(
                jsonResponse(
                    200,
                    """{"accessContexts":[{"membershipId":"membership-1","tenantId":"tenant-1","tenantName":"Tenant","tenantSlug":"tenant","workspaceId":"workspace-1","workspaceName":"Workspace","workspaceSlug":"workspace"}]}"""
                )
            )
            val contexts = gateway.accessContexts()
            assertTrue(contexts is AccessContextsOutcome.Available)
            assertEquals(
                "Workspace",
                (contexts as AccessContextsOutcome.Available).contexts.single().workspaceName
            )
            val list = server.takeRequest()
            assertEquals("GET", list.method)
            assertEquals("/api/v1/me/access-contexts", list.path)
            assertEquals("NATIVE", list.getHeader("X-Nexa-Client"))
            assertEquals("PLATFORM", list.getHeader("X-Nexa-Surface"))
            assertEquals("opaque-ticket", list.getHeader("X-Nexa-Context-Ticket"))
            assertNull(list.getHeader("Authorization"))

            server.enqueue(
                jsonResponse(
                    200,
                    """{"accessToken":"access-1","tokenType":"Bearer","expiresIn":300,"session":{"userId":"user-1","tenantId":"tenant-1","tenantSlug":"tenant","workspaceId":"workspace-1","workspaceSlug":"workspace","membershipId":"membership-1","permissions":["warehouse.read"],"surface":"PLATFORM"}}"""
                ).addHeader("X-Nexa-Refresh-Token", "refresh-1")
            )
            val selected = gateway.selectAccessContext("membership-1")
            assertTrue(selected is AccessContextSelectionOutcome.Authenticated)
            val select = server.takeRequest()
            assertEquals("POST", select.method)
            assertEquals("/api/v1/me/access-context-selections", select.path)
            assertEquals("NATIVE", select.getHeader("X-Nexa-Client"))
            assertEquals("PLATFORM", select.getHeader("X-Nexa-Surface"))
            assertEquals("opaque-ticket", select.getHeader("X-Nexa-Context-Ticket"))
            assertNull(select.getHeader("Authorization"))
            val selectBody = Json.parseToJsonElement(select.body.readUtf8()).jsonObject
            assertEquals("membership-1", selectBody["membershipId"]?.jsonPrimitive?.content)

            assertEquals(
                AccessContextSelectionOutcome.Rejected,
                gateway.selectAccessContext("membership-1")
            )
            assertEquals("only the dispatched selection reaches the server", 3, server.requestCount)
        }
    }

    @Test
    fun authenticatedContextSwitchUsesBearerAndReturnsReplacementSession() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = NexaIdentityAccessGateway.create(
                ApiEndpoint(server.url("/").toString()),
                clock = clock
            )
            server.enqueue(
                jsonResponse(
                    200,
                    """{"accessContexts":[{"membershipId":"membership-2","tenantId":"tenant-1","tenantName":"Tenant","tenantSlug":"tenant","workspaceId":"workspace-2","workspaceName":"Workspace 2","workspaceSlug":"workspace-2"}]}"""
                )
            )

            val contexts = gateway.accessContexts(accessToken = "current-access")
            assertTrue(contexts is AccessContextsOutcome.Available)
            val list = server.takeRequest()
            assertEquals("GET", list.method)
            assertEquals("/api/v1/me/access-contexts", list.path)
            assertEquals("NATIVE", list.getHeader("X-Nexa-Client"))
            assertEquals("PLATFORM", list.getHeader("X-Nexa-Surface"))
            assertEquals("Bearer current-access", list.getHeader("Authorization"))
            assertNull(list.getHeader("X-Nexa-Context-Ticket"))

            server.enqueue(
                jsonResponse(
                    200,
                    """{"accessToken":"replacement-access","tokenType":"Bearer","expiresIn":300,"session":{"userId":"user-1","tenantId":"tenant-1","tenantSlug":"tenant","workspaceId":"workspace-2","workspaceSlug":"workspace-2","membershipId":"membership-2","permissions":["warehouse.read"],"surface":"PLATFORM"}}"""
                ).addHeader("X-Nexa-Refresh-Token", "replacement-refresh")
            )
            val selected = gateway.selectAccessContext(
                "membership-2",
                accessToken = "current-access"
            )
            assertTrue(selected is AccessContextSelectionOutcome.Authenticated)
            val auth = selected as AccessContextSelectionOutcome.Authenticated
            assertEquals("replacement-access", auth.issuedSession.accessToken)
            assertEquals("replacement-refresh", auth.issuedSession.refreshCredential)
            val selection = server.takeRequest()
            assertEquals("POST", selection.method)
            assertEquals("/api/v1/me/access-context-selections", selection.path)
            assertEquals("Bearer current-access", selection.getHeader("Authorization"))
            assertNull(selection.getHeader("X-Nexa-Context-Ticket"))
            assertEquals("PLATFORM", selection.getHeader("X-Nexa-Surface"))
            val body = Json.parseToJsonElement(selection.body.readUtf8()).jsonObject
            assertEquals("membership-2", body["membershipId"]?.jsonPrimitive?.content)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun bearerUnauthorizedResponsesRequireSessionReauthentication() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = NexaIdentityAccessGateway.create(
                ApiEndpoint(server.url("/").toString()),
                clock = clock
            )
            server.enqueue(jsonResponse(401, "{}"))
            assertEquals(
                AccessContextsOutcome.SessionExpired,
                gateway.accessContexts(accessToken = "expired-access")
            )
            val list = server.takeRequest()
            assertEquals("Bearer expired-access", list.getHeader("Authorization"))
            assertNull(list.getHeader("X-Nexa-Context-Ticket"))

            server.enqueue(jsonResponse(401, "{}"))
            assertEquals(
                AccessContextSelectionOutcome.SessionExpired,
                gateway.selectAccessContext("membership-2", accessToken = "expired-access")
            )
            val selection = server.takeRequest()
            assertEquals("Bearer expired-access", selection.getHeader("Authorization"))
            assertNull(selection.getHeader("X-Nexa-Context-Ticket"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun directAuthenticationCarriesSessionAndRefreshCredential() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = NexaIdentityAccessGateway.create(
                ApiEndpoint(server.url("/").toString()),
                clock = clock
            )
            server.enqueue(
                jsonResponse(
                    200,
                    """{"outcome":"SESSION_ESTABLISHED","session":{"accessToken":"access-1","tokenType":"Bearer","expiresIn":300,"session":{"userId":"user-1","tenantId":"tenant-1","tenantSlug":"tenant","workspaceId":"workspace-1","workspaceSlug":"workspace","membershipId":"membership-1","permissions":["warehouse.read"],"surface":"PLATFORM"}}}"""
                ).addHeader("X-Nexa-Refresh-Token", "refresh-1")
            )

            val outcome = gateway.identitySignIn("person@example.test", "secret-password")
            assertTrue(outcome is IdentitySignInOutcome.Authenticated)
            assertEquals("/api/v1/authentication/identity-sign-in", server.takeRequest().path)
            val authenticated = outcome as IdentitySignInOutcome.Authenticated
            assertEquals("access-1", authenticated.issuedSession.accessToken)
            assertEquals("refresh-1", authenticated.issuedSession.refreshCredential)
            assertEquals("membership-1", authenticated.sessionContext.membershipId)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun noWorkContextRequiresAccepted200AndKeepsExpiredTicketLocal() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = NexaIdentityAccessGateway.create(
                ApiEndpoint(server.url("/").toString()),
                clock = clock
            )
            server.enqueue(
                jsonResponse(
                    200,
                    """{"outcome":"NO_WORK_CONTEXT","session":null,"ticketExpiresAt":null}"""
                )
            )
            assertEquals(
                IdentitySignInOutcome.NoWorkContext,
                gateway.identitySignIn("person@example.test", "secret-password")
            )
            server.takeRequest()

            server.enqueue(
                jsonResponse(403, """{"type":"about:blank","code":"NO_WORK_CONTEXT"}""")
            )
            assertEquals(
                IdentitySignInOutcome.Rejected,
                gateway.identitySignIn("person@example.test", "secret-password")
            )
            server.takeRequest()

            server.enqueue(
                jsonResponse(
                    200,
                    """{"outcome":"CONTEXT_SELECTION_REQUIRED","session":null,"ticketExpiresAt":"2026-09-24T11:59:59Z"}"""
                ).addHeader("X-Nexa-Context-Ticket", "expired-ticket")
            )
            assertEquals(
                IdentitySignInOutcome.ServiceUnavailable,
                gateway.identitySignIn("person@example.test", "secret-password")
            )
            server.takeRequest()
            assertEquals(AccessContextsOutcome.TicketExpired, gateway.accessContexts())
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun uncertainSelectionFailureConsumesTicketWithoutReplay() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = NexaIdentityAccessGateway.create(
                ApiEndpoint(server.url("/").toString()),
                clock = clock
            )
            server.enqueue(
                jsonResponse(
                    200,
                    """{"outcome":"CONTEXT_SELECTION_REQUIRED","session":null,"ticketExpiresAt":"2026-09-24T12:05:00Z"}"""
                ).addHeader("X-Nexa-Context-Ticket", "opaque-ticket")
            )
            gateway.identitySignIn("person@example.test", "secret-password")
            server.takeRequest()

            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(
                AccessContextSelectionOutcome.UnknownOutcome,
                gateway.selectAccessContext("membership-1")
            )
            server.takeRequest()
            assertEquals(
                AccessContextSelectionOutcome.Rejected,
                gateway.selectAccessContext("membership-1")
            )
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun malformedContextListIsRejectedAsAWhole() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = NexaIdentityAccessGateway.create(
                ApiEndpoint(server.url("/").toString()),
                clock = clock
            )
            server.enqueue(
                jsonResponse(
                    200,
                    """{"outcome":"CONTEXT_SELECTION_REQUIRED","session":null,"ticketExpiresAt":"2026-09-24T12:05:00Z"}"""
                ).addHeader("X-Nexa-Context-Ticket", "opaque-ticket")
            )
            gateway.identitySignIn("person@example.test", "secret-password")
            server.takeRequest()

            server.enqueue(
                jsonResponse(
                    200,
                    """{"accessContexts":[{"membershipId":"membership-1","tenantId":"tenant-1","tenantSlug":"tenant","workspaceId":"workspace-1","workspaceName":"Workspace","workspaceSlug":"workspace"}]}"""
                )
            )
            assertEquals(AccessContextsOutcome.Rejected, gateway.accessContexts())
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun contextSelectionTicketIsRequiredInResponseHeader() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = NexaIdentityAccessGateway.create(
                ApiEndpoint(server.url("/").toString()),
                clock = clock
            )
            server.enqueue(
                jsonResponse(
                    200,
                    """{"outcome":"CONTEXT_SELECTION_REQUIRED","session":null,"accessContextTicket":"body-only-ticket","ticketExpiresAt":"2026-09-24T12:05:00Z"}"""
                )
            )

            assertEquals(
                IdentitySignInOutcome.ServiceUnavailable,
                gateway.identitySignIn("person@example.test", "secret-password")
            )
            assertEquals(AccessContextsOutcome.TicketExpired, gateway.accessContexts())
            assertEquals(1, server.requestCount)
        }
    }

    private fun jsonResponse(status: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(status)
        .setBody(body)
        .addHeader("Content-Type", "application/json")
}
