package com.nexa.mobile.operations.core.network

import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

private const val OUTGOING_FULFILLMENTS = "/api/v1/fulfillments"
private val outgoingCheckJson = Json { ignoreUnknownKeys = true }
private val outgoingUuid = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class PhysicalAllocationLineProjection(
    val id: String,
    val skuId: String,
    val catalogItemId: String,
    val lotId: String?,
    val quantity: BigDecimal,
    val releasedQuantity: BigDecimal,
    val consumedQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val unit: String
)

data class PhysicalAllocationProjection(
    val id: String,
    val status: String,
    val version: Long,
    val asOf: Instant,
    val lines: List<PhysicalAllocationLineProjection>
)

data class OutgoingGoodsCheckLineProjection(
    val physicalAllocationLineId: String,
    val skuId: String,
    val expectedLotId: String?,
    val observedLotId: String?,
    val expectedQuantity: BigDecimal,
    val observedQuantity: BigDecimal,
    val unit: String,
    val matches: Boolean
)

data class OutgoingGoodsDiscrepancyProjection(
    val id: String,
    val fulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val checkedByMembershipId: String,
    val checkedAt: Instant,
    val lines: List<OutgoingGoodsCheckLineProjection>
)

data class OutgoingGoodsCheckProjection(
    val id: String,
    val fulfillmentId: String,
    val fulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val matches: Boolean,
    val current: Boolean,
    val openDiscrepancy: Boolean,
    val checkedAt: Instant,
    val lines: List<OutgoingGoodsCheckLineProjection>,
    val replayed: Boolean,
    val discrepancy: OutgoingGoodsDiscrepancyProjection? = null
)

data class OutgoingGoodsDiscrepancyResolutionCommand(
    val fulfillmentId: String,
    val expectedFulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val discrepancyCheckId: String,
    val matchingCheckId: String,
    val reason: String,
    val idempotencyKey: String,
    val exactRequestBody: String
)

data class OutgoingGoodsDiscrepancyResolutionProjection(
    val id: String,
    val fulfillmentId: String,
    val fulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val discrepancyCheckId: String,
    val matchingCheckId: String,
    val actorMembershipId: String,
    val reason: String,
    val resolvedAt: Instant,
    val current: Boolean,
    val replayed: Boolean
)

data class OutgoingGoodsObservation(
    val physicalAllocationLineId: String,
    val observedLotId: String?,
    val observedQuantity: BigDecimal
)

data class OutgoingGoodsCheckCommand(
    val fulfillmentId: String,
    val expectedFulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val observations: List<OutgoingGoodsObservation>,
    val idempotencyKey: String,
    val exactRequestBody: String? = null
)

sealed interface OutgoingGoodsCheckNetworkOutcome {
    data class Snapshot(
        val allocation: PhysicalAllocationProjection,
        val currentCheck: OutgoingGoodsCheckProjection?
    ) : OutgoingGoodsCheckNetworkOutcome

    data class Recorded(val check: OutgoingGoodsCheckProjection) : OutgoingGoodsCheckNetworkOutcome
    data class Resolved(val resolution: OutgoingGoodsDiscrepancyResolutionProjection) : OutgoingGoodsCheckNetworkOutcome
    data object NetworkUnavailable : OutgoingGoodsCheckNetworkOutcome
    data object UnknownOutcome : OutgoingGoodsCheckNetworkOutcome
    data object ServiceUnavailable : OutgoingGoodsCheckNetworkOutcome
    data object PermissionDenied : OutgoingGoodsCheckNetworkOutcome
    data object ContextInvalidated : OutgoingGoodsCheckNetworkOutcome
    data object SessionInvalidated : OutgoingGoodsCheckNetworkOutcome
    data object Stale : OutgoingGoodsCheckNetworkOutcome
    data object Conflict : OutgoingGoodsCheckNetworkOutcome
}

