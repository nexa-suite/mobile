package com.nexa.mobile.operations.core.network

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val FULFILLMENT_DISPATCH_BASE = "/api/v1/fulfillments"
private val fulfillmentDispatchJson = Json { ignoreUnknownKeys = true }
private val fulfillmentDispatchUuid =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class FulfillmentDispatchProjection(
    val fulfillmentId: String,
    val status: String,
    val version: Long,
    val updatedAt: Instant,
    val deliveryId: String?,
    val deliveryStatus: String?,
    val deliveryVersion: Long?
)

data class FulfillmentHandoffEvidenceProjection(
    val id: String,
    val fulfillmentId: String,
    val fulfillmentVersion: Long,
    val deliveryId: String,
    val warehouseActorMembershipId: String,
    val driverAssignmentId: String,
    val driverMembershipId: String,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val outgoingGoodsCheckId: String,
    val occurredAt: Instant,
    val current: Boolean
)

sealed interface FulfillmentHandoffEvidenceNetworkOutcome {
    data class Evidence(val value: FulfillmentHandoffEvidenceProjection) :
        FulfillmentHandoffEvidenceNetworkOutcome
    data object NetworkUnavailable : FulfillmentHandoffEvidenceNetworkOutcome
    data object ServiceUnavailable : FulfillmentHandoffEvidenceNetworkOutcome
    data object PermissionDenied : FulfillmentHandoffEvidenceNetworkOutcome
    data object ContextInvalidated : FulfillmentHandoffEvidenceNetworkOutcome
    data object SessionInvalidated : FulfillmentHandoffEvidenceNetworkOutcome
}

sealed interface FulfillmentDispatchNetworkOutcome {
    data class Current(val fulfillment: FulfillmentDispatchProjection) :
        FulfillmentDispatchNetworkOutcome
    data class Dispatched(val fulfillment: FulfillmentDispatchProjection) :
        FulfillmentDispatchNetworkOutcome
    data object NetworkUnavailable : FulfillmentDispatchNetworkOutcome
    data object UnknownOutcome : FulfillmentDispatchNetworkOutcome
    data object ServiceUnavailable : FulfillmentDispatchNetworkOutcome
    data object PermissionDenied : FulfillmentDispatchNetworkOutcome
    data object ContextInvalidated : FulfillmentDispatchNetworkOutcome
    data object SessionInvalidated : FulfillmentDispatchNetworkOutcome
    data object Stale : FulfillmentDispatchNetworkOutcome
    data object Conflict : FulfillmentDispatchNetworkOutcome
}

