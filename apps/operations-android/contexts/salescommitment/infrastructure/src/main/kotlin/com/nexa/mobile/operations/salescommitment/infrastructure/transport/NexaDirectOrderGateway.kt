package com.nexa.mobile.operations.salescommitment.infrastructure.transport

import com.nexa.mobile.operations.core.network.FailureKind
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.salescommitment.application.commercial.FieldRequestSubmission
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestIntent
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestReceipt
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.commercial.CommercialAuthority
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val DIRECT_ORDER_PATH = "/api/v1/direct-orders"
private val directOrderJson = Json { ignoreUnknownKeys = true }
private val directOrderPriorities = setOf("NORMAL", "HIGH", "URGENT")
private val directOrderPaymentOptions =
    setOf(
        "CREDIT_LINE",
        "BANK_TRANSFER",
        "CARD_STRIPE",
        "CASH",
        "CASH_ON_DELIVERY",
        "PREPAID",
        "IMMEDIATE"
    )

/** Protected transport for the API's atomic Direct Order command. */
class NexaDirectOrderGateway(private val calls: ProtectedCallExecutor) {
    suspend fun submit(
        authority: CommercialAuthority,
        intent: FieldRequestIntent,
        expectedCustomerId: String
    ): FieldRequestSubmission {
        if (intent.operation != FieldRequestIntent.DIRECT_ORDER_OPERATION) {
            return FieldRequestSubmission.LegacyIntent
        }
        if (intent.key.isBlank() || intent.key.length > 160 ||
            !authority.tenantId.isUuid() || !authority.workspaceId.isUuid() ||
            !authority.membershipId.isUuid()
        ) {
            return FieldRequestSubmission.PermissionDenied
        }
        val request = intent.exactBody.toFrozenRequest() ?: return FieldRequestSubmission.Rejected
        if (!request.clientAccountId.sameUuid(expectedCustomerId)) {
            return FieldRequestSubmission.Rejected
        }

        return when (
            val result = calls.execute(
                ProtectedRequest(
                    ProtectedMethod.POST,
                    DIRECT_ORDER_PATH,
                    intent.exactBody,
                    intent.key
                )
            )
        ) {
            is ProtectedResult.Success -> result.toSubmission(authority, request)

            is ProtectedResult.Failure -> when (result.error.kind) {
                FailureKind.AuthenticationRequired,
                FailureKind.AuthorizationFailure -> FieldRequestSubmission.PermissionDenied

                else -> when (result.error.httpStatus) {
                    401, 403 -> FieldRequestSubmission.PermissionDenied
                    404 -> FieldRequestSubmission.Unavailable
                    409, 412 -> FieldRequestSubmission.Conflict
                    400, 422 -> FieldRequestSubmission.Rejected
                    else -> FieldRequestSubmission.UnknownOutcome
                }
            }
        }
    }

    private fun ProtectedResult.Success.toSubmission(
        authority: CommercialAuthority,
        request: FrozenDirectOrderRequest
    ): FieldRequestSubmission {
        val response = runCatching {
            directOrderJson.decodeFromString<DirectOrderResponseWire>(body ?: "")
        }.getOrNull() ?: return FieldRequestSubmission.UnknownOutcome
        if (!response.isEnclosedBy(authority, request) ||
            etag?.trim()?.removeSurrounding("\"")?.toLongOrNull() != response.version
        ) {
            return FieldRequestSubmission.UnknownOutcome
        }
        val receipt = response.toReceipt() ?: return FieldRequestSubmission.UnknownOutcome
        return when {
            status == 201 && response.status == "CONFIRMED" &&
                response.paymentOption != "PREPAID" && response.version == 1L ->
                FieldRequestSubmission.Confirmed(receipt)

            status == 202 && response.status == "PENDING" &&
                response.paymentOption == "PREPAID" && response.version == 0L ->
                FieldRequestSubmission.PrepaidPending(receipt)

            else -> FieldRequestSubmission.UnknownOutcome
        }
    }

