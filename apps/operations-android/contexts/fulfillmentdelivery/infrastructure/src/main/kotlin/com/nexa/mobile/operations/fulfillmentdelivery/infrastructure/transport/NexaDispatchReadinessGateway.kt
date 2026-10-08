package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

import com.nexa.mobile.operations.core.network.ClientFailure
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

private const val READINESS_PATH = "/api/v1/dispatch-readiness"
private const val READINESS_PAGE_SIZE = 100
private const val MAX_READINESS_PAGES = 100
private val dispatchReadinessJson = Json { ignoreUnknownKeys = true }
private val readinessUuidPattern =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val catalogIdPattern = Regex("(?i)CAT-[A-Z0-9-]{1,63}")

data class DispatchReadinessLineProjection(
    val fulfillmentLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val allocatedQuantity: BigDecimal,
    val physicallyAllocatedQuantity: BigDecimal,
    val pickedQuantity: BigDecimal,
    val evidencedPickedQuantity: BigDecimal,
    val allocationComplete: Boolean,
    val pickingComplete: Boolean,
    val evidenceComplete: Boolean
)

data class DispatchReadinessProjection(
    val subjectKind: String,
    val fulfillmentId: String,
    val fulfillmentVersion: Long,
    val fulfillmentStatus: String,
    val physicalAllocationId: String,
    val physicalAllocationStatus: String,
    val physicalAllocationVersion: Long,
    val deliveryId: String?,
    val deliveryStatus: String?,
    val deliveryVersion: Long?,
    val windowStart: Instant?,
    val windowEnd: Instant?,
    val windowSource: String?,
    val allocationComplete: Boolean,
    val pickingComplete: Boolean,
    val pickingEvidenceComplete: Boolean,
    val ready: Boolean,
    val reasons: List<String>,
    val lines: List<DispatchReadinessLineProjection>,
    val asOf: Instant
) {
    override fun toString(): String = "DispatchReadinessProjection(fulfillmentId=REDACTED, " +
        "ready=$ready, lines=${lines.size})"
}

sealed interface DispatchReadinessNetworkOutcome {
    data class ListResult(val items: List<DispatchReadinessProjection>, val asOf: Instant) :
        DispatchReadinessNetworkOutcome

    data class Detail(val item: DispatchReadinessProjection) : DispatchReadinessNetworkOutcome
    data object NetworkUnavailable : DispatchReadinessNetworkOutcome
    data object ServiceUnavailable : DispatchReadinessNetworkOutcome
    data object PermissionDenied : DispatchReadinessNetworkOutcome
    data object ContextInvalidated : DispatchReadinessNetworkOutcome
    data object SessionInvalidated : DispatchReadinessNetworkOutcome
}

/** Protected read-only adapter for current, server-evaluated dispatch readiness. */
class NexaDispatchReadinessGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun list(): DispatchReadinessNetworkOutcome {
        val collected = mutableListOf<DispatchReadinessProjection>()
        val seenIds = mutableSetOf<String>()
        var expectedTotal: Long? = null
        var lastAsOf: Instant? = null

