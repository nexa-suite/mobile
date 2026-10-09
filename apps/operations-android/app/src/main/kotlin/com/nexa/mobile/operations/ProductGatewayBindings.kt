package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.catalogcommercialpolicy.application.warehouse.CatalogOperationsContextProjector
import com.nexa.mobile.operations.catalogcommercialpolicy.application.warehouse.WarehouseGateway
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WarehouseViewModel
import com.nexa.mobile.operations.tenantaccessgovernance.application.access.AccessGateway
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessViewModel

internal class AccessViewModelFactory(private val gateway: AccessGateway) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AccessViewModel::class.java))
        return AccessViewModel(gateway) as T
    }
}

internal class WarehouseViewModelFactory(
    private val gateway: WarehouseGateway,
    private val contextProjector: CatalogOperationsContextProjector
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(WarehouseViewModel::class.java))
        return WarehouseViewModel(gateway, contextProjector) as T
    }
}
