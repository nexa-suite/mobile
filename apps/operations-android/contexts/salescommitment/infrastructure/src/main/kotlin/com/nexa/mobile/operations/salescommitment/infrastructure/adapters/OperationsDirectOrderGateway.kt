package com.nexa.mobile.operations.salescommitment.infrastructure.adapters

import com.nexa.mobile.operations.catalogcommercialpolicy.application.publicapi.CustomerOfferQuery
import com.nexa.mobile.operations.catalogcommercialpolicy.application.publicapi.CustomerOfferRead
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerQuery
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerRead
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderGateway
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderReview
import com.nexa.mobile.operations.salescommitment.application.commercial.DirectOrderSubmission
import com.nexa.mobile.operations.salescommitment.application.commercial.isValidForSubmission
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderIntent
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.DirectOrderDraft
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.DirectOrderLine
import com.nexa.mobile.operations.salescommitment.infrastructure.transport.NexaDirectOrderGateway
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.canReadCustomers
import java.time.Instant
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Protected app adapter rechecks the current relationship and scope independently of UI epochs. */
class OperationsDirectOrderGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val customers: CommercialCustomerQuery,
    private val offers: CustomerOfferQuery,
    calls: ProtectedCallExecutor
) : DirectOrderGateway {
    private val directOrders = NexaDirectOrderGateway(calls)

    private fun current(a: CommercialAuthority): Boolean {
        val verified = sessions.verifiedSession.value ?: return false
        return sessions.sessionState.value == SessionState.Active && a.canReadCustomers() &&
            a.permissions.any { it in setOf("catalog.read", "catalog:read") } &&
            verified.hasAuthorizedContext &&
            verified.userId == a.userId && verified.tenantId == a.tenantId &&
            verified.workspaceId == a.workspaceId &&
            verified.membershipId == a.membershipId && verified.permissions == a.permissions
    }
    override suspend fun quote(
        authority: CommercialAuthority,
        customerId: String,
        productId: String,
        quantity: String
    ): DirectOrderLine? {
        if (!current(authority) || quantity.toBigDecimalOrNull()?.signum() != 1) return null
        val lease = sessions.currentAccess() ?: return null
        val customer =
            (customers.detail(customerId) as? CommercialCustomerRead.Detail)?.value ?: return null
        if (customer.status != "ACTIVE") return null
        val item =
            (
                offers.detail(
                    customerId,
                    productId,
                    quantity
                ) as? CustomerOfferRead.Found
                )?.value
                ?: return null
        val after =
            (customers.detail(customerId) as? CommercialCustomerRead.Detail)?.value ?: return null
        val money = item.currentOfferPrice ?: return null
        if (!current(
                authority
            ) || !sessions.isEpochCurrent(lease.epoch) || after.status != "ACTIVE" ||
            customer.version != after.version ||
            money.amount.toBigDecimalOrNull()?.signum()?.let { it < 0 } != false ||
            !Regex("[A-Z]{3}").matches(money.currency) ||
            runCatching { Instant.parse(item.pricingAsOf) }.isFailure
        ) {
            return null
        }
        return DirectOrderLine(
            item.catalogItemId,
            item.itemName,
            quantity.toBigDecimal().toPlainString(),
            item.unitOfMeasure,
            money.amount,
            money.currency,
            item.pricingAsOf!!
        )
    }
    override suspend fun review(
        authority: CommercialAuthority,
        draft: DirectOrderDraft
    ): DirectOrderReview {
        if (!current(authority)) return DirectOrderReview.PermissionDenied
        val lease = sessions.currentAccess() ?: return DirectOrderReview.PermissionDenied
        val customer = (customers.detail(draft.customerId) as? CommercialCustomerRead.Detail)?.value
            ?: return DirectOrderReview.PermissionDenied
        if (customer.status != "ACTIVE" ||
            !draft.isValidForSubmission()
        ) {
            return DirectOrderReview.PermissionDenied
        }
        val lines = draft.lines.map {
            quote(authority, draft.customerId, it.catalogItemId, it.quantity)
                ?: return DirectOrderReview.Unavailable
        }
        val after = (customers.detail(draft.customerId) as? CommercialCustomerRead.Detail)?.value
        if (!current(authority) || !sessions.isEpochCurrent(lease.epoch) ||
            after?.status != "ACTIVE"
        ) {
            return DirectOrderReview.PermissionDenied
        }
        if (after.version != customer.version) return DirectOrderReview.Unavailable
        return DirectOrderReview.Current(
            draft.copy(customerVersion = customer.version, lines = lines)
        )
    }
    override fun freeze(draft: DirectOrderDraft): String = draft.toDirectOrderRequestBody()
    override suspend fun submit(
        authority: CommercialAuthority,
        intent: DirectOrderIntent
    ): DirectOrderSubmission {
        if (intent.operation != DirectOrderIntent.DIRECT_ORDER_OPERATION) {
            return DirectOrderSubmission.LegacyIntent
        }
        if (!current(authority) ||
            authority.permissions.none { it in SALES_WRITE }
        ) {
            return DirectOrderSubmission.PermissionDenied
        }
        val lease = sessions.currentAccess() ?: return DirectOrderSubmission.PermissionDenied
        val customerId =
            runCatching {
                Json.parseToJsonElement(intent.exactBody).jsonObject["clientAccountId"]
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull()
                ?: return DirectOrderSubmission.Rejected
        val customer = (customers.detail(customerId) as? CommercialCustomerRead.Detail)?.value
            ?: return DirectOrderSubmission.PermissionDenied
        if (customer.status != "ACTIVE" || !current(authority) ||
            !sessions.isEpochCurrent(lease.epoch)
        ) {
            return DirectOrderSubmission.PermissionDenied
        }
        val result = directOrders.submit(authority, intent, customerId)
        if (result == DirectOrderSubmission.PermissionDenied) return result
        if (!current(authority) ||
            !sessions.isEpochCurrent(lease.epoch)
        ) {
            return DirectOrderSubmission.UnknownOutcome
        }
        return result
    }
    private companion object {
        val SALES_WRITE =
            setOf(
                "sales:write",
                "sales.purchase_request.review",
                "sales.order.create_manual",
                "sales.order.manage",
                "client.manage"
            )
    }
}

internal fun DirectOrderDraft.toDirectOrderRequestBody(): String = buildJsonObject {
    put("clientAccountId", customerId)
    put("priority", "NORMAL")
    put("requestedDeliveryDate", deliveryDate)
    put("deliveryProfileSnapshot", deliveryProfile)
    put("paymentOption", paymentOption)
    put("comment", comment)
    putJsonArray("lines") {
        lines.forEach { line ->
            add(
                buildJsonObject {
                    put("catalogItemId", line.catalogItemId.trim())
                    put("quantity", JsonPrimitive(line.quantity.toBigDecimal()))
                    put("unit", requireNotNull(line.unit?.trim()?.takeIf(String::isNotBlank)))
                }
            )
        }
    }
}.toString()
