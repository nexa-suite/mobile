package com.nexa.mobile.operations.inventoryavailability.application.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidenceAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidenceDraft
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidenceIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidencePhotoCandidate
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidencePhotoSelection
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidenceScope
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperaturePhotoResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureSubmitResult
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidencePayload
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceSubjectType

/** Application adapter owns protected transport and full current-authority revalidation. */
interface TemperatureEvidenceGateway {
    suspend fun subjects(
        type: TemperatureEvidenceSubjectType,
        authority: TemperatureEvidenceAuthority
    ): TemperatureLookupResult

    suspend fun subject(
        type: TemperatureEvidenceSubjectType,
        subjectId: String,
        authority: TemperatureEvidenceAuthority
    ): TemperatureLookupResult = subjects(type, authority)

    suspend fun snapshot(
        evidenceId: String,
        authority: TemperatureEvidenceAuthority
    ): TemperatureLookupResult = TemperatureLookupResult.ServiceUnavailable

    suspend fun uploadPhoto(
        selection: TemperatureEvidencePhotoSelection,
        candidate: TemperatureEvidencePhotoCandidate,
        idempotencyKey: String,
        authority: TemperatureEvidenceAuthority
    ): TemperaturePhotoResult = TemperaturePhotoResult.ServiceUnavailable

    suspend fun photoStatus(
        evidenceId: String,
        warehouseId: String,
        authority: TemperatureEvidenceAuthority
    ): TemperaturePhotoResult = TemperaturePhotoResult.ServiceUnavailable

    suspend fun record(
        payload: TemperatureEvidencePayload,
        idempotencyKey: String,
        authority: TemperatureEvidenceAuthority
    ): TemperatureSubmitResult
}

interface TemperatureEvidenceMetadataStore {
    suspend fun loadDraft(
        scope: TemperatureEvidenceScope
    ): TemperatureMetadataRead<TemperatureEvidenceDraft>

    suspend fun saveDraft(
        scope: TemperatureEvidenceScope,
        draft: TemperatureEvidenceDraft
    ): TemperatureMetadataWrite

    suspend fun loadIntent(
        scope: TemperatureEvidenceScope
    ): TemperatureMetadataRead<TemperatureEvidenceIntent>

    suspend fun saveIntent(intent: TemperatureEvidenceIntent): TemperatureMetadataWrite

    suspend fun markUnknownOutcome(
        scope: TemperatureEvidenceScope,
        idempotencyKey: String
    ): TemperatureMetadataWrite

    suspend fun clearIntent(
        scope: TemperatureEvidenceScope,
        idempotencyKey: String
    ): TemperatureMetadataWrite
}
