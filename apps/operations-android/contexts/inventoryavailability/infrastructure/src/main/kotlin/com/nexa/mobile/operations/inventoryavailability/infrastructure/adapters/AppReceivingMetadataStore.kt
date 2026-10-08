package com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters

import android.content.Context
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundReceiptRequest
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingDraftMetadata
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingIntentMetadata
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingIntentMetadataStatus
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingScopeIdentity
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.ReceivingMetadataStore
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingProductReference
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.receiving.AndroidReceivingMetadataStore
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.receiving.ReceivingDraftMetadataRecord
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.receiving.ReceivingIntentMetadataRecord
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.receiving.ReceivingIntentPayload
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.receiving.ReceivingIntentStatus
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.receiving.ReceivingMetadataRead as LocalMetadataRead
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.receiving.ReceivingMetadataScope
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.receiving.ReceivingMetadataStore as LocalMetadataStore
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.receiving.ReceivingMetadataWrite as LocalMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.infrastructure.storage.receiving.ReceivingProductReferenceMetadata
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Singleton

/** Application-only adapter from the receiving feature port to durable scoped local metadata. */
class AppReceivingMetadataStore(private val local: LocalMetadataStore) :
    ReceivingMetadataStore {
    override suspend fun loadDraft(
        scope: ReceivingScopeIdentity
    ): ReceivingMetadataRead<ReceivingDraftMetadata> =
        when (val result = local.loadDraft(scope.toLocal())) {
            LocalMetadataRead.Unavailable -> ReceivingMetadataRead.Unavailable

            is LocalMetadataRead.Available -> ReceivingMetadataRead.Available(
                result.value?.let { draft ->
                    ReceivingDraftMetadata(
                        selectedProduct = draft.selectedProduct?.let { product ->
                            ReceivingProductReference(
                                catalogItemId = product.catalogItemId,
                                skuId = product.skuId,
                                displayName = product.displayName,
                                skuCode = product.skuCode,
                                unit = product.unit
                            )
                        },
                        warehouseId = draft.warehouseId,
                        zoneId = draft.zoneId,
                        batchNumber = draft.batchNumber,
                        expirationDateText = draft.expirationDateText,
                        quantityText = draft.quantityText,
                        unit = draft.unit,
                        temperatureReadingText = draft.temperatureReadingText,
                        temperatureEvidenceObjectId = draft.temperatureEvidenceObjectId
                    )
                }
            )
        }

    override suspend fun saveDraft(
        scope: ReceivingScopeIdentity,
        draft: ReceivingDraftMetadata
    ): ReceivingMetadataWrite = local.saveDraft(
        scope.toLocal(),
        ReceivingDraftMetadataRecord(
            selectedProduct = draft.selectedProduct?.let { product ->
                ReceivingProductReferenceMetadata(
                    catalogItemId = product.catalogItemId,
                    skuId = product.skuId,
                    displayName = product.displayName,
                    skuCode = product.skuCode,
                    unit = product.unit
                )
            },
            warehouseId = draft.warehouseId,
            zoneId = draft.zoneId,
            batchNumber = draft.batchNumber,
            expirationDateText = draft.expirationDateText,
            quantityText = draft.quantityText,
            unit = draft.unit,
            temperatureReadingText = draft.temperatureReadingText,
            temperatureEvidenceObjectId = draft.temperatureEvidenceObjectId
        )
    ).toFeatureResult()

    override suspend fun loadIntent(
        scope: ReceivingScopeIdentity
    ): ReceivingMetadataRead<ReceivingIntentMetadata> =
        when (val result = local.loadIntent(scope.toLocal())) {
            LocalMetadataRead.Unavailable -> ReceivingMetadataRead.Unavailable

            is LocalMetadataRead.Available -> {
                val intent = result.value?.let { stored -> stored.toFeatureIntentOrNull() }
                if (result.value != null && intent == null) {
                    ReceivingMetadataRead.Unavailable
                } else {
                    ReceivingMetadataRead.Available(intent)
                }
            }
        }

    override suspend fun saveIntent(intent: ReceivingIntentMetadata): ReceivingMetadataWrite = try {
        local.saveIntent(intent.toLocalIntent()).toFeatureResult()
    } catch (_: IllegalArgumentException) {
        ReceivingMetadataWrite.Unavailable
    }

    override suspend fun clearIntent(
        scope: ReceivingScopeIdentity,
        idempotencyKey: String
    ): ReceivingMetadataWrite = local.clearIntent(scope.toLocal(), idempotencyKey).toFeatureResult()
}

@Module
@InstallIn(SingletonComponent::class)
object AppReceivingMetadataBindings {
    @Provides
    @Singleton
    fun receivingMetadataStore(@ApplicationContext context: Context): ReceivingMetadataStore =
        AppReceivingMetadataStore(AndroidReceivingMetadataStore(context))
}

private fun ReceivingScopeIdentity.toLocal(): ReceivingMetadataScope =
    ReceivingMetadataScope(userId, tenantId, workspaceId, membershipId)

private fun LocalMetadataWrite.toFeatureResult(): ReceivingMetadataWrite = when (this) {
    LocalMetadataWrite.Saved -> ReceivingMetadataWrite.Saved

    LocalMetadataWrite.Conflict,
    LocalMetadataWrite.Stale,
    LocalMetadataWrite.Unavailable -> ReceivingMetadataWrite.Unavailable
}

private fun ReceivingIntentMetadata.toLocalIntent(): ReceivingIntentMetadataRecord =
    ReceivingIntentMetadataRecord(
        scope = scope.toLocal(),
        idempotencyKey = idempotencyKey,
        payload = ReceivingIntentPayload(
            warehouseId = request.warehouseId,
            zoneId = request.zoneId,
            catalogItemId = request.catalogItemId,
            skuId = request.skuId,
            batchNumber = request.batchNumber,
            expirationDate = request.expirationDate.toString(),
            quantity = request.quantity.toPlainString(),
            unit = request.unit,
            temperatureReading = request.temperatureReading?.toPlainString(),
            notes = request.notes,
            temperatureEvidenceObjectId = request.temperatureEvidenceObjectId
        ),
        status = when (status) {
            ReceivingIntentMetadataStatus.Pending -> ReceivingIntentStatus.Pending
            ReceivingIntentMetadataStatus.UnknownOutcome -> ReceivingIntentStatus.UnknownOutcome
        }
    )

private fun ReceivingIntentMetadataRecord.toFeatureIntentOrNull(): ReceivingIntentMetadata? = try {
    ReceivingIntentMetadata(
        scope = ReceivingScopeIdentity(
            scope.userId,
            scope.tenantId,
            scope.workspaceId,
            scope.membershipId
        ),
        idempotencyKey = idempotencyKey,
        request = InboundReceiptRequest(
            warehouseId = payload.warehouseId,
            zoneId = payload.zoneId,
            catalogItemId = payload.catalogItemId,
            skuId = payload.skuId,
            batchNumber = payload.batchNumber,
            expirationDate = LocalDate.parse(payload.expirationDate),
            quantity = BigDecimal(payload.quantity),
            unit = payload.unit,
            temperatureReading = payload.temperatureReading?.let { BigDecimal(it) },
            notes = payload.notes,
            temperatureEvidenceObjectId = payload.temperatureEvidenceObjectId
        ),
        status = when (status) {
            ReceivingIntentStatus.Pending -> ReceivingIntentMetadataStatus.Pending
            ReceivingIntentStatus.UnknownOutcome -> ReceivingIntentMetadataStatus.UnknownOutcome
        }
    )
} catch (_: RuntimeException) {
    null
}
