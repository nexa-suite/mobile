package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.StockTransferMetadataStore
import com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters.OperationsStockTransferGateway
import com.nexa.mobile.operations.inventoryavailability.infrastructure.serialization.warehouse.CanonicalWarehouseFrozenPayloadCodec
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.StockTransferViewModel
import javax.inject.Inject

internal class StockTransferGatewayBindings @Inject constructor(
    private val gateway: OperationsStockTransferGateway,
    private val metadataStore: StockTransferMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(StockTransferViewModel::class.java))
            return StockTransferViewModel(
                gateway,
                metadataStore,
                payloadCodec = CanonicalWarehouseFrozenPayloadCodec()
            ) as T
        }
    }
}
