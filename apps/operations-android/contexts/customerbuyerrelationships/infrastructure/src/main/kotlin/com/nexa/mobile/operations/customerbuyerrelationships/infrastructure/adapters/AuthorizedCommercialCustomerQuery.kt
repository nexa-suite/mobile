package com.nexa.mobile.operations.customerbuyerrelationships.infrastructure.adapters

import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerFacts
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerQuery
import com.nexa.mobile.operations.customerbuyerrelationships.application.publicapi.CommercialCustomerRead
import com.nexa.mobile.operations.customerbuyerrelationships.infrastructure.transport.CustomerNetworkResult
import com.nexa.mobile.operations.customerbuyerrelationships.infrastructure.transport.NexaCustomerGateway
import javax.inject.Inject

class AuthorizedCommercialCustomerQuery @Inject constructor(calls: ProtectedCallExecutor) :
    CommercialCustomerQuery {
    private val customers = NexaCustomerGateway(calls)
    override suspend fun detail(customerId: String): CommercialCustomerRead =
        when (val result = customers.detail(customerId)) {
            is CustomerNetworkResult.Detail -> CommercialCustomerRead.Detail(
                CommercialCustomerFacts(
                    result.value.businessName,
                    result.value.status,
                    result.value.version
                )
            )

            else -> CommercialCustomerRead.Unavailable
        }
}
