package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.salescommitment.infrastructure.adapters.OperationsCustomerProgressGateway
import com.nexa.mobile.operations.salescommitment.presentation.commercial.CustomerProgressViewModel
import javax.inject.Inject
internal class CustomerProgressViewModelFactory @Inject constructor(
    private val gateway: OperationsCustomerProgressGateway
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CustomerProgressViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return CustomerProgressViewModel(gateway) as T
    }
}
