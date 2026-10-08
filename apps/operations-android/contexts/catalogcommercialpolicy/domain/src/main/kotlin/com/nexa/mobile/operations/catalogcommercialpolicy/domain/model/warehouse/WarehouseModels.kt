package com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse

import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.operations.ActiveOperationsContext

data class ProductCandidate(
    val key: String,
    val productDisplayName: String,
    val brandOrVariant: String?,
    val presentation: String,
    val sku: String,
    val imageFileName: String? = null,
    /** Opaque server detail key used by an authorized catalog projection. */
    val detailKey: String? = null
) {
    override fun toString(): String =
        "ProductCandidate(productDisplayName=$productDisplayName, sku=REDACTED, key=REDACTED)"
}

data class ConfirmedSkuProjection(
    val candidateKey: String,
    val productDisplayName: String,
    val variant: String?,
    val presentation: String,
    val sku: String,
    val brand: String?,
    val unit: String?,
    val packaging: String?,
    val coldChain: String?,
    val context: ActiveOperationsContext,
    val authorityEpoch: Long,
    val imageFileName: String? = null
) {
    override fun toString(): String =
        "ConfirmedSkuProjection(productDisplayName=$productDisplayName, " +
            "sku=REDACTED, authorityEpoch=$authorityEpoch)"
}
