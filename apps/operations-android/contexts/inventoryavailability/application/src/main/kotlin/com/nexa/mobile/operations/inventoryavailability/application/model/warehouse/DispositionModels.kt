package com.nexa.mobile.operations.inventoryavailability.application.model.warehouse

import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.DispositionLotFacts
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotDispositionAction
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotDispositionCommand

data class DispositionAuthority(
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

    val scope: DispositionScopeIdentity
        get() = DispositionScopeIdentity(userId, tenantId, workspaceId, membershipId)

    val canReadLots: Boolean
        get() = permissions.any { it in LOT_READ_PERMISSIONS }

    fun canRecord(disposition: LotDispositionAction): Boolean = when (disposition) {
        LotDispositionAction.RELEASE -> "inventory.release" in permissions

        LotDispositionAction.HOLD,
        LotDispositionAction.WASTE,
        LotDispositionAction.RETURN_TO_SUPPLIER -> "inventory.waste" in permissions
    }

    override fun toString(): String =
        "DispositionAuthority(scope=REDACTED, permissions=${permissions.size})"

    private companion object {
        val LOT_READ_PERMISSIONS = setOf("inventory.read", "warehouse.read", "warehouse:read")
    }
}

data class DispositionScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    override fun toString(): String = "DispositionScopeIdentity(REDACTED)"
}

sealed interface DispositionGatewayResult {
    data class Lot(val facts: DispositionLotFacts) : DispositionGatewayResult
    data class Confirmed(val facts: DispositionLotFacts) : DispositionGatewayResult
    data class Rejected(val code: String?) : DispositionGatewayResult
    data object UnknownOutcome : DispositionGatewayResult
    data object PreconditionFailed : DispositionGatewayResult
    data object Conflict : DispositionGatewayResult
    data object NetworkUnavailable : DispositionGatewayResult
    data object ServiceUnavailable : DispositionGatewayResult
    data object PermissionDenied : DispositionGatewayResult
    data object ContextInvalidated : DispositionGatewayResult
    data object SessionInvalidated : DispositionGatewayResult
}

data class DispositionDraftMetadata(
    val lotIdText: String,
    val disposition: LotDispositionAction?,
    val reason: String
) {
    override fun toString(): String = "DispositionDraftMetadata(values=REDACTED)"
}

enum class DispositionIntentMetadataStatus {
    Pending,
    UnknownOutcome,
    PreconditionFailed,
    Conflict,
    Rejected
}

/** Persists only the exact scope-bound command and stable key, never its result or authority. */

data class DispositionIntentMetadata(
    val scope: DispositionScopeIdentity,
    val idempotencyKey: String,
    val command: LotDispositionCommand,
    val status: DispositionIntentMetadataStatus
) {
    override fun toString(): String = "DispositionIntentMetadata(status=$status, key=REDACTED)"
}

sealed interface DispositionMetadataRead<out T> {
    data class Available<T>(val value: T?) : DispositionMetadataRead<T>
    data object Unavailable : DispositionMetadataRead<Nothing>
}

enum class DispositionMetadataWrite { Saved, Unavailable }
