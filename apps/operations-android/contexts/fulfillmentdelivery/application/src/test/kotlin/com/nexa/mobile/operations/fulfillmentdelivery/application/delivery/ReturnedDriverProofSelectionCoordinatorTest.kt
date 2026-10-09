package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverProofEvidenceKind
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnedDriverProofSelectionCoordinatorTest {
    private val scope = DriverAttemptScopeIdentity("user", "tenant", "workspace", "member")
    private val selection = DriverProofSelectionContext(7, scope, "delivery", "attempt", "proof")

    private fun intent() = DriverProofIntentMetadata(
        scope,
        "delivery",
        "attempt",
        4,
        "create-key",
        "frozen-body",
        "Receiver",
        "2026-10-01T00:00:00Z",
        null,
        DriverProofIntentStage.ProofCreated,
        DriverProofIntentStatus.Pending,
        "proof",
        5
    )

    @Test
    fun stagesMatchingReturnedSelectionAndDeletesTemporaryCopy() = runTest {
        val store = Store(intent())
        val files = FileSelection(candidate())
        val coordinator = ReturnedDriverProofSelectionCoordinator(store, files)

        assertTrue(coordinator.stageReturnedSelection(selection, "content://picker/image"))

        val staged = store.staged!!
        assertEquals(scope, staged.scope)
        assertEquals(intent().createBody, staged.createBody)
        assertEquals(intent().createIdempotencyKey, staged.createIdempotencyKey)
        assertEquals(DriverProofIntentStage.EvidenceReadyForReview, staged.stage)
        assertEquals(DriverProofEvidenceKind.PHOTO, staged.evidenceKind)
        assertTrue(staged.evidenceUploadKey!!.isNotBlank())
        assertEquals(
            listOf("user", "tenant", "workspace", "member", "delivery", "attempt", "proof")
                .joinToString("") { "${it.length}:$it" },
            files.scopeKey
        )
        assertEquals("content://picker/image", files.sourceUri)
        assertTrue(files.discarded)
    }

    @Test
    fun rejectsSelectionBeforeCopyWhenTheCurrentIntentNoLongerMatches() = runTest {
        val store = Store(intent())
        val files = FileSelection(candidate())
        val coordinator = ReturnedDriverProofSelectionCoordinator(store, files)

        assertFalse(
            coordinator.stageReturnedSelection(
                selection.copy(deliveryId = "changed-delivery"),
                "content://picker/image"
            )
        )

        assertEquals(null, store.staged)
        assertEquals(0, files.prepareCalls)
        assertFalse(files.discarded)
        files.cleanupCandidate()
    }

    @Test
    fun rejectsIntentThatChangesWhileThePickerCopyIsBeingPreparedAndDeletesCopy() = runTest {
        val store = Store(
            intent(),
            intentOnRead = { read ->
                if (read == 0) intent() else intent().copy(proofId = "new-proof")
            }
        )
        val files = FileSelection(candidate())
        val coordinator = ReturnedDriverProofSelectionCoordinator(store, files)

        assertFalse(coordinator.stageReturnedSelection(selection, "content://picker/image"))

        assertEquals(null, store.staged)
        assertEquals(2, store.loadCalls)
        assertEquals(1, files.prepareCalls)
        assertTrue(files.discarded)
    }

    @Test
    fun cancellationDuringStagingPropagatesAfterTemporaryCopyCleanup() = runTest {
        val store = Store(intent(), failure = CancellationException("cancelled"))
        val files = FileSelection(candidate())
        val coordinator = ReturnedDriverProofSelectionCoordinator(store, files)

        val cancelled = runCatching {
            coordinator.stageReturnedSelection(selection, "content://picker/image")
        }.exceptionOrNull()

        assertTrue(cancelled is CancellationException)
        assertTrue(files.discarded)
    }

    @Test
    fun storageFailureDoesNotClaimStagingAndDeletesTemporaryCopy() = runTest {
        val store = Store(intent(), result = DriverProofMetadataWrite.Unavailable)
        val files = FileSelection(candidate())
        val coordinator = ReturnedDriverProofSelectionCoordinator(store, files)

        assertFalse(coordinator.stageReturnedSelection(selection, "content://picker/image"))

        assertEquals(null, store.staged)
        assertTrue(files.discarded)
    }

    @Test
    fun incidentSelectionIsScopedToTheSavedDraftAndDeletesTemporaryCopy() = runTest {
        val incident = DriverIncidentSelectionContext(
            7,
            scope,
            "delivery",
            "attempt",
            8,
            true,
            "draft"
        )
        val files = FileSelection(candidate())
        val store = IncidentStore()
        val coordinator = ReturnedDriverIncidentEvidenceSelectionCoordinator(store, files)

        assertEquals(
            DriverIncidentMetadataWrite.Saved,
            coordinator.stageReturnedEvidence(incident, "content://picker/incident-image")
        )

        assertEquals(incident, store.selection)
        assertEquals(
            listOf("user", "tenant", "workspace", "member", "delivery", "attempt", "draft")
                .joinToString("") { "${it.length}:$it" },
            files.scopeKey
        )
        assertEquals("content://picker/incident-image", files.sourceUri)
        assertTrue(files.discarded)
    }

    private fun candidate(): DriverProofFileCandidate {
        val file = File.createTempFile("proof-selection", ".jpg")
        file.writeBytes(byteArrayOf(1, 2, 3))
        return DriverProofFileCandidate(file, "proof.jpg", "image/jpeg", 3, "a".repeat(64))
    }

    private class FileSelection(private val candidate: DriverProofFileCandidate) :
        DriverEvidenceFileSelectionPort {
        var sourceUri: String? = null
        var scopeKey: String? = null
        var prepareCalls = 0
        var discarded = false

        override suspend fun prepare(
            sourceUri: String,
            scopeKey: String
        ): DriverProofFileCandidate {
            prepareCalls++
            this.sourceUri = sourceUri
            this.scopeKey = scopeKey
            return candidate
        }

        override fun discard(candidate: DriverProofFileCandidate) {
            discarded = true
            candidate.file.delete()
        }

        fun cleanupCandidate() {
            candidate.file.delete()
        }
    }

    private class Store(
        private val intent: DriverProofIntentMetadata,
        private val result: DriverProofMetadataWrite = DriverProofMetadataWrite.Saved,
        private val failure: Throwable? = null,
        private val intentOnRead: (Int) -> DriverProofIntentMetadata? = { intent }
    ) : DriverProofMetadataStore {
        var staged: DriverProofIntentMetadata? = null
        var loadCalls = 0

        override suspend fun loadIntent(scope: DriverAttemptScopeIdentity) =
            DriverProofMetadataRead.Available(intentOnRead(loadCalls++))

        override suspend fun saveIntent(
            intent: DriverProofIntentMetadata
        ): DriverProofMetadataWrite = error("No independent save")

        override suspend fun stageCandidate(
            intent: DriverProofIntentMetadata,
            candidate: DriverProofFileCandidate
        ): DriverProofMetadataWrite {
            failure?.let { throw it }
            if (result == DriverProofMetadataWrite.Saved) staged = intent
            return result
        }

        override suspend fun loadCandidate(
            intent: DriverProofIntentMetadata
        ): DriverProofFileCandidate? = error("No upload")

        override suspend fun clearCandidate(intent: DriverProofIntentMetadata): Boolean =
            error("No mutation")

        override suspend fun clearIntent(
            scope: DriverAttemptScopeIdentity,
            createIdempotencyKey: String
        ): DriverProofMetadataWrite = error("No mutation")
    }

    private class IncidentStore : DriverIncidentMetadataStore {
        var selection: DriverIncidentSelectionContext? = null

        override suspend fun load(scope: DriverAttemptScopeIdentity): DriverIncidentMetadataRead =
            error("No load")

        override suspend fun saveDraft(
            metadata: DriverIncidentMetadata
        ): DriverIncidentMetadataWrite = error("No save")

        override suspend fun persistIntent(
            metadata: DriverIncidentMetadata
        ): DriverIncidentMetadataWrite = error("No intent")

        override suspend fun persistRecorded(
            metadata: DriverIncidentMetadata
        ): DriverIncidentMetadataWrite = error("No record")

        override suspend fun stageReturnedEvidence(
            context: DriverIncidentSelectionContext,
            candidate: DriverProofFileCandidate
        ): DriverIncidentMetadataWrite {
            selection = context
            return DriverIncidentMetadataWrite.Saved
        }

        override suspend fun updateRecordedEvidence(
            metadata: DriverIncidentMetadata
        ): DriverIncidentMetadataWrite = error("No update")

        override suspend fun loadCandidate(
            metadata: DriverIncidentMetadata
        ): DriverProofFileCandidate? = error("No load candidate")

        override suspend fun clearCandidate(metadata: DriverIncidentMetadata): Boolean =
            error("No clear candidate")

        override suspend fun clearIntent(
            scope: DriverAttemptScopeIdentity,
            deliveryId: String,
            idempotencyKey: String
        ): DriverIncidentMetadataWrite = error("No clear intent")
    }
}
