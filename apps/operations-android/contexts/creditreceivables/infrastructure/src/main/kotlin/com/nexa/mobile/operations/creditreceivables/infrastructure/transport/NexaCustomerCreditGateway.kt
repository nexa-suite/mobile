package com.nexa.mobile.operations.creditreceivables.infrastructure.transport

import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.creditreceivables.application.publicapi.CustomerCreditExposureQuery
import com.nexa.mobile.operations.creditreceivables.application.publicapi.CustomerCreditExposureRead
import com.nexa.mobile.operations.creditreceivables.application.publicapi.CustomerCreditSnapshot
import com.nexa.mobile.operations.creditreceivables.domain.model.commercial.CustomerCredit
import java.time.Instant
import javax.inject.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class NexaCustomerCreditGateway @Inject constructor(private val calls: ProtectedCallExecutor) :
    CustomerCreditExposureQuery {
    override suspend fun credit(customerId: String, currency: String): CustomerCreditExposureRead {
        if (!progressId.matches(customerId) ||
            !Regex("[A-Z]{3}").matches(currency)
        ) {
            return CustomerCreditExposureRead.Unavailable
        }
        return read(
            "/api/v1/client-accounts/$customerId/credit-exposure?currency=$currency"
        ) { body ->
            val value = progressJson.decodeFromString<CreditExposureWire>(body)
            if (value.clientAccountId != customerId || value.currency != currency ||
                !value.valid()
            ) {
                CustomerCreditExposureRead.Unavailable
            } else {
                CustomerCreditExposureRead.Available(value.toProjection().toSnapshot())
            }
        }
    }
    private suspend fun read(
        path: String,
        decode: (String) -> CustomerCreditExposureRead
    ): CustomerCreditExposureRead =
        when (val result = calls.execute(ProtectedRequest(ProtectedMethod.GET, path))) {
            is ProtectedResult.Success -> runCatching { result.body?.let(decode) }.getOrNull()
                ?: CustomerCreditExposureRead.Unavailable

            is ProtectedResult.Failure -> if (
                result.error.kind == FailureKind.AuthorizationFailure ||
                result.error.kind == FailureKind.AuthenticationRequired ||
                result.error.httpStatus == 404
            ) {
                CustomerCreditExposureRead.PermissionDenied
            } else {
                CustomerCreditExposureRead.Unavailable
            }
        }
}
private val progressJson = Json { ignoreUnknownKeys = true }
private val progressId = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

@Serializable
data class CreditExposureWire(
    val clientAccountId: String,
    val currency: String,
    val creditLimit: JsonPrimitive,
    val ledgerExposure: JsonPrimitive,
    val outstandingReceivables: JsonPrimitive,
    val reservedExposure: JsonPrimitive,
    val used: JsonPrimitive,
    val availableCredit: JsonPrimitive,
    val active: Boolean,
    val asOf: String
) {
    fun valid(): Boolean = listOf(
        creditLimit,
        ledgerExposure,
        outstandingReceivables,
        reservedExposure,
        used,
        availableCredit
    )
        .all { it.content.toBigDecimalOrNull() != null } &&
        runCatching { Instant.parse(asOf) }.isSuccess
    override fun toString(): String = "CreditExposureWire(REDACTED)"
}
private fun CreditExposureWire.toProjection() = CustomerCredit(
    currency, creditLimit.content, ledgerExposure.content, outstandingReceivables.content,
    reservedExposure.content, used.content, availableCredit.content, active, asOf
)

private fun CustomerCredit.toSnapshot() = CustomerCreditSnapshot(
    currency, limit, ledgerExposure, outstanding, reserved, used, available, active, asOf
)
