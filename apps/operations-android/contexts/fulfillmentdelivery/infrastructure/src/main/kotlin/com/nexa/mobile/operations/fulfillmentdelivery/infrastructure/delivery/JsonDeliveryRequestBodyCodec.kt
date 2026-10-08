package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DeliveryRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionAcknowledgementCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionAction
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryOperationalExceptionCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverHandoffIssueCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofAttachCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofCreateCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofIntentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureDisposition
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverIncidentType
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverOutcomeKind
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverOutcomeLineDecision
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverProofEvidenceKind
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.driverDeliveryInstructionAcknowledgementBody as encodeInstructionAcknowledgementBody
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.driverDeliveryOperationalExceptionEmptyBody as encodeOperationalExceptionEmptyBody
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.driverDeliveryOperationalExceptionResolutionBody as encodeOperationalExceptionBody
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.driverDeliveryOperationalExceptionResolutionFromBody as decodeOperationalExceptionBody
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.driverExecutionTemperatureDispositionBody as encodeTemperatureDispositionBody
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.driverExecutionTemperatureReadingBody as encodeTemperatureReadingBody
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.driverHandoffIssueBody as encodeHandoffIssueBody
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.driverIncidentBody as encodeTypedIncidentBody
import java.math.BigDecimal
import java.time.Instant
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JsonDeliveryRequestBodyCodec @Inject constructor() : DeliveryRequestBodyCodec {
    override fun driverIncidentBody(
        type: DriverIncidentType?,
        reason: String,
        description: String,
        place: String
    ): String = if (type == null) {
        driverIncidentLegacyBody(reason, description, place)
    } else {
        encodeTypedIncidentBody(type, reason, description, place)
    }

    override fun driverIncidentEvidenceAttachBody(evidenceId: String): String = JsonObject(
        mapOf("evidenceObjectIds" to JsonArray(listOf(JsonPrimitive(evidenceId))))
    ).toString()

    override fun isValid(command: DriverIncidentCommand): Boolean =
        command.frozenBody == driverIncidentBody(
            command.type,
            command.reason,
            command.description,
            command.place
        )

    override fun driverDeliveryInstructionAcknowledgementBody(
        instructionIds: Collection<String>
    ): String = encodeInstructionAcknowledgementBody(instructionIds)

    override fun isValid(command: DriverDeliveryInstructionAcknowledgementCommand): Boolean =
        command.frozenBody ==
            driverDeliveryInstructionAcknowledgementBody(command.instructionVersions.keys)

    override fun driverDeliveryOperationalExceptionResolutionBody(value: String): String =
        encodeOperationalExceptionBody(value)

    override fun driverDeliveryOperationalExceptionResolutionFromBody(body: String): String? =
        decodeOperationalExceptionBody(body)

    override fun driverDeliveryOperationalExceptionEmptyBody(): String =
        encodeOperationalExceptionEmptyBody()

    override fun isValid(command: DriverDeliveryOperationalExceptionCommand): Boolean =
        when (command.action) {
            DriverDeliveryOperationalExceptionAction.Claim,
            DriverDeliveryOperationalExceptionAction.Review ->
                command.frozenBody == DRIVER_OPERATIONAL_EXCEPTION_EMPTY_BODY

            DriverDeliveryOperationalExceptionAction.ResolveWarning ->
                driverDeliveryOperationalExceptionResolutionFromBody(command.frozenBody) != null

            DriverDeliveryOperationalExceptionAction.CloseWarning -> command.frozenBody.isEmpty()
        }

    override fun driverExecutionTemperatureBody(
        command: DriverExecutionTemperatureCommand
    ): String = when (command) {
        is DriverExecutionTemperatureCommand.Reading -> driverExecutionTemperatureReadingBody(
            command.fulfillmentLineId,
            command.skuId,
            command.affectedQuantity,
            command.valueCelsius,
            command.occurredAt,
            command.sourceIncidentId,
            command.evidenceObjectId
        )

        is DriverExecutionTemperatureCommand.Disposition ->
            driverExecutionTemperatureDispositionBody(command.disposition, command.reason)
    }

    override fun driverExecutionTemperatureReadingBody(
        fulfillmentLineId: String,
        skuId: String,
        affectedQuantity: BigDecimal,
        valueCelsius: BigDecimal,
        occurredAt: Instant,
        sourceIncidentId: String?,
        evidenceObjectId: String?
    ): String = encodeTemperatureReadingBody(
        fulfillmentLineId,
        skuId,
        affectedQuantity,
        valueCelsius,
        occurredAt,
        sourceIncidentId,
        evidenceObjectId
    )

    override fun driverExecutionTemperatureDispositionBody(
        disposition: DriverExecutionTemperatureDisposition,
        reason: String
    ): String = encodeTemperatureDispositionBody(disposition, reason)

    override fun isValid(command: DriverExecutionTemperatureCommand): Boolean =
        command.isValid() && command.frozenBody == driverExecutionTemperatureBody(command)

    override fun driverHandoffIssueBody(attemptId: String): String =
        encodeHandoffIssueBody(attemptId)

    override fun isValid(command: DriverHandoffIssueCommand): Boolean =
        command.frozenBody == driverHandoffIssueBody(command.attemptId)

    override fun isValid(command: DriverProofCreateCommand): Boolean =
        command.expectedVersion >= 0 && command.idempotencyKey.isNotBlank() &&
            command.idempotencyKey.length <= 160 && command.receiverName.isNotBlank() &&
            runCatching {
                val body = Json.parseToJsonElement(command.frozenBody).jsonObject
                body.keys == setOf("receiverName", "capturedAt", "notes") &&
                    body["receiverName"]?.jsonPrimitive?.contentOrNull == command.receiverName &&
                    body["capturedAt"]?.jsonPrimitive?.contentOrNull == command.capturedAt &&
                    (
                        body["notes"] == JsonNull ||
                            body["notes"]?.jsonPrimitive?.contentOrNull != null
                        )
            }.getOrDefault(false)

    override fun isValid(command: DriverProofAttachCommand): Boolean =
        command.expectedVersion >= 0 && command.idempotencyKey.isNotBlank() &&
            command.idempotencyKey.length <= 160 && command.frozenBody == driverProofAttachBody(
                command.evidenceKind,
                command.evidenceObjectId
            )

    override fun isValid(intent: DriverProofIntentMetadata): Boolean {
        val evidenceKind = intent.evidenceKind
        val evidenceId = intent.evidenceId
        return intent.createExpectedVersion >= 0 && intent.createIdempotencyKey.isNotBlank() &&
            intent.createIdempotencyKey.length <= 160 &&
            intent.createBody == driverProofCreateBody(
                intent.receiverName,
                intent.capturedAt,
                intent.notes
            ) && (
                intent.attachBody == null ||
                    (
                        intent.attachExpectedVersion != null && intent.attachKey != null &&
                            evidenceKind != null && evidenceId != null &&
                            intent.attachBody == driverProofAttachBody(evidenceKind, evidenceId)
                        )
                )
    }

    override fun driverOutcomeBody(
        outcome: DriverOutcomeKind,
        reason: String?,
        notes: String?,
        attemptedAt: String,
        lines: List<DriverOutcomeLineDecision>
    ): String {
        val lineBody = lines.joinToString(prefix = "[", postfix = "]") { line ->
            "{" +
                "\"fulfillmentLineId\":${quoteJson(line.fulfillmentLineId)}," +
                "\"skuId\":${quoteJson(line.skuId)}," +
                "\"attemptedQuantity\":${line.attemptedQuantity.toPlainString()}," +
                "\"deliveredQuantity\":${line.deliveredQuantity.toPlainString()}," +
                "\"rejectedQuantity\":${line.rejectedQuantity.toPlainString()}," +
                "\"cancelledQuantity\":${line.cancelledQuantity.toPlainString()}," +
                "\"unit\":${quoteJson(line.unit)}" +
                "}"
        }
        return "{" +
            "\"outcome\":${quoteJson(outcome.name)}," +
            "\"failureReason\":${reason?.let(::quoteJson) ?: "null"}," +
            "\"notes\":${notes?.let(::quoteJson) ?: "null"}," +
            "\"attemptedAt\":${quoteJson(attemptedAt)}," +
            "\"lines\":$lineBody" +
            "}"
    }

    override fun driverProofCreateBody(
        receiverName: String,
        capturedAt: String,
        notes: String?
    ): String = "{" +
        "\"receiverName\":${quoteJson(receiverName)}," +
        "\"capturedAt\":${quoteJson(capturedAt)}," +
        "\"notes\":${notes?.let(::quoteJson) ?: "null"}" +
        "}"

    override fun driverProofAttachBody(kind: DriverProofEvidenceKind, evidenceId: String): String =
        "{" +
            "\"kind\":${quoteJson(kind.name)}," +
            "\"evidenceObjectId\":${quoteJson(evidenceId)}" +
            "}"

    override fun driverArrivalBody(): String = "{}"

    override fun isValid(
        command:
        com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverArrivalCommand
    ): Boolean = command.frozenBody == driverArrivalBody()

    override fun isValid(
        command:
        com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverOutcomeCommand
    ): Boolean = command.frozenBody == driverOutcomeBody(
        command.outcome,
        command.failureReason,
        command.notes,
        command.attemptedAt,
        command.lines
    )

    private fun quoteJson(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")

                '\\' -> append("\\\\")

                '\b' -> append("\\b")

                '\u000C' -> append("\\f")

                '\n' -> append("\\n")

                '\r' -> append("\\r")

                '\t' -> append("\\t")

                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
