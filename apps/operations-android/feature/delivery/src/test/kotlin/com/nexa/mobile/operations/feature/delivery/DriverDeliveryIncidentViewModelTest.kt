package com.nexa.mobile.operations.feature.delivery

import com.nexa.mobile.operations.feature.delivery.application.DriverIncidentGateway
import com.nexa.mobile.operations.feature.delivery.application.DriverIncidentMetadataStore
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentCurrentDelivery
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentCurrentDeliveryResult
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentEvidenceAttachCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentEvidenceDraft
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentEvidenceResult
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentEvidenceUploadCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentMetadata
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentMetadataWrite
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentRecordStatus
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentResult
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentSelectionContext
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentSummary
import com.nexa.mobile.operations.feature.delivery.model.DriverIncidentType
import com.nexa.mobile.operations.feature.delivery.model.DriverProofFileCandidate
import com.nexa.mobile.operations.feature.delivery.model.driverIncidentLegacyBody
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DriverDeliveryIncidentViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun savesEncryptedDraftThenPersistsExactCommandBeforePost() = runTest {
        val events = mutableListOf<String>()
        val store = FakeIncidentMetadataStore(events)
        val gateway = FakeIncidentGateway(events)
        gateway.result = DriverIncidentResult.Recorded(summary())
        val viewModel =
            DriverDeliveryIncidentViewModel(gateway, store, keyFactory = { "incident-key" })
        viewModel.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
        advanceUntilIdle()
        viewModel.editType(DriverIncidentType.DELAY)
        viewModel.editReason("Road closure")
        viewModel.editDescription("Entrance blocked")
        viewModel.editPlace("North entrance")
        viewModel.saveDraft()
        advanceUntilIdle()

        assertEquals(0, gateway.commands.size)
        assertEquals(DriverIncidentRecordStatus.Draft, store.stored?.status)
        viewModel.reviewDraft()
        advanceUntilIdle()
        assertEquals(DriverIncidentUiStatus.ReadyForReview, viewModel.state.value.status)
        viewModel.submitIncident()
        advanceUntilIdle()

        assertEquals(listOf("persist", "post"), events)
        assertEquals(7L, gateway.commands.single().expectedVersion)
        assertEquals("incident-key", gateway.commands.single().idempotencyKey)
        assertEquals(
            """{"type":"DELAY","reason":"Road closure","description":"Entrance blocked","place":"North entrance"}""",
            gateway.commands.single().frozenBody
        )
        assertEquals(DriverIncidentType.DELAY, gateway.commands.single().type)
        assertEquals(DriverIncidentType.DELAY, store.persistedIntents.single().type)
        assertEquals(DriverIncidentUiStatus.Recorded, viewModel.state.value.status)
        assertEquals("PENDIENTE_EVIDENCIA", viewModel.state.value.summary?.evidenceLabel)
        assertTrue(viewModel.state.value.summary?.recordedAt == "2026-10-01T17:00:00Z")
        assertNull(store.stored)
    }

    @Test
    fun recoveredPendingBecomesUnknownAndOnlyExplicitRetryReplaysSameCommand() = runTest {
        val store = FakeIncidentMetadataStore()
        val firstGateway = FakeIncidentGateway()
        firstGateway.result = DriverIncidentResult.UnknownOutcome
        val first = preparedViewModel(firstGateway, store)
        first.submitIncident()
        advanceUntilIdle()
        assertEquals(1, firstGateway.commands.size)
        assertEquals(DriverIncidentUiStatus.UnknownOutcome, first.state.value.status)
        assertEquals(DriverIncidentRecordStatus.UnknownOutcome, store.stored?.status)

        val replayGateway = FakeIncidentGateway()
        replayGateway.result = DriverIncidentResult.Recorded(summary(replayed = true))
        val recovered = DriverDeliveryIncidentViewModel(replayGateway, store)
        recovered.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
        advanceUntilIdle()
        assertEquals(DriverIncidentUiStatus.UnknownOutcome, recovered.state.value.status)
        assertTrue(replayGateway.commands.isEmpty())

        recovered.retryUnknownOutcome()
        advanceUntilIdle()
        assertEquals(1, replayGateway.commands.size)
        assertEquals(firstGateway.commands.single(), replayGateway.commands.single())
        assertEquals(DriverIncidentUiStatus.Recorded, recovered.state.value.status)
    }

    @Test
    fun recoveredLegacyIntentStaysUnclassifiedAndOnlyManualRetryUsesExactOldBody() = runTest {
        val store = FakeIncidentMetadataStore()
        val oldCommand = DriverIncidentCommand(
            DELIVERY_ID,
            ATTEMPT_ID,
            7,
            "legacy-incident-key",
            "Road closure",
            "Entrance blocked",
            "North entrance",
            driverIncidentLegacyBody("Road closure", "Entrance blocked", "North entrance")
        )
        store.stored = DriverIncidentMetadata(
            scope = AUTHORITY.scopeIdentity,
            deliveryId = DELIVERY_ID,
            attemptId = ATTEMPT_ID,
            draftVersion = 7,
            reason = oldCommand.reason,
            description = oldCommand.description,
            place = oldCommand.place,
            status = DriverIncidentRecordStatus.UnknownOutcome,
            command = oldCommand,
            draftId = "legacy-draft"
        )
        val gateway = FakeIncidentGateway().apply {
            result = DriverIncidentResult.Recorded(summary(replayed = true, type = null))
        }
        val recovered = DriverDeliveryIncidentViewModel(gateway, store)
        recovered.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
        advanceUntilIdle()

        assertEquals(DriverIncidentUiStatus.UnknownOutcome, recovered.state.value.status)
        assertNull(recovered.state.value.type)
        assertTrue(gateway.commands.isEmpty())

        recovered.retryUnknownOutcome()
        advanceUntilIdle()

        assertEquals(listOf(oldCommand), gateway.commands)
        assertEquals(oldCommand.frozenBody, gateway.commands.single().frozenBody)
        assertNull(gateway.commands.single().type)
        assertNull(recovered.state.value.summary?.type)
        assertEquals(DriverIncidentUiStatus.Recorded, recovered.state.value.status)
    }

    @Test
    fun persistenceFailureAndChangedServerVersionNeverPostOrOverwrite() = runTest {
        val store = FakeIncidentMetadataStore()
        val gateway = FakeIncidentGateway()
        val viewModel = preparedViewModel(gateway, store)
        store.writeResult = DriverIncidentMetadataWrite.Unavailable
        viewModel.submitIncident()
        advanceUntilIdle()
        assertEquals(DriverIncidentUiStatus.PersistenceUnavailable, viewModel.state.value.status)
        assertTrue(gateway.commands.isEmpty())

        val freshStore = FakeIncidentMetadataStore()
        val staleGateway = FakeIncidentGateway()
        val stale = preparedViewModel(staleGateway, freshStore)
        staleGateway.current = current(version = 8)
        stale.submitIncident()
        advanceUntilIdle()
        assertEquals(DriverIncidentUiStatus.NeedsReview, stale.state.value.status)
        assertTrue(staleGateway.commands.isEmpty())
    }

    @Test
    fun changedAttemptCannotPassFreshReview() = runTest {
        val store = FakeIncidentMetadataStore()
        val gateway = FakeIncidentGateway()
        val viewModel = preparedViewModel(gateway, store)
        gateway.current = current(activeAttemptId = OTHER_ATTEMPT_ID)
        viewModel.submitIncident()
        advanceUntilIdle()
        assertEquals(DriverIncidentUiStatus.Stale, viewModel.state.value.status)
        assertTrue(gateway.commands.isEmpty())
    }

    @Test
    fun restoredProtectedMediaWaitsForExplicitUpload() = runTest {
        val store = FakeIncidentMetadataStore()
        val staged = DriverIncidentEvidenceDraft(
            fileToken = "protected-token",
            originalFilename = "incident.jpg",
            contentType = "image/jpeg",
            byteSize = 1,
            checksumSha256 = "0".repeat(64)
        )
        store.stored = DriverIncidentMetadata(
            scope = AUTHORITY.scopeIdentity,
            deliveryId = DELIVERY_ID,
            attemptId = ATTEMPT_ID,
            draftVersion = 7,
            reason = "Road closure",
            description = "Entrance blocked",
            place = "North entrance",
            status = DriverIncidentRecordStatus.RecordedWithEvidence,
            draftId = "protected-draft",
            incidentId = INCIDENT_ID,
            evidence = staged,
            recordedAt = "2026-10-01T17:00:00Z",
            recordedByMembershipId = MEMBERSHIP_ID,
            deliveryVersion = 7,
            type = DriverIncidentType.DELAY,
            severity = "WARNING",
            operationalExceptionId = "99999999-9999-4999-8999-999999999999"
        )
        val gateway = FakeIncidentGateway()
        val viewModel = DriverDeliveryIncidentViewModel(gateway, store)
        viewModel.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
        advanceUntilIdle()

        assertEquals(DriverIncidentUiStatus.Recorded, viewModel.state.value.status)
        assertEquals(staged, viewModel.state.value.evidence)
        assertTrue(gateway.commands.isEmpty())
        assertTrue(gateway.uploads.isEmpty())
        assertTrue(gateway.statusReads.isEmpty())
    }

    private suspend fun kotlinx.coroutines.test.TestScope.preparedViewModel(
        gateway: FakeIncidentGateway,
        store: FakeIncidentMetadataStore
    ): DriverDeliveryIncidentViewModel {
        val viewModel =
            DriverDeliveryIncidentViewModel(gateway, store, keyFactory = { "incident-key" })
        viewModel.activate(AUTHORITY, DELIVERY_ID, ATTEMPT_ID, 7)
        advanceUntilIdle()
        viewModel.editType(DriverIncidentType.DELAY)
        viewModel.editReason("Road closure")
        viewModel.editDescription("Entrance blocked")
        viewModel.editPlace("North entrance")
        viewModel.saveDraft()
        advanceUntilIdle()
        viewModel.reviewDraft()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.canSubmit)
        return viewModel
    }

    private class FakeIncidentGateway(private val events: MutableList<String> = mutableListOf()) :
        DriverIncidentGateway {
        var current = current()
        var result: DriverIncidentResult = DriverIncidentResult.UnknownOutcome
        val commands = mutableListOf<DriverIncidentCommand>()
        val uploads = mutableListOf<DriverIncidentEvidenceUploadCommand>()
        val statusReads = mutableListOf<String>()

        override suspend fun currentDelivery(
            deliveryId: String,
            authority: DriverDeliveryAuthority
        ): DriverIncidentCurrentDeliveryResult = if (deliveryId == current.deliveryId) {
            DriverIncidentCurrentDeliveryResult.Loaded(current)
        } else {
            DriverIncidentCurrentDeliveryResult.NotFound
        }

        override suspend fun recordIncident(
            command: DriverIncidentCommand,
            authority: DriverDeliveryAuthority
        ): DriverIncidentResult {
            events += "post"
            commands += command
            return result
        }

        override suspend fun uploadEvidence(
            command: DriverIncidentEvidenceUploadCommand,
            authority: DriverDeliveryAuthority
        ): DriverIncidentEvidenceResult {
            events += "upload"
            uploads += command
            return DriverIncidentEvidenceResult.Unavailable
        }

        override suspend fun evidenceStatus(
            evidenceId: String,
            authority: DriverDeliveryAuthority
        ): DriverIncidentEvidenceResult {
            statusReads += evidenceId
            return DriverIncidentEvidenceResult.Unavailable
        }

        override suspend fun attachEvidence(
            command: DriverIncidentEvidenceAttachCommand,
            authority: DriverDeliveryAuthority
        ): DriverIncidentResult = DriverIncidentResult.Unavailable
    }

    private class FakeIncidentMetadataStore(
        private val events: MutableList<String> = mutableListOf()
    ) : DriverIncidentMetadataStore {
        var stored: DriverIncidentMetadata? = null
        var writeResult: DriverIncidentMetadataWrite = DriverIncidentMetadataWrite.Saved
        val persistedIntents = mutableListOf<DriverIncidentMetadata>()

        override suspend fun load(scope: DriverAttemptScopeIdentity): DriverIncidentMetadataRead {
            val current = stored
            if (current?.scope != scope) return DriverIncidentMetadataRead.Available(null)
            val recovered = if (current.status == DriverIncidentRecordStatus.Pending) {
                current.copy(status = DriverIncidentRecordStatus.UnknownOutcome)
            } else {
                current
            }
            stored = recovered
            return DriverIncidentMetadataRead.Available(recovered)
        }

        override suspend fun saveDraft(
            metadata: DriverIncidentMetadata
        ): DriverIncidentMetadataWrite {
            if (writeResult != DriverIncidentMetadataWrite.Saved) return writeResult
            stored = metadata
            return DriverIncidentMetadataWrite.Saved
        }

        override suspend fun persistIntent(
            metadata: DriverIncidentMetadata
        ): DriverIncidentMetadataWrite {
            if (writeResult != DriverIncidentMetadataWrite.Saved) return writeResult
            val existing = stored
            if (existing?.status != DriverIncidentRecordStatus.Draft &&
                existing?.command != metadata.command
            ) {
                return DriverIncidentMetadataWrite.Conflict
            }
            stored = metadata
            persistedIntents += metadata
            events += "persist"
            return DriverIncidentMetadataWrite.Saved
        }

        override suspend fun persistRecorded(
            metadata: DriverIncidentMetadata
        ): DriverIncidentMetadataWrite {
            if (writeResult != DriverIncidentMetadataWrite.Saved) return writeResult
            stored = metadata
            events += "persist-recorded"
            return DriverIncidentMetadataWrite.Saved
        }

        override suspend fun stageReturnedEvidence(
            context: DriverIncidentSelectionContext,
            candidate: DriverProofFileCandidate
        ): DriverIncidentMetadataWrite = DriverIncidentMetadataWrite.Unavailable

        override suspend fun updateRecordedEvidence(
            metadata: DriverIncidentMetadata
        ): DriverIncidentMetadataWrite {
            if (writeResult != DriverIncidentMetadataWrite.Saved) return writeResult
            stored = metadata
            events += "persist-evidence"
            return DriverIncidentMetadataWrite.Saved
        }

        override suspend fun loadCandidate(
            metadata: DriverIncidentMetadata
        ): DriverProofFileCandidate? = null

        override suspend fun clearCandidate(metadata: DriverIncidentMetadata): Boolean = true

        override suspend fun clearIntent(
            scope: DriverAttemptScopeIdentity,
            deliveryId: String,
            idempotencyKey: String
        ): DriverIncidentMetadataWrite {
            val current = stored ?: return DriverIncidentMetadataWrite.Saved
            if (current.scope != scope || current.deliveryId != deliveryId ||
                current.command?.idempotencyKey != idempotencyKey
            ) {
                return DriverIncidentMetadataWrite.Stale
            }
            stored = null
            return DriverIncidentMetadataWrite.Saved
        }
    }

    private companion object {
        const val USER_ID = "11111111-1111-4111-8111-111111111111"
        const val TENANT_ID = "22222222-2222-4222-8222-222222222222"
        const val WORKSPACE_ID = "33333333-3333-4333-8333-333333333333"
        const val MEMBERSHIP_ID = "44444444-4444-4444-8444-444444444444"
        const val DELIVERY_ID = "55555555-5555-4555-8555-555555555555"
        const val ATTEMPT_ID = "66666666-6666-4666-8666-666666666666"
        const val OTHER_ATTEMPT_ID = "77777777-7777-4777-8777-777777777777"
        const val INCIDENT_ID = "88888888-8888-4888-8888-888888888888"

        val AUTHORITY = DriverDeliveryAuthority(
            USER_ID,
            TENANT_ID,
            WORKSPACE_ID,
            MEMBERSHIP_ID,
            setOf("dispatch.read", "dispatch.start_route"),
            authorityEpoch = 12
        )

        fun current(version: Long = 7, activeAttemptId: String? = ATTEMPT_ID) =
            DriverIncidentCurrentDelivery(DELIVERY_ID, "IN_TRANSIT", version, activeAttemptId)

        fun summary(
            replayed: Boolean = false,
            type: DriverIncidentType? = DriverIncidentType.DELAY
        ) = DriverIncidentSummary(
            "88888888-8888-4888-8888-888888888888", DELIVERY_ID, ATTEMPT_ID,
            "Road closure", "Entrance blocked", "North entrance", MEMBERSHIP_ID,
            "2026-10-01T17:00:00Z", emptyList(), 7, replayed,
            type = type, severity = if (type == null) null else "WARNING",
            operationalExceptionId = if (type ==
                null
            ) {
                null
            } else {
                "99999999-9999-4999-8999-999999999999"
            }
        )
    }
}