        for (page in 0 until MAX_READINESS_PAGES) {
            val path = "$READINESS_PATH?page=$page&size=$READINESS_PAGE_SIZE"
            when (
                val result = protectedCalls.execute(
                    ProtectedRequest(ProtectedMethod.GET, path)
                )
            ) {
                is ProtectedResult.Failure -> return result.error.toReadinessOutcome()

                is ProtectedResult.Success -> {
                    val response = result.body.decode<DispatchReadinessPageWire>()
                        ?: return DispatchReadinessNetworkOutcome.ServiceUnavailable
                    val wires = response.items
                        ?: return DispatchReadinessNetworkOutcome.ServiceUnavailable
                    val responsePage = response.page
                        ?: return DispatchReadinessNetworkOutcome.ServiceUnavailable
                    val responseSize = response.size
                        ?: return DispatchReadinessNetworkOutcome.ServiceUnavailable
                    val total = response.totalItems
                        ?: return DispatchReadinessNetworkOutcome.ServiceUnavailable
                    val asOf = response.asOf.requiredText()?.parseInstant()
                        ?: return DispatchReadinessNetworkOutcome.ServiceUnavailable
                    if (responsePage != page || responseSize != READINESS_PAGE_SIZE || total < 0 ||
                        wires.size > responseSize ||
                        (expectedTotal != null && total != expectedTotal)
                    ) {
                        return DispatchReadinessNetworkOutcome.ServiceUnavailable
                    }
                    expectedTotal = total
                    lastAsOf = asOf
                    if (total > MAX_READINESS_PAGES.toLong() * READINESS_PAGE_SIZE ||
                        collected.size.toLong() + wires.size > total
                    ) {
                        return DispatchReadinessNetworkOutcome.ServiceUnavailable
                    }
                    for (wire in wires) {
                        val item = wire.toProjection()
                            ?: return DispatchReadinessNetworkOutcome.ServiceUnavailable
                        if (!seenIds.add(item.fulfillmentId.lowercase())) {
                            return DispatchReadinessNetworkOutcome.ServiceUnavailable
                        }
                        collected += item
                    }
                    if (wires.isEmpty() && collected.size.toLong() < total) {
                        return DispatchReadinessNetworkOutcome.ServiceUnavailable
                    }
                    if (collected.size.toLong() == total) {
                        return DispatchReadinessNetworkOutcome.ListResult(collected.toList(), asOf)
                    }
                }
            }
        }
        return if (expectedTotal == 0L) {
            DispatchReadinessNetworkOutcome.ListResult(emptyList(), lastAsOf ?: Instant.EPOCH)
        } else {
            DispatchReadinessNetworkOutcome.ServiceUnavailable
        }
    }

    suspend fun detail(fulfillmentId: String): DispatchReadinessNetworkOutcome {
        if (!fulfillmentId.isUuid()) return DispatchReadinessNetworkOutcome.ServiceUnavailable
        val path = "$READINESS_PATH/${URLEncoder.encode(
            fulfillmentId,
            StandardCharsets.UTF_8.name()
        )}"
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(ProtectedMethod.GET, path)
            )
        ) {
            is ProtectedResult.Failure -> result.error.toReadinessOutcome()

            is ProtectedResult.Success -> {
                val item = result.body.decode<DispatchReadinessWire>()?.toProjection()
                    ?: return DispatchReadinessNetworkOutcome.ServiceUnavailable
                if (!item.fulfillmentId.equals(fulfillmentId, ignoreCase = true)) {
                    DispatchReadinessNetworkOutcome.ServiceUnavailable
                } else {
                    DispatchReadinessNetworkOutcome.Detail(item)
                }
            }
        }
    }

    private fun DispatchReadinessWire.toProjection(): DispatchReadinessProjection? {
        val safeKind =
            subjectKind.requiredText()?.takeIf { it == PREPARED_FULFILLMENT } ?: return null
        val safeFulfillmentId = fulfillmentId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val safeFulfillmentVersion = fulfillmentVersion?.takeIf { it >= 0 } ?: return null
        val safeFulfillmentStatus = fulfillmentStatus.requiredText() ?: return null
        val safeAllocationId =
            physicalAllocationId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val safeAllocationStatus = physicalAllocationStatus.requiredText() ?: return null
        val safeAllocationVersion = physicalAllocationVersion?.takeIf { it >= 0 } ?: return null
        val safeAsOf = asOf.requiredText()?.parseInstant() ?: return null
        val safeReasons = reasons?.takeIf { values ->
            values.none(String::isBlank) && values.distinct().size == values.size
        } ?: return null
        val safeLines = lines?.map { it.toProjection() ?: return null } ?: return null
        val safeDeliveryId = deliveryId?.takeIf(String::isNotBlank)
        val safeDeliveryStatus = deliveryStatus?.takeIf(String::isNotBlank)
        val safeWindowStart = windowStart?.requiredText()?.parseInstant()
        val safeWindowEnd = windowEnd?.requiredText()?.parseInstant()
        val safeWindowSource = windowSource?.requiredText()
        if ((deliveryId == null && (deliveryStatus != null || deliveryVersion != null)) ||
            (
                deliveryId != null &&
                    (
                        safeDeliveryId?.isUuid() != true || safeDeliveryStatus == null ||
                            deliveryVersion == null || deliveryVersion < 0
                        )
                )
        ) {
            return null
        }
        if ((windowStart != null && safeWindowStart == null) ||
            (windowEnd != null && safeWindowEnd == null) ||
            safeWindowSource !in setOf(null, "COMMERCIAL", "DISPATCH_PLAN") ||
            (safeWindowSource == null && (safeWindowStart != null || safeWindowEnd != null)) ||
            (
                safeWindowSource == "COMMERCIAL" && safeWindowStart == null &&
                    safeWindowEnd == null
                ) ||
            (
                safeWindowSource == "DISPATCH_PLAN" &&
                    (safeWindowStart == null || safeWindowEnd == null)
                )
        ) {
            return null
        }
        val safeAllocationComplete = allocationComplete ?: return null
        val safePickingComplete = pickingComplete ?: return null
        val safeEvidenceComplete = pickingEvidenceComplete ?: return null
        val safeReady = ready ?: return null
        if (safeReady != safeReasons.isEmpty()) return null
        return DispatchReadinessProjection(
            subjectKind = safeKind,
            fulfillmentId = safeFulfillmentId,
            fulfillmentVersion = safeFulfillmentVersion,
            fulfillmentStatus = safeFulfillmentStatus,
            physicalAllocationId = safeAllocationId,
            physicalAllocationStatus = safeAllocationStatus,
            physicalAllocationVersion = safeAllocationVersion,
            deliveryId = safeDeliveryId,
            deliveryStatus = safeDeliveryStatus,
            deliveryVersion = deliveryVersion,
            windowStart = safeWindowStart,
            windowEnd = safeWindowEnd,
            windowSource = safeWindowSource,
            allocationComplete = safeAllocationComplete,
            pickingComplete = safePickingComplete,
            pickingEvidenceComplete = safeEvidenceComplete,
            ready = safeReady,
            reasons = safeReasons.toList(),
            lines = safeLines,
            asOf = safeAsOf
        )
    }

    private fun DispatchReadinessLineWire.toProjection(): DispatchReadinessLineProjection? {
        val safeLineId = fulfillmentLineId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val safeSkuId = skuId.requiredText()?.takeIf { it.isUuid() } ?: return null
        val safeCatalogId =
            catalogItemId.requiredText()?.takeIf(catalogIdPattern::matches) ?: return null
        val allocated = allocatedQuantity.decimalValue()?.takeIf { it.signum() >= 0 } ?: return null
        val physicallyAllocated = physicallyAllocatedQuantity.decimalValue()
            ?.takeIf { it.signum() >= 0 } ?: return null
        val picked = pickedQuantity.decimalValue()?.takeIf { it.signum() >= 0 } ?: return null
        val evidencedPicked = evidencedPickedQuantity.decimalValue()
            ?.takeIf { it.signum() >= 0 } ?: return null
        return DispatchReadinessLineProjection(
            fulfillmentLineId = safeLineId,
            skuId = safeSkuId,
            catalogItemId = safeCatalogId,
            allocatedQuantity = allocated,
            physicallyAllocatedQuantity = physicallyAllocated,
            pickedQuantity = picked,
            evidencedPickedQuantity = evidencedPicked,
            allocationComplete = allocationComplete ?: return null,
            pickingComplete = pickingComplete ?: return null,
            evidenceComplete = evidenceComplete ?: return null
        )
    }

    private fun ClientFailure.toReadinessOutcome(): DispatchReadinessNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired ->
            DispatchReadinessNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            DispatchReadinessNetworkOutcome.ContextInvalidated

        httpStatus == 404 || kind == FailureKind.AuthorizationFailure ||
            kind == FailureKind.ResourceUnavailable ->
            DispatchReadinessNetworkOutcome.PermissionDenied

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            DispatchReadinessNetworkOutcome.NetworkUnavailable

        else -> DispatchReadinessNetworkOutcome.ServiceUnavailable
    }

    private fun JsonElement?.decimalValue(): BigDecimal? = try {
        when (this) {
            null, JsonNull -> null
            is JsonPrimitive -> BigDecimal(content)
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }

    private fun String.parseInstant(): Instant? = try {
        Instant.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun String.isUuid(): Boolean = readinessUuidPattern.matches(this)

    private fun String?.requiredText(): String? = this?.takeIf(String::isNotBlank)

    private inline fun <reified T> String?.decode(): T? = try {
        this?.let { dispatchReadinessJson.decodeFromString<T>(it) }
    } catch (_: SerializationException) {
        null
    }

    private companion object {
        const val PREPARED_FULFILLMENT = "PREPARED_FULFILLMENT"
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
    }
}

@Serializable
private data class DispatchReadinessPageWire(
    val items: List<DispatchReadinessWire>? = null,
    val page: Int? = null,
    val size: Int? = null,
    val totalItems: Long? = null,
    val asOf: String? = null
)

@Serializable
private data class DispatchReadinessWire(
    val subjectKind: String? = null,
    val fulfillmentId: String? = null,
    val fulfillmentVersion: Long? = null,
    val fulfillmentStatus: String? = null,
    val physicalAllocationId: String? = null,
    val physicalAllocationStatus: String? = null,
    val physicalAllocationVersion: Long? = null,
    val deliveryId: String? = null,
    val deliveryStatus: String? = null,
    val deliveryVersion: Long? = null,
    val windowStart: String? = null,
    val windowEnd: String? = null,
    val windowSource: String? = null,
    val allocationComplete: Boolean? = null,
    val pickingComplete: Boolean? = null,
    val pickingEvidenceComplete: Boolean? = null,
    val ready: Boolean? = null,
    val reasons: List<String>? = null,
    val lines: List<DispatchReadinessLineWire>? = null,
    val asOf: String? = null
)

@Serializable
private data class DispatchReadinessLineWire(
    val fulfillmentLineId: String? = null,
    val skuId: String? = null,
    val catalogItemId: String? = null,
    val allocatedQuantity: JsonElement? = null,
    val physicallyAllocatedQuantity: JsonElement? = null,
    val pickedQuantity: JsonElement? = null,
    val evidencedPickedQuantity: JsonElement? = null,
    val allocationComplete: Boolean? = null,
    val pickingComplete: Boolean? = null,
    val evidenceComplete: Boolean? = null
)
