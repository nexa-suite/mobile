package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverHandoffCurrentDelivery
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverHandoffTokenReceipt

data class DriverHandoffIssueCommand(
    val deliveryId: String,
    val attemptId: String,
    val expectedVersion: Long,
    val idempotencyKey: String,
    val frozenBody: String
) {
    init {
        require(deliveryId.isNotBlank() && attemptId.isNotBlank())
        require(expectedVersion >= 0)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
    }

    override fun toString(): String =
        "DriverHandoffIssueCommand(version=$expectedVersion, key=REDACTED)"
}



sealed interface DriverHandoffCurrentDeliveryResult {
    data class Loaded(val delivery: DriverHandoffCurrentDelivery) :
        DriverHandoffCurrentDeliveryResult
    data object NotFound : DriverHandoffCurrentDeliveryResult
    data object Unavailable : DriverHandoffCurrentDeliveryResult
    data object PermissionDenied : DriverHandoffCurrentDeliveryResult
    data object ContextInvalidated : DriverHandoffCurrentDeliveryResult
    data object SessionInvalidated : DriverHandoffCurrentDeliveryResult
}



sealed interface DriverHandoffIssueResult {
    data class Issued(val receipt: DriverHandoffTokenReceipt, val token: String) :
        DriverHandoffIssueResult
    data class TokenUnavailable(val receipt: DriverHandoffTokenReceipt) : DriverHandoffIssueResult
    data class Rejected(val code: String?) : DriverHandoffIssueResult
    data object NotFound : DriverHandoffIssueResult
    data object UnknownOutcome : DriverHandoffIssueResult
    data object Unavailable : DriverHandoffIssueResult
    data object PermissionDenied : DriverHandoffIssueResult
    data object ContextInvalidated : DriverHandoffIssueResult
    data object SessionInvalidated : DriverHandoffIssueResult
}



sealed interface DriverHandoffMetadataRead {
    data class Available(val command: DriverHandoffIssueCommand?) : DriverHandoffMetadataRead
    data object Unavailable : DriverHandoffMetadataRead
}



enum class DriverHandoffMetadataWrite { Saved, Conflict, Stale, Unavailable }
