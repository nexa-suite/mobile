package com.nexa.mobile.operations.tenantaccessgovernance.domain.model.operations

data class VerifiedOperationsIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>
) {
    override fun toString(): String = "VerifiedOperationsIdentity(REDACTED)"
}

data class ActiveOperationsContext(
    val companyName: String,
    val workspaceName: String,
    val authorityEpoch: Long,
    val verifiedIdentity: VerifiedOperationsIdentity? = null
)
