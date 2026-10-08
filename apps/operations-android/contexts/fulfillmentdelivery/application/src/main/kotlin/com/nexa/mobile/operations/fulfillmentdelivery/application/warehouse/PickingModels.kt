package com.nexa.mobile.operations.fulfillmentdelivery.application.warehouse

import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingConfirmationCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingIntentCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingIntentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingLoadResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingMutationResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingScopeIdentity

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
