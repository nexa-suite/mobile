package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.isValidOpaqueIdentifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun driverDeliveryInstructionAcknowledgementBody(instructionIds: Collection<String>): String {
    val ids = instructionIds.map(String::lowercase).distinct().sorted()
    require(ids.isNotEmpty() && ids.all(::isValidOpaqueIdentifier))
    return JsonObject(
        mapOf("instructionIds" to JsonArray(ids.map(::JsonPrimitive)))
    ).toString()
}
