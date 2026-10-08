package com.nexa.mobile.operations.catalogcommercialpolicy.application.publicapi

data class QuotedUnitPrice(val amount: String, val currency: String)

/** Quoted server facts, with no local pricing or availability decision. */
data class CustomerOfferFacts(
    val catalogItemId: String,
    val itemName: String,
    val unitOfMeasure: String?,
    val currentOfferPrice: QuotedUnitPrice?,
    val pricingAsOf: String?
)

sealed interface CustomerOfferRead {
    data class Found(val value: CustomerOfferFacts) : CustomerOfferRead
    data object Unavailable : CustomerOfferRead
}

interface CustomerOfferQuery {
    suspend fun detail(customerId: String, productId: String, quantity: String): CustomerOfferRead
}
