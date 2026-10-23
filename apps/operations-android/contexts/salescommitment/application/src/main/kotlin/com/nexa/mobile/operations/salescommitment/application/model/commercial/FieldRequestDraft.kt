package com.nexa.mobile.operations.salescommitment.application.model.commercial

data class FieldRequestProductFacts(
    val catalogItemId: String,
    val productId: String,
    val name: String,
    val skuCode: String?,
    val brandName: String?,
    val categoryName: String?,
    val familyName: String?,
    val variantName: String?,
    val unit: String?,
    val price: String?,
    val currency: String?,
    val pricingAsOf: String?,
    val availability: String?,
    val availabilityStatus: String?,
    val availabilityAsOf: String?
) {
    override fun toString(): String = "FieldRequestProductFacts(REDACTED)"
}

data class FieldRequestCustomerFacts(
    val businessName: String,
    val status: String,
    val version: Long
) {
    override fun toString(): String = "FieldRequestCustomerFacts(REDACTED)"
}

data class FieldRequestDraft(
    val customerId: String = "",
    val lines: List<FieldRequestDraftLine> = emptyList(),
    val customerName: String? = null,
    val customerVersion: Long? = null
) {
    override fun toString(): String = "FieldRequestDraft(REDACTED)"
}

data class FieldRequestDraftLine(
    val catalogItemId: String,
    val productId: String,
    val name: String,
    val skuCode: String?,
    val brandName: String?,
    val categoryName: String?,
    val familyName: String?,
    val variantName: String?,
    val unit: String?,
    val quantity: String,
    val price: String?,
    val currency: String?,
    val pricingAsOf: String?,
    val availability: String?,
    val availabilityStatus: String?,
    val availabilityAsOf: String?
) {
    override fun toString(): String = "FieldRequestDraftLine(REDACTED)"
}
