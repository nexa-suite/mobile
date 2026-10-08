package com.nexa.mobile.operations.inventoryavailability.application.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionCurrentResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionScopeIdentity
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionWork

/** Stores only the exact scope-bound command needed for explicit recovery. */
interface LotSubstitutionMetadataStore {
    suspend fun load(scope: LotSubstitutionScopeIdentity): LotSubstitutionMetadataRead
    suspend fun freeze(intent: LotSubstitutionIntent): LotSubstitutionMetadataWrite
    suspend fun markUnknown(
        scope: LotSubstitutionScopeIdentity,
        idempotencyKey: String
    ): LotSubstitutionMetadataWrite
    suspend fun clear(
        scope: LotSubstitutionScopeIdentity,
        idempotencyKey: String
    ): LotSubstitutionMetadataWrite
}

interface LotSubstitutionGateway {
    suspend fun alternatives(
        work: LotSubstitutionWork,
        authority: LotSubstitutionAuthority
    ): LotSubstitutionLookupResult

    suspend fun request(
        intent: LotSubstitutionIntent,
        authority: LotSubstitutionAuthority
    ): LotSubstitutionResult

    suspend fun currentAllocation(
        work: LotSubstitutionWork,
        authority: LotSubstitutionAuthority
    ): LotSubstitutionCurrentResult
}
