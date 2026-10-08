package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

import com.nexa.mobile.operations.core.network.ClientFailure
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.DriverDeliveryInstructionsNetworkOutcome as InstructionsNetworkOutcome
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val DRIVER_DELIVERY_INSTRUCTIONS_PATH = "/api/v1/driver/deliveries"
private val instructionTransportJson = Json { ignoreUnknownKeys = true }
private val instructionTransportUuid =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class DriverDeliveryInstructionTransport(
    val id: String,
    val kind: String,
    val content: String,
    val instructionVersion: Long,
    val critical: Boolean,
    val acknowledged: Boolean,
    val acknowledgedAt: String?,
    val acknowledgedByMembershipId: String?,
    val sourceKind: String? = null,
    val recordedByMembershipId: String? = null,
    val recordedAt: String? = null
)

data class DriverDeliveryInstructionsTransport(
    val deliveryId: String,
    val deliveryVersion: Long,
    val instructionSetVersion: Long,
    val instructions: List<DriverDeliveryInstructionTransport>
)

data class DriverDeliveryInstructionAcknowledgementTransport(
    val instructionId: String,
    val instructionVersion: Long,
    val acknowledgedByMembershipId: String,
    val acknowledgedAt: String
)

data class DriverDeliveryInstructionAcknowledgementResponseTransport(
    val deliveryId: String,
    val instructionSetVersion: Long,
    val acknowledgements: List<DriverDeliveryInstructionAcknowledgementTransport>,
    val replayed: Boolean
)

sealed interface DriverDeliveryInstructionsNetworkOutcome {
    data class Loaded(val value: DriverDeliveryInstructionsTransport) :
        InstructionsNetworkOutcome
    data class Acknowledged(val value: DriverDeliveryInstructionAcknowledgementResponseTransport) :
        InstructionsNetworkOutcome

    data class Rejected(val code: String?) : InstructionsNetworkOutcome
    data object NotFound : InstructionsNetworkOutcome
    data object StaleVersion : InstructionsNetworkOutcome
    data object UnknownOutcome : InstructionsNetworkOutcome
    data object NetworkUnavailable : InstructionsNetworkOutcome
    data object ServiceUnavailable : InstructionsNetworkOutcome
    data object PermissionDenied : InstructionsNetworkOutcome
    data object ContextInvalidated : InstructionsNetworkOutcome
    data object SessionInvalidated : InstructionsNetworkOutcome
}

