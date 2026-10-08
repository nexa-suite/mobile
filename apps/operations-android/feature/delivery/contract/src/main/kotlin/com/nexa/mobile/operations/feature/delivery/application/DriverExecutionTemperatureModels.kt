package com.nexa.mobile.operations.feature.delivery.application

import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.model.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureIntent
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureLoadResult
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureMetadataWrite
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureMode
import com.nexa.mobile.operations.feature.delivery.model.DriverExecutionTemperatureMutationResult

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
