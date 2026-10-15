package com.nexa.mobile.operations.salescommitment.presentation.commercial

import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestCustomerFacts
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestProductFacts
import com.nexa.mobile.operations.salescommitment.application.model.commercial.FieldRequestDraft

enum class FieldRequestDraftStatus {
    Loading,
    Saved,
    Saving,
    AddingProduct,
    Reviewing,
    Reviewed,
    ReviewUnavailable,
    ProductUnavailable,
    MetadataUnavailable
}

data class FieldRequestDraftState(
    val draft: FieldRequestDraft = FieldRequestDraft(),
    val status: FieldRequestDraftStatus = FieldRequestDraftStatus.Loading,
    val currentProducts: Map<String, FieldRequestProductFacts> = emptyMap(),
    val changedProducts: Set<String> = emptySet(),
    val currentCustomer: FieldRequestCustomerFacts? = null,
    val customerChanged: Boolean = false
)
