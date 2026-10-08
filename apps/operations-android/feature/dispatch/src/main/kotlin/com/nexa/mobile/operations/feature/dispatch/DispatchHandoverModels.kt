package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoverCommand
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoverReceipt
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoverSnapshot

enum class DispatchHandoverStatus {
    Initial,
    Loading,
    Current,
    Submitting,
    Completed,
    UnknownOutcome,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    Stale,
    Conflict,
    ContextInvalidated,
    SessionInvalidated
}

@Immutable
data class DispatchHandoverUiState(
    val authorityEpoch: Long = 0,
    val fulfillmentId: String? = null,
    val status: DispatchHandoverStatus = DispatchHandoverStatus.Initial,
    val snapshot: DispatchHandoverSnapshot? = null,
    val receipt: DispatchHandoverReceipt? = null,
    val pendingCommand: DispatchHandoverCommand? = null,
    val hasPendingCommand: Boolean = false
) {
    val canConfirm: Boolean
        get() = status == DispatchHandoverStatus.Current && snapshot?.canConfirm == true &&
            !hasPendingCommand

    val canReplay: Boolean
        get() = status == DispatchHandoverStatus.UnknownOutcome && pendingCommand != null &&
            hasPendingCommand

    override fun toString(): String = "DispatchHandoverUiState(status=$status, " +
        "ready=${snapshot?.canConfirm == true}, deliveryRecorded=${receipt != null})"
}
