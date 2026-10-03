package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.commercial.CommercialAuthority
import com.nexa.mobile.operations.commercial.CustomerGateway
import com.nexa.mobile.operations.commercial.CustomerRelationship
import com.nexa.mobile.operations.commercial.CustomerResult
import com.nexa.mobile.operations.commercial.CustomerSearchViewModel
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.network.CustomerNetworkResult
import com.nexa.mobile.operations.core.network.CustomerWire
import com.nexa.mobile.operations.core.network.NexaCustomerGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import javax.inject.Inject

internal class OperationsCustomerGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    protectedCalls: ProtectedCallExecutor
) : CustomerGateway {
    private val network = NexaCustomerGateway(protectedCalls)
    override suspend fun search(
        authority: CommercialAuthority,
        query: String,
        page: Int
    ): CustomerResult = execute(authority) { network.search(query, page) }
    override suspend fun detail(authority: CommercialAuthority, id: String): CustomerResult =
        execute(authority) { network.detail(id) }

    private suspend fun execute(
        authority: CommercialAuthority,
        action: suspend () -> CustomerNetworkResult
    ): CustomerResult {
        if (!current(authority)) return CustomerResult.PermissionDenied
        val lease = sessions.currentAccess() ?: return CustomerResult.PermissionDenied
        val result = action()
        if (!sessions.isEpochCurrent(lease.epoch) ||
            !current(authority)
        ) {
            return CustomerResult.PermissionDenied
        }
        return when (result) {
            is CustomerNetworkResult.Page -> CustomerResult.Page(
                result.value.items.map {
                    it.toFeature()
                },
                result.value.page,
                result.value.total
            )

            is CustomerNetworkResult.Detail -> CustomerResult.Detail(result.value.toFeature())

            CustomerNetworkResult.PermissionDenied, CustomerNetworkResult.SessionInvalidated ->
                CustomerResult.PermissionDenied

            CustomerNetworkResult.Unavailable -> CustomerResult.Unavailable
        }
    }
    private fun current(authority: CommercialAuthority): Boolean {
        val verified = sessions.verifiedSession.value ?: return false
        return sessions.sessionState.value == SessionState.Active && authority.canReadCustomers &&
            verified.hasAuthorizedContext && verified.userId == authority.userId &&
            verified.tenantId == authority.tenantId &&
            verified.workspaceId == authority.workspaceId &&
            verified.membershipId == authority.membershipId &&
            verified.permissions == authority.permissions
    }
    private fun CustomerWire.toFeature() = CustomerRelationship(
        id, code, businessName, commercialName, status == "ACTIVE", buyerMembershipId != null,
        version, contactPerson, contactEmail, phone
    )
}

internal class CustomerSearchViewModelFactory @Inject constructor(
    private val gateway: OperationsCustomerGateway
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CustomerSearchViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return CustomerSearchViewModel(gateway) as T
    }
}
