package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAssignmentScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness

/** Stores immutable uncertain commands before dispatch; this port never sends requests. */
interface DispatchAssignmentMetadataStore {
    suspend fun loadIntent(
        scope: DispatchAssignmentScopeIdentity,
        fulfillmentId: String
    ): DispatchAssignmentMetadataRead

    suspend fun saveIntent(intent: DispatchAssignmentIntent): DispatchAssignmentMetadataWrite

    suspend fun clearIntent(
        scope: DispatchAssignmentScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): DispatchAssignmentMetadataWrite
}

/** Only accepts current server readiness, current eligible drivers and current assignment facts. */
interface DispatchAssignmentGateway {
    suspend fun load(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): DispatchAssignmentGatewayResult

    suspend fun assign(
        fulfillment: DispatchReadiness,
        responsibleMembershipId: String,
        context: DispatchAuthorityContext,
        idempotencyKey: String
    ): DispatchAssignmentGatewayResult

    /** Reposts only the exact command already staged for this verified actor scope. */
    suspend fun replay(
        intent: DispatchAssignmentIntent,
        context: DispatchAuthorityContext
    ): DispatchAssignmentGatewayResult
}
