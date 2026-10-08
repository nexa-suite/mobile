package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.BusinessOperationalExceptionAction
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoffIdentityCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTemperatureCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DeliveryLoadCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DeliveryLoadCommandAction
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchPlanChangeIntent
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DeliveryLoad
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DeliveryLoadCompatibilityAttestation
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchDeliveryInstructionKind
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsCommandType
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DELIVERY_LOAD_STATUSES
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.MAX_LOAD_STOPS
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.PreparedFulfillmentDriverAssignment
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.isValidOpaqueIdentifier
import java.time.Instant
import kotlinx.serialization.json.Json
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.businessOperationalExceptionRequestBody as encodeBusinessExceptionBody
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.dispatchDeliveryInstructionRequestBody as encodeInstructionBody
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.deliveryLoadCreationBody as encodeLoadCreationBody
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.dispatchWindowPlanBody as encodeWindowPlanBody
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.deliveryLoadAssignBody as encodeLoadAssignBody
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.deliveryLoadReorderBody as encodeLoadReorderBody
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.dispatchHandoffIssueBody as encodeHandoffIssueBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

class JsonDispatchRequestBodyCodec @Inject constructor() : DispatchRequestBodyCodec {
    override fun businessOperationalExceptionRequestBody(
        action: BusinessOperationalExceptionAction,
        responsibleMembershipId: String?,
        followUpNote: String?,
        reason: String?
    ): String = encodeBusinessExceptionBody(
        action,
        responsibleMembershipId,
        followUpNote,
        reason
    )

    override fun isValid(command: BusinessOperationalExceptionCommand): Boolean =
        command.frozenBody.length <= 8_000 && runCatching {
            Json.parseToJsonElement(command.frozenBody) is JsonObject
        }.getOrDefault(false)

    override fun dispatchDeliveryInstructionRequestBody(
        instructionId: String?,
        kind: DispatchDeliveryInstructionKind,
        content: String
    ): String = encodeInstructionBody(instructionId, kind, content)

    override fun isValid(intent: DispatchDeliveryInstructionIntent): Boolean =
        intent.expectedDeliveryVersion >= 0 && intent.idempotencyKey.isNotBlank() &&
            intent.idempotencyKey.length <= 160 && runCatching {
            intent.exactRequestBody == dispatchDeliveryInstructionRequestBody(
                intent.instructionId,
                intent.kind,
                intent.content
            )
        }.getOrDefault(false)

    override fun deliveryLoadCreationBody(
        readiness: List<DispatchReadiness>,
        stopOrder: List<String>,
        reason: String,
        attestation: DeliveryLoadCompatibilityAttestation
    ): String = encodeLoadCreationBody(readiness, stopOrder, reason, attestation)

    override fun dispatchWindowPlanBody(
        windowStart: String,
        windowEnd: String,
        reason: String
    ): String = encodeWindowPlanBody(windowStart, windowEnd, reason)

    override fun deliveryLoadAssignBody(driverMembershipId: String): String =
        encodeLoadAssignBody(driverMembershipId)

    override fun deliveryLoadReorderBody(stopOrder: List<String>, reason: String): String =
        encodeLoadReorderBody(stopOrder, reason)

