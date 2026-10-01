package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.commercial.CommercialAuthority
import com.nexa.mobile.operations.commercial.CommercialCatalogGateway
import com.nexa.mobile.operations.commercial.CommercialCatalogResult
import com.nexa.mobile.operations.commercial.CommercialCatalogViewModel
import com.nexa.mobile.operations.commercial.CommercialProductChoice
import com.nexa.mobile.operations.commercial.CommercialProductFacts
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.CatalogSearchOutcome
import com.nexa.mobile.operations.core.network.CommercialCatalogNetworkResult
import com.nexa.mobile.operations.core.network.CustomerNetworkResult
import com.nexa.mobile.operations.core.network.NexaCatalogGateway
import com.nexa.mobile.operations.core.network.NexaCommercialCatalogGateway
import com.nexa.mobile.operations.core.network.NexaCustomerGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import java.time.Instant
import javax.inject.Inject

internal class OperationsCommercialCatalogGateway @Inject constructor(private val sessions: SessionCoordinator,
    calls: ProtectedCallExecutor) : CommercialCatalogGateway {
    private val customers = NexaCustomerGateway(calls)
    private val catalog = NexaCatalogGateway(calls)
    private val commercial = NexaCommercialCatalogGateway(calls)
    override suspend fun search(authority: CommercialAuthority, customerId: String, query: String, page: String?): CommercialCatalogResult =
        execute(authority, customerId) {
            when (val result = catalog.search(query, page)) {
                is CatalogSearchOutcome.Page -> CommercialCatalogResult.Choices(result.value.candidates.map {
                    CommercialProductChoice(it.catalogItemId, it.itemName, it.skuCode)
                }, result.value.nextPageKey)
                CatalogSearchOutcome.PermissionDenied, CatalogSearchOutcome.ContextInvalidated,
                CatalogSearchOutcome.SessionExpired -> CommercialCatalogResult.PermissionDenied
                else -> CommercialCatalogResult.Unavailable
            }
        }
    override suspend fun detail(authority: CommercialAuthority, customerId: String, id: String): CommercialCatalogResult =
        execute(authority, customerId) {
            when (val result = commercial.detail(customerId, id)) {
                is CommercialCatalogNetworkResult.Found -> {
                    val item = result.value
                    val money = item.currentOfferPrice ?: item.effectivePrice ?: item.unitPrice
                    val amount = money?.amount?.content?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
                    val pricingTime = item.pricingAsOf?.validInstant()
                    val availabilityTime = item.availabilityAsOf?.validInstant()
                    val currency = money?.currency?.takeIf { Regex("[A-Z]{3}").matches(it) }
                    val available = item.sellableAvailability?.content?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
                    CommercialCatalogResult.Product(CommercialProductFacts(item.catalogItemId, item.productId, item.itemName,
                        item.sellableSkuId, item.skuCode, item.unitOfMeasure,
                        if (pricingTime != null && currency != null) amount?.toPlainString() else null, currency,
                        pricingTime, item.availabilityStatus,
                        if (availabilityTime != null) available?.toPlainString() else null, availabilityTime))
                }
                CommercialCatalogNetworkResult.PermissionDenied -> CommercialCatalogResult.PermissionDenied
                CommercialCatalogNetworkResult.Unavailable -> CommercialCatalogResult.Unavailable
            }
        }
    private suspend fun execute(authority: CommercialAuthority, customerId: String,
        action: suspend () -> CommercialCatalogResult): CommercialCatalogResult {
        if (!current(authority)) return CommercialCatalogResult.PermissionDenied
        val lease = sessions.currentAccess() ?: return CommercialCatalogResult.PermissionDenied
        val customer = (customers.detail(customerId) as? CustomerNetworkResult.Detail)?.value
        if (customer?.status != "ACTIVE") return CommercialCatalogResult.PermissionDenied
        val result = action()
        val refreshed = (customers.detail(customerId) as? CustomerNetworkResult.Detail)?.value
        return if (!sessions.isEpochCurrent(lease.epoch) || !current(authority) || refreshed?.status != "ACTIVE")
            CommercialCatalogResult.PermissionDenied else if (refreshed.version != customer.version)
            CommercialCatalogResult.Unavailable else result
    }
    private fun current(authority: CommercialAuthority): Boolean {
        val verified = sessions.verifiedSession.value ?: return false
        return sessions.sessionState.value == SessionState.Active && authority.canReadCustomers &&
            authority.permissions.any { it == "catalog.read" || it == "catalog:read" } && verified.hasAuthorizedContext &&
            verified.userId == authority.userId && verified.tenantId == authority.tenantId &&
            verified.workspaceId == authority.workspaceId && verified.membershipId == authority.membershipId && verified.permissions == authority.permissions
    }
    private fun String.validInstant(): String? = takeIf { runCatching { Instant.parse(it) }.isSuccess }
}
internal class CommercialCatalogViewModelFactory @Inject constructor(private val gateway: OperationsCommercialCatalogGateway) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CommercialCatalogViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return CommercialCatalogViewModel(gateway) as T
    }
}
