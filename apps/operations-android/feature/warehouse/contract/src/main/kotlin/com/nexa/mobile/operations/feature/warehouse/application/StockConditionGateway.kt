package com.nexa.mobile.operations.feature.warehouse.application

import com.nexa.mobile.operations.feature.warehouse.model.ActiveOperationsContext
import com.nexa.mobile.operations.feature.warehouse.model.StockConditionGatewayResult

/** Read-only gateway; each request is bound by the app adapter to current verified identity. */
interface StockConditionGateway {
    suspend fun lots(context: ActiveOperationsContext): StockConditionGatewayResult

    suspend fun lot(lotId: String, context: ActiveOperationsContext): StockConditionGatewayResult

    suspend fun availability(
        warehouseId: String,
        catalogItemId: String,
        context: ActiveOperationsContext
    ): StockConditionGatewayResult
}
