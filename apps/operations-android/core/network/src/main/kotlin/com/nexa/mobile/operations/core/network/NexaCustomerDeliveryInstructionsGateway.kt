package com.nexa.mobile.operations.core.network

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class CustomerDeliveryInstructionTransport(
    val id: String,
    val instructionVersion: Long,
    val kind: String,
    val content: String,
    val sourceKind: String,
    val sourceReference: String?,
    val recordedByMembershipId: String,
    val recordedAt: String
)
data class CustomerDeliveryInstructionsTransport(
    val salesOrderId: String,
    val version: Long,
    val editable: Boolean,
    val instructions: List<CustomerDeliveryInstructionTransport>
)
sealed interface CustomerDeliveryInstructionsNetworkResult {
    data class Current(val snapshot: CustomerDeliveryInstructionsTransport) :
        CustomerDeliveryInstructionsNetworkResult
    data class Failed(val code: String?, val unknownOutcome: Boolean) :
        CustomerDeliveryInstructionsNetworkResult
}

/** Sales records received Customer instructions; source and edit window remain server authority. */
class NexaCustomerDeliveryInstructionsGateway(private val calls: ProtectedCallExecutor) {
    suspend fun read(orderId: String): CustomerDeliveryInstructionsNetworkResult {
        if (!validUuid(
                orderId
            )
        ) {
            return CustomerDeliveryInstructionsNetworkResult.Failed("INVALID_ORDER", false)
        }
        return execute(ProtectedRequest(ProtectedMethod.GET, path(orderId)), orderId)
    }
    suspend fun publish(
        orderId: String,
        version: Long,
        key: String,
        frozenBody: String
    ): CustomerDeliveryInstructionsNetworkResult {
        if (!validUuid(orderId) || version < 0 || key.isBlank()) {
            return CustomerDeliveryInstructionsNetworkResult.Failed("INVALID_COMMAND", false)
        }
        return execute(
            ProtectedRequest(ProtectedMethod.POST, path(orderId), frozenBody, key, "\"$version\""),
            orderId
        )
    }
    private suspend fun execute(
        request: ProtectedRequest,
        orderId: String
    ): CustomerDeliveryInstructionsNetworkResult = when (val result = calls.execute(request)) {
        is ProtectedResult.Failure -> CustomerDeliveryInstructionsNetworkResult.Failed(
            result.error.problemCode,
            request.isMutation && result.error.kind in setOf(
                FailureKind.UnknownOutcome,
                FailureKind.NetworkUnavailable,
                FailureKind.Timeout,
                FailureKind.ProtocolFailure
            )
        )

        is ProtectedResult.Success -> {
            val snapshot = decode(result.body)
            if (snapshot == null || snapshot.salesOrderId != orderId ||
                result.etag != "\"${snapshot.version}\""
            ) {
                CustomerDeliveryInstructionsNetworkResult.Failed(
                    "INVALID_RESPONSE",
                    request.isMutation
                )
            } else {
                CustomerDeliveryInstructionsNetworkResult.Current(snapshot)
            }
        }
    }
    private fun decode(body: String?): CustomerDeliveryInstructionsTransport? = try {
        val root = Json.parseToJsonElement(body ?: return null).jsonObject
        val id = root.text("salesOrderId").also { require(validUuid(it)) }
        val version = root["version"]!!.jsonPrimitive.longOrNull!!.also { require(it >= 0) }
        val editable = root["editable"]!!.jsonPrimitive.booleanOrNull!!
        val instructions = root["instructions"]!!.jsonArray.map { element ->
            val row = element.jsonObject
            CustomerDeliveryInstructionTransport(
                row.text("id").also { require(validUuid(it)) },
                row["instructionVersion"]!!.jsonPrimitive.longOrNull!!.also { require(it > 0) },
                row.text("kind").also { require(it in kinds) },
                row.text("content"),
                row.text("sourceKind").also {
                    require(it in setOf("BUYER", "CUSTOMER_REPORTED_BY_SALES"))
                },
                row["sourceReference"]?.jsonPrimitive?.contentOrNull,
                row.text("recordedByMembershipId").also { require(validUuid(it)) },
                row.text("recordedAt").also { Instant.parse(it) }
            )
        }
        require(instructions.map { it.id }.distinct().size == instructions.size)
        CustomerDeliveryInstructionsTransport(id, version, editable, instructions)
    } catch (_: Exception) {
        null
    }
    private fun JsonObject.text(key: String): String = this[key]!!.jsonPrimitive
        .also { require(it.isString) }.content.also { require(it.isNotBlank()) }
    private fun path(orderId: String) =
        "/api/v1/sales-orders/$orderId/customer-delivery-instructions"
    companion object {
        val kinds =
            setOf(
                "NORMAL",
                "COLD_CHAIN",
                "ACCESS_RESTRICTION",
                "SPECIAL_UNLOADING",
                "CUSTOMER_SAFETY",
                "GOODS_HANDLING"
            )
        fun validUuid(value: String): Boolean = try {
            UUID.fromString(value).toString().equals(value, true)
        } catch (
            _: Exception
        ) {
            false
        }
        fun body(id: String, kind: String, content: String, source: String): String {
            require(
                validUuid(id) && kind in kinds && content.isNotBlank() && content.length <= 2000 &&
                    source.isNotBlank() &&
                    source.length <= 500
            )
            return JsonObject(
                mapOf(
                    "instructionId" to JsonPrimitive(id),
                    "kind" to JsonPrimitive(kind),
                    "content" to JsonPrimitive(content),
                    "sourceReference" to JsonPrimitive(source)
                )
            ).toString()
        }
    }
}
