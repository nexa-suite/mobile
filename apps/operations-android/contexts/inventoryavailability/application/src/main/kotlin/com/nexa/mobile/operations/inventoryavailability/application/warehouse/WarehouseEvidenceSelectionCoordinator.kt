package com.nexa.mobile.operations.inventoryavailability.application.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundDiscrepancySelectionContext
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingEvidenceSelectionContext
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidencePhotoSelection
import java.io.File

/** A validated private copy held only until its context-owned workflow consumes it. */
data class WarehouseEvidenceFileCandidate(
    val file: File,
    val originalFilename: String,
    val declaredContentType: String,
    val byteSize: Long,
    val checksumSha256: String
)

/** Platform boundary for copying one returned picker URI into private temporary storage. */
interface WarehouseEvidenceFileSelectionPort {
    suspend fun prepare(sourceUri: String, scopeKey: String): WarehouseEvidenceFileCandidate?
    fun discard(candidate: WarehouseEvidenceFileCandidate)
}

/** Builds context-specific temporary scopes before the owning workflow stages or uploads a file. */
class WarehouseEvidenceSelectionCoordinator(private val files: WarehouseEvidenceFileSelectionPort) {
    suspend fun prepareInboundDiscrepancy(
        selection: InboundDiscrepancySelectionContext,
        sourceUri: String
    ): WarehouseEvidenceFileCandidate? = files.prepare(
        sourceUri,
        lengthPrefixedKey(
            selection.scope.userId,
            selection.scope.tenantId,
            selection.scope.workspaceId,
            selection.scope.membershipId,
            selection.warehouseId,
            selection.caseId
        )
    )

    suspend fun prepareReceivingTemperature(
        selection: ReceivingEvidenceSelectionContext,
        sourceUri: String
    ): WarehouseEvidenceFileCandidate? = files.prepare(
        sourceUri,
        lengthPrefixedKey(
            selection.scope.userId,
            selection.scope.tenantId,
            selection.scope.workspaceId,
            selection.scope.membershipId,
            selection.warehouseId
        )
    )

    suspend fun prepareTemperatureEvidence(
        selection: TemperatureEvidencePhotoSelection,
        sourceUri: String
    ): WarehouseEvidenceFileCandidate? = files.prepare(
        sourceUri,
        lengthPrefixedKey(
            selection.scope.userId,
            selection.scope.tenantId,
            selection.scope.workspaceId,
            selection.scope.membershipId,
            selection.authorityEpoch.toString(),
            selection.subjectType.name,
            selection.subjectId,
            selection.warehouseId,
            selection.expectedLotVersion?.toString().orEmpty()
        )
    )

    fun discard(candidate: WarehouseEvidenceFileCandidate) = files.discard(candidate)

    private fun lengthPrefixedKey(vararg values: String): String =
        values.joinToString("") { "${it.length}:$it" }
}
