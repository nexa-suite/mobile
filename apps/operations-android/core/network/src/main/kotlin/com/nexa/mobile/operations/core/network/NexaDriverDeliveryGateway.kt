package com.nexa.mobile.operations.core.network

import com.nexa.mobile.operations.core.network.FailureKind
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val DRIVER_DELIVERIES_PATH = "/api/v1/driver/deliveries"
private val driverDeliveryJson = Json { ignoreUnknownKeys = true }
private val driverUuidPattern =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class DriverDeliveryAttemptProjection(
    val id: String,
    val attemptNumber: Int,
    val status: String,
    val startedByMembershipId: String?,
    val startedAt: String?
)

data class DriverDeliveryOutcomeLineProjection(
    val fulfillmentLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val dispatchedQuantity: BigDecimal,
    val deliveredQuantity: BigDecimal,
    val rejectedQuantity: BigDecimal,
    val cancelledQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val unit: String
)

data class DriverRemainingQuantityLineProjection(
    val fulfillmentLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val quantity: BigDecimal,
    val unit: String
)

data class DriverDeliveryOutcomeProjection(
    val attemptId: String,
    val deliveryId: String,
    val deliveryVersion: Long,
    val outcome: String,
    val attemptedAt: String,
    val partial: Boolean,
    val remainingLines: List<DriverRemainingQuantityLineProjection>
)

data class DriverDeliveryArrivalProjection(
    val id: String,
    val deliveryId: String,
    val attemptId: String,
    val actorMembershipId: String,
    val arrivedAt: String,
    val deliveryVersion: Long,
    val replayed: Boolean
)

data class DriverDeliveryArrivalFactProjection(
    val id: String,
    val attemptId: String,
    val arrivedAt: String
)

data class DriverDeliveryProjection(
    val id: String,
    val fulfillmentId: String?,
    val salesOrderId: String?,
    val status: String,
    val destinationSnapshot: String?,
    val scheduledAt: String?,
    val dispatchedAt: String?,
    val deliveredAt: String?,
    val updatedAt: String?,
    val version: Long,
    val activeAttempt: DriverDeliveryAttemptProjection?,
    val outcomeLines: List<DriverDeliveryOutcomeLineProjection> = emptyList(),
    val arrival: DriverDeliveryArrivalFactProjection? = null
)

sealed interface DriverDeliveryNetworkOutcome {
    data class Assigned(val items: List<DriverDeliveryProjection>) : DriverDeliveryNetworkOutcome
    data class Detail(val item: DriverDeliveryProjection) : DriverDeliveryNetworkOutcome
    data class Started(
        val delivery: DriverDeliveryProjection,
        val attempt: DriverDeliveryAttemptProjection
    ) : DriverDeliveryNetworkOutcome
    data class OutcomeRecorded(val value: DriverDeliveryOutcomeProjection) : DriverDeliveryNetworkOutcome
    data class ArrivalRecorded(val value: DriverDeliveryArrivalProjection) : DriverDeliveryNetworkOutcome
    data class Rejected(val code: String?) : DriverDeliveryNetworkOutcome
    data object NotFound : DriverDeliveryNetworkOutcome
    data object StaleVersion : DriverDeliveryNetworkOutcome
    data object UnknownOutcome : DriverDeliveryNetworkOutcome
    data object NetworkUnavailable : DriverDeliveryNetworkOutcome
    data object ServiceUnavailable : DriverDeliveryNetworkOutcome
    data object PermissionDenied : DriverDeliveryNetworkOutcome
    data object ContextInvalidated : DriverDeliveryNetworkOutcome
    data object SessionInvalidated : DriverDeliveryNetworkOutcome
}

