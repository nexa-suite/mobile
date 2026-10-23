package com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery

import java.math.BigDecimal

data class DriverDeliveryAttempt(
    val id: String,
    val attemptNumber: Int,
    val status: String,
    val startedByMembershipId: String?,
    val startedAt: String?
) {
    override fun toString(): String = "DriverDeliveryAttempt(number=$attemptNumber, status=$status)"
}

/** Server projection; assignment eligibility and current attempt remain server-owned. */

data class DriverDeliverySnapshot(
    val id: String,
    val fulfillmentId: String?,
    val salesOrderId: String?,
    val status: String,
    val destination: String?,
    val scheduledAt: String?,
    val dispatchedAt: String?,
    val deliveredAt: String?,
    val updatedAt: String?,
    val version: Long,
    val activeAttempt: DriverDeliveryAttempt?,
    val outcomeLines: List<DriverDeliveryOutcomeLine> = emptyList(),
    val arrival: DriverDeliveryArrivalFact? = null,
    val destinationCoordinates: DriverDeliveryCoordinates? = null
) {
    override fun toString(): String =
        "DriverDeliverySnapshot(status=$status, version=$version, active=$activeAttempt)"
}

data class DriverDeliveryCoordinates(val latitude: BigDecimal, val longitude: BigDecimal) {
    init {
        require(latitude >= BigDecimal.valueOf(-90) && latitude <= BigDecimal.valueOf(90))
        require(longitude >= BigDecimal.valueOf(-180) && longitude <= BigDecimal.valueOf(180))
    }
}

data class DriverDeliveryArrivalFact(val id: String, val attemptId: String, val arrivedAt: String)

data class DriverDeliveryOutcomeLine(
    val fulfillmentLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val dispatchedQuantity: BigDecimal,
    val deliveredQuantity: BigDecimal,
    val rejectedQuantity: BigDecimal,
    val cancelledQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val unit: String
)

data class DriverRemainingQuantityLine(
    val fulfillmentLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val quantity: BigDecimal,
    val unit: String
)

enum class DriverOutcomeKind { DELIVERED, PARTIAL, FAILED, REFUSED, ABSENT }

data class DriverOutcomeLineDecision(
    val fulfillmentLineId: String,
    val skuId: String,
    val attemptedQuantity: BigDecimal,
    val deliveredQuantity: BigDecimal,
    val rejectedQuantity: BigDecimal,
    val cancelledQuantity: BigDecimal,
    val unit: String
)

data class DriverOutcomeSummary(
    val attemptId: String,
    val outcome: String,
    val attemptedAt: String,
    val deliveryVersion: Long,
    val partial: Boolean,
    val remainingLines: List<DriverRemainingQuantityLine>
)

data class DriverArrivalSummary(
    val eventId: String,
    val deliveryId: String,
    val attemptId: String,
    val arrivedAt: String,
    val deliveryVersion: Long
)
