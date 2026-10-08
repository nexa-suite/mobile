package com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch

import java.math.BigDecimal
import java.time.Instant

data class DispatchTemperatureEvidence(
    val id: String,
    val fulfillmentId: String,
    val fulfillmentVersion: Long,
    val lotId: String,
    val valueCelsius: BigDecimal,
    val occurredAt: Instant,
    val actorMembershipId: String,
    val status: String,
    val evidenceObjectId: String? = null,
    val expectedLotVersion: Long? = null,
    val resultingLotVersion: Long? = null,
    val inventoryTemperatureEvaluationId: String? = null,
    val inventoryLotStatus: String? = null,
    val affectedQuantity: BigDecimal? = null
) {
    override fun toString(): String = "DispatchTemperatureEvidence(REDACTED, status=$status)"
}

data class DispatchTemperatureLot(
    val skuId: String,
    val lotId: String?,
    val warehouseId: String?,
    val zoneId: String?,
    val skuColdChainRequired: Boolean,
    val requiredForFulfillment: Boolean,
    val minimumCelsius: BigDecimal?,
    val maximumCelsius: BigDecimal?,
    val status: String,
    val latestEvidence: DispatchTemperatureEvidence?,
    val version: Long? = null
) {
    val supportsInRangeEvidence: Boolean
        get() = skuColdChainRequired && (minimumCelsius != null || maximumCelsius != null) &&
            lotId != null && warehouseId != null && zoneId != null && version != null

    fun isWithinRange(value: BigDecimal): Boolean =
        (minimumCelsius == null || value >= minimumCelsius) &&
            (maximumCelsius == null || value <= maximumCelsius)

    override fun toString(): String = "DispatchTemperatureLot(REDACTED, status=$status)"
}

data class DispatchTemperatureReadiness(
    val fulfillmentId: String,
    val fulfillmentStatus: String,
    val fulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val temperatureRequiredForFulfillment: Boolean,
    val asOf: Instant,
    val lots: List<DispatchTemperatureLot>
) {
    override fun toString(): String = "DispatchTemperatureReadiness(REDACTED, " +
        "version=$fulfillmentVersion, lots=${lots.size})"
}

data class DispatchTemperaturePhotoEvidence(
    val id: String,
    val subjectType: String,
    val subjectId: String,
    val lifecycleStatus: String
) {
    override fun toString(): String = "DispatchTemperaturePhotoEvidence(status=$lifecycleStatus)"
}
