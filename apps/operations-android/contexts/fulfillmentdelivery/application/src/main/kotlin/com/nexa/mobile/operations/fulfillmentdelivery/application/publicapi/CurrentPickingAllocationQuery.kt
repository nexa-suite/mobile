package com.nexa.mobile.operations.fulfillmentdelivery.application.publicapi

import com.nexa.mobile.operations.inventoryavailability.application.publicapi.PhysicalAllocationProjection

/** Narrow read contract for the current allocation attached to fulfillment picking. */
interface CurrentPickingAllocationQuery {
    suspend fun current(
        fulfillmentId: String,
        authority: PickingAllocationScope
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

/** Correlated caller facts for a guarded allocation read, not a grant of authority. */
data class PickingAllocationScope(
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
    override fun toString(): String = "PickingAllocationScope(REDACTED)"
}
