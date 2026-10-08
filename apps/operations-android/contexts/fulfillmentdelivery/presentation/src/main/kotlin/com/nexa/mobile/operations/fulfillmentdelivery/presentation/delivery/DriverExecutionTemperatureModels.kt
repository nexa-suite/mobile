package com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery.DriverExecutionTemperatureCommandStatus as TemperatureCommandStatus
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery.DriverExecutionTemperatureEvidenceStatus as TemperatureEvidenceStatus
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery.DriverExecutionTemperatureLoadStatus as TemperatureLoadStatus
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery.DriverExecutionTemperatureUiState as TemperatureUiState
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureCommand
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureMode
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureReading
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureSnapshot

enum class DriverExecutionTemperatureCommandStatus {
    Idle,
    PersistingIntent,
    Pending,
    UnknownOutcome,
    Recorded,
    Disposed,
    StaleVersion,
    Rejected,
    PersistenceUnavailable
}
enum class DriverExecutionTemperatureLoadStatus {
    NotRequested,
    Loading,
    Ready,
    NotFound,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    ContextInvalidated,
    SessionInvalidated
}

sealed interface DriverExecutionTemperatureEvidenceStatus {
    data object None : TemperatureEvidenceStatus
    data object Checking : TemperatureEvidenceStatus
    data object Available : TemperatureEvidenceStatus
    data class Unavailable(val code: String? = null) : TemperatureEvidenceStatus
}

@Immutable
data class DriverExecutionTemperatureUiState(
    val authorityEpoch: Long = 0,
    val mode: DriverExecutionTemperatureMode = DriverExecutionTemperatureMode.DRIVER,
    val deliveryId: String? = null,
    val canRead: Boolean = false,
    val canRecord: Boolean = false,
    val canDispose: Boolean = false,
    val currentMembershipId: String? = null,
    val snapshot: DriverExecutionTemperatureSnapshot? = null,
    val lastReading: DriverExecutionTemperatureReading? = null,
    val loadStatus: TemperatureLoadStatus = TemperatureLoadStatus.NotRequested,
    val commandStatus: TemperatureCommandStatus = TemperatureCommandStatus.Idle,
    val command: DriverExecutionTemperatureCommand? = null,
    val hasRecoverableCommand: Boolean = false,
    val unresolvedCommandForOtherDelivery: Boolean = false,
    val quantitiesByLine: Map<String, String> = emptyMap(),
    val valuesCelsiusByLine: Map<String, String> = emptyMap(),
    val dispositionReasonsByHold: Map<String, String> = emptyMap(),
    val sourceIncidentId: String? = null,
    val sourceEvidenceObjectId: String? = null,
    val sourceEvidenceStatus: TemperatureEvidenceStatus = TemperatureEvidenceStatus.None,
    val replayed: Boolean = false,
    val rejectionCode: String? = null
) {
    val canRetryUnknownOutcome: Boolean
        get() = hasRecoverableCommand &&
            commandStatus == TemperatureCommandStatus.UnknownOutcome &&
            command != null && !unresolvedCommandForOtherDelivery

    override fun toString(): String =
        "TemperatureUiState(mode=$mode, load=$loadStatus, command=$commandStatus, payload=REDACTED)"
}
