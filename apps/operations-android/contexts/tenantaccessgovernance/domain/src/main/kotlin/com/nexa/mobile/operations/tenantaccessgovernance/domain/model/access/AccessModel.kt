package com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access

enum class PermissionHint { Available, Unavailable, Unknown }

/** Identity and permissions from a verified current session, never from a context list. */

data class VerifiedContextAuthority(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>
) {
    override fun toString(): String = "VerifiedContextAuthority(REDACTED)"
}

data class WorkforceContextSummary(
    val key: String,
    val companyName: String,
    val workspaceName: String,
    val permissionHint: PermissionHint = PermissionHint.Unknown,
    val isCurrent: Boolean = false,
    val verifiedAuthority: VerifiedContextAuthority? = null
) {
    override fun toString(): String = "WorkforceContextSummary(companyName=$companyName, " +
        "workspaceName=$workspaceName, key=REDACTED)"
}
