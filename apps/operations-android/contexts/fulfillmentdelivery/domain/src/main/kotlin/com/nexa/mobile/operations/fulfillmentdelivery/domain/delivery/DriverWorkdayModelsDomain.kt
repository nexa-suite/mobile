package com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery

enum class DriverWorkdayStatus {
    ACTIVE,
    LOCATION_UNAVAILABLE,
    CLOSED
}



data class DriverWorkday(
    val id: String,
    val version: Long,
    val status: DriverWorkdayStatus,
    val startedAt: String,
    val endedAt: String?,
    val locationAvailable: Boolean
)



data class DriverWorkdayLocationSample(
    val sampleId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAt: String
)



sealed interface DriverWorkdayLocationEvent {
    data class Sample(val value: DriverWorkdayLocationSample) : DriverWorkdayLocationEvent
    data object PermissionUnavailable : DriverWorkdayLocationEvent
    data object ProviderUnavailable : DriverWorkdayLocationEvent
}
