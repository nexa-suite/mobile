package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.runtime.Immutable
import java.io.File
import java.math.BigDecimal
import java.time.Instant

@Immutable
data class TemperatureEvidenceAuthority(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>,
    val authorityEpoch: Long
) {
    init {
        require(listOf(userId, tenantId, workspaceId, membershipId).all(String::isNotBlank))
        require(authorityEpoch > 0)
    }

    val canRecord: Boolean get() = "inventory.receive" in permissions
    val canLookUpSubjects: Boolean
        get() = permissions.any { it in SUBJECT_LOOKUP_PERMISSIONS }

    val scope: TemperatureEvidenceScope
        get() = TemperatureEvidenceScope(userId, tenantId, workspaceId, membershipId)

    override fun toString(): String =
        "TemperatureEvidenceAuthority(scope=REDACTED, permissions=${permissions.size}, " +
            "epoch=$authorityEpoch)"

    private companion object {
        val SUBJECT_LOOKUP_PERMISSIONS = setOf("warehouse.read", "inventory.read", "warehouse:read")
    }
}

@Immutable
data class TemperatureEvidenceScope(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "TemperatureEvidenceScope(REDACTED)"
}

enum class TemperatureEvidenceSubjectType { LOT, WAREHOUSE }
enum class TemperatureEvidenceUnit { CELSIUS, FAHRENHEIT }

@Immutable
data class TemperatureEvidenceSubject(
    val id: String,
    val type: TemperatureEvidenceSubjectType,
    val primaryLabel: String,
    val detailLabel: String,
    val warehouseId: String? = null,
    val lotVersion: Long? = null,
    val physicalRemaining: BigDecimal? = null,
    val quantityUnit: String? = null
) {
    init {
        require(id.isNotBlank() && primaryLabel.isNotBlank())
        require(warehouseId == null || warehouseId.isNotBlank())
        require(lotVersion == null || lotVersion >= 0)
        require(physicalRemaining == null || physicalRemaining.signum() >= 0)
    }
}

