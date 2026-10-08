package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsAllocation
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsCheck
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsLine
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsResolution
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import java.time.Instant

enum class DispatchOutgoingGoodsStatus {
    Initial,
    Loading,
    Current,
    Submitting,
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
data class DispatchOutgoingGoodsUiState(
    val authorityEpoch: Long = 0,
    val fulfillment: DispatchReadiness? = null,
    val status: DispatchOutgoingGoodsStatus = DispatchOutgoingGoodsStatus.Initial,
    val allocation: DispatchOutgoingGoodsAllocation? = null,
    val currentCheck: DispatchOutgoingGoodsCheck? = null,
    val resolutionReason: String = "",
    val currentResolution: DispatchOutgoingGoodsResolution? = null,
    val observedAt: Instant? = null,
    val hasPendingCommand: Boolean = false
) {
    val canSubmit: Boolean
        get() = status == DispatchOutgoingGoodsStatus.Current &&
            fulfillment?.let { it.ready && it.fulfillmentStatus == READY_FOR_DISPATCH } == true &&
            allocation?.let { it.status == ALLOCATED && it.lines.isNotEmpty() } == true &&
            currentCheck?.openDiscrepancy != true &&
            currentCheck?.let { check ->
                allocation?.let { active ->
                    check.current && check.physicalAllocationId == active.id &&
                        check.physicalAllocationVersion == active.version
                }
            } != true &&
            allocation?.let { active -> active.lines.all(::isValidObservation) } == true

    val canResolveDiscrepancy: Boolean
        get() = status == DispatchOutgoingGoodsStatus.Current && !hasPendingCommand &&
            currentCheck?.let {
                it.current && it.openDiscrepancy && it.matches && it.discrepancy != null &&
                    allocation?.let { active ->
                        it.physicalAllocationId == active.id &&
                            it.physicalAllocationVersion == active.version
                    } == true
            } == true && resolutionReason.isNotBlank() && resolutionReason.trim().length <= 1000

    override fun toString(): String = "DispatchOutgoingGoodsUiState(status=$status, " +
        "lines=${allocation?.lines?.size ?: 0}, pending=$hasPendingCommand)"

    private fun isValidObservation(line: DispatchOutgoingGoodsLine): Boolean {
        val quantity =
            line.observedQuantity.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 } ?: return false
        return if (quantity.signum() == 0) {
            line.observedLotId.isBlank()
        } else {
            line.observedLotId.isUuid()
        }
    }

    private fun String.isUuid(): Boolean = UUID_PATTERN.matches(this)

    private companion object {
        const val READY_FOR_DISPATCH = "READY_FOR_DISPATCH"
        const val ALLOCATED = "ALLOCATED"
        val UUID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
