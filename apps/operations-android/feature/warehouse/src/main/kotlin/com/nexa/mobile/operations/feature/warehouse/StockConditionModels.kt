package com.nexa.mobile.operations.feature.warehouse

import androidx.compose.runtime.Immutable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Immutable
data class StockConditionLot(
    val id: String,
    val warehouseId: String,
    val zoneId: String,
    val catalogItemId: String?,
    val skuId: String?,
    val batchNumber: String,
    val expirationDate: LocalDate,
    val receivedAt: Instant,
    val onHand: BigDecimal,
    val reserved: BigDecimal,
    /** Server's `available` lot projection is physical remainder, not sellable quantity. */
    val physicalRemaining: BigDecimal,
    val unit: String,
    val status: String,
    val version: Long
) {
    override fun toString(): String =
        "StockConditionLot(id=REDACTED, warehouseId=REDACTED, quantities=REDACTED)"
}

@Immutable
data class StockConditionAvailability(
    val catalogItemId: String,
    val status: String,
    val asOf: Instant,
    val physicalQuantity: BigDecimal?,
    val safetyStock: BigDecimal?,
    val sellableQuantity: BigDecimal?
)

enum class StockConditionStatus {
    Initial,
    Loading,
    Current,
    Empty,
    PermissionUnknown,
    PermissionDenied,
    NetworkUnavailable,
    ServiceUnavailable,
    ContextInvalidated,
    SessionInvalidated
}

enum class StockConditionDetailStatus {
    NotRequested,
    Loading,
    Current,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

enum class StockConditionAvailabilityStatus {
    NotRequested,
    Loading,
    Current,
    Unavailable,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

@Immutable
data class StockConditionUiState(
    val authorityEpoch: Long = 0,
    val status: StockConditionStatus = StockConditionStatus.Initial,
    val lots: List<StockConditionLot> = emptyList(),
    val listObservedAt: Instant? = null,
    val selectedLotId: String? = null,
    val selectedLot: StockConditionLot? = null,
    val detailStatus: StockConditionDetailStatus = StockConditionDetailStatus.NotRequested,
    val detailObservedAt: Instant? = null,
    val availability: StockConditionAvailability? = null,
    val availabilityStatus: StockConditionAvailabilityStatus =
        StockConditionAvailabilityStatus.NotRequested
) {
    override fun toString(): String = "StockConditionUiState(status=$status, lots=${lots.size}, " +
        "detailStatus=$detailStatus, availabilityStatus=$availabilityStatus, " +
        "authorityEpoch=$authorityEpoch)"
}

sealed interface StockConditionGatewayResult {
    data class Lots(val items: List<StockConditionLot>) : StockConditionGatewayResult
    data class Lot(val item: StockConditionLot) : StockConditionGatewayResult
    data class Availability(val item: StockConditionAvailability?) : StockConditionGatewayResult
    data object NetworkUnavailable : StockConditionGatewayResult
    data object ServiceUnavailable : StockConditionGatewayResult
    data object PermissionDenied : StockConditionGatewayResult
    data object ContextInvalidated : StockConditionGatewayResult
    data object SessionInvalidated : StockConditionGatewayResult
}

/** Read-only gateway; each request is bound by the app adapter to current verified identity. */
interface StockConditionGateway {
    suspend fun lots(context: ActiveOperationsContext): StockConditionGatewayResult

    suspend fun lot(lotId: String, context: ActiveOperationsContext): StockConditionGatewayResult

    suspend fun availability(
        warehouseId: String,
        catalogItemId: String,
        context: ActiveOperationsContext
    ): StockConditionGatewayResult
}
