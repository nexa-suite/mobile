package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchHandoverSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness

/** Separate encrypted purpose from assignment and outgoing-check commands. */
interface DispatchHandoverMetadataStore {
    suspend fun loadIntent(
        scope: DispatchOutgoingGoodsScopeIdentity,
        fulfillmentId: String
    ): DispatchHandoverMetadataRead

    suspend fun saveIntent(intent: DispatchHandoverIntent): DispatchHandoverMetadataWrite

    suspend fun clearIntent(
        scope: DispatchOutgoingGoodsScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): DispatchHandoverMetadataWrite
}

/** Current-server prerequisites and a versioned, durable warehouse-control transition. */
interface DispatchHandoverGateway {
    suspend fun load(
        fulfillment: DispatchReadiness,
        context: DispatchAuthorityContext
    ): DispatchHandoverGatewayResult

    suspend fun dispatch(
        fulfillment: DispatchReadiness,
        snapshot: DispatchHandoverSnapshot?,
        command: DispatchHandoverCommand,
        context: DispatchAuthorityContext
    ): DispatchHandoverGatewayResult
}
