package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DeliveryLoad
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DeliveryLoadDriverCandidate
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.isValidOpaqueIdentifier

data class DeliveryLoadScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    init {
        require(listOf(userId, tenantId, workspaceId, membershipId).all(::isValidOpaqueIdentifier))
    }
    override fun toString(): String = "DeliveryLoadScopeIdentity(REDACTED)"
}



enum class DeliveryLoadCommandAction {
    CREATE,
    REORDER,
    ASSIGN,
    OFFER,
    CONFIRM_HANDOFF,
    ACCEPT,
    PLAN_WINDOW
}



enum class DeliveryLoadCommandIntentStatus {
    Pending,
    UnknownOutcome
}



data class DeliveryLoadCommand(
    val scope: DeliveryLoadScopeIdentity,
    val driverCommand: Boolean,
    val action: DeliveryLoadCommandAction,
    val loadId: String?,
    val expectedVersion: Long?,
    val idempotencyKey: String,
    val frozenBody: String?,
    val status: DeliveryLoadCommandIntentStatus
) {
    init {
        require(
            if (action == DeliveryLoadCommandAction.CREATE) {
                loadId == null && expectedVersion == null
            } else {
                loadId != null && expectedVersion != null
            }
        )
        require(loadId == null || isValidOpaqueIdentifier(loadId))
        require(expectedVersion == null || expectedVersion >= 0)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(driverCommand == (action == DeliveryLoadCommandAction.ACCEPT))
        require(
            when (action) {
                DeliveryLoadCommandAction.CREATE,
                DeliveryLoadCommandAction.REORDER,
                DeliveryLoadCommandAction.ASSIGN,
                DeliveryLoadCommandAction.PLAN_WINDOW -> !frozenBody.isNullOrBlank()

                DeliveryLoadCommandAction.OFFER,
                DeliveryLoadCommandAction.CONFIRM_HANDOFF,
                DeliveryLoadCommandAction.ACCEPT -> frozenBody == null
            }
        )
    }

    override fun toString(): String =
        "DeliveryLoadCommand(action=$action, version=$expectedVersion, key=REDACTED, body=REDACTED)"
}



sealed interface DeliveryLoadCommandMetadataRead {
    data class Available(val command: DeliveryLoadCommand?) : DeliveryLoadCommandMetadataRead
    data object Unavailable : DeliveryLoadCommandMetadataRead
}



enum class DeliveryLoadCommandMetadataWrite {
    Saved,
    Conflict,
    Stale,
    Unavailable
}



sealed interface DeliveryLoadGatewayResult {
    data class Loaded(
        val loads: List<DeliveryLoad>,
        val readyCandidates: List<DispatchReadiness>,
        val drivers: List<DeliveryLoadDriverCandidate>
    ) : DeliveryLoadGatewayResult

    data class Changed(val load: DeliveryLoad) : DeliveryLoadGatewayResult
    data class WindowPlanned(val fulfillmentId: String) : DeliveryLoadGatewayResult
    data class Failed(val code: String?, val unknownOutcome: Boolean = false) :
        DeliveryLoadGatewayResult
}
