package com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery

data class DriverDeliveryOperationalException(
    val id: String,
    val sourceKind: String,
    val sourceIncidentId: String,
    val affectedObjectType: String,
    val affectedObjectId: String,
    val type: String,
    val severity: String,
    val status: String,
    val reason: String?,
    val description: String,
    val place: String?,
    val resolution: String?,
    val outcome: String?,
    val reportedByMembershipId: String,
    val occurredAt: String,
    val reportedAt: String,
    val responsibleMembershipId: String?,
    val claimedAt: String?,
    val underReviewByMembershipId: String?,
    val underReviewAt: String?,
    val evidenceObjectIds: List<String>
) {
    init {
        require(isValidOpaqueIdentifier(id))
        require(sourceKind in setOf("DISPATCH_INCIDENT", "DRIVER_INCIDENT"))
        require(isValidOpaqueIdentifier(sourceIncidentId))
        require(affectedObjectType == "DELIVERY")
        require(isValidOpaqueIdentifier(affectedObjectId))
        require(type.isNotBlank() && severity.isNotBlank() && status.isNotBlank())
        require(status in setOf("OPEN", "CLAIMED", "UNDER_REVIEW", "RESOLVED", "CLOSED"))
        require(description.isNotBlank())
        require(isValidOpaqueIdentifier(reportedByMembershipId))
        require(occurredAt.isNotBlank())
        require(reportedAt.isNotBlank())
        require(
            responsibleMembershipId == null ||
                isValidOpaqueIdentifier(responsibleMembershipId)
        )
        require(claimedAt == null || claimedAt.isNotBlank())
        require(
            underReviewByMembershipId == null ||
                isValidOpaqueIdentifier(underReviewByMembershipId)
        )
        require(underReviewAt == null || underReviewAt.isNotBlank())
        require(evidenceObjectIds.distinct().size == evidenceObjectIds.size)
        require(evidenceObjectIds.all(::isValidOpaqueIdentifier))
    }
}



data class DriverDeliveryOperationalExceptionsSnapshot(
    val deliveryId: String,
    val deliveryVersion: Long,
    val exceptions: List<DriverDeliveryOperationalException>
) {
    init {
        require(isValidOpaqueIdentifier(deliveryId))
        require(deliveryVersion >= 0)
        require(exceptions.map { it.id.lowercase() }.distinct().size == exceptions.size)
    }
}



const val DRIVER_WARNING_RESOLUTION_MAX_CHARS = 2000
