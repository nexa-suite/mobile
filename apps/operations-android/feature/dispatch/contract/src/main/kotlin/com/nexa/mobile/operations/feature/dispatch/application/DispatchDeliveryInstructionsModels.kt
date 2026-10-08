package com.nexa.mobile.operations.feature.dispatch.application

import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.model.DispatchDeliveryInstructionIntent
import com.nexa.mobile.operations.feature.dispatch.model.DispatchDeliveryInstructionMetadataRead
import com.nexa.mobile.operations.feature.dispatch.model.DispatchDeliveryInstructionMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.model.DispatchDeliveryInstructionScopeIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchDeliveryInstructionsGatewayResult

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
