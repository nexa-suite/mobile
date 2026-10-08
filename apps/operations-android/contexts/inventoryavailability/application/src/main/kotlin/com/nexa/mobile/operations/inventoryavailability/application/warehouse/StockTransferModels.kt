package com.nexa.mobile.operations.inventoryavailability.application.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferScope
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TransferLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TransferMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TransferMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TransferSubmitResult

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
