package com.nexa.mobile.operations.feature.commercial.model

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

data class CustomerRelationship(
    val id: String,
    val code: String,
    val name: String,
    val commercialName: String?,
    val active: Boolean,
    val buyerLinked: Boolean,
    val version: Long,
    val contactPerson: String? = null,
    val email: String? = null,
    val phone: String? = null
) {
    override fun toString(): String = "CustomerRelationship(REDACTED)"
}

enum class CustomerSearchStatus { Idle, Loading, Current, Unavailable, PermissionDenied }
