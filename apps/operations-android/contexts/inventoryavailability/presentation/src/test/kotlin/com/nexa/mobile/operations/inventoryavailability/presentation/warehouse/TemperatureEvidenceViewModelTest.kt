@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidenceAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidenceDraft
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidenceIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidencePhoto
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidencePhotoCandidate
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidencePhotoSelection
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidenceScope
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureIntentStatus
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperaturePhotoResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureSubmitResult
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.TemperatureEvidenceGateway
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.TemperatureEvidenceMetadataStore
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.WarehouseEvidenceFileCandidate
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.WarehouseEvidenceFileSelectionPort
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.WarehouseEvidenceSelectionCoordinator
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceFacts
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidencePayload
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceSubject
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceSubjectType
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceUnit
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TemperatureEvidenceViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun selectedServerSubjectAndOutOfRangeResponseAreShownWithoutLocalDisposition() = runTest {
        val store = MemoryMetadataStore()
        val gateway = FakeGateway().apply {
            submitResults += TemperatureSubmitResult.Confirmed(facts(status = "OUT_OF_RANGE"))
            lifecycleEvents = store.events
        }
        val viewModel = viewModel(gateway, store)
        viewModel.activate(authority())
        advanceUntilIdle()
        val listed = gateway.subjectCalls.single()
        assertEquals(TemperatureEvidenceSubjectType.LOT, listed)
        assertEquals("LOT-A · ACTIVE", viewModel.state.value.subjects.single().primaryLabel)

        viewModel.selectSubject(viewModel.state.value.subjects.single())
        advanceUntilIdle()
        viewModel.valueChanged("-18.765432100")
        viewModel.unitChanged(TemperatureEvidenceUnit.CELSIUS)
        viewModel.occurredAtChanged("2026-09-30T10:22:33-05:00")
        viewModel.stageAndRecord()
        advanceUntilIdle()

        val submitted = gateway.commands.single()
        assertEquals("-18.765432100", submitted.first.value)
        assertEquals("2026-09-30T15:22:33Z", submitted.first.occurredAt)
        assertEquals("temperature-key-1", submitted.second)
        assertEquals(TemperatureCommandStatus.Confirmed, viewModel.state.value.command)
        assertEquals("OUT_OF_RANGE", viewModel.state.value.confirmed?.status)
        assertNull(viewModel.state.value.notice)
        assertEquals(listOf("intent", "request", "clear"), store.events)
        assertFalse(store.draft?.toString().orEmpty().contains("temperature-key-1"))
    }

    @Test
    fun unknownOutcomeKeepsFrozenExactPayloadAndRetryReusesTheSameKey() = runTest {
        val store = MemoryMetadataStore()
        val gateway = FakeGateway().apply {
            submitResults += TemperatureSubmitResult.UnknownOutcome
            submitResults += TemperatureSubmitResult.Confirmed(facts(status = "UNKNOWN"))
        }
        val viewModel = viewModel(gateway, store)
        viewModel.activate(authority())
        advanceUntilIdle()
        val subject = viewModel.state.value.subjects.single()
        viewModel.selectSubject(subject)
        advanceUntilIdle()
        viewModel.valueChanged("4.2500")
        viewModel.occurredAtChanged("2026-09-30T15:22:33Z")
        viewModel.stageAndRecord()
        advanceUntilIdle()

        assertEquals(TemperatureCommandStatus.UnknownOutcome, viewModel.state.value.command)
        val frozen = requireNotNull(store.intent)
        assertEquals(TemperatureIntentStatus.UnknownOutcome, frozen.status)
        viewModel.valueChanged("100")
        viewModel.retryUnknownOutcome()
        advanceUntilIdle()

        assertEquals(
            listOf("temperature-key-1", "temperature-key-1"),
            gateway.commands.map {
                it.second
            }
        )
        assertEquals("4.2500", gateway.commands.last().first.value)
        assertEquals("UNKNOWN", viewModel.state.value.confirmed?.status)
        assertEquals(TemperatureCommandStatus.Confirmed, viewModel.state.value.command)
        assertNull(store.intent)
    }

    @Test
    fun restoredPendingBecomesUnknownAndOnlyExplicitRetryReusesExactExcursionPayload() = runTest {
        val scope = authority().scope
        val store = MemoryMetadataStore().apply {
            intent = TemperatureEvidenceIntent(
                scope,
                "restored-key",
                payload(),
                TemperatureIntentStatus.Pending
            )
        }
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway, store)

        viewModel.activate(authority())
        advanceUntilIdle()

        assertEquals(TemperatureCommandStatus.UnknownOutcome, viewModel.state.value.command)
        assertEquals(TemperatureIntentStatus.UnknownOutcome, store.intent?.status)
        assertTrue(gateway.commands.isEmpty())
        assertEquals("restored-key", store.intent?.idempotencyKey)
        assertEquals("-18.765432100", store.intent?.payload?.value)
        assertEquals(PHOTO_ID, store.intent?.payload?.evidenceObjectId)
        assertEquals(7L, store.intent?.payload?.expectedLotVersion)
        assertEquals("5.125000001", store.intent?.payload?.affectedQuantity)
        assertEquals("Warehouse exception", store.intent?.payload?.reason)
        assertEquals(SOURCE_EVIDENCE_ID, store.intent?.payload?.sourceEvidenceId)

        viewModel.retryUnknownOutcome()
        advanceUntilIdle()

        assertEquals(1, gateway.commands.size)
        assertEquals("restored-key", gateway.commands.single().second)
        assertEquals(payload(), gateway.commands.single().first)
    }

    @Test
    fun missingPermissionOrUnavailableMetadataNeverDispatchesAndLateResponseIsDiscarded() =
        runTest {
            val noPermissionGateway = FakeGateway()
            val noPermission = viewModel(noPermissionGateway, MemoryMetadataStore())
            noPermission.activate(authority(permissions = setOf("warehouse.read")))
            advanceUntilIdle()
            fill(noPermission)
            noPermission.stageAndRecord()
            advanceUntilIdle()
            assertTrue(noPermissionGateway.commands.isEmpty())

            val unavailableStore = MemoryMetadataStore().apply { available = false }
            val unavailableGateway = FakeGateway()
            val unavailable = viewModel(unavailableGateway, unavailableStore)
            unavailable.activate(authority())
            advanceUntilIdle()
            fill(unavailable)
            unavailable.stageAndRecord()
            advanceUntilIdle()
            assertTrue(unavailableGateway.commands.isEmpty())

            val response = CompletableDeferred<TemperatureSubmitResult>()
            val delayedGateway = FakeGateway().apply { pendingResponse = response }
            val delayed = viewModel(delayedGateway, MemoryMetadataStore())
            delayed.activate(authority())
            advanceUntilIdle()
            fill(delayed)
            delayed.stageAndRecord()
            advanceUntilIdle()
            delayed.deactivate()
            response.complete(TemperatureSubmitResult.Confirmed(facts(status = "WITHIN_RANGE")))
            advanceUntilIdle()
            assertNull(delayed.state.value.confirmed)
            assertEquals(0, delayed.state.value.authorityEpoch)
        }

    @Test
    fun returnedWarehousePhotoFromAIsRejectedAfterScopeChangesToBBeforeCopy() = runTest {
        val store = MemoryMetadataStore()
        val gateway = FakeGateway()
        val files = FakeWarehouseSelectionPort()
        val viewModel = viewModel(gateway, store, files)
        viewModel.activate(authority())
        advanceUntilIdle()
        viewModel.selectSubject(viewModel.state.value.subjects.single())
        advanceUntilIdle()
        val selectionA = requireNotNull(viewModel.photoSelectionContext())

        viewModel.activate(authority(epoch = 10, userId = "user-b"))
        advanceUntilIdle()

        assertFalse(viewModel.uploadReturnedPhoto(selectionA, "content://picker/photo"))
        assertEquals(0, files.prepareCalls)
        assertEquals(0, gateway.photoUploadCalls)
    }

    @Test
    fun cancellationDuringWarehousePhotoUploadDeletesItsPrivateCopy() = runTest {
        val store = MemoryMetadataStore()
        val gateway = FakeGateway().apply { cancelPhotoUpload = true }
        val files = FakeWarehouseSelectionPort()
        val viewModel = viewModel(gateway, store, files)
        viewModel.activate(authority())
        advanceUntilIdle()
        viewModel.selectSubject(viewModel.state.value.subjects.single())
        advanceUntilIdle()
        val selection = requireNotNull(viewModel.photoSelectionContext())

        try {
            viewModel.uploadReturnedPhoto(selection, "content://picker/photo")
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            // The selection action must release its private copy before propagating cancellation.
        }

        assertEquals(false, requireNotNull(files.lastCandidate).file.exists())
        assertEquals(1, gateway.photoUploadCalls)
    }

    private fun viewModel(
        gateway: FakeGateway,
        store: MemoryMetadataStore,
        files: FakeWarehouseSelectionPort? = null
    ) = TemperatureEvidenceViewModel(
        gateway,
        store,
        now = { Instant.parse("2026-09-30T15:22:33Z") },
        newIdempotencyKey = { "temperature-key-1" },
        evidenceSelection = files?.let(::WarehouseEvidenceSelectionCoordinator)
    )

    private suspend fun TestScope.fill(viewModel: TemperatureEvidenceViewModel) {
        viewModel.selectSubject(viewModel.state.value.subjects.single())
        advanceUntilIdle()
        viewModel.valueChanged("-18.765")
        viewModel.occurredAtChanged("2026-09-30T15:22:33Z")
    }

    private fun authority(
        permissions: Set<String> = setOf("inventory.receive", "inventory.read"),
        epoch: Long = 9,
        userId: String = USER_ID
    ) = TemperatureEvidenceAuthority(
        userId,
        TENANT_ID,
        WORKSPACE_ID,
        MEMBERSHIP_ID,
        permissions,
        authorityEpoch = epoch
    )

    private fun payload() = TemperatureEvidencePayload(
        subjectType = TemperatureEvidenceSubjectType.LOT,
        subjectId = LOT_ID,
        value = "-18.765432100",
        unit = TemperatureEvidenceUnit.CELSIUS,
        occurredAt = "2026-09-30T15:22:33Z",
        evidenceObjectId = PHOTO_ID,
        expectedLotVersion = 7,
        affectedQuantity = "5.125000001",
        reason = "Warehouse exception",
        sourceEvidenceId = SOURCE_EVIDENCE_ID
    )

    private fun facts(status: String) = TemperatureEvidenceFacts(
        id = EVIDENCE_ID,
        subjectType = TemperatureEvidenceSubjectType.LOT,
        subjectId = LOT_ID,
        lotId = LOT_ID,
        warehouseId = WAREHOUSE_ID,
        value = BigDecimal("-18.765432100"),
        unit = TemperatureEvidenceUnit.CELSIUS,
        occurredAt = Instant.parse("2026-09-30T15:22:33Z"),
        actorMembershipId = MEMBERSHIP_ID,
        status = status,
        source = "MANUAL",
        remainingHeldQuantity = BigDecimal("5.125000001")
    )

    private inner class FakeGateway : TemperatureEvidenceGateway {
        val subjectCalls = mutableListOf<TemperatureEvidenceSubjectType>()
        val commands = mutableListOf<Pair<TemperatureEvidencePayload, String>>()
        val submitResults = mutableListOf<TemperatureSubmitResult>()
        var lifecycleEvents: MutableList<String> = mutableListOf()
        var pendingResponse: CompletableDeferred<TemperatureSubmitResult>? = null
        var photoUploadCalls = 0
        var cancelPhotoUpload = false

        override suspend fun uploadPhoto(
            selection: TemperatureEvidencePhotoSelection,
            candidate: TemperatureEvidencePhotoCandidate,
            idempotencyKey: String,
            authority: TemperatureEvidenceAuthority
        ): TemperaturePhotoResult {
            photoUploadCalls++
            if (cancelPhotoUpload) throw CancellationException()
            return TemperaturePhotoResult.Evidence(
                TemperatureEvidencePhoto(
                    "photo-id",
                    "WAREHOUSE",
                    selection.warehouseId,
                    "AVAILABLE"
                )
            )
        }

        override suspend fun subjects(
            type: TemperatureEvidenceSubjectType,
            authority: TemperatureEvidenceAuthority
        ): TemperatureLookupResult {
            subjectCalls += type
            return TemperatureLookupResult.Subjects(
                listOf(
                    TemperatureEvidenceSubject(
                        id = if (type ==
                            TemperatureEvidenceSubjectType.LOT
                        ) {
                            LOT_ID
                        } else {
                            WAREHOUSE_ID
                        },
                        type = type,
                        primaryLabel = if (type == TemperatureEvidenceSubjectType.LOT) {
                            "LOT-A · ACTIVE"
                        } else {
                            "Cold store · WH-1"
                        },
                        detailLabel = "Warehouse $WAREHOUSE_ID",
                        warehouseId = WAREHOUSE_ID,
                        lotVersion = if (type == TemperatureEvidenceSubjectType.LOT) 7 else null,
                        physicalRemaining = if (type == TemperatureEvidenceSubjectType.LOT) {
                            BigDecimal("20.000000000")
                        } else {
                            null
                        },
                        quantityUnit = if (type ==
                            TemperatureEvidenceSubjectType.LOT
                        ) {
                            "kg"
                        } else {
                            null
                        }
                    )
                )
            )
        }

        override suspend fun record(
            payload: TemperatureEvidencePayload,
            idempotencyKey: String,
            authority: TemperatureEvidenceAuthority
        ): TemperatureSubmitResult {
            commands += payload to idempotencyKey
            lifecycleEvents += "request"
            pendingResponse?.let { return it.await() }
            return submitResults.removeFirstOrNull()
                ?: TemperatureSubmitResult.Confirmed(facts(status = "WITHIN_RANGE"))
        }
    }

    private inner class FakeWarehouseSelectionPort : WarehouseEvidenceFileSelectionPort {
        var prepareCalls = 0
        var lastCandidate: WarehouseEvidenceFileCandidate? = null

        override suspend fun prepare(
            sourceUri: String,
            scopeKey: String
        ): WarehouseEvidenceFileCandidate {
            prepareCalls++
            val file = File.createTempFile("warehouse-photo-", ".jpg")
            val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
            file.writeBytes(bytes)
            val candidate = WarehouseEvidenceFileCandidate(
                file = file,
                originalFilename = file.name,
                declaredContentType = "image/jpeg",
                byteSize = file.length(),
                checksumSha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
            )
            lastCandidate = candidate
            return candidate
        }

        override fun discard(candidate: WarehouseEvidenceFileCandidate) {
            candidate.file.delete()
        }
    }

    private class MemoryMetadataStore : TemperatureEvidenceMetadataStore {
        var available = true
        var draft: TemperatureEvidenceDraft? = null
        var intent: TemperatureEvidenceIntent? = null
        val events = mutableListOf<String>()

        override suspend fun loadDraft(
            scope: TemperatureEvidenceScope
        ): TemperatureMetadataRead<TemperatureEvidenceDraft> = if (available) {
            TemperatureMetadataRead.Available(draft)
        } else {
            TemperatureMetadataRead.Unavailable
        }

        override suspend fun saveDraft(
            scope: TemperatureEvidenceScope,
            draft: TemperatureEvidenceDraft
        ): TemperatureMetadataWrite {
            if (!available) return TemperatureMetadataWrite.Unavailable
            this.draft = draft
            return TemperatureMetadataWrite.Saved
        }

        override suspend fun loadIntent(
            scope: TemperatureEvidenceScope
        ): TemperatureMetadataRead<TemperatureEvidenceIntent> = if (available) {
            TemperatureMetadataRead.Available(intent?.takeIf { it.scope == scope })
        } else {
            TemperatureMetadataRead.Unavailable
        }

        override suspend fun saveIntent(
            intent: TemperatureEvidenceIntent
        ): TemperatureMetadataWrite {
            if (!available) return TemperatureMetadataWrite.Unavailable
            val existing = this.intent
            if (existing != null && (
                    existing.idempotencyKey != intent.idempotencyKey ||
                        existing.payload != intent.payload
                    )
            ) {
                return TemperatureMetadataWrite.Unavailable
            }
            this.intent = intent
            events += "intent"
            return TemperatureMetadataWrite.Saved
        }

        override suspend fun markUnknownOutcome(
            scope: TemperatureEvidenceScope,
            idempotencyKey: String
        ): TemperatureMetadataWrite {
            if (!available) return TemperatureMetadataWrite.Unavailable
            val current =
                intent?.takeIf { it.scope == scope && it.idempotencyKey == idempotencyKey }
                    ?: return TemperatureMetadataWrite.Unavailable
            intent = current.copy(status = TemperatureIntentStatus.UnknownOutcome)
            return TemperatureMetadataWrite.Saved
        }

        override suspend fun clearIntent(
            scope: TemperatureEvidenceScope,
            idempotencyKey: String
        ): TemperatureMetadataWrite {
            if (!available) return TemperatureMetadataWrite.Unavailable
            if (intent?.idempotencyKey !=
                idempotencyKey
            ) {
                return TemperatureMetadataWrite.Unavailable
            }
            intent = null
            events += "clear"
            return TemperatureMetadataWrite.Saved
        }
    }

    private companion object {
        const val USER_ID = "e6c14000-0479-453f-93b9-c71cde8fbd01"
        const val TENANT_ID = "e6c14000-0479-453f-93b9-c71cde8fbd02"
        const val WORKSPACE_ID = "e6c14000-0479-453f-93b9-c71cde8fbd03"
        const val MEMBERSHIP_ID = "e6c14000-0479-453f-93b9-c71cde8fbd04"
        const val LOT_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413401"
        const val WAREHOUSE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413101"
        const val EVIDENCE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413501"
        const val PHOTO_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413502"
        const val SOURCE_EVIDENCE_ID = "b8c24a46-57d9-4f64-8fa7-6a641b413503"
    }
}
