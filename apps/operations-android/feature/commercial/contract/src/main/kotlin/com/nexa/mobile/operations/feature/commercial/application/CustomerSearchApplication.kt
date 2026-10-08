package com.nexa.mobile.operations.feature.commercial.application

import com.nexa.mobile.operations.feature.commercial.model.CommercialAuthority
import com.nexa.mobile.operations.feature.commercial.model.CustomerRelationship

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
