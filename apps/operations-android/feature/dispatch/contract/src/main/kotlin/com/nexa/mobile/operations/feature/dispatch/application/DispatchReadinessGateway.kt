package com.nexa.mobile.operations.feature.dispatch.application

import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.model.DispatchReadinessGatewayResult

/** Read-only port. Implementations bind every request to verified current identity and lease. */
interface DispatchReadinessGateway {
    suspend fun list(context: DispatchAuthorityContext): DispatchReadinessGatewayResult

    suspend fun detail(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): DispatchReadinessGatewayResult
}
