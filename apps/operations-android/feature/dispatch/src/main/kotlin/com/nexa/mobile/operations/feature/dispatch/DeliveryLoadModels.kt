package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val loadUuid = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private const val MAX_LOAD_STOPS = 20

@Immutable
data class DeliveryLoadStop(
    val fulfillmentId: String,
    val deliveryId: String,
    val position: Int,
    val deliveryVersion: Long
) {
    init {
        require(loadUuid.matches(fulfillmentId) && loadUuid.matches(deliveryId))
        require(position > 0 && deliveryVersion >= 0)
    }
}

@Immutable
data class DeliveryLoadCompatibilityAttestation(
    val capacitySufficient: Boolean,
    val handlingCompatible: Boolean,
    val zoneReasonable: Boolean,
    val noExclusiveTransportRestriction: Boolean,
    val observation: String? = null,
    val attestedByMembershipId: String? = null,
    val attestedAt: Instant? = null
) {
    val complete: Boolean
        get() = capacitySufficient && handlingCompatible && zoneReasonable &&
            noExclusiveTransportRestriction

    init {
        require(
            observation == null || (observation.isNotBlank() && observation.trim().length <= 1000)
        )
        require(attestedByMembershipId == null || loadUuid.matches(attestedByMembershipId))
        require((attestedByMembershipId == null) == (attestedAt == null))
    }
}

@Immutable
data class DeliveryLoadHistoryEvent(
    val eventType: String,
    val actorMembershipId: String,
    val occurredAt: Instant,
    val reason: String?,
    val affectedDriverMembershipId: String?,
    val previousStopOrder: List<String>,
    val newStopOrder: List<String>
)

@Immutable
data class DeliveryLoad(
    val id: String,
    val version: Long,
    val status: String,
    val originWarehouseId: String,
    val stops: List<DeliveryLoadStop>,
    val assignedDriverMembershipId: String?,
    val vehicleReference: String?,
    val compatibilityAttestation: DeliveryLoadCompatibilityAttestation,
    val offeredByMembershipId: String?,
    val offeredAt: Instant?,
    val dispatchConfirmedByMembershipId: String?,
    val dispatchConfirmedAt: Instant?,
    val driverAcceptedByMembershipId: String?,
    val driverAcceptedAt: Instant?,
    val history: List<DeliveryLoadHistoryEvent>
) {
    val orderedStops: List<DeliveryLoadStop> get() = stops.sortedBy(DeliveryLoadStop::position)

    init {
        require(loadUuid.matches(id) && loadUuid.matches(originWarehouseId) && version >= 0)
        require(status in DELIVERY_LOAD_STATUSES)
        require(stops.size in 2..MAX_LOAD_STOPS)
        require(stops.map { it.fulfillmentId.lowercase() }.distinct().size == stops.size)
        require(stops.map { it.deliveryId.lowercase() }.distinct().size == stops.size)
        require(stops.map { it.position }.sorted() == (1..stops.size).toList())
        require(assignedDriverMembershipId == null || loadUuid.matches(assignedDriverMembershipId))
        require((offeredByMembershipId == null) == (offeredAt == null))
        require(offeredByMembershipId == null || loadUuid.matches(offeredByMembershipId))
        require((dispatchConfirmedByMembershipId == null) == (dispatchConfirmedAt == null))
        require(
            dispatchConfirmedByMembershipId == null ||
                loadUuid.matches(dispatchConfirmedByMembershipId)
        )
        require((driverAcceptedByMembershipId == null) == (driverAcceptedAt == null))
        require(
            driverAcceptedByMembershipId == null || loadUuid.matches(driverAcceptedByMembershipId)
        )
        require(
            status != "RESPONSIBILITY_TRANSFERRED" ||
                (dispatchConfirmedAt != null && driverAcceptedAt != null)
        )
    }

    override fun toString(): String =
        "DeliveryLoad(status=$status, version=$version, stops=${stops.size})"
}

@Immutable
data class DeliveryLoadDriverCandidate(val membershipId: String, val displayName: String) {
    init {
        require(loadUuid.matches(membershipId) && displayName.isNotBlank())
    }
}

data class DeliveryLoadScopeIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    init {
        require(listOf(userId, tenantId, workspaceId, membershipId).all(loadUuid::matches))
    }
    override fun toString(): String = "DeliveryLoadScopeIdentity(REDACTED)"
}