@Immutable
data class TemperatureEvidencePayload(
    val subjectType: TemperatureEvidenceSubjectType,
    val subjectId: String,
    val value: String,
    val unit: TemperatureEvidenceUnit,
    val occurredAt: String,
    val evidenceObjectId: String? = null,
    val expectedLotVersion: Long? = null,
    val affectedQuantity: String? = null,
    val reason: String? = null,
    val sourceEvidenceId: String? = null
) {
    init {
        require(subjectId.isNotBlank() && value.isNotBlank() && occurredAt.isNotBlank())
        require(evidenceObjectId == null || UUID_PATTERN.matches(evidenceObjectId))
        require(expectedLotVersion == null || expectedLotVersion >= 0)
        require(affectedQuantity == null || BigDecimal(affectedQuantity).signum() > 0)
        require(reason == null || reason.isNotBlank())
        require(sourceEvidenceId == null || UUID_PATTERN.matches(sourceEvidenceId))
    }

    override fun toString(): String = "TemperatureEvidencePayload(REDACTED)"

    private companion object {
        val UUID_PATTERN =
            Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

@Immutable
data class TemperatureEvidenceSelectionFacts(
    val temperatureEvidenceId: String,
    val lotId: String,
    val affectedQuantity: BigDecimal,
    val expectedLotVersion: Long,
    val resultingLotVersion: Long,
    val inventoryTemperatureEvaluationId: String?,
    val inventoryLotStatus: String?,
    val remainingHeldQuantity: BigDecimal?,
    val actorMembershipId: String,
    val occurredAt: Instant,
    val evidenceObjectId: String?,
    val reason: String?,
    val evaluationStatus: String?,
    val disposition: String?,
    val blocksCommittedExecution: Boolean
) {
    init {
        require(affectedQuantity.signum() > 0)
        require(expectedLotVersion >= 0 && resultingLotVersion >= 0)
        require(remainingHeldQuantity == null || remainingHeldQuantity.signum() >= 0)
    }
}

/** Facts shown as confirmed only when decoded from the authoritative POST response. */
@Immutable
data class TemperatureEvidenceFacts(
    val id: String,
    val subjectType: TemperatureEvidenceSubjectType,
    val subjectId: String,
    val lotId: String?,
    val warehouseId: String?,
    val value: BigDecimal,
    val unit: TemperatureEvidenceUnit,
    val occurredAt: Instant,
    val actorMembershipId: String,
    val status: String,
    val source: String,
    val evidenceObjectId: String? = null,
    val expectedLotVersion: Long? = null,
    val resultingLotVersion: Long? = null,
    val inventoryTemperatureEvaluationId: String? = null,
    val inventoryLotStatus: String? = null,
    val affectedQuantity: BigDecimal? = null,
    val remainingHeldQuantity: BigDecimal? = null,
    val reason: String? = null,
    val sourceEvidenceId: String? = null,
    val exceptionId: String? = null,
    val exceptionStatus: String? = null,
    val evaluationStatus: String? = null,
    val disposition: String? = null,
    val selections: List<TemperatureEvidenceSelectionFacts> = emptyList()
) {
    init {
        require(remainingHeldQuantity == null || remainingHeldQuantity.signum() >= 0)
    }
}

enum class TemperatureLookupStatus {
    NotRequested,
    Loading,
    Ready,
    Empty,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated,
    Stale,
    Rejected
}

sealed interface TemperatureLookupResult {
    data class Subjects(val items: List<TemperatureEvidenceSubject>) : TemperatureLookupResult
    data class EvidenceSnapshot(val facts: TemperatureEvidenceFacts) : TemperatureLookupResult
    data class Rejected(val code: String?) : TemperatureLookupResult
    data object Stale : TemperatureLookupResult
    data object NetworkUnavailable : TemperatureLookupResult
    data object ServiceUnavailable : TemperatureLookupResult
    data object PermissionDenied : TemperatureLookupResult
    data object ContextInvalidated : TemperatureLookupResult
    data object SessionInvalidated : TemperatureLookupResult
}

sealed interface TemperatureSubmitResult {
    data class Confirmed(val facts: TemperatureEvidenceFacts) : TemperatureSubmitResult
    data class Rejected(val code: String?) : TemperatureSubmitResult
    data object Stale : TemperatureSubmitResult
    data object UnknownOutcome : TemperatureSubmitResult
    data object PermissionDenied : TemperatureSubmitResult
    data object ContextInvalidated : TemperatureSubmitResult
    data object SessionInvalidated : TemperatureSubmitResult
    data object ServiceUnavailable : TemperatureSubmitResult
}

data class TemperatureEvidencePhotoSelection(
    val scope: TemperatureEvidenceScope,
    val authorityEpoch: Long,
    val subjectType: TemperatureEvidenceSubjectType,
    val subjectId: String,
    val warehouseId: String,
    val expectedLotVersion: Long?
) {
    init {
        require(authorityEpoch > 0)
        require(subjectId.isNotBlank() && warehouseId.isNotBlank())
        require(expectedLotVersion == null || expectedLotVersion >= 0)
        require(subjectType == TemperatureEvidenceSubjectType.LOT || expectedLotVersion == null)
    }

    override fun toString(): String = "TemperatureEvidencePhotoSelection(REDACTED)"
}

data class TemperatureEvidencePhotoCandidate(
    val file: File,
    val originalFilename: String,
    val declaredContentType: String,
    val byteSize: Long,
    val checksumSha256: String
) {
    init {
        require(file.isFile && file.length() == byteSize)
        require(originalFilename.isNotBlank() && originalFilename.length <= 255)
        require(declaredContentType in setOf("image/jpeg", "image/png", "image/webp"))
        require(byteSize in 1..10L * 1024 * 1024)
        require(checksumSha256.matches(Regex("[0-9a-f]{64}")))
    }

    override fun toString(): String =
        "TemperatureEvidencePhotoCandidate(type=$declaredContentType, bytes=$byteSize)"
}

@Immutable
data class TemperatureEvidencePhoto(
    val id: String,
    val subjectType: String,
    val subjectId: String,
    val lifecycleStatus: String
) {
    override fun toString(): String = "TemperatureEvidencePhoto(status=$lifecycleStatus)"
}

enum class TemperaturePhotoStatus {
    None,
    Uploading,
    Checking,
    AwaitingAvailability,
    Available,
    UnknownOutcome,
    NetworkUnavailable,
    ServiceUnavailable,
    Rejected,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

sealed interface TemperaturePhotoResult {
    data class Evidence(val photo: TemperatureEvidencePhoto) : TemperaturePhotoResult
    data class Rejected(val code: String?) : TemperaturePhotoResult
    data object UnknownOutcome : TemperaturePhotoResult
    data object NetworkUnavailable : TemperaturePhotoResult
    data object ServiceUnavailable : TemperaturePhotoResult
    data object PermissionDenied : TemperaturePhotoResult
    data object ContextInvalidated : TemperaturePhotoResult
    data object SessionInvalidated : TemperaturePhotoResult
}

/** Application adapter owns protected transport and full current-authority revalidation. */
interface TemperatureEvidenceGateway {
    suspend fun subjects(
        type: TemperatureEvidenceSubjectType,
        authority: TemperatureEvidenceAuthority
    ): TemperatureLookupResult

    suspend fun subject(
        type: TemperatureEvidenceSubjectType,
        subjectId: String,
        authority: TemperatureEvidenceAuthority
    ): TemperatureLookupResult = subjects(type, authority)

    suspend fun snapshot(
        evidenceId: String,
        authority: TemperatureEvidenceAuthority
    ): TemperatureLookupResult = TemperatureLookupResult.ServiceUnavailable

    suspend fun uploadPhoto(
        selection: TemperatureEvidencePhotoSelection,
        candidate: TemperatureEvidencePhotoCandidate,
        idempotencyKey: String,
        authority: TemperatureEvidenceAuthority
    ): TemperaturePhotoResult = TemperaturePhotoResult.ServiceUnavailable

    suspend fun photoStatus(
        evidenceId: String,
        warehouseId: String,
        authority: TemperatureEvidenceAuthority
    ): TemperaturePhotoResult = TemperaturePhotoResult.ServiceUnavailable

    suspend fun record(
        payload: TemperatureEvidencePayload,
        idempotencyKey: String,
        authority: TemperatureEvidenceAuthority
    ): TemperatureSubmitResult
}

@Immutable
data class TemperatureEvidenceDraft(
    val subjectType: TemperatureEvidenceSubjectType,
    val subjectId: String,
    val value: String,
    val unit: TemperatureEvidenceUnit,
    val occurredAt: String,
    val affectedQuantity: String = "",
    val reason: String = "",
    val sourceEvidenceId: String = "",
    val evidenceObjectId: String? = null
) {
    override fun toString(): String = "TemperatureEvidenceDraft(REDACTED)"
}

enum class TemperatureIntentStatus { Pending, UnknownOutcome }

@Immutable
data class TemperatureEvidenceIntent(
    val scope: TemperatureEvidenceScope,
    val idempotencyKey: String,
    val payload: TemperatureEvidencePayload,
    val status: TemperatureIntentStatus
) {
    override fun toString(): String = "TemperatureEvidenceIntent(status=$status, key=REDACTED)"
}

sealed interface TemperatureMetadataRead<out T> {
    data class Available<T>(val value: T?) : TemperatureMetadataRead<T>
    data object Unavailable : TemperatureMetadataRead<Nothing>
}

enum class TemperatureMetadataWrite { Saved, Unavailable }

interface TemperatureEvidenceMetadataStore {
    suspend fun loadDraft(
        scope: TemperatureEvidenceScope
    ): TemperatureMetadataRead<TemperatureEvidenceDraft>

    suspend fun saveDraft(
        scope: TemperatureEvidenceScope,
        draft: TemperatureEvidenceDraft
    ): TemperatureMetadataWrite

    suspend fun loadIntent(
        scope: TemperatureEvidenceScope
    ): TemperatureMetadataRead<TemperatureEvidenceIntent>

    suspend fun saveIntent(intent: TemperatureEvidenceIntent): TemperatureMetadataWrite

    suspend fun markUnknownOutcome(
        scope: TemperatureEvidenceScope,
        idempotencyKey: String
    ): TemperatureMetadataWrite

    suspend fun clearIntent(
        scope: TemperatureEvidenceScope,
        idempotencyKey: String
    ): TemperatureMetadataWrite
}
