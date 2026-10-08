package com.nexa.mobile.operations.salescommitment.infrastructure.transport

import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Reads commitment and credit independently; never computes a credit decision. */
class NexaCustomerProgressGateway(private val calls: ProtectedCallExecutor) {
    suspend fun orders(customerId: String, page: Int): CustomerProgressNetworkResult {
        if (!progressId.matches(customerId) ||
            page < 0
        ) {
            return CustomerProgressNetworkResult.Unavailable
        }
        return read("/api/v1/sales-orders?clientAccountId=$customerId&page=$page&size=25") { body ->
            val value = progressJson.decodeFromString<CommitmentPageWire>(body)
            if (value.page != page || value.size != 25 || value.total < value.items.size ||
                value.items.size > 25 ||
                value.items.any { it.clientAccountId != customerId || !it.valid() }
            ) {
                CustomerProgressNetworkResult.Unavailable
            } else {
                CustomerProgressNetworkResult.Orders(value)
            }
        }
    }
    private suspend fun read(
        path: String,
        decode: (String) -> CustomerProgressNetworkResult
    ): CustomerProgressNetworkResult =
        when (val result = calls.execute(ProtectedRequest(ProtectedMethod.GET, path))) {
            is ProtectedResult.Success -> try {
                result.body?.let(decode)
                    ?: CustomerProgressNetworkResult.Unavailable
            } catch (
                _: Exception
            ) {
                CustomerProgressNetworkResult.Unavailable
            }

            is ProtectedResult.Failure ->
                if (result.error.kind == FailureKind.AuthorizationFailure ||
                    result.error.kind == FailureKind.AuthenticationRequired ||
                    result.error.httpStatus == 404
                ) {
                    CustomerProgressNetworkResult.PermissionDenied
                } else {
                    CustomerProgressNetworkResult.Unavailable
                }
        }
}
private val progressJson = Json { ignoreUnknownKeys = true }
private val progressId = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

@Serializable
data class CommitmentWire(
    val id: String,
    val number: String,
    val clientAccountId: String,
    val tenantId: String,
    val workspaceId: String,
    val status: String,
    val currency: String,
    val total: JsonPrimitive,
    val version: Long,
    val updatedAt: String
) {
    fun valid(): Boolean = progressId.matches(id) && number.isNotBlank() && version >= 0 &&
        status in
        setOf(
            "PENDING", "CONFIRMED", "IN_FULFILLMENT", "PARTIALLY_FULFILLED", "FULFILLED",
            "PARTIALLY_DELIVERED", "COMPLETED", "REJECTED", "CANCELLED"
        ) &&
        Regex("[A-Z]{3}").matches(currency) && total.content.toBigDecimalOrNull() != null &&
        runCatching { Instant.parse(updatedAt) }.isSuccess
    override fun toString(): String = "CommitmentWire(REDACTED)"
}

@Serializable
data class CommitmentPageWire(
    val items: List<CommitmentWire>,
    val page: Int,
    val size: Int,
    val total: Long
)

sealed interface CustomerProgressNetworkResult {
    data class Orders(val page: CommitmentPageWire) : CustomerProgressNetworkResult
    data object Unavailable : CustomerProgressNetworkResult
    data object PermissionDenied : CustomerProgressNetworkResult
}
