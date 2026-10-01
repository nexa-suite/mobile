package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.runtime.Immutable
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
    val detailLabel: String
) {
    init {
        require(id.isNotBlank() && primaryLabel.isNotBlank())
    }
}

@Immutable
data class TemperatureEvidencePayload(
    val subjectType: TemperatureEvidenceSubjectType,
    val subjectId: String,
    val value: String,
    val unit: TemperatureEvidenceUnit,
    val occurredAt: String
) {
    init {
        require(subjectId.isNotBlank() && value.isNotBlank() && occurredAt.isNotBlank())
    }

    override fun toString(): String = "TemperatureEvidencePayload(REDACTED)"
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
    val source: String
)

enum class TemperatureLookupStatus {
    NotRequested,
    Loading,
    Ready,
    Empty,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

sealed interface TemperatureLookupResult {
    data class Subjects(val items: List<TemperatureEvidenceSubject>) : TemperatureLookupResult
    data object NetworkUnavailable : TemperatureLookupResult
    data object ServiceUnavailable : TemperatureLookupResult
    data object PermissionDenied : TemperatureLookupResult
    data object ContextInvalidated : TemperatureLookupResult
    data object SessionInvalidated : TemperatureLookupResult
}

sealed interface TemperatureSubmitResult {
    data class Confirmed(val facts: TemperatureEvidenceFacts) : TemperatureSubmitResult
    data class Rejected(val code: String?) : TemperatureSubmitResult
    data object UnknownOutcome : TemperatureSubmitResult
    data object PermissionDenied : TemperatureSubmitResult
    data object ContextInvalidated : TemperatureSubmitResult
    data object SessionInvalidated : TemperatureSubmitResult
    data object ServiceUnavailable : TemperatureSubmitResult
}

/** Application adapter owns protected transport and full current-authority revalidation. */
interface TemperatureEvidenceGateway {
    suspend fun subjects(
        type: TemperatureEvidenceSubjectType,
        authority: TemperatureEvidenceAuthority
    ): TemperatureLookupResult

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
    val occurredAt: String
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
    suspend fun loadDraft(scope: TemperatureEvidenceScope): TemperatureMetadataRead<TemperatureEvidenceDraft>

    suspend fun saveDraft(
        scope: TemperatureEvidenceScope,
        draft: TemperatureEvidenceDraft
    ): TemperatureMetadataWrite

    suspend fun loadIntent(scope: TemperatureEvidenceScope):
        TemperatureMetadataRead<TemperatureEvidenceIntent>

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
