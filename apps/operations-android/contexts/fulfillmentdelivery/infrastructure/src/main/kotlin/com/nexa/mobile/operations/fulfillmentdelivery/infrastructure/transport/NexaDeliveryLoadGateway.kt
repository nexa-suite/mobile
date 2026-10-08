package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DeliveryLoadStopTransport(
    val fulfillmentId: String,
    val deliveryId: String,
    val position: Int,
    val deliveryVersion: Long
)

@Serializable
data class DeliveryLoadAttestationTransport(
    val capacitySufficient: Boolean,
    val handlingCompatible: Boolean,
    val zoneReasonable: Boolean,
    val noExclusiveTransportRestriction: Boolean,
    val attestedByMembershipId: String,
    val attestedAt: String,
    val observation: String? = null
)

@Serializable
data class DeliveryLoadHistoryTransport(
    val eventType: String,
    val actorMembershipId: String,
    val occurredAt: String,
    val reason: String? = null,
    val previousStopOrder: List<String> = emptyList(),
    val newStopOrder: List<String> = emptyList()
)

@Serializable
data class DeliveryLoadTransport(
    val id: String,
    val version: Long,
    val status: String,
    val originWarehouseId: String,
    val stops: List<DeliveryLoadStopTransport>,
    val assignedDriverMembershipId: String? = null,
    val vehicleReference: String? = null,
    val compatibilityAttestation: DeliveryLoadAttestationTransport,
    val offeredByMembershipId: String? = null,
    val offeredAt: String? = null,
    val dispatchConfirmedByMembershipId: String? = null,
    val dispatchConfirmedAt: String? = null,
    val driverAcceptedByMembershipId: String? = null,
    val driverAcceptedAt: String? = null,
    val history: List<DeliveryLoadHistoryTransport> = emptyList()
)

@Serializable
data class DispatchWindowPlanTransport(
    val fulfillmentId: String,
    val fulfillmentVersion: Long,
    val revision: Int,
    val windowStart: String,
    val windowEnd: String,
    val reason: String,
    val recordedByMembershipId: String,
    val recordedAt: String,
    val replayed: Boolean
)
enum class DeliveryLoadNetworkAction {
    CREATE,
    REORDER,
    ASSIGN,
    OFFER,
    CONFIRM_HANDOFF,
    ACCEPT,
    PLAN_WINDOW
}
sealed interface DeliveryLoadNetworkResult {
    data class ListResult(val loads: List<DeliveryLoadTransport>) : DeliveryLoadNetworkResult
    data class Current(val load: DeliveryLoadTransport) : DeliveryLoadNetworkResult
    data class WindowPlanned(val plan: DispatchWindowPlanTransport) : DeliveryLoadNetworkResult
    data class Failed(val code: String?, val unknownOutcome: Boolean = false) :
        DeliveryLoadNetworkResult
}

