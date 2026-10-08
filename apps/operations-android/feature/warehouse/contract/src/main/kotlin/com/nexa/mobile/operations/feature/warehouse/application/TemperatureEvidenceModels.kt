package com.nexa.mobile.operations.feature.warehouse.application

import com.nexa.mobile.operations.feature.warehouse.model.TemperatureEvidenceAuthority
import com.nexa.mobile.operations.feature.warehouse.model.TemperatureEvidenceDraft
import com.nexa.mobile.operations.feature.warehouse.model.TemperatureEvidenceIntent
import com.nexa.mobile.operations.feature.warehouse.model.TemperatureEvidencePayload
import com.nexa.mobile.operations.feature.warehouse.model.TemperatureEvidencePhotoCandidate
import com.nexa.mobile.operations.feature.warehouse.model.TemperatureEvidencePhotoSelection
import com.nexa.mobile.operations.feature.warehouse.model.TemperatureEvidenceScope
import com.nexa.mobile.operations.feature.warehouse.model.TemperatureEvidenceSubjectType
import com.nexa.mobile.operations.feature.warehouse.model.TemperatureLookupResult
import com.nexa.mobile.operations.feature.warehouse.model.TemperatureMetadataRead
import com.nexa.mobile.operations.feature.warehouse.model.TemperatureMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.model.TemperaturePhotoResult
import com.nexa.mobile.operations.feature.warehouse.model.TemperatureSubmitResult

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
