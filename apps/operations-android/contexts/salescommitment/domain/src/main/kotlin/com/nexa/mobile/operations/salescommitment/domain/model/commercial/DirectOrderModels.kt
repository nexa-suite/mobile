package com.nexa.mobile.operations.salescommitment.domain.model.commercial

data class DirectOrderLine(
    val catalogItemId: String,
    val name: String,
    val quantity: String,
    val unit: String?,
    val price: String,
    val currency: String,
    val asOf: String
)

data class DirectOrderDraft(
    val customerId: String = "",
    val customerVersion: Long? = null,
    val lines: List<DirectOrderLine> = emptyList(),
    val deliveryDate: String = "",
    val deliveryProfile: String = "",
    val paymentOption: String = "CASH_ON_DELIVERY",
    val comment: String = ""
) {
    override fun toString(): String = "DirectOrderDraft(REDACTED)"
}
