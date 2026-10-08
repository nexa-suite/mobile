package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsGateway as OutgoingGoodsGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsMetadataStore as OutgoingGoodsMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.JsonDispatchRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.DispatchOutgoingGoodsViewModel as OutgoingGoodsViewModel
import javax.inject.Inject

internal class DispatchOutgoingGoodsViewModelFactory @Inject constructor(
    private val gateway: OutgoingGoodsGateway,
    private val metadata: OutgoingGoodsMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(OutgoingGoodsViewModel::class.java))
        return OutgoingGoodsViewModel(
            gateway,
            metadata,
            requestBodyCodec = JsonDispatchRequestBodyCodec()
        ) as T
    }
}
