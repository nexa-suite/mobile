package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.catalogcommercialpolicy.application.warehouse.ProductScannerGateway
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductScannerViewModel

internal class ProductScannerViewModelFactory(private val gateway: ProductScannerGateway) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ProductScannerViewModel::class.java))
        return ProductScannerViewModel(gateway) as T
    }
}
