package com.nexa.mobile.operations.inventoryavailability.presentation.warehouse

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockConditionAvailability
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockConditionLot
import java.time.Instant

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
