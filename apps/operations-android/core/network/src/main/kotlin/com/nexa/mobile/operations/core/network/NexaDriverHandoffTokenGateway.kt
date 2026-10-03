package com.nexa.mobile.operations.core.network

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val HANDOFF_DELIVERY_PATH = "/api/v1/deliveries"
private val handoffJson = Json { ignoreUnknownKeys = true }
private val handoffUuid = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

data class DriverHandoffTokenProjection(
    val handoffId: String,
    val deliveryId: String,
    val attemptId: String,
    val expiresAt: String,
    val status: String,
    val token: String?
)

sealed interface DriverHandoffTokenNetworkOutcome {
    data class Issued(val value: DriverHandoffTokenProjection) : DriverHandoffTokenNetworkOutcome
    data class Rejected(val code: String?) : DriverHandoffTokenNetworkOutcome
    data object NotFound : DriverHandoffTokenNetworkOutcome
    data object UnknownOutcome : DriverHandoffTokenNetworkOutcome
    data object Unavailable : DriverHandoffTokenNetworkOutcome
    data object PermissionDenied : DriverHandoffTokenNetworkOutcome
    data object ContextInvalidated : DriverHandoffTokenNetworkOutcome
    data object SessionInvalidated : DriverHandoffTokenNetworkOutcome
}

/** Driver issue-only transport. Buyer validation is deliberately not exposed here. */
class NexaDriverHandoffTokenGateway(private val protectedCalls: ProtectedCallExecutor) {
    suspend fun issue(
        deliveryId: String,
        attemptId: String,
        idempotencyKey: String,
        frozenBody: String
    ): DriverHandoffTokenNetworkOutcome {
        if (!handoffUuid.matches(deliveryId) || !handoffUuid.matches(attemptId) ||
            idempotencyKey.isBlank() || idempotencyKey.length > 160 ||
            !bodyMatches(frozenBody, attemptId)
        ) {
            return DriverHandoffTokenNetworkOutcome.Unavailable
        }
        return when (
            val result = protectedCalls.execute(
                ProtectedRequest(
                    method = ProtectedMethod.POST,
                    path = "$HANDOFF_DELIVERY_PATH/$deliveryId/handoff-tokens",
                    payload = frozenBody,
                    idempotencyKey = idempotencyKey
                )
            )
        ) {
            is ProtectedResult.Failure -> result.error.toHandoffOutcome()

            is ProtectedResult.Success -> {
                if (result.status !in
                    setOf(200, 201)
                ) {
                    return DriverHandoffTokenNetworkOutcome.UnknownOutcome
                }
                val projection = result.body.toHandoffProjection()
                    ?: return DriverHandoffTokenNetworkOutcome.UnknownOutcome
                if (projection.deliveryId != deliveryId || projection.attemptId != attemptId ||
                    projection.status != "ACTIVE" ||
                    ((result.status == 201) != !projection.token.isNullOrBlank()) ||
                    (projection.token?.length ?: 0) > MAX_TOKEN_LENGTH
                ) {
                    DriverHandoffTokenNetworkOutcome.UnknownOutcome
                } else {
                    DriverHandoffTokenNetworkOutcome.Issued(projection)
                }
            }
        }
    }

    private fun bodyMatches(body: String, attemptId: String): Boolean = try {
        val root = handoffJson.parseToJsonElement(body).jsonObject
        root.keys == setOf("attemptId") &&
            root["attemptId"]?.jsonPrimitive?.contentOrNull == attemptId
    } catch (_: Exception) {
        false
    }

    private fun String?.toHandoffProjection(): DriverHandoffTokenProjection? = try {
        val root = this?.let(handoffJson::parseToJsonElement)?.jsonObject ?: return null
        val expiresAt = root.string("expiresAt")
        Instant.parse(expiresAt)
        val token = when (val value = root["token"]) {
            null, JsonNull -> null

            else -> value.jsonPrimitive.takeIf(JsonPrimitive::isString)?.contentOrNull
                ?: return null
        }
        if (token != null && token.isBlank()) return null
        DriverHandoffTokenProjection(
            handoffId = root.string("handoffId").also { require(handoffUuid.matches(it)) },
            deliveryId = root.string("deliveryId").also { require(handoffUuid.matches(it)) },
            attemptId = root.string("attemptId").also { require(handoffUuid.matches(it)) },
            expiresAt = expiresAt,
            status = root.string("status"),
            token = token
        )
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.takeIf(
        JsonPrimitive::isString
    )?.contentOrNull?.takeIf(String::isNotBlank)
        ?: error("handoff response field is invalid")

    private fun ClientFailure.toHandoffOutcome(): DriverHandoffTokenNetworkOutcome = when {
        kind == FailureKind.AuthenticationRequired ->
            DriverHandoffTokenNetworkOutcome.SessionInvalidated

        httpStatus == 403 && problemCode == ACCESS_CONTEXT_INVALID ->
            DriverHandoffTokenNetworkOutcome.ContextInvalidated

        kind == FailureKind.AuthorizationFailure ->
            DriverHandoffTokenNetworkOutcome.PermissionDenied

        kind == FailureKind.ResourceUnavailable -> DriverHandoffTokenNetworkOutcome.NotFound

        kind == FailureKind.ValidationFailure || kind == FailureKind.BusinessConflict ||
            kind == FailureKind.PreconditionRequired || kind == FailureKind.StaleState ->
            DriverHandoffTokenNetworkOutcome.Rejected(problemCode)

        else -> DriverHandoffTokenNetworkOutcome.UnknownOutcome
    }

    private companion object {
        const val ACCESS_CONTEXT_INVALID = "ACCESS_CONTEXT_INVALID"
        const val MAX_TOKEN_LENGTH = 400
    }
}
