package com.nexa.mobile.operations.inventoryavailability.infrastructure.transport

import com.nexa.mobile.operations.core.network.ApiEndpoint
import com.nexa.mobile.operations.core.network.ApiHttpClient
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaReceivingGatewayTest {
    @Test
    fun warehouseAndZoneLookupsUseCanonicalPagedRoutesAndProjectServerChoices() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(
                    200,
                    """{"items":[{"id":"$WAREHOUSE_A","code":"WH-A","name":"North","status":"ACTIVE","version":1}],"page":0,"size":100,"total":2}"""
                )
            )
            server.enqueue(
                jsonResponse(
                    200,
                    """{"items":[{"id":"$WAREHOUSE_B","code":"WH-B","name":"South","status":"INACTIVE","version":1}],"page":1,"size":100,"total":2}"""
                )
            )
            server.enqueue(
                jsonResponse(
                    200,
                    """{"items":[{"id":"$ZONE_A","warehouseId":"$WAREHOUSE_A","code":"COLD","name":"Cold room","status":"ACTIVE","type":"COLD","version":4}],"page":0,"size":100,"total":1}"""
                )
            )
            val gateway = gateway(server)

            val warehouses = gateway.warehouses() as ReceivingNetworkOutcome.Warehouses
            val zones = gateway.zones(WAREHOUSE_A) as ReceivingNetworkOutcome.Zones

            assertEquals(listOf(WAREHOUSE_A, WAREHOUSE_B), warehouses.items.map { it.id })
            assertEquals("North", warehouses.items.first().name)
            assertEquals(1, zones.items.size)
            assertEquals(ZONE_A, zones.items.single().id)
            assertEquals(WAREHOUSE_A, zones.items.single().warehouseId)
            assertEquals("ACTIVE", zones.items.single().status)
            assertEquals(
                "/api/v1/warehouses?page=0&size=100&sort=code,asc",
                server.takeRequest().path
            )
            assertEquals(
                "/api/v1/warehouses?page=1&size=100&sort=code,asc",
                server.takeRequest().path
            )
            assertEquals(
                "/api/v1/warehouses/$WAREHOUSE_A/zones?page=0&size=100",
                server.takeRequest().path
            )
        }
    }

    @Test
    fun malformedIdsAndMalformedLookupPagesNeverBecomeChoices() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                jsonResponse(
                    200,
                    """{"items":[{"id":"../x","code":"A","name":"Fake","status":"ACTIVE"}],"page":0,"size":100,"total":1}"""
                )
            )
            val gateway = gateway(server)

            assertEquals(ReceivingNetworkOutcome.ServiceUnavailable, gateway.warehouses())
            assertEquals(
                ReceivingNetworkOutcome.ServiceUnavailable,
                gateway.zones("../$WAREHOUSE_A")
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun receiptUsesCanonicalRouteExactNumericDecimalsAndOnlyProtectedHeaders() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(201, lotJson(onHand = "0.0100", unit = "KG")))
            val command = receipt(
                catalogItemId = "CAT-0017",
                skuId = SKU_ID,
                quantity = "0.0100",
                temperatureReading = "-1.250"
            ).copy(temperatureEvidenceObjectId = EVIDENCE_ID)

            val outcome = gateway(server).receive(command, "arrival-intent-01")
                as ReceivingNetworkOutcome.Confirmed
            val request = server.takeRequest()
            val body = request.body.readUtf8()

            assertEquals(WAREHOUSE_A, outcome.lot.warehouseId)
            assertEquals(ZONE_A, outcome.lot.zoneId)
            assertEquals("0.0100", outcome.lot.onHand.toPlainString())
            assertEquals("KG", outcome.lot.unit)
            assertEquals("POST", request.method)
            assertEquals("/api/v1/inventory/inbound-receipts", request.path)
            assertEquals("arrival-intent-01", request.getHeader("Idempotency-Key"))
            assertEquals("Bearer access-1", request.getHeader("Authorization"))
            val json = Json.parseToJsonElement(body).jsonObject
            assertFalse(json.getValue("quantity").jsonPrimitive.isString)
            assertEquals("0.0100", json.getValue("quantity").jsonPrimitive.content)
            assertFalse(json.getValue("temperatureReading").jsonPrimitive.isString)
            assertEquals("-1.250", json.getValue("temperatureReading").jsonPrimitive.content)
            assertEquals(
                EVIDENCE_ID,
                json.getValue("temperatureEvidenceObjectId").jsonPrimitive.content
            )
            assertTrue(body.contains("\"catalogItemId\":\"CAT-0017\""))
            assertTrue(body.contains("\"skuId\":\"$SKU_ID\""))
            assertFalse(body.contains("NaN"))
            assertNull(request.getHeader("X-Nexa-Client"))
            assertNull(request.getHeader("X-Nexa-Surface"))
            assertNull(request.getHeader("X-Nexa-Refresh-Token"))
        }
    }

    @Test
    fun temperatureEvidenceUploadAndStatusUseTheExactWarehouseSubject() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(201, evidenceJson(WAREHOUSE_A)))
            server.enqueue(jsonResponse(200, evidenceJson(WAREHOUSE_A)))
            server.enqueue(jsonResponse(200, evidenceJson(WAREHOUSE_B)))
            val file = File.createTempFile("receiving-evidence", ".jpg")
            try {
                file.writeBytes(byteArrayOf(1, 2, 3, 4))
                val gateway = gateway(server)
                val uploaded = gateway.uploadTemperatureEvidence(
                    warehouseId = WAREHOUSE_A,
                    idempotencyKey = "receiving-evidence-01",
                    file = file,
                    originalFilename = "temperature.jpg",
                    declaredContentType = "image/jpeg",
                    byteSize = file.length(),
                    checksumSha256 = "a".repeat(64)
                ) as ReceivingNetworkOutcome.EvidenceUploaded
                val uploadRequest = server.takeRequest()
                val multipart = uploadRequest.body.readUtf8()
                val status = gateway.temperatureEvidenceStatus(EVIDENCE_ID, WAREHOUSE_A)
                val statusRequest = server.takeRequest()
                val mismatched = gateway.temperatureEvidenceStatus(EVIDENCE_ID, WAREHOUSE_A)

                assertEquals("AVAILABLE", uploaded.evidence.lifecycleStatus)
                assertEquals("POST", uploadRequest.method)
                assertEquals("/api/v1/business-document-evidence", uploadRequest.path)
                assertEquals("receiving-evidence-01", uploadRequest.getHeader("Idempotency-Key"))
                assertTrue(multipart.contains("name=\"subjectType\""))
                assertTrue(multipart.contains("WAREHOUSE"))
                assertTrue(multipart.contains("name=\"subjectId\""))
                assertTrue(multipart.contains(WAREHOUSE_A))
                assertTrue(status is ReceivingNetworkOutcome.EvidenceStatus)
                assertEquals("GET", statusRequest.method)
                assertEquals("/api/v1/business-document-evidence/$EVIDENCE_ID", statusRequest.path)
                assertEquals(ReceivingNetworkOutcome.ServiceUnavailable, mismatched)
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun eitherCatalogOrSkuIdentifierCanBeSentWithoutClientConversion() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(201, lotJson()))
            server.enqueue(jsonResponse(201, lotJson(catalogItemId = "CAT-0017")))
            val gateway = gateway(server)

            assertTrue(
                gateway.receive(receipt(catalogItemId = "CAT-0017", skuId = null), "cat-only")
                    is ReceivingNetworkOutcome.Confirmed
            )
            val catOnly = server.takeRequest().body.readUtf8()
            assertTrue(catOnly.contains("\"catalogItemId\":\"CAT-0017\""))
            assertFalse(catOnly.contains("skuId"))

            assertTrue(
                gateway.receive(receipt(catalogItemId = null, skuId = SKU_ID), "sku-only")
                    is ReceivingNetworkOutcome.Confirmed
            )
            val skuOnly = server.takeRequest().body.readUtf8()
            assertFalse(skuOnly.contains("catalogItemId"))
            assertTrue(skuOnly.contains("\"skuId\":\"$SKU_ID\""))
        }
    }

    @Test
    fun malformedOrInconsistentSuccessfulReceiptIsUnknownNotConfirmed() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(201, "not-json"))
            server.enqueue(jsonResponse(201, lotJson(onHand = "99")))
            val gateway = gateway(server)

            assertEquals(
                ReceivingNetworkOutcome.UnknownOutcome,
                gateway.receive(receipt(), "same-intent-a")
            )
            assertEquals(
                ReceivingNetworkOutcome.UnknownOutcome,
                gateway.receive(receipt(), "same-intent-b")
            )
        }
    }

    @Test
    fun knownClientRejectionAndPermissionFailureStayDistinctFromUnknown() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(400, "INVALID_REQUEST"))
            server.enqueue(problemResponse(403, "PERMISSION_DENIED"))
            server.enqueue(problemResponse(403, "ACCESS_CONTEXT_INVALID"))
            val gateway = gateway(server)

            assertEquals(
                ReceivingNetworkOutcome.Rejected("INVALID_REQUEST"),
                gateway.receive(receipt(), "known-rejection")
            )
            assertEquals(
                ReceivingNetworkOutcome.PermissionDenied,
                gateway.receive(receipt(), "permission-denial")
            )
            assertEquals(
                ReceivingNetworkOutcome.ContextInvalidated,
                gateway.receive(receipt(), "context-invalid")
            )
        }
    }

    @Test
    fun changedPayloadConflictForAnExistingKeyRemainsARejection() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(409, "IDEMPOTENCY_PAYLOAD_CONFLICT"))
            val command = receipt(quantity = "0.0200")

            assertEquals(
                ReceivingNetworkOutcome.Rejected("IDEMPOTENCY_PAYLOAD_CONFLICT"),
                gateway(server).receive(command, "previous-arrival-key")
            )
            val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertFalse(sent.getValue("quantity").jsonPrimitive.isString)
            assertEquals("0.0200", sent.getValue("quantity").jsonPrimitive.content)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun uncertainPostResponseAndDisconnectRemainUnknownWithoutRetry() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(503, "TEMPORARY_FAILURE"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val source = FakeAccessTokenSource()
            val gateway = gateway(server, source)

            assertEquals(
                ReceivingNetworkOutcome.UnknownOutcome,
                gateway.receive(receipt(), "uncertain-a")
            )
            assertEquals(
                ReceivingNetworkOutcome.UnknownOutcome,
                gateway.receive(receipt(), "uncertain-b")
            )
            assertEquals(0, source.recoverCount)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun definiteUnauthorizedReceiptReplayKeepsSameKeyAndByteIdenticalBody() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(problemResponse(401, "ACCESS_TOKEN_INVALID"))
            server.enqueue(jsonResponse(201, lotJson()))
            val source = FakeAccessTokenSource()
            val gateway = gateway(server, source)
            val command = receipt()

            assertTrue(
                gateway.receive(
                    command,
                    "arrival-401-safe-replay"
                ) is ReceivingNetworkOutcome.Confirmed
            )
            val first = server.takeRequest()
            val replay = server.takeRequest()
            assertEquals(1, source.recoverCount)
            assertEquals("arrival-401-safe-replay", first.getHeader("Idempotency-Key"))
            assertEquals(first.getHeader("Idempotency-Key"), replay.getHeader("Idempotency-Key"))
            assertEquals(first.body.readUtf8(), replay.body.readUtf8())
            assertEquals("Bearer access-1", first.getHeader("Authorization"))
            assertEquals("Bearer access-2", replay.getHeader("Authorization"))
        }
    }

    private fun gateway(
        server: MockWebServer,
        source: FakeAccessTokenSource = FakeAccessTokenSource()
    ): NexaReceivingGateway {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return NexaReceivingGateway(
            ProtectedCallExecutor(endpoint, ApiHttpClient.create(endpoint), source)
        )
    }

    private fun receipt(
        catalogItemId: String? = "CAT-0017",
        skuId: String? = NexaReceivingGatewayTest.SKU_ID,
        quantity: String = "2.500",
        temperatureReading: String? = null
    ) = InboundReceiptCommand(
        warehouseId = WAREHOUSE_A,
        zoneId = ZONE_A,
        catalogItemId = catalogItemId,
        skuId = skuId,
        batchNumber = "LOT-REAL-7",
        expirationDate = LocalDate.parse("2099-06-30"),
        quantity = BigDecimal(quantity),
        unit = "kg",
        temperatureReading = temperatureReading?.let(::BigDecimal)
    )

    private fun lotJson(
        onHand: String = "2.500",
        unit: String = "KG",
        catalogItemId: String? = "CAT-0017",
        skuId: String? = NexaReceivingGatewayTest.SKU_ID
    ): String =
        """{"id":"$LOT_ID","warehouseId":"$WAREHOUSE_A","zoneId":"$ZONE_A","catalogItemId":${catalogItemId.jsonOrNull()},"skuId":${skuId.jsonOrNull()},"batchNumber":"LOT-REAL-7","expirationDate":"2099-06-30","receivedAt":"2026-09-30T15:00:00Z","onHand":$onHand,"reserved":0,"available":$onHand,"unit":"$unit","status":"AVAILABLE","version":1}"""

    private fun evidenceJson(warehouseId: String) =
        """{"id":"$EVIDENCE_ID","subjectType":"WAREHOUSE","subjectId":"$warehouseId","lifecycleStatus":"AVAILABLE","declaredContentType":"image/jpeg","checksumSha256":"${"a".repeat(
            64
        )}","byteSize":4}"""

    private fun String?.jsonOrNull(): String = this?.let { "\"$it\"" } ?: "null"

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
        private var lease = AccessTokenLease("access-1", generation = 1, epoch = 1)
        var recoverCount = 0

        override suspend fun currentAccess(): AccessTokenLease = lease

        override suspend fun recoverAfterUnauthorized(
            observed: AccessTokenLease
        ): AccessTokenLease {
            recoverCount++
            lease = AccessTokenLease("access-2", generation = 2, epoch = observed.epoch)
            return lease
        }

        override suspend fun rejectCurrentAccess(observed: AccessTokenLease) = Unit

        override suspend fun isEpochCurrent(epoch: Long): Boolean = epoch == 1L
    }

    private companion object {
        const val WAREHOUSE_A = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val WAREHOUSE_B = "b8c24a46-57d9-4f64-8fa7-6a641b413102"
        const val ZONE_A = "b8c24a46-57d9-4f64-8fa7-6a641b413201"
        const val SKU_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413301"
        const val LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413401"
        const val EVIDENCE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413501"
    }
}
