package com.nexa.mobile.operations.feature.dispatch.application

import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeGatewayResult
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeIntent
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeMetadataRead
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeScopeIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchReadiness
import com.nexa.mobile.operations.feature.dispatch.model.PreparedFulfillmentDriverAssignment

interface DispatchPlanChangeMetadataStore {
    suspend fun loadIntent(
        scope: DispatchPlanChangeScopeIdentity,
        fulfillmentId: String
    ): DispatchPlanChangeMetadataRead

    suspend fun saveIntent(intent: DispatchPlanChangeIntent): DispatchPlanChangeMetadataWrite

    suspend fun clearIntent(
        scope: DispatchPlanChangeScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): DispatchPlanChangeMetadataWrite
}

interface DispatchPlanChangeGateway {
    suspend fun load(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): DispatchPlanChangeGatewayResult

    suspend fun change(
        readiness: DispatchReadiness,
        assignment: PreparedFulfillmentDriverAssignment,
        intent: DispatchPlanChangeIntent,
        context: DispatchAuthorityContext
    ): DispatchPlanChangeGatewayResult

    /** Sends only the immutable request staged before its original mutation. */
    suspend fun replay(
        intent: DispatchPlanChangeIntent,
        context: DispatchAuthorityContext
    ): DispatchPlanChangeGatewayResult
}
