package com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.commercial

data class CommercialProductChoice(
    val id: String,
    val name: String,
    val skuCode: String,
    val imageFileName: String? = null
)

data class CommercialProductFacts(
    val catalogItemId: String,
    val productId: String,
    val name: String,
    val skuId: String?,
    val skuCode: String?,
    val unit: String?,
    val price: String?,
    val currency: String?,
    val pricingAsOf: String?,
    val availabilityStatus: String?,
    val sellableAvailability: String?,
    val availabilityAsOf: String?,
    val imageFileName: String? = null
)
