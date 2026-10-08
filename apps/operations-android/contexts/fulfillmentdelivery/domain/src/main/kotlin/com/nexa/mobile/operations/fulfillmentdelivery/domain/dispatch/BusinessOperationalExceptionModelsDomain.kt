package com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch

internal val BUSINESS_OPERATIONAL_EXCEPTION_STATUSES =
    setOf("OPEN", "CLAIMED", "UNDER_REVIEW", "RESOLVED", "CLOSED")



data class BusinessOperationalException(
    val id: String,
    val deliveryId: String,
    val deliveryVersion: Long,
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
    val coordinationOwnerMembershipId: String?,
    val coordinationClaimedAt: String?,
    val evidenceObjectIds: List<String>
) {
    init {
        require(isValidOpaqueIdentifier(id) && isValidOpaqueIdentifier(deliveryId))
        require(deliveryVersion >= 0 && isValidOpaqueIdentifier(sourceIncidentId))
        require(isValidOpaqueIdentifier(affectedObjectId) && affectedObjectType.isNotBlank())
        require(type.isNotBlank() && severity in setOf("WARNING", "BLOCKING", "CRITICAL"))
        require(status in BUSINESS_OPERATIONAL_EXCEPTION_STATUSES && description.isNotBlank())
        require(isValidOpaqueIdentifier(reportedByMembershipId))
        require(occurredAt.isNotBlank() && reportedAt.isNotBlank())
        require(
            responsibleMembershipId == null ||
                isValidOpaqueIdentifier(responsibleMembershipId)
        )
        require(
            underReviewByMembershipId == null ||
                isValidOpaqueIdentifier(underReviewByMembershipId)
        )
        require(
            coordinationOwnerMembershipId == null ||
                isValidOpaqueIdentifier(coordinationOwnerMembershipId)
        )
        require(evidenceObjectIds.distinct().size == evidenceObjectIds.size)
        require(evidenceObjectIds.all(::isValidOpaqueIdentifier))
    }
}



data class BusinessOperationalExceptionsSnapshot(
    val asOf: String,
    val exceptions: List<BusinessOperationalException>
) {
    init {
        require(asOf.isNotBlank())
        require(exceptions.map { it.id.lowercase() }.distinct().size == exceptions.size)
    }
}



data class BusinessOperationalExceptionActor(
    val membershipId: String,
    val displayName: String,
    val coordinator: Boolean,
    val driverReporter: Boolean
) {
    init {
        require(isValidOpaqueIdentifier(membershipId) && displayName.isNotBlank())
    }
}
