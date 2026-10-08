package com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi

/** Immutable customer relationship facts; authorization remains with the server. */
data class CommercialCustomerFacts(val businessName: String, val status: String, val version: Long)

sealed interface CommercialCustomerRead {
    data class Detail(val value: CommercialCustomerFacts) : CommercialCustomerRead
    data object Unavailable : CommercialCustomerRead
}

interface CommercialCustomerQuery {
    suspend fun detail(customerId: String): CommercialCustomerRead
}
