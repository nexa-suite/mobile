package com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch

import java.time.Instant

const val MAX_LOAD_STOPS = 20



val DELIVERY_LOAD_STATUSES = setOf(
    "DRAFT",
    "ASSIGNED",
    "OFFERED",
    "HANDOFF_CONFIRMED",
    "DRIVER_ACCEPTED",
    "RESPONSIBILITY_TRANSFERRED"
)



data class DeliveryLoadStop(
    val fulfillmentId: String,
    val deliveryId: String,
    val position: Int,
    val deliveryVersion: Long
) {
    init {
        require(isValidOpaqueIdentifier(fulfillmentId) && isValidOpaqueIdentifier(deliveryId))
        require(position > 0 && deliveryVersion >= 0)
    }
}



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
        require(attestedByMembershipId == null || isValidOpaqueIdentifier(attestedByMembershipId))
        require((attestedByMembershipId == null) == (attestedAt == null))
    }
}



data class DeliveryLoadHistoryEvent(
    val eventType: String,
    val actorMembershipId: String,
    val occurredAt: Instant,
    val reason: String?,
    val affectedDriverMembershipId: String?,
    val previousStopOrder: List<String>,
    val newStopOrder: List<String>
)



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
        require(isValidOpaqueIdentifier(id) && isValidOpaqueIdentifier(originWarehouseId) && version >= 0)
        require(status in DELIVERY_LOAD_STATUSES)
        require(stops.size in 2..MAX_LOAD_STOPS)
        require(stops.map { it.fulfillmentId.lowercase() }.distinct().size == stops.size)
        require(stops.map { it.deliveryId.lowercase() }.distinct().size == stops.size)
        require(stops.map { it.position }.sorted() == (1..stops.size).toList())
        require(assignedDriverMembershipId == null || isValidOpaqueIdentifier(assignedDriverMembershipId))
        require((offeredByMembershipId == null) == (offeredAt == null))
        require(offeredByMembershipId == null || isValidOpaqueIdentifier(offeredByMembershipId))
        require((dispatchConfirmedByMembershipId == null) == (dispatchConfirmedAt == null))
        require(
            dispatchConfirmedByMembershipId == null ||
                isValidOpaqueIdentifier(dispatchConfirmedByMembershipId)
        )
        require((driverAcceptedByMembershipId == null) == (driverAcceptedAt == null))
        require(
            driverAcceptedByMembershipId == null || isValidOpaqueIdentifier(driverAcceptedByMembershipId)
        )
        require(
            status != "RESPONSIBILITY_TRANSFERRED" ||
                (dispatchConfirmedAt != null && driverAcceptedAt != null)
        )
    }

    override fun toString(): String =
        "DeliveryLoad(status=$status, version=$version, stops=${stops.size})"
}



data class DeliveryLoadDriverCandidate(val membershipId: String, val displayName: String) {
    init {
        require(isValidOpaqueIdentifier(membershipId) && displayName.isNotBlank())
    }
}
