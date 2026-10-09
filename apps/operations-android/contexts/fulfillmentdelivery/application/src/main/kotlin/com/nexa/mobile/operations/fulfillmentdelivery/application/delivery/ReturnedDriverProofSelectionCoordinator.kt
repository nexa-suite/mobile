package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverProofEvidenceKind
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Owns local returned-file matching and staging for a previously created proof. */
class ReturnedDriverProofSelectionCoordinator(
    private val metadataStore: DriverProofMetadataStore,
    private val fileSelection: DriverEvidenceFileSelectionPort
) {
    suspend fun stageReturnedSelection(
        context: DriverProofSelectionContext,
        sourceUri: String
    ): Boolean {
        if (!hasMatchingPendingProof(context)) return false

        val candidate = try {
            fileSelection.prepare(sourceUri, selectionKey(context))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return false

        return try {
            stageReturnedCandidate(context, candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            fileSelection.discard(candidate)
        }
    }

    private suspend fun hasMatchingPendingProof(context: DriverProofSelectionContext): Boolean {
        val intent = try {
            (metadataStore.loadIntent(context.scope) as? DriverProofMetadataRead.Available)
                ?.intent
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        return intent.matchesPendingProof(context)
    }

    private suspend fun stageReturnedCandidate(
        context: DriverProofSelectionContext,
        candidate: DriverProofFileCandidate
    ): Boolean {
        val intent =
            (metadataStore.loadIntent(context.scope) as? DriverProofMetadataRead.Available)
                ?.intent ?: return false
        if (!intent.matchesPendingProof(context)) return false

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
        return metadataStore.stageCandidate(staged, candidate) == DriverProofMetadataWrite.Saved
    }

    private fun selectionKey(context: DriverProofSelectionContext): String = lengthPrefixed(
        context.scope.userId,
        context.scope.tenantId,
        context.scope.workspaceId,
        context.scope.membershipId,
        context.deliveryId,
        context.attemptId,
        context.proofId
    )

    private fun DriverProofIntentMetadata?.matchesPendingProof(
        context: DriverProofSelectionContext
    ): Boolean = this != null &&
        scope == context.scope &&
        deliveryId == context.deliveryId &&
        attemptId == context.attemptId &&
        proofId == context.proofId &&
        stage == DriverProofIntentStage.ProofCreated &&
        status == DriverProofIntentStatus.Pending
}

/** Encodes each opaque identity component without delimiter ambiguity. */
internal fun lengthPrefixed(vararg values: String): String =
    values.joinToString("") { "${it.length}:$it" }
