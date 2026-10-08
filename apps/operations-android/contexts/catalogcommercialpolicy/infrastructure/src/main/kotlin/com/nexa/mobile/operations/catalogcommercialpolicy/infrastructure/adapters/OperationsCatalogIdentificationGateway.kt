package com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.adapters

import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.CandidateConfirmationResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.ProductSearchResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.warehouse.WarehouseGateway
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.CatalogOperationsContext
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ConfirmedSkuProjection
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ProductCandidate
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.CatalogDetailProjection
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.NexaOperationsCatalogGateway
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.OperationsCatalogDetailOutcome
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.OperationsCatalogDetailProjection
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.OperationsCatalogSearchOutcome
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.contextIsCurrent
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.catalogReadHint
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.PermissionHint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

@Singleton
class OperationsCatalogIdentificationGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val operationsCatalog: NexaOperationsCatalogGateway
) : WarehouseGateway {
    override suspend fun search(
        query: String,
        pageKey: String?,
        authorityEpoch: Long
    ): ProductSearchResult {
        val access = sessions.currentAccess() ?: return ProductSearchResult.SessionInvalidated
        val context = sessions.verifiedSession.value
            ?: return ProductSearchResult.ContextInvalidated
        val result = operationsCatalog.search(query, pageKey)
        if (!sessions.isEpochCurrent(access.epoch)) return ProductSearchResult.SessionInvalidated
        val current = sessions.verifiedSession.value
            ?: return ProductSearchResult.ContextInvalidated
        if (!contextIsCurrent(context, current)) return ProductSearchResult.ContextInvalidated
        if (catalogReadHint(current.permissions) ==
            PermissionHint.Unavailable
        ) {
            return ProductSearchResult.PermissionDenied
        }
        return when (result) {
            is OperationsCatalogSearchOutcome.Page -> ProductSearchResult.Page(
                result.value.candidates.map {
                    ProductCandidate(
                        key = it.catalogItemId,
                        productDisplayName = it.itemName,
                        brandOrVariant = it.brandName,
                        presentation = it.presentation,
                        sku = it.skuCode,
                        imageFileName = it.imageFileName,
                        detailKey = it.productId
                    )
                },
                result.value.nextPageKey
            )

            OperationsCatalogSearchOutcome.InvalidQuery -> ProductSearchResult.InvalidQuery

            OperationsCatalogSearchOutcome.NetworkUnavailable ->
                ProductSearchResult.NetworkUnavailable

            OperationsCatalogSearchOutcome.ServiceUnavailable ->
                ProductSearchResult.ServiceUnavailable

            OperationsCatalogSearchOutcome.PermissionDenied -> ProductSearchResult.PermissionDenied

            OperationsCatalogSearchOutcome.ContextInvalidated -> {
                sessions.invalidateContext()
                ProductSearchResult.ContextInvalidated
            }

            OperationsCatalogSearchOutcome.SessionExpired -> ProductSearchResult.SessionInvalidated
        }
    }

    override suspend fun confirm(
        candidate: ProductCandidate,
        authorityEpoch: Long,
        context: CatalogOperationsContext
    ): CandidateConfirmationResult {
        val access = sessions.currentAccess()
            ?: return CandidateConfirmationResult.SessionInvalidated
        val verified = sessions.verifiedSession.value
            ?: return CandidateConfirmationResult.ContextInvalidated
        val detailKey = candidate.detailKey
        if (detailKey == null) return CandidateConfirmationResult.CandidateUnavailable
        val result = operationsCatalog.loadDetail(detailKey)
        if (!sessions.isEpochCurrent(access.epoch)) {
            return CandidateConfirmationResult.SessionInvalidated
        }
        val current = sessions.verifiedSession.value
            ?: return CandidateConfirmationResult.ContextInvalidated
        if (!contextIsCurrent(
                verified,
                current
            )
        ) {
            return CandidateConfirmationResult.ContextInvalidated
        }
        if (catalogReadHint(current.permissions) ==
            PermissionHint.Unavailable
        ) {
            return CandidateConfirmationResult.PermissionDenied
        }
        return when (result) {
            is OperationsCatalogDetailOutcome.Found -> mapConfirmedCatalogDetail(
                candidate = candidate,
                detail = result.value.toCatalogDetailProjection(),
                context = context,
                authorityEpoch = authorityEpoch
            )

            OperationsCatalogDetailOutcome.CandidateUnavailable ->
                CandidateConfirmationResult.CandidateUnavailable

            OperationsCatalogDetailOutcome.NetworkUnavailable ->
                CandidateConfirmationResult.NetworkUnavailable

            OperationsCatalogDetailOutcome.ServiceUnavailable ->
                CandidateConfirmationResult.ServiceUnavailable

            OperationsCatalogDetailOutcome.PermissionDenied ->
                CandidateConfirmationResult.PermissionDenied

            OperationsCatalogDetailOutcome.ContextInvalidated -> {
                sessions.invalidateContext()
                CandidateConfirmationResult.ContextInvalidated
            }

            OperationsCatalogDetailOutcome.SessionExpired ->
                CandidateConfirmationResult.SessionInvalidated
        }
    }
}

private fun OperationsCatalogDetailProjection.toCatalogDetailProjection(): CatalogDetailProjection =
    CatalogDetailProjection(
        catalogItemId = catalogItemId,
        itemName = itemName,
        presentation = presentation,
        skuCode = skuCode,
        brandName = brandName,
        productVariantName = null,
        productFamilyName = null,
        unitOfMeasure = unitOfMeasure,
        packagingType = null,
        coldChainRequirement = storageTemperature,
        imageFileName = imageFileName
    )

fun mapConfirmedCatalogDetail(
    candidate: ProductCandidate,
    detail: CatalogDetailProjection,
    context: CatalogOperationsContext,
    authorityEpoch: Long
): CandidateConfirmationResult {
    if (detail.catalogItemId != candidate.key || detail.skuCode != candidate.sku) {
        return CandidateConfirmationResult.CandidateUnavailable
    }
    return CandidateConfirmationResult.Confirmed(
        ConfirmedSkuProjection(
            candidateKey = candidate.key,
            productDisplayName = detail.productFamilyName ?: detail.itemName,
            variant = detail.productVariantName,
            presentation = detail.presentation,
            sku = detail.skuCode,
            brand = detail.brandName,
            unit = detail.unitOfMeasure,
            packaging = detail.packagingType,
            coldChain = detail.coldChainRequirement,
            context = context,
            authorityEpoch = authorityEpoch,
            imageFileName = detail.imageFileName
        )
    )
}
