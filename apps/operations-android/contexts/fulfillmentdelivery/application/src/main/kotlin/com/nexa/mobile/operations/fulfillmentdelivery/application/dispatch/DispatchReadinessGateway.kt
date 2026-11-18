package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchReadinessGatewayResult

/** Dispatch reads and commands bound to the verified current identity and access token lease. */
interface DispatchReadinessGateway {
    suspend fun list(context: DispatchAuthorityContext): DispatchReadinessGatewayResult

    suspend fun detail(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): DispatchReadinessGatewayResult

    suspend fun deliveryPod(
        deliveryId: String,
        context: DispatchAuthorityContext
    ): DispatchDeliveryPodGatewayResult

    suspend fun sealPod(
        command: SealDispatchPodCommand,
        context: DispatchAuthorityContext
    ): DispatchDeliveryPodGatewayResult

    suspend fun startTransit(
        intent: DispatchTransitStartIntent,
        context: DispatchAuthorityContext,
        replayUnknown: Boolean
    ): DispatchTransitStartGatewayResult
}
