package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverHandoffTokenMetadataStore as HandoffTokenMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.OperationsDriverHandoffTokenGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.JsonDeliveryRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery.DriverHandoffTokenViewModel as HandoffTokenViewModel
import javax.inject.Inject
import javax.inject.Singleton

/** Route/factory seam for Root navigation. Root owns MainActivity and DeliveryScreen. */
@Singleton
internal class DriverHandoffTokenGatewayBindings @Inject constructor(
    private val gateway: OperationsDriverHandoffTokenGateway,
    private val metadataStore: HandoffTokenMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HandoffTokenViewModel::class.java))
            return HandoffTokenViewModel(
                gateway,
                metadataStore,
                requestBodyCodec = JsonDeliveryRequestBodyCodec()
            ) as T
        }
    }
}
