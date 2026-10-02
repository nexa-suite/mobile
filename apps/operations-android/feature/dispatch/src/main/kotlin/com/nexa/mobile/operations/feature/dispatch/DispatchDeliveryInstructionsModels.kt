package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.feature.dispatch.DispatchDeliveryInstructionIntentStatus as InstructionIntentStatus
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Immutable
data class DispatchDeliveryInstruction(
    val id: String,
    val kind: DispatchDeliveryInstructionKind,
    val content: String,
    val instructionVersion: Long,
    val critical: Boolean,
    val acknowledged: Boolean,
    val acknowledgedAt: String?,
    val acknowledgedByMembershipId: String?,
    val sourceKind: String?,
    val recordedByMembershipId: String?,
    val recordedAt: String?
)

enum class DispatchDeliveryInstructionKind {
    NORMAL,
    COLD_CHAIN,
    ACCESS_RESTRICTION,
    SPECIAL_UNLOADING,
    CUSTOMER_SAFETY,
    GOODS_HANDLING;

    val critical: Boolean get() = this != NORMAL
}

@Immutable
data class DispatchDeliveryInstructionsSnapshot(
    val deliveryId: String,
    val deliveryVersion: Long,
    val instructionSetVersion: Long,
    val instructions: List<DispatchDeliveryInstruction>
)

enum class DispatchDeliveryInstructionsStatus {
    Initial,
    Loading,
    Current,
    Saving,
    UnknownOutcome,
    InvalidDeliveryId,
    NotFound,
    StaleVersion,
    Conflict,
    PermissionDenied,
    NetworkUnavailable,
    ServiceUnavailable,
    ContextInvalidated,
    SessionInvalidated
}

@Immutable
data class DispatchDeliveryInstructionsUiState(
    val authorityEpoch: Long = 0,
    val status: DispatchDeliveryInstructionsStatus = DispatchDeliveryInstructionsStatus.Initial,
    val deliveryIdInput: String = "",
    val snapshot: DispatchDeliveryInstructionsSnapshot? = null,
    val selectedInstructionId: String? = null,
    val selectedKind: DispatchDeliveryInstructionKind = DispatchDeliveryInstructionKind.NORMAL,
    val content: String = "",
    val pendingIntent: DispatchDeliveryInstructionIntent? = null,
    val metadataReady: Boolean = false,
    val errorCode: String? = null,
    val canRead: Boolean = false,
    val canPublish: Boolean = false
) {
    val isEditingOperationalInstruction: Boolean
        get() = selectedInstructionId != null

    val canPublishCurrent: Boolean
        get() {
            val current = snapshot ?: return false
            val selected = selectedInstructionId?.let { id ->
                current.instructions.firstOrNull { it.id.equals(id, ignoreCase = true) }
            }
            return status == DispatchDeliveryInstructionsStatus.Current && metadataReady &&
                canPublish &&
                pendingIntent == null && content.isNotBlank() &&
                content.trim().length <= MAX_INSTRUCTION_CONTENT &&
                (
                    selectedInstructionId == null ||
                        selected?.sourceKind == OPERATIONAL_DISPATCH_SOURCE
                    )
        }

    val canRetryUnknownOutcome: Boolean
        get() = status == DispatchDeliveryInstructionsStatus.UnknownOutcome && metadataReady &&
            pendingIntent?.status == InstructionIntentStatus.UnknownOutcome &&
            canRead && canPublish

    override fun toString(): String = "DispatchDeliveryInstructionsUiState(status=$status, " +
        "deliveryId=REDACTED, instructions=${snapshot?.instructions?.size ?: 0}, pending=${pendingIntent != null})"

    companion object {
        const val OPERATIONAL_DISPATCH_SOURCE = "OPERATIONAL_DISPATCH"
        const val MAX_INSTRUCTION_CONTENT = 2000
    }
}

data class DispatchDeliveryInstructionScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "DispatchDeliveryInstructionScopeIdentity(REDACTED)"
}

