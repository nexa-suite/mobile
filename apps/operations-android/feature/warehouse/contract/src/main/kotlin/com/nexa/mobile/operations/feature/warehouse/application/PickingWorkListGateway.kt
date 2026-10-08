package com.nexa.mobile.operations.feature.warehouse.application

import com.nexa.mobile.operations.feature.warehouse.model.PickingAuthority
import com.nexa.mobile.operations.feature.warehouse.model.PickingWorkListResult

/** Client port for server-authorized prepared fulfillment work. */
interface PickingWorkListGateway {
    suspend fun list(authority: PickingAuthority, page: Int, size: Int): PickingWorkListResult
}
