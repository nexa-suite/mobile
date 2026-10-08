package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverIncidentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun driverIncidentBody(
    type: DriverIncidentType,
    reason: String,
    description: String,
    place: String
): String = JsonObject(
    linkedMapOf(
        "type" to JsonPrimitive(type.name),
        "reason" to JsonPrimitive(reason),
        "description" to JsonPrimitive(description),
        "place" to JsonPrimitive(place)
    )
).toString()

/** Retains the immutable request shape of reports created before typed source classification. */


fun driverIncidentLegacyBody(reason: String, description: String, place: String): String =

    JsonObject(
        linkedMapOf(
            "reason" to JsonPrimitive(reason),
            "description" to JsonPrimitive(description),
            "place" to JsonPrimitive(place)
        )
    ).toString()
