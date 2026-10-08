package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchReadinessGatewayResult

/** Read-only port. Implementations bind every request to verified current identity and lease. */
interface DispatchReadinessGateway {
    suspend fun list(context: DispatchAuthorityContext): DispatchReadinessGatewayResult

    suspend fun detail(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): DispatchReadinessGatewayResult
}
