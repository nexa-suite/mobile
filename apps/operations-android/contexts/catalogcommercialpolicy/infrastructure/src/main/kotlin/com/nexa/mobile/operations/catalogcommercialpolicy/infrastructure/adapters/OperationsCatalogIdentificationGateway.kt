package com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.adapters

import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.CandidateConfirmationResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.ProductFilterOptionsResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.ProductSearchResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.WarehouseCatalogFilterChoice
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.WarehouseCatalogFilterKind
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.WarehouseCatalogFilterPage
import com.nexa.mobile.operations.catalogcommercialpolicy.application.warehouse.WarehouseGateway
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.CatalogOperationsContext
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ConfirmedSkuProjection
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ProductCandidate
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.CatalogDetailOutcome
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.CatalogDetailProjection
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.CatalogFilterOptionsOutcome
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.CatalogSearchOutcome
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.NexaCatalogGateway
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.contextIsCurrent
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.PermissionHint
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.catalogReadHint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

@Singleton
class OperationsCatalogIdentificationGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    calls: ProtectedCallExecutor
) : WarehouseGateway {
    private val catalog = NexaCatalogGateway(calls)

    override suspend fun search(
        query: String,
        brandName: String?,
        categoryName: String?,
        pageKey: String?,
        authorityEpoch: Long
    ): ProductSearchResult {
        val access = sessions.currentAccess() ?: return ProductSearchResult.SessionInvalidated
        val context = sessions.verifiedSession.value
            ?: return ProductSearchResult.ContextInvalidated
        val result = catalog.search(query, pageKey, brandName, categoryName)
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
            is CatalogSearchOutcome.Page -> ProductSearchResult.Page(
                result.value.candidates.map {
                    ProductCandidate(
                        key = it.catalogItemId,
                        productDisplayName = it.itemName,
                        brandOrVariant = it.brandName,
                        presentation = it.presentation,
                        sku = it.skuCode,
                        imageFileName = it.imageFileName,
                        detailKey = it.catalogItemId
                    )
                },
                result.value.nextPageKey
            )

            CatalogSearchOutcome.InvalidQuery -> ProductSearchResult.InvalidQuery

            CatalogSearchOutcome.NetworkUnavailable ->
                ProductSearchResult.NetworkUnavailable

            CatalogSearchOutcome.ServiceUnavailable ->
                ProductSearchResult.ServiceUnavailable

            CatalogSearchOutcome.PermissionDenied -> ProductSearchResult.PermissionDenied

            CatalogSearchOutcome.ContextInvalidated -> {
                sessions.invalidateContext()
                ProductSearchResult.ContextInvalidated
            }

            CatalogSearchOutcome.SessionExpired -> ProductSearchResult.SessionInvalidated
        }
    }

    override suspend fun filterOptions(
        kind: WarehouseCatalogFilterKind,
        search: String,
        page: Int,
        authorityEpoch: Long
    ): ProductFilterOptionsResult {
        val access =
            sessions.currentAccess() ?: return ProductFilterOptionsResult.SessionInvalidated
        val context = sessions.verifiedSession.value
            ?: return ProductFilterOptionsResult.ContextInvalidated
        val result = catalog.filterOptions(kind.wireValue, search, page)
        if (!sessions.isEpochCurrent(access.epoch)) {
            return ProductFilterOptionsResult.SessionInvalidated
        }
        val current = sessions.verifiedSession.value
            ?: return ProductFilterOptionsResult.ContextInvalidated
        if (!contextIsCurrent(context, current)) {
            return ProductFilterOptionsResult.ContextInvalidated
        }
        if (catalogReadHint(current.permissions) == PermissionHint.Unavailable) {
            return ProductFilterOptionsResult.PermissionDenied
        }
        return when (result) {
            is CatalogFilterOptionsOutcome.Page -> ProductFilterOptionsResult.Page(
                WarehouseCatalogFilterPage(
                    items = result.items.map { WarehouseCatalogFilterChoice(it.id, it.name) },
                    page = result.page,
                    size = result.size,
                    total = result.total
                )
            )

            CatalogFilterOptionsOutcome.InvalidQuery -> ProductFilterOptionsResult.InvalidQuery

            CatalogFilterOptionsOutcome.NetworkUnavailable ->
                ProductFilterOptionsResult.NetworkUnavailable

            CatalogFilterOptionsOutcome.ServiceUnavailable ->
                ProductFilterOptionsResult.ServiceUnavailable

            CatalogFilterOptionsOutcome.PermissionDenied ->
                ProductFilterOptionsResult.PermissionDenied

            CatalogFilterOptionsOutcome.ContextInvalidated -> {
                sessions.invalidateContext()
                ProductFilterOptionsResult.ContextInvalidated
            }

            CatalogFilterOptionsOutcome.SessionExpired ->
                ProductFilterOptionsResult.SessionInvalidated
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
        val result = catalog.loadDetail(candidate.key)
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
            is CatalogDetailOutcome.Found -> mapConfirmedCatalogDetail(
                candidate = candidate,
                detail = result.value,
                context = context,
                authorityEpoch = authorityEpoch
            )

            CatalogDetailOutcome.CandidateUnavailable ->
                CandidateConfirmationResult.CandidateUnavailable

            CatalogDetailOutcome.NetworkUnavailable ->
                CandidateConfirmationResult.NetworkUnavailable

            CatalogDetailOutcome.ServiceUnavailable ->
                CandidateConfirmationResult.ServiceUnavailable

            CatalogDetailOutcome.PermissionDenied ->
                CandidateConfirmationResult.PermissionDenied

            CatalogDetailOutcome.ContextInvalidated -> {
                sessions.invalidateContext()
                CandidateConfirmationResult.ContextInvalidated
            }

            CatalogDetailOutcome.SessionExpired ->
                CandidateConfirmationResult.SessionInvalidated
        }
    }
}

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
