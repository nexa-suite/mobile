package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

import com.nexa.mobile.operations.core.network.ClientFailure
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult

import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.PublishedOperationalDeliveryInstructionTransport as DeliveryInstructionTransport
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val OPERATIONAL_DELIVERY_INSTRUCTIONS_PATH = "/api/v1/deliveries"
private val operationalInstructionJson = Json { ignoreUnknownKeys = true }
private val operationalInstructionUuid =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class OperationalDeliveryInstructionTransport(
    val id: String,
    val kind: String,
    val content: String,
    val instructionVersion: Long,
    val critical: Boolean,
    val acknowledged: Boolean,
    val acknowledgedAt: String?,
    val acknowledgedByMembershipId: String?,
    val sourceKind: String?,
    val recordedByMembershipId: String?,
    val recordedAt: String?
)

data class OperationalDeliveryInstructionsTransport(
    val deliveryId: String,
    val deliveryVersion: Long,
    val instructionSetVersion: Long,
    val instructions: List<OperationalDeliveryInstructionTransport>
)

data class PublishedOperationalDeliveryInstructionTransport(
    val deliveryId: String,
    val instructionId: String,
    val kind: String,
    val content: String,
    val instructionVersion: Long,
    val critical: Boolean,
    val deliveryVersion: Long,
    val instructionSetVersion: Long,
    val replayed: Boolean
)

sealed interface OperationalDeliveryInstructionsNetworkResult {
    data class Current(val value: OperationalDeliveryInstructionsTransport) :
        OperationalDeliveryInstructionsNetworkResult
    data class Published(val value: DeliveryInstructionTransport) :
        OperationalDeliveryInstructionsNetworkResult
    data class Rejected(val code: String?) : OperationalDeliveryInstructionsNetworkResult
    data object NotFound : OperationalDeliveryInstructionsNetworkResult
    data object StaleVersion : OperationalDeliveryInstructionsNetworkResult
    data object UnknownOutcome : OperationalDeliveryInstructionsNetworkResult
    data object NetworkUnavailable : OperationalDeliveryInstructionsNetworkResult
    data object ServiceUnavailable : OperationalDeliveryInstructionsNetworkResult
    data object PermissionDenied : OperationalDeliveryInstructionsNetworkResult
    data object ContextInvalidated : OperationalDeliveryInstructionsNetworkResult
    data object SessionInvalidated : OperationalDeliveryInstructionsNetworkResult
}