/** Connected, versioned transition from prepared fulfillment to the real Delivery. */
class NexaFulfillmentDispatchGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun current(fulfillmentId: String): FulfillmentDispatchNetworkOutcome {
        if (!fulfillmentId.isUuid()) return FulfillmentDispatchNetworkOutcome.ServiceUnavailable
        val encodedId = URLEncoder.encode(fulfillmentId, StandardCharsets.UTF_8.name())
        val result = protectedCalls.execute(
            ProtectedRequest(ProtectedMethod.GET, "$FULFILLMENT_DISPATCH_BASE/$encodedId")
        )
        return when (result) {
            is ProtectedResult.Failure -> result.error.toDispatchOutcome()

            is ProtectedResult.Success -> {
                if (result.status !=
                    200
                ) {
                    return FulfillmentDispatchNetworkOutcome.ServiceUnavailable
                }
                val value = result.body.decode<FulfillmentDispatchWire>()?.toProjection()
                    ?: return FulfillmentDispatchNetworkOutcome.ServiceUnavailable
                if (!value.fulfillmentId.equals(fulfillmentId, ignoreCase = true) ||
                    result.etag.toVersion() != value.version
                ) {
                    FulfillmentDispatchNetworkOutcome.ServiceUnavailable
                } else {
                    FulfillmentDispatchNetworkOutcome.Current(value)
                }
            }
        }
    }

    suspend fun dispatch(
        fulfillmentId: String,
        expectedFulfillmentVersion: Long,
        idempotencyKey: String,
        exactRequestBody: String
    ): FulfillmentDispatchNetworkOutcome {
        if (!fulfillmentId.isUuid() || expectedFulfillmentVersion < 0 ||
            idempotencyKey.isBlank() || idempotencyKey.length > 160 ||
            exactRequestBody.isBlank() || !exactRequestBody.trim().startsWith("{") ||
            !exactRequestBody.trim().endsWith("}")
        ) {
            return FulfillmentDispatchNetworkOutcome.ServiceUnavailable
        }
        val encodedId = URLEncoder.encode(fulfillmentId, StandardCharsets.UTF_8.name())
        val result = protectedCalls.execute(
            ProtectedRequest(
                method = ProtectedMethod.POST,
                path = "$FULFILLMENT_DISPATCH_BASE/$encodedId/dispatches",
                payload = exactRequestBody,
                idempotencyKey = idempotencyKey,
                ifMatch = "\"$expectedFulfillmentVersion\""
            )
        )
        return when (result) {
            is ProtectedResult.Failure -> result.error.toDispatchOutcome()

            is ProtectedResult.Success -> {
                if (result.status != 200 && result.status != 201) {
                    return FulfillmentDispatchNetworkOutcome.ServiceUnavailable
                }
                val value = result.body.decode<FulfillmentDispatchWire>()?.toProjection()
                    ?: return FulfillmentDispatchNetworkOutcome.UnknownOutcome
                if (!value.fulfillmentId.equals(fulfillmentId, ignoreCase = true) ||
                    value.status != HANDED_OVER || value.version < expectedFulfillmentVersion + 1 ||
                    value.deliveryId == null || value.deliveryStatus == null ||
                    value.deliveryVersion == null ||
                    result.etag.toVersion() != value.version
                ) {
                    FulfillmentDispatchNetworkOutcome.UnknownOutcome
                } else {
                    FulfillmentDispatchNetworkOutcome.Dispatched(value)
                }
            }
        }
    }

    suspend fun currentHandoffEvidence(
        fulfillmentId: String
    ): FulfillmentHandoffEvidenceNetworkOutcome {
        if (!fulfillmentId.isUuid()) {
            return FulfillmentHandoffEvidenceNetworkOutcome.ServiceUnavailable
        }
        val encodedId = URLEncoder.encode(fulfillmentId, StandardCharsets.UTF_8.name())
        val result = protectedCalls.execute(
            ProtectedRequest(
                ProtectedMethod.GET,
                "$FULFILLMENT_DISPATCH_BASE/$encodedId/handoff-evidence/current"
            )
        )
        return when (result) {
            is ProtectedResult.Failure -> result.error.toHandoffEvidenceOutcome()

            is ProtectedResult.Success -> {
                if (result.status !=
                    200
                ) {
                    return FulfillmentHandoffEvidenceNetworkOutcome.ServiceUnavailable
                }
                val value = result.body.decode<HandoffEvidenceWire>()?.toProjection()
                    ?: return FulfillmentHandoffEvidenceNetworkOutcome.ServiceUnavailable
                if (!value.fulfillmentId.equals(fulfillmentId, ignoreCase = true) ||
                    result.etag.toVersion() != value.fulfillmentVersion
                ) {
                    FulfillmentHandoffEvidenceNetworkOutcome.ServiceUnavailable
                } else {
                    FulfillmentHandoffEvidenceNetworkOutcome.Evidence(value)
                }
            }
        }
    }

    private fun FulfillmentDispatchWire.toProjection(): FulfillmentDispatchProjection? {
        val id = id?.takeIf { it.isUuid() } ?: return null
        val status = status?.takeIf(String::isNotBlank) ?: return null
        val version = version?.takeIf { it >= 0 } ?: return null
        val updated =
            updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val delivery = deliveryId?.takeIf { it.isUuid() }
        val deliveryState = deliveryStatus?.takeIf(String::isNotBlank)
        val deliveryVersion = deliveryVersion?.takeIf { it >= 0 }
        if (status == HANDED_OVER &&
            (delivery == null || deliveryState == null || deliveryVersion == null)
        ) {
            return null
        }
        if (status != HANDED_OVER &&
            (delivery != null || deliveryState != null || deliveryVersion != null)
        ) {
            return null
        }
        return FulfillmentDispatchProjection(
            id,
            status,
            version,
            updated,
            delivery,
            deliveryState,
            deliveryVersion
        )
    }

    private fun ClientFailure.toDispatchOutcome(): FulfillmentDispatchNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired ->
            FulfillmentDispatchNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == "ACCESS_CONTEXT_INVALID" ->
            FulfillmentDispatchNetworkOutcome.ContextInvalidated

        httpStatus == 403 || httpStatus == 404 || kind == FailureKind.AuthorizationFailure ||
            kind == FailureKind.ResourceUnavailable ->
            FulfillmentDispatchNetworkOutcome.PermissionDenied

        httpStatus == 412 || httpStatus == 428 || kind == FailureKind.StaleState ||
            kind == FailureKind.PreconditionRequired -> FulfillmentDispatchNetworkOutcome.Stale

        httpStatus == 409 || kind == FailureKind.BusinessConflict ->
            FulfillmentDispatchNetworkOutcome.Conflict

        kind == FailureKind.UnknownOutcome -> FulfillmentDispatchNetworkOutcome.UnknownOutcome

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            FulfillmentDispatchNetworkOutcome.NetworkUnavailable

        else -> FulfillmentDispatchNetworkOutcome.ServiceUnavailable
    }

    private fun ClientFailure.toHandoffEvidenceOutcome(): FulfillmentHandoffEvidenceNetworkOutcome =
        when {
            kind == FailureKind.AuthenticationRequired ->
                FulfillmentHandoffEvidenceNetworkOutcome.SessionInvalidated

            httpStatus == 403 && problemCode == "ACCESS_CONTEXT_INVALID" ->
                FulfillmentHandoffEvidenceNetworkOutcome.ContextInvalidated

            httpStatus == 403 || httpStatus == 404 || kind == FailureKind.AuthorizationFailure ||
                kind == FailureKind.ResourceUnavailable ->
                FulfillmentHandoffEvidenceNetworkOutcome.PermissionDenied

            kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
                FulfillmentHandoffEvidenceNetworkOutcome.NetworkUnavailable

            else -> FulfillmentHandoffEvidenceNetworkOutcome.ServiceUnavailable
        }

    private fun String.isUuid(): Boolean = fulfillmentDispatchUuid.matches(this)

    private fun HandoffEvidenceWire.toProjection(): FulfillmentHandoffEvidenceProjection? {
        val evidenceId = id?.takeIf { it.isUuid() } ?: return null
        val workId = fulfillmentId?.takeIf { it.isUuid() } ?: return null
        val workVersion = fulfillmentVersion?.takeIf { it >= 0 } ?: return null
        val delivery = deliveryId?.takeIf { it.isUuid() } ?: return null
        val warehouseActor = warehouseActorMembershipId?.takeIf { it.isUuid() } ?: return null
        val assignment = driverAssignmentId?.takeIf { it.isUuid() } ?: return null
        val driver = driverMembershipId?.takeIf { it.isUuid() } ?: return null
        val allocation = physicalAllocationId?.takeIf { it.isUuid() } ?: return null
        val allocationVersion = physicalAllocationVersion?.takeIf { it >= 0 } ?: return null
        val check = outgoingGoodsCheckId?.takeIf { it.isUuid() } ?: return null
        val occurred =
            occurredAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val isCurrent = current ?: return null
        return FulfillmentHandoffEvidenceProjection(
            evidenceId, workId, workVersion, delivery, warehouseActor,
            assignment, driver, allocation, allocationVersion, check, occurred, isCurrent
        )
    }

    private fun String?.toVersion(): Long? = this?.removeSurrounding("\"")?.toLongOrNull()?.takeIf {
        it >=
            0
    }

    private inline fun <reified T> String?.decode(): T? = try {
        this?.let { fulfillmentDispatchJson.decodeFromString<T>(it) }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val HANDED_OVER = "HANDED_OVER"
    }
}

@Serializable
private data class FulfillmentDispatchWire(
    val id: String? = null,
    val status: String? = null,
    val version: Long? = null,
    val updatedAt: String? = null,
    val deliveryId: String? = null,
    val deliveryStatus: String? = null,
    val deliveryVersion: Long? = null
)

@Serializable
private data class HandoffEvidenceWire(
    val id: String? = null,
    val fulfillmentId: String? = null,
    val fulfillmentVersion: Long? = null,
    val deliveryId: String? = null,
    val warehouseActorMembershipId: String? = null,
    val driverAssignmentId: String? = null,
    val driverMembershipId: String? = null,
    val physicalAllocationId: String? = null,
    val physicalAllocationVersion: Long? = null,
    val outgoingGoodsCheckId: String? = null,
    val occurredAt: String? = null,
    val current: Boolean? = null
)
