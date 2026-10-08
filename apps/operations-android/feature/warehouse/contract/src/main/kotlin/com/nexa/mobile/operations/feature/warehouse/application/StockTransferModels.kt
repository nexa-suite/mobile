package com.nexa.mobile.operations.feature.warehouse.application

import com.nexa.mobile.operations.feature.warehouse.model.StockTransferAuthority
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferIntent
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferScope
import com.nexa.mobile.operations.feature.warehouse.model.TransferLookupResult
import com.nexa.mobile.operations.feature.warehouse.model.TransferMetadataRead
import com.nexa.mobile.operations.feature.warehouse.model.TransferMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.model.TransferSubmitResult

interface StockTransferGateway {
    suspend fun warehouses(authority: StockTransferAuthority): TransferLookupResult
    suspend fun zones(warehouseId: String, authority: StockTransferAuthority): TransferLookupResult
    suspend fun sourceLots(authority: StockTransferAuthority): TransferLookupResult
    suspend fun create(
        frozenPayload: String,
        expectedSourceVersion: Long,
        idempotencyKey: String,
        authority: StockTransferAuthority
    ): TransferSubmitResult
}

interface StockTransferMetadataStore {
    suspend fun loadIntent(scope: StockTransferScope): TransferMetadataRead<StockTransferIntent>
    suspend fun saveIntent(intent: StockTransferIntent): TransferMetadataWrite
    suspend fun markUnknownOutcome(
        scope: StockTransferScope,
        idempotencyKey: String
    ): TransferMetadataWrite
    suspend fun clearIntent(
        scope: StockTransferScope,
        idempotencyKey: String
    ): TransferMetadataWrite
}
