package com.nexa.mobile.operations.feature.warehouse.application

import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyArtifactIdentity
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyArtifactRead
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyArtifactWrite
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyAuthority
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyCreateCommand
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyDraft
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyDraftRead
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyDraftWrite
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyEvidenceCandidate as DiscrepancyEvidenceCandidate
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyEvidenceStatusResult
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyMutationResult
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyScope
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancySelectionContext
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancySubmitCommand
import com.nexa.mobile.operations.feature.warehouse.model.InboundDiscrepancyUploadCommand

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
