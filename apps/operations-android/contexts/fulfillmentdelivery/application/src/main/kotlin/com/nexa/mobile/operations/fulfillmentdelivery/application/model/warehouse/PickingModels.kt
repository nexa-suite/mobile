package com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse

import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.FulfillmentPickingLine
import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.FulfillmentPickingSnapshot
import com.nexa.mobile.operations.inventoryavailability.application.publicapi.PhysicalAllocationLineProjection
import com.nexa.mobile.operations.inventoryavailability.application.publicapi.PhysicalAllocationProjection
import java.math.BigDecimal

data class PickingAuthority(
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

    val scope: PickingScopeIdentity
        get() = PickingScopeIdentity(userId, tenantId, workspaceId, membershipId)

    val canRead: Boolean
        get() = permissions.any { it in FULFILLMENT_READ_PERMISSIONS }

    val canPick: Boolean
        get() = permissions.any { it in FULFILLMENT_WRITE_PERMISSIONS }

    override fun toString(): String =
        "PickingAuthority(scope=REDACTED, permissions=${permissions.size}, epoch=$authorityEpoch)"

    private companion object {
        val FULFILLMENT_READ_PERMISSIONS = setOf("fulfillment.read", "fulfillment:read")
        val FULFILLMENT_WRITE_PERMISSIONS = setOf("fulfillment.manage", "warehouse:write")
    }
}

/** Scope key for harmless local command metadata. It never stores permission authority. */

data class PickingScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "PickingScopeIdentity(REDACTED)"
}

/** Application projection that pairs fulfillment facts with BC-05's published allocation read. */
data class PickingFulfillmentSnapshot(
    val fulfillment: FulfillmentPickingSnapshot,
    val allocation: PhysicalAllocationProjection
) {
    fun offers(): List<PickingOffer> = allocation.lines.map { allocated ->
        val matching = fulfillment.lines.filter { line ->
            line.skuId == allocated.skuId &&
                (line.catalogItemId == null || line.catalogItemId == allocated.catalogItemId) &&
                line.unit.equals(allocated.unit, ignoreCase = true)
        }
        PickingOffer(
            fulfillmentLine = matching.singleOrNull(),
            allocationLine = allocated,
            ambiguousFulfillmentMatch = matching.size != 1
        )
    }
}

data class PickingOffer(
    val fulfillmentLine: FulfillmentPickingLine?,
    val allocationLine: PhysicalAllocationLineProjection,
    val ambiguousFulfillmentMatch: Boolean
) {
    val isPickable: Boolean
        get() = !ambiguousFulfillmentMatch &&
            fulfillmentLine != null &&
            allocationLine.remainingQuantity.signum() > 0 &&
            fulfillmentLine.remainingQuantity.signum() > 0 &&
            allocationLine.unit.equals(fulfillmentLine.unit, ignoreCase = true)
}

data class PickingConfirmationCommand(
    val fulfillmentId: String,
    val expectedFulfillmentVersion: Long,
    val allocationVersion: Long,
    val fulfillmentLineId: String,
    val skuId: String,
    val physicalAllocationLineId: String,
    val lotId: String,
    val warehouseId: String,
    val quantity: BigDecimal,
    val unit: String
) {
    override fun toString(): String =
        "PickingConfirmationCommand(fulfillmentId=REDACTED, versions=$expectedFulfillmentVersion/$allocationVersion, quantity=$quantity, unit=$unit)"
}

sealed interface PickingIntentCommand {
    data class Start(val fulfillmentId: String, val expectedFulfillmentVersion: Long) :
        PickingIntentCommand

    data class Confirm(val request: PickingConfirmationCommand) : PickingIntentCommand
}

enum class PickingIntentMetadataStatus { Pending, UnknownOutcome }

/** Persist only scope-bound frozen command identity. Never persist server stock facts or tokens. */

data class PickingIntentMetadata(
    val scope: PickingScopeIdentity,
    val idempotencyKey: String,
    val command: PickingIntentCommand,
    val status: PickingIntentMetadataStatus
) {
    override fun toString(): String = "PickingIntentMetadata(status=$status, key=REDACTED)"
}

sealed interface PickingMetadataRead<out T> {
    data class Available<T>(val value: T?) : PickingMetadataRead<T>
    data object Unavailable : PickingMetadataRead<Nothing>
}

enum class PickingMetadataWrite { Saved, Unavailable }

sealed interface PickingLoadResult {
    data class Loaded(val snapshot: PickingFulfillmentSnapshot) : PickingLoadResult
    data class AllocationUnavailable(val fulfillment: FulfillmentPickingSnapshot) :
        PickingLoadResult
    data object NotFound : PickingLoadResult
    data object NetworkUnavailable : PickingLoadResult
    data object ServiceUnavailable : PickingLoadResult
    data object PermissionDenied : PickingLoadResult
    data object ContextInvalidated : PickingLoadResult
    data object SessionInvalidated : PickingLoadResult
}

sealed interface PickingMutationResult {
    data class Confirmed(val fulfillment: FulfillmentPickingSnapshot) : PickingMutationResult
    data class Rejected(val code: String?) : PickingMutationResult
    data object StaleVersion : PickingMutationResult
    data object UnknownOutcome : PickingMutationResult
    data object PermissionDenied : PickingMutationResult
    data object ContextInvalidated : PickingMutationResult
    data object SessionInvalidated : PickingMutationResult
    data object ServiceUnavailable : PickingMutationResult
}
