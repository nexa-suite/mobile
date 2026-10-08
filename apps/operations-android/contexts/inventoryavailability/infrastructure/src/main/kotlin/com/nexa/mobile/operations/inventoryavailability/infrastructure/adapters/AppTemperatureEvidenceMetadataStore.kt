package com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters

import android.content.Context
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.temperature.AndroidTemperatureEvidenceMetadataStore
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.temperature.StoredTemperatureSubjectType
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.temperature.StoredTemperatureUnit
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.temperature.TemperatureEvidenceCommandPayload
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.temperature.TemperatureEvidenceDraftRecord
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.temperature.TemperatureEvidenceIntentRecord
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.temperature.TemperatureEvidenceIntentStatus
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.temperature.TemperatureEvidenceMetadataStore as LocalTemperatureMetadataStore
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.temperature.TemperatureMetadataRead as LocalTemperatureMetadataRead
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.temperature.TemperatureMetadataScope
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.temperature.TemperatureMetadataWrite as LocalTemperatureMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.TemperatureEvidenceMetadataStore
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidenceDraft
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidenceIntent
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidencePayload
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidenceScope
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceSubjectType
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceUnit
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureIntentStatus
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureMetadataWrite
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** App-only bridge; local storage contains drafts and exact intents, never response facts. */
class AppTemperatureEvidenceMetadataStore(private val local: LocalTemperatureMetadataStore) :
    TemperatureEvidenceMetadataStore {
    override suspend fun loadDraft(
        scope: TemperatureEvidenceScope
    ): TemperatureMetadataRead<TemperatureEvidenceDraft> =
        when (val result = local.loadDraft(scope.toLocal())) {
            LocalTemperatureMetadataRead.Unavailable -> TemperatureMetadataRead.Unavailable

            is LocalTemperatureMetadataRead.Available -> TemperatureMetadataRead.Available(
                result.value?.toFeatureDraft()
            )
        }

    override suspend fun saveDraft(
        scope: TemperatureEvidenceScope,
        draft: TemperatureEvidenceDraft
    ): TemperatureMetadataWrite = local.saveDraft(
        scope.toLocal(),
        TemperatureEvidenceDraftRecord(
            subjectType = draft.subjectType.toLocal(),
            subjectIdText = draft.subjectId,
            valueText = draft.value,
            unit = draft.unit.toLocal(),
            occurredAtText = draft.occurredAt,
            affectedQuantityText = draft.affectedQuantity,
            reasonText = draft.reason,
            sourceEvidenceIdText = draft.sourceEvidenceId,
            evidenceObjectId = draft.evidenceObjectId
        )
    ).toFeature()

    override suspend fun loadIntent(
        scope: TemperatureEvidenceScope
    ): TemperatureMetadataRead<TemperatureEvidenceIntent> =
        when (val result = local.loadIntent(scope.toLocal())) {
            LocalTemperatureMetadataRead.Unavailable -> TemperatureMetadataRead.Unavailable

            is LocalTemperatureMetadataRead.Available -> {
                val intent = result.value?.toFeatureIntentOrNull()
                if (result.value != null && intent == null) {
                    TemperatureMetadataRead.Unavailable
                } else {
                    TemperatureMetadataRead.Available(intent)
                }
            }
        }

    override suspend fun saveIntent(intent: TemperatureEvidenceIntent): TemperatureMetadataWrite =
        try {
            local.saveIntent(intent.toLocal()).toFeature()
        } catch (_: IllegalArgumentException) {
            TemperatureMetadataWrite.Unavailable
        }

    override suspend fun markUnknownOutcome(
        scope: TemperatureEvidenceScope,
        idempotencyKey: String
    ): TemperatureMetadataWrite =
        local.markUnknownOutcome(scope.toLocal(), idempotencyKey).toFeature()

    override suspend fun clearIntent(
        scope: TemperatureEvidenceScope,
        idempotencyKey: String
    ): TemperatureMetadataWrite = local.clearIntent(scope.toLocal(), idempotencyKey).toFeature()
}

@Module
@InstallIn(SingletonComponent::class)
object AppTemperatureEvidenceMetadataBindings {
    @Provides
    @Singleton
    fun temperatureEvidenceMetadataStore(
        @ApplicationContext context: Context
    ): TemperatureEvidenceMetadataStore =
        AppTemperatureEvidenceMetadataStore(AndroidTemperatureEvidenceMetadataStore(context))
}

