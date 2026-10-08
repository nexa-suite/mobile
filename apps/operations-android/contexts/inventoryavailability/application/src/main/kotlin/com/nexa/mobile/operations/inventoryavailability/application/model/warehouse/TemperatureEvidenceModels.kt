package com.nexa.mobile.operations.inventoryavailability.application.model.warehouse

import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceFacts
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidencePayload
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceSubject
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceSubjectType
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceUnit
import java.io.File

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

data class TemperatureEvidenceScope(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "TemperatureEvidenceScope(REDACTED)"
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
        require(originalFilename.isNotBlank() && originalFilename.length <= 255)
        require(declaredContentType in setOf("image/jpeg", "image/png", "image/webp"))
        require(byteSize in 1..10L * 1024 * 1024)
        require(checksumSha256.matches(Regex("[0-9a-f]{64}")))
    }

    override fun toString(): String =
        "TemperatureEvidencePhotoCandidate(type=$declaredContentType, bytes=$byteSize)"
}

data class TemperatureEvidencePhoto(
    val id: String,
    val subjectType: String,
    val subjectId: String,
    val lifecycleStatus: String
) {
    override fun toString(): String = "TemperatureEvidencePhoto(status=$lifecycleStatus)"
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
