package com.nexa.mobile.operations.notifications.infrastructure.transport

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
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.notifications.application.inbox.NotificationsAuthority
import com.nexa.mobile.operations.notifications.application.inbox.NotificationsResult
import com.nexa.mobile.operations.notifications.application.push.PushSubscriptionRegistration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OperationsPushSubscriptionGatewayTest {
    @Test
    fun registrationUsesNativeMarkerAndAcceptsMatchingEnabledResponse() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(enabledResponse())
            server.enqueue(MockResponse().setResponseCode(204))
            val sessions = activeSessions(backgroundScope)
            val calls = protectedCalls(server, sessions)
            val gateway = OperationsPushSubscriptionGateway(sessions, calls)

            assertEquals(NotificationsResult.Success(Unit), gateway.register(registration()))

            val registrationRequest = server.takeRequest()
            assertEquals("POST", registrationRequest.method)
            assertEquals(PUSH_SUBSCRIPTIONS_PATH, registrationRequest.path)
            assertEquals("NATIVE", registrationRequest.getHeader("X-Nexa-Client"))
            assertEquals(
                "stable-registration-key",
                registrationRequest.getHeader("Idempotency-Key")
            )
            val requestBody = Json.parseToJsonElement(
                registrationRequest.body.readUtf8()
            ).jsonObject
            val installationId = requestBody.getValue("installationId").jsonPrimitive.content
            assertEquals(OPAQUE_INSTALLATION_ID, installationId)
            assertFalse(installationId.contains(FID))
            assertEquals("ANDROID", requestBody.getValue("platform").jsonPrimitive.content)
            assertEquals(
                "fcm-fid:$FID",
                requestBody.getValue("providerToken").jsonPrimitive.content
            )

            assertTrue(
                calls.execute(
                    ProtectedRequest(ProtectedMethod.GET, "/api/v1/runtime-check")
                ) is ProtectedResult.Success
            )
            assertEquals(null, server.takeRequest().getHeader("X-Nexa-Client"))
        }
    }

    @Test
    fun mismatchedMembershipOrSurfaceInEnabledResponseIsRejected() = runTest {
        for (responseBody in listOf(
            enabledResponseBody(membershipId = "different-membership"),
            enabledResponseBody(surface = "BUYER")
        )) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(jsonResponse(responseBody))
                val sessions = activeSessions(backgroundScope)
                val gateway = OperationsPushSubscriptionGateway(
                    sessions,
                    protectedCalls(server, sessions)
                )

                assertEquals(NotificationsResult.Unavailable, gateway.register(registration()))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun membershipOrEpochMismatchIsDeniedBeforeNetworkDispatch() = runTest {
        MockWebServer().use { server ->
            server.start()
            val sessions = activeSessions(backgroundScope)
            val gateway = OperationsPushSubscriptionGateway(
                sessions,
                protectedCalls(server, sessions)
            )

            assertEquals(
                NotificationsResult.PermissionDenied,
                gateway.register(
                    registration(authority = authority(membershipId = "different-membership"))
                )
            )
            assertEquals(
                NotificationsResult.PermissionDenied,
                gateway.register(registration(authority = authority(authorityEpoch = 2)))
            )
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun changedMembershipEpochRejectsAnInFlightRegistrationResponse() = runTest {
        MockWebServer().use { server ->
            server.start()
            val requestArrived = CountDownLatch(1)
            val releaseResponse = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestArrived.countDown()
                    if (!releaseResponse.await(5, TimeUnit.SECONDS)) {
                        return MockResponse().setResponseCode(504)
                    }
                    return enabledResponse()
                }
            }
            val auth = TestAuthGateway(verifiedSession())
            val sessions = activeSessions(backgroundScope, auth)
            val gateway = OperationsPushSubscriptionGateway(
                sessions,
                protectedCalls(server, sessions)
            )
            val pending = async(Dispatchers.IO) { gateway.register(registration()) }

            assertTrue(requestArrived.await(5, TimeUnit.SECONDS))
            auth.verified = verifiedSession(membershipId = "new-membership")
            assertTrue(
                sessions.signIn(
                    NativeSignIn("test-user", "test-password", "test-workspace")
                )
            )
            releaseResponse.countDown()

            assertEquals(NotificationsResult.PermissionDenied, pending.await())
            assertEquals(1, server.requestCount)
        }
    }

    private fun protectedCalls(
        server: MockWebServer,
        sessions: SessionCoordinator
    ): ProtectedCallExecutor {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), sessions)
    }

    private suspend fun activeSessions(
        scope: kotlinx.coroutines.CoroutineScope,
        auth: TestAuthGateway = TestAuthGateway(verifiedSession())
    ): SessionCoordinator = SessionCoordinator(TestStore(), auth, scope).also {
        assertTrue(
            it.signIn(NativeSignIn("test-user", "test-password", "test-workspace"))
        )
    }

    private fun registration(authority: NotificationsAuthority = authority()) =
        PushSubscriptionRegistration(
            authority = authority,
            installationId = OPAQUE_INSTALLATION_ID,
            platform = "ANDROID",
            providerToken = "fcm-fid:$FID",
            idempotencyKey = "stable-registration-key"
        )

    private fun authority(membershipId: String = MEMBERSHIP_ID, authorityEpoch: Long = 1) =
        NotificationsAuthority(
            userId = USER_ID,
            tenantId = TENANT_ID,
            workspaceId = WORKSPACE_ID,
            membershipId = membershipId,
            permissions = setOf(READ_PERMISSION),
            authorityEpoch = authorityEpoch
        )

    private fun verifiedSession(membershipId: String = MEMBERSHIP_ID) = VerifiedSession(
        hasAuthorizedContext = true,
        userId = USER_ID,
        tenantId = TENANT_ID,
        workspaceId = WORKSPACE_ID,
        membershipId = membershipId,
        permissions = setOf(READ_PERMISSION)
    )

    private fun enabledResponse() = jsonResponse(enabledResponseBody())

    private fun enabledResponseBody(
        membershipId: String = MEMBERSHIP_ID,
        surface: String = "PLATFORM"
    ) = """
        {
          "id":"00000000-0000-0000-0000-000000000001",
          "status":"ENABLED",
          "installationId":"$OPAQUE_INSTALLATION_ID",
          "platform":"ANDROID",
          "surface":"$surface",
          "recipientMembershipId":"$membershipId"
        }
    """.trimIndent()

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(201)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

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

    private class TestAuthGateway(var verified: VerifiedSession) : AuthRemoteGateway {
        override suspend fun signIn(input: NativeSignIn) =
            IssuedNativeSession("synthetic-access", "synthetic-refresh")

        override suspend fun refresh(credential: String) =
            IssuedNativeSession("synthetic-access-refreshed", "synthetic-refresh-refreshed")

        override suspend fun currentSession(accessToken: String) = verified

        override suspend fun signOut(accessToken: String) = Unit
    }

    private companion object {
        const val PUSH_SUBSCRIPTIONS_PATH = "/api/v1/notifications/push-subscriptions"
        const val READ_PERMISSION = "notification.read"
        const val USER_ID = "user-1"
        const val TENANT_ID = "tenant-1"
        const val WORKSPACE_ID = "workspace-1"
        const val MEMBERSHIP_ID = "membership-1"
        const val FID = "sample_fid-123"
        const val OPAQUE_INSTALLATION_ID =
            "operations-d01b62c11e2ef24349dadd23116daccc9b96b3976024e3df836f43d00dc33839"
    }
}
