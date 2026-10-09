package com.nexa.mobile.operations.businessdocuments.application.commercial

import com.nexa.mobile.operations.businessdocuments.domain.model.commercial.BusinessDocumentContent
import com.nexa.mobile.operations.businessdocuments.domain.model.commercial.BusinessDocumentIdentity
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority

sealed interface BusinessDocumentsResult {
    data class Page(val items: List<BusinessDocumentIdentity>, val totalItems: Long) :
        BusinessDocumentsResult
    data class Content(val value: BusinessDocumentContent) : BusinessDocumentsResult
    data object Denied : BusinessDocumentsResult
    data object Unavailable : BusinessDocumentsResult
}

interface BusinessDocumentsGateway {
    suspend fun list(authority: CommercialAuthority, page: Int): BusinessDocumentsResult
    suspend fun content(authority: CommercialAuthority, id: String): BusinessDocumentsResult
}
