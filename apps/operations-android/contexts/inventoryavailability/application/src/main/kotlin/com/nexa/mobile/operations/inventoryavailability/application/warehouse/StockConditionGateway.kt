package com.nexa.mobile.operations.inventoryavailability.application.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockConditionGatewayResult
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.operations.ActiveOperationsContext

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
