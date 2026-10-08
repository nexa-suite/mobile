package com.nexa.mobile.operations.feature.warehouse.application

import com.nexa.mobile.operations.feature.warehouse.model.CycleCountAuthority
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountCorrection
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountCorrectionIntent
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountIntent
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountLookupResult
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountMetadataRead
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountRecord
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountResult
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountScope
import com.nexa.mobile.operations.feature.warehouse.model.CycleCountStoredWork

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
