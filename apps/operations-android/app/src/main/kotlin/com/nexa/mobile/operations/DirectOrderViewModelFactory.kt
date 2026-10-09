package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.salescommitment.infrastructure.adapters.AppDirectOrderStore
import com.nexa.mobile.operations.salescommitment.infrastructure.adapters.OperationsDirectOrderGateway
import com.nexa.mobile.operations.salescommitment.presentation.commercial.DirectOrderViewModel
import javax.inject.Inject
internal class DirectOrderViewModelFactory @Inject constructor(
    private val gateway: OperationsDirectOrderGateway,
    private val store: AppDirectOrderStore
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DirectOrderViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return DirectOrderViewModel(gateway, store) as T
    }
}
