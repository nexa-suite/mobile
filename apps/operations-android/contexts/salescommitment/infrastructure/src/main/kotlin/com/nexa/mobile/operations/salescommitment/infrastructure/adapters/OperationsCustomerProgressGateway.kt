package com.nexa.mobile.operations.salescommitment.infrastructure.adapters

import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.creditreceivables.application.publicapi.CustomerCreditExposureQuery
import com.nexa.mobile.operations.creditreceivables.application.publicapi.CustomerCreditExposureRead
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerQuery
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerRead
import com.nexa.mobile.operations.salescommitment.application.commercial.CustomerProgressGateway
import com.nexa.mobile.operations.salescommitment.application.commercial.CustomerProgressResult
import com.nexa.mobile.operations.salescommitment.application.commercial.ProgressStatus
import com.nexa.mobile.operations.salescommitment.domain.model.commercial.CustomerCommitment
import com.nexa.mobile.operations.salescommitment.infrastructure.transport.CustomerProgressNetworkResult
import com.nexa.mobile.operations.salescommitment.infrastructure.transport.NexaCustomerProgressGateway
import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.CommercialAuthority
import javax.inject.Inject

class OperationsCustomerProgressGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val customers: CommercialCustomerQuery,
    private val credit: CustomerCreditExposureQuery,
    calls: ProtectedCallExecutor
) : CustomerProgressGateway {
    private val progress = NexaCustomerProgressGateway(calls)
    override suspend fun read(
        authority: CommercialAuthority,
        id: String,
        currency: String,
        page: Int
    ): CustomerProgressResult {
        fun denied() = CustomerProgressResult(
            null,
            emptyList(),
            null,
            ProgressStatus.PermissionDenied,
            ProgressStatus.PermissionDenied,
            page,
            null
        )
        if (!current(authority) ||
            authority.permissions.none { it == "client.read" || it == "sales:read" }
        ) {
            return denied()
        }
        val lease = sessions.currentAccess() ?: return denied()
        val customer =
            (customers.detail(id) as? CommercialCustomerRead.Detail)?.value ?: return denied()
        if (customer.status != "ACTIVE") return denied()
        val orders = if (authority.permissions.any {
                it == "sales.order.read" || it == "sales:read"
            }
        ) {
            progress.orders(id, page)
        } else {
            CustomerProgressNetworkResult.PermissionDenied
        }
        val credit = credit.credit(id, currency)
        val refreshedCustomer = (customers.detail(id) as? CommercialCustomerRead.Detail)?.value
        if (!sessions.isEpochCurrent(lease.epoch) || !current(authority) ||
            refreshedCustomer?.status != "ACTIVE"
        ) {
            return denied()
        }
        if (refreshedCustomer.version !=
            customer.version
        ) {
            return CustomerProgressResult(
                null,
                emptyList(),
                null,
                ProgressStatus.Unavailable,
                ProgressStatus.Unavailable,
                page,
                null
            )
        }
        if (orders is CustomerProgressNetworkResult.Orders && orders.page.items.any {
                it.tenantId != authority.tenantId || it.workspaceId != authority.workspaceId
            }
        ) {
            return denied()
        }
        val commitments = (orders as? CustomerProgressNetworkResult.Orders)?.page
        val creditValue = (credit as? CustomerCreditExposureRead.Available)?.value
        return CustomerProgressResult(
            customer.businessName,
            commitments?.items?.map {
                CustomerCommitment(
                    it.id,
                    it.number,
                    it.status,
                    it.total.content,
                    it.currency,
                    it.version,
                    it.updatedAt
                )
            } ?: emptyList(),
            creditValue,
            orders.status(),
            credit.status(),
            page,
            commitments?.total
        )
    }
    private fun current(authority: CommercialAuthority): Boolean {
        val verified = sessions.verifiedSession.value ?: return false
        return sessions.sessionState.value == SessionState.Active && authority.authorityEpoch > 0 &&
            verified.hasAuthorizedContext && verified.userId == authority.userId &&
            verified.tenantId == authority.tenantId &&
            verified.workspaceId == authority.workspaceId &&
            verified.membershipId == authority.membershipId &&
            verified.permissions == authority.permissions
    }
    private fun CustomerProgressNetworkResult.status() = when (this) {
        is CustomerProgressNetworkResult.Orders ->
            ProgressStatus.Current

        CustomerProgressNetworkResult.PermissionDenied -> ProgressStatus.PermissionDenied

        CustomerProgressNetworkResult.Unavailable -> ProgressStatus.Unavailable
    }
    private fun CustomerCreditExposureRead.status() = when (this) {
        is CustomerCreditExposureRead.Available -> ProgressStatus.Current
        CustomerCreditExposureRead.PermissionDenied -> ProgressStatus.PermissionDenied
        CustomerCreditExposureRead.Unavailable -> ProgressStatus.Unavailable
    }
}
