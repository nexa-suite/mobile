package com.nexa.mobile.operations.feature.dispatch.application

import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsAllocation
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsCommand
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsGatewayResult
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsIntent
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsMetadataRead
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsScopeIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchReadiness

interface DispatchOutgoingGoodsMetadataStore {
    suspend fun loadIntent(
        scope: DispatchOutgoingGoodsScopeIdentity,
        fulfillmentId: String
    ): DispatchOutgoingGoodsMetadataRead

    suspend fun saveIntent(intent: DispatchOutgoingGoodsIntent): DispatchOutgoingGoodsMetadataWrite

    suspend fun clearIntent(
        scope: DispatchOutgoingGoodsScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): DispatchOutgoingGoodsMetadataWrite
}

/** Server-owned physical facts and immutable outgoing-goods comparison. */
interface DispatchOutgoingGoodsGateway {
    suspend fun load(
        fulfillment: DispatchReadiness,
        context: DispatchAuthorityContext
    ): DispatchOutgoingGoodsGatewayResult

    suspend fun record(
        fulfillment: DispatchReadiness,
        allocation: DispatchOutgoingGoodsAllocation,
        command: DispatchOutgoingGoodsCommand,
        context: DispatchAuthorityContext
    ): DispatchOutgoingGoodsGatewayResult

    suspend fun resolveDiscrepancy(
        fulfillment: DispatchReadiness,
        command: DispatchOutgoingGoodsCommand,
        context: DispatchAuthorityContext
    ): DispatchOutgoingGoodsGatewayResult
}
