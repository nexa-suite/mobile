package com.nexa.mobile.operations.feature.warehouse.application

import com.nexa.mobile.operations.feature.warehouse.model.DispositionAuthority
import com.nexa.mobile.operations.feature.warehouse.model.DispositionDraftMetadata
import com.nexa.mobile.operations.feature.warehouse.model.DispositionGatewayResult
import com.nexa.mobile.operations.feature.warehouse.model.DispositionIntentMetadata
import com.nexa.mobile.operations.feature.warehouse.model.DispositionMetadataRead
import com.nexa.mobile.operations.feature.warehouse.model.DispositionMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.model.DispositionScopeIdentity
import com.nexa.mobile.operations.feature.warehouse.model.LotDispositionCommand

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
