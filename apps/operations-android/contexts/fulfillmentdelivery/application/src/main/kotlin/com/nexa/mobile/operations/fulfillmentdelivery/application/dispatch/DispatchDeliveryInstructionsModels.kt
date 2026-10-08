package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchDeliveryInstructionsGatewayResult

interface DispatchDeliveryInstructionMetadataStore {
    suspend fun loadIntent(
        scope: DispatchDeliveryInstructionScopeIdentity,
        deliveryId: String
    ): DispatchDeliveryInstructionMetadataRead

    suspend fun saveIntent(
        intent: DispatchDeliveryInstructionIntent
    ): DispatchDeliveryInstructionMetadataWrite

    suspend fun clearIntent(
        scope: DispatchDeliveryInstructionScopeIdentity,
        deliveryId: String,
        idempotencyKey: String
    ): DispatchDeliveryInstructionMetadataWrite
}

interface DispatchDeliveryInstructionsGateway {
    suspend fun currentInstructions(
        deliveryId: String,
        context: DispatchAuthorityContext
    ): DispatchDeliveryInstructionsGatewayResult

    suspend fun publish(
        intent: DispatchDeliveryInstructionIntent,
        context: DispatchAuthorityContext
    ): DispatchDeliveryInstructionsGatewayResult
}
