package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun driverHandoffIssueBody(attemptId: String): String =

    JsonObject(mapOf("attemptId" to JsonPrimitive(attemptId))).toString()
