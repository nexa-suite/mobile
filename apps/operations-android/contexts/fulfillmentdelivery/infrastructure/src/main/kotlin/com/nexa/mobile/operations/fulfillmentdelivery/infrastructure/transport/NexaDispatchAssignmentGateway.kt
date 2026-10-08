package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

import com.nexa.mobile.operations.core.network.ClientFailure
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val DISPATCH_ASSIGNEES_PATH = "/api/v1/dispatch-assignees"
private const val FULFILLMENTS_PATH = "/api/v1/fulfillments"
private val dispatchAssignmentJson = Json { ignoreUnknownKeys = true }
private val dispatchAssignmentUuid =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class DispatchDriverCandidateProjection(
    val membershipId: String,
    val email: String,
    val displayName: String
) {
    override fun toString(): String = "DispatchDriverCandidateProjection(membershipId=REDACTED)"
}

data class DispatchDriverAssignmentProjection(
    val id: String,
    val fulfillmentId: String,
    val fulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val responsibleMembershipId: String,
    val responsibleDisplayName: String,
    val assignedAt: Instant,
    val deliveryId: String?,
    val plannedDispatchAt: Instant? = null,
    val current: Boolean = true
) {
    override fun toString(): String = "DispatchDriverAssignmentProjection(id=REDACTED, " +
        "fulfillmentVersion=$fulfillmentVersion, deliveryLinked=${deliveryId != null})"
}

data class DispatchDriverAssignmentRequest(
    val fulfillmentId: String,
    val expectedFulfillmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val responsibleMembershipId: String
)

data class DispatchPlanChangeRequest(
    val fulfillmentId: String,
    val expectedFulfillmentVersion: Long,
    val expectedAssignmentId: String,
    val expectedAssignmentVersion: Long,
    val physicalAllocationId: String,
    val physicalAllocationVersion: Long,
    val resultResponsibleMembershipId: String,
    val resultPlannedDispatchAt: Instant?,
    val requestBody: String,
    val idempotencyKey: String
)

sealed interface DispatchAssignmentNetworkOutcome {
    data class Candidates(val items: List<DispatchDriverCandidateProjection>) :
        DispatchAssignmentNetworkOutcome

    data class Current(val item: DispatchDriverAssignmentProjection?) :
        DispatchAssignmentNetworkOutcome

    data class Assigned(val item: DispatchDriverAssignmentProjection) :
        DispatchAssignmentNetworkOutcome

    data object NetworkUnavailable : DispatchAssignmentNetworkOutcome
    data object UnknownOutcome : DispatchAssignmentNetworkOutcome
    data object ServiceUnavailable : DispatchAssignmentNetworkOutcome
    data object PermissionDenied : DispatchAssignmentNetworkOutcome
    data object ContextInvalidated : DispatchAssignmentNetworkOutcome
    data object SessionInvalidated : DispatchAssignmentNetworkOutcome
    data object Stale : DispatchAssignmentNetworkOutcome
    data object Conflict : DispatchAssignmentNetworkOutcome
}

sealed interface DispatchPlanChangeNetworkOutcome {
    data class History(val items: List<DispatchDriverAssignmentProjection>) :
        DispatchPlanChangeNetworkOutcome

    data class Changed(val item: DispatchDriverAssignmentProjection) :
        DispatchPlanChangeNetworkOutcome

    data object NetworkUnavailable : DispatchPlanChangeNetworkOutcome
    data object UnknownOutcome : DispatchPlanChangeNetworkOutcome
    data object ServiceUnavailable : DispatchPlanChangeNetworkOutcome
    data object PermissionDenied : DispatchPlanChangeNetworkOutcome
    data object ContextInvalidated : DispatchPlanChangeNetworkOutcome
    data object SessionInvalidated : DispatchPlanChangeNetworkOutcome
    data object Stale : DispatchPlanChangeNetworkOutcome
    data object Conflict : DispatchPlanChangeNetworkOutcome
}

