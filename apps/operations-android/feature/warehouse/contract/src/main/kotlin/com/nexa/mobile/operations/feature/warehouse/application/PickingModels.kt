package com.nexa.mobile.operations.feature.warehouse.application

import com.nexa.mobile.operations.feature.warehouse.model.PickingAuthority
import com.nexa.mobile.operations.feature.warehouse.model.PickingConfirmationCommand
import com.nexa.mobile.operations.feature.warehouse.model.PickingIntentCommand
import com.nexa.mobile.operations.feature.warehouse.model.PickingIntentMetadata
import com.nexa.mobile.operations.feature.warehouse.model.PickingLoadResult
import com.nexa.mobile.operations.feature.warehouse.model.PickingMetadataRead
import com.nexa.mobile.operations.feature.warehouse.model.PickingMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.model.PickingMutationResult
import com.nexa.mobile.operations.feature.warehouse.model.PickingScopeIdentity

interface PickingMetadataStore {
    suspend fun loadIntent(scope: PickingScopeIdentity): PickingMetadataRead<PickingIntentMetadata>
    suspend fun saveIntent(intent: PickingIntentMetadata): PickingMetadataWrite
    suspend fun clearIntent(
        scope: PickingScopeIdentity,
        idempotencyKey: String
    ): PickingMetadataWrite
}

/** Safe default until the durable scope-bound adapter is supplied by the integrator. */
object UnavailablePickingMetadataStore : PickingMetadataStore {
    override suspend fun loadIntent(
        scope: PickingScopeIdentity
    ): PickingMetadataRead<PickingIntentMetadata> = PickingMetadataRead.Unavailable

    override suspend fun saveIntent(intent: PickingIntentMetadata): PickingMetadataWrite =
        PickingMetadataWrite.Unavailable

    override suspend fun clearIntent(
        scope: PickingScopeIdentity,
        idempotencyKey: String
    ): PickingMetadataWrite = PickingMetadataWrite.Unavailable
}

/** Feature port: all facts and authorization are supplied by the current server session. */
interface PickingGateway {
    suspend fun load(fulfillmentId: String, authority: PickingAuthority): PickingLoadResult

    suspend fun startPicking(
        command: PickingIntentCommand.Start,
        idempotencyKey: String,
        authority: PickingAuthority
    ): PickingMutationResult

    suspend fun confirmPicking(
        command: PickingConfirmationCommand,
        idempotencyKey: String,
        authority: PickingAuthority
    ): PickingMutationResult
}
