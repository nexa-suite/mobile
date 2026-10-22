package com.nexa.mobile.operations.salescommitment.application.commercial

import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestCustomerFacts
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestDraft
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestProductFacts
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority

sealed interface FieldRequestDraftRead {
    data class Available(val draft: FieldRequestDraft) : FieldRequestDraftRead
    data object Unavailable : FieldRequestDraftRead
}

interface FieldRequestDraftStore {
    suspend fun load(authority: CommercialAuthority): FieldRequestDraftRead
    suspend fun save(authority: CommercialAuthority, draft: FieldRequestDraft): Boolean
}

sealed interface FieldRequestProductRead {
    data class Current(val facts: FieldRequestProductFacts) : FieldRequestProductRead
    data object Unavailable : FieldRequestProductRead
    data object PermissionDenied : FieldRequestProductRead
}

sealed interface FieldRequestCustomerRead {
    data class Current(val facts: FieldRequestCustomerFacts) : FieldRequestCustomerRead
    data object Unavailable : FieldRequestCustomerRead
    data object PermissionDenied : FieldRequestCustomerRead
}

interface FieldRequestDraftGateway {
    suspend fun product(
        authority: CommercialAuthority,
        customerId: String,
        catalogItemId: String
    ): FieldRequestProductRead

    suspend fun customer(
        authority: CommercialAuthority,
        customerId: String
    ): FieldRequestCustomerRead
}
