package com.nexa.mobile.operations.feature.commercial.application

import com.nexa.mobile.operations.feature.commercial.model.BusinessDocumentContent
import com.nexa.mobile.operations.feature.commercial.model.BusinessDocumentIdentity
import com.nexa.mobile.operations.feature.commercial.model.CommercialAuthority

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
