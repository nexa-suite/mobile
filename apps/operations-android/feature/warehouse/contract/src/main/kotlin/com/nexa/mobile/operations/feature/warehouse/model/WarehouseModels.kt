package com.nexa.mobile.operations.feature.warehouse.model

data class VerifiedOperationsIdentity(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val permissions: Set<String>
) {
    override fun toString(): String = "VerifiedOperationsIdentity(REDACTED)"
}

data class ActiveOperationsContext(
    val companyName: String,
    val workspaceName: String,
    val authorityEpoch: Long,
    val verifiedIdentity: VerifiedOperationsIdentity? = null
)

data class ProductCandidate(
    val key: String,
    val productDisplayName: String,
    val brandOrVariant: String?,
    val presentation: String,
    val sku: String,
    val imageFileName: String? = null,
    /** Opaque server detail key used by an authorized catalog projection. */
    val detailKey: String? = null
) {
    override fun toString(): String =
        "ProductCandidate(productDisplayName=$productDisplayName, sku=REDACTED, key=REDACTED)"
}

data class ConfirmedSkuProjection(
    val candidateKey: String,
    val productDisplayName: String,
    val variant: String?,
    val presentation: String,
    val sku: String,
    val brand: String?,
    val unit: String?,
    val packaging: String?,
    val coldChain: String?,
    val context: ActiveOperationsContext,
    val authorityEpoch: Long,
    val imageFileName: String? = null
) {
    override fun toString(): String =
        "ConfirmedSkuProjection(productDisplayName=$productDisplayName, " +
            "sku=REDACTED, authorityEpoch=$authorityEpoch)"
}

sealed interface ProductSearchResult {
    data class Page(val items: List<ProductCandidate>, val nextPageKey: String?) :
        ProductSearchResult
    data object InvalidQuery : ProductSearchResult
    data object NetworkUnavailable : ProductSearchResult
    data object ServiceUnavailable : ProductSearchResult
    data object PermissionDenied : ProductSearchResult
    data object ContextInvalidated : ProductSearchResult
    data object SessionInvalidated : ProductSearchResult
    data object IntegrationUnavailable : ProductSearchResult
}

sealed interface CandidateConfirmationResult {
    data class Confirmed(val sku: ConfirmedSkuProjection) : CandidateConfirmationResult
    data object CandidateUnavailable : CandidateConfirmationResult
    data object NetworkUnavailable : CandidateConfirmationResult
    data object ServiceUnavailable : CandidateConfirmationResult
    data object PermissionDenied : CandidateConfirmationResult
    data object ContextInvalidated : CandidateConfirmationResult
    data object SessionInvalidated : CandidateConfirmationResult
    data object IntegrationUnavailable : CandidateConfirmationResult
    data object UnknownOutcome : CandidateConfirmationResult
}
