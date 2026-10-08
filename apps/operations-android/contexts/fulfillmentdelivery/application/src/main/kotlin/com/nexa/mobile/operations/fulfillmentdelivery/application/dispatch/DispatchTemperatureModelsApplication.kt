package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchTemperatureEvidence
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchTemperaturePhotoEvidence
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchTemperatureReadiness
import java.io.File
import java.math.BigDecimal
import java.time.Instant

data class DispatchTemperatureCommand(
    val fulfillmentId: String,
    val expectedFulfillmentVersion: Long,
    val lotId: String,
    val valueCelsius: BigDecimal,
    val occurredAt: Instant,
    val idempotencyKey: String,
    val exactRequestBody: String,
    val evidenceObjectId: String? = null,
    val expectedLotVersion: Long? = null
) {
    override fun toString(): String = "DispatchTemperatureCommand(REDACTED, " +
        "version=$expectedFulfillmentVersion)"

    fun isValid(): Boolean = UUID_PATTERN.matches(fulfillmentId) && UUID_PATTERN.matches(lotId) &&
        expectedFulfillmentVersion >= 0 && idempotencyKey.isNotBlank() &&
        idempotencyKey.length <= 160 &&
        (expectedLotVersion == null || expectedLotVersion >= 0) &&
        (expectedLotVersion != null || evidenceObjectId == null) &&
        (evidenceObjectId == null || UUID_PATTERN.matches(evidenceObjectId))

    private companion object {
        val UUID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

/** A private, validated image copy passed from the app picker into this feature. */


data class DispatchTemperaturePhotoCandidate(
    val file: File,
    val originalFilename: String,
    val declaredContentType: String,
    val byteSize: Long,
    val checksumSha256: String
) {
    init {
        require(file.isFile && file.length() == byteSize)
        require(originalFilename.isNotBlank() && originalFilename.length <= 255)
        require(declaredContentType in ALLOWED_CONTENT_TYPES)
        require(byteSize in 1..MAX_BYTES)
        require(checksumSha256.matches(Regex("[0-9a-f]{64}")))
    }

    override fun toString(): String =
        "DispatchTemperaturePhotoCandidate(type=$declaredContentType, bytes=$byteSize)"

    private companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
        val ALLOWED_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}

/** Snapshot captured before opening the system photo picker; every field is revalidated after return. */


data class DispatchTemperatureEvidenceSelectionContext(
    val scope: DispatchTemperatureScopeIdentity,
    val authorityEpoch: Long,
    val fulfillmentId: String,
    val lotId: String,
    val warehouseId: String
) {
    override fun toString(): String = "DispatchTemperatureEvidenceSelectionContext(REDACTED)"
}



data class DispatchTemperatureScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "DispatchTemperatureScopeIdentity(REDACTED)"
}



enum class DispatchTemperatureIntentStatus { Pending, UnknownOutcome }



data class DispatchTemperatureIntent(
    val scope: DispatchTemperatureScopeIdentity,
    val command: DispatchTemperatureCommand,
    val status: DispatchTemperatureIntentStatus = DispatchTemperatureIntentStatus.Pending
) {
    override fun toString(): String = "DispatchTemperatureIntent(REDACTED, status=$status)"
}

/** Encrypted upload retry facts only; the temporary image itself is never restored automatically. */


data class DispatchTemperaturePhotoUploadIntent(
    val scope: DispatchTemperatureScopeIdentity,
    val fulfillmentId: String,
    val lotId: String,
    val warehouseId: String,
    val idempotencyKey: String,
    val originalFilename: String,
    val declaredContentType: String,
    val byteSize: Long,
    val checksumSha256: String
) {
    fun matches(candidate: DispatchTemperaturePhotoCandidate): Boolean =
        declaredContentType == candidate.declaredContentType && byteSize == candidate.byteSize &&
            checksumSha256 == candidate.checksumSha256

    fun isValid(): Boolean = listOf(fulfillmentId, lotId, warehouseId).all(UUID_PATTERN::matches) &&
        idempotencyKey.isNotBlank() && idempotencyKey.length <= 160 &&
        originalFilename.isNotBlank() && originalFilename.length <= 255 &&
        originalFilename.none { it == '\r' || it == '\n' || it == '/' || it == '\\' } &&
        declaredContentType in ALLOWED_CONTENT_TYPES && byteSize in 1..MAX_BYTES &&
        checksumSha256.matches(Regex("[0-9a-f]{64}"))

    override fun toString(): String =
        "DispatchTemperaturePhotoUploadIntent(REDACTED, bytes=$byteSize)"

    private companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
        val ALLOWED_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        val UUID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}



sealed interface DispatchTemperatureMetadataRead {
    data class Available(val intent: DispatchTemperatureIntent?) : DispatchTemperatureMetadataRead
    data object Unavailable : DispatchTemperatureMetadataRead
}



sealed interface DispatchTemperaturePhotoUploadMetadataRead {
    data class Available(val intent: DispatchTemperaturePhotoUploadIntent?) :
        DispatchTemperaturePhotoUploadMetadataRead
    data object Unavailable : DispatchTemperaturePhotoUploadMetadataRead
}



enum class DispatchTemperatureMetadataWrite { Saved, Conflict, Stale, Unavailable }



sealed interface DispatchTemperatureGatewayResult {
    data class Current(val readiness: DispatchTemperatureReadiness) :
        DispatchTemperatureGatewayResult
    data class Recorded(val evidence: DispatchTemperatureEvidence) :
        DispatchTemperatureGatewayResult
    data object OutsideRangeBackendContractGap : DispatchTemperatureGatewayResult
    data object UnknownOutcome : DispatchTemperatureGatewayResult
    data object NetworkUnavailable : DispatchTemperatureGatewayResult
    data object ServiceUnavailable : DispatchTemperatureGatewayResult
    data object PermissionDenied : DispatchTemperatureGatewayResult
    data object ContextInvalidated : DispatchTemperatureGatewayResult
    data object SessionInvalidated : DispatchTemperatureGatewayResult
    data object Stale : DispatchTemperatureGatewayResult
    data object Conflict : DispatchTemperatureGatewayResult
}



sealed interface DispatchTemperaturePhotoGatewayResult {
    data class Evidence(val photo: DispatchTemperaturePhotoEvidence) :
        DispatchTemperaturePhotoGatewayResult
    data class Rejected(val code: String?) : DispatchTemperaturePhotoGatewayResult
    data object UnknownOutcome : DispatchTemperaturePhotoGatewayResult
    data object NetworkUnavailable : DispatchTemperaturePhotoGatewayResult
    data object ServiceUnavailable : DispatchTemperaturePhotoGatewayResult
    data object PermissionDenied : DispatchTemperaturePhotoGatewayResult
    data object ContextInvalidated : DispatchTemperaturePhotoGatewayResult
    data object SessionInvalidated : DispatchTemperaturePhotoGatewayResult
}
