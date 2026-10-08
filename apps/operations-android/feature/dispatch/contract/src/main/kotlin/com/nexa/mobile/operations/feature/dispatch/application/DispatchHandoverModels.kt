package com.nexa.mobile.operations.feature.dispatch.application

import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoverCommand
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoverGatewayResult
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoverIntent
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoverMetadataRead
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoverMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoverSnapshot
import com.nexa.mobile.operations.feature.dispatch.model.DispatchOutgoingGoodsScopeIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchReadiness

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
