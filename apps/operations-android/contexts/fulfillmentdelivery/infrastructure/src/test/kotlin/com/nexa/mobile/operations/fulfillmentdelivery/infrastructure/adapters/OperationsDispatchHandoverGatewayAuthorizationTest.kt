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
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverPermissions
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.JsonDispatchRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.NexaDispatchAssignmentGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.NexaDispatchReadinessGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.NexaFulfillmentDispatchGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.NexaOutgoingGoodsCheckGateway
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OperationsDispatchHandoverGatewayAuthorizationTest {
    @Test
    fun coordinatorHandoverUsesDispatchSummaryAndTreats204AsNoWarehouseCheck() = runTest {
        MockWebServer().use { server ->
            server.start()
            val permissions = setOf(DispatchHandoverPermissions.READ, "dispatch.complete")
            server.enqueue(
                jsonResponse(
                    """{"id":"$FULFILLMENT_ID","status":"READY_FOR_DISPATCH","version":1,"updatedAt":"2026-10-26T00:00:00Z"}"""
                ).addHeader("ETag", "\"1\"")
            )
            server.enqueue(
                jsonResponse(
                    """{"subjectKind":"PREPARED_FULFILLMENT","fulfillmentId":"$FULFILLMENT_ID","fulfillmentVersion":1,"fulfillmentStatus":"READY_FOR_DISPATCH","physicalAllocationId":"$ALLOCATION_ID","physicalAllocationStatus":"ALLOCATED","physicalAllocationVersion":1,"allocationComplete":true,"pickingComplete":true,"pickingEvidenceComplete":true,"ready":true,"reasons":[],"lines":[],"asOf":"2026-10-26T00:00:00Z"}"""
                )
            )
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val sessions = activeSessions(permissions, backgroundScope)

            val result = gateway(server, sessions).load(readiness(), context(permissions))

            assertTrue(
                "Expected a pending handover snapshot, got $result",
                result is DispatchHandoverGatewayResult.Snapshot
            )
            val snapshot = (result as DispatchHandoverGatewayResult.Snapshot).value
            assertEquals(null, snapshot.outgoingCheck)
            assertFalse(snapshot.canConfirm)
            assertEquals(4, server.requestCount)
            val requestPaths = List(4) { server.takeRequest().path }
            assertEquals(
                listOf(
                    "/api/v1/fulfillments/$FULFILLMENT_ID",
                    "/api/v1/dispatch-readiness/$FULFILLMENT_ID",
                    "/api/v1/fulfillments/$FULFILLMENT_ID/outgoing-checks/current-summary",
                    "/api/v1/fulfillments/$FULFILLMENT_ID/driver-assignments"
                ),
                requestPaths
            )
        }
    }

    @Test
    fun canonicalAndAliasPermissionsReachProtectedDispatchReadWithoutFulfillmentManage() = runTest {
        for (completionPermission in DispatchHandoverPermissions.complete) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setResponseCode(400))
                val permissions = setOf(DispatchHandoverPermissions.READ, completionPermission)
                val sessions = activeSessions(permissions, backgroundScope)
                val gateway = gateway(server, sessions)

                val result = gateway.load(readiness(), context(permissions))

                assertFalse("fulfillment.manage" in permissions)
                assertEquals(DispatchHandoverGatewayResult.ServiceUnavailable, result)
                assertEquals(1, server.requestCount)
                assertEquals(
                    "/api/v1/fulfillments/$FULFILLMENT_ID",
                    server.takeRequest().path
                )
            }
        }
    }

    @Test
    fun fulfillmentManageWithoutDispatchCompleteIsDeniedBeforeNetwork() = runTest {
        MockWebServer().use { server ->
            server.start()
            val permissions = setOf(DispatchHandoverPermissions.READ, "fulfillment.manage")
            val sessions = activeSessions(permissions, backgroundScope)
            val gateway = gateway(server, sessions)

            val result = gateway.load(readiness(), context(permissions))

            assertEquals(DispatchHandoverGatewayResult.PermissionDenied, result)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun canonicalAndAliasPermissionsReachHandoverPostWithoutFulfillmentManage() = runTest {
        for (completionPermission in DispatchHandoverPermissions.complete) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setResponseCode(400))
                val permissions = setOf(DispatchHandoverPermissions.READ, completionPermission)
                val sessions = activeSessions(permissions, backgroundScope)
                val gateway = gateway(server, sessions)
                val command = handoverCommand()

                val result = gateway.dispatch(
                    readiness(),
                    snapshot = null,
                    command = command,
                    context = context(permissions)
                )

                assertFalse("fulfillment.manage" in permissions)
                assertEquals(DispatchHandoverGatewayResult.ServiceUnavailable, result)
                assertEquals(1, server.requestCount)
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/api/v1/fulfillments/$FULFILLMENT_ID/dispatches", request.path)
                assertEquals(command.exactRequestBody, request.body.readUtf8())
            }
        }
    }

    @Test
    fun fulfillmentManageWithoutDispatchCompleteIsDeniedBeforePost() = runTest {
        MockWebServer().use { server ->
            server.start()
            val permissions = setOf(DispatchHandoverPermissions.READ, "fulfillment.manage")
            val sessions = activeSessions(permissions, backgroundScope)
            val gateway = gateway(server, sessions)

            val result = gateway.dispatch(
                readiness(),
                snapshot = null,
                command = handoverCommand(),
                context = context(permissions)
            )

            assertEquals(DispatchHandoverGatewayResult.PermissionDenied, result)
            assertEquals(0, server.requestCount)
        }
    }

    private fun gateway(
        server: MockWebServer,
        sessions: SessionCoordinator
    ): OperationsDispatchHandoverGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        val protectedCalls = ProtectedCallExecutor(
            endpoint,
            ApiHttpClient.create(endpoint),
            sessions
        )
        return OperationsDispatchHandoverGateway(
            sessions,
            NexaDispatchReadinessGateway(protectedCalls),
            NexaDispatchAssignmentGateway(protectedCalls),
            NexaOutgoingGoodsCheckGateway(protectedCalls),
            NexaFulfillmentDispatchGateway(protectedCalls),
            JsonDispatchRequestBodyCodec()
        )
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private suspend fun activeSessions(
        permissions: Set<String>,
        scope: kotlinx.coroutines.CoroutineScope
    ): SessionCoordinator = SessionCoordinator(
        TestStore(),
        TestAuthGateway(permissions),
        scope
    ).also {
        assertTrue(it.signIn(NativeSignIn("synthetic-user", "synthetic-password", "workspace")))
    }

    private fun context(permissions: Set<String>) = DispatchAuthorityContext(
        authorityEpoch = 1,
        identity = DispatchAuthorityIdentity(
            USER_ID,
            TENANT_ID,
            WORKSPACE_ID,
            MEMBERSHIP_ID,
            permissions
        )
    )

    private fun readiness() = DispatchReadiness(
        subjectKind = "FULFILLMENT",
        fulfillmentId = FULFILLMENT_ID,
        fulfillmentVersion = 1,
        fulfillmentStatus = "READY_FOR_DISPATCH",
        physicalAllocationId = "allocation",
        physicalAllocationStatus = "COMPLETE",
        physicalAllocationVersion = 1,
        deliveryId = null,
        deliveryStatus = null,
        deliveryVersion = null,
        allocationComplete = true,
        pickingComplete = true,
        pickingEvidenceComplete = true,
        ready = true,
        reasons = emptyList(),
        lines = emptyList(),
        asOf = Instant.parse("2026-10-26T00:00:00Z")
    )

    private fun handoverCommand(): DispatchHandoverCommand {
        val codec = JsonDispatchRequestBodyCodec()
        val command = DispatchHandoverCommand(
            fulfillmentId = FULFILLMENT_ID,
            expectedFulfillmentVersion = 1,
            physicalAllocationId = ALLOCATION_ID,
            physicalAllocationVersion = 1,
            driverAssignmentId = ASSIGNMENT_ID,
            driverAssignmentVersion = 1,
            outgoingGoodsCheckId = CHECK_ID,
            idempotencyKey = "handover-key",
            exactRequestBody = ""
        )
        return command.copy(exactRequestBody = codec.dispatchHandoverRequestBody(command))
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

    private class TestAuthGateway(private val permissions: Set<String>) : AuthRemoteGateway {
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
            permissions = permissions
        )

        override suspend fun signOut(accessToken: String) = Unit
    }

    private companion object {
        const val FULFILLMENT_ID = "00000000-0000-0000-0000-000000000001"
        const val ALLOCATION_ID = "00000000-0000-0000-0000-000000000002"
        const val ASSIGNMENT_ID = "00000000-0000-0000-0000-000000000003"
        const val CHECK_ID = "00000000-0000-0000-0000-000000000004"
        const val USER_ID = "user"
        const val TENANT_ID = "tenant"
        const val WORKSPACE_ID = "workspace"
        const val MEMBERSHIP_ID = "membership"
    }
}
