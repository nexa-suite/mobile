package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchPlanChangeGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchPlanChangeIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchPlanChangeMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchPlanChangeMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchPlanChangeScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.PreparedFulfillmentDriverAssignment

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
