package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverMetadataStore as HandoverMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.JsonDispatchRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.DispatchHandoverViewModel
import javax.inject.Inject

internal class DispatchHandoverViewModelFactory @Inject constructor(
    private val gateway: DispatchHandoverGateway,
    private val metadata: HandoverMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DispatchHandoverViewModel::class.java))
        return DispatchHandoverViewModel(
            gateway,
            metadata,
            requestBodyCodec = JsonDispatchRequestBodyCodec()
        ) as T
    }
}
