package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsViewModel as OutgoingGoodsViewModel
import com.nexa.mobile.operations.feature.dispatch.application.DispatchOutgoingGoodsGateway as OutgoingGoodsGateway
import com.nexa.mobile.operations.feature.dispatch.application.DispatchOutgoingGoodsMetadataStore as OutgoingGoodsMetadataStore
import javax.inject.Inject

internal class DispatchOutgoingGoodsViewModelFactory @Inject constructor(
    private val gateway: OutgoingGoodsGateway,
    private val metadata: OutgoingGoodsMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(OutgoingGoodsViewModel::class.java))
        return OutgoingGoodsViewModel(gateway, metadata) as T
    }
}
