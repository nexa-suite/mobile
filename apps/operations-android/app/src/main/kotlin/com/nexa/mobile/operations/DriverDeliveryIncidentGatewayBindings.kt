package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentMetadataStore as IncidentMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.OperationsDriverIncidentGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.JsonDeliveryRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery.DriverDeliveryIncidentViewModel as IncidentViewModel
import javax.inject.Inject
import javax.inject.Singleton

/** Factory seam for Root navigation; Root owns route construction and lifecycle. */
@Singleton
internal class DriverDeliveryIncidentGatewayBindings @Inject constructor(
    private val gateway: OperationsDriverIncidentGateway,
    private val metadataStore: IncidentMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(IncidentViewModel::class.java))
            return IncidentViewModel(
                gateway,
                metadataStore,
                requestBodyCodec = JsonDeliveryRequestBodyCodec()
            ) as T
        }
    }
}
