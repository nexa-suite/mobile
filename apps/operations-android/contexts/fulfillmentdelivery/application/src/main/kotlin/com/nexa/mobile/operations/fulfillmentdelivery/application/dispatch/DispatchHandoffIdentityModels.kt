package com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch

import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoffIdentityCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoffIssueResult
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoffMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoffMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoffValidationResult

interface DispatchHandoffIdentityMetadataStore {
    suspend fun load(
        scope: DispatchAuthorityIdentity,
        deliveryId: String,
        assignmentId: String
    ): DispatchHandoffMetadataRead

    suspend fun persistIntent(
        scope: DispatchAuthorityIdentity,
        command: DispatchHandoffIdentityCommand,
        replacingIdempotencyKey: String? = null
    ): DispatchHandoffMetadataWrite

    suspend fun clearCommand(
        scope: DispatchAuthorityIdentity,
        deliveryId: String,
        assignmentId: String,
        idempotencyKey: String
    ): DispatchHandoffMetadataWrite
}

interface DispatchHandoffIdentityGateway {
    suspend fun issue(
        command: DispatchHandoffIdentityCommand,
        context: DispatchAuthorityContext
    ): DispatchHandoffIssueResult

    suspend fun validate(
        deliveryId: String,
        assignmentId: String,
        token: String,
        context: DispatchAuthorityContext
    ): DispatchHandoffValidationResult
}
