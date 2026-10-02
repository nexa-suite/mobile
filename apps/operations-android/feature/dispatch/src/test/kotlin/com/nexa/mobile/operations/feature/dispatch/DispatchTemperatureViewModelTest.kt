package com.nexa.mobile.operations.feature.dispatch

import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DispatchTemperatureViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun outsideRangeReadingRequiresAvailablePhotoBeforeTemperaturePost() = runTest {
        val metadata = FakeMetadataStore()
        val gateway = FakeGateway(metadata)
        val viewModel = DispatchTemperatureViewModel(gateway, metadata, now = { OBSERVED_AT })
        viewModel.activate(FULFILLMENT_ID, context())
        runCurrent()
        viewModel.updateValue(LOT_ID, "9.5")

        viewModel.record(LOT_ID)
        runCurrent()

        assertEquals(
            DispatchTemperatureMutationStatus.ExcursionPhotoRequired,
            viewModel.state.value.mutationStatus
        )
        assertTrue(gateway.commands.isEmpty())

        val selection = requireNotNull(viewModel.excursionEvidenceSelectionContext(LOT_ID))
        val candidate = photoCandidate("first-image.jpg")
        gateway.photoStatus = "PROCESSING"
        viewModel.uploadExcursionEvidence(candidate, selection)
        assertEquals(
            DispatchTemperaturePhotoStatus.AwaitingAvailability,
            viewModel.state.value.photoByLotId[LOT_ID]?.status
        )
        viewModel.record(LOT_ID)
        runCurrent()
        assertTrue(gateway.commands.isEmpty())

        gateway.photoStatus = "AVAILABLE"
        viewModel.refreshExcursionEvidenceStatus(LOT_ID)
        runCurrent()
        assertEquals(
            DispatchTemperaturePhotoStatus.Available,
            viewModel.state.value.photoByLotId[LOT_ID]?.status
        )
        viewModel.record(LOT_ID)
        runCurrent()
        assertEquals(1, gateway.commands.size)
        assertEquals(EVIDENCE_ID, gateway.commands.single().evidenceObjectId)
        assertEquals(
            true,
            gateway.commands.single().exactRequestBody.contains(
                "\"evidenceObjectId\":\"$EVIDENCE_ID\""
            )
        )
    }

    @Test
    fun unknownPhotoUploadRetryUsesSameKeyAndExactPhotoFilename() = runTest {
        val metadata = FakeMetadataStore()
        val gateway = FakeGateway(metadata).apply { returnPhotoUnknownOnce = true }
        var keyNumber = 0
        val viewModel = DispatchTemperatureViewModel(
            gateway,
            metadata,
            now = { OBSERVED_AT },
            newCommandKey = { "photo-key-${++keyNumber}" }
        )
        viewModel.activate(FULFILLMENT_ID, context())
        runCurrent()
        val selection = requireNotNull(viewModel.excursionEvidenceSelectionContext(LOT_ID))

        viewModel.uploadExcursionEvidence(photoCandidate("selected-a.jpg"), selection)
        assertEquals(
            DispatchTemperaturePhotoStatus.UnknownOutcome,
            viewModel.state.value.photoByLotId[LOT_ID]?.status
        )
        viewModel.uploadExcursionEvidence(photoCandidate("selected-b.jpg"), selection)

        assertEquals(listOf("photo-key-1", "photo-key-1"), gateway.photoKeys)
        assertEquals(listOf("selected-a.jpg", "selected-a.jpg"), gateway.photoNames)
        assertEquals(
            DispatchTemperaturePhotoStatus.Available,
            viewModel.state.value.photoByLotId[LOT_ID]?.status
        )
    }

    @Test
    fun photoSelectionIsRejectedWhenFreshReadChangesWarehouse() = runTest {
        val metadata = FakeMetadataStore()
        val gateway = FakeGateway(metadata)
        val viewModel = DispatchTemperatureViewModel(gateway, metadata, now = { OBSERVED_AT })
        viewModel.activate(FULFILLMENT_ID, context())
        runCurrent()
        val selection = requireNotNull(viewModel.excursionEvidenceSelectionContext(LOT_ID))
        gateway.currentWarehouseId = OTHER_WAREHOUSE_ID

        viewModel.uploadExcursionEvidence(photoCandidate("selected.jpg"), selection)

        assertTrue(gateway.photoKeys.isEmpty())
        assertEquals(null, viewModel.state.value.photoByLotId[LOT_ID])
    }

    @Test
    fun inRangeManualCelsiusEvidenceUsesCurrentFulfillmentVersion() = runTest {
        val metadata = FakeMetadataStore()
        val gateway = FakeGateway(metadata)
        val viewModel = DispatchTemperatureViewModel(
            gateway,
            metadata,
            now = { OBSERVED_AT },
            newCommandKey = { "temperature-key" }
        )
        viewModel.activate(FULFILLMENT_ID, context())
        runCurrent()
        viewModel.updateValue(LOT_ID, "5.5")

        viewModel.record(LOT_ID)
        runCurrent()

        assertEquals(
            DispatchTemperatureMutationStatus.Recorded,
            viewModel.state.value.mutationStatus
        )
        assertEquals(1, gateway.commands.size)
        assertEquals(8L, gateway.commands.single().expectedFulfillmentVersion)
        assertEquals(4L, gateway.commands.single().expectedLotVersion)
        assertEquals(BigDecimal("5.5"), gateway.commands.single().valueCelsius)
        assertEquals("temperature-key", gateway.commands.single().idempotencyKey)
        assertEquals(null, metadata.intent)
    }

    @Test
    fun unknownOutcomeSurvivesRefreshAndRetriesTheSamePersistedCommand() = runTest {
        val metadata = FakeMetadataStore()
        val gateway = FakeGateway(metadata).apply { returnUnknownOnce = true }
        val viewModel = DispatchTemperatureViewModel(
            gateway,
            metadata,
            now = { OBSERVED_AT },
            newCommandKey = { "temperature-key" }
        )
        viewModel.activate(FULFILLMENT_ID, context())
        runCurrent()
        viewModel.updateValue(LOT_ID, "5.5")

        viewModel.record(LOT_ID)
        runCurrent()
        assertEquals(
            DispatchTemperatureMutationStatus.UnknownOutcome,
            viewModel.state.value.mutationStatus
        )
        assertEquals(DispatchTemperatureIntentStatus.UnknownOutcome, metadata.intent?.status)
        val original = gateway.commands.single()

        viewModel.refresh()
        runCurrent()
        assertEquals(
            DispatchTemperatureMutationStatus.UnknownOutcome,
            viewModel.state.value.mutationStatus
        )
        viewModel.retryUnknownOutcome()
        runCurrent()

        assertEquals(2, gateway.commands.size)
        assertEquals(original, gateway.commands.last())
        assertEquals(
            DispatchTemperatureMutationStatus.Recorded,
            viewModel.state.value.mutationStatus
        )
        assertEquals(null, metadata.intent)
    }

    private fun context() = DispatchAuthorityContext(
        authorityEpoch = 2,
        identity = DispatchAuthorityIdentity(
            userId = USER_ID,
            tenantId = TENANT_ID,
            workspaceId = WORKSPACE_ID,
            membershipId = ACTOR_ID,
            permissions = setOf(
                "fulfillment.read",
                "fulfillment.manage",
                "document.upload",
                "document.read"
            )
        )
    )

    private class FakeGateway(private val metadata: FakeMetadataStore) :
        DispatchTemperatureGateway {
        val commands = mutableListOf<DispatchTemperatureCommand>()
        val photoKeys = mutableListOf<String>()
        val photoNames = mutableListOf<String>()
        var returnUnknownOnce = false
        var returnPhotoUnknownOnce = false
        var photoStatus = "AVAILABLE"
        var currentWarehouseId = WAREHOUSE_ID

        override suspend fun current(
            fulfillmentId: String,
            context: DispatchAuthorityContext
        ): DispatchTemperatureGatewayResult = DispatchTemperatureGatewayResult.Current(
            DispatchTemperatureReadiness(
                fulfillmentId = fulfillmentId,
                fulfillmentStatus = "READY_FOR_DISPATCH",
                fulfillmentVersion = 8,
                physicalAllocationId = ALLOCATION_ID,
                physicalAllocationVersion = 4,
                temperatureRequiredForFulfillment = false,
                asOf = OBSERVED_AT,
                lots = listOf(
                    DispatchTemperatureLot(
                        skuId = SKU_ID,
                        lotId = LOT_ID,
                        warehouseId = currentWarehouseId,
                        zoneId = ZONE_ID,
                        skuColdChainRequired = true,
                        requiredForFulfillment = false,
                        minimumCelsius = BigDecimal("2"),
                        maximumCelsius = BigDecimal("8"),
                        status = "OPTIONAL_NOT_RECORDED",
                        latestEvidence = null,
                        version = 4
                    )
                )
            )
        )

        override suspend fun record(
            command: DispatchTemperatureCommand,
            context: DispatchAuthorityContext
        ): DispatchTemperatureGatewayResult {
            assertEquals(
                "exact temperature command persisted before POST",
                true,
                metadata.intent != null
            )
            commands += command
            if (returnUnknownOnce) {
                returnUnknownOnce = false
                return DispatchTemperatureGatewayResult.UnknownOutcome
            }
            return DispatchTemperatureGatewayResult.Recorded(
                DispatchTemperatureEvidence(
                    id = EVIDENCE_ID,
                    fulfillmentId = command.fulfillmentId,
                    fulfillmentVersion = command.expectedFulfillmentVersion,
                    lotId = command.lotId,
                    valueCelsius = command.valueCelsius,
                    occurredAt = command.occurredAt,
                    actorMembershipId = ACTOR_ID,
                    status = if (command.evidenceObjectId ==
                        null
                    ) {
                        "WITHIN_RANGE"
                    } else {
                        "OUT_OF_RANGE"
                    },
                    evidenceObjectId = command.evidenceObjectId,
                    expectedLotVersion = command.expectedLotVersion,
                    resultingLotVersion = command.evidenceObjectId?.let { 5L },
                    inventoryTemperatureEvaluationId = command.evidenceObjectId?.let {
                        EVALUATION_ID
                    },
                    inventoryLotStatus = command.evidenceObjectId?.let { "HOLD" },
                    affectedQuantity = command.evidenceObjectId?.let { BigDecimal("2.0") }
                )
            )
        }

        override suspend fun uploadExcursionPhoto(
            warehouseId: String,
            candidate: DispatchTemperaturePhotoCandidate,
            idempotencyKey: String,
            context: DispatchAuthorityContext
        ): DispatchTemperaturePhotoGatewayResult {
            photoKeys += idempotencyKey
            photoNames += candidate.originalFilename
            if (returnPhotoUnknownOnce) {
                returnPhotoUnknownOnce = false
                return DispatchTemperaturePhotoGatewayResult.UnknownOutcome
            }
            return DispatchTemperaturePhotoGatewayResult.Evidence(
                DispatchTemperaturePhotoEvidence(EVIDENCE_ID, "WAREHOUSE", warehouseId, photoStatus)
            )
        }

        override suspend fun excursionPhotoStatus(
            evidenceObjectId: String,
            warehouseId: String,
            context: DispatchAuthorityContext
        ): DispatchTemperaturePhotoGatewayResult = DispatchTemperaturePhotoGatewayResult.Evidence(
            DispatchTemperaturePhotoEvidence(
                evidenceObjectId,
                "WAREHOUSE",
                warehouseId,
                photoStatus
            )
        )
    }

    private class FakeMetadataStore : DispatchTemperatureMetadataStore {
        var intent: DispatchTemperatureIntent? = null
        private val photoIntents =
            mutableMapOf<Pair<String, String>, DispatchTemperaturePhotoUploadIntent>()

        override suspend fun loadIntent(
            scope: DispatchTemperatureScopeIdentity,
            fulfillmentId: String
        ): DispatchTemperatureMetadataRead = DispatchTemperatureMetadataRead.Available(
            intent?.takeIf { it.scope == scope && it.command.fulfillmentId == fulfillmentId }
        )

        override suspend fun saveIntent(
            intent: DispatchTemperatureIntent
        ): DispatchTemperatureMetadataWrite {
            this.intent = intent
            return DispatchTemperatureMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: DispatchTemperatureScopeIdentity,
            fulfillmentId: String,
            idempotencyKey: String
        ): DispatchTemperatureMetadataWrite {
            if (intent?.scope != scope || intent?.command?.fulfillmentId != fulfillmentId ||
                intent?.command?.idempotencyKey != idempotencyKey
            ) {
                return DispatchTemperatureMetadataWrite.Stale
            }
            intent = null
            return DispatchTemperatureMetadataWrite.Saved
        }

        override suspend fun loadPhotoUploadIntent(
            scope: DispatchTemperatureScopeIdentity,
            fulfillmentId: String,
            lotId: String
        ): DispatchTemperaturePhotoUploadMetadataRead =
            DispatchTemperaturePhotoUploadMetadataRead.Available(
                photoIntents[fulfillmentId to lotId]?.takeIf { it.scope == scope }
            )

        override suspend fun savePhotoUploadIntent(
            intent: DispatchTemperaturePhotoUploadIntent
        ): DispatchTemperatureMetadataWrite {
            val key = intent.fulfillmentId to intent.lotId
            val current = photoIntents[key]
            if (current != null &&
                current != intent
            ) {
                return DispatchTemperatureMetadataWrite.Conflict
            }
            photoIntents[key] = intent
            return DispatchTemperatureMetadataWrite.Saved
        }

        override suspend fun clearPhotoUploadIntent(
            scope: DispatchTemperatureScopeIdentity,
            fulfillmentId: String,
            lotId: String,
            idempotencyKey: String
        ): DispatchTemperatureMetadataWrite {
            val key = fulfillmentId to lotId
            val current = photoIntents[key] ?: return DispatchTemperatureMetadataWrite.Saved
            if (current.scope != scope || current.idempotencyKey != idempotencyKey) {
                return DispatchTemperatureMetadataWrite.Stale
            }
            photoIntents.remove(key)
            return DispatchTemperatureMetadataWrite.Saved
        }
    }

    private companion object {
        const val USER_ID = "11111111-1111-4111-8111-111111111111"
        const val TENANT_ID = "22222222-2222-4222-8222-222222222222"
        const val WORKSPACE_ID = "33333333-3333-4333-8333-333333333333"
        const val ACTOR_ID = "44444444-4444-4444-8444-444444444444"
        const val FULFILLMENT_ID = "55555555-5555-4555-8555-555555555555"
        const val ALLOCATION_ID = "66666666-6666-4666-8666-666666666666"
        const val SKU_ID = "77777777-7777-4777-8777-777777777777"
        const val LOT_ID = "88888888-8888-4888-8888-888888888888"
        const val WAREHOUSE_ID = "99999999-9999-4999-8999-999999999999"
        const val ZONE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val EVIDENCE_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val EVALUATION_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val OTHER_WAREHOUSE_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val OBSERVED_AT: Instant = Instant.parse("2026-10-01T10:15:30Z")
    }

    private fun photoCandidate(filename: String): DispatchTemperaturePhotoCandidate {
        val file = File(temporaryFolder.root, filename).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val checksum = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        return DispatchTemperaturePhotoCandidate(
            file = file,
            originalFilename = filename,
            declaredContentType = "image/jpeg",
            byteSize = file.length(),
            checksumSha256 = checksum
        )
    }
}
