package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.data.OperationsStockTransferGateway
import com.nexa.mobile.operations.feature.warehouse.StockTransferViewModel
import com.nexa.mobile.operations.feature.warehouse.application.StockTransferMetadataStore
import javax.inject.Inject

internal class StockTransferGatewayBindings @Inject constructor(
    private val gateway: OperationsStockTransferGateway,
    private val metadataStore: StockTransferMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(StockTransferViewModel::class.java))
            return StockTransferViewModel(gateway, metadataStore) as T
        }
    }
}
