package com.nexa.mobile.operations.inventoryavailability.application.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.DispositionAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.DispositionDraftMetadata
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.DispositionGatewayResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.DispositionIntentMetadata
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.DispositionMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.DispositionMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.DispositionScopeIdentity
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotDispositionCommand

/** Feature port. App adapter must bind each call to current session and full verified scope. */
interface DispositionGateway {
    suspend fun lot(lotId: String, authority: DispositionAuthority): DispositionGatewayResult

    suspend fun dispose(
        command: LotDispositionCommand,
        idempotencyKey: String,
        authority: DispositionAuthority
    ): DispositionGatewayResult
}

interface DispositionMetadataStore {
    suspend fun loadDraft(
        scope: DispositionScopeIdentity
    ): DispositionMetadataRead<DispositionDraftMetadata>

    suspend fun saveDraft(
        scope: DispositionScopeIdentity,
        draft: DispositionDraftMetadata
    ): DispositionMetadataWrite

    suspend fun loadIntent(
        scope: DispositionScopeIdentity
    ): DispositionMetadataRead<DispositionIntentMetadata>

    suspend fun saveIntent(intent: DispositionIntentMetadata): DispositionMetadataWrite

    suspend fun clearIntent(
        scope: DispositionScopeIdentity,
        expectedIdempotencyKey: String
    ): DispositionMetadataWrite
}