    private fun DirectOrderResponseWire.isEnclosedBy(
        authority: CommercialAuthority,
        request: FrozenDirectOrderRequest
    ): Boolean {
        val amount = total?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()
            ?: return false
        if (amount.signum() < 0) return false
        val currencyCode = currency ?: return false
        if (!currencyCode.isIsoCurrency()) return false
        val orderId = id ?: return false
        val orderNumber = number ?: return false
        if (!orderId.isUuid() || orderNumber.isBlank() ||
            !tenantId.sameUuid(authority.tenantId) ||
            !workspaceId.sameUuid(authority.workspaceId) ||
            !clientAccountId.sameUuid(request.clientAccountId) ||
            !createdByMembershipId.sameUuid(authority.membershipId) ||
            !buyerMembershipId.sameUuid(authority.membershipId) ||
            sourcePurchaseRequestId != null ||
            priority != request.priority ||
            requestedDeliveryDate != request.requestedDeliveryDate ||
            deliverySnapshot.orEmpty() != request.deliveryProfileSnapshot ||
            paymentOption != request.paymentOption ||
            notes.orEmpty() != request.comment ||
            status !in setOf("CONFIRMED", "PENDING") ||
            version == null || version < 0 ||
            originType != "DIRECT_ORDER" || commercialCommitmentId?.isUuid() != true
        ) {
            return false
        }
        if (lines.size != request.lines.size) return false
        val unmatched = request.lines.toMutableList()
        var lineTotal = BigDecimal.ZERO
        for (orderLine in lines) {
            val orderQuantity = orderLine.quantity?.takeUnless { it.isString }
                ?.content?.toBigDecimalOrNull()
                ?: return false
            val requestedIndex = unmatched.indexOfFirst {
                it.catalogItemId == orderLine.catalogItemId &&
                    it.quantity.compareTo(orderQuantity) == 0 &&
                    it.unit == orderLine.unit
            }
            if (requestedIndex < 0) return false
            unmatched.removeAt(requestedIndex)
            val unitPriceAmount = orderLine.unitPriceAmount?.takeUnless { it.isString }
                ?.content?.toBigDecimalOrNull()
                ?: return false
            val lineSubtotal = orderLine.lineSubtotal?.takeUnless { it.isString }
                ?.content?.toBigDecimalOrNull()
                ?: return false
            if (orderLine.catalogItemId.isNullOrBlank() ||
                unitPriceAmount.signum() < 0 ||
                orderLine.unitPriceCurrency != currencyCode ||
                lineSubtotal.signum() < 0 ||
                lineSubtotal.compareTo(orderQuantity.multiply(unitPriceAmount)) != 0
            ) {
                return false
            }
            lineTotal = lineTotal.add(lineSubtotal)
        }
        return unmatched.isEmpty() && amount.compareTo(lineTotal) == 0
    }