enum class DispatchDeliveryInstructionIntentStatus {
    Pending,
    UnknownOutcome
}

/** Exact request, key and version frozen before first publication attempt. */
data class DispatchDeliveryInstructionIntent(
    val scope: DispatchDeliveryInstructionScopeIdentity,
    val deliveryId: String,
    val expectedDeliveryVersion: Long,
    val instructionId: String?,
    val kind: DispatchDeliveryInstructionKind,
    val content: String,
    val exactRequestBody: String,
    val idempotencyKey: String,
    val status: InstructionIntentStatus = InstructionIntentStatus.Pending
) {
    override fun toString(): String = "DispatchDeliveryInstructionIntent(deliveryId=REDACTED, " +
        "version=$expectedDeliveryVersion, status=$status)"
}

sealed interface DispatchDeliveryInstructionMetadataRead {
    data class Available(val intent: DispatchDeliveryInstructionIntent?) :
        DispatchDeliveryInstructionMetadataRead
    data object Unavailable : DispatchDeliveryInstructionMetadataRead
}

enum class DispatchDeliveryInstructionMetadataWrite {
    Saved,
    Conflict,
    Stale,
    Unavailable
}

interface DispatchDeliveryInstructionMetadataStore {
    suspend fun loadIntent(
        scope: DispatchDeliveryInstructionScopeIdentity,
        deliveryId: String
    ): DispatchDeliveryInstructionMetadataRead

    suspend fun saveIntent(
        intent: DispatchDeliveryInstructionIntent
    ): DispatchDeliveryInstructionMetadataWrite

    suspend fun clearIntent(
        scope: DispatchDeliveryInstructionScopeIdentity,
        deliveryId: String,
        idempotencyKey: String
    ): DispatchDeliveryInstructionMetadataWrite
}

sealed interface DispatchDeliveryInstructionsGatewayResult {
    data class Snapshot(val value: DispatchDeliveryInstructionsSnapshot) :
        DispatchDeliveryInstructionsGatewayResult
    data class Published(val value: DispatchDeliveryInstructionReceipt) :
        DispatchDeliveryInstructionsGatewayResult
    data class Rejected(val code: String?) : DispatchDeliveryInstructionsGatewayResult
    data object NotFound : DispatchDeliveryInstructionsGatewayResult
    data object StaleVersion : DispatchDeliveryInstructionsGatewayResult
    data object UnknownOutcome : DispatchDeliveryInstructionsGatewayResult
    data object NetworkUnavailable : DispatchDeliveryInstructionsGatewayResult
    data object ServiceUnavailable : DispatchDeliveryInstructionsGatewayResult
    data object PermissionDenied : DispatchDeliveryInstructionsGatewayResult
    data object ContextInvalidated : DispatchDeliveryInstructionsGatewayResult
    data object SessionInvalidated : DispatchDeliveryInstructionsGatewayResult
}

data class DispatchDeliveryInstructionReceipt(
    val deliveryId: String,
    val instructionId: String,
    val kind: DispatchDeliveryInstructionKind,
    val content: String,
    val instructionVersion: Long,
    val critical: Boolean,
    val deliveryVersion: Long,
    val instructionSetVersion: Long,
    val replayed: Boolean
)

interface DispatchDeliveryInstructionsGateway {
    suspend fun currentInstructions(
        deliveryId: String,
        context: DispatchAuthorityContext
    ): DispatchDeliveryInstructionsGatewayResult

    suspend fun publish(
        intent: DispatchDeliveryInstructionIntent,
        context: DispatchAuthorityContext
    ): DispatchDeliveryInstructionsGatewayResult
}

fun dispatchDeliveryInstructionRequestBody(
    instructionId: String?,
    kind: DispatchDeliveryInstructionKind,
    content: String
): String = buildJsonObject {
    if (instructionId != null) put("instructionId", JsonPrimitive(instructionId))
    put("kind", JsonPrimitive(kind.name))
    put("content", JsonPrimitive(content))
}.toString()
