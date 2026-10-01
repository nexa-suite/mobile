package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.runtime.Immutable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Immutable
data class DispatchOutgoingGoodsLine(
    val physicalAllocationLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val expectedLotId: String?,
    val allocatedQuantity: BigDecimal,
    val releasedQuantity: BigDecimal,
    val consumedQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val unit: String,
    val observedLotId: String = "",
    val observedQuantity: String = ""
) {
    override fun toString(): String = "DispatchOutgoingGoodsLine(REDACTED, quantity=$remainingQuantity)"
}

@Immutable
data class DispatchOutgoingGoodsAllocation(
    val id: String,
    val status: String,
    val version: Long,
    val asOf: Instant,
    val lines: List<DispatchOutgoingGoodsLine>
)

@Immutable
data class DispatchOutgoingGoodsCheckLine(
    val physicalAllocationLineId: String,
    val expectedLotId: String?,
    val observedLotId: String?,
    val expectedQuantity: BigDecimal,
    val observedQuantity: BigDecimal,
    val unit: String,
    val matches: Boolean
)

@Immutable
data class DispatchOutgoingGoodsCheck(
    val id: String,
    val fulfillmentId: String,
    val fulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val matches: Boolean,
    val current: Boolean,
    val openDiscrepancy: Boolean,
    val checkedAt: Instant,
    val lines: List<DispatchOutgoingGoodsCheckLine>,
    val replayed: Boolean
) {
    override fun toString(): String = "DispatchOutgoingGoodsCheck(REDACTED, matches=$matches, current=$current)"
}

data class DispatchOutgoingGoodsObservation(
    val physicalAllocationLineId: String,
    val observedLotId: String?,
    val observedQuantity: BigDecimal
)

data class DispatchOutgoingGoodsCommand(
    val fulfillmentId: String,
    val expectedFulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val observations: List<DispatchOutgoingGoodsObservation>,
    val idempotencyKey: String,
    val exactRequestBody: String
) {
    override fun toString(): String = "DispatchOutgoingGoodsCommand(REDACTED, " +
        "versions=$expectedFulfillmentVersion/$physicalAllocationVersion)"
}

data class DispatchOutgoingGoodsScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "DispatchOutgoingGoodsScopeIdentity(REDACTED)"
}

enum class DispatchOutgoingGoodsIntentStatus { Pending, UnknownOutcome }

/** Frozen request intent is encrypted and scoped before a mutating request can leave the device. */
data class DispatchOutgoingGoodsIntent(
    val scope: DispatchOutgoingGoodsScopeIdentity,
    val command: DispatchOutgoingGoodsCommand,
    val status: DispatchOutgoingGoodsIntentStatus = DispatchOutgoingGoodsIntentStatus.Pending
) {
    override fun toString(): String = "DispatchOutgoingGoodsIntent(REDACTED, status=$status)"
}

sealed interface DispatchOutgoingGoodsMetadataRead {
    data class Available(val intent: DispatchOutgoingGoodsIntent?) : DispatchOutgoingGoodsMetadataRead
    data object Unavailable : DispatchOutgoingGoodsMetadataRead
}

enum class DispatchOutgoingGoodsMetadataWrite { Saved, Conflict, Stale, Unavailable }

interface DispatchOutgoingGoodsMetadataStore {
    suspend fun loadIntent(
        scope: DispatchOutgoingGoodsScopeIdentity,
        fulfillmentId: String
    ): DispatchOutgoingGoodsMetadataRead

    suspend fun saveIntent(intent: DispatchOutgoingGoodsIntent): DispatchOutgoingGoodsMetadataWrite

    suspend fun clearIntent(
        scope: DispatchOutgoingGoodsScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): DispatchOutgoingGoodsMetadataWrite
}

data class DispatchOutgoingGoodsSnapshot(
    val allocation: DispatchOutgoingGoodsAllocation,
    val currentCheck: DispatchOutgoingGoodsCheck?
)

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
    val observedAt: Instant? = null,
    val hasPendingCommand: Boolean = false
) {
    val canSubmit: Boolean
        get() = status == DispatchOutgoingGoodsStatus.Current &&
            fulfillment?.let { it.ready && it.fulfillmentStatus == READY_FOR_DISPATCH } == true &&
            allocation?.let { it.status == ALLOCATED && it.lines.isNotEmpty() } == true &&
            currentCheck?.openDiscrepancy != true &&
            currentCheck?.let {
                it.current && it.physicalAllocationId == allocation?.id &&
                    it.physicalAllocationVersion == allocation.version
            } != true &&
            allocation?.lines?.all(::isValidObservation) == true

    override fun toString(): String = "DispatchOutgoingGoodsUiState(status=$status, " +
        "lines=${allocation?.lines?.size ?: 0}, pending=$hasPendingCommand)"

    private fun isValidObservation(line: DispatchOutgoingGoodsLine): Boolean {
        val quantity = line.observedQuantity.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 } ?: return false
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

sealed interface DispatchOutgoingGoodsGatewayResult {
    data class Snapshot(val value: DispatchOutgoingGoodsSnapshot) : DispatchOutgoingGoodsGatewayResult
    data class Recorded(val value: DispatchOutgoingGoodsCheck) : DispatchOutgoingGoodsGatewayResult
    data object UnknownOutcome : DispatchOutgoingGoodsGatewayResult
    data object NetworkUnavailable : DispatchOutgoingGoodsGatewayResult
    data object ServiceUnavailable : DispatchOutgoingGoodsGatewayResult
    data object PermissionDenied : DispatchOutgoingGoodsGatewayResult
    data object Stale : DispatchOutgoingGoodsGatewayResult
    data object Conflict : DispatchOutgoingGoodsGatewayResult
    data object ContextInvalidated : DispatchOutgoingGoodsGatewayResult
    data object SessionInvalidated : DispatchOutgoingGoodsGatewayResult
}

/** Server-owned physical facts and immutable outgoing-goods comparison. */
interface DispatchOutgoingGoodsGateway {
    suspend fun load(
        fulfillment: DispatchReadiness,
        context: DispatchAuthorityContext
    ): DispatchOutgoingGoodsGatewayResult

    suspend fun record(
        fulfillment: DispatchReadiness,
        allocation: DispatchOutgoingGoodsAllocation,
        command: DispatchOutgoingGoodsCommand,
        context: DispatchAuthorityContext
    ): DispatchOutgoingGoodsGatewayResult
}
