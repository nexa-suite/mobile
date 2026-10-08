package com.nexa.mobile.operations.data

import android.content.Context
import com.nexa.mobile.operations.core.local.disposition.AndroidDispositionMetadataStore
import com.nexa.mobile.operations.core.local.disposition.DispositionCommandPayload as LocalCommandPayload
import com.nexa.mobile.operations.core.local.disposition.DispositionDraftRecord as LocalDraft
import com.nexa.mobile.operations.core.local.disposition.DispositionIntentRecord as LocalIntent
import com.nexa.mobile.operations.core.local.disposition.DispositionIntentStatus as LocalIntentStatus
import com.nexa.mobile.operations.core.local.disposition.DispositionMetadataRead as LocalRead
import com.nexa.mobile.operations.core.local.disposition.DispositionMetadataScope as LocalScope
import com.nexa.mobile.operations.core.local.disposition.DispositionMetadataStore as LocalStore
import com.nexa.mobile.operations.core.local.disposition.DispositionMetadataWrite as LocalWrite
import com.nexa.mobile.operations.core.local.disposition.StoredLotDisposition as LocalDisposition
import com.nexa.mobile.operations.feature.warehouse.application.DispositionMetadataStore
import com.nexa.mobile.operations.feature.warehouse.model.DispositionDraftMetadata
import com.nexa.mobile.operations.feature.warehouse.model.DispositionIntentMetadata
import com.nexa.mobile.operations.feature.warehouse.model.DispositionIntentMetadataStatus
import com.nexa.mobile.operations.feature.warehouse.model.DispositionMetadataRead
import com.nexa.mobile.operations.feature.warehouse.model.DispositionMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.model.DispositionScopeIdentity
import com.nexa.mobile.operations.feature.warehouse.model.LotDispositionAction
import com.nexa.mobile.operations.feature.warehouse.model.LotDispositionCommand
import com.nexa.mobile.operations.feature.warehouse.model.PartialDispositionEvaluation
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.math.BigDecimal
import javax.inject.Singleton

/** App-only adapter between the feature metadata port and encrypted durable local storage. */
class AppDispositionMetadataStore(private val local: LocalStore) : DispositionMetadataStore {
    override suspend fun loadDraft(
        scope: DispositionScopeIdentity
    ): DispositionMetadataRead<DispositionDraftMetadata> =
        when (val result = local.loadDraft(scope.toLocal())) {
            LocalRead.Unavailable -> DispositionMetadataRead.Unavailable

            is LocalRead.Available -> DispositionMetadataRead.Available(
                result.value?.let {
                    DispositionDraftMetadata(
                        lotIdText = it.lotId.orEmpty(),
                        disposition = it.disposition?.toFeature(),
                        reason = it.reason
                    )
                }
            )
        }

    override suspend fun saveDraft(
        scope: DispositionScopeIdentity,
        draft: DispositionDraftMetadata
    ): DispositionMetadataWrite = local.saveDraft(
        scope.toLocal(),
        LocalDraft(
            lotId = draft.lotIdText,
            disposition = draft.disposition?.toLocal(),
            reason = draft.reason
        )
    ).toFeature()

    override suspend fun loadIntent(
        scope: DispositionScopeIdentity
    ): DispositionMetadataRead<DispositionIntentMetadata> =
        when (val result = local.loadIntent(scope.toLocal())) {
            LocalRead.Unavailable -> DispositionMetadataRead.Unavailable

            is LocalRead.Available -> {
                val intent = result.value?.toFeatureOrNull()
                if (result.value != null && intent == null) {
                    DispositionMetadataRead.Unavailable
                } else {
                    DispositionMetadataRead.Available(intent)
                }
            }
        }

    override suspend fun saveIntent(intent: DispositionIntentMetadata): DispositionMetadataWrite =
        try {
            local.saveIntent(intent.toLocal()).toFeature()
        } catch (_: IllegalArgumentException) {
            DispositionMetadataWrite.Unavailable
        }

    override suspend fun clearIntent(
        scope: DispositionScopeIdentity,
        expectedIdempotencyKey: String
    ): DispositionMetadataWrite =
        local.clearIntent(scope.toLocal(), expectedIdempotencyKey).toFeature()
}

