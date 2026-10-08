package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.CycleCountGateway
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.CycleCountMetadataStore
import com.nexa.mobile.operations.inventoryavailability.infrastructure.serialization.warehouse.CanonicalWarehouseFrozenPayloadCodec
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.CycleCountViewModel

internal object CycleCountViewModelBindings {
    fun viewModelFactory(
        gateway: CycleCountGateway,
        metadataStore: CycleCountMetadataStore
    ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CycleCountViewModel::class.java))
            return CycleCountViewModel(
                gateway,
                metadataStore,
                payloadCodec = CanonicalWarehouseFrozenPayloadCodec()
            ) as T
        }
    }
}
