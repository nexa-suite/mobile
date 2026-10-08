package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoverViewModel
import com.nexa.mobile.operations.feature.dispatch.application.DispatchHandoverGateway
import com.nexa.mobile.operations.feature.dispatch.application.DispatchHandoverMetadataStore as HandoverMetadataStore
import javax.inject.Inject

internal class DispatchHandoverViewModelFactory @Inject constructor(
    private val gateway: DispatchHandoverGateway,
    private val metadata: HandoverMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DispatchHandoverViewModel::class.java))
        return DispatchHandoverViewModel(gateway, metadata) as T
    }
}
