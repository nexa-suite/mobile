package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureDisposition
import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun driverExecutionTemperatureReadingBody(
    fulfillmentLineId: String,
    skuId: String,
    affectedQuantity: BigDecimal,
    valueCelsius: BigDecimal,
    occurredAt: Instant,
    sourceIncidentId: String?,
    evidenceObjectId: String?
): String {
    val source = sourceIncidentId?.let { ",\"sourceIncidentId\":\"$it\"" }.orEmpty()
    val evidence = evidenceObjectId?.let { ",\"evidenceObjectId\":\"$it\"" }.orEmpty()
    return "{\"fulfillmentLineId\":\"$fulfillmentLineId\",\"skuId\":\"$skuId\"," +
        "\"affectedQuantity\":${affectedQuantity.stripTrailingZeros().toPlainString()}," +
        "\"value\":${valueCelsius.stripTrailingZeros().toPlainString()},\"unit\":\"CELSIUS\"," +
        "\"occurredAt\":\"$occurredAt\"$source$evidence}"
}

fun driverExecutionTemperatureDispositionBody(
    disposition: DriverExecutionTemperatureDisposition,
    reason: String
): String = JsonObject(
    linkedMapOf(
        "disposition" to JsonPrimitive(disposition.name),
        "reason" to JsonPrimitive(reason.trim())
    )
).toString()