/** Protected transport for current Driver delivery instructions and explicit critical acknowledgement. */
class NexaDriverDeliveryInstructionsGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun currentInstructions(deliveryId: String): InstructionsNetworkOutcome {
        if (!instructionTransportUuid.matches(deliveryId)) {
            return InstructionsNetworkOutcome.ServiceUnavailable
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    ProtectedMethod.GET,
                    "$DRIVER_DELIVERY_INSTRUCTIONS_PATH/$deliveryId/instructions"
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toInstructionReadOutcome()

            is ProtectedResult.Success -> {
                val body = result.body.toInstructionObject()
                    ?: return InstructionsNetworkOutcome.ServiceUnavailable
                val value = body.toInstructions()
                    ?: return InstructionsNetworkOutcome.ServiceUnavailable
                if (value.deliveryId != deliveryId ||
                    result.etag.toStrongVersion() != value.instructionSetVersion
                ) {
                    InstructionsNetworkOutcome.ServiceUnavailable
                } else {
                    InstructionsNetworkOutcome.Loaded(value)
                }
            }
        }
    }

    suspend fun acknowledgeCriticalInstructions(
        deliveryId: String,
        instructionSetVersion: Long,
        instructionIds: List<String>,
        idempotencyKey: String,
        frozenBody: String
    ): InstructionsNetworkOutcome {
        if (!instructionTransportUuid.matches(deliveryId) || instructionSetVersion < 0 ||
            instructionIds.isEmpty() || instructionIds.any {
                !instructionTransportUuid.matches(it)
            } ||
            instructionIds.map(String::lowercase).distinct().size != instructionIds.size ||
            idempotencyKey.isBlank() || idempotencyKey.length > 160 ||
            !frozenAcknowledgementBodyMatches(frozenBody, instructionIds)
        ) {
            return InstructionsNetworkOutcome.ServiceUnavailable
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = "$DRIVER_DELIVERY_INSTRUCTIONS_PATH/$deliveryId/" +
                        "instruction-acknowledgements",
                    payload = frozenBody,
                    idempotencyKey = idempotencyKey,
                    ifMatch = "\"$instructionSetVersion\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toInstructionMutationOutcome()

            is ProtectedResult.Success -> {
                if (result.status !in setOf(200, 201)) {
                    return InstructionsNetworkOutcome.UnknownOutcome
                }
                val value = result.body.toInstructionAcknowledgement()
                    ?: return InstructionsNetworkOutcome.UnknownOutcome
                if (value.deliveryId != deliveryId ||
                    value.instructionSetVersion != instructionSetVersion ||
                    value.acknowledgements.map { it.instructionId.lowercase() }.toSet() !=
                    instructionIds.map(String::lowercase).toSet()
                ) {
                    InstructionsNetworkOutcome.UnknownOutcome
                } else {
                    InstructionsNetworkOutcome.Acknowledged(value)
                }
            }
        }
    }

    private fun String?.toInstructionObject(): JsonObject? = try {
        this?.let(instructionTransportJson::parseToJsonElement)?.jsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toInstructions(): DriverDeliveryInstructionsTransport? = try {
        val deliveryId =
            requiredText("deliveryId")?.takeIf(instructionTransportUuid::matches) ?: return null
        val deliveryVersion = requiredLong("deliveryVersion") ?: return null
        val instructionSetVersion = requiredLong("instructionSetVersion") ?: return null
        val rows = this["instructions"]?.jsonArray ?: return null
        val instructions = rows.map { it.jsonObject.toInstruction() ?: return null }
        if (instructions.map { it.id.lowercase() }.distinct().size != instructions.size) return null
        DriverDeliveryInstructionsTransport(
            deliveryId,
            deliveryVersion,
            instructionSetVersion,
            instructions
        )
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toInstruction(): DriverDeliveryInstructionTransport? = try {
        val id = requiredText("id")?.takeIf(instructionTransportUuid::matches) ?: return null
        val kind = requiredText("kind")?.takeIf { it in INSTRUCTION_KINDS } ?: return null
        val content = requiredText("content") ?: return null
        val instructionVersion = requiredLong("instructionVersion") ?: return null
        val critical = requiredBoolean("critical") ?: return null
        val acknowledged = requiredBoolean("acknowledged") ?: return null
        val acknowledgedAt = optionalText("acknowledgedAt")
        val acknowledgedBy = optionalText("acknowledgedByMembershipId")
        val sourceKind = optionalText("sourceKind")
        val recordedBy = optionalText("recordedByMembershipId")
        val recordedAt = optionalText("recordedAt")
        val hasSource = listOf(sourceKind, recordedBy, recordedAt).any { it != null }
        if (hasSource &&
            (
                sourceKind !in setOf(
                    "BUYER",
                    "CUSTOMER_REPORTED_BY_SALES",
                    "OPERATIONAL_DISPATCH"
                ) ||
                    recordedBy == null || !instructionTransportUuid.matches(recordedBy) ||
                    recordedAt == null || !recordedAt.isIsoInstant()
                )
        ) {
            return null
        }

        val acknowledgementFactsValid = if (acknowledged) {
            !acknowledgedAt.isNullOrBlank() && !acknowledgedBy.isNullOrBlank() &&
                acknowledgedAt.isIsoInstant()
        } else {
            this["acknowledgedAt"].isNullOrMissing() &&
                this["acknowledgedByMembershipId"].isNullOrMissing()
        }
        if (instructionVersion < 0 || critical != (kind != "NORMAL") ||
            !acknowledgementFactsValid ||
            (acknowledgedBy != null && !instructionTransportUuid.matches(acknowledgedBy))
        ) {
            return null
        }
        DriverDeliveryInstructionTransport(
            id, kind, content, instructionVersion, critical, acknowledged,
            acknowledgedAt, acknowledgedBy,
            sourceKind, recordedBy, recordedAt
        )
    } catch (_: Exception) {
        null
    }

    private fun String?.toInstructionAcknowledgement():
        DriverDeliveryInstructionAcknowledgementResponseTransport? =
        try {
            val root =
                this?.let(instructionTransportJson::parseToJsonElement)?.jsonObject ?: return null
            val deliveryId =
                root.requiredText("deliveryId")?.takeIf(instructionTransportUuid::matches)
                    ?: return null
            val version = root.requiredLong("instructionSetVersion") ?: return null
            val replayed = root.requiredBoolean("replayed") ?: return null
            val rows = root["acknowledgements"]?.jsonArray ?: return null
            val acknowledgements = rows.map { it.jsonObject.toAcknowledgement() ?: return null }
            if (version < 0 ||
                acknowledgements.map { it.instructionId.lowercase() }.distinct().size !=
                acknowledgements.size
            ) {
                return null
            }
            DriverDeliveryInstructionAcknowledgementResponseTransport(
                deliveryId,
                version,
                acknowledgements,
                replayed
            )
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.toAcknowledgement(): DriverDeliveryInstructionAcknowledgementTransport? =
        try {
            val instructionId =
                requiredText("instructionId")?.takeIf(instructionTransportUuid::matches)
                    ?: return null
            val version = requiredLong("instructionVersion") ?: return null
            val membershipId = requiredText("acknowledgedByMembershipId")
                ?.takeIf(instructionTransportUuid::matches) ?: return null
            val acknowledgedAt = requiredText("acknowledgedAt") ?: return null
            if (version < 0 || !acknowledgedAt.isIsoInstant()) return null
            DriverDeliveryInstructionAcknowledgementTransport(
                instructionId,
                version,
                membershipId,
                acknowledgedAt
            )
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.requiredText(key: String): String? = this[key]?.jsonPrimitive
        ?.takeIf(JsonPrimitive::isString)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.optionalText(key: String): String? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.contentOrNull
        else -> null
    }

    private fun kotlinx.serialization.json.JsonElement?.isNullOrMissing(): Boolean =
        this == null || this == JsonNull

    private fun String.isIsoInstant(): Boolean = try {
        Instant.parse(this)
        true
    } catch (_: Exception) {
        false
    }

    private fun JsonObject.requiredLong(key: String): Long? = this[key]?.jsonPrimitive
        ?.takeUnless(JsonPrimitive::isString)?.longOrNull

    private fun JsonObject.requiredBoolean(key: String): Boolean? = this[key]?.jsonPrimitive
        ?.takeUnless(JsonPrimitive::isString)?.booleanOrNull

    private fun String?.toStrongVersion(): Long? {
        val tag = this?.trim()?.takeUnless { it.startsWith("W/", ignoreCase = true) } ?: return null
        if (!tag.startsWith('"') || !tag.endsWith('"')) return null
        return tag.removeSurrounding("\"").toLongOrNull()?.takeIf { it >= 0 }
    }

    private fun frozenAcknowledgementBodyMatches(body: String, ids: List<String>): Boolean = try {
        val root = instructionTransportJson.parseToJsonElement(body).jsonObject
        val values = root["instructionIds"]?.jsonArray?.map { element ->
            element.jsonPrimitive.takeIf(JsonPrimitive::isString)?.contentOrNull
                ?.takeIf(instructionTransportUuid::matches) ?: return false
        } ?: return false
        root.keys == setOf("instructionIds") &&
            values.map(String::lowercase) == ids.map(String::lowercase).sorted()
    } catch (_: Exception) {
        false
    }

    private fun ClientFailure.toInstructionReadOutcome(): InstructionsNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired ->
            InstructionsNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            InstructionsNetworkOutcome.ContextInvalidated

        kind == FailureKind.AuthorizationFailure ->
            InstructionsNetworkOutcome.PermissionDenied

        kind == FailureKind.ResourceUnavailable ->
            InstructionsNetworkOutcome.NotFound

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            InstructionsNetworkOutcome.NetworkUnavailable

        else -> InstructionsNetworkOutcome.ServiceUnavailable
    }

    private fun ClientFailure.toInstructionMutationOutcome(): InstructionsNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired ->
            InstructionsNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            InstructionsNetworkOutcome.ContextInvalidated

        kind == FailureKind.AuthorizationFailure ->
            InstructionsNetworkOutcome.PermissionDenied

        kind == FailureKind.StaleState -> InstructionsNetworkOutcome.StaleVersion

        kind == FailureKind.ResourceUnavailable ->
            InstructionsNetworkOutcome.NotFound

        kind == FailureKind.ValidationFailure || kind == FailureKind.BusinessConflict ||
            kind == FailureKind.PreconditionRequired ->
            InstructionsNetworkOutcome.Rejected(
                problemCode
            )

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ||
            kind == FailureKind.UnknownOutcome || kind == FailureKind.RetryableServerFailure ->
            InstructionsNetworkOutcome.UnknownOutcome

        else -> InstructionsNetworkOutcome.ServiceUnavailable
    }

    private companion object {
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
        val INSTRUCTION_KINDS = setOf(
            "NORMAL",
            "COLD_CHAIN",
            "ACCESS_RESTRICTION",
            "SPECIAL_UNLOADING",
            "CUSTOMER_SAFETY",
            "GOODS_HANDLING"
        )
    }
}
