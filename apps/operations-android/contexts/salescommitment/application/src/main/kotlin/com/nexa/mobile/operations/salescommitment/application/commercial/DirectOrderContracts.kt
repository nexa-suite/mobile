package com.nexa.mobile.operations.salescommitment.application.commercial

import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderIntent
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderReceipt
import com.nexa.mobile.operations.salescommitment.application.model.commercial.DirectOrderRecord
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.DirectOrderDraft
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.DirectOrderLine
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority

sealed interface DirectOrderRead {
    data class Available(val record: DirectOrderRecord) : DirectOrderRead
    data object Unavailable : DirectOrderRead
}

interface DirectOrderStore {
    suspend fun load(authority: CommercialAuthority): DirectOrderRead
    suspend fun save(authority: CommercialAuthority, record: DirectOrderRecord): Boolean
}

sealed interface DirectOrderReview {
    data class Current(val draft: DirectOrderDraft) : DirectOrderReview
    data object PermissionDenied : DirectOrderReview
    data object Unavailable : DirectOrderReview
}

sealed interface DirectOrderSubmission {
    data class Confirmed(val receipt: DirectOrderReceipt) : DirectOrderSubmission
    data class PrepaidPending(val receipt: DirectOrderReceipt) : DirectOrderSubmission
    data object Conflict : DirectOrderSubmission
    data object PermissionDenied : DirectOrderSubmission
    data object Rejected : DirectOrderSubmission
    data object Unavailable : DirectOrderSubmission
    data object LegacyIntent : DirectOrderSubmission
    data object UnknownOutcome : DirectOrderSubmission
}

interface DirectOrderGateway {
    suspend fun quote(
        authority: CommercialAuthority,
        customerId: String,
        productId: String,
        quantity: String
    ): DirectOrderLine?
    suspend fun review(authority: CommercialAuthority, draft: DirectOrderDraft): DirectOrderReview
    fun freeze(draft: DirectOrderDraft): String
    suspend fun submit(
        authority: CommercialAuthority,
        intent: DirectOrderIntent
    ): DirectOrderSubmission
}
