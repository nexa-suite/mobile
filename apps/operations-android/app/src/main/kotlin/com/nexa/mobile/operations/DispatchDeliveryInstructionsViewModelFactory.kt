package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionMetadataStore as InstructionMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionsGateway as InstructionsGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.JsonDispatchRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.DispatchDeliveryInstructionsViewModel as InstructionsViewModel
import javax.inject.Inject

internal class DispatchDeliveryInstructionsViewModelFactory @Inject constructor(
    private val gateway: InstructionsGateway,
    private val metadata: InstructionMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(InstructionsViewModel::class.java))
        return InstructionsViewModel(
            gateway,
            metadata,
            requestBodyCodec = JsonDispatchRequestBodyCodec()
        ) as T
    }
}
