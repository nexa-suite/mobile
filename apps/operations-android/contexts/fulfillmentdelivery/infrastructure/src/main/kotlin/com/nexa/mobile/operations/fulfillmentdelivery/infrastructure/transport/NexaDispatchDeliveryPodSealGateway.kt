package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

import com.nexa.mobile.operations.core.network.ClientFailure
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val DELIVERIES_PATH = "/api/v1/deliveries"
private val deliveryPodSealJson = Json { ignoreUnknownKeys = true }
private val deliveryPodSealUuidPattern =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** Sends the coordinator-owned POD seal command through the protected Delivery transition. */
class NexaDispatchDeliveryPodSealGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun sealPod(
        deliveryId: String,
        deliveryEtag: String,
        idempotencyKey: String
    ): DispatchDeliveryPodNetworkOutcome {
        val expectedVersion = deliveryEtag.version()
            ?: return DispatchDeliveryPodNetworkOutcome.ServiceUnavailable
        if (!deliveryId.isUuid() || idempotencyKey.isBlank() || idempotencyKey.length > 160) {
            return DispatchDeliveryPodNetworkOutcome.ServiceUnavailable
        }
        val result = protectedCalls.execute(
            ProtectedRequest(
                method = ProtectedMethod.POST,
                path = "$DELIVERIES_PATH/${encode(deliveryId)}/pod/seals",
                payload = "{}",
                idempotencyKey = idempotencyKey,
                ifMatch = deliveryEtag
            )
        )
        if (result is ProtectedResult.Failure) return result.error.toPodOutcome(mutation = true)
        result as ProtectedResult.Success
        if (result.status != 200) return DispatchDeliveryPodNetworkOutcome.UnknownOutcome
        val wire = result.body.decode<DeliveryPodSealWire>()
            ?: return DispatchDeliveryPodNetworkOutcome.UnknownOutcome
        val pod = wire.toProjection(result.etag)
            ?: return DispatchDeliveryPodNetworkOutcome.UnknownOutcome
        if (!pod.deliveryId.equals(deliveryId, true) || pod.status != "SEALED" ||
            pod.deliveryVersion != expectedVersion + 1 ||
            pod.deliveryEtag.version() != pod.deliveryVersion
        ) {
            return DispatchDeliveryPodNetworkOutcome.UnknownOutcome
        }
        return DispatchDeliveryPodNetworkOutcome.Sealed(pod)
    }

    private fun DeliveryPodSealWire.toProjection(etag: String?): DispatchPodEvidenceProjection? {
        val id = this.id.requiredText()?.takeIf { it.isUuid() } ?: return null
        val deliveryId = this.deliveryId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val status = this.status.requiredText() ?: return null
        val version = this.deliveryVersion?.takeIf { it >= 0 } ?: return null
        val deliveryEtag = etag?.takeIf { it.version() == version } ?: return null
        val capturedAt = this.capturedAt?.requiredText()?.parseInstant()
        val sealedAt = this.sealedAt?.requiredText()?.parseInstant()
        if ((this.capturedAt != null && capturedAt == null) ||
            (this.sealedAt != null && sealedAt == null)
        ) {
            return null
        }
        return DispatchPodEvidenceProjection(
            id = id,
            deliveryId = deliveryId,
            status = status,
            receiverName = receiverName?.takeIf(String::isNotBlank),
            capturedAt = capturedAt,
            sealedAt = sealedAt,
            deliveryVersion = version,
            deliveryEtag = deliveryEtag
        )
    }

    private fun ClientFailure.toPodOutcome(
        mutation: Boolean = false
    ): DispatchDeliveryPodNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired ->
            DispatchDeliveryPodNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == "ACCESS_CONTEXT_INVALID" ->
            DispatchDeliveryPodNetworkOutcome.ContextInvalidated

        httpStatus == 403 || kind == FailureKind.AuthorizationFailure ->
            DispatchDeliveryPodNetworkOutcome.PermissionDenied

        httpStatus == 404 || kind == FailureKind.ResourceUnavailable ->
            DispatchDeliveryPodNetworkOutcome.PermissionDenied

        httpStatus == 412 || httpStatus == 428 || kind == FailureKind.StaleState ||
            kind == FailureKind.PreconditionRequired -> DispatchDeliveryPodNetworkOutcome.Stale

        problemCode == "IDEMPOTENCY_PAYLOAD_CONFLICT" || httpStatus == 409 ||
            kind == FailureKind.BusinessConflict -> DispatchDeliveryPodNetworkOutcome.Conflict

        mutation && (
            kind == FailureKind.UnknownOutcome || kind == FailureKind.NetworkUnavailable ||
                kind == FailureKind.Timeout
            ) -> DispatchDeliveryPodNetworkOutcome.UnknownOutcome

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            DispatchDeliveryPodNetworkOutcome.NetworkUnavailable

        else -> DispatchDeliveryPodNetworkOutcome.ServiceUnavailable
    }

    private fun String?.version(): Long? {
        val value = this?.trim()?.removeSurrounding("\"") ?: return null
        if (value.isEmpty() || value.any { !it.isDigit() }) return null
        return value.toLongOrNull()?.takeIf { it >= 0 }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun String.parseInstant(): Instant? = try {
        Instant.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun String.isUuid(): Boolean = deliveryPodSealUuidPattern.matches(this)

    private fun String?.requiredText(): String? = this?.takeIf(String::isNotBlank)

    private inline fun <reified T> String?.decode(): T? = try {
        this?.let { deliveryPodSealJson.decodeFromString<T>(it) }
    } catch (_: SerializationException) {
        null
    }
}

@Serializable
private data class DeliveryPodSealWire(
    val id: String? = null,
    val deliveryId: String? = null,
    val status: String? = null,
    val receiverName: String? = null,
    val capturedAt: String? = null,
    val sealedAt: String? = null,
    val deliveryVersion: Long? = null
)
