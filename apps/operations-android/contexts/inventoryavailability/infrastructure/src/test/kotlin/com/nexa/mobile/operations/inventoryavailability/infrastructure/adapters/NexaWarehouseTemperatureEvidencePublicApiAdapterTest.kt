package com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.ApiEndpoint
import com.nexa.mobile.operations.core.network.ApiHttpClient
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.inventoryavailability.application.publicapi.WarehouseTemperatureEvidenceProjection
import com.nexa.mobile.operations.inventoryavailability.application.publicapi.WarehouseTemperatureEvidenceResult
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.NexaReceivingGateway
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class NexaWarehouseTemperatureEvidencePublicApiAdapterTest {
    @Test
    fun uploadAndStatusExposeImmutableWarehouseEvidenceProjections() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(201, evidenceJson()))
            server.enqueue(jsonResponse(200, evidenceJson()))
            val file = File.createTempFile("temperature-evidence", ".jpg")
            try {
                file.writeBytes(byteArrayOf(1, 2, 3, 4))
                val api = NexaWarehouseTemperatureEvidencePublicApiAdapter(
                    NexaReceivingGateway(executor(server))
                )

                val uploaded = api.uploadTemperatureEvidence(
                    warehouseId = WAREHOUSE_ID,
                    idempotencyKey = "photo-key-01",
                    file = file,
                    originalFilename = "temperature.jpg",
                    declaredContentType = "image/jpeg",
                    byteSize = file.length(),
                    checksumSha256 = CHECKSUM
                )
                val uploadRequest = server.takeRequest()
                val status = api.temperatureEvidenceStatus(EVIDENCE_ID, WAREHOUSE_ID)
                val statusRequest = server.takeRequest()

                assertEquals(
                    WarehouseTemperatureEvidenceResult.Uploaded(PROJECTION),
                    uploaded
                )
                assertEquals(WarehouseTemperatureEvidenceResult.Status(PROJECTION), status)
                assertEquals("POST", uploadRequest.method)
                assertEquals("photo-key-01", uploadRequest.getHeader("Idempotency-Key"))
                assertEquals("GET", statusRequest.method)
                assertEquals(
                    "/api/v1/business-document-evidence/$EVIDENCE_ID",
                    statusRequest.path
                )
            } finally {
                file.delete()
            }
        }
    }

    private fun executor(server: MockWebServer): ProtectedCallExecutor {
        val endpoint = ApiEndpoint(server.url("/").toString())
        return ProtectedCallExecutor(
            endpoint,
            ApiHttpClient.create(endpoint),
            FakeAccessTokenSource()
        )
    }

    private fun evidenceJson() =
        """{"id":"$EVIDENCE_ID","subjectType":"WAREHOUSE","subjectId":"$WAREHOUSE_ID","lifecycleStatus":"AVAILABLE","declaredContentType":"image/jpeg","checksumSha256":"$CHECKSUM","byteSize":4}"""

    private fun jsonResponse(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

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
        const val WAREHOUSE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val EVIDENCE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413501"
        const val CHECKSUM = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        val PROJECTION = WarehouseTemperatureEvidenceProjection(
            id = EVIDENCE_ID,
            subjectType = "WAREHOUSE",
            subjectId = WAREHOUSE_ID,
            lifecycleStatus = "AVAILABLE"
        )
    }
}