enum class DeliveryLoadCommandAction {
    CREATE,
    REORDER,
    ASSIGN,
    OFFER,
    CONFIRM_HANDOFF,
    ACCEPT,
    PLAN_WINDOW
}
enum class DeliveryLoadCommandIntentStatus {
    Pending,
    UnknownOutcome
}

data class DeliveryLoadCommand(
    val scope: DeliveryLoadScopeIdentity,
    val driverCommand: Boolean,
    val action: DeliveryLoadCommandAction,
    val loadId: String?,
    val expectedVersion: Long?,
    val idempotencyKey: String,
    val frozenBody: String?,
    val status: DeliveryLoadCommandIntentStatus
) {
    init {
        require(
            if (action == DeliveryLoadCommandAction.CREATE) {
                loadId == null && expectedVersion == null
            } else {
                loadId != null && expectedVersion != null
            }
        )
        require(loadId == null || loadUuid.matches(loadId))
        require(expectedVersion == null || expectedVersion >= 0)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 160)
        require(driverCommand == (action == DeliveryLoadCommandAction.ACCEPT))
        require(
            when (action) {
                DeliveryLoadCommandAction.CREATE,
                DeliveryLoadCommandAction.REORDER,
                DeliveryLoadCommandAction.ASSIGN,
                DeliveryLoadCommandAction.PLAN_WINDOW -> !frozenBody.isNullOrBlank()

                DeliveryLoadCommandAction.OFFER,
                DeliveryLoadCommandAction.CONFIRM_HANDOFF,
                DeliveryLoadCommandAction.ACCEPT -> frozenBody == null
            }
        )
    }

    override fun toString(): String =
        "DeliveryLoadCommand(action=$action, version=$expectedVersion, key=REDACTED, body=REDACTED)"
}

sealed interface DeliveryLoadCommandMetadataRead {
    data class Available(val command: DeliveryLoadCommand?) : DeliveryLoadCommandMetadataRead
    data object Unavailable : DeliveryLoadCommandMetadataRead
}

enum class DeliveryLoadCommandMetadataWrite {
    Saved,
    Conflict,
    Stale,
    Unavailable
}

interface DeliveryLoadCommandMetadataStore {
    suspend fun load(scope: DeliveryLoadScopeIdentity): DeliveryLoadCommandMetadataRead
    suspend fun save(command: DeliveryLoadCommand): DeliveryLoadCommandMetadataWrite
    suspend fun clear(
        scope: DeliveryLoadScopeIdentity,
        idempotencyKey: String
    ): DeliveryLoadCommandMetadataWrite
}

sealed interface DeliveryLoadGatewayResult {
    data class Loaded(
        val loads: List<DeliveryLoad>,
        val readyCandidates: List<DispatchReadiness>,
        val drivers: List<DeliveryLoadDriverCandidate>
    ) : DeliveryLoadGatewayResult

    data class Changed(val load: DeliveryLoad) : DeliveryLoadGatewayResult
    data class WindowPlanned(val fulfillmentId: String) : DeliveryLoadGatewayResult
    data class Failed(val code: String?, val unknownOutcome: Boolean = false) :
        DeliveryLoadGatewayResult
}

interface DeliveryLoadGateway {
    suspend fun current(
        context: DispatchAuthorityContext,
        driver: Boolean
    ): DeliveryLoadGatewayResult
    suspend fun mutate(
        command: DeliveryLoadCommand,
        context: DispatchAuthorityContext
    ): DeliveryLoadGatewayResult
}

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
    require(loadUuid.matches(driverMembershipId))
    return buildJsonObject { put("driverMembershipId", driverMembershipId) }.toString()
}

fun deliveryLoadReorderBody(stopOrder: List<String>, reason: String): String {
    val normalizedReason = reason.trim()
    require(stopOrder.size in 2..MAX_LOAD_STOPS && stopOrder.all(loadUuid::matches))
    require(stopOrder.distinct().size == stopOrder.size)
    require(normalizedReason.isNotEmpty() && normalizedReason.length <= 500)
    return buildJsonObject {
        put("stopOrder", JsonArray(stopOrder.map { JsonPrimitive(it) }))
        put("reason", normalizedReason)
    }.toString()
}

internal val DELIVERY_LOAD_STATUSES = setOf(
    "DRAFT",
    "ASSIGNED",
    "OFFERED",
    "HANDOFF_CONFIRMED",
    "DRIVER_ACCEPTED",
    "RESPONSIBILITY_TRANSFERRED"
)
