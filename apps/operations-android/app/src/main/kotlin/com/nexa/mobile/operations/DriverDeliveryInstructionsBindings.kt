package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionMetadataStore as InstructionMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionsGateway as InstructionsGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.JsonDeliveryRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.delivery.DriverDeliveryInstructionsViewModel as InstructionsViewModel
import javax.inject.Inject

internal class DriverDeliveryInstructionsBindings @Inject constructor(
    private val gateway: InstructionsGateway,
    private val metadataStore: InstructionMetadataStore
) {
    fun viewModelFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(InstructionsViewModel::class.java))
            return InstructionsViewModel(
                gateway,
                metadataStore,
                requestBodyCodec = JsonDeliveryRequestBodyCodec()
            ) as T
        }
    }
}
