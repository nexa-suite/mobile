package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchDeliveryInstructionKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

fun dispatchDeliveryInstructionRequestBody(
    instructionId: String?,
    kind: DispatchDeliveryInstructionKind,
    content: String
): String = buildJsonObject {
    if (instructionId != null) put("instructionId", JsonPrimitive(instructionId))
    put("kind", JsonPrimitive(kind.name))
    put("content", JsonPrimitive(content))
}.toString()
