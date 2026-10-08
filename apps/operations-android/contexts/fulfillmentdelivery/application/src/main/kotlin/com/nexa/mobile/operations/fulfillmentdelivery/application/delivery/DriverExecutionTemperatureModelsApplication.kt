package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureDisposition
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureHold
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureReading
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.isValidOpaqueIdentifier
import java.math.BigDecimal
import java.time.Instant

enum class DriverExecutionTemperatureIntentStatus {
    Pending,
    UnknownOutcome,
    StaleVersion
}

enum class DriverExecutionTemperatureMetadataWrite {
    Saved,
    Conflict,
    Stale,
    Unavailable
}

sealed interface DriverExecutionTemperatureCommand {
    val deliveryId: String
    val expectedDeliveryVersion: Long
    val idempotencyKey: String
    val frozenBody: String
    fun isValid(): Boolean
    data class Reading(
        override val deliveryId: String,
        override val expectedDeliveryVersion: Long,
        val fulfillmentLineId: String,
        val skuId: String,
        val affectedQuantity: BigDecimal,
        val valueCelsius: BigDecimal,
        val occurredAt: Instant,
        val sourceIncidentId: String?,
        val evidenceObjectId: String?,
        override val idempotencyKey: String,
        override val frozenBody: String
    ) : DriverExecutionTemperatureCommand {
        override fun isValid(): Boolean = isValidOpaqueIdentifier(deliveryId) &&
            expectedDeliveryVersion >= 0 && isValidOpaqueIdentifier(fulfillmentLineId) &&
            isValidOpaqueIdentifier(skuId) && affectedQuantity.signum() > 0 &&
            valueCelsius.abs() < BigDecimal("1000") && idempotencyKey.isNotBlank() &&
            idempotencyKey.length <= 160 &&
            (
                (sourceIncidentId == null && evidenceObjectId == null) ||
                    (
                        sourceIncidentId != null && evidenceObjectId != null &&
                            isValidOpaqueIdentifier(sourceIncidentId) &&
                            isValidOpaqueIdentifier(evidenceObjectId)
                        )
                )

        override fun toString(): String =
            "ExecutionTemperatureReadingCommand(version=$expectedDeliveryVersion, key=REDACTED)"
    }
    data class Disposition(
        override val deliveryId: String,
        val holdId: String,
        override val expectedDeliveryVersion: Long,
        val disposition: DriverExecutionTemperatureDisposition,
        val reason: String,
        override val idempotencyKey: String,
        override val frozenBody: String
    ) : DriverExecutionTemperatureCommand {
        override fun isValid(): Boolean = isValidOpaqueIdentifier(deliveryId) &&
            isValidOpaqueIdentifier(holdId) && expectedDeliveryVersion >= 0 &&
            reason.trim().isNotEmpty() && reason.trim().length <= 2000 &&
            idempotencyKey.isNotBlank() && idempotencyKey.length <= 160

        override fun toString(): String =
            "ExecutionTemperatureDispositionCommand(version=$expectedDeliveryVersion, key=REDACTED)"
    }
}

data class DriverExecutionTemperatureIntent(
    val scope: DriverAttemptScopeIdentity,
    val command: DriverExecutionTemperatureCommand,
    val initiatedAt: Instant,
    val status: DriverExecutionTemperatureIntentStatus
) {
    init {
        require(command.isValid())
        require(command.deliveryId.isNotBlank() && command.idempotencyKey.isNotBlank())
    }

    override fun toString(): String =
        "DriverExecutionTemperatureIntent(status=$status, command=REDACTED)"
}

sealed interface DriverExecutionTemperatureMetadataRead {
    data class Available(val intent: DriverExecutionTemperatureIntent?) :
        DriverExecutionTemperatureMetadataRead
    data object Unavailable : DriverExecutionTemperatureMetadataRead
}

sealed interface DriverExecutionTemperatureLoadResult {
    data class Loaded(val snapshot: DriverExecutionTemperatureSnapshot) :
        DriverExecutionTemperatureLoadResult
    data object NotFound : DriverExecutionTemperatureLoadResult
    data object NetworkUnavailable : DriverExecutionTemperatureLoadResult
    data object ServiceUnavailable : DriverExecutionTemperatureLoadResult
    data object PermissionDenied : DriverExecutionTemperatureLoadResult
    data object ContextInvalidated : DriverExecutionTemperatureLoadResult
    data object SessionInvalidated : DriverExecutionTemperatureLoadResult
}

sealed interface DriverExecutionTemperatureMutationResult {
    data class ReadingRecorded(val reading: DriverExecutionTemperatureReading) :
        DriverExecutionTemperatureMutationResult
    data class DispositionRecorded(
        val hold: DriverExecutionTemperatureHold,
        val deliveryVersion: Long,
        val replayed: Boolean
    ) : DriverExecutionTemperatureMutationResult
    data class Rejected(val code: String?) : DriverExecutionTemperatureMutationResult
    data object NotFound : DriverExecutionTemperatureMutationResult
    data object StaleVersion : DriverExecutionTemperatureMutationResult
    data object Conflict : DriverExecutionTemperatureMutationResult
    data object UnknownOutcome : DriverExecutionTemperatureMutationResult
    data object NetworkUnavailable : DriverExecutionTemperatureMutationResult
    data object ServiceUnavailable : DriverExecutionTemperatureMutationResult
    data object PermissionDenied : DriverExecutionTemperatureMutationResult
    data object ContextInvalidated : DriverExecutionTemperatureMutationResult
    data object SessionInvalidated : DriverExecutionTemperatureMutationResult
}
