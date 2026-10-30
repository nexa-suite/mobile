package com.nexa.mobile.operations.tenantaccessgovernance.domain.model.profile

/** Current user profile projection; roles and account ownership are not editable here. */
data class OwnProfile(
    val userId: String,
    val email: String,
    val displayName: String,
    val firstName: String?,
    val lastName: String?,
    val phone: String?,
    val preferredLanguage: String,
    val timezone: String,
    val version: Long
) {
    override fun toString(): String = "OwnProfile(REDACTED)"
}

data class OwnProfileEdit(
    val displayName: String,
    val firstName: String,
    val lastName: String,
    val phone: String,
    val preferredLanguage: String
) {
    override fun toString(): String = "OwnProfileEdit(REDACTED)"
}
