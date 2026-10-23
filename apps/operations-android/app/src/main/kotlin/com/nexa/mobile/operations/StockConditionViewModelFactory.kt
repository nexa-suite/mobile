package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.fulfillmentdelivery.application.changefeed.OperationsChangeFeedGateway
import com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters.OperationsStockConditionGateway
import com.nexa.mobile.operations.inventoryavailability.presentation.warehouse.StockConditionViewModel
import javax.inject.Inject

internal class StockConditionViewModelFactory @Inject constructor(
    private val gateway: OperationsStockConditionGateway,
    private val changeFeedGateway: OperationsChangeFeedGateway
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(StockConditionViewModel::class.java))
        return StockConditionViewModel(
            gateway,
            stockConditionChangeSignals = changeFeedGateway.stockConditionChangeSignals()
        ) as T
    }
}
