package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureGateway as TemperatureGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureMetadataStore as TemperatureMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.OperationsDriverIncidentGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.JsonDeliveryRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery.DriverExecutionTemperatureViewModel as TemperatureViewModel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DriverExecutionTemperatureViewModelFactory @Inject constructor(
    private val gateway: TemperatureGateway,
    private val incidentGateway: OperationsDriverIncidentGateway,
    private val metadata: TemperatureMetadataStore
) {
    fun viewModelFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TemperatureViewModel::class.java))
            return TemperatureViewModel(
                gateway,
                incidentGateway,
                metadata,
                requestBodyCodec = JsonDeliveryRequestBodyCodec()
            ) as T
        }
    }
}
