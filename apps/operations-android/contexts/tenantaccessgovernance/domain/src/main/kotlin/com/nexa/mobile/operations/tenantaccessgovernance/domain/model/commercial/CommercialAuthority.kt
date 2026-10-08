package com.nexa.mobile.operations.tenantaccessgovernance.domain.model.commercial

data class CommercialAuthority(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>,
    val authorityEpoch: Long
) {
    val canReadCustomers: Boolean get() = authorityEpoch > 0 &&
        permissions.any { it == "client.read" || it == "sales:read" } &&
        listOf(userId, tenantId, workspaceId, membershipId).none(String::isBlank)
    override fun toString(): String = "CommercialAuthority(REDACTED)"
}
