package com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery

data class DriverHandoffCurrentDelivery(
    val deliveryId: String,
    val status: String,
    val version: Long,
    val activeAttemptId: String?
)

data class DriverHandoffTokenReceipt(
    val handoffId: String,
    val deliveryId: String,
    val attemptId: String,
    val expiresAt: String,
    val status: String
) {
    override fun toString(): String = "DriverHandoffTokenReceipt(status=$status, token=REDACTED)"
}