private fun TemperatureEvidenceScope.toLocal(): TemperatureMetadataScope =
    TemperatureMetadataScope(userId, tenantId, workspaceId, membershipId)

private fun LocalTemperatureMetadataWrite.toFeature(): TemperatureMetadataWrite = when (this) {
    LocalTemperatureMetadataWrite.Saved -> TemperatureMetadataWrite.Saved

    LocalTemperatureMetadataWrite.Conflict,
    LocalTemperatureMetadataWrite.Stale,
    LocalTemperatureMetadataWrite.Unavailable -> TemperatureMetadataWrite.Unavailable
}

private fun TemperatureEvidenceSubjectType.toLocal(): StoredTemperatureSubjectType = when (this) {
    TemperatureEvidenceSubjectType.LOT -> StoredTemperatureSubjectType.LOT
    TemperatureEvidenceSubjectType.WAREHOUSE -> StoredTemperatureSubjectType.WAREHOUSE
}

private fun StoredTemperatureSubjectType.toFeature(): TemperatureEvidenceSubjectType = when (this) {
    StoredTemperatureSubjectType.LOT -> TemperatureEvidenceSubjectType.LOT
    StoredTemperatureSubjectType.WAREHOUSE -> TemperatureEvidenceSubjectType.WAREHOUSE
}

private fun TemperatureEvidenceUnit.toLocal(): StoredTemperatureUnit = when (this) {
    TemperatureEvidenceUnit.CELSIUS -> StoredTemperatureUnit.CELSIUS
    TemperatureEvidenceUnit.FAHRENHEIT -> StoredTemperatureUnit.FAHRENHEIT
}

private fun StoredTemperatureUnit.toFeature(): TemperatureEvidenceUnit = when (this) {
    StoredTemperatureUnit.CELSIUS -> TemperatureEvidenceUnit.CELSIUS
    StoredTemperatureUnit.FAHRENHEIT -> TemperatureEvidenceUnit.FAHRENHEIT
}

private fun TemperatureEvidenceDraftRecord.toFeatureDraft() = TemperatureEvidenceDraft(
    subjectType.toFeature(),
    subjectIdText,
    valueText,
    unit.toFeature(),
    occurredAtText,
    affectedQuantityText,
    reasonText,
    sourceEvidenceIdText,
    evidenceObjectId
)

private fun TemperatureEvidenceIntent.toLocal(): TemperatureEvidenceIntentRecord =
    TemperatureEvidenceIntentRecord(
        scope = scope.toLocal(),
        idempotencyKey = idempotencyKey,
        payload = TemperatureEvidenceCommandPayload(
            subjectType = payload.subjectType.toLocal(),
            subjectId = payload.subjectId,
            value = payload.value,
            unit = payload.unit.toLocal(),
            occurredAt = payload.occurredAt,
            evidenceObjectId = payload.evidenceObjectId,
            expectedLotVersion = payload.expectedLotVersion,
            affectedQuantity = payload.affectedQuantity,
            reason = payload.reason,
            sourceEvidenceId = payload.sourceEvidenceId
        ),
        status = when (status) {
            TemperatureIntentStatus.Pending -> TemperatureEvidenceIntentStatus.Pending
            TemperatureIntentStatus.UnknownOutcome -> TemperatureEvidenceIntentStatus.UnknownOutcome
        }
    )

private fun TemperatureEvidenceIntentRecord.toFeatureIntentOrNull(): TemperatureEvidenceIntent? =
    try {
        TemperatureEvidenceIntent(
            scope = TemperatureEvidenceScope(
                scope.userId,
                scope.tenantId,
                scope.workspaceId,
                scope.membershipId
            ),
            idempotencyKey = idempotencyKey,
            payload = TemperatureEvidencePayload(
                subjectType = payload.subjectType.toFeature(),
                subjectId = payload.subjectId,
                value = payload.value,
                unit = payload.unit.toFeature(),
                occurredAt = payload.occurredAt,
                evidenceObjectId = payload.evidenceObjectId,
                expectedLotVersion = payload.expectedLotVersion,
                affectedQuantity = payload.affectedQuantity,
                reason = payload.reason,
                sourceEvidenceId = payload.sourceEvidenceId
            ),
            status = when (status) {
                TemperatureEvidenceIntentStatus.Pending -> TemperatureIntentStatus.Pending

                TemperatureEvidenceIntentStatus.UnknownOutcome ->
                    TemperatureIntentStatus.UnknownOutcome
            }
        )
    } catch (_: RuntimeException) {
        null
    }
