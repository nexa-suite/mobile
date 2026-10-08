package com.nexa.mobile.operations.inventoryavailability.application.warehouse

import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptObservationResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferReceiptResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.StockTransferScope

interface StockTransferReceiptGateway {
    suspend fun warehouses(authority: StockTransferAuthority): StockTransferReceiptLookupResult
    suspend fun transfers(
        destinationWarehouseId: String,
        page: Int,
        authority: StockTransferAuthority
    ): StockTransferReceiptLookupResult
    suspend fun transfer(
        transferId: String,
        authority: StockTransferAuthority
    ): StockTransferReceiptLookupResult
    suspend fun receive(
        intent: StockTransferReceiptIntent,
        authority: StockTransferAuthority
    ): StockTransferReceiptResult
    suspend fun observeArrival(
        intent: StockTransferReceiptObservationIntent,
        authority: StockTransferAuthority
    ): StockTransferReceiptObservationResult
}

interface StockTransferReceiptMetadataStore {
    suspend fun loadIntent(scope: StockTransferScope): StockTransferReceiptMetadataRead
    suspend fun saveIntent(intent: StockTransferReceiptIntent): StockTransferReceiptMetadataWrite
    suspend fun markUnknownOutcome(
        scope: StockTransferScope,
        idempotencyKey: String
    ): StockTransferReceiptMetadataWrite
    suspend fun clearIntent(
        scope: StockTransferScope,
        idempotencyKey: String
    ): StockTransferReceiptMetadataWrite
}

interface StockTransferReceiptObservationMetadataStore {
    suspend fun loadIntent(scope: StockTransferScope): StockTransferReceiptObservationMetadataRead
    suspend fun saveIntent(
        intent: StockTransferReceiptObservationIntent
    ): StockTransferReceiptObservationMetadataWrite
    suspend fun markUnknownOutcome(
        scope: StockTransferScope,
        idempotencyKey: String
    ): StockTransferReceiptObservationMetadataWrite
    suspend fun clearIntent(
        scope: StockTransferScope,
        idempotencyKey: String
    ): StockTransferReceiptObservationMetadataWrite
}
