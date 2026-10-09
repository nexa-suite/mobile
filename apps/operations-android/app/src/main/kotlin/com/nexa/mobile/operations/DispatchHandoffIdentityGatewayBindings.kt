package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoffIdentityMetadataStore as HandoffIdentityMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters.OperationsDispatchHandoffIdentityGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.JsonDispatchRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch.DispatchHandoffIdentityViewModel as HandoffIdentityViewModel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DispatchHandoffIdentityGatewayBindings @Inject constructor(
    private val gateway: OperationsDispatchHandoffIdentityGateway,
    private val metadataStore: HandoffIdentityMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HandoffIdentityViewModel::class.java))
            return HandoffIdentityViewModel(
                gateway,
                metadataStore,
                requestBodyCodec = JsonDispatchRequestBodyCodec()
            ) as T
        }
    }
}
