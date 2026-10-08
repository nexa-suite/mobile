package com.nexa.mobile.operations.customerbuyerrelationships.application.commercial

import com.nexa.mobile.operations.customerbuyerrelationships.domain.model.commercial.CustomerRelationship
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority

sealed interface CustomerResult {
    data class Page(val items: List<CustomerRelationship>, val page: Int, val total: Long) :
        CustomerResult
    data class Detail(val customer: CustomerRelationship) : CustomerResult
    data object Unavailable : CustomerResult
    data object PermissionDenied : CustomerResult
}

interface CustomerGateway {
    suspend fun search(authority: CommercialAuthority, query: String, page: Int): CustomerResult
    suspend fun detail(authority: CommercialAuthority, id: String): CustomerResult
}
