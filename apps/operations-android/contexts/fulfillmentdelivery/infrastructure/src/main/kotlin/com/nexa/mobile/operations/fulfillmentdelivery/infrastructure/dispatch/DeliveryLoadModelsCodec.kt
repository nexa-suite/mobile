package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DeliveryLoadCompatibilityAttestation
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.MAX_LOAD_STOPS
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.isValidOpaqueIdentifier
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun deliveryLoadCreationBody(
    readiness: List<DispatchReadiness>,
    stopOrder: List<String>,
    reason: String,
    attestation: DeliveryLoadCompatibilityAttestation
): String {
    require(readiness.size in 2..MAX_LOAD_STOPS && attestation.complete)
    require(
        readiness.all {
            it.windowStart != null && it.windowEnd != null &&
                it.windowSource in setOf("COMMERCIAL", "DISPATCH_PLAN")
        }
    )
    val normalizedReason = reason.trim()
    require(normalizedReason.isNotEmpty() && normalizedReason.length <= 500)
    val byId = readiness.associateBy(DispatchReadiness::fulfillmentId)
    require(stopOrder.size == readiness.size && stopOrder.toSet() == byId.keys)
    val versions =
        JsonObject(byId.mapValues { (_, item) -> JsonPrimitive(item.fulfillmentVersion) })
    return buildJsonObject {
        put("fulfillmentIds", JsonArray(readiness.map { JsonPrimitive(it.fulfillmentId) }))
        put("stopOrder", JsonArray(stopOrder.map { JsonPrimitive(it) }))
        put("expectedFulfillmentVersions", versions)
        put("reason", normalizedReason)
        put(
            "compatibilityAttestation",
            buildJsonObject {
                put("capacitySufficient", attestation.capacitySufficient)
                put("handlingCompatible", attestation.handlingCompatible)
                put("zoneReasonable", attestation.zoneReasonable)
                put("noExclusiveTransportRestriction", attestation.noExclusiveTransportRestriction)
                attestation.observation?.trim()?.let { put("observation", it) }
            }
        )
    }.toString()
}

fun dispatchWindowPlanBody(windowStart: String, windowEnd: String, reason: String): String {
    val start = Instant.parse(windowStart.trim())
    val end = Instant.parse(windowEnd.trim())
    val normalizedReason = reason.trim()
    require(start.isBefore(end) && normalizedReason.isNotEmpty() && normalizedReason.length <= 2000)
    return buildJsonObject {
        put("windowStart", start.toString())
        put("windowEnd", end.toString())
        put("reason", normalizedReason)
    }.toString()
}

fun deliveryLoadAssignBody(driverMembershipId: String): String {
    require(isValidOpaqueIdentifier(driverMembershipId))
    return buildJsonObject { put("driverMembershipId", driverMembershipId) }.toString()
}

fun deliveryLoadReorderBody(stopOrder: List<String>, reason: String): String {
    val normalizedReason = reason.trim()
    require(stopOrder.size in 2..MAX_LOAD_STOPS && stopOrder.all(::isValidOpaqueIdentifier))
    require(stopOrder.distinct().size == stopOrder.size)
    require(normalizedReason.isNotEmpty() && normalizedReason.length <= 500)
    return buildJsonObject {
        put("stopOrder", JsonArray(stopOrder.map { JsonPrimitive(it) }))
        put("reason", normalizedReason)
    }.toString()
}