@Module
@InstallIn(SingletonComponent::class)
object AppDispositionMetadataBindings {
    @Provides
    @Singleton
    fun dispositionMetadataStore(@ApplicationContext context: Context): DispositionMetadataStore =
        AppDispositionMetadataStore(AndroidDispositionMetadataStore(context))
}

private fun DispositionScopeIdentity.toLocal() =
    LocalScope(userId, tenantId, workspaceId, membershipId)

private fun LocalWrite.toFeature(): DispositionMetadataWrite = when (this) {
    LocalWrite.Saved -> DispositionMetadataWrite.Saved
    LocalWrite.Conflict -> DispositionMetadataWrite.Unavailable
    LocalWrite.Stale -> DispositionMetadataWrite.Unavailable
    LocalWrite.Unavailable -> DispositionMetadataWrite.Unavailable
}

private fun LotDispositionAction.toLocal(): LocalDisposition = when (this) {
    LotDispositionAction.RELEASE -> LocalDisposition.RELEASE
    LotDispositionAction.HOLD -> LocalDisposition.HOLD
    LotDispositionAction.WASTE -> LocalDisposition.WASTE
    LotDispositionAction.RETURN_TO_SUPPLIER -> LocalDisposition.RETURN_TO_SUPPLIER
}

private fun LocalDisposition.toFeature(): LotDispositionAction = when (this) {
    LocalDisposition.RELEASE -> LotDispositionAction.RELEASE
    LocalDisposition.HOLD -> LotDispositionAction.HOLD
    LocalDisposition.WASTE -> LotDispositionAction.WASTE
    LocalDisposition.RETURN_TO_SUPPLIER -> LotDispositionAction.RETURN_TO_SUPPLIER
}

private fun DispositionIntentMetadata.toLocal() = LocalIntent(
    scope = scope.toLocal(),
    idempotencyKey = idempotencyKey,
    payload = LocalCommandPayload(
        lotId = command.lotId,
        disposition = command.disposition.toLocal(),
        reason = command.reason,
        expectedVersion = command.expectedVersion,
        affectedQuantity = command.partialEvaluation?.affectedQuantity?.toPlainString(),
        temperatureEvaluationId = command.partialEvaluation?.temperatureEvaluationId
    ),
    status = when (status) {
        DispositionIntentMetadataStatus.Pending -> LocalIntentStatus.Pending
        DispositionIntentMetadataStatus.UnknownOutcome -> LocalIntentStatus.UnknownOutcome
        DispositionIntentMetadataStatus.PreconditionFailed -> LocalIntentStatus.PreconditionFailed
        DispositionIntentMetadataStatus.Conflict -> LocalIntentStatus.Conflict
        DispositionIntentMetadataStatus.Rejected -> LocalIntentStatus.Rejected
    }
)

private fun LocalIntent.toFeatureOrNull(): DispositionIntentMetadata? = try {
    DispositionIntentMetadata(
        scope = DispositionScopeIdentity(
            scope.userId,
            scope.tenantId,
            scope.workspaceId,
            scope.membershipId
        ),
        idempotencyKey = idempotencyKey,
        command = LotDispositionCommand(
            lotId = payload.lotId,
            disposition = payload.disposition.toFeature(),
            reason = payload.reason,
            expectedVersion = payload.expectedVersion,
            partialEvaluation = payload.affectedQuantity?.let { quantity ->
                PartialDispositionEvaluation(
                    temperatureEvaluationId = requireNotNull(payload.temperatureEvaluationId),
                    affectedQuantity = BigDecimal(quantity)
                )
            }
        ),
        status = when (status) {
            LocalIntentStatus.Pending -> DispositionIntentMetadataStatus.Pending

            LocalIntentStatus.UnknownOutcome -> DispositionIntentMetadataStatus.UnknownOutcome

            LocalIntentStatus.PreconditionFailed ->
                DispositionIntentMetadataStatus.PreconditionFailed

            LocalIntentStatus.Conflict -> DispositionIntentMetadataStatus.Conflict

            LocalIntentStatus.Rejected -> DispositionIntentMetadataStatus.Rejected
        }
    )
} catch (_: RuntimeException) {
    null
}
