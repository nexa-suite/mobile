package com.nexa.mobile.operations.feature.commercial.application

import com.nexa.mobile.operations.feature.commercial.model.CommercialAuthority
import com.nexa.mobile.operations.feature.commercial.model.FieldRequestDraft
import com.nexa.mobile.operations.feature.commercial.model.FieldRequestIntent
import com.nexa.mobile.operations.feature.commercial.model.FieldRequestLine
import com.nexa.mobile.operations.feature.commercial.model.FieldRequestRecord

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
    data class Confirmed(val id: String) : FieldRequestSubmission
    data object Conflict : FieldRequestSubmission
    data object PermissionDenied : FieldRequestSubmission
    data object Rejected : FieldRequestSubmission
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
