package com.nexa.mobile.operations.salescommitment.infrastructure.transport

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.ApiEndpoint
import com.nexa.mobile.operations.core.network.ApiHttpClient
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestSubmission
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestIntent
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaDirectOrderGatewayTest {
    @Test
    fun confirmedOrderPreservesServerReceiptAndFrozenRequest() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response(201, order()))
            val result = gateway(server).submit(authority, intent(), CUSTOMER)
            assertTrue(result is FieldRequestSubmission.Confirmed)
            val receipt = (result as FieldRequestSubmission.Confirmed).receipt
            assertEquals(ORDER, receipt.id)
            assertEquals("SO-SYNTHETIC-42", receipt.number)
            assertEquals("CONFIRMED", receipt.status)
            assertEquals("25.00", receipt.total)
            assertEquals("PEN", receipt.currency)
            assertEquals(1, receipt.version.toInt())
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/direct-orders", request.path)
            assertEquals("stable-key", request.getHeader("Idempotency-Key"))
            assertEquals(intent().exactBody, request.body.readUtf8())
        }
    }

    @Test
    fun prepaidPendingIsRecordedWithoutClaimingConfirmationAndReplayKeepsIdentity() = runTest {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(response(202, order("PREPAID", "PENDING", 0), "0")) }
            val gateway = gateway(server)
            val intent = intent("PREPAID")
            repeat(2) {
                val result = gateway.submit(authority, intent, CUSTOMER)
                assertTrue(result is FieldRequestSubmission.PrepaidPending)
                assertEquals(
                    "PENDING",
                    (result as FieldRequestSubmission.PrepaidPending).receipt.status
                )
            }
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
            assertEquals(first.body.readUtf8(), second.body.readUtf8())
        }
    }

    @Test
    fun mismatchedScopeMoneyStatusVersionAndMalformedResponsesRemainUnknown() = runTest {
        MockWebServer().use { server ->
            server.start()
            val invalid = listOf(
                order().replace("\"tenantId\":\"$TENANT\"", "\"tenantId\":\"$OTHER\""),
                order().replace(
                    "\"workspaceId\":\"$WORKSPACE\"",
                    "\"workspaceId\":\"$OTHER\""
                ),
                order().replace(
                    "\"clientAccountId\":\"$CUSTOMER\"",
                    "\"clientAccountId\":\"$OTHER\""
                ),
                order().replace(
                    "\"createdByMembershipId\":\"$MEMBER\"",
                    "\"createdByMembershipId\":\"$OTHER\""
                ),
                order().replace(
                    "\"buyerMembershipId\":\"$MEMBER\"",
                    "\"buyerMembershipId\":\"$OTHER\""
                ),
                order().replace(
                    "\"sourcePurchaseRequestId\":null",
                    "\"sourcePurchaseRequestId\":\"$OTHER\""
                ),
                order().replace("\"priority\":\"NORMAL\"", "\"priority\":\"HIGH\""),
                order().replace(
                    "\"requestedDeliveryDate\":\"2026-10-09\"",
                    "\"requestedDeliveryDate\":\"2026-10-10\""
                ),
                order().replace("\"deliverySnapshot\":\"Door\"", "\"deliverySnapshot\":\"Other\""),
                order().replace("\"notes\":\"Synthetic\"", "\"notes\":\"Other\""),
                order().replace("\"total\":25.00", "\"total\":-25.00"),
                order().replace("\"currency\":\"PEN\"", "\"currency\":\"invalid\""),
                order().replace("\"quantity\":2.500", "\"quantity\":3"),
                order().replace("\"unitPriceAmount\":10.00", "\"unitPriceAmount\":-10.00"),
                order().replace("\"unitPriceCurrency\":\"PEN\"", "\"unitPriceCurrency\":\"USD\""),
                order().replace("\"lineSubtotal\":25.00", "\"lineSubtotal\":-25.00"),
                order().replace(
                    "\"originType\":\"DIRECT_ORDER\"",
                    "\"originType\":\"PURCHASE_REQUEST\""
                ),
                order().replace(
                    "\"commercialCommitmentId\":\"$OTHER\"",
                    "\"commercialCommitmentId\":\"invalid\""
                ),
                order(payment = "PREPAID"),
                order().replace("\"lineSubtotal\":25.00", "\"lineSubtotal\":24.99"),
                order().replace("\"total\":25.00", "\"total\":24.99"),
                order().replace("\"total\":25.00", "\"total\":\"25.00\""),
                order().replace("\"status\":\"CONFIRMED\"", "\"status\":\"PENDING\""),
                "not-json"
            )
            val gateway = gateway(server)
            invalid.forEach { body ->
                server.enqueue(response(201, body))
                assertEquals(
                    FieldRequestSubmission.UnknownOutcome,
                    gateway.submit(authority, intent(), CUSTOMER)
                )
            }
            server.enqueue(response(201, order(), "2"))
            assertEquals(
                FieldRequestSubmission.UnknownOutcome,
                gateway.submit(authority, intent(), CUSTOMER)
            )
            server.enqueue(response(201, order(version = 0), "0"))
            assertEquals(
                FieldRequestSubmission.UnknownOutcome,
                gateway.submit(authority, intent(), CUSTOMER)
            )
            server.enqueue(response(202, order("PREPAID", "PENDING", 1), "1"))
            assertEquals(
                FieldRequestSubmission.UnknownOutcome,
                gateway.submit(authority, intent("PREPAID"), CUSTOMER)
            )
            server.enqueue(response(202, order()))
            assertEquals(
                FieldRequestSubmission.UnknownOutcome,
                gateway.submit(authority, intent(), CUSTOMER)
            )
        }
    }

    @Test
    fun serverRejectionAndAuthorizationNeverBecomeOrderSuccess() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = gateway(server)
            listOf(
                401 to FieldRequestSubmission.PermissionDenied,
                403 to FieldRequestSubmission.PermissionDenied,
                409 to FieldRequestSubmission.Conflict,
                412 to FieldRequestSubmission.Conflict,
                422 to FieldRequestSubmission.Rejected,
                500 to FieldRequestSubmission.UnknownOutcome
            ).forEach { (status, expected) ->
                server.enqueue(
                    MockResponse().setResponseCode(
                        status
                    ).addHeader("Content-Type", "application/problem+json")
                        .setBody(
                            """{"status":$status,"code":"SYNTHETIC_REJECTION","category":"CLIENT_ERROR"}"""
                        )
                )
                assertEquals(expected, gateway.submit(authority, intent(), CUSTOMER))
            }
        }
    }

    @Test
    fun legacyIntentAndMalformedCommandsDoNotDispatch() = runTest {
        MockWebServer().use { server ->
            server.start()
            val gateway = gateway(server)
            assertEquals(
                FieldRequestSubmission.LegacyIntent,
                gateway.submit(
                    authority,
                    intent().copy(
                        exactBody = legacyPurchaseRequestBody(),
                        operation = FieldRequestIntent.LEGACY_FIELD_REQUEST_OPERATION
                    ),
                    CUSTOMER
                )
            )
            assertEquals(
                FieldRequestSubmission.Rejected,
                gateway.submit(
                    authority,
                    intent().copy(exactBody = "{}"),
                    CUSTOMER
                )
            )
            assertEquals(
                FieldRequestSubmission.Rejected,
                gateway.submit(
                    authority,
                    intent().copy(exactBody = intent().exactBody.replace("2.500", "0")),
                    CUSTOMER
                )
            )
            assertEquals(
                FieldRequestSubmission.PermissionDenied,
                gateway.submit(
                    authority,
                    intent().copy(key = ""),
                    CUSTOMER
                )
            )
            assertEquals(0, server.requestCount)
        }
    }

    private fun gateway(server: MockWebServer): NexaDirectOrderGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaDirectOrderGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), Tokens())
        )
    }

    private fun intent(payment: String = "CASH_ON_DELIVERY") = FieldRequestIntent(
        "stable-key",
        """{"clientAccountId":"$CUSTOMER","priority":"NORMAL","requestedDeliveryDate":"2026-10-09","deliveryProfileSnapshot":"Door","paymentOption":"$payment","comment":"Synthetic","lines":[{"catalogItemId":"CAT-1","quantity":2.500,"unit":"KG"}]}"""
    )

    private fun legacyPurchaseRequestBody() =
        """{"clientAccountId":"$CUSTOMER","priority":"NORMAL","requestedDeliveryDate":"2026-10-09","deliveryProfileSnapshot":"Door","paymentOption":"CASH_ON_DELIVERY","comment":"Synthetic","lines":[{"catalogItemId":"CAT-1","quantity":2.500,"unit":"KG","expectedUnitPrice":10.00,"expectedCurrency":"PEN"}]}"""

    private fun order(
        payment: String = "CASH_ON_DELIVERY",
        status: String = "CONFIRMED",
        version: Long = 1
    ): String =
        """{"id":"$ORDER","number":"SO-SYNTHETIC-42","tenantId":"$TENANT","workspaceId":"$WORKSPACE","clientAccountId":"$CUSTOMER","createdByMembershipId":"$MEMBER","buyerMembershipId":"$MEMBER","sourcePurchaseRequestId":null,"priority":"NORMAL","requestedDeliveryDate":"2026-10-09","deliverySnapshot":"Door","paymentOption":"$payment","notes":"Synthetic","currency":"PEN","total":25.00,"status":"$status","version":$version,"originType":"DIRECT_ORDER","commercialCommitmentId":"$OTHER","lines":[{"catalogItemId":"CAT-1","quantity":2.500,"unit":"KG","unitPriceAmount":10.00,"unitPriceCurrency":"PEN","lineSubtotal":25.00}]}"""

    private fun response(status: Int, body: String, etag: String = "1") = MockResponse()
        .setResponseCode(
            status
        ).addHeader("Content-Type", "application/json").addHeader("ETag", "\"$etag\"").setBody(body)

    private class Tokens : AccessTokenSource {
        override val sessionState: StateFlow<SessionState> = MutableStateFlow(SessionState.Active)
        private val lease = AccessTokenLease("synthetic-access", 1, 1)
        override suspend fun currentAccess(): AccessTokenLease = lease
        override suspend fun recoverAfterUnauthorized(
            observed: AccessTokenLease
        ): AccessTokenLease? = null
        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit
        override suspend fun isEpochCurrent(epoch: Long): Boolean = epoch == 1L
    }

    private val authority =
        CommercialAuthority("synthetic-user", TENANT, WORKSPACE, MEMBER, setOf("sales:write"), 1)
    private companion object {
        const val TENANT = "00000000-0000-0000-0000-000000000001"
        const val WORKSPACE = "00000000-0000-0000-0000-000000000002"
        const val MEMBER = "00000000-0000-0000-0000-000000000003"
        const val CUSTOMER = "00000000-0000-0000-0000-000000000004"
        const val ORDER = "00000000-0000-0000-0000-000000000005"
        const val OTHER = "00000000-0000-0000-0000-000000000006"
    }
}
