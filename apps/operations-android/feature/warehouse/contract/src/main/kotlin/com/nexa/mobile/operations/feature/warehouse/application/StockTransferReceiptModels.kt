package com.nexa.mobile.operations.feature.warehouse.application

import com.nexa.mobile.operations.feature.warehouse.model.StockTransferAuthority
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptIntent
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptLookupResult
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptMetadataRead
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptObservationIntent
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptObservationMetadataRead
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptObservationMetadataWrite
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptObservationResult
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptResult
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferScope

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
