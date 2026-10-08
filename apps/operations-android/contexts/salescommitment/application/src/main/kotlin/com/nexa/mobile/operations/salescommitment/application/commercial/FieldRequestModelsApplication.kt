package com.nexa.mobile.operations.salescommitment.application.commercial

import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestIntent
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestReceipt
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestRecord
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.FieldRequestDraft
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.FieldRequestLine
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority

sealed interface FieldRequestRead {
    data class Available(val record: FieldRequestRecord) : FieldRequestRead
    data object Unavailable : FieldRequestRead
}

interface FieldRequestStore {
    suspend fun load(authority: CommercialAuthority): FieldRequestRead
    suspend fun save(authority: CommercialAuthority, record: FieldRequestRecord): Boolean
}

sealed interface FieldRequestReview {
    data class Current(val draft: FieldRequestDraft) : FieldRequestReview
    data object PermissionDenied : FieldRequestReview
    data object Unavailable : FieldRequestReview
}

sealed interface FieldRequestSubmission {
    data class Confirmed(val receipt: FieldRequestReceipt) : FieldRequestSubmission
    data class PrepaidPending(val receipt: FieldRequestReceipt) : FieldRequestSubmission
    data object Conflict : FieldRequestSubmission
    data object PermissionDenied : FieldRequestSubmission
    data object Rejected : FieldRequestSubmission
    data object Unavailable : FieldRequestSubmission
    data object LegacyIntent : FieldRequestSubmission
    data object UnknownOutcome : FieldRequestSubmission
}

interface FieldRequestGateway {
    suspend fun quote(
        authority: CommercialAuthority,
        customerId: String,
        productId: String,
        quantity: String
    ): FieldRequestLine?
    suspend fun review(authority: CommercialAuthority, draft: FieldRequestDraft): FieldRequestReview
    fun freeze(draft: FieldRequestDraft): String
    suspend fun submit(
        authority: CommercialAuthority,
        intent: FieldRequestIntent
    ): FieldRequestSubmission
}
