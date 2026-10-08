package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.data.OperationsCommercialCatalogGateway
import com.nexa.mobile.operations.feature.commercial.CommercialCatalogViewModel
import javax.inject.Inject
internal class CommercialCatalogViewModelFactory @Inject constructor(
    private val gateway: OperationsCommercialCatalogGateway
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CommercialCatalogViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return CommercialCatalogViewModel(gateway) as T
    }
}
