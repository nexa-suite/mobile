package com.nexa.mobile.operations.inventoryavailability.application.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyArtifactIdentity
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyArtifactRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyArtifactWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyCreateCommand
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyDraft
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyDraftRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyDraftWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyEvidenceStatusResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyMutationResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyScope
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancySelectionContext
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancySubmitCommand
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyUploadCommand

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancyEvidenceCandidate as DiscrepancyEvidenceCandidate

/** Encrypted, scope-bound artifact staging; loading never starts network work. */
interface InboundDiscrepancyEvidenceArtifactStore {
    suspend fun stageReturnedSelection(
        selection: InboundDiscrepancySelectionContext,
        candidate: DiscrepancyEvidenceCandidate
    ): InboundDiscrepancyArtifactWrite

    suspend fun load(identity: InboundDiscrepancyArtifactIdentity): InboundDiscrepancyArtifactRead
    suspend fun openForUpload(
        identity: InboundDiscrepancyArtifactIdentity
    ): DiscrepancyEvidenceCandidate?
    fun releaseUploadCandidate(candidate: DiscrepancyEvidenceCandidate)
    suspend fun clear(identity: InboundDiscrepancyArtifactIdentity): Boolean
}

interface InboundDiscrepancyGateway {
    suspend fun createCase(
        command: InboundDiscrepancyCreateCommand,
        authority: InboundDiscrepancyAuthority
    ): InboundDiscrepancyMutationResult

    suspend fun uploadEvidence(
        command: InboundDiscrepancyUploadCommand,
        authority: InboundDiscrepancyAuthority
    ): InboundDiscrepancyMutationResult

    suspend fun evidenceStatus(
        evidenceId: String,
        caseId: String,
        authority: InboundDiscrepancyAuthority
    ): InboundDiscrepancyEvidenceStatusResult

    suspend fun submitForReview(
        command: InboundDiscrepancySubmitCommand,
        authority: InboundDiscrepancyAuthority
    ): InboundDiscrepancyMutationResult
}

/** Encrypted local draft and exact replay identity only; never treats local data as server truth. */
interface InboundDiscrepancyDraftStore {
    suspend fun load(scope: InboundDiscrepancyScope): InboundDiscrepancyDraftRead
    suspend fun save(
        scope: InboundDiscrepancyScope,
        draft: InboundDiscrepancyDraft
    ): InboundDiscrepancyDraftWrite
    suspend fun discard(
        scope: InboundDiscrepancyScope,
        expectedDraftId: String
    ): InboundDiscrepancyDraftWrite
}
