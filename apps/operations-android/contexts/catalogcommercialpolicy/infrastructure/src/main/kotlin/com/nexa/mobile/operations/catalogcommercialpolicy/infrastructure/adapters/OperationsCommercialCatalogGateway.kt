package com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.adapters

import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogFilterChoice
import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogFilterKind
import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogFilterOptionsResult
import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogGateway
import com.nexa.mobile.operations.catalogcommercialpolicy.application.commercial.CommercialCatalogResult
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.commercial.CommercialProductChoice
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.commercial.CommercialProductFacts
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.CatalogFilterOptionsOutcome
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.CatalogSearchOutcome
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.CommercialCatalogNetworkResult
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.NexaCatalogGateway
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.NexaCommercialCatalogGateway
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerFacts
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerQuery
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerRead
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.canReadCustomers
import java.time.Instant
import javax.inject.Inject

class OperationsCommercialCatalogGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val customers: CommercialCustomerQuery,
    calls: ProtectedCallExecutor
) : CommercialCatalogGateway {
    private val catalog = NexaCatalogGateway(calls)
    private val commercial = NexaCommercialCatalogGateway(calls)

    override suspend fun filterOptions(
        authority: CommercialAuthority,
        kind: CommercialCatalogFilterKind,
        search: String,
        page: Int
    ): CommercialCatalogFilterOptionsResult {
        if (!current(authority)) return CommercialCatalogFilterOptionsResult.PermissionDenied
        val lease =
            sessions.currentAccess() ?: return CommercialCatalogFilterOptionsResult.PermissionDenied
        val result = catalog.filterOptions(kind.wireValue, search, page)
        if (!sessions.isEpochCurrent(lease.epoch) || !current(authority)) {
            return CommercialCatalogFilterOptionsResult.PermissionDenied
        }
        return when (result) {
            is CatalogFilterOptionsOutcome.Page -> CommercialCatalogFilterOptionsResult.Choices(
                result.items.map { CommercialCatalogFilterChoice(it.id, it.name) },
                result.page,
                result.size,
                result.total
            )

            CatalogFilterOptionsOutcome.PermissionDenied,
            CatalogFilterOptionsOutcome.ContextInvalidated,
            CatalogFilterOptionsOutcome.SessionExpired ->
                CommercialCatalogFilterOptionsResult.PermissionDenied

            else -> CommercialCatalogFilterOptionsResult.Unavailable
        }
    }
    override suspend fun search(
        authority: CommercialAuthority,
        customerId: String,
        query: String,
        page: String?
    ): CommercialCatalogResult = search(authority, customerId, query, "", "", page)

    override suspend fun search(
        authority: CommercialAuthority,
        customerId: String,
        query: String,
        brandName: String,
        categoryName: String,
        page: String?
    ): CommercialCatalogResult = execute(authority, customerId) {
        when (val result = catalog.search(query, page, brandName, categoryName)) {
            is CatalogSearchOutcome.Page -> CommercialCatalogResult.Choices(
                result.value.candidates.map {
                    CommercialProductChoice(
                        id = it.catalogItemId,
                        name = it.itemName,
                        skuCode = it.skuCode,
                        imageFileName = it.imageFileName,
                        brandName = it.brandName,
                        categoryName = it.categoryName,
                        familyName = it.productFamilyName,
                        variantName = it.productVariantName,
                        presentation = it.presentation,
                        familyId = it.productFamilyId
                    )
                },
                result.value.nextPageKey
            )

            CatalogSearchOutcome.PermissionDenied, CatalogSearchOutcome.ContextInvalidated,
            CatalogSearchOutcome.SessionExpired -> CommercialCatalogResult.PermissionDenied

            else -> CommercialCatalogResult.Unavailable
        }
    }
    override suspend fun detail(
        authority: CommercialAuthority,
        customerId: String,
        id: String
    ): CommercialCatalogResult = execute(authority, customerId) {
        when (val result = commercial.detail(customerId, id)) {
            is CommercialCatalogNetworkResult.Found -> {
                val item = result.value
                val money = item.currentOfferPrice ?: item.effectivePrice ?: item.unitPrice
                val amount = money?.amount?.content?.toBigDecimalOrNull()?.takeIf {
                    it.signum() >=
                        0
                }
                val pricingTime = item.pricingAsOf?.validInstant()
                val availabilityTime = item.availabilityAsOf?.validInstant()
                val currency = money?.currency?.takeIf { Regex("[A-Z]{3}").matches(it) }
                val available = item.sellableAvailability?.content?.toBigDecimalOrNull()?.takeIf {
                    it.signum() >=
                        0
                }
                CommercialCatalogResult.Product(
                    CommercialProductFacts(
                        item.catalogItemId, item.productId, item.itemName,
                        item.sellableSkuId, item.skuCode, item.unitOfMeasure,
                        if (pricingTime != null &&
                            currency != null
                        ) {
                            amount?.toPlainString()
                        } else {
                            null
                        },
                        currency,
                        pricingTime, item.availabilityStatus,
                        if (availabilityTime !=
                            null
                        ) {
                            available?.toPlainString()
                        } else {
                            null
                        },
                        availabilityTime,
                        item.image?.fileName,
                        item.brandName,
                        item.categoryName,
                        item.familyName,
                        item.variantName,
                        item.familyId
                    )
                )
            }

            CommercialCatalogNetworkResult.PermissionDenied ->
                CommercialCatalogResult.PermissionDenied

            CommercialCatalogNetworkResult.Unavailable -> CommercialCatalogResult.Unavailable
        }
    }
    private suspend fun execute(
        authority: CommercialAuthority,
        customerId: String,
        action: suspend () -> CommercialCatalogResult
    ): CommercialCatalogResult {
        if (!current(authority)) return CommercialCatalogResult.PermissionDenied
        val lease = sessions.currentAccess() ?: return CommercialCatalogResult.PermissionDenied
        val customer = customers.detail(customerId).factsOrNull()
            ?: return CommercialCatalogResult.PermissionDenied
        if (customer.status != "ACTIVE") return CommercialCatalogResult.PermissionDenied
        val result = action()
        val refreshed = customers.detail(customerId).factsOrNull()
        return if (!sessions.isEpochCurrent(lease.epoch) || !current(authority) ||
            refreshed == null || refreshed.status != "ACTIVE"
        ) {
            CommercialCatalogResult.PermissionDenied
        } else if (refreshed.version != customer.version) {
            CommercialCatalogResult.Unavailable
        } else {
            result
        }
    }
    private fun CommercialCustomerRead.factsOrNull(): CommercialCustomerFacts? =
        (this as? CommercialCustomerRead.Detail)?.value

    private fun current(authority: CommercialAuthority): Boolean {
        val verified = sessions.verifiedSession.value ?: return false
        return sessions.sessionState.value == SessionState.Active && authority.canReadCustomers() &&
            authority.permissions.any { it == "catalog.read" || it == "catalog:read" } &&
            verified.hasAuthorizedContext &&
            verified.userId == authority.userId && verified.tenantId == authority.tenantId &&
            verified.workspaceId == authority.workspaceId &&
            verified.membershipId == authority.membershipId &&
            verified.permissions == authority.permissions
    }
    private fun String.validInstant(): String? = takeIf {
        runCatching { Instant.parse(it) }.isSuccess
    }
}
