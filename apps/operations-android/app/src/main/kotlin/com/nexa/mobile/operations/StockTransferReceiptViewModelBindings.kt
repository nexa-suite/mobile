package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.StockTransferReceiptGateway
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.StockTransferReceiptMetadataStore
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.StockTransferReceiptObservationMetadataStore
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.StockTransferReceiptViewModel

internal object StockTransferReceiptViewModelBindings {
    fun viewModelFactory(
        gateway: StockTransferReceiptGateway,
        metadataStore: StockTransferReceiptMetadataStore,
        observationMetadataStore: StockTransferReceiptObservationMetadataStore
    ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(StockTransferReceiptViewModel::class.java))
            return StockTransferReceiptViewModel(
                gateway,
                metadataStore,
                observationMetadataStore
            ) as T
        }
    }
}
