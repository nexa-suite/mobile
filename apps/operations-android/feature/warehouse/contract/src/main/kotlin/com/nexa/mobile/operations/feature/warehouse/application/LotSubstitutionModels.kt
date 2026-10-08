package com.nexa.mobile.operations.feature.warehouse.application

import com.nexa.mobile.operations.feature.warehouse.model.LotSubstitutionCurrentResult
import com.nexa.mobile.operations.feature.warehouse.model.LotSubstitutionIntent
import com.nexa.mobile.operations.feature.warehouse.model.LotSubstitutionLookupResult
import com.nexa.mobile.operations.feature.warehouse.model.LotSubstitutionMetadataRead
import com.nexa.mobile.operations.feature.warehouse.model.LotSubstitutionMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.model.LotSubstitutionResult
import com.nexa.mobile.operations.feature.warehouse.model.LotSubstitutionWork
import com.nexa.mobile.operations.feature.warehouse.model.PickingAuthority
import com.nexa.mobile.operations.feature.warehouse.model.PickingScopeIdentity

/** Stores only the exact scope-bound command needed for explicit recovery. */
interface LotSubstitutionMetadataStore {
    suspend fun load(scope: PickingScopeIdentity): LotSubstitutionMetadataRead
    suspend fun freeze(intent: LotSubstitutionIntent): LotSubstitutionMetadataWrite
    suspend fun markUnknown(
        scope: PickingScopeIdentity,
        idempotencyKey: String
    ): LotSubstitutionMetadataWrite
    suspend fun clear(
        scope: PickingScopeIdentity,
        idempotencyKey: String
    ): LotSubstitutionMetadataWrite
}

interface LotSubstitutionGateway {
    suspend fun alternatives(
        work: LotSubstitutionWork,
        authority: PickingAuthority
    ): LotSubstitutionLookupResult

    suspend fun request(
        intent: LotSubstitutionIntent,
        authority: PickingAuthority
    ): LotSubstitutionResult

    suspend fun currentAllocation(
        work: LotSubstitutionWork,
        authority: PickingAuthority
    ): LotSubstitutionCurrentResult
}
