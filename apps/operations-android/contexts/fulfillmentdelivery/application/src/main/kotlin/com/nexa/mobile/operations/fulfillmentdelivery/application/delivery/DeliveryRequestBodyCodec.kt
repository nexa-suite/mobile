package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverIncidentType
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverOutcomeKind
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverOutcomeLineDecision
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverProofEvidenceKind

/** Infrastructure-owned JSON encoding and frozen-body validation for driver delivery requests. */
interface DeliveryRequestBodyCodec {
    fun driverIncidentBody(
        type: DriverIncidentType?,
        reason: String,
        description: String,
        place: String
    ): String
    fun driverIncidentEvidenceAttachBody(evidenceId: String): String
    fun isValid(command: DriverIncidentCommand): Boolean

    fun driverDeliveryInstructionAcknowledgementBody(instructionIds: Collection<String>): String
    fun isValid(command: DriverDeliveryInstructionAcknowledgementCommand): Boolean

    fun driverDeliveryOperationalExceptionResolutionBody(value: String): String
    fun driverDeliveryOperationalExceptionResolutionFromBody(body: String): String?
    fun driverDeliveryOperationalExceptionEmptyBody(): String
    fun isValid(command: DriverDeliveryOperationalExceptionCommand): Boolean

    fun driverExecutionTemperatureBody(command: DriverExecutionTemperatureCommand): String
    fun driverExecutionTemperatureReadingBody(
        fulfillmentLineId: String,
        skuId: String,
        affectedQuantity: java.math.BigDecimal,
        valueCelsius: java.math.BigDecimal,
        occurredAt: java.time.Instant,
        sourceIncidentId: String?,
        evidenceObjectId: String?
    ): String
    fun driverExecutionTemperatureDispositionBody(
        disposition: com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureDisposition,
        reason: String
    ): String
    fun isValid(command: DriverExecutionTemperatureCommand): Boolean

    fun driverHandoffIssueBody(attemptId: String): String
    fun isValid(command: DriverHandoffIssueCommand): Boolean
    fun isValid(command: DriverProofCreateCommand): Boolean
    fun isValid(command: DriverProofAttachCommand): Boolean
    fun isValid(intent: DriverProofIntentMetadata): Boolean

    fun driverOutcomeBody(
        outcome: DriverOutcomeKind,
        reason: String?,
        notes: String?,
        attemptedAt: String,
        lines: List<DriverOutcomeLineDecision>
    ): String

    fun driverProofCreateBody(receiverName: String, capturedAt: String, notes: String?): String
    fun driverProofAttachBody(kind: DriverProofEvidenceKind, evidenceId: String): String
    fun driverArrivalBody(): String
    fun isValid(command: DriverArrivalCommand): Boolean
    fun isValid(command: DriverOutcomeCommand): Boolean
}
