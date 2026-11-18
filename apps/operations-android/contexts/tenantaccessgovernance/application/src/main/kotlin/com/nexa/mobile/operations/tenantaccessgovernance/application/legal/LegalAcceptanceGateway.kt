package com.nexa.mobile.operations.tenantaccessgovernance.application.legal

/** Server-published terms content. It is a client projection, not a local legal record. */
data class MobileTermsDocument(
    val documentId: String,
    val version: String,
    val content: String,
    val contentDigest: String
) {
    init {
        require(documentId.isNotBlank())
        require(version.isNotBlank())
        require(content.isNotBlank())
        require(contentDigest.isNotBlank())
    }
}

data class MobileTermsAcceptance(
    val document: MobileTermsDocument,
    val accepted: Boolean,
    val acceptedAt: String?
)

/** In-memory lease identity only; it is never persisted or exposed to UI. */
data class MobileTermsSessionLease(val epoch: Long, val generation: Long)

interface LegalAcceptanceGateway {
    suspend fun loadPublicTerms(): MobileTermsDocumentResult

    suspend fun loadAcceptance(): MobileTermsAcceptanceResult

    suspend fun accept(document: MobileTermsDocument): MobileTermsAcceptResult
}

sealed interface MobileTermsDocumentResult {
    data class Available(val document: MobileTermsDocument) : MobileTermsDocumentResult
    data object Unavailable : MobileTermsDocumentResult
}

sealed interface MobileTermsAcceptanceResult {
    data class Available(
        val acceptance: MobileTermsAcceptance,
        val lease: MobileTermsSessionLease
    ) :
        MobileTermsAcceptanceResult

    data object SessionExpired : MobileTermsAcceptanceResult
    data class Unavailable(
        val lease: MobileTermsSessionLease,
        val transportFailure: Boolean = false
    ) : MobileTermsAcceptanceResult
}

sealed interface MobileTermsAcceptResult {
    data object Accepted : MobileTermsAcceptResult
    data object StaleDocument : MobileTermsAcceptResult
    data object SessionExpired : MobileTermsAcceptResult
    data object UnknownOutcome : MobileTermsAcceptResult
    data object Unavailable : MobileTermsAcceptResult
}
