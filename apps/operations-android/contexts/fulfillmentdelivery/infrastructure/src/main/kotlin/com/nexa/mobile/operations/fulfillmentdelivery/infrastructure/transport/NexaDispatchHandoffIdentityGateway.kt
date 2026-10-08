package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport

import com.nexa.mobile.operations.core.network.ClientFailure
import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val DISPATCH_HANDOFF_PURPOSE = "DISPATCH_HANDOFF"
private const val DISPATCH_HANDOFF_PATH = "/api/v1/deliveries"
private const val DISPATCH_HANDOFF_VALIDATE_PATH = "/api/v1/delivery-handoff/validations"
private const val MAX_DISPATCH_HANDOFF_TOKEN_LENGTH = 400
private val dispatchHandoffJson = Json { ignoreUnknownKeys = true }
private val dispatchHandoffUuid =
    Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class DispatchHandoffIdentityProjection(
    val handoffId: String,
    val deliveryId: String,
    val assignmentId: String,
    val deliveryVersion: Long,
    val expiresAt: String,
    val status: String,
    val token: String?
) {
    override fun toString(): String =
        "DispatchHandoffIdentityProjection(delivery=REDACTED, assignment=REDACTED, token=REDACTED)"
}

sealed interface DispatchHandoffIdentityNetworkOutcome {
    data class Identity(val value: DispatchHandoffIdentityProjection) :
        DispatchHandoffIdentityNetworkOutcome

    data class Rejected(val code: String?) : DispatchHandoffIdentityNetworkOutcome
    data object NotFound : DispatchHandoffIdentityNetworkOutcome
    data object UnknownOutcome : DispatchHandoffIdentityNetworkOutcome
    data object Unavailable : DispatchHandoffIdentityNetworkOutcome
    data object PermissionDenied : DispatchHandoffIdentityNetworkOutcome
    data object ContextInvalidated : DispatchHandoffIdentityNetworkOutcome
    data object SessionInvalidated : DispatchHandoffIdentityNetworkOutcome
}

