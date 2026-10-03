package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.local.picking.AndroidPickingMetadataStore
import com.nexa.mobile.operations.core.local.picking.PickingCommandRecord
import com.nexa.mobile.operations.core.local.picking.PickingIntentMetadataRecord
import com.nexa.mobile.operations.core.local.picking.PickingIntentStatus
import com.nexa.mobile.operations.core.local.picking.PickingMetadataRead as LocalPickingRead
import com.nexa.mobile.operations.core.local.picking.PickingMetadataScope
import com.nexa.mobile.operations.core.local.picking.PickingMetadataStore as LocalPickingStore
import com.nexa.mobile.operations.core.local.picking.PickingMetadataWrite as LocalPickingWrite
import com.nexa.mobile.operations.feature.warehouse.PickingConfirmationCommand
import com.nexa.mobile.operations.feature.warehouse.PickingIntentCommand
import com.nexa.mobile.operations.feature.warehouse.PickingIntentMetadata
import com.nexa.mobile.operations.feature.warehouse.PickingIntentMetadataStatus
import com.nexa.mobile.operations.feature.warehouse.PickingMetadataRead
import com.nexa.mobile.operations.feature.warehouse.PickingMetadataStore
import com.nexa.mobile.operations.feature.warehouse.PickingMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.PickingScopeIdentity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.math.BigDecimal
import javax.inject.Singleton

/** Maps the feature's frozen command port onto encrypted, full-scope local metadata. */
internal class AppPickingMetadataStore(private val local: LocalPickingStore) :
    PickingMetadataStore {
    override suspend fun loadIntent(
        scope: PickingScopeIdentity
    ): PickingMetadataRead<PickingIntentMetadata> =
        when (val result = local.loadIntent(scope.toLocal())) {
            LocalPickingRead.Unavailable -> PickingMetadataRead.Unavailable

            is LocalPickingRead.Available -> {
                val stored = result.value
                val intent = stored?.toFeatureOrNull()
                if (stored != null && intent == null) {
                    PickingMetadataRead.Unavailable
                } else {
                    PickingMetadataRead.Available(intent)
                }
            }
        }

    override suspend fun saveIntent(intent: PickingIntentMetadata): PickingMetadataWrite = try {
        local.saveIntent(intent.toLocal()).toFeatureResult()
    } catch (_: IllegalArgumentException) {
        PickingMetadataWrite.Unavailable
    }

    override suspend fun clearIntent(
        scope: PickingScopeIdentity,
        idempotencyKey: String
    ): PickingMetadataWrite = local.clearIntent(scope.toLocal(), idempotencyKey).toFeatureResult()
}

@Module
@InstallIn(SingletonComponent::class)
internal object AppPickingMetadataBindings {
    @Provides
    @Singleton
    fun pickingMetadataStore(
        @ApplicationContext context: android.content.Context
    ): PickingMetadataStore = AppPickingMetadataStore(AndroidPickingMetadataStore(context))
}

private fun PickingScopeIdentity.toLocal() =
    PickingMetadataScope(userId, tenantId, workspaceId, membershipId)

private fun LocalPickingWrite.toFeatureResult(): PickingMetadataWrite = when (this) {
    LocalPickingWrite.Saved -> PickingMetadataWrite.Saved

    LocalPickingWrite.Conflict,
    LocalPickingWrite.Stale,
    LocalPickingWrite.Unavailable -> PickingMetadataWrite.Unavailable
}

private fun PickingIntentMetadata.toLocal(): PickingIntentMetadataRecord =
    PickingIntentMetadataRecord(
        scope = scope.toLocal(),
        idempotencyKey = idempotencyKey,
        command = when (val value = command) {
            is PickingIntentCommand.Start -> PickingCommandRecord.Start(
                fulfillmentId = value.fulfillmentId,
                expectedFulfillmentVersion = value.expectedFulfillmentVersion
            )

            is PickingIntentCommand.Confirm -> value.request.toLocal()
        },
        status = when (status) {
            PickingIntentMetadataStatus.Pending -> PickingIntentStatus.Pending
            PickingIntentMetadataStatus.UnknownOutcome -> PickingIntentStatus.UnknownOutcome
        }
    )

private fun PickingConfirmationCommand.toLocal() = PickingCommandRecord.Confirm(
    fulfillmentId = fulfillmentId,
    expectedFulfillmentVersion = expectedFulfillmentVersion,
    expectedAllocationVersion = allocationVersion,
    fulfillmentLineId = fulfillmentLineId,
    skuId = skuId,
    physicalAllocationLineId = physicalAllocationLineId,
    lotId = lotId,
    warehouseId = warehouseId,
    quantity = quantity.toPlainString(),
    unit = unit
)

private fun PickingIntentMetadataRecord.toFeatureOrNull(): PickingIntentMetadata? = try {
    PickingIntentMetadata(
        scope = PickingScopeIdentity(
            scope.userId,
            scope.tenantId,
            scope.workspaceId,
            scope.membershipId
        ),
        idempotencyKey = idempotencyKey,
        command = when (val value = command) {
            is PickingCommandRecord.Start -> PickingIntentCommand.Start(
                fulfillmentId = value.fulfillmentId,
                expectedFulfillmentVersion = value.expectedFulfillmentVersion
            )

            is PickingCommandRecord.Confirm -> PickingIntentCommand.Confirm(
                PickingConfirmationCommand(
                    fulfillmentId = value.fulfillmentId,
                    expectedFulfillmentVersion = value.expectedFulfillmentVersion,
                    allocationVersion = value.expectedAllocationVersion,
                    fulfillmentLineId = value.fulfillmentLineId,
                    skuId = value.skuId,
                    physicalAllocationLineId = value.physicalAllocationLineId,
                    lotId = value.lotId,
                    warehouseId = value.warehouseId,
                    quantity = BigDecimal(value.quantity),
                    unit = value.unit
                )
            )
        },
        status = when (status) {
            PickingIntentStatus.Pending -> PickingIntentMetadataStatus.Pending
            PickingIntentStatus.UnknownOutcome -> PickingIntentMetadataStatus.UnknownOutcome
        }
    )
} catch (_: RuntimeException) {
    null
}
