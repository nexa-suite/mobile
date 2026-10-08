package com.nexa.mobile.operations.salescommitment.application.model.commercial

import com.nexa.mobile.operations.salescommitment.domain.model.commercial.FieldRequestDraft

data class FieldRequestIntent(
    val key: String,
    val exactBody: String,
    val outcome: String = "Pending",
    val receiptId: String? = null,
    val operation: String = DIRECT_ORDER_OPERATION,
    val receipt: FieldRequestReceipt? = null
) {
    override fun toString(): String = "FieldRequestIntent(REDACTED)"

    companion object {
        const val DIRECT_ORDER_OPERATION = "direct-order-v1"
        const val LEGACY_FIELD_REQUEST_OPERATION = "field-request-v1"
    }
}

data class FieldRequestReceipt(
    val id: String,
    val number: String,
    val status: String,
    val paymentOption: String,
    val currency: String,
    val total: String,
    val version: Long
) {
    override fun toString(): String = "FieldRequestReceipt(REDACTED)"
}

data class FieldRequestRecord(
    val draft: FieldRequestDraft = FieldRequestDraft(),
    val intent: FieldRequestIntent? = null
)
