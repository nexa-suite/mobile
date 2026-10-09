package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import kotlinx.coroutines.CancellationException

/** Owns the returned-file preparation boundary for an existing incident draft. */
class ReturnedDriverIncidentEvidenceSelectionCoordinator(
    private val metadataStore: DriverIncidentMetadataStore,
    private val fileSelection: DriverEvidenceFileSelectionPort
) {
    suspend fun stageReturnedEvidence(
        context: DriverIncidentSelectionContext,
        sourceUri: String
    ): DriverIncidentMetadataWrite {
        val candidate = try {
            fileSelection.prepare(sourceUri, selectionKey(context))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return DriverIncidentMetadataWrite.Unavailable

        return try {
            metadataStore.stageReturnedEvidence(context, candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DriverIncidentMetadataWrite.Unavailable
        } finally {
            fileSelection.discard(candidate)
        }
    }

    private fun selectionKey(context: DriverIncidentSelectionContext): String = lengthPrefixed(
        context.scope.userId,
        context.scope.tenantId,
        context.scope.workspaceId,
        context.scope.membershipId,
        context.deliveryId,
        context.attemptId,
        context.draftId
    )
}