/** Protected current allocation and server-attributed outbound comparison routes. */
class NexaOutgoingGoodsCheckGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun snapshot(fulfillmentId: String): OutgoingGoodsCheckNetworkOutcome {
        if (!fulfillmentId.isUuid()) return OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable
        val allocationPath = fulfillmentPath(fulfillmentId, "physical-allocation")
        val allocation = when (
            val result = protectedCalls.execute(ProtectedRequest(ProtectedMethod.GET, allocationPath))
        ) {
            is ProtectedResult.Failure -> return result.error.toOutgoingOutcome(mutation = false)
            is ProtectedResult.Success -> {
                if (result.status != 200) return OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable
                val value = result.body.decode<PhysicalAllocationWire>()?.toProjection()
                    ?: return OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable
                if (result.etag.toVersion() != value.version) {
                    return OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable
                }
                value
            }
        }

        val check = when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    ProtectedMethod.GET,
                    fulfillmentPath(fulfillmentId, "outgoing-checks/current")
                )
            )
        ) {
            is ProtectedResult.Failure -> return result.error.toOutgoingOutcome(mutation = false)
            is ProtectedResult.Success -> when (result.status) {
                204 -> null
                200 -> {
                    val value = result.body.decode<OutgoingGoodsCheckWire>()?.toProjection()
                        ?: return OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable
                    if (!value.fulfillmentId.equals(fulfillmentId, ignoreCase = true) ||
                        result.etag.toVersion() != value.fulfillmentVersion
                    ) {
                        return OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable
                    }
                    value
                }
                else -> return OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable
            }
        }
        return OutgoingGoodsCheckNetworkOutcome.Snapshot(allocation, check)
    }

    suspend fun record(command: OutgoingGoodsCheckCommand): OutgoingGoodsCheckNetworkOutcome {
        if (!command.isValid()) return OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable
        val canonicalBody = command.toJson()
        if (command.exactRequestBody != null && command.exactRequestBody != canonicalBody) {
            return OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable
        }
        val body = command.exactRequestBody ?: canonicalBody
        val result = protectedCalls.execute(
            ProtectedRequest(
                method = ProtectedMethod.POST,
                path = fulfillmentPath(command.fulfillmentId, "outgoing-checks"),
                payload = body,
                idempotencyKey = command.idempotencyKey,
                ifMatch = "\"${command.expectedFulfillmentVersion}\""
            )
        )
        return when (result) {
            is ProtectedResult.Failure -> result.error.toOutgoingOutcome(mutation = true)
            is ProtectedResult.Success -> {
                if (result.status != 200 && result.status != 201) {
                    return OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable
                }
                val check = result.body.decode<OutgoingGoodsCheckWire>()?.toProjection()
                    ?: return OutgoingGoodsCheckNetworkOutcome.UnknownOutcome
                if (!check.fulfillmentId.equals(command.fulfillmentId, ignoreCase = true) ||
                    check.fulfillmentVersion != command.expectedFulfillmentVersion ||
                    !check.physicalAllocationId.equals(command.physicalAllocationId, ignoreCase = true) ||
                    check.physicalAllocationVersion != command.physicalAllocationVersion ||
                    check.lines.map { it.physicalAllocationLineId.lowercase() }.toSet() !=
                    command.observations.map { it.physicalAllocationLineId.lowercase() }.toSet() ||
                    result.etag.toVersion() != check.fulfillmentVersion
                ) {
                    OutgoingGoodsCheckNetworkOutcome.UnknownOutcome
                } else {
                    OutgoingGoodsCheckNetworkOutcome.Recorded(check)
                }
            }
        }
    }

    suspend fun resolve(command: OutgoingGoodsDiscrepancyResolutionCommand): OutgoingGoodsCheckNetworkOutcome {
        if (!command.fulfillmentId.isUuid() || command.expectedFulfillmentVersion < 0 ||
            !command.physicalAllocationId.isUuid() || command.physicalAllocationVersion < 0 ||
            !command.discrepancyCheckId.isUuid() || !command.matchingCheckId.isUuid() ||
            command.discrepancyCheckId.equals(command.matchingCheckId, ignoreCase = true) ||
            command.reason.isBlank() || command.reason.length > 1000 ||
            command.idempotencyKey.isBlank() || command.idempotencyKey.length > 160
        ) return OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable
        val canonicalBody = command.toJson()
        if (command.exactRequestBody != canonicalBody) return OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable
        val result = protectedCalls.execute(
            ProtectedRequest(
                method = ProtectedMethod.POST,
                path = fulfillmentPath(command.fulfillmentId, "outgoing-discrepancy-resolutions"),
                payload = command.exactRequestBody,
                idempotencyKey = command.idempotencyKey,
                ifMatch = "\"${command.expectedFulfillmentVersion}\""
            )
        )
        return when (result) {
            is ProtectedResult.Failure -> result.error.toOutgoingOutcome(mutation = true)
            is ProtectedResult.Success -> {
                if (result.status != 200 && result.status != 201) return OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable
                val value = result.body.decode<OutgoingGoodsDiscrepancyResolutionWire>()?.toProjection()
                    ?: return OutgoingGoodsCheckNetworkOutcome.UnknownOutcome
                if (!value.fulfillmentId.equals(command.fulfillmentId, true) ||
                    value.fulfillmentVersion != command.expectedFulfillmentVersion ||
                    !value.physicalAllocationId.equals(command.physicalAllocationId, true) ||
                    value.physicalAllocationVersion != command.physicalAllocationVersion ||
                    !value.discrepancyCheckId.equals(command.discrepancyCheckId, true) ||
                    !value.matchingCheckId.equals(command.matchingCheckId, true) ||
                    result.etag.toVersion() != value.fulfillmentVersion
                ) OutgoingGoodsCheckNetworkOutcome.UnknownOutcome
                else OutgoingGoodsCheckNetworkOutcome.Resolved(value)
            }
        }
    }

    private fun PhysicalAllocationWire.toProjection(): PhysicalAllocationProjection? {
        val safeId = allocationId?.takeIf { it.isUuid() } ?: return null
        val safeStatus = status?.takeIf(String::isNotBlank) ?: return null
        val safeVersion = version?.takeIf { it >= 0 } ?: return null
        val safeAsOf = asOf?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val safeLines = lines?.map { line ->
            val id = line.physicalAllocationLineId?.takeIf { it.isUuid() } ?: return null
            val sku = line.skuId?.takeIf { it.isUuid() } ?: return null
            val catalog = line.catalogItemId?.takeIf { it.matches(Regex("(?i)CAT-[A-Z0-9-]{1,63}")) }
                ?: return null
            val lot = line.lotId
            if (lot != null && !lot.isUuid()) return null
            val quantity = line.quantity.decimalValue()?.takeIf { it.signum() >= 0 } ?: return null
            val released = line.releasedQuantity.decimalValue()?.takeIf { it.signum() >= 0 } ?: return null
            val consumed = line.consumedQuantity.decimalValue()?.takeIf { it.signum() >= 0 } ?: return null
            val remaining = line.remainingQuantity.decimalValue()?.takeIf { it.signum() >= 0 } ?: return null
            val unit = line.unit?.takeIf(String::isNotBlank) ?: return null
            if (quantity.subtract(released).subtract(consumed).compareTo(remaining) != 0 ||
                (remaining.signum() > 0 && lot == null)
            ) return null
            PhysicalAllocationLineProjection(id, sku, catalog, lot, quantity, released, consumed, remaining, unit)
        } ?: return null
        if (safeLines.isEmpty() || safeLines.map { it.id.lowercase() }.toSet().size != safeLines.size) return null
        return PhysicalAllocationProjection(safeId, safeStatus, safeVersion, safeAsOf, safeLines)
    }

    private fun OutgoingGoodsCheckWire.toProjection(): OutgoingGoodsCheckProjection? {
        val safeId = id?.takeIf { it.isUuid() } ?: return null
        val fulfillment = fulfillmentId?.takeIf { it.isUuid() } ?: return null
        val fulfillmentVersion = fulfillmentVersion?.takeIf { it >= 0 } ?: return null
        val allocation = physicalAllocationId?.takeIf { it.isUuid() } ?: return null
        val allocationVersion = physicalAllocationVersion?.takeIf { it >= 0 } ?: return null
        checkedByMembershipId?.takeIf { it.isUuid() } ?: return null
        val checked = checkedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val safeLines = lines?.map { line ->
            val lineId = line.physicalAllocationLineId?.takeIf { it.isUuid() } ?: return null
            val sku = line.skuId?.takeIf { it.isUuid() } ?: return null
            val expectedLot = line.expectedLotId
            val observedLot = line.observedLotId
            if ((expectedLot != null && !expectedLot.isUuid()) ||
                (observedLot != null && !observedLot.isUuid())
            ) return null
            val expected = line.expectedQuantity.decimalValue()?.takeIf { it.signum() >= 0 } ?: return null
            val observed = line.observedQuantity.decimalValue()?.takeIf { it.signum() >= 0 } ?: return null
            val unit = line.unit?.takeIf(String::isNotBlank) ?: return null
            OutgoingGoodsCheckLineProjection(lineId, sku, expectedLot, observedLot, expected, observed, unit,
                line.matches ?: return null)
        } ?: return null
        if (safeLines.isEmpty() || safeLines.map { it.physicalAllocationLineId.lowercase() }.toSet().size != safeLines.size) return null
        val safeDiscrepancy = discrepancy?.toProjection() ?: if (discrepancy == null) null else return null
        return OutgoingGoodsCheckProjection(
            safeId, fulfillment, fulfillmentVersion, allocation, allocationVersion,
            matches ?: return null, current ?: return null, openDiscrepancy ?: return null,
            checked, safeLines, replayed ?: false, safeDiscrepancy
        )
    }

    private fun OutgoingGoodsDiscrepancyWire.toProjection(): OutgoingGoodsDiscrepancyProjection? {
        val safeId = id?.takeIf { it.isUuid() } ?: return null
        val safeAllocation = physicalAllocationId?.takeIf { it.isUuid() } ?: return null
        val actor = checkedByMembershipId?.takeIf { it.isUuid() } ?: return null
        val checked = checkedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val safeLines = lines?.map { line ->
            val lineId = line.physicalAllocationLineId?.takeIf { it.isUuid() } ?: return null
            val sku = line.skuId?.takeIf { it.isUuid() } ?: return null
            val expectedLot = line.expectedLotId
            val observedLot = line.observedLotId
            if ((expectedLot != null && !expectedLot.isUuid()) || (observedLot != null && !observedLot.isUuid())) return null
            val expected = line.expectedQuantity.decimalValue()?.takeIf { it.signum() >= 0 } ?: return null
            val observed = line.observedQuantity.decimalValue()?.takeIf { it.signum() >= 0 } ?: return null
            OutgoingGoodsCheckLineProjection(lineId, sku, expectedLot, observedLot, expected, observed,
                line.unit?.takeIf(String::isNotBlank) ?: return null, line.matches ?: return null)
        } ?: return null
        if (safeLines.isEmpty()) return null
        return OutgoingGoodsDiscrepancyProjection(safeId, fulfillmentVersion?.takeIf { it >= 0 } ?: return null,
            safeAllocation, physicalAllocationVersion?.takeIf { it >= 0 } ?: return null, actor, checked, safeLines)
    }

    private fun OutgoingGoodsDiscrepancyResolutionWire.toProjection(): OutgoingGoodsDiscrepancyResolutionProjection? {
        val safeId = id?.takeIf { it.isUuid() } ?: return null
        val fulfillment = fulfillmentId?.takeIf { it.isUuid() } ?: return null
        val safeFulfillmentVersion = fulfillmentVersion?.takeIf { it >= 0 } ?: return null
        val allocation = physicalAllocationId?.takeIf { it.isUuid() } ?: return null
        val safeAllocationVersion = physicalAllocationVersion?.takeIf { it >= 0 } ?: return null
        val discrepancy = discrepancyCheckId?.takeIf { it.isUuid() } ?: return null
        val matching = matchingCheckId?.takeIf { it.isUuid() } ?: return null
        val actor = actorMembershipId?.takeIf { it.isUuid() } ?: return null
        val safeReason = reason?.takeIf(String::isNotBlank) ?: return null
        val at = resolvedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        return OutgoingGoodsDiscrepancyResolutionProjection(safeId, fulfillment, safeFulfillmentVersion,
            allocation, safeAllocationVersion, discrepancy, matching, actor, safeReason, at,
            current ?: return null, replayed ?: return null)
    }

    private fun OutgoingGoodsDiscrepancyResolutionCommand.toJson(): String =
        "{\"physicalAllocationId\":\"$physicalAllocationId\",\"physicalAllocationVersion\":$physicalAllocationVersion," +
            "\"discrepancyCheckId\":\"$discrepancyCheckId\",\"matchingCheckId\":\"$matchingCheckId\"," +
            "\"reason\":\"${reason.jsonEscape()}\"}"

    private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r")

    private fun OutgoingGoodsCheckCommand.toJson(): String = buildString {
        append("{\"physicalAllocationId\":\"").append(physicalAllocationId)
            .append("\",\"physicalAllocationVersion\":").append(physicalAllocationVersion)
            .append(",\"observations\":[")
        observations.forEachIndexed { index, line ->
            if (index > 0) append(',')
            append("{\"physicalAllocationLineId\":\"").append(line.physicalAllocationLineId)
                .append("\",\"observedLotId\":")
            if (line.observedLotId == null) append("null") else append('"').append(line.observedLotId).append('"')
            append(",\"observedQuantity\":").append(line.observedQuantity.toPlainString()).append('}')
        }
        append("]}")
    }

    private fun OutgoingGoodsCheckCommand.isValid(): Boolean =
        fulfillmentId.isUuid() && expectedFulfillmentVersion >= 0 && physicalAllocationId.isUuid() &&
            physicalAllocationVersion >= 0 && idempotencyKey.isNotBlank() && idempotencyKey.length <= 160 &&
            observations.isNotEmpty() && observations.all {
                it.physicalAllocationLineId.isUuid() && it.observedQuantity.signum() >= 0 &&
                    ((it.observedQuantity.signum() == 0 && it.observedLotId == null) ||
                        (it.observedQuantity.signum() > 0 && it.observedLotId?.isUuid() == true))
            } && observations.map { it.physicalAllocationLineId.lowercase() }.toSet().size == observations.size

    private fun ClientFailure.toOutgoingOutcome(mutation: Boolean): OutgoingGoodsCheckNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired -> OutgoingGoodsCheckNetworkOutcome.SessionInvalidated
        httpStatus == 403 && problemCode == "ACCESS_CONTEXT_INVALID" ->
            OutgoingGoodsCheckNetworkOutcome.ContextInvalidated
        httpStatus == 403 || httpStatus == 404 || kind == FailureKind.AuthorizationFailure ||
            kind == FailureKind.ResourceUnavailable -> OutgoingGoodsCheckNetworkOutcome.PermissionDenied
        httpStatus == 412 || httpStatus == 428 || kind == FailureKind.StaleState ||
            kind == FailureKind.PreconditionRequired -> OutgoingGoodsCheckNetworkOutcome.Stale
        httpStatus == 409 || kind == FailureKind.BusinessConflict -> OutgoingGoodsCheckNetworkOutcome.Conflict
        mutation && kind == FailureKind.UnknownOutcome -> OutgoingGoodsCheckNetworkOutcome.UnknownOutcome
        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            OutgoingGoodsCheckNetworkOutcome.NetworkUnavailable
        else -> OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable
    }

    private fun String.isUuid(): Boolean = outgoingUuid.matches(this)
    private fun String?.toVersion(): Long? = this?.removeSurrounding("\"")?.toLongOrNull()?.takeIf { it >= 0 }
    private fun fulfillmentPath(id: String, suffix: String) =
        "$OUTGOING_FULFILLMENTS/${URLEncoder.encode(id, StandardCharsets.UTF_8.name())}/$suffix"

    private fun kotlinx.serialization.json.JsonElement?.decimalValue(): BigDecimal? = try {
        when (this) {
            null, JsonNull -> null
            is JsonPrimitive -> BigDecimal(content)
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }

    private inline fun <reified T> String?.decode(): T? = try {
        this?.let { outgoingCheckJson.decodeFromString<T>(it) }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

@Serializable
private data class PhysicalAllocationWire(
    val allocationId: String? = null,
    val status: String? = null,
    val version: Long? = null,
    val asOf: String? = null,
    val lines: List<PhysicalAllocationLineWire>? = null
)

@Serializable
private data class PhysicalAllocationLineWire(
    val physicalAllocationLineId: String? = null,
    val skuId: String? = null,
    val catalogItemId: String? = null,
    val lotId: String? = null,
    val quantity: kotlinx.serialization.json.JsonElement? = null,
    val releasedQuantity: kotlinx.serialization.json.JsonElement? = null,
    val consumedQuantity: kotlinx.serialization.json.JsonElement? = null,
    val remainingQuantity: kotlinx.serialization.json.JsonElement? = null,
    val unit: String? = null
)

@Serializable
private data class OutgoingGoodsCheckWire(
    val id: String? = null,
    val fulfillmentId: String? = null,
    val fulfillmentVersion: Long? = null,
    val physicalAllocationId: String? = null,
    val physicalAllocationVersion: Long? = null,
    val matches: Boolean? = null,
    val current: Boolean? = null,
    val openDiscrepancy: Boolean? = null,
    val checkedByMembershipId: String? = null,
    val checkedAt: String? = null,
    val lines: List<OutgoingGoodsCheckLineWire>? = null,
    val replayed: Boolean? = null,
    val discrepancy: OutgoingGoodsDiscrepancyWire? = null
)

@Serializable
private data class OutgoingGoodsDiscrepancyWire(
    val id: String? = null,
    val fulfillmentVersion: Long? = null,
    val physicalAllocationId: String? = null,
    val physicalAllocationVersion: Long? = null,
    val checkedByMembershipId: String? = null,
    val checkedAt: String? = null,
    val lines: List<OutgoingGoodsCheckLineWire>? = null
)

@Serializable
private data class OutgoingGoodsDiscrepancyResolutionWire(
    val id: String? = null,
    val fulfillmentId: String? = null,
    val fulfillmentVersion: Long? = null,
    val physicalAllocationId: String? = null,
    val physicalAllocationVersion: Long? = null,
    val discrepancyCheckId: String? = null,
    val matchingCheckId: String? = null,
    val actorMembershipId: String? = null,
    val reason: String? = null,
    val resolvedAt: String? = null,
    val current: Boolean? = null,
    val replayed: Boolean? = null
)

@Serializable
private data class OutgoingGoodsCheckLineWire(
    val physicalAllocationLineId: String? = null,
    val skuId: String? = null,
    val expectedLotId: String? = null,
    val observedLotId: String? = null,
    val expectedQuantity: kotlinx.serialization.json.JsonElement? = null,
    val observedQuantity: kotlinx.serialization.json.JsonElement? = null,
    val unit: String? = null,
    val matches: Boolean? = null
)