/** Protected transport for current-driver assigned deliveries and active attempt start. */
class NexaDriverDeliveryGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun assignedDeliveries(): DriverDeliveryNetworkOutcome {
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(ProtectedMethod.GET, DRIVER_DELIVERIES_PATH)
            )
        ) {
            is ProtectedResult.Failure -> result.error.toReadOutcome()

            is ProtectedResult.Success -> {
                val items = result.body.toDeliveryList()
                    ?: return DriverDeliveryNetworkOutcome.ServiceUnavailable
                if (items.map { it.id.lowercase() }.distinct().size != items.size) {
                    DriverDeliveryNetworkOutcome.ServiceUnavailable
                } else {
                    DriverDeliveryNetworkOutcome.Assigned(items)
                }
            }
        }
    }

    suspend fun delivery(deliveryId: String): DriverDeliveryNetworkOutcome {
        if (!driverUuidPattern.matches(
                deliveryId
            )
        ) {
            return DriverDeliveryNetworkOutcome.ServiceUnavailable
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(ProtectedMethod.GET, "$DRIVER_DELIVERIES_PATH/$deliveryId")
            )
        ) {
            is ProtectedResult.Failure -> result.error.toReadOutcome()

            is ProtectedResult.Success -> {
                val projection = result.body.toDeliveryProjection()
                    ?: return DriverDeliveryNetworkOutcome.ServiceUnavailable
                if (projection.id != deliveryId || result.etag.toVersion() != projection.version) {
                    DriverDeliveryNetworkOutcome.ServiceUnavailable
                } else {
                    DriverDeliveryNetworkOutcome.Detail(projection)
                }
            }
        }
    }

    suspend fun startAttempt(
        deliveryId: String,
        expectedVersion: Long,
        idempotencyKey: String
    ): DriverDeliveryNetworkOutcome {
        if (!driverUuidPattern.matches(deliveryId) || expectedVersion < 0 ||
            idempotencyKey.isBlank() ||
            idempotencyKey.length > 160
        ) {
            return DriverDeliveryNetworkOutcome.ServiceUnavailable
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = "$DRIVER_DELIVERIES_PATH/$deliveryId/attempts",
                    payload = "{}",
                    idempotencyKey = idempotencyKey,
                    ifMatch = "\"$expectedVersion\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toMutationOutcome()

            is ProtectedResult.Success -> {
                if (result.status !in
                    setOf(200, 201)
                ) {
                    return DriverDeliveryNetworkOutcome.UnknownOutcome
                }
                val root =
                    result.body.toObject() ?: return DriverDeliveryNetworkOutcome.UnknownOutcome
                val delivery = (root["delivery"] as? JsonObject)?.toProjection()
                    ?: return DriverDeliveryNetworkOutcome.UnknownOutcome
                val attempt = (root["attempt"] as? JsonObject)?.toAttempt()
                    ?: return DriverDeliveryNetworkOutcome.UnknownOutcome
                val responseVersion = result.etag.toVersion()
                if (delivery.id != deliveryId || responseVersion != delivery.version ||
                    delivery.version <= expectedVersion || attempt.status != "ACTIVE" ||
                    delivery.activeAttempt?.id != attempt.id
                ) {
                    DriverDeliveryNetworkOutcome.UnknownOutcome
                } else {
                    DriverDeliveryNetworkOutcome.Started(delivery, attempt)
                }
            }
        }
    }

    suspend fun recordOutcome(
        deliveryId: String,
        attemptId: String,
        expectedVersion: Long,
        idempotencyKey: String,
        frozenBody: String
    ): DriverDeliveryNetworkOutcome {
        if (!driverUuidPattern.matches(deliveryId) || !driverUuidPattern.matches(attemptId) ||
            expectedVersion < 0 || idempotencyKey.isBlank() || idempotencyKey.length > 160 ||
            frozenBody.isBlank()
        ) {
            return DriverDeliveryNetworkOutcome.ServiceUnavailable
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = "$DRIVER_DELIVERIES_PATH/$deliveryId/attempts/$attemptId/outcomes",
                    payload = frozenBody,
                    idempotencyKey = idempotencyKey,
                    ifMatch = "\"$expectedVersion\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toMutationOutcome()

            is ProtectedResult.Success -> {
                if (result.status !in setOf(200, 201)) {
                    return DriverDeliveryNetworkOutcome.UnknownOutcome
                }
                val projection = result.body.toOutcomeProjection()
                    ?: return DriverDeliveryNetworkOutcome.UnknownOutcome
                if (projection.deliveryId != deliveryId || projection.attemptId != attemptId ||
                    projection.deliveryVersion <= expectedVersion ||
                    result.etag.toVersion() != projection.deliveryVersion
                ) {
                    DriverDeliveryNetworkOutcome.UnknownOutcome
                } else {
                    DriverDeliveryNetworkOutcome.OutcomeRecorded(projection)
                }
            }
        }
    }

    suspend fun signalArrival(
        deliveryId: String,
        attemptId: String,
        expectedVersion: Long,
        idempotencyKey: String,
        frozenBody: String
    ): DriverDeliveryNetworkOutcome {
        if (!driverUuidPattern.matches(deliveryId) || !driverUuidPattern.matches(attemptId) ||
            expectedVersion < 0 || idempotencyKey.isBlank() || idempotencyKey.length > 160 ||
            frozenBody != "{}"
        ) {
            return DriverDeliveryNetworkOutcome.ServiceUnavailable
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = "$DRIVER_DELIVERIES_PATH/$deliveryId/attempts/$attemptId/arrivals",
                    payload = frozenBody,
                    idempotencyKey = idempotencyKey,
                    ifMatch = "\"$expectedVersion\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toMutationOutcome()
            is ProtectedResult.Success -> {
                if (result.status !in setOf(200, 201)) {
                    return DriverDeliveryNetworkOutcome.UnknownOutcome
                }
                val arrival = result.body.toArrivalProjection()
                    ?: return DriverDeliveryNetworkOutcome.UnknownOutcome
                val replayStatus = result.status == 200
                if (arrival.deliveryId != deliveryId || arrival.attemptId != attemptId ||
                    arrival.deliveryVersion < expectedVersion ||
                    (!replayStatus && arrival.deliveryVersion <= expectedVersion) ||
                    arrival.replayed != replayStatus || result.etag.toVersion() != arrival.deliveryVersion
                ) {
                    DriverDeliveryNetworkOutcome.UnknownOutcome
                } else {
                    DriverDeliveryNetworkOutcome.ArrivalRecorded(arrival)
                }
            }
        }
    }

    private fun String?.toDeliveryList(): List<DriverDeliveryProjection>? = try {
        val elements = this?.let(driverDeliveryJson::parseToJsonElement)?.jsonArray ?: return null
        elements.map { (it as? JsonObject)?.toProjection() ?: return null }
    } catch (_: Exception) {
        null
    }

    private fun String?.toDeliveryProjection(): DriverDeliveryProjection? =
        this.toObject()?.toProjection()

    private fun String?.toObject(): JsonObject? = try {
        this?.let(driverDeliveryJson::parseToJsonElement)?.jsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toProjection(): DriverDeliveryProjection? = try {
        val id = requiredText("id")?.takeIf(driverUuidPattern::matches) ?: return null
        val fulfillmentId = optionalText("fulfillmentId")
            ?.takeIf(driverUuidPattern::matches)
            ?: if (this["fulfillmentId"] == null ||
                this["fulfillmentId"] == JsonNull
            ) {
                null
            } else {
                return null
            }
        val salesOrderId = optionalText("salesOrderId")
            ?.takeIf(driverUuidPattern::matches)
            ?: if (this["salesOrderId"] == null ||
                this["salesOrderId"] == JsonNull
            ) {
                null
            } else {
                return null
            }
        val status = requiredText("status") ?: return null
        val destination = optionalText("destinationSnapshot")
            ?: if (this["destinationSnapshot"] == null ||
                this["destinationSnapshot"] == JsonNull
            ) {
                null
            } else {
                return null
            }
        val scheduled = optionalText("scheduledAt")
            ?: if (this["scheduledAt"] == null ||
                this["scheduledAt"] == JsonNull
            ) {
                null
            } else {
                return null
            }
        val dispatched = optionalText("dispatchedAt")
            ?: if (this["dispatchedAt"] == null ||
                this["dispatchedAt"] == JsonNull
            ) {
                null
            } else {
                return null
            }
        val delivered = optionalText("deliveredAt")
            ?: if (this["deliveredAt"] == null ||
                this["deliveredAt"] == JsonNull
            ) {
                null
            } else {
                return null
            }
        val updated = optionalText("updatedAt")
            ?: if (this["updatedAt"] == null || this["updatedAt"] == JsonNull) null else return null
        val version = requiredLong("version")?.takeIf { it >= 0 } ?: return null
        val attemptElement = this["activeAttempt"]
        val activeAttempt = when (attemptElement) {
            null, JsonNull -> null

            is JsonObject -> attemptElement.toAttempt()?.takeIf { it.status == "ACTIVE" }
                ?: return null

            else -> return null
        }
        val outcomeLineElement = this["outcomeLines"]
        val outcomeLines = when (outcomeLineElement) {
            null, JsonNull -> emptyList()
            else -> outcomeLineElement.jsonArray.map { (it as? JsonObject)?.toOutcomeLine() ?: return null }
        }
        val arrivalElement = this["arrival"]
        val arrival = when (arrivalElement) {
            null, JsonNull -> null
            is JsonObject -> arrivalElement.toArrivalFact() ?: return null
            else -> return null
        }
        if (arrival != null && activeAttempt?.id != arrival.attemptId) return null
        DriverDeliveryProjection(
            id, fulfillmentId, salesOrderId, status, destination, scheduled, dispatched,
            delivered, updated, version, activeAttempt, outcomeLines, arrival
        )
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toAttempt(): DriverDeliveryAttemptProjection? = try {
        val id = requiredText("id")?.takeIf(driverUuidPattern::matches) ?: return null
        val number =
            requiredLong("attemptNumber")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: return null
        val status = requiredText("status") ?: return null
        val startedBy = optionalText("startedByMembershipId")
            ?.takeIf(driverUuidPattern::matches)
            ?: if (this["startedByMembershipId"] == null ||
                this["startedByMembershipId"] == JsonNull
            ) {
                null
            } else {
                return null
            }
        val startedAt = optionalText("startedAt")
            ?: if (this["startedAt"] == null || this["startedAt"] == JsonNull) null else return null
        DriverDeliveryAttemptProjection(id, number, status, startedBy, startedAt)
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toOutcomeLine(): DriverDeliveryOutcomeLineProjection? = try {
        val lineId = requiredText("fulfillmentLineId")?.takeIf(driverUuidPattern::matches) ?: return null
        val skuId = requiredText("skuId")?.takeIf(driverUuidPattern::matches) ?: return null
        val catalogItemId = requiredText("catalogItemId") ?: return null
        val dispatched = requiredDecimal("dispatchedQuantity")?.takeIf { it.signum() >= 0 } ?: return null
        val delivered = requiredDecimal("deliveredQuantity")?.takeIf { it.signum() >= 0 } ?: return null
        val rejected = requiredDecimal("rejectedQuantity")?.takeIf { it.signum() >= 0 } ?: return null
        val cancelled = requiredDecimal("cancelledQuantity")?.takeIf { it.signum() >= 0 } ?: return null
        val remaining = requiredDecimal("remainingQuantity")?.takeIf { it.signum() >= 0 } ?: return null
        val unit = requiredText("unit") ?: return null
        DriverDeliveryOutcomeLineProjection(
            lineId, skuId, catalogItemId, dispatched, delivered, rejected, cancelled, remaining, unit
        )
    } catch (_: Exception) {
        null
    }

    private fun String?.toOutcomeProjection(): DriverDeliveryOutcomeProjection? = try {
        val root = this.toObject() ?: return null
        val attemptId = root.requiredText("attemptId")?.takeIf(driverUuidPattern::matches) ?: return null
        val delivery = root["delivery"]?.jsonObject ?: return null
        val deliveryId = delivery.requiredText("id")?.takeIf(driverUuidPattern::matches) ?: return null
        val version = delivery.requiredLong("version")?.takeIf { it >= 0 } ?: return null
        val attempts = delivery["attempts"]?.jsonArray ?: return null
        val attempt = attempts.mapNotNull { it as? JsonObject }
            .firstOrNull { it.requiredText("id") == attemptId } ?: return null
        val outcome = attempt.requiredText("outcome") ?: return null
        val attemptedAt = attempt.requiredText("attemptedAt") ?: return null
        val partial = root["partial"]?.jsonPrimitive?.booleanOrNull ?: return null
        val remaining = root["remainingLines"]?.jsonArray?.map { element ->
            val line = element as? JsonObject ?: return null
            DriverRemainingQuantityLineProjection(
                line.requiredText("fulfillmentLineId")?.takeIf(driverUuidPattern::matches) ?: return null,
                line.requiredText("skuId")?.takeIf(driverUuidPattern::matches) ?: return null,
                line.requiredText("catalogItemId") ?: return null,
                line.requiredDecimal("quantity")?.takeIf { it.signum() >= 0 } ?: return null,
                line.requiredText("unit") ?: return null
            )
        } ?: return null
        DriverDeliveryOutcomeProjection(
            attemptId, deliveryId, version, outcome, attemptedAt, partial, remaining
        )
    } catch (_: Exception) {
        null
    }

    private fun String?.toArrivalProjection(): DriverDeliveryArrivalProjection? = try {
        val root = this.toObject() ?: return null
        val id = root.requiredText("id")?.takeIf(driverUuidPattern::matches) ?: return null
        val deliveryId = root.requiredText("deliveryId")?.takeIf(driverUuidPattern::matches) ?: return null
        val attemptId = root.requiredText("attemptId")?.takeIf(driverUuidPattern::matches) ?: return null
        val actorId = root.requiredText("actorMembershipId")?.takeIf(driverUuidPattern::matches) ?: return null
        val arrivedAt = root.requiredText("arrivedAt") ?: return null
        val version = root.requiredLong("deliveryVersion")?.takeIf { it >= 0 } ?: return null
        val replayed = root["replayed"]?.jsonPrimitive?.booleanOrNull ?: return null
        DriverDeliveryArrivalProjection(id, deliveryId, attemptId, actorId, arrivedAt, version, replayed)
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toArrivalFact(): DriverDeliveryArrivalFactProjection? = try {
        val id = requiredText("id")?.takeIf(driverUuidPattern::matches) ?: return null
        val attemptId = requiredText("attemptId")?.takeIf(driverUuidPattern::matches) ?: return null
        val arrivedAt = requiredText("arrivedAt") ?: return null
        DriverDeliveryArrivalFactProjection(id, attemptId, arrivedAt)
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.requiredText(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?.takeIf(String::isNotBlank)

    private fun JsonObject.optionalText(key: String): String? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.contentOrNull
        else -> null
    }

    private fun JsonObject.requiredLong(key: String): Long? =
        this[key]?.jsonPrimitive?.takeUnless(JsonPrimitive::isString)?.longOrNull

    private fun JsonObject.requiredDecimal(key: String): BigDecimal? =
        this[key]?.jsonPrimitive?.takeUnless(JsonPrimitive::isString)?.contentOrNull
            ?.let { runCatching { BigDecimal(it) }.getOrNull() }

    private fun String?.toVersion(): Long? = this?.trim()?.removePrefix("W/")?.trim()
        ?.removeSurrounding("\"")?.toLongOrNull()?.takeIf { it >= 0 }

    private fun ClientFailure.toReadOutcome(): DriverDeliveryNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired -> DriverDeliveryNetworkOutcome.SessionInvalidated
        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID -> DriverDeliveryNetworkOutcome.ContextInvalidated
        kind == FailureKind.AuthorizationFailure -> DriverDeliveryNetworkOutcome.PermissionDenied
        kind == FailureKind.ResourceUnavailable -> DriverDeliveryNetworkOutcome.NotFound
        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout -> DriverDeliveryNetworkOutcome.NetworkUnavailable
        else -> DriverDeliveryNetworkOutcome.ServiceUnavailable
    }

    private fun ClientFailure.toMutationOutcome(): DriverDeliveryNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired -> DriverDeliveryNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID -> DriverDeliveryNetworkOutcome.ContextInvalidated

        kind == FailureKind.AuthorizationFailure -> DriverDeliveryNetworkOutcome.PermissionDenied

        kind == FailureKind.StaleState -> DriverDeliveryNetworkOutcome.StaleVersion

        kind == FailureKind.ResourceUnavailable -> DriverDeliveryNetworkOutcome.NotFound

        kind == FailureKind.ValidationFailure || kind == FailureKind.BusinessConflict ||
            kind == FailureKind.PreconditionRequired -> DriverDeliveryNetworkOutcome.Rejected(
            problemCode
        )

        else -> DriverDeliveryNetworkOutcome.UnknownOutcome
    }

    private companion object {
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
    }
}
