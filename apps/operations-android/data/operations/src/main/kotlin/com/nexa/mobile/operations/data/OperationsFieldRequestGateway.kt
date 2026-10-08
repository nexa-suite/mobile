package com.nexa.mobile.operations.data

import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.CommercialCatalogNetworkResult
import com.nexa.mobile.operations.core.network.CustomerNetworkResult
import com.nexa.mobile.operations.core.network.NexaCommercialCatalogGateway
import com.nexa.mobile.operations.core.network.NexaCustomerGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ProtectedMethod
import com.nexa.mobile.operations.core.network.ProtectedRequest
import com.nexa.mobile.operations.core.network.ProtectedResult
import com.nexa.mobile.operations.feature.commercial.application.FieldRequestGateway
import com.nexa.mobile.operations.feature.commercial.application.FieldRequestReview
import com.nexa.mobile.operations.feature.commercial.application.FieldRequestSubmission
import com.nexa.mobile.operations.feature.commercial.model.CommercialAuthority
import com.nexa.mobile.operations.feature.commercial.model.FieldRequestDraft
import com.nexa.mobile.operations.feature.commercial.model.FieldRequestIntent
import com.nexa.mobile.operations.feature.commercial.model.FieldRequestLine
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
class OperationsFieldRequestGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val calls: ProtectedCallExecutor
) : FieldRequestGateway {
    private val customers = NexaCustomerGateway(calls)
    private val offers = NexaCommercialCatalogGateway(calls)
    private fun current(a: CommercialAuthority): Boolean {
        val verified = sessions.verifiedSession.value ?: return false
        return sessions.sessionState.value == SessionState.Active && a.canReadCustomers &&
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
    ): FieldRequestLine? {
        if (!current(authority) || quantity.toBigDecimalOrNull()?.signum() != 1) return null
        val lease = sessions.currentAccess() ?: return null
        val customer =
            (customers.detail(customerId) as? CustomerNetworkResult.Detail)?.value ?: return null
        if (customer.status != "ACTIVE") return null
        val item =
            (
                offers.detail(
                    customerId,
                    productId,
                    quantity
                ) as? CommercialCatalogNetworkResult.Found
                )?.value
                ?: return null
        val after =
            (customers.detail(customerId) as? CustomerNetworkResult.Detail)?.value ?: return null
        val money = item.currentOfferPrice ?: return null
        if (!current(
                authority
            ) || !sessions.isEpochCurrent(lease.epoch) || after.status != "ACTIVE" ||
            customer.version != after.version ||
            money.amount.content.toBigDecimalOrNull()?.signum()?.let { it < 0 } != false ||
            !Regex("[A-Z]{3}").matches(money.currency) ||
            runCatching { Instant.parse(item.pricingAsOf) }.isFailure
        ) {
            return null
        }
        return FieldRequestLine(
            item.catalogItemId,
            item.itemName,
            quantity.toBigDecimal().toPlainString(),
            item.unitOfMeasure,
            money.amount.content,
            money.currency,
            item.pricingAsOf!!
        )
    }
    override suspend fun review(
        authority: CommercialAuthority,
        draft: FieldRequestDraft
    ): FieldRequestReview {
        if (!current(authority)) return FieldRequestReview.PermissionDenied
        val lease = sessions.currentAccess() ?: return FieldRequestReview.PermissionDenied
        val customer = (customers.detail(draft.customerId) as? CustomerNetworkResult.Detail)?.value
            ?: return FieldRequestReview.PermissionDenied
        if (customer.status != "ACTIVE" ||
            !draft.valid()
        ) {
            return FieldRequestReview.PermissionDenied
        }
        val lines = draft.lines.map {
            quote(authority, draft.customerId, it.catalogItemId, it.quantity)
                ?: return FieldRequestReview.Unavailable
        }
        val after = (customers.detail(draft.customerId) as? CustomerNetworkResult.Detail)?.value
        if (!current(authority) || !sessions.isEpochCurrent(lease.epoch) ||
            after?.status != "ACTIVE"
        ) {
            return FieldRequestReview.PermissionDenied
        }
        if (after.version != customer.version) return FieldRequestReview.Unavailable
        return FieldRequestReview.Current(
            draft.copy(customerVersion = customer.version, lines = lines)
        )
    }
    override fun freeze(draft: FieldRequestDraft): String = buildJsonObject {
        put("clientAccountId", draft.customerId)
        put("priority", "NORMAL")
        put("requestedDeliveryDate", draft.deliveryDate)
        put("deliveryProfileSnapshot", draft.deliveryProfile)
        put("paymentOption", draft.paymentOption)
        put("comment", draft.comment)
        putJsonArray("lines") {
            draft.lines.forEach { line ->
                add(
                    buildJsonObject {
                        put("catalogItemId", line.catalogItemId)
                        put("quantity", JsonPrimitive(line.quantity.toBigDecimal()))
                        line.unit?.let { put("unit", it) }
                        put("expectedUnitPrice", JsonPrimitive(line.price.toBigDecimal()))
                        put("expectedCurrency", line.currency)
                    }
                )
            }
        }
    }.toString()
    override suspend fun submit(
        authority: CommercialAuthority,
        intent: FieldRequestIntent
    ): FieldRequestSubmission {
        if (!current(authority) ||
            authority.permissions.none { it in SALES_WRITE }
        ) {
            return FieldRequestSubmission.PermissionDenied
        }
        val lease = sessions.currentAccess() ?: return FieldRequestSubmission.PermissionDenied
        val body = runCatching { Json.parseToJsonElement(intent.exactBody).jsonObject }.getOrNull()
            ?: return FieldRequestSubmission.Rejected
        val customerId =
            body["clientAccountId"]?.jsonPrimitive?.content
                ?: return FieldRequestSubmission.Rejected
        val customer = (customers.detail(customerId) as? CustomerNetworkResult.Detail)?.value
            ?: return FieldRequestSubmission.PermissionDenied
        if (customer.status != "ACTIVE" || !current(authority) ||
            !sessions.isEpochCurrent(lease.epoch)
        ) {
            return FieldRequestSubmission.PermissionDenied
        }
        val result = calls.execute(
            ProtectedRequest(ProtectedMethod.POST, FIELD_REQUEST_PATH, intent.exactBody, intent.key)
        )
        if (!current(authority) ||
            !sessions.isEpochCurrent(lease.epoch)
        ) {
            return FieldRequestSubmission.UnknownOutcome
        }
        return when (result) {
            is ProtectedResult.Success -> {
                val receipt = runCatching {
                    Json.parseToJsonElement(result.body ?: "").jsonObject
                }.getOrNull()
                val id = receipt?.get("id")?.jsonPrimitive?.content
                if (result.status != 201 ||
                    receipt?.get("clientAccountId")?.jsonPrimitive?.content != customerId ||
                    receipt?.get("status")?.jsonPrimitive?.content != "SUBMITTED" ||
                    id.isNullOrBlank()
                ) {
                    FieldRequestSubmission.UnknownOutcome
                } else {
                    FieldRequestSubmission.Confirmed(id)
                }
            }

            is ProtectedResult.Failure -> when (result.error.httpStatus) {
                409, 412 -> FieldRequestSubmission.Conflict
                400, 422 -> FieldRequestSubmission.Rejected
                401, 403, 404 -> FieldRequestSubmission.PermissionDenied
                else -> FieldRequestSubmission.UnknownOutcome
            }
        }
    }
    private companion object {
        const val FIELD_REQUEST_PATH = "/api/v1/purchase-requests/field-submissions"
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
