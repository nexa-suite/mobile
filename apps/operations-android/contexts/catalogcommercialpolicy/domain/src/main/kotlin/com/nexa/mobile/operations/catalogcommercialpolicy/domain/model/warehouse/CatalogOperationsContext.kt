package com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse

/** Catalog-owned projection of the active workforce scope used to correlate catalog reads. */
data class CatalogOperationsContext(
    val companyName: String,
    val workspaceName: String,
    val authorityEpoch: Long,
    val verifiedIdentity: CatalogOperationsIdentity? = null
)

data class CatalogOperationsIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>
) {
    override fun toString(): String = "CatalogOperationsIdentity(REDACTED)"
}
