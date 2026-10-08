package com.nexa.mobile.operations.feature.warehouse.application

import com.nexa.mobile.operations.feature.warehouse.model.InboundReceiptRequest
import com.nexa.mobile.operations.feature.warehouse.model.ReceivingAuthority
import com.nexa.mobile.operations.feature.warehouse.model.ReceivingDraftMetadata
import com.nexa.mobile.operations.feature.warehouse.model.ReceivingEvidenceCandidate
import com.nexa.mobile.operations.feature.warehouse.model.ReceivingEvidenceResult
import com.nexa.mobile.operations.feature.warehouse.model.ReceivingIntentMetadata
import com.nexa.mobile.operations.feature.warehouse.model.ReceivingLookupResult
import com.nexa.mobile.operations.feature.warehouse.model.ReceivingMetadataRead
import com.nexa.mobile.operations.feature.warehouse.model.ReceivingMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.model.ReceivingScopeIdentity
import com.nexa.mobile.operations.feature.warehouse.model.ReceivingSubmitResult

/** Feature port; implementations must bind each request to the current verified authority. */
interface ReceivingGateway {
    suspend fun warehouses(authority: ReceivingAuthority): ReceivingLookupResult

    suspend fun zones(warehouseId: String, authority: ReceivingAuthority): ReceivingLookupResult

    suspend fun receive(
        request: InboundReceiptRequest,
        idempotencyKey: String,
        authority: ReceivingAuthority
    ): ReceivingSubmitResult

    suspend fun uploadTemperatureEvidence(
        warehouseId: String,
        candidate: ReceivingEvidenceCandidate,
        idempotencyKey: String,
        authority: ReceivingAuthority
    ): ReceivingEvidenceResult = ReceivingEvidenceResult.ServiceUnavailable

    suspend fun temperatureEvidenceStatus(
        evidenceId: String,
        warehouseId: String,
        authority: ReceivingAuthority
    ): ReceivingEvidenceResult = ReceivingEvidenceResult.ServiceUnavailable
}

/**
 * Non-authoritative, scope-bound metadata port. A production implementation must be durable,
 * atomic, and scoped; it must not store credentials, permission snapshots, or server lot facts.
 */
interface ReceivingMetadataStore {
    suspend fun loadDraft(
        scope: ReceivingScopeIdentity
    ): ReceivingMetadataRead<ReceivingDraftMetadata>

    suspend fun saveDraft(
        scope: ReceivingScopeIdentity,
        draft: ReceivingDraftMetadata
    ): ReceivingMetadataWrite

    suspend fun loadIntent(
        scope: ReceivingScopeIdentity
    ): ReceivingMetadataRead<ReceivingIntentMetadata>

    suspend fun saveIntent(intent: ReceivingIntentMetadata): ReceivingMetadataWrite

    suspend fun clearIntent(
        scope: ReceivingScopeIdentity,
        idempotencyKey: String
    ): ReceivingMetadataWrite
}

/** Safe default until the durable scoped adapter is installed by the application. */
object UnavailableReceivingMetadataStore : ReceivingMetadataStore {
    override suspend fun loadDraft(
        scope: ReceivingScopeIdentity
    ): ReceivingMetadataRead<ReceivingDraftMetadata> = ReceivingMetadataRead.Unavailable

    override suspend fun saveDraft(
        scope: ReceivingScopeIdentity,
        draft: ReceivingDraftMetadata
    ): ReceivingMetadataWrite = ReceivingMetadataWrite.Unavailable

    override suspend fun loadIntent(
        scope: ReceivingScopeIdentity
    ): ReceivingMetadataRead<ReceivingIntentMetadata> = ReceivingMetadataRead.Unavailable

    override suspend fun saveIntent(intent: ReceivingIntentMetadata): ReceivingMetadataWrite =
        ReceivingMetadataWrite.Unavailable

    override suspend fun clearIntent(
        scope: ReceivingScopeIdentity,
        idempotencyKey: String
    ): ReceivingMetadataWrite = ReceivingMetadataWrite.Unavailable
}
