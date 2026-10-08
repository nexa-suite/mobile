package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsAllocation
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness

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
