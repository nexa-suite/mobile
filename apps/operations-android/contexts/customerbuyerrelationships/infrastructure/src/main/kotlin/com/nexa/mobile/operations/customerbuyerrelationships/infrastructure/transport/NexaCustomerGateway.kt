package com.nexa.mobile.operations.customerbuyerrelationships.infrastructure.transport

import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Protected BC-02 reads; search and authorization stay server-owned. */
class NexaCustomerGateway(private val calls: ProtectedCallExecutor) {
    suspend fun search(search: String, page: Int): CustomerNetworkResult {
        if (page < 0 || search.length > 200) return CustomerNetworkResult.Unavailable
        return read(
            "/api/v1/client-accounts?search=${URLEncoder.encode(
                search,
                StandardCharsets.UTF_8.name()
            )}&page=$page&size=25"
        ) { body ->
            val value = customerJson.decodeFromString<CustomerPageWire>(body)
            if (value.page != page || value.size != 25 || value.total < value.items.size ||
                value.items.size > 25 ||
                value.items.map { it.id }.distinct().size != value.items.size ||
                value.items.any { !it.valid() }
            ) {
                CustomerNetworkResult.Unavailable
            } else {
                CustomerNetworkResult.Page(value)
            }
        }
    }

    suspend fun detail(id: String): CustomerNetworkResult {
        if (!customerId.matches(id)) return CustomerNetworkResult.Unavailable
        return read("/api/v1/client-accounts/$id") { body ->
            val value = customerJson.decodeFromString<CustomerWire>(body)
            if (value.id != id || !value.valid()) {
                CustomerNetworkResult.Unavailable
            } else {
                CustomerNetworkResult.Detail(value)
            }
        }
    }

    private suspend fun read(
        path: String,
        decode: (String) -> CustomerNetworkResult
    ): CustomerNetworkResult =
        when (val result = calls.execute(ProtectedRequest(ProtectedMethod.GET, path))) {
            is ProtectedResult.Success -> try {
                result.body?.let(decode)
                    ?: CustomerNetworkResult.Unavailable
            } catch (
                _: Exception
            ) {
                CustomerNetworkResult.Unavailable
            }

            is ProtectedResult.Failure -> when {
                result.error.kind == FailureKind.AuthenticationRequired ->
                    CustomerNetworkResult.SessionInvalidated

                result.error.kind == FailureKind.AuthorizationFailure ||
                    result.error.httpStatus == 404 -> CustomerNetworkResult.PermissionDenied

                else -> CustomerNetworkResult.Unavailable
            }
        }
}

private val customerJson = Json { ignoreUnknownKeys = true }
private val customerId = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

@Serializable
data class CustomerWire(
    val id: String,
    val code: String,
    val businessName: String,
    val commercialName: String? = null,
    val status: String,
    val buyerMembershipId: String? = null,
    val version: Long,
    val contactPerson: String? = null,
    val contactEmail: String? = null,
    val phone: String? = null
) {
    fun valid(): Boolean =
        customerId.matches(id) && code.isNotBlank() && businessName.isNotBlank() &&
            status in setOf("ACTIVE", "SUSPENDED") && version >= 0
    override fun toString(): String = "CustomerWire(REDACTED)"
}

@Serializable
data class CustomerPageWire(
    val items: List<CustomerWire>,
    val page: Int,
    val size: Int,
    val total: Long
)

sealed interface CustomerNetworkResult {
    data class Page(val value: CustomerPageWire) : CustomerNetworkResult
    data class Detail(val value: CustomerWire) : CustomerNetworkResult
    data object PermissionDenied : CustomerNetworkResult
    data object SessionInvalidated : CustomerNetworkResult
    data object Unavailable : CustomerNetworkResult
}
