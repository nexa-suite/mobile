package com.nexa.mobile.operations.core.network

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaTemperatureEvidenceGatewayTest {
    @Test
    fun partialHoldFactsRequireWellFormedServerEvidenceAndKeepUnheldLotStatus() = runTest {
        MockWebServer().use { server ->
            server.start()
            val body = response(status = "OUT_OF_RANGE").dropLast(1) +
                """, "evidenceObjectId":"$EVIDENCE_ID","expectedLotVersion":7,"resultingLotVersion":8,"inventoryTemperatureEvaluationId":"$EVIDENCE_ID","inventoryLotStatus":"AVAILABLE","affectedQuantity":5.123456789,"reason":"Affected cases","exceptionId":"$EVIDENCE_ID"}"""
            server.enqueue(jsonResponse(201, body))
            server.enqueue(jsonResponse(201, body.replace("5.123456789", "-5")))
            val adapter = gateway(server)
            val outcome = adapter.record(command(), "server-facts", MEMBERSHIP_ID)
                as TemperatureEvidenceNetworkOutcome.Confirmed
            assertEquals("5.123456789", outcome.response.affectedQuantity?.toPlainString())
            assertEquals(8L, outcome.response.resultingLotVersion)
            assertEquals("AVAILABLE", outcome.response.inventoryLotStatus)
            assertEquals(EVIDENCE_ID, outcome.response.exceptionId)
            assertEquals(
                TemperatureEvidenceNetworkOutcome.ServiceUnavailable,
                adapter.record(command(), "malformed-facts", MEMBERSHIP_ID)
            )
        }
    }

    @Test
    fun explicitAffectedQuantityAndWarehouseSourcePreservePrecisionWithoutDisposition() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(201, response(status = "OUT_OF_RANGE")))
            gateway(server).record(
                command().copy(
                    evidenceObjectId = EVIDENCE_ID,
                    expectedLotVersion = 7,
                    affectedQuantity = "5.123456789",
                    reason = "Affected cases identified during warehouse review",
                    sourceEvidenceId = EVIDENCE_ID
                ),
                "partial-hold-key",
                MEMBERSHIP_ID
            )
            val request = server.takeRequest()
            val json = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("partial-hold-key", request.getHeader("Idempotency-Key"))
            assertEquals("5.123456789", json.getValue("affectedQuantity").jsonPrimitive.content)
            assertFalse(json.getValue("affectedQuantity").jsonPrimitive.isString)
            assertEquals("7", json.getValue("expectedLotVersion").jsonPrimitive.content)
            assertEquals(EVIDENCE_ID, json.getValue("evidenceObjectId").jsonPrimitive.content)
            assertEquals(EVIDENCE_ID, json.getValue("sourceEvidenceId").jsonPrimitive.content)
            assertFalse(json.containsKey("disposition"))
            assertFalse(json.containsKey("severity"))
            assertFalse(json.containsKey("status"))
        }
    }

    @Test
    fun recordsExactManualEvidenceWithStableKeyAndProjectsServerClassification() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(201, response(status = "OUT_OF_RANGE")))
            val outcome = gateway(server).record(command(), "temperature-key-01", MEMBERSHIP_ID)
                as TemperatureEvidenceNetworkOutcome.Confirmed
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            val json = Json.parseToJsonElement(body).jsonObject

            assertEquals("POST", request.method)
            assertEquals("/api/v1/temperature-evidence", request.path)
            assertEquals("temperature-key-01", request.getHeader("Idempotency-Key"))
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            assertEquals("LOT", json.getValue("subjectType").jsonPrimitive.content)
            assertEquals(LOT_ID, json.getValue("subjectId").jsonPrimitive.content)
            assertEquals("-18.765432100", json.getValue("value").jsonPrimitive.content)
            assertFalse(json.getValue("value").jsonPrimitive.isString)
            assertEquals("CELSIUS", json.getValue("unit").jsonPrimitive.content)
            assertEquals("2026-09-30T15:22:33Z", json.getValue("occurredAt").jsonPrimitive.content)
            assertEquals("OUT_OF_RANGE", outcome.response.status)
            assertEquals("MANUAL", outcome.response.source)
            assertEquals("-18.765432100", outcome.response.value.toPlainString())
            assertEquals(MEMBERSHIP_ID, outcome.response.actorMembershipId)
            assertFalse(body.contains("temperatureReading"))
            assertFalse(body.contains("disposition"))
        }
    }

    @Test
    fun mismatchedAndMalformedSuccessfulBodiesNeverBecomeConfirmed() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(201, "not-json"))
            server.enqueue(jsonResponse(201, response().replace(LOT_ID, OTHER_LOT_ID)))
            val gateway = gateway(server)

            assertEquals(
                TemperatureEvidenceNetworkOutcome.ServiceUnavailable,
                gateway.record(command(), "same-key-1", MEMBERSHIP_ID)
            )
            assertEquals(
                TemperatureEvidenceNetworkOutcome.ServiceUnavailable,
                gateway.record(command(), "same-key-2", MEMBERSHIP_ID)
            )
        }
    }

    @Test
    fun knownRejectionAndPermissionContextFailuresRemainDistinct() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(422, "INVALID_TEMPERATURE_EVIDENCE"))
            server.enqueue(problemResponse(403, "ACCESS_DENIED"))
            server.enqueue(problemResponse(403, "ACCESS_CONTEXT_INVALID"))
            val gateway = gateway(server)

            assertEquals(
                TemperatureEvidenceNetworkOutcome.Rejected("INVALID_TEMPERATURE_EVIDENCE"),
                gateway.record(command(), "reject-key", MEMBERSHIP_ID)
            )
            assertEquals(
                TemperatureEvidenceNetworkOutcome.PermissionDenied,
                gateway.record(command(), "deny-key", MEMBERSHIP_ID)
            )
            assertEquals(
                TemperatureEvidenceNetworkOutcome.ContextInvalidated,
                gateway.record(command(), "scope-key", MEMBERSHIP_ID)
            )
        }
    }

    @Test
    fun transportFailureIsUnknownAndRequiresTheSameExplicitCommandIdentity() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))
            val outcome = gateway(server).record(command(), "unknown-key", MEMBERSHIP_ID)
            val request = server.takeRequest()

            assertEquals(TemperatureEvidenceNetworkOutcome.UnknownOutcome, outcome)
            assertEquals("unknown-key", request.getHeader("Idempotency-Key"))
            assertTrue(request.body.readUtf8().contains("-18.765432100"))
        }
    }

    private fun gateway(server: MockWebServer) = NexaTemperatureEvidenceGateway(
        ProtectedCallExecutor(
            ApiEndpoint(server.url("/").toString()),
            ApiHttpClient.create(ApiEndpoint(server.url("/").toString())),
            FakeAccessTokenSource()
        )
    )

    private fun command() = TemperatureEvidenceCommandWire(
        TemperatureSubjectTypeWire.LOT,
        LOT_ID,
        "-18.765432100",
        TemperatureUnitWire.CELSIUS,
        Instant.parse("2026-09-30T15:22:33Z")
    )

    private fun response(status: String = "WITHIN_RANGE") =
        """{"id":"$EVIDENCE_ID","subjectType":"LOT","subjectId":"$LOT_ID","lotId":"$LOT_ID","warehouseId":"$WAREHOUSE_ID","value":-18.765432100,"unit":"CELSIUS","occurredAt":"2026-09-30T15:22:33Z","actorMembershipId":"$MEMBERSHIP_ID","status":"$status","source":"MANUAL"}"""

    private fun jsonResponse(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun problemResponse(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/problem+json")
        .setBody("""{"status":$status,"code":"$code","category":"CLIENT_ERROR"}""")

    private class FakeAccessTokenSource : AccessTokenSource {
        override val sessionState: StateFlow<SessionState> = MutableStateFlow(SessionState.Active)
        private val lease = AccessTokenLease("access-1", generation = 1, epoch = 1)

        override suspend fun currentAccess(): AccessTokenLease = lease

        override suspend fun recoverAfterUnauthorized(
            observed: AccessTokenLease
        ): AccessTokenLease? = null

        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit

        override suspend fun isEpochCurrent(epoch: Long): Boolean = epoch == 1L
    }

    private companion object {
        const val LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413401"
        const val OTHER_LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413402"
        const val WAREHOUSE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val EVIDENCE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413501"
        const val MEMBERSHIP_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413601"
    }
}
