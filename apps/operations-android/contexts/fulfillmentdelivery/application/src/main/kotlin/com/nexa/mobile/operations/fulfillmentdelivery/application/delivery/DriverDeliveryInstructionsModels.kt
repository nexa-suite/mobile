package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionAcknowledgementCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionAcknowledgementResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionIntentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionsLoadResult

interface DriverDeliveryInstructionMetadataStore {
    suspend fun loadIntent(scope: DriverAttemptScopeIdentity): DriverDeliveryInstructionMetadataRead
    suspend fun saveIntent(
        intent: DriverDeliveryInstructionIntentMetadata
    ): DriverDeliveryInstructionMetadataWrite
    suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverDeliveryInstructionMetadataWrite
}

interface DriverDeliveryInstructionsGateway {
    suspend fun currentInstructions(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): DriverDeliveryInstructionsLoadResult

    suspend fun acknowledgeCriticalInstructions(
        command: DriverDeliveryInstructionAcknowledgementCommand,
        authority: DriverDeliveryAuthority
    ): DriverDeliveryInstructionAcknowledgementResult
}
