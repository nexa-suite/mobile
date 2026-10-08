package com.nexa.mobile.operations.inventoryavailability.application.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.CycleCountAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.CycleCountCorrectionIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.CycleCountIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.CycleCountLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.CycleCountMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.CycleCountMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.CycleCountResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.CycleCountScope
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.CycleCountStoredWork
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.CycleCountCorrection
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.CycleCountRecord

interface CycleCountMetadataStore {
    suspend fun load(scope: CycleCountScope): CycleCountMetadataRead
    suspend fun saveDraft(work: CycleCountStoredWork): CycleCountMetadataWrite
    suspend fun freezeCount(intent: CycleCountIntent): CycleCountMetadataWrite
    suspend fun markCountUnknown(
        scope: CycleCountScope,
        idempotencyKey: String
    ): CycleCountMetadataWrite
    suspend fun completeCount(
        scope: CycleCountScope,
        idempotencyKey: String,
        count: CycleCountRecord
    ): CycleCountMetadataWrite
    suspend fun clearCountIntent(
        scope: CycleCountScope,
        idempotencyKey: String
    ): CycleCountMetadataWrite
    suspend fun freezeCorrection(intent: CycleCountCorrectionIntent): CycleCountMetadataWrite
    suspend fun markCorrectionUnknown(
        scope: CycleCountScope,
        idempotencyKey: String
    ): CycleCountMetadataWrite
    suspend fun completeCorrection(
        scope: CycleCountScope,
        idempotencyKey: String,
        correction: CycleCountCorrection
    ): CycleCountMetadataWrite
    suspend fun clearCorrectionIntent(
        scope: CycleCountScope,
        idempotencyKey: String
    ): CycleCountMetadataWrite
    suspend fun clearStaleCount(scope: CycleCountScope, countId: String): CycleCountMetadataWrite
}

interface CycleCountGateway {
    suspend fun lots(authority: CycleCountAuthority, page: Int = 0): CycleCountLookupResult
    suspend fun record(intent: CycleCountIntent, authority: CycleCountAuthority): CycleCountResult
    suspend fun applyCorrection(
        intent: CycleCountCorrectionIntent,
        authority: CycleCountAuthority
    ): CycleCountResult
}
