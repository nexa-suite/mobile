package com.nexa.mobile.operations.feature.dispatch.model

import com.nexa.mobile.operations.feature.dispatch.model.DispatchDeliveryInstructionIntentStatus as InstructionIntentStatus
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

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

data class DispatchDeliveryInstructionsSnapshot(
    val deliveryId: String,
    val deliveryVersion: Long,
    val instructionSetVersion: Long,
    val instructions: List<DispatchDeliveryInstruction>
)

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

fun dispatchDeliveryInstructionRequestBody(
    instructionId: String?,
    kind: DispatchDeliveryInstructionKind,
    content: String
): String = buildJsonObject {
    if (instructionId != null) put("instructionId", JsonPrimitive(instructionId))
    put("kind", JsonPrimitive(kind.name))
    put("content", JsonPrimitive(content))
}.toString()
