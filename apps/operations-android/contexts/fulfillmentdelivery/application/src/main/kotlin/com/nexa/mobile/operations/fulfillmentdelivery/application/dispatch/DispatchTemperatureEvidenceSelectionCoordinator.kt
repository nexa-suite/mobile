package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import java.io.File

data class DispatchTemperatureSelectedPhoto(
    val file: File,
    val originalFilename: String,
    val declaredContentType: String,
    val byteSize: Long,
    val checksumSha256: String
)

/** Platform boundary for copying one dispatch photo into private temporary storage. */
interface DispatchTemperaturePhotoSelectionPort {
    suspend fun prepare(sourceUri: String, scopeKey: String): DispatchTemperatureSelectedPhoto?
    fun discard(candidate: DispatchTemperatureSelectedPhoto)
}

/** Owns the temporary selection scope for a dispatch lot's returned photo. */
class DispatchTemperatureEvidenceSelectionCoordinator(
    private val photos: DispatchTemperaturePhotoSelectionPort
) {
    suspend fun prepare(
        selection: DispatchTemperatureEvidenceSelectionContext,
        sourceUri: String
    ): DispatchTemperatureSelectedPhoto? = photos.prepare(
        sourceUri,
        lengthPrefixedKey(
            selection.scope.userId,
            selection.scope.tenantId,
            selection.scope.workspaceId,
            selection.scope.membershipId,
            selection.fulfillmentId,
            selection.lotId,
            selection.warehouseId
        )
    )

    fun discard(candidate: DispatchTemperatureSelectedPhoto) = photos.discard(candidate)

    private fun lengthPrefixedKey(vararg values: String): String =
        values.joinToString("") { "${it.length}:$it" }
}
