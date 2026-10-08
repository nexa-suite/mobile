package com.nexa.mobile.operations.customerbuyerrelationships.domain.model.commercial

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
