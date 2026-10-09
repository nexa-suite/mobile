package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionAction
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.isValidOpaqueIdentifier
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun businessOperationalExceptionRequestBody(
    action: BusinessOperationalExceptionAction,
    responsibleMembershipId: String? = null,
    followUpNote: String? = null,
    reason: String? = null
): String {
    val values = when (action) {
        BusinessOperationalExceptionAction.CLAIM,
        BusinessOperationalExceptionAction.RESOLVE,
        BusinessOperationalExceptionAction.CLOSE -> {
            val normalizedReason = reason?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2_000 }
                ?: throw IllegalArgumentException("Reason is required")
            mapOf("reason" to JsonPrimitive(normalizedReason))
        }

        BusinessOperationalExceptionAction.FOLLOW_UP -> {
            val normalizedReason = reason?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2_000 }
                ?: throw IllegalArgumentException("Reason is required")
            val normalizedNote =
                followUpNote?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2_000 }
                    ?: throw IllegalArgumentException("Follow-up note is required")
            mapOf(
                "reason" to JsonPrimitive(normalizedReason),
                "note" to JsonPrimitive(normalizedNote)
            )
        }

        BusinessOperationalExceptionAction.REASSIGN -> {
            require(
                responsibleMembershipId != null &&
                    isValidOpaqueIdentifier(responsibleMembershipId)
            )
            val normalizedReason = reason?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2_000 }
                ?: throw IllegalArgumentException("Reason is required")
            mapOf(
                "responsibleMembershipId" to JsonPrimitive(responsibleMembershipId),
                "reason" to JsonPrimitive(normalizedReason)
            )
        }
    }
    return JsonObject(values).toString()
}
