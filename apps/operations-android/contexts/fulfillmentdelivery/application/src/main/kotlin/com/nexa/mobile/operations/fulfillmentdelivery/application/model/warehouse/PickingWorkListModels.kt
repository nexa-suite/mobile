package com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse

import com.nexa.mobile.operations.fulfillmentdelivery.domain.model.warehouse.PickingWorkPage

sealed interface PickingWorkListResult {
    data class Loaded(val value: PickingWorkPage) : PickingWorkListResult
    data object NetworkUnavailable : PickingWorkListResult
    data object ServiceUnavailable : PickingWorkListResult
    data object PermissionDenied : PickingWorkListResult
    data object ContextInvalidated : PickingWorkListResult
    data object SessionInvalidated : PickingWorkListResult
}
