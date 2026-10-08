package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DRIVER_WARNING_RESOLUTION_MAX_CHARS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val DRIVER_OPERATIONAL_EXCEPTION_EMPTY_BODY = "{}"



fun driverDeliveryOperationalExceptionResolutionBody(value: String): String {
    val normalized = value.trim()
    require(normalized.isNotEmpty() && normalized.length <= DRIVER_WARNING_RESOLUTION_MAX_CHARS)
    return JsonObject(mapOf("resolution" to JsonPrimitive(normalized))).toString()
}



fun driverDeliveryOperationalExceptionResolutionFromBody(body: String): String? = try {
    val root = Json.parseToJsonElement(body).jsonObject
    val value = root["resolution"]?.jsonPrimitive
    if (root.keys != setOf("resolution") || value == null || !value.isString) {
        null
    } else {
        value.content.trim().takeIf {
            it.isNotEmpty() &&
                it.length <= DRIVER_WARNING_RESOLUTION_MAX_CHARS
        }
    }
} catch (_: Exception) {
    null
}



const val DRIVER_OPERATIONAL_EXCEPTION_BODYLESS = ""



fun driverDeliveryOperationalExceptionEmptyBody(): String = DRIVER_OPERATIONAL_EXCEPTION_EMPTY_BODY
