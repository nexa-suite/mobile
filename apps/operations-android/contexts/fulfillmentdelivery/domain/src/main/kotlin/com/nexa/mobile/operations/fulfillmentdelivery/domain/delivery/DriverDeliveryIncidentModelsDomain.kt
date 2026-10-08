package com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery

data class DriverIncidentCurrentDelivery(
    val deliveryId: String,
    val status: String,
    val version: Long,
    val activeAttemptId: String?
)

/** Ephemeral native-picker binding. It carries no access token or server authority. */

enum class DriverIncidentType {
    DELAY,
    INCOMPLETE_INSTRUCTION,
    ACCESS_BLOCKED,
    CUSTOMER_UNAVAILABLE,
    DELIVERY_NOT_EXECUTABLE,
    TEMPERATURE_EXCURSION,
    SAFETY_COMPROMISING_DAMAGE
}

data class DriverIncidentSummary(
    val incidentId: String,
    val deliveryId: String,
    val attemptId: String,
    val reason: String,
    val description: String,
    val place: String,
    val recordedByMembershipId: String,
    val recordedAt: String,
    val evidenceObjectIds: List<String>,
    val deliveryVersion: Long,
    val replayed: Boolean,
    val type: DriverIncidentType? = null,
    val severity: String? = null,
    val operationalExceptionId: String? = null
) {
    val evidenceLabel: String
        get() = if (evidenceObjectIds.isEmpty()) {
            "PENDIENTE_EVIDENCIA"
        } else {
            "EVIDENCIA_VINCULADA_PARA_REVISION"
        }
}

data class DriverIncidentEvidenceProjection(
    val evidenceId: String,
    val subjectType: String,
    val subjectId: String,
    val lifecycleStatus: String,
    val contentType: String,
    val checksumSha256: String?,
    val byteSize: Long
)
