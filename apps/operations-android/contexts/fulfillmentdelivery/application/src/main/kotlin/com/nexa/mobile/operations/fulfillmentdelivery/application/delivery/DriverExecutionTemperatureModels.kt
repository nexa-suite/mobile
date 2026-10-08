package com.nexa.mobile.operations.fulfillmentdelivery.application.delivery

import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureLoadResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverExecutionTemperatureMode
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverExecutionTemperatureMutationResult

interface DriverExecutionTemperatureMetadataStore {
    suspend fun loadIntent(
        scope: DriverAttemptScopeIdentity
    ): DriverExecutionTemperatureMetadataRead
    suspend fun saveIntent(
        intent: DriverExecutionTemperatureIntent
    ): DriverExecutionTemperatureMetadataWrite
    suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverExecutionTemperatureMetadataWrite
}

interface DriverExecutionTemperatureGateway {
    suspend fun current(
        deliveryId: String,
        mode: DriverExecutionTemperatureMode,
        authority: DriverDeliveryAuthority
    ): DriverExecutionTemperatureLoadResult

    suspend fun record(
        command: DriverExecutionTemperatureCommand.Reading,
        authority: DriverDeliveryAuthority
    ): DriverExecutionTemperatureMutationResult

    suspend fun dispose(
        command: DriverExecutionTemperatureCommand.Disposition,
        authority: DriverDeliveryAuthority
    ): DriverExecutionTemperatureMutationResult
}