    private fun String.toFrozenRequest(): FrozenDirectOrderRequest? {
        val json = runCatching { directOrderJson.parseToJsonElement(this).jsonObject }.getOrNull()
            ?: return null
        val requiredKeys = setOf(
            "clientAccountId",
            "priority",
            "requestedDeliveryDate",
            "deliveryProfileSnapshot",
            "paymentOption",
            "comment",
            "lines"
        )
        if (json.keys != requiredKeys) return null
        fun stringField(name: String): String? = (json[name] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        val clientAccountId = stringField("clientAccountId") ?: return null
        val priority = stringField("priority") ?: return null
        val requestedDeliveryDate = stringField("requestedDeliveryDate") ?: return null
        val deliveryProfileSnapshot = stringField("deliveryProfileSnapshot") ?: return null
        val paymentOption = stringField("paymentOption") ?: return null
        val comment = stringField("comment") ?: return null
        if (!clientAccountId.isUuid() || priority !in directOrderPriorities ||
            runCatching { LocalDate.parse(requestedDeliveryDate) }.isFailure ||
            deliveryProfileSnapshot.length > 2000 || comment.length > 2000 ||
            paymentOption !in directOrderPaymentOptions
        ) {
            return null
        }
        val lineArray = runCatching { json.getValue("lines").jsonArray }.getOrNull() ?: return null
        val lines = mutableListOf<FrozenDirectOrderLine>()
        for (element in lineArray) {
            val item = runCatching { element.jsonObject }.getOrNull() ?: return null
            if (item.keys != setOf("catalogItemId", "quantity", "unit")) return null
            val catalogItemId = (item["catalogItemId"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?: return null
            val quantity = (item["quantity"] as? JsonPrimitive)?.content?.toBigDecimalOrNull()
                ?: return null
            val unit =
                (item["unit"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (catalogItemId.isBlank() || catalogItemId.length > 64 ||
                quantity.signum() <= 0 || unit.isBlank() || unit.length > 32
            ) {
                return null
            }
            lines += FrozenDirectOrderLine(catalogItemId, quantity, unit)
        }
        if (lines.isEmpty() || lines.size > 100) return null
        return FrozenDirectOrderRequest(
            clientAccountId,
            priority,
            requestedDeliveryDate,
            deliveryProfileSnapshot,
            paymentOption,
            comment,
            lines
        )
    }

    private fun String.isUuid(): Boolean = runCatching {
        UUID.fromString(this).toString().equals(this, ignoreCase = true)
    }.getOrDefault(false)

    private fun String?.sameUuid(other: String): Boolean = this?.let { value ->
        runCatching { UUID.fromString(value) == UUID.fromString(other) }.getOrDefault(false)
    } == true

    private fun String.isIsoCurrency(): Boolean = runCatching {
        Currency.getInstance(this).currencyCode == this
    }.getOrDefault(false)

    private fun DirectOrderResponseWire.toReceipt(): FieldRequestReceipt? {
        val id = id ?: return null
        val number = number ?: return null
        val status = status ?: return null
        val paymentOption = paymentOption ?: return null
        val currency = currency ?: return null
        val total = total ?: return null
        val version = version ?: return null
        return FieldRequestReceipt(
            id,
            number,
            status,
            paymentOption,
            currency,
            total.content,
            version
        )
    }
}

private data class FrozenDirectOrderRequest(
    val clientAccountId: String,
    val priority: String,
    val requestedDeliveryDate: String,
    val deliveryProfileSnapshot: String,
    val paymentOption: String,
    val comment: String,
    val lines: List<FrozenDirectOrderLine>
)

private data class FrozenDirectOrderLine(
    val catalogItemId: String,
    val quantity: BigDecimal,
    val unit: String
)

@Serializable
private data class DirectOrderResponseWire(
    val id: String? = null,
    val number: String? = null,
    val tenantId: String? = null,
    val workspaceId: String? = null,
    val clientAccountId: String? = null,
    val createdByMembershipId: String? = null,
    val buyerMembershipId: String? = null,
    val sourcePurchaseRequestId: String? = null,
    val priority: String? = null,
    val requestedDeliveryDate: String? = null,
    val deliverySnapshot: String? = null,
    val paymentOption: String? = null,
    val notes: String? = null,
    val currency: String? = null,
    val total: JsonPrimitive? = null,
    val status: String? = null,
    val version: Long? = null,
    val lines: List<DirectOrderLineResponseWire> = emptyList(),
    val originType: String? = null,
    val commercialCommitmentId: String? = null
)

@Serializable
private data class DirectOrderLineResponseWire(
    val catalogItemId: String? = null,
    val itemName: String? = null,
    val quantity: JsonPrimitive? = null,
    val unit: String? = null,
    val unitPriceAmount: JsonPrimitive? = null,
    val unitPriceCurrency: String? = null,
    val lineSubtotal: JsonPrimitive? = null
)
