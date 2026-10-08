package com.nexa.mobile.operations.feature.dispatch

import androidx.compose.runtime.Immutable
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperatureCommand
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperaturePhotoEvidence
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperatureReadiness
import java.time.Instant

enum class DispatchTemperaturePhotoStatus {
    None,
    Uploading,
    Checking,
    AwaitingAvailability,
    Available,
    UnknownOutcome,
    NetworkUnavailable,
    ServiceUnavailable,
    Rejected,
    PermissionDenied
}

@Immutable
data class DispatchTemperaturePhotoState(
    val evidence: DispatchTemperaturePhotoEvidence? = null,
    val warehouseId: String? = null,
    val status: DispatchTemperaturePhotoStatus = DispatchTemperaturePhotoStatus.None
) {
    val isAvailable: Boolean
        get() = status == DispatchTemperaturePhotoStatus.Available &&
            evidence?.lifecycleStatus.equals("AVAILABLE", ignoreCase = true)
}

enum class DispatchTemperatureStatus {
    Initial,
    Loading,
    Current,
    PermissionUnknown,
    PermissionDenied,
    NetworkUnavailable,
    ServiceUnavailable,
    ContextInvalidated,
    SessionInvalidated
}

enum class DispatchTemperatureMutationStatus {
    Idle,
    Submitting,
    Recorded,
    ExcursionPhotoRequired,
    OutsideRangeBackendContractGap,
    UnknownOutcome,
    NetworkUnavailable,
    ServiceUnavailable,
    PermissionDenied,
    Stale,
    Conflict
}

@Immutable
data class DispatchTemperatureUiState(
    val authorityEpoch: Long = 0,
    val fulfillmentId: String? = null,
    val status: DispatchTemperatureStatus = DispatchTemperatureStatus.Initial,
    val readiness: DispatchTemperatureReadiness? = null,
    val observedAt: Instant? = null,
    val valuesCelsius: Map<String, String> = emptyMap(),
    val mutationStatus: DispatchTemperatureMutationStatus = DispatchTemperatureMutationStatus.Idle,
    val mutationLotId: String? = null,
    val canRecord: Boolean = false,
    val metadataReady: Boolean = false,
    val hasPendingCommand: Boolean = false,
    val pendingCommand: DispatchTemperatureCommand? = null,
    val photoByLotId: Map<String, DispatchTemperaturePhotoState> = emptyMap(),
    val canUploadPhoto: Boolean = false
) {
    val canRetryUnknownOutcome: Boolean
        get() = canRecord && hasPendingCommand && pendingCommand != null &&
            mutationStatus in setOf(
                DispatchTemperatureMutationStatus.UnknownOutcome,
                DispatchTemperatureMutationStatus.Recorded
            )

    override fun toString(): String = "DispatchTemperatureUiState(status=$status, " +
        "mutationStatus=$mutationStatus, readiness=${readiness != null})"
}
