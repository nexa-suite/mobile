package com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse

import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingWorkListResult

/** Client port for server-authorized prepared fulfillment work. */
interface PickingWorkListGateway {
    suspend fun list(authority: PickingAuthority, page: Int, size: Int): PickingWorkListResult
}
