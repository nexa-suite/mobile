package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.data.OperationsDispatchHandoffIdentityGateway
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoffIdentityViewModel as HandoffIdentityViewModel
import com.nexa.mobile.operations.feature.dispatch.application.DispatchHandoffIdentityMetadataStore as HandoffIdentityMetadataStore
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
            return HandoffIdentityViewModel(gateway, metadataStore) as T
        }
    }
}
