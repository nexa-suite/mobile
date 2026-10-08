package com.nexa.mobile.operations.inventoryavailability.application.publicapi

import java.io.File

/** Immutable evidence fields needed by a consuming bounded context. */
data class WarehouseTemperatureEvidenceProjection(
    val id: String,
    val subjectType: String,
    val subjectId: String,
    val lifecycleStatus: String
)

/** Outcome projection for the two warehouse evidence operations exposed to other contexts. */
sealed interface WarehouseTemperatureEvidenceResult {
    data class Uploaded(val evidence: WarehouseTemperatureEvidenceProjection) :
        WarehouseTemperatureEvidenceResult

    data class Status(val evidence: WarehouseTemperatureEvidenceProjection) :
        WarehouseTemperatureEvidenceResult

    data class Rejected(val code: String?) : WarehouseTemperatureEvidenceResult
    data object UnknownOutcome : WarehouseTemperatureEvidenceResult
    data object NetworkUnavailable : WarehouseTemperatureEvidenceResult
    data object ServiceUnavailable : WarehouseTemperatureEvidenceResult
    data object PermissionDenied : WarehouseTemperatureEvidenceResult
    data object ContextInvalidated : WarehouseTemperatureEvidenceResult
    data object SessionInvalidated : WarehouseTemperatureEvidenceResult
}

/** Narrow BC05 application contract for evidence attached to a warehouse subject. */
interface WarehouseTemperatureEvidencePublicApi {
    suspend fun uploadTemperatureEvidence(
        warehouseId: String,
        idempotencyKey: String,
        file: File,
        originalFilename: String,
        declaredContentType: String,
        byteSize: Long,
        checksumSha256: String
    ): WarehouseTemperatureEvidenceResult

    suspend fun temperatureEvidenceStatus(
        evidenceId: String,
        warehouseId: String
    ): WarehouseTemperatureEvidenceResult
}
