package com.nexa.mobile.operations.payments.infrastructure.transport

import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.payments.application.refund.WalletRefundAuthority
import com.nexa.mobile.operations.payments.application.refund.WalletRefundCandidatesRead
import com.nexa.mobile.operations.payments.application.refund.WalletRefundGateway
import com.nexa.mobile.operations.payments.application.refund.WalletRefundIntent
import com.nexa.mobile.operations.payments.application.refund.WalletRefundSubmission
import com.nexa.mobile.operations.payments.application.refund.canReconcilePayments
import com.nexa.mobile.operations.payments.domain.model.WalletRefundCandidate
import com.nexa.mobile.operations.payments.domain.model.WalletRefundCandidatePage
import com.nexa.mobile.operations.payments.domain.model.WalletRefundPosted
import com.nexa.mobile.operations.payments.infrastructure.adapters.WalletRefundIntentIntegrity
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val CANDIDATES_PATH = "/api/v1/sales-orders/wallet-refund-candidates"
private const val SALES_ORDERS_PATH = "/api/v1/sales-orders"
private const val PEN = "PEN"
private val walletRefundJson = Json { ignoreUnknownKeys = true }
private val uuidRegex = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** Protected transport for server-owned wallet refund candidates and posted refund facts. */
class NexaWalletRefundGateway @Inject constructor(private val calls: ProtectedCallExecutor) :
    WalletRefundGateway {
    override fun freezeIntent(
        salesOrderId: String,
        amountPEN: String,
        reason: String
    ): WalletRefundIntent = WalletRefundIntentIntegrity.freeze(salesOrderId, amountPEN, reason)

    override suspend fun candidates(
        authority: WalletRefundAuthority,
        page: Int,
        size: Int
    ): WalletRefundCandidatesRead {
        if (!authority.canReconcilePayments() || page < 0 || size != 25) {
            return WalletRefundCandidatesRead.Denied
        }
        val path = "$CANDIDATES_PATH?page=$page&size=$size"
        return when (val result = execute(ProtectedRequest(ProtectedMethod.GET, path))) {
            is ProtectedResult.Failure -> result.toCandidatesFailure()

            is ProtectedResult.Success -> {
                val body = result.body.decodeObject()
                    ?: return WalletRefundCandidatesRead.ProtocolFailure
                val candidatePage = body.toCandidatePage(page, size)
                    ?: return WalletRefundCandidatesRead.ProtocolFailure
                WalletRefundCandidatesRead.Available(candidatePage)
            }
        }
    }

    override suspend fun submit(
        authority: WalletRefundAuthority,
        intent: WalletRefundIntent
    ): WalletRefundSubmission {
        if (!authority.canReconcilePayments() || !WalletRefundIntentIntegrity.isValid(intent)) {
            return WalletRefundSubmission.Denied
        }
        val result = execute(
            ProtectedRequest(
                ProtectedMethod.POST,
                "$SALES_ORDERS_PATH/${intent.salesOrderId}/wallet-refunds",
                payload = intent.exactPayload,
                idempotencyKey = intent.idempotencyKey
            )
        )
        return when (result) {
            is ProtectedResult.Failure -> result.toSubmissionFailure()

            is ProtectedResult.Success -> {
                val receipt = result.body.decodeObject()?.toPosted()
                    ?: return WalletRefundSubmission.UnknownOutcome
                if (!receipt.salesOrderId.equals(intent.salesOrderId, ignoreCase = true) ||
                    receipt.amountPEN.compareTo(intent.amountPEN.toBigDecimal()) != 0 ||
                    receipt.currency != PEN || receipt.status != "POSTED"
                ) {
                    WalletRefundSubmission.UnknownOutcome
                } else {
                    WalletRefundSubmission.Posted(receipt)
                }
            }
        }
    }

    private suspend fun execute(request: ProtectedRequest): ProtectedResult = calls.execute(request)

    private fun ProtectedResult.Failure.toCandidatesFailure(): WalletRefundCandidatesRead = when {
        error.kind == FailureKind.AuthenticationRequired ||
            error.kind == FailureKind.AuthorizationFailure || error.httpStatus == 401 ||
            error.httpStatus == 403 -> WalletRefundCandidatesRead.Denied

        error.kind == FailureKind.ProtocolFailure -> WalletRefundCandidatesRead.ProtocolFailure

        else -> WalletRefundCandidatesRead.Unavailable
    }

    private fun ProtectedResult.Failure.toSubmissionFailure(): WalletRefundSubmission = when {
        error.kind == FailureKind.AuthenticationRequired ||
            error.kind == FailureKind.AuthorizationFailure || error.httpStatus == 401 ||
            error.httpStatus == 403 -> WalletRefundSubmission.Denied

        error.httpStatus == 409 || error.httpStatus == 412 -> WalletRefundSubmission.Conflict

        error.httpStatus == 400 || error.httpStatus == 422 -> WalletRefundSubmission.Rejected

        else -> WalletRefundSubmission.UnknownOutcome
    }

    private fun String?.decodeObject(): JsonObject? = runCatching {
        walletRefundJson.parseToJsonElement(this.orEmpty()).jsonObject
    }.getOrNull()

    private fun JsonObject.toCandidatePage(
        expectedPage: Int,
        expectedSize: Int
    ): WalletRefundCandidatePage? {
        return try {
            val items = getValue("items").jsonArray.map {
                it.jsonObject.toCandidate() ?: return null
            }
            val page = getValue("page").intValue() ?: return null
            val size = getValue("size").intValue() ?: return null
            val total = getValue("total").longValue() ?: return null
            if (page != expectedPage || size != expectedSize || total < 0 || items.size > size) {
                return null
            }
            WalletRefundCandidatePage(items, page, size, total)
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.toCandidate(): WalletRefundCandidate? {
        val orderId = getValue("salesOrderId").stringValue() ?: return null
        val orderNumber = getValue("orderNumber").stringValue()?.takeIf(String::isNotBlank)
            ?: return null
        val currency = getValue("currency").stringValue() ?: return null
        if (!uuidRegex.matches(orderId) || currency != PEN) return null
        val consumed = getValue("consumedPEN").decimalValue() ?: return null
        val refunded = getValue("refundedPEN").decimalValue() ?: return null
        val refundable = getValue("refundablePEN").decimalValue() ?: return null
        if (consumed.signum() < 0 || refunded.signum() < 0 || refundable.signum() < 0) {
            return null
        }
        return WalletRefundCandidate(orderId, orderNumber, currency, consumed, refunded, refundable)
    }

    private fun JsonObject.toPosted(): WalletRefundPosted? {
        val orderId = getValue("salesOrderId").stringValue() ?: return null
        val refundId = getValue("refundId").stringValue() ?: return null
        val ledgerId = getValue("ledgerEntryId").stringValue() ?: return null
        val amount = getValue("amountPEN").decimalValue() ?: return null
        val currency = getValue("currency").stringValue() ?: return null
        val status = getValue("status").stringValue() ?: return null
        if (!uuidRegex.matches(orderId) || !uuidRegex.matches(refundId) ||
            !uuidRegex.matches(ledgerId) || amount.signum() <= 0
        ) {
            return null
        }
        return WalletRefundPosted(orderId, refundId, ledgerId, amount, currency, status)
    }

    private fun JsonElement.stringValue(): String? =
        (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonElement.decimalValue(): BigDecimal? =
        (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.content?.toBigDecimalOrNull()

    private fun JsonElement.intValue(): Int? =
        (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.content?.toIntOrNull()

    private fun JsonElement.longValue(): Long? =
        (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.content?.toLongOrNull()
}
