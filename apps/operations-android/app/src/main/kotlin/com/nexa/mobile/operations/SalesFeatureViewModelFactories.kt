package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.customerbuyerrelationships.infrastructure.adapters.OperationsSalesConversationGateway
import com.nexa.mobile.operations.customerbuyerrelationships.presentation.commercial.SalesConversationViewModel
import com.nexa.mobile.operations.salescommitment.infrastructure.adapters.OperationsSalesOrderExportGateway
import com.nexa.mobile.operations.salescommitment.presentation.commercial.SalesOrderExportViewModel
import javax.inject.Inject

internal class SalesConversationViewModelFactory @Inject constructor(
    private val gateway: OperationsSalesConversationGateway
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SalesConversationViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return SalesConversationViewModel(gateway) as T
    }
}

internal class SalesOrderExportViewModelFactory @Inject constructor(
    private val gateway: OperationsSalesOrderExportGateway
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SalesOrderExportViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return SalesOrderExportViewModel(gateway) as T
    }
}
