package com.nexa.mobile.operations.feature.dispatch.model

import java.io.File
import java.math.BigDecimal
import java.time.Instant

data class DispatchTemperatureEvidence(
    val id: String,
    val fulfillmentId: String,
    val fulfillmentVersion: Long,
    val lotId: String,
    val valueCelsius: BigDecimal,
    val occurredAt: Instant,
    val actorMembershipId: String,
    val status: String,
    val evidenceObjectId: String? = null,
    val expectedLotVersion: Long? = null,
    val resultingLotVersion: Long? = null,
    val inventoryTemperatureEvaluationId: String? = null,
    val inventoryLotStatus: String? = null,
    val affectedQuantity: BigDecimal? = null
) {
    override fun toString(): String = "DispatchTemperatureEvidence(REDACTED, status=$status)"
}

data class DispatchTemperatureLot(
    val skuId: String,
    val lotId: String?,
    val warehouseId: String?,
    val zoneId: String?,
    val skuColdChainRequired: Boolean,
    val requiredForFulfillment: Boolean,
    val minimumCelsius: BigDecimal?,
    val maximumCelsius: BigDecimal?,
    val status: String,
    val latestEvidence: DispatchTemperatureEvidence?,
    val version: Long? = null
) {
    val supportsInRangeEvidence: Boolean
        get() = skuColdChainRequired && (minimumCelsius != null || maximumCelsius != null) &&
            lotId != null && warehouseId != null && zoneId != null && version != null

    fun isWithinRange(value: BigDecimal): Boolean =
        (minimumCelsius == null || value >= minimumCelsius) &&
            (maximumCelsius == null || value <= maximumCelsius)

    override fun toString(): String = "DispatchTemperatureLot(REDACTED, status=$status)"
}

data class DispatchTemperatureReadiness(
    val fulfillmentId: String,
    val fulfillmentStatus: String,
    val fulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val temperatureRequiredForFulfillment: Boolean,
    val asOf: Instant,
    val lots: List<DispatchTemperatureLot>
) {
    override fun toString(): String = "DispatchTemperatureReadiness(REDACTED, " +
        "version=$fulfillmentVersion, lots=${lots.size})"
}

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

    fun buildRequestBody(): String {
        val common = "{\"lotId\":\"$lotId\",\"value\":" +
            "${valueCelsius.stripTrailingZeros().toPlainString()},\"unit\":\"CELSIUS\",\"occurredAt\":\"$occurredAt\""
        val lotVersion = expectedLotVersion ?: return "$common}"
        val evidence = evidenceObjectId?.let { "\"$it\"" } ?: "null"
        return "$common,\"expectedLotVersion\":$lotVersion,\"evidenceObjectId\":$evidence}"
    }

    fun isValid(): Boolean = UUID_PATTERN.matches(fulfillmentId) && UUID_PATTERN.matches(lotId) &&
        expectedFulfillmentVersion >= 0 && idempotencyKey.isNotBlank() &&
        idempotencyKey.length <= 160 &&
        (expectedLotVersion == null || expectedLotVersion >= 0) &&
        (expectedLotVersion != null || evidenceObjectId == null) &&
        (evidenceObjectId == null || UUID_PATTERN.matches(evidenceObjectId)) &&
        exactRequestBody == buildRequestBody()

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

data class DispatchTemperaturePhotoEvidence(
    val id: String,
    val subjectType: String,
    val subjectId: String,
    val lifecycleStatus: String
) {
    override fun toString(): String = "DispatchTemperaturePhotoEvidence(status=$lifecycleStatus)"
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