/** Protected current driver and assignment reads plus the prepared-Fulfillment assignment command. */
class NexaDispatchAssignmentGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun candidates(): DispatchAssignmentNetworkOutcome {
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(ProtectedMethod.GET, DISPATCH_ASSIGNEES_PATH)
            )
        ) {
            is ProtectedResult.Failure -> result.error.toDispatchAssignmentOutcome(mutation = false)

            is ProtectedResult.Success -> {
                val items = result.body.decode<List<DispatchDriverCandidateWire>>()
                    ?.map {
                        it.toProjection()
                            ?: return DispatchAssignmentNetworkOutcome.ServiceUnavailable
                    }
                    ?: return DispatchAssignmentNetworkOutcome.ServiceUnavailable
                if (items.map { it.membershipId.lowercase(Locale.ROOT) }.distinct().size !=
                    items.size
                ) {
                    DispatchAssignmentNetworkOutcome.ServiceUnavailable
                } else {
                    DispatchAssignmentNetworkOutcome.Candidates(items)
                }
            }
        }
    }

    suspend fun current(fulfillmentId: String): DispatchAssignmentNetworkOutcome {
        if (!fulfillmentId.isUuid()) return DispatchAssignmentNetworkOutcome.ServiceUnavailable
        val path = assignmentPath(fulfillmentId)
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(ProtectedMethod.GET, path)
            )
        ) {
            is ProtectedResult.Failure -> result.error.toDispatchAssignmentOutcome(mutation = false)

            is ProtectedResult.Success -> when (result.status) {
                204 -> DispatchAssignmentNetworkOutcome.Current(null)

                200 -> {
                    val item = result.body.decode<DispatchDriverAssignmentWire>()?.toProjection()
                        ?: return DispatchAssignmentNetworkOutcome.ServiceUnavailable
                    if (!item.fulfillmentId.equals(fulfillmentId, ignoreCase = true) ||
                        result.etag.toVersion() != item.fulfillmentVersion
                    ) {
                        DispatchAssignmentNetworkOutcome.ServiceUnavailable
                    } else {
                        DispatchAssignmentNetworkOutcome.Current(item)
                    }
                }

                else -> DispatchAssignmentNetworkOutcome.ServiceUnavailable
            }
        }
    }

    suspend fun history(fulfillmentId: String): DispatchPlanChangeNetworkOutcome {
        if (!fulfillmentId.isUuid()) return DispatchPlanChangeNetworkOutcome.ServiceUnavailable
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(ProtectedMethod.GET, "${assignmentPath(fulfillmentId)}/history")
            )
        ) {
            is ProtectedResult.Failure -> result.error.toPlanChangeOutcome(mutation = false)

            is ProtectedResult.Success -> {
                val items = result.body.decode<List<DispatchDriverAssignmentWire>>()
                    ?.map {
                        it.toProjection()
                            ?: return DispatchPlanChangeNetworkOutcome.ServiceUnavailable
                    }
                    ?: return DispatchPlanChangeNetworkOutcome.ServiceUnavailable
                if (items.any { !it.fulfillmentId.equals(fulfillmentId, ignoreCase = true) } ||
                    items.map { it.id.lowercase(Locale.ROOT) }.distinct().size != items.size ||
                    items.count { it.current } > 1 ||
                    (items.isNotEmpty() && items.none { it.current })
                ) {
                    DispatchPlanChangeNetworkOutcome.ServiceUnavailable
                } else {
                    DispatchPlanChangeNetworkOutcome.History(items)
                }
            }
        }
    }

    suspend fun changePlan(request: DispatchPlanChangeRequest): DispatchPlanChangeNetworkOutcome {
        if (!request.isValid()) return DispatchPlanChangeNetworkOutcome.ServiceUnavailable
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = "${assignmentPath(request.fulfillmentId)}/plan-changes",
                    payload = request.requestBody,
                    idempotencyKey = request.idempotencyKey,
                    ifMatch = "\"${request.expectedFulfillmentVersion}\""
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toPlanChangeOutcome(mutation = true)

            is ProtectedResult.Success -> {
                val item = result.body.decode<DispatchDriverAssignmentWire>()?.toProjection()
                    ?: return DispatchPlanChangeNetworkOutcome.UnknownOutcome
                if (!item.fulfillmentId.equals(request.fulfillmentId, ignoreCase = true) ||
                    item.fulfillmentVersion != request.expectedFulfillmentVersion + 1 ||
                    !item.physicalAllocationId.equals(
                        request.physicalAllocationId,
                        ignoreCase = true
                    ) ||
                    item.physicalAllocationVersion != request.physicalAllocationVersion ||
                    !item.responsibleMembershipId.equals(
                        request.resultResponsibleMembershipId,
                        ignoreCase = true
                    ) || item.plannedDispatchAt != request.resultPlannedDispatchAt ||
                    result.etag.toVersion() != item.fulfillmentVersion
                ) {
                    DispatchPlanChangeNetworkOutcome.UnknownOutcome
                } else {
                    DispatchPlanChangeNetworkOutcome.Changed(item)
                }
            }
        }
    }

    suspend fun assign(
        request: DispatchDriverAssignmentRequest,
        idempotencyKey: String
    ): DispatchAssignmentNetworkOutcome {
        if (!request.isValid() || idempotencyKey.isBlank() || idempotencyKey.length > 160) {
            return DispatchAssignmentNetworkOutcome.ServiceUnavailable
        }
        val body = buildJsonObject {
            put("responsibleMembershipId", request.responsibleMembershipId)
            put("physicalAllocationId", request.physicalAllocationId)
            put("physicalAllocationVersion", request.physicalAllocationVersion)
        }.toString()
        val result = protectedCalls.execute(
            ProtectedRequest(
                method = ProtectedMethod.POST,
                path = assignmentPath(request.fulfillmentId),
                payload = body,
                idempotencyKey = idempotencyKey,
                ifMatch = "\"${request.expectedFulfillmentVersion}\""
            )
        )
        return when (result) {
            is ProtectedResult.Failure -> result.error.toDispatchAssignmentOutcome(mutation = true)

            is ProtectedResult.Success -> {
                val item = result.body.decode<DispatchDriverAssignmentWire>()?.toProjection()
                    ?: return DispatchAssignmentNetworkOutcome.UnknownOutcome
                if (!item.fulfillmentId.equals(request.fulfillmentId, ignoreCase = true) ||
                    item.fulfillmentVersion != request.expectedFulfillmentVersion + 1 ||
                    !item.physicalAllocationId.equals(
                        request.physicalAllocationId,
                        ignoreCase = true
                    ) ||
                    item.physicalAllocationVersion != request.physicalAllocationVersion ||
                    !item.responsibleMembershipId.equals(
                        request.responsibleMembershipId,
                        ignoreCase = true
                    ) ||
                    result.etag.toVersion() != item.fulfillmentVersion
                ) {
                    DispatchAssignmentNetworkOutcome.UnknownOutcome
                } else {
                    DispatchAssignmentNetworkOutcome.Assigned(item)
                }
            }
        }
    }

    private fun DispatchDriverCandidateWire.toProjection(): DispatchDriverCandidateProjection? {
        val membership = id?.takeIf { it.isUuid() } ?: return null
        val safeEmail = email?.takeIf(String::isNotBlank) ?: return null
        val safeName = displayName?.takeIf(String::isNotBlank) ?: return null
        return DispatchDriverCandidateProjection(membership, safeEmail, safeName)
    }

    private fun DispatchDriverAssignmentWire.toProjection(): DispatchDriverAssignmentProjection? {
        val safeId = id?.takeIf { it.isUuid() } ?: return null
        val safeFulfillment = fulfillmentId?.takeIf { it.isUuid() } ?: return null
        val safeFulfillmentVersion = fulfillmentVersion?.takeIf { it >= 0 } ?: return null
        val safeAllocation = physicalAllocationId?.takeIf { it.isUuid() } ?: return null
        val safeAllocationVersion = physicalAllocationVersion?.takeIf { it >= 0 } ?: return null
        val safeMembership = responsibleMembershipId?.takeIf { it.isUuid() } ?: return null
        val safeName = responsibleDisplayName?.takeIf(String::isNotBlank) ?: return null
        val safeAssignedAt =
            assignedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val safePlannedDispatchAt = plannedDispatchAt?.let {
            runCatching { Instant.parse(it) }.getOrNull() ?: return null
        }
        val safeDeliveryId = deliveryId?.takeIf(String::isNotBlank)
        if (deliveryId != null && safeDeliveryId?.isUuid() != true) return null
        return DispatchDriverAssignmentProjection(
            safeId,
            safeFulfillment,
            safeFulfillmentVersion,
            safeAllocation,
            safeAllocationVersion,
            safeMembership,
            safeName,
            safeAssignedAt,
            safeDeliveryId,
            safePlannedDispatchAt,
            current
        )
    }

    private fun DispatchDriverAssignmentRequest.isValid(): Boolean =
        fulfillmentId.isUuid() && expectedFulfillmentVersion >= 0 &&
            physicalAllocationId.isUuid() && physicalAllocationVersion >= 0 &&
            responsibleMembershipId.isUuid()

    private fun DispatchPlanChangeRequest.isValid(): Boolean =
        fulfillmentId.isUuid() && expectedFulfillmentVersion >= 0 &&
            expectedAssignmentId.isUuid() && expectedAssignmentVersion >= 0 &&
            physicalAllocationId.isUuid() && physicalAllocationVersion >= 0 &&
            resultResponsibleMembershipId.isUuid() && requestBody.isNotBlank() &&
            idempotencyKey.isNotBlank() && idempotencyKey.length <= 160

    private fun String.isUuid(): Boolean = dispatchAssignmentUuid.matches(this)

    private fun assignmentPath(fulfillmentId: String): String {
        val encodedId = URLEncoder.encode(fulfillmentId, StandardCharsets.UTF_8.name())
        return "$FULFILLMENTS_PATH/$encodedId/driver-assignments"
    }

    private fun String?.toVersion(): Long? =
        this?.removeSurrounding("\"")?.toLongOrNull()?.takeIf { it >= 0 }

    private inline fun <reified T> String?.decode(): T? = try {
        this?.let { dispatchAssignmentJson.decodeFromString<T>(it) }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun ClientFailure.toDispatchAssignmentOutcome(
        mutation: Boolean
    ): DispatchAssignmentNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired ->
            DispatchAssignmentNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            DispatchAssignmentNetworkOutcome.ContextInvalidated

        httpStatus == 403 || httpStatus == 404 || kind == FailureKind.AuthorizationFailure ||
            kind == FailureKind.ResourceUnavailable ->
            DispatchAssignmentNetworkOutcome.PermissionDenied

        httpStatus == 412 || httpStatus == 428 || kind == FailureKind.StaleState ||
            kind == FailureKind.PreconditionRequired -> DispatchAssignmentNetworkOutcome.Stale

        httpStatus == 409 || kind == FailureKind.BusinessConflict ->
            DispatchAssignmentNetworkOutcome.Conflict

        mutation && kind == FailureKind.UnknownOutcome ->
            DispatchAssignmentNetworkOutcome.UnknownOutcome

        kind == FailureKind.NetworkUnavailable || kind == FailureKind.Timeout ->
            DispatchAssignmentNetworkOutcome.NetworkUnavailable

        else -> DispatchAssignmentNetworkOutcome.ServiceUnavailable
    }

    private fun ClientFailure.toPlanChangeOutcome(
        mutation: Boolean
    ): DispatchPlanChangeNetworkOutcome = when (toDispatchAssignmentOutcome(mutation)) {
        DispatchAssignmentNetworkOutcome.NetworkUnavailable ->
            DispatchPlanChangeNetworkOutcome.NetworkUnavailable

        DispatchAssignmentNetworkOutcome.UnknownOutcome ->
            DispatchPlanChangeNetworkOutcome.UnknownOutcome

        DispatchAssignmentNetworkOutcome.ServiceUnavailable ->
            DispatchPlanChangeNetworkOutcome.ServiceUnavailable

        DispatchAssignmentNetworkOutcome.PermissionDenied ->
            DispatchPlanChangeNetworkOutcome.PermissionDenied

        DispatchAssignmentNetworkOutcome.ContextInvalidated ->
            DispatchPlanChangeNetworkOutcome.ContextInvalidated

        DispatchAssignmentNetworkOutcome.SessionInvalidated ->
            DispatchPlanChangeNetworkOutcome.SessionInvalidated

        DispatchAssignmentNetworkOutcome.Stale -> DispatchPlanChangeNetworkOutcome.Stale

        DispatchAssignmentNetworkOutcome.Conflict -> DispatchPlanChangeNetworkOutcome.Conflict

        is DispatchAssignmentNetworkOutcome.Candidates,
        is DispatchAssignmentNetworkOutcome.Current,
        is DispatchAssignmentNetworkOutcome.Assigned -> if (mutation) {
            DispatchPlanChangeNetworkOutcome.UnknownOutcome
        } else {
            DispatchPlanChangeNetworkOutcome.ServiceUnavailable
        }
    }

    private companion object {
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
    }
}

@kotlinx.serialization.Serializable
private data class DispatchDriverCandidateWire(
    val id: String? = null,
    val email: String? = null,
    val displayName: String? = null
)

@kotlinx.serialization.Serializable
private data class DispatchDriverAssignmentWire(
    val id: String? = null,
    val fulfillmentId: String? = null,
    val fulfillmentVersion: Long? = null,
    val physicalAllocationId: String? = null,
    val physicalAllocationVersion: Long? = null,
    val responsibleMembershipId: String? = null,
    val responsibleDisplayName: String? = null,
    val assignedAt: String? = null,
    val plannedDispatchAt: String? = null,
    val deliveryId: String? = null,
    val current: Boolean = true
)
