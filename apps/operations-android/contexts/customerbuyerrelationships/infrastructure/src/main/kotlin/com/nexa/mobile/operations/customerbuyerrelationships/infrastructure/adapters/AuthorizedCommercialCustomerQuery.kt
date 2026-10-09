package com.nexa.mobile.operations.customerbuyerrelationships.infrastructure.adapters

import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerFacts
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerQuery
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerRead
import com.nexa.mobile.operations.customerbuyerrelationships.infrastructure.transport.CustomerNetworkResult
import com.nexa.mobile.operations.customerbuyerrelationships.infrastructure.transport.NexaCustomerGateway
import javax.inject.Inject

class AuthorizedCommercialCustomerQuery @Inject constructor(
    private val sessions: SessionCoordinator,
    calls: ProtectedCallExecutor
) : CommercialCustomerQuery {
    private val customers = NexaCustomerGateway(calls)
    override suspend fun detail(customerId: String): CommercialCustomerRead {
        val lease = sessions.currentAccess() ?: return CommercialCustomerRead.Unavailable
        return when (val result = customers.detail(customerId)) {
            is CustomerNetworkResult.Detail -> {
                if (!sessions.isEpochCurrent(lease.epoch)) {
                    CommercialCustomerRead.Unavailable
                } else {
                    CommercialCustomerRead.Detail(
                        CommercialCustomerFacts(
                            result.value.businessName,
                            result.value.status,
                            result.value.version
                        )
                    )
                }
            }

            CustomerNetworkResult.ContextInvalidated -> {
                if (sessions.invalidateContextIfCurrent(lease)) {
                    CommercialCustomerRead.ContextInvalidated
                } else {
                    CommercialCustomerRead.Unavailable
                }
            }

            else -> CommercialCustomerRead.Unavailable
        }
    }
}