    override fun isValid(command: DeliveryLoadCommand): Boolean {
        if (command.action in setOf(
                DeliveryLoadCommandAction.OFFER,
                DeliveryLoadCommandAction.CONFIRM_HANDOFF,
                DeliveryLoadCommandAction.ACCEPT
            )
        ) {
            return command.frozenBody == null
        }
        val body = runCatching {
            Json.parseToJsonElement(command.frozenBody ?: "").jsonObject
        }.getOrNull() ?: return false
        return try {
            when (command.action) {
            DeliveryLoadCommandAction.CREATE -> {
                val expectedKeys = setOf(
                    "fulfillmentIds",
                    "stopOrder",
                    "expectedFulfillmentVersions",
                    "reason",
                    "compatibilityAttestation"
                )
                val fulfillmentIds = body["fulfillmentIds"]?.jsonArray?.map {
                    (it as? JsonPrimitive)?.contentOrNull ?: return false
                } ?: return false
                val stopOrder = body["stopOrder"]?.jsonArray?.map {
                    (it as? JsonPrimitive)?.contentOrNull ?: return false
                } ?: return false
                val versions = body["expectedFulfillmentVersions"]?.jsonObject ?: return false
                val reason = body["reason"]?.jsonPrimitive?.contentOrNull ?: return false
                val attestation = body["compatibilityAttestation"]?.jsonObject ?: return false
                val versionValues = versions.values.map {
                    (it as? JsonPrimitive)?.longOrNull ?: return false
                }
                val booleanKeys = setOf(
                    "capacitySufficient",
                    "handlingCompatible",
                    "zoneReasonable",
                    "noExclusiveTransportRestriction"
                )
                val validAttestationKeys = booleanKeys + "observation"
                body.keys == expectedKeys && fulfillmentIds.size in 2..MAX_LOAD_STOPS &&
                    fulfillmentIds.all(::isValidOpaqueIdentifier) && stopOrder == fulfillmentIds &&
                    stopOrder.distinct().size == stopOrder.size && versions.keys == fulfillmentIds.toSet() &&
                    versionValues.all { it >= 0 } &&
                    reason.trim().isNotEmpty() && reason.trim().length <= 500 &&
                    attestation.keys.all { it in validAttestationKeys } &&
                    booleanKeys.all { attestation[it]?.jsonPrimitive?.booleanOrNull != null } &&
                    (attestation["observation"] == null ||
                        attestation["observation"]?.jsonPrimitive?.contentOrNull
                            ?.let { it.isNotBlank() && it.trim().length <= 1000 } == true)
            }

            DeliveryLoadCommandAction.REORDER -> {
                val stopOrder = body["stopOrder"]?.jsonArray?.map {
                    (it as? JsonPrimitive)?.contentOrNull ?: return false
                } ?: return false
                val reason = body["reason"]?.jsonPrimitive?.contentOrNull ?: return false
                body.keys == setOf("stopOrder", "reason") && stopOrder.size in 2..MAX_LOAD_STOPS &&
                    stopOrder.all(::isValidOpaqueIdentifier) && stopOrder.distinct().size == stopOrder.size &&
                    reason.trim().isNotEmpty() && reason.trim().length <= 500
            }

            DeliveryLoadCommandAction.ASSIGN -> {
                val membershipId = body["driverMembershipId"]?.jsonPrimitive?.contentOrNull
                body.keys == setOf("driverMembershipId") &&
                    membershipId != null && isValidOpaqueIdentifier(membershipId)
            }

            DeliveryLoadCommandAction.PLAN_WINDOW -> {
                val start = body["windowStart"]?.jsonPrimitive?.contentOrNull
                val end = body["windowEnd"]?.jsonPrimitive?.contentOrNull
                val reason = body["reason"]?.jsonPrimitive?.contentOrNull
                body.keys == setOf("windowStart", "windowEnd", "reason") &&
                    start != null && end != null &&
                    runCatching { Instant.parse(start).isBefore(Instant.parse(end)) }.getOrDefault(false) &&
                    !reason.isNullOrBlank() && reason.trim().length <= 2_000
            }

                DeliveryLoadCommandAction.OFFER,
                DeliveryLoadCommandAction.CONFIRM_HANDOFF,
                DeliveryLoadCommandAction.ACCEPT -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun matches(load: DeliveryLoad, command: DeliveryLoadCommand, membershipId: String?): Boolean {
        if (command.action == DeliveryLoadCommandAction.CREATE && command.loadId != null) return false
        if (command.loadId != null && load.id != command.loadId) return false
        val expectedVersion = command.expectedVersion
        if (expectedVersion != null && load.version < expectedVersion) return false
        return when (command.action) {
            DeliveryLoadCommandAction.CREATE -> {
                val body = command.frozenBody ?: return false
                val decoded = runCatching { Json.parseToJsonElement(body) as? JsonObject }
                    .getOrNull() ?: return false
                val expectedIds = runCatching {
                    decoded["stopOrder"]?.let { element ->
                        (element as? JsonArray)?.map { it.toString().removeSurrounding("\"") }
                    }
                }.getOrNull() ?: return false
                load.status in DELIVERY_LOAD_STATUSES &&
                    load.orderedStops.map { it.fulfillmentId } == expectedIds
            }

            DeliveryLoadCommandAction.REORDER -> {
                val decoded = runCatching {
                    Json.parseToJsonElement(command.frozenBody ?: "") as? JsonObject
                }.getOrNull() ?: return false
                val expected = (decoded["stopOrder"] as? JsonArray)
                    ?.map { it.toString().removeSurrounding("\"") } ?: return false
                load.orderedStops.map { it.fulfillmentId } == expected
            }

            DeliveryLoadCommandAction.ASSIGN -> {
                val body = runCatching {
                    Json.parseToJsonElement(command.frozenBody ?: "") as? JsonObject
                }.getOrNull() ?: return false
                val expected = body["driverMembershipId"]?.toString()
                    ?.removeSurrounding("\"") ?: return false
                load.assignedDriverMembershipId == expected && load.status in setOf(
                    "ASSIGNED",
                    "OFFERED",
                    "HANDOFF_CONFIRMED",
                    "DRIVER_ACCEPTED",
                    "RESPONSIBILITY_TRANSFERRED"
                )
            }

            DeliveryLoadCommandAction.OFFER -> load.status in setOf(
                "OFFERED",
                "HANDOFF_CONFIRMED",
                "DRIVER_ACCEPTED",
                "RESPONSIBILITY_TRANSFERRED"
            )

            DeliveryLoadCommandAction.CONFIRM_HANDOFF -> load.status in setOf(
                "HANDOFF_CONFIRMED",
                "RESPONSIBILITY_TRANSFERRED"
            )

            DeliveryLoadCommandAction.ACCEPT -> load.assignedDriverMembershipId == membershipId &&
                load.status in setOf("DRIVER_ACCEPTED", "RESPONSIBILITY_TRANSFERRED")

            DeliveryLoadCommandAction.PLAN_WINDOW -> false
        }
    }

    override fun dispatchHandoffIssueBody(assignmentId: String): String =
        encodeHandoffIssueBody(assignmentId)

    override fun isValid(command: DispatchHandoffIdentityCommand): Boolean =
        runCatching {
            command.frozenBody == dispatchHandoffIssueBody(command.assignmentId)
        }.getOrDefault(false)

    override fun dispatchHandoverRequestBody(command: DispatchHandoverCommand): String = buildString {
        append("{\"physicalAllocationId\":\"").append(command.physicalAllocationId)
            .append("\",\"physicalAllocationVersion\":").append(command.physicalAllocationVersion)
            .append(",\"driverAssignmentId\":\"").append(command.driverAssignmentId)
            .append("\",\"driverAssignmentVersion\":").append(command.driverAssignmentVersion)
            .append(",\"outgoingGoodsCheckId\":\"").append(command.outgoingGoodsCheckId).append("\"}")
    }

    override fun isValid(command: DispatchHandoverCommand): Boolean =
        command.isValid() && command.exactRequestBody == dispatchHandoverRequestBody(command)

    override fun dispatchOutgoingGoodsRequestBody(command: DispatchOutgoingGoodsCommand): String =
        if (command.type == DispatchOutgoingGoodsCommandType.ResolveDiscrepancy) {
            "{\"physicalAllocationId\":\"${command.physicalAllocationId}\"," +
                "\"physicalAllocationVersion\":${command.physicalAllocationVersion}," +
                "\"discrepancyCheckId\":\"${command.discrepancyCheckId.orEmpty()}\"," +
                "\"matchingCheckId\":\"${command.matchingCheckId.orEmpty()}\"," +
                "\"reason\":\"${command.reason.orEmpty().jsonEscape()}\"}"
        } else {
            command.observations.toRequestBody(command.physicalAllocationId, command.physicalAllocationVersion)
        }

    override fun isValid(command: DispatchOutgoingGoodsCommand): Boolean =
        command.exactRequestBody == dispatchOutgoingGoodsRequestBody(command)

    override fun dispatchOutgoingGoodsObservationsRequestBody(
        allocationId: String,
        allocationVersion: Long,
        observations: List<com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsObservation>
    ): String = observations.toRequestBody(allocationId, allocationVersion)

    override fun dispatchPlanChangeRequestBody(
        assignment: PreparedFulfillmentDriverAssignment,
        readiness: DispatchReadiness,
        membershipId: String?,
        dispatchAt: Instant?
    ): String = dispatchPlanChangeRequestBody(
        assignment.id,
        assignment.fulfillmentVersion,
        readiness.physicalAllocationId,
        readiness.physicalAllocationVersion,
        membershipId,
        dispatchAt
    )

    override fun isValid(intent: DispatchPlanChangeIntent): Boolean =
        intent.expectedFulfillmentVersion >= 0 && intent.expectedAssignmentVersion >= 0 &&
            intent.physicalAllocationVersion >= 0 && intent.idempotencyKey.isNotBlank() &&
            intent.idempotencyKey.length <= 160 && intent.requestBody == dispatchPlanChangeRequestBody(
                intent.expectedAssignmentId,
                intent.expectedAssignmentVersion,
                intent.physicalAllocationId,
                intent.physicalAllocationVersion,
                intent.requestedMembershipId,
                intent.requestedDispatchAt
            )

    private fun dispatchPlanChangeRequestBody(
        expectedAssignmentId: String,
        expectedAssignmentVersion: Long,
        physicalAllocationId: String,
        physicalAllocationVersion: Long,
        membershipId: String?,
        dispatchAt: Instant?
    ): String = buildString {
        append('{')
        append("\"expectedAssignmentId\":").append(jsonString(expectedAssignmentId)).append(',')
        append("\"expectedAssignmentVersion\":").append(expectedAssignmentVersion).append(',')
        append("\"physicalAllocationId\":").append(jsonString(physicalAllocationId)).append(',')
        append("\"physicalAllocationVersion\":").append(physicalAllocationVersion).append(',')
        append("\"responsibleMembershipId\":")
            .append(membershipId?.let(::jsonString) ?: "null").append(',')
        append("\"plannedDispatchAt\":")
            .append(dispatchAt?.toString()?.let(::jsonString) ?: "null")
        append('}')
    }

    override fun dispatchTemperatureRequestBody(command: DispatchTemperatureCommand): String {
        val common = "{\"lotId\":\"${command.lotId}\",\"value\":" +
            "${command.valueCelsius.stripTrailingZeros().toPlainString()},\"unit\":\"CELSIUS\",\"occurredAt\":\"${command.occurredAt}\""
        val lotVersion = command.expectedLotVersion ?: return "$common}"
        val evidence = command.evidenceObjectId?.let { "\"$it\"" } ?: "null"
        return "$common,\"expectedLotVersion\":$lotVersion,\"evidenceObjectId\":$evidence}"
    }

    override fun isValid(command: DispatchTemperatureCommand): Boolean =
        command.isValid() && command.exactRequestBody == dispatchTemperatureRequestBody(command)

    private fun List<com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsObservation>.toRequestBody(
        allocationId: String,
        allocationVersion: Long
    ): String {
        val observations = this
        return buildString {
            append("{\"physicalAllocationId\":\"").append(allocationId)
                .append("\",\"physicalAllocationVersion\":").append(allocationVersion)
                .append(",\"observations\":[")
            observations.forEachIndexed { index, observation ->
                if (index > 0) append(',')
                append("{\"physicalAllocationLineId\":\"").append(observation.physicalAllocationLineId)
                    .append("\",\"observedLotId\":")
                val lotId = observation.observedLotId
                if (lotId == null) append("null") else append('"').append(lotId).append('"')
                append(",\"observedQuantity\":").append(observation.observedQuantity.toPlainString()).append('}')
            }
            append("]}")
        }
    }

    private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r")

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

}
