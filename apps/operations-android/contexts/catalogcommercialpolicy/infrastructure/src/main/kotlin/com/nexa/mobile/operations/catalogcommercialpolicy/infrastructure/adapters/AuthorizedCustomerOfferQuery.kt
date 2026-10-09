package com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.adapters

import com.nexa.mobile.operations.catalogcommercialpolicy.application.publicapi.CustomerOfferFacts
import com.nexa.mobile.operations.catalogcommercialpolicy.application.publicapi.CustomerOfferQuery
import com.nexa.mobile.operations.catalogcommercialpolicy.application.publicapi.CustomerOfferRead
import com.nexa.mobile.operations.catalogcommercialpolicy.application.publicapi.QuotedUnitPrice
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.CommercialCatalogNetworkResult
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.transport.NexaCommercialCatalogGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import javax.inject.Inject

class AuthorizedCustomerOfferQuery @Inject constructor(calls: ProtectedCallExecutor) :
    CustomerOfferQuery {
    private val offers = NexaCommercialCatalogGateway(calls)
    override suspend fun detail(
        customerId: String,
        productId: String,
        quantity: String
    ): CustomerOfferRead = when (val result = offers.detail(customerId, productId, quantity)) {
        is CommercialCatalogNetworkResult.Found -> CustomerOfferRead.Found(
            CustomerOfferFacts(
                result.value.catalogItemId,
                result.value.itemName,
                result.value.unitOfMeasure,
                result.value.currentOfferPrice?.let {
                    QuotedUnitPrice(it.amount.content, it.currency)
                },
                result.value.pricingAsOf
            )
        )

        else -> CustomerOfferRead.Unavailable
    }
}