/** Protected transport for Dispatch's current instruction projection and immutable publication. */
class NexaOperationalDeliveryInstructionsGateway(
    private val protectedCalls: ProtectedCallExecutor
) {
    suspend fun currentInstructions(
        deliveryId: String
    ): OperationalDeliveryInstructionsNetworkResult {
        if (!operationalInstructionUuid.matches(deliveryId)) {
            return OperationalDeliveryInstructionsNetworkResult.ServiceUnavailable
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    ProtectedMethod.GET,
                    "$OPERATIONAL_DELIVERY_INSTRUCTIONS_PATH/$deliveryId/instructions"
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toReadOutcome()

            is ProtectedResult.Success -> {
                val body = result.body.toObject()
                    ?: return OperationalDeliveryInstructionsNetworkResult.ServiceUnavailable
                val value = body.toInstructions()
                    ?: return OperationalDeliveryInstructionsNetworkResult.ServiceUnavailable
                if (value.deliveryId != deliveryId ||
                    result.etag.toStrongVersion() != value.deliveryVersion
                ) {
                    OperationalDeliveryInstructionsNetworkResult.ServiceUnavailable
                } else {
                    OperationalDeliveryInstructionsNetworkResult.Current(value)
                }
            }
        }
    }

    suspend fun publish(
        deliveryId: String,
        expectedDeliveryVersion: Long,
        idempotencyKey: String,
        frozenBody: String
    ): OperationalDeliveryInstructionsNetworkResult {
        if (!operationalInstructionUuid.matches(deliveryId) || expectedDeliveryVersion < 0 ||
            idempotencyKey.isBlank() || idempotencyKey.length > 160 ||
            !frozenPublishBodyMatches(frozenBody)
        ) {
            return OperationalDeliveryInstructionsNetworkResult.ServiceUnavailable
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = "$OPERATIONAL_DELIVERY_INSTRUCTIONS_PATH/$deliveryId/instructions",
                    payload = frozenBody,
                    idempotencyKey = idempotencyKey,
                    ifMatch = "\"$expectedDeliveryVersion\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toPublishOutcome()

            is ProtectedResult.Success -> {
                if (result.status !in setOf(200, 201)) {
                    return OperationalDeliveryInstructionsNetworkResult.UnknownOutcome
                }
                val value = result.body.toObject()?.toPublishedInstruction()
                    ?: return OperationalDeliveryInstructionsNetworkResult.UnknownOutcome
                if (value.deliveryId != deliveryId ||
                    value.deliveryVersion != expectedDeliveryVersion + 1
                ) {
                    OperationalDeliveryInstructionsNetworkResult.UnknownOutcome
                } else {
                    OperationalDeliveryInstructionsNetworkResult.Published(value)
                }
            }
        }
    }

    private fun String?.toObject(): JsonObject? = try {
        this?.let(operationalInstructionJson::parseToJsonElement)?.jsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toInstructions(): OperationalDeliveryInstructionsTransport? = try {
        val deliveryId =
            requiredText("deliveryId")?.takeIf(operationalInstructionUuid::matches) ?: return null
        val deliveryVersion = requiredLong("deliveryVersion") ?: return null
        val instructionSetVersion = requiredLong("instructionSetVersion") ?: return null
        val rows = this["instructions"]?.jsonArray ?: return null
        val instructions = rows.map { it.jsonObject.toInstruction() ?: return null }
        if (deliveryVersion < 0 || instructionSetVersion < 0 ||
            instructions.map { it.id.lowercase() }.distinct().size != instructions.size
        ) {
            return null
        }
        OperationalDeliveryInstructionsTransport(
            deliveryId,
            deliveryVersion,
            instructionSetVersion,
            instructions
        )
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toInstruction(): OperationalDeliveryInstructionTransport? = try {
        val id = requiredText("id")?.takeIf(operationalInstructionUuid::matches) ?: return null
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
        val acknowledgementFactsValid = if (acknowledged) {
            !acknowledgedAt.isNullOrBlank() && !acknowledgedBy.isNullOrBlank() &&
                acknowledgedAt.isIsoInstant()
        } else {
            this["acknowledgedAt"].isNullOrMissing() &&
                this["acknowledgedByMembershipId"].isNullOrMissing()
        }
        if (instructionVersion < 1 || critical != (kind != "NORMAL") ||
            !acknowledgementFactsValid ||
            (acknowledgedBy != null && !operationalInstructionUuid.matches(acknowledgedBy)) ||
            (
                hasSource && (
                    sourceKind !in SOURCE_KINDS || recordedBy == null ||
                        !operationalInstructionUuid.matches(recordedBy) || recordedAt == null ||
                        !recordedAt.isIsoInstant()
                    )
                )
        ) {
            return null
        }
        OperationalDeliveryInstructionTransport(
            id, kind, content, instructionVersion, critical, acknowledged,
            acknowledgedAt, acknowledgedBy, sourceKind, recordedBy, recordedAt
        )
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toPublishedInstruction(): DeliveryInstructionTransport? = try {
        val deliveryId =
            requiredText("deliveryId")?.takeIf(operationalInstructionUuid::matches)
                ?: return null
        val instructionId =
            requiredText("instructionId")?.takeIf(operationalInstructionUuid::matches)
                ?: return null
        val kind = requiredText("kind")?.takeIf { it in INSTRUCTION_KINDS } ?: return null
        val content = requiredText("content") ?: return null
        val instructionVersion = requiredLong("instructionVersion") ?: return null
        val critical = requiredBoolean("critical") ?: return null
        val deliveryVersion = requiredLong("deliveryVersion") ?: return null
        val instructionSetVersion = requiredLong("instructionSetVersion") ?: return null
        val replayed = requiredBoolean("replayed") ?: return null
        if (instructionVersion < 1 || deliveryVersion < 1 || instructionSetVersion < 1 ||
            critical != (kind != "NORMAL")
        ) {
            return null
        }
        DeliveryInstructionTransport(
            deliveryId, instructionId, kind, content, instructionVersion, critical,
            deliveryVersion, instructionSetVersion, replayed
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

    private fun JsonObject.requiredLong(key: String): Long? = this[key]?.jsonPrimitive
        ?.takeUnless(JsonPrimitive::isString)?.longOrNull

    private fun JsonObject.requiredBoolean(key: String): Boolean? = this[key]?.jsonPrimitive
        ?.takeUnless(JsonPrimitive::isString)?.booleanOrNull

    private fun kotlinx.serialization.json.JsonElement?.isNullOrMissing(): Boolean =
        this == null || this == JsonNull

    private fun String?.toStrongVersion(): Long? {
        val tag = this?.trim()?.takeUnless { it.startsWith("W/", ignoreCase = true) } ?: return null
        if (!tag.startsWith('"') || !tag.endsWith('"')) return null
        return tag.removeSurrounding("\"").toLongOrNull()?.takeIf { it >= 0 }
    }

    private fun frozenPublishBodyMatches(body: String): Boolean = try {
        val root = operationalInstructionJson.parseToJsonElement(body).jsonObject
        val id = when (val value = root["instructionId"]) {
            null, JsonNull -> null

            is JsonPrimitive -> value.contentOrNull?.takeIf(operationalInstructionUuid::matches)
                ?: return false

            else -> return false
        }
        val kind = root.requiredText("kind")?.takeIf { it in INSTRUCTION_KINDS } ?: return false
        val content = root.requiredText("content")?.takeIf { it.length <= 2000 } ?: return false
        root.keys.all { it in setOf("instructionId", "kind", "content") } &&
            root.keys.containsAll(setOf("kind", "content")) &&
            (id != null || root["instructionId"] == null || root["instructionId"] == JsonNull) &&
            content.isNotBlank() && kind in INSTRUCTION_KINDS
    } catch (_: Exception) {
        false
    }

    private fun String.isIsoInstant(): Boolean = try {
        Instant.parse(this)
        true
    } catch (_: Exception) {
        false
    }

    private fun ClientFailure.toReadOutcome(): OperationalDeliveryInstructionsNetworkResult = when {
        kind == FailureKind.AuthenticationRequired ->
            OperationalDeliveryInstructionsNetworkResult.SessionInvalidated

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            OperationalDeliveryInstructionsNetworkResult.ContextInvalidated

        kind == FailureKind.AuthorizationFailure ->
            OperationalDeliveryInstructionsNetworkResult.PermissionDenied

        kind == FailureKind.ResourceUnavailable ->
            OperationalDeliveryInstructionsNetworkResult.NotFound

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            OperationalDeliveryInstructionsNetworkResult.NetworkUnavailable

        else -> OperationalDeliveryInstructionsNetworkResult.ServiceUnavailable
    }

    private fun ClientFailure.toPublishOutcome(): OperationalDeliveryInstructionsNetworkResult =
        when {
            kind == FailureKind.AuthenticationRequired ->
                OperationalDeliveryInstructionsNetworkResult.SessionInvalidated

            httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
                OperationalDeliveryInstructionsNetworkResult.ContextInvalidated

            kind == FailureKind.AuthorizationFailure ->
                OperationalDeliveryInstructionsNetworkResult.PermissionDenied

            kind == FailureKind.StaleState ->
                OperationalDeliveryInstructionsNetworkResult.StaleVersion

            kind == FailureKind.ResourceUnavailable ->
                OperationalDeliveryInstructionsNetworkResult.NotFound

            kind == FailureKind.ValidationFailure || kind == FailureKind.BusinessConflict ||
                kind == FailureKind.PreconditionRequired ->
                OperationalDeliveryInstructionsNetworkResult.Rejected(
                    problemCode
                )

            kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ||
                kind == FailureKind.UnknownOutcome || kind == FailureKind.RetryableServerFailure ->
                OperationalDeliveryInstructionsNetworkResult.UnknownOutcome

            else -> OperationalDeliveryInstructionsNetworkResult.ServiceUnavailable
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
        val SOURCE_KINDS = setOf("BUYER", "CUSTOMER_REPORTED_BY_SALES", "OPERATIONAL_DISPATCH")
    }
}
