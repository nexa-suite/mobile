package com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi

/** Verified identity projection used to carry the selected workforce scope across client flows. */
data class VerifiedOperationsIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>
) {
    override fun toString(): String = "VerifiedOperationsIdentity(REDACTED)"
}

/** Current operations scope projection; clients use it to correlate requests, not grant authority. */
data class ActiveOperationsContext(
    val companyName: String,
    val workspaceName: String,
    val authorityEpoch: Long,
    val verifiedIdentity: VerifiedOperationsIdentity? = null
)