/** Dispatch identity issue/validation transport; does not expose Buyer receipt operations. */
class NexaDispatchHandoffIdentityGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun issue(
        deliveryId: String,
        assignmentId: String,
        idempotencyKey: String,
        frozenBody: String
    ): DispatchHandoffIdentityNetworkOutcome {
        if (!dispatchHandoffUuid.matches(
                deliveryId
            ) || !dispatchHandoffUuid.matches(assignmentId) ||
            idempotencyKey.isBlank() || idempotencyKey.length > 160 ||
            !bodyMatches(frozenBody, assignmentId)
        ) {
            return DispatchHandoffIdentityNetworkOutcome.Unavailable
        }

        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = "$DISPATCH_HANDOFF_PATH/$deliveryId/handoff-tokens",
                    payload = frozenBody,
                    idempotencyKey = idempotencyKey
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toHandoffOutcome()

            is ProtectedResult.Success -> {
                if (result.status !in setOf(200, 201)) {
                    return DispatchHandoffIdentityNetworkOutcome.UnknownOutcome
                }
                val projection = result.body.toProjection(allowToken = true)
                    ?: return DispatchHandoffIdentityNetworkOutcome.UnknownOutcome
                if (!projection.deliveryId.equals(deliveryId, ignoreCase = true) ||
                    !projection.assignmentId.equals(assignmentId, ignoreCase = true) ||
                    ((result.status == 201) != (projection.token != null))
                ) {
                    DispatchHandoffIdentityNetworkOutcome.UnknownOutcome
                } else {
                    DispatchHandoffIdentityNetworkOutcome.Identity(projection)
                }
            }
        }
    }

    suspend fun validate(
        deliveryId: String,
        assignmentId: String,
        token: String
    ): DispatchHandoffIdentityNetworkOutcome {
        if (!dispatchHandoffUuid.matches(
                deliveryId
            ) || !dispatchHandoffUuid.matches(assignmentId) ||
            token.isBlank() || token.length > MAX_DISPATCH_HANDOFF_TOKEN_LENGTH
        ) {
            return DispatchHandoffIdentityNetworkOutcome.Rejected("HANDOFF_TOKEN_INVALID")
        }

        val body = """{"purpose":"$DISPATCH_HANDOFF_PURPOSE","token":${JsonPrimitive(
            token
        )},"deliveryId":"$deliveryId","assignmentId":"$assignmentId"}"""
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = DISPATCH_HANDOFF_VALIDATE_PATH,
                    payload = body
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toHandoffOutcome()

            is ProtectedResult.Success -> {
                if (result.status !=
                    200
                ) {
                    return DispatchHandoffIdentityNetworkOutcome.UnknownOutcome
                }
                val projection = result.body.toProjection(allowToken = false)
                    ?: return DispatchHandoffIdentityNetworkOutcome.UnknownOutcome
                if (!projection.deliveryId.equals(deliveryId, ignoreCase = true) ||
                    !projection.assignmentId.equals(assignmentId, ignoreCase = true)
                ) {
                    DispatchHandoffIdentityNetworkOutcome.UnknownOutcome
                } else {
                    DispatchHandoffIdentityNetworkOutcome.Identity(projection)
                }
            }
        }
    }

    private fun bodyMatches(body: String, assignmentId: String): Boolean = try {
        val root = dispatchHandoffJson.parseToJsonElement(body).jsonObject
        root.keys == setOf("purpose", "assignmentId") &&
            root["purpose"]?.jsonPrimitive?.contentOrNull == DISPATCH_HANDOFF_PURPOSE &&
            root["assignmentId"]?.jsonPrimitive?.contentOrNull == assignmentId
    } catch (_: Exception) {
        false
    }

    private fun String?.toProjection(allowToken: Boolean): DispatchHandoffIdentityProjection? =
        try {
            val root = this?.let(dispatchHandoffJson::parseToJsonElement)?.jsonObject ?: return null
            if (root.string("purpose") != DISPATCH_HANDOFF_PURPOSE) return null
            val token = when (val value = root["token"]) {
                null, JsonNull -> null

                else -> value.jsonPrimitive.takeIf(JsonPrimitive::isString)?.contentOrNull
                    ?: return null
            }
            if ((!allowToken && token != null) ||
                token?.let { it.isBlank() || it.length > MAX_DISPATCH_HANDOFF_TOKEN_LENGTH } == true
            ) {
                return null
            }
            val expiresAt = root.string("expiresAt")
            Instant.parse(expiresAt)
            DispatchHandoffIdentityProjection(
                handoffId = root.string("handoffId").also {
                    require(dispatchHandoffUuid.matches(it))
                },
                deliveryId = root.string("deliveryId").also {
                    require(dispatchHandoffUuid.matches(it))
                },
                assignmentId = root.string("assignmentId").also {
                    require(dispatchHandoffUuid.matches(it))
                },
                deliveryVersion = root.long("deliveryVersion").also { require(it >= 0) },
                expiresAt = expiresAt,
                status = root.string("status").also { require(it == "ACTIVE") },
                token = token
            )
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: error("handoff response field invalid")

    private fun JsonObject.long(key: String): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: error("handoff response field invalid")

    private fun ClientFailure.toHandoffOutcome(): DispatchHandoffIdentityNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired ->
            DispatchHandoffIdentityNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            DispatchHandoffIdentityNetworkOutcome.ContextInvalidated

        kind == FailureKind.AuthorizationFailure ->
            DispatchHandoffIdentityNetworkOutcome.PermissionDenied

        httpStatus == 404 || kind == FailureKind.ResourceUnavailable ->
            DispatchHandoffIdentityNetworkOutcome.NotFound

        kind == FailureKind.ValidationFailure || kind == FailureKind.BusinessConflict ||
            kind == FailureKind.PreconditionRequired || kind == FailureKind.StaleState ->
            DispatchHandoffIdentityNetworkOutcome.Rejected(problemCode)

        else -> DispatchHandoffIdentityNetworkOutcome.UnknownOutcome
    }

    private companion object {
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
    }
}
