package com.nexa.mobile.operations

import com.nexa.mobile.operations.feature.delivery.application.DriverProofMetadataStore
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.model.DriverProofFileCandidate
import com.nexa.mobile.operations.feature.delivery.model.DriverProofIntentMetadata
import com.nexa.mobile.operations.feature.delivery.model.DriverProofIntentStage
import com.nexa.mobile.operations.feature.delivery.model.DriverProofIntentStatus
import com.nexa.mobile.operations.feature.delivery.model.DriverProofMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverProofMetadataWrite
import com.nexa.mobile.operations.feature.delivery.model.DriverProofSelectionContext as ProofSelectionContext
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnedDriverProofSelectionTest {
    private val scope = DriverAttemptScopeIdentity("user", "tenant", "workspace", "member")
    private val selection = ProofSelectionContext(7, scope, "delivery", "attempt", "proof")
    private fun intent() = DriverProofIntentMetadata(
        scope, "delivery", "attempt", 4, "create-key",
        "frozen-body", "Receiver", "2026-10-01T00:00:00Z", null,
        DriverProofIntentStage.ProofCreated, DriverProofIntentStatus.Pending, "proof", 5
    )
    private fun candidate(): DriverProofFileCandidate {
        val file = File.createTempFile("proof-selection", ".jpg")
        file.writeBytes(byteArrayOf(1, 2, 3))
        return DriverProofFileCandidate(file, "proof.jpg", "image/jpeg", 3, "a".repeat(64))
    }

    @Test fun stagesOriginalIdentityForReviewWithoutNetworkOrPlaintextRetention() = runTest {
        val store = Store(intent())
        val file = candidate()
        assertTrue(store.stageReturnedProofSelection(selection, file))
        val retained = store.staged!!
        assertEquals(scope, retained.scope)
        assertEquals(intent().createBody, retained.createBody)
        assertEquals(intent().createIdempotencyKey, retained.createIdempotencyKey)
        assertEquals(DriverProofIntentStage.EvidenceReadyForReview, retained.stage)
        assertNotNull(retained.evidenceUploadKey)
        assertFalse(file.file.exists())
    }

    @Test fun rejectsChangedDeliveryAndDeletesPrivateCandidate() = runTest {
        val store = Store(intent())
        val file = candidate()
        assertFalse(store.stageReturnedProofSelection(selection.copy(deliveryId = "other"), file))
        assertNull(store.staged)
        assertFalse(file.file.exists())
    }

    @Test fun persistenceFailureNeverClaimsStagedEvidence() = runTest {
        val store = Store(intent(), DriverProofMetadataWrite.Unavailable)
        val file = candidate()
        assertFalse(store.stageReturnedProofSelection(selection, file))
        assertFalse(file.file.exists())
    }

    private class Store(
        val intent: DriverProofIntentMetadata,
        val result: DriverProofMetadataWrite = DriverProofMetadataWrite.Saved
    ) : DriverProofMetadataStore {
        var staged: DriverProofIntentMetadata? = null
        override suspend fun loadIntent(scope: DriverAttemptScopeIdentity) =
            DriverProofMetadataRead.Available(intent)
        override suspend fun saveIntent(
            intent: DriverProofIntentMetadata
        ): DriverProofMetadataWrite = error("No independent save")
        override suspend fun stageCandidate(
            intent: DriverProofIntentMetadata,
            candidate: DriverProofFileCandidate
        ): DriverProofMetadataWrite {
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
}
