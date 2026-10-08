package com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery

import java.math.BigDecimal
import java.time.Instant

enum class DriverExecutionTemperatureMode {
    DRIVER,
    HOLD_DISPOSITION
}

enum class DriverExecutionTemperatureDisposition {
    RELEASE,
    CONTINUE_HOLD,
    REJECT,
    WASTE
}

data class DriverExecutionTemperatureLine(
    val fulfillmentLineId: String,
    val skuId: String,
    val unit: String,
    val remainingQuantity: BigDecimal,
    val coldChainRequired: Boolean,
    val minimumCelsius: BigDecimal?,
    val maximumCelsius: BigDecimal?
) {
    init {
        require(
            isValidOpaqueIdentifier(fulfillmentLineId) &&
                isValidOpaqueIdentifier(skuId)
        )
        require(unit.isNotBlank() && remainingQuantity.signum() >= 0)
        require(
            minimumCelsius == null || maximumCelsius == null || minimumCelsius <= maximumCelsius
        )
    }

    val supportsReading: Boolean
        get() = coldChainRequired && minimumCelsius != null && maximumCelsius != null &&
            remainingQuantity.signum() > 0

    fun isWithinRange(value: BigDecimal): Boolean =
        (minimumCelsius == null || value >= minimumCelsius) &&
            (maximumCelsius == null || value <= maximumCelsius)
}

data class DriverExecutionTemperatureHold(
    val id: String,
    val readingId: String,
    val exceptionId: String,
    val fulfillmentLineId: String,
    val skuId: String,
    val affectedQuantity: BigDecimal,
    val quantityUnit: String,
    val status: String,
    val reportedByMembershipId: String,
    val reportedAt: Instant,
    val disposition: String?,
    val authorizedByMembershipId: String?,
    val disposedAt: Instant?,
    val reason: String?
) {
    init {
        require(
            listOf(id, readingId, exceptionId, fulfillmentLineId, skuId, reportedByMembershipId)
                .all(::isValidOpaqueIdentifier)
        )
        require(affectedQuantity.signum() > 0 && quantityUnit.isNotBlank() && status.isNotBlank())
        require(
            disposition == null ||
                disposition in DriverExecutionTemperatureDisposition.entries.map { it.name }
        )
        require(
            authorizedByMembershipId == null ||
                isValidOpaqueIdentifier(authorizedByMembershipId)
        )
    }
}

data class DriverExecutionTemperatureSnapshot(
    val deliveryId: String,
    val deliveryVersion: Long,
    val deliveryStatus: String,
    val attemptId: String?,
    val originWarehouseId: String?,
    val lines: List<DriverExecutionTemperatureLine>,
    val holds: List<DriverExecutionTemperatureHold>
) {
    init {
        require(
            isValidOpaqueIdentifier(deliveryId) && deliveryVersion >= 0 &&
                deliveryStatus.isNotBlank()
        )
        require(attemptId == null || isValidOpaqueIdentifier(attemptId))
        require(originWarehouseId == null || isValidOpaqueIdentifier(originWarehouseId))
        require(lines.map { it.fulfillmentLineId.lowercase() }.distinct().size == lines.size)
        require(holds.map { it.id.lowercase() }.distinct().size == holds.size)
    }
}

data class DriverExecutionTemperatureReading(
    val id: String,
    val deliveryId: String,
    val attemptId: String?,
    val fulfillmentLineId: String,
    val skuId: String,
    val affectedQuantity: BigDecimal,
    val quantityUnit: String,
    val valueCelsius: BigDecimal,
    val temperatureUnit: String,
    val minimumCelsius: BigDecimal?,
    val maximumCelsius: BigDecimal?,
    val status: String,
    val actorMembershipId: String,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val evidenceObjectId: String?,
    val sourceIncidentId: String?,
    val hold: DriverExecutionTemperatureHold?,
    val deliveryVersion: Long,
    val replayed: Boolean
)