/** Protected grouped-load transport. Caller owns the immutable mutation identity and payload. */
class NexaDeliveryLoadGateway(private val calls: ProtectedCallExecutor) {
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun list(driver: Boolean): DeliveryLoadNetworkResult = when (
        val result = calls.execute(
            ProtectedRequest(
                ProtectedMethod.GET,
                if (driver) "/api/v1/driver/loads" else "/api/v1/dispatch/loads"
            )
        )
    ) {
        is ProtectedResult.Failure -> DeliveryLoadNetworkResult.Failed(result.error.problemCode)

        is ProtectedResult.Success -> try {
            val values = json.decodeFromString<List<DeliveryLoadTransport>>(
                result.body ?: error("Missing list")
            )
            require(values.all(::valid) && values.map { it.id }.distinct().size == values.size)
            DeliveryLoadNetworkResult.ListResult(values)
        } catch (_: Exception) {
            DeliveryLoadNetworkResult.Failed("INVALID_RESPONSE")
        }
    }
    suspend fun mutate(
        driver: Boolean,
        loadId: String?,
        action: DeliveryLoadNetworkAction,
        version: Long?,
        key: String,
        frozenBody: String?
    ): DeliveryLoadNetworkResult {
        val creating = action == DeliveryLoadNetworkAction.CREATE
        if (key.isBlank() || key.length > 160 ||
            driver != (action == DeliveryLoadNetworkAction.ACCEPT) ||
            (creating && (loadId != null || version != null)) ||
            (
                !creating && (
                    loadId == null || !uuid(
                        loadId
                    ) || version == null || version < 0
                    )
                ) ||
            (
                action in
                    setOf(
                        DeliveryLoadNetworkAction.CREATE,
                        DeliveryLoadNetworkAction.REORDER,
                        DeliveryLoadNetworkAction.ASSIGN,
                        DeliveryLoadNetworkAction.PLAN_WINDOW
                    ) &&
                    frozenBody.isNullOrBlank()
                ) ||
            (
                action in
                    setOf(
                        DeliveryLoadNetworkAction.OFFER,
                        DeliveryLoadNetworkAction.CONFIRM_HANDOFF,
                        DeliveryLoadNetworkAction.ACCEPT
                    ) &&
                    frozenBody != null
                )
        ) {
            return DeliveryLoadNetworkResult.Failed("INVALID_COMMAND")
        }
        if (action == DeliveryLoadNetworkAction.PLAN_WINDOW &&
            driver
        ) {
            return DeliveryLoadNetworkResult.Failed("INVALID_COMMAND")
        }
        val prefix = if (driver) "/api/v1/driver/loads" else "/api/v1/dispatch/loads"
        val suffix = when (action) {
            DeliveryLoadNetworkAction.CREATE -> ""
            DeliveryLoadNetworkAction.REORDER -> "/$loadId/stops"
            DeliveryLoadNetworkAction.ASSIGN -> "/$loadId/assignments"
            DeliveryLoadNetworkAction.OFFER -> "/$loadId/offers"
            DeliveryLoadNetworkAction.CONFIRM_HANDOFF -> "/$loadId/handoff-confirmations"
            DeliveryLoadNetworkAction.ACCEPT -> "/$loadId/acceptances"
            DeliveryLoadNetworkAction.PLAN_WINDOW -> "/$loadId/dispatch-window-plans"
        }
        val path = if (action == DeliveryLoadNetworkAction.PLAN_WINDOW) {
            "/api/v1/fulfillments/$loadId/dispatch-window-plans"
        } else {
            prefix + suffix
        }
        val request = ProtectedRequest(
            if (action ==
                DeliveryLoadNetworkAction.REORDER
            ) {
                ProtectedMethod.PUT
            } else {
                ProtectedMethod.POST
            },
            path,
            frozenBody,
            key,
            version?.let { "\"$it\"" }
        )
        return when (val result = calls.execute(request)) {
            is ProtectedResult.Failure -> DeliveryLoadNetworkResult.Failed(
                result.error.problemCode,
                result.error.kind in
                    setOf(
                        FailureKind.UnknownOutcome,
                        FailureKind.ProtocolFailure,
                        FailureKind.NetworkUnavailable,
                        FailureKind.Timeout
                    )
            )

            is ProtectedResult.Success -> try {
                if (action == DeliveryLoadNetworkAction.PLAN_WINDOW) {
                    val value = json.decodeFromString<DispatchWindowPlanTransport>(
                        result.body ?: error("Missing plan")
                    )
                    require(
                        valid(value) && value.fulfillmentId == loadId &&
                            value.fulfillmentVersion == (version ?: -2L) + 1 &&
                            result.etag == "\"${value.fulfillmentVersion}\""
                    )
                    DeliveryLoadNetworkResult.WindowPlanned(value)
                } else {
                    val value = json.decodeFromString<DeliveryLoadTransport>(
                        result.body ?: error("Missing load")
                    )
                    require(
                        valid(value) && (creating || value.id == loadId) &&
                            result.etag == "\"${value.version}\""
                    )
                    DeliveryLoadNetworkResult.Current(value)
                }
            } catch (_: Exception) {
                DeliveryLoadNetworkResult.Failed("INVALID_RESPONSE", true)
            }
        }
    }
    private fun valid(value: DeliveryLoadTransport): Boolean = try {
        require(
            uuid(value.id) && uuid(value.originWarehouseId) && value.version >= 0 &&
                value.status in statuses
        )
        require(
            value.stops.size in 2..20 &&
                value.stops.map { it.deliveryId }.distinct().size == value.stops.size &&
                value.stops.map { it.fulfillmentId }.distinct().size == value.stops.size &&
                value.stops.map { it.position }.sorted() == (1..value.stops.size).toList()
        )
        require(
            value.stops.all {
                uuid(it.deliveryId) && uuid(it.fulfillmentId) &&
                    it.deliveryVersion >= 0
            }
        )
        require(value.assignedDriverMembershipId == null || uuid(value.assignedDriverMembershipId))
        val attestation = value.compatibilityAttestation
        require(
            attestation.capacitySufficient && attestation.handlingCompatible &&
                attestation.zoneReasonable &&
                attestation.noExclusiveTransportRestriction &&
                uuid(attestation.attestedByMembershipId)
        )
        Instant.parse(attestation.attestedAt)
        require(
            pairedFact(value.offeredByMembershipId, value.offeredAt) &&
                pairedFact(value.dispatchConfirmedByMembershipId, value.dispatchConfirmedAt) &&
                pairedFact(value.driverAcceptedByMembershipId, value.driverAcceptedAt)
        )
        require(
            value.status != "RESPONSIBILITY_TRANSFERRED" ||
                (value.dispatchConfirmedAt != null && value.driverAcceptedAt != null)
        )
        require(
            value.history.all {
                uuid(it.actorMembershipId) && it.eventType.isNotBlank() &&
                    runCatching { Instant.parse(it.occurredAt) }.isSuccess &&
                    it.previousStopOrder.all(::uuid) &&
                    it.newStopOrder.all(::uuid)
            }
        )
        true
    } catch (_: Exception) {
        false
    }
    private fun valid(value: DispatchWindowPlanTransport): Boolean = try {
        require(
            uuid(value.fulfillmentId) && value.fulfillmentVersion >= 1 && value.revision >= 1 &&
                value.reason.isNotBlank() && uuid(value.recordedByMembershipId)
        )
        require(Instant.parse(value.windowStart).isBefore(Instant.parse(value.windowEnd)))
        Instant.parse(value.recordedAt)
        true
    } catch (_: Exception) {
        false
    }
    private fun pairedFact(actor: String?, at: String?): Boolean = if (actor == null ||
        at == null
    ) {
        actor == null && at == null
    } else {
        uuid(actor) &&
            runCatching { Instant.parse(at) }.isSuccess
    }
    private fun uuid(value: String): Boolean = try {
        UUID.fromString(value).toString().equals(value, true)
    } catch (
        _: Exception
    ) {
        false
    }
    private companion object {
        val statuses =
            setOf(
                "DRAFT",
                "ASSIGNED",
                "OFFERED",
                "HANDOFF_CONFIRMED",
                "DRIVER_ACCEPTED",
                "RESPONSIBILITY_TRANSFERRED"
            )
    }
}
