package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.feature.warehouse.CycleCountViewModel
import com.nexa.mobile.operations.feature.warehouse.application.CycleCountGateway
import com.nexa.mobile.operations.feature.warehouse.application.CycleCountMetadataStore

internal object CycleCountViewModelBindings {
    fun viewModelFactory(
        gateway: CycleCountGateway,
        metadataStore: CycleCountMetadataStore
    ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CycleCountViewModel::class.java))
            return CycleCountViewModel(gateway, metadataStore) as T
        }
    }
}
