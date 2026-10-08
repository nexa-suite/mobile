package com.nexa.mobile.operations.fulfillmentdelivery.application.publicapi

import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingAuthority
import com.nexa.mobile.operations.inventoryavailability.application.publicapi.PhysicalAllocationProjection

/** Narrow read contract for the current allocation attached to fulfillment picking. */
interface CurrentPickingAllocationQuery {
    suspend fun current(
        fulfillmentId: String,
        authority: PickingAuthority
    ): CurrentPickingAllocationResult
}

sealed interface CurrentPickingAllocationResult {
    data class Loaded(val allocation: PhysicalAllocationProjection) :
        CurrentPickingAllocationResult

    data object NotFound : CurrentPickingAllocationResult
    data object NetworkUnavailable : CurrentPickingAllocationResult
    data object ServiceUnavailable : CurrentPickingAllocationResult
    data object PermissionDenied : CurrentPickingAllocationResult
    data object ContextInvalidated : CurrentPickingAllocationResult
    data object SessionInvalidated : CurrentPickingAllocationResult
}
