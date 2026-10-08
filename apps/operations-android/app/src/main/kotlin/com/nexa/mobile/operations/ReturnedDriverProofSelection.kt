package com.nexa.mobile.operations

import com.nexa.mobile.operations.feature.delivery.application.DriverProofMetadataStore
import com.nexa.mobile.operations.feature.delivery.model.DriverProofEvidenceKind
import com.nexa.mobile.operations.feature.delivery.model.DriverProofFileCandidate
import com.nexa.mobile.operations.feature.delivery.model.DriverProofIntentStage
import com.nexa.mobile.operations.feature.delivery.model.DriverProofIntentStatus
import com.nexa.mobile.operations.feature.delivery.model.DriverProofMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverProofMetadataWrite
import com.nexa.mobile.operations.feature.delivery.model.DriverProofSelectionContext as ProofSelectionContext
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Local protected staging only; authorization and delivery freshness are checked before review/upload. */
internal suspend fun DriverProofMetadataStore.stageReturnedProofSelection(
    context: ProofSelectionContext,
    candidate: DriverProofFileCandidate
): Boolean = try {
    val intent = (loadIntent(context.scope) as? DriverProofMetadataRead.Available)?.intent
    if (
        intent == null ||
        intent.scope != context.scope ||
        intent.deliveryId != context.deliveryId ||
        intent.attemptId != context.attemptId || intent.proofId != context.proofId ||
        intent.stage != DriverProofIntentStage.ProofCreated ||
        intent.status != DriverProofIntentStatus.Pending
    ) {
        false
    } else {
        val staged = intent.copy(
            stage = DriverProofIntentStage.EvidenceReadyForReview,
            status = DriverProofIntentStatus.Pending,
            evidenceKind = DriverProofEvidenceKind.PHOTO,
            evidenceUploadKey = UUID.randomUUID().toString(),
            candidateFileToken = UUID.randomUUID().toString(),
            candidateFilename = candidate.originalFilename,
            candidateContentType = candidate.declaredContentType,
            candidateByteSize = candidate.byteSize,
            candidateChecksumSha256 = candidate.checksumSha256
        )
        stageCandidate(staged, candidate) == DriverProofMetadataWrite.Saved
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
} finally {
    candidate.file.delete()
}
