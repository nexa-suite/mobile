package com.nexa.mobile.operations.inventoryavailability.application.warehouse

import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingScopeIdentity
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionCurrentResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionResult
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionWork

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
