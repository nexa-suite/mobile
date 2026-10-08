package com.nexa.mobile.operations.feature.commercial.model

import java.time.LocalDate

data class FieldRequestLine(
    val catalogItemId: String,
    val name: String,
    val quantity: String,
    val unit: String?,
    val price: String,
    val currency: String,
    val asOf: String
)

data class FieldRequestDraft(
    val customerId: String = "",
    val customerVersion: Long? = null,
    val lines: List<FieldRequestLine> = emptyList(),
    val deliveryDate: String = "",
    val deliveryProfile: String = "",
    val paymentOption: String = "CASH_ON_DELIVERY",
    val comment: String = ""
) {
    fun valid(): Boolean = customerId.isNotBlank() && lines.isNotEmpty() && lines.size <= 100 &&
        lines.all { it.quantity.toBigDecimalOrNull()?.signum() == 1 } &&
        runCatching { LocalDate.parse(deliveryDate) }.isSuccess && deliveryProfile.isNotBlank()
    override fun toString(): String = "FieldRequestDraft(REDACTED)"
}

data class FieldRequestIntent(
    val key: String,
    val exactBody: String,
    val outcome: String = "Pending",
    val receiptId: String? = null
) {
    override fun toString(): String = "FieldRequestIntent(REDACTED)"
}

data class FieldRequestRecord(
    val draft: FieldRequestDraft = FieldRequestDraft(),
    val intent: FieldRequestIntent? = null
)
