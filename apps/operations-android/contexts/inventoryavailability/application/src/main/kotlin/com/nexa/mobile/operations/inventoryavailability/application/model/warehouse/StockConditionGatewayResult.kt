package com.nexa.mobile.operations.inventoryavailability.application.model.warehouse

import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockConditionAvailability
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.StockConditionLot

sealed interface StockConditionGatewayResult {
    data class Lots(val items: List<StockConditionLot>) : StockConditionGatewayResult
    data class Lot(val item: StockConditionLot) : StockConditionGatewayResult
    data class Availability(val item: StockConditionAvailability?) : StockConditionGatewayResult
    data object NetworkUnavailable : StockConditionGatewayResult
    data object ServiceUnavailable : StockConditionGatewayResult
    data object PermissionDenied : StockConditionGatewayResult
    data object ContextInvalidated : StockConditionGatewayResult
    data object SessionInvalidated : StockConditionGatewayResult
}
