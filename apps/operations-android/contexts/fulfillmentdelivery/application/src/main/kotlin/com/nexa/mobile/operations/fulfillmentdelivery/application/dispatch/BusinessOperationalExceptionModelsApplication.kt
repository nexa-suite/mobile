package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.BusinessOperationalException
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.BusinessOperationalExceptionActor
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.BusinessOperationalExceptionsSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.isValidOpaqueIdentifier

data class BusinessOperationalExceptionScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    init {
        require(
            listOf(userId, tenantId, workspaceId, membershipId).all(::isValidOpaqueIdentifier)
        )
    }
    override fun toString(): String = "BusinessOperationalExceptionScopeIdentity(REDACTED)"
}



data class BusinessOperationalExceptionAuthority(
    val authorityEpoch: Long,
    val scope: BusinessOperationalExceptionScopeIdentity?,
    val permissions: Set<String>
) {
    val canRead: Boolean get() = authorityEpoch > 0 && scope != null &&
        READ_PERMISSION in permissions
    val canCoordinate: Boolean get() = canRead && COORDINATE_PERMISSION in permissions

    override fun toString(): String =
        "BusinessOperationalExceptionAuthority(epoch=$authorityEpoch, permissions=${permissions.size})"

    companion object {
        const val READ_PERMISSION = "delivery.exception.read"
        const val COORDINATE_PERMISSION = "delivery.exception.coordinate"
    }
}



enum class BusinessOperationalExceptionAction { CLAIM, REASSIGN, FOLLOW_UP, RESOLVE, CLOSE }



enum class BusinessOperationalExceptionIntentStatus { Pending, UnknownOutcome }

/** Exact frozen request. The encrypted record supports recovery; it never supplies authority. */


data class BusinessOperationalExceptionCommand(
    val scope: BusinessOperationalExceptionScopeIdentity,
    val action: BusinessOperationalExceptionAction,
    val exceptionId: String,
    val expectedDeliveryVersion: Long,
    val idempotencyKey: String,
    val frozenBody: String,
    val status: BusinessOperationalExceptionIntentStatus
) {
    init {
        require(isValidOpaqueIdentifier(exceptionId))
        require(expectedDeliveryVersion >= 0)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(frozenBody.length <= 8_000)
    }

    override fun toString(): String =
        "BusinessOperationalExceptionCommand(action=$action, version=$expectedDeliveryVersion, key=REDACTED, body=REDACTED)"
}



data class BusinessOperationalExceptionIntent(val command: BusinessOperationalExceptionCommand) {
    override fun toString(): String = "BusinessOperationalExceptionIntent(command=REDACTED)"
}



sealed interface BusinessOperationalExceptionMetadataRead {
    data class Available(val intent: BusinessOperationalExceptionIntent?) :
        BusinessOperationalExceptionMetadataRead
    data object Unavailable : BusinessOperationalExceptionMetadataRead
}



enum class BusinessOperationalExceptionMetadataWrite { Saved, Conflict, Unavailable }



sealed interface BusinessOperationalExceptionsGatewayResult {
    data class Current(val snapshot: BusinessOperationalExceptionsSnapshot) :
        BusinessOperationalExceptionsGatewayResult
    data class Changed(
        val exception: BusinessOperationalException,
        val deliveryVersion: Long,
        val replayed: Boolean
    ) : BusinessOperationalExceptionsGatewayResult
    data class Failed(val code: String?, val unknownOutcome: Boolean = false) :
        BusinessOperationalExceptionsGatewayResult
}



sealed interface BusinessOperationalExceptionAssigneesResult {
    data class Loaded(val values: List<BusinessOperationalExceptionActor>) :
        BusinessOperationalExceptionAssigneesResult
    data class Failed(val code: String?) : BusinessOperationalExceptionAssigneesResult
}
