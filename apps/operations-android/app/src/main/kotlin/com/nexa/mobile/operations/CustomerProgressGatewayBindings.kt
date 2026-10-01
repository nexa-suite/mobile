package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.commercial.CommercialAuthority
import com.nexa.mobile.operations.commercial.CustomerCommitment
import com.nexa.mobile.operations.commercial.CustomerCredit
import com.nexa.mobile.operations.commercial.CustomerProgressGateway
import com.nexa.mobile.operations.commercial.CustomerProgressResult
import com.nexa.mobile.operations.commercial.CustomerProgressViewModel
import com.nexa.mobile.operations.commercial.ProgressStatus
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.CustomerNetworkResult
import com.nexa.mobile.operations.core.network.CustomerProgressNetworkResult
import com.nexa.mobile.operations.core.network.NexaCustomerGateway
import com.nexa.mobile.operations.core.network.NexaCustomerProgressGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import javax.inject.Inject

internal class OperationsCustomerProgressGateway @Inject constructor(private val sessions: SessionCoordinator,
    calls: ProtectedCallExecutor) : CustomerProgressGateway {
    private val customers = NexaCustomerGateway(calls)
    private val progress = NexaCustomerProgressGateway(calls)
    override suspend fun read(authority: CommercialAuthority, id: String, currency: String, page: Int): CustomerProgressResult {
        fun denied() = CustomerProgressResult(null, emptyList(), null, ProgressStatus.PermissionDenied, ProgressStatus.PermissionDenied, page, null)
        if (!current(authority) || authority.permissions.none { it == "client.read" || it == "sales:read" }) return denied()
        val lease = sessions.currentAccess() ?: return denied()
        val customer = (customers.detail(id) as? CustomerNetworkResult.Detail)?.value ?: return denied()
        if (customer.status != "ACTIVE") return denied()
        val orders = if (authority.permissions.any { it == "sales.order.read" || it == "sales:read" })
            progress.orders(id, page) else CustomerProgressNetworkResult.PermissionDenied
        val credit = progress.credit(id, currency)
        val refreshedCustomer = (customers.detail(id) as? CustomerNetworkResult.Detail)?.value
        if (!sessions.isEpochCurrent(lease.epoch) || !current(authority) || refreshedCustomer?.status != "ACTIVE") return denied()
        if (refreshedCustomer.version != customer.version) return CustomerProgressResult(null, emptyList(), null,
            ProgressStatus.Unavailable, ProgressStatus.Unavailable, page, null)
        if (orders is CustomerProgressNetworkResult.Orders && orders.page.items.any {
                it.tenantId != authority.tenantId || it.workspaceId != authority.workspaceId
            }) return denied()
        val commitments = (orders as? CustomerProgressNetworkResult.Orders)?.page
        val creditValue = (credit as? CustomerProgressNetworkResult.Credit)?.value
        return CustomerProgressResult(customer.businessName,
            commitments?.items?.map { CustomerCommitment(it.id, it.number, it.status, it.total.content,
                it.currency, it.version, it.updatedAt) } ?: emptyList(),
            creditValue?.let { CustomerCredit(it.currency, it.creditLimit.content, it.ledgerExposure.content,
                it.outstandingReceivables.content, it.reservedExposure.content, it.used.content,
                it.availableCredit.content, it.active, it.asOf) },
            orders.status(), credit.status(), page, commitments?.total)
    }
    private fun current(authority: CommercialAuthority): Boolean {
        val verified = sessions.verifiedSession.value ?: return false
        return sessions.sessionState.value == SessionState.Active && authority.authorityEpoch > 0 &&
            verified.hasAuthorizedContext && verified.userId == authority.userId && verified.tenantId == authority.tenantId &&
            verified.workspaceId == authority.workspaceId && verified.membershipId == authority.membershipId && verified.permissions == authority.permissions
    }
    private fun CustomerProgressNetworkResult.status() = when (this) {
        is CustomerProgressNetworkResult.Orders, is CustomerProgressNetworkResult.Credit -> ProgressStatus.Current
        CustomerProgressNetworkResult.PermissionDenied -> ProgressStatus.PermissionDenied
        CustomerProgressNetworkResult.Unavailable -> ProgressStatus.Unavailable
    }
}
internal class CustomerProgressViewModelFactory @Inject constructor(private val gateway: OperationsCustomerProgressGateway) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CustomerProgressViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return CustomerProgressViewModel(gateway) as T
    }
}
