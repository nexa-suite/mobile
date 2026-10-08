package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.feature.dispatch.model.DispatchDeliveryInstructionIntent
import com.nexa.mobile.operations.feature.dispatch.model.DispatchDeliveryInstructionIntentStatus as InstructionIntentStatus
import com.nexa.mobile.operations.feature.dispatch.model.DispatchDeliveryInstructionKind
import com.nexa.mobile.operations.feature.dispatch.model.DispatchDeliveryInstructionLimits
import com.nexa.mobile.operations.feature.dispatch.model.DispatchDeliveryInstructionsSnapshot

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
                content.trim().length <= DispatchDeliveryInstructionLimits.MAX_CONTENT &&
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
        const val MAX_INSTRUCTION_CONTENT = DispatchDeliveryInstructionLimits.MAX_CONTENT
    }
}
