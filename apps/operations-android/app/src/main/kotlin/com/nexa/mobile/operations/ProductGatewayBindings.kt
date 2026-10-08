package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.feature.access.AccessViewModel
import com.nexa.mobile.operations.feature.access.application.AccessGateway
import com.nexa.mobile.operations.feature.warehouse.WarehouseViewModel
import com.nexa.mobile.operations.feature.warehouse.application.WarehouseGateway

internal class AccessViewModelFactory(private val gateway: AccessGateway) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AccessViewModel::class.java))
        return AccessViewModel(gateway) as T
    }
}

internal class WarehouseViewModelFactory(private val gateway: WarehouseGateway) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(WarehouseViewModel::class.java))
        return WarehouseViewModel(gateway) as T
    }
}
