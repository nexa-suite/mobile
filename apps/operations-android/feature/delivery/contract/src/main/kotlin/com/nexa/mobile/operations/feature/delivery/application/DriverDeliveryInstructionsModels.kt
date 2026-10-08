package com.nexa.mobile.operations.feature.delivery.application

import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryInstructionAcknowledgementCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryInstructionAcknowledgementResult
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryInstructionIntentMetadata
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryInstructionMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryInstructionMetadataWrite
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryInstructionsLoadResult

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
