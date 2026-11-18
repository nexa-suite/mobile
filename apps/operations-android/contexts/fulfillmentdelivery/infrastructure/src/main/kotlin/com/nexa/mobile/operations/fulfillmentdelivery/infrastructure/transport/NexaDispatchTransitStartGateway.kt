package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

import com.nexa.mobile.operations.core.network.ClientFailure
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartConfirmation
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val DELIVERIES_PATH = "/api/v1/deliveries"
private val dispatchTransitJson = Json { ignoreUnknownKeys = true }

sealed interface DispatchTransitStartNetworkOutcome {
    data class Started(val value: DispatchTransitStartConfirmation) :
        DispatchTransitStartNetworkOutcome
    data object UnknownOutcome : DispatchTransitStartNetworkOutcome
    data object NetworkUnavailable : DispatchTransitStartNetworkOutcome
    data object ServiceUnavailable : DispatchTransitStartNetworkOutcome
    data object PermissionDenied : DispatchTransitStartNetworkOutcome
    data object Stale : DispatchTransitStartNetworkOutcome
    data object Conflict : DispatchTransitStartNetworkOutcome
    data object ContextInvalidated : DispatchTransitStartNetworkOutcome
    data object SessionInvalidated : DispatchTransitStartNetworkOutcome
}

/** Sends only the explicit server-owned transition from DISPATCHED to IN_TRANSIT. */
class NexaDispatchTransitStartGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun startTransit(
        command: DispatchTransitStartCommand,
        replayUnknown: Boolean
    ): DispatchTransitStartNetworkOutcome {
        val expectedVersion = command.expectedVersion
            ?: return DispatchTransitStartNetworkOutcome.ServiceUnavailable
        if (!command.isValid()) return DispatchTransitStartNetworkOutcome.ServiceUnavailable

        val result = protectedCalls.execute(
            ProtectedRequest(
                method = ProtectedMethod.POST,
                path = "$DELIVERIES_PATH/${encode(command.deliveryId)}/transit-starts",
                payload = null,
                idempotencyKey = command.idempotencyKey,
                ifMatch = command.deliveryEtag
            )
        )
        if (result is ProtectedResult.Failure) {
            return result.error.toTransitOutcome()
        }
        result as ProtectedResult.Success
        if (result.status != 200) return DispatchTransitStartNetworkOutcome.UnknownOutcome

        val wire = result.body.decode<DeliveryTransitionWire>()
            ?: return DispatchTransitStartNetworkOutcome.UnknownOutcome
        val deliveryId = wire.id.requiredText()?.takeIf { it.equals(command.deliveryId, true) }
            ?: return DispatchTransitStartNetworkOutcome.UnknownOutcome
        val fulfillmentId = wire.fulfillmentId.requiredText()
            ?.takeIf { it.equals(command.fulfillmentId, true) }
            ?: return DispatchTransitStartNetworkOutcome.UnknownOutcome
        val status = wire.status.requiredText()
            ?.takeIf { value ->
                if (replayUnknown) {
                    value in REPLAY_TRANSIT_DELIVERY_STATUSES
                } else {
                    value == "IN_TRANSIT"
                }
            }
            ?: return DispatchTransitStartNetworkOutcome.UnknownOutcome
        val version = wire.version?.takeIf { it >= 0 }
            ?: return DispatchTransitStartNetworkOutcome.UnknownOutcome
        val etag = result.etag?.takeIf { it.version() == version }
            ?: return DispatchTransitStartNetworkOutcome.UnknownOutcome
        if (version <= expectedVersion || (!replayUnknown && version != expectedVersion + 1)) {
            return DispatchTransitStartNetworkOutcome.UnknownOutcome
        }
        return DispatchTransitStartNetworkOutcome.Started(
            DispatchTransitStartConfirmation(
                deliveryId = deliveryId,
                fulfillmentId = fulfillmentId,
                deliveryStatus = status,
                deliveryVersion = version,
                deliveryEtag = etag
            )
        )
    }

    private fun ClientFailure.toTransitOutcome(): DispatchTransitStartNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired ->
            DispatchTransitStartNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            DispatchTransitStartNetworkOutcome.ContextInvalidated

        httpStatus == 403 || kind == FailureKind.AuthorizationFailure ||
            httpStatus == 404 || kind == FailureKind.ResourceUnavailable ->
            DispatchTransitStartNetworkOutcome.PermissionDenied

        httpStatus == 412 || httpStatus == 428 || kind == FailureKind.StaleState ||
            kind == FailureKind.PreconditionRequired -> DispatchTransitStartNetworkOutcome.Stale

        problemCode == IDEMPOTENCY_PAYLOAD_CONFLICT || httpStatus == 409 ||
            kind == FailureKind.BusinessConflict -> DispatchTransitStartNetworkOutcome.Conflict

        kind == FailureKind.UnknownOutcome || kind == FailureKind.NetworkUnavailable ||
            kind == FailureKind.Timeout -> DispatchTransitStartNetworkOutcome.UnknownOutcome

        else -> DispatchTransitStartNetworkOutcome.ServiceUnavailable
    }

    private fun String?.version(): Long? {
        val value = this?.trim()?.removeSurrounding("\"") ?: return null
        if (value.isEmpty() || value.any { !it.isDigit() }) return null
        return value.toLongOrNull()?.takeIf { it >= 0 }
    }

    private fun String?.requiredText(): String? = this?.takeIf(String::isNotBlank)

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private inline fun <reified T> String?.decode(): T? = try {
        this?.let { dispatchTransitJson.decodeFromString<T>(it) }
    } catch (_: SerializationException) {
        null
    }

    private companion object {
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
        const val IDEMPOTENCY_PAYLOAD_CONFLICT = "IDEMPOTENCY_PAYLOAD_CONFLICT"
        val REPLAY_TRANSIT_DELIVERY_STATUSES = setOf(
            "IN_TRANSIT",
            "PARTIAL",
            "DELIVERED",
            "FAILED",
            "CANCELLED"
        )
    }
}

@Serializable
private data class DeliveryTransitionWire(
    val id: String? = null,
    val fulfillmentId: String? = null,
    val status: String? = null,
    val version: Long? = null
)
