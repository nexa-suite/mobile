package com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch

data class DispatchHandoffIdentity(
    val handoffId: String,
    val deliveryId: String,
    val assignmentId: String,
    val deliveryVersion: Long,
    val expiresAt: String,
    val status: String
) {
    override fun toString(): String =
        "DispatchHandoffIdentity(delivery=REDACTED, assignment=REDACTED, status=$status)"
}
