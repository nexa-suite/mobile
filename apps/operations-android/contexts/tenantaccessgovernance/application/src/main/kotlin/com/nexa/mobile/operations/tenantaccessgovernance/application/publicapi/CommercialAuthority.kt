package com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi

/** Client projection used to correlate commercial reads with a verified access scope. */
data class CommercialAuthority(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>,
    val authorityEpoch: Long
) {
    override fun toString(): String = "CommercialAuthority(REDACTED)"
}

/**
 * Client permission hint for customer reads. The server remains authoritative for every request.
 */
fun CommercialAuthority.canReadCustomers(): Boolean = authorityEpoch > 0 &&
    permissions.any { it == "client.read" || it == "sales:read" } &&
    listOf(userId, tenantId, workspaceId, membershipId).none(String::isBlank)
