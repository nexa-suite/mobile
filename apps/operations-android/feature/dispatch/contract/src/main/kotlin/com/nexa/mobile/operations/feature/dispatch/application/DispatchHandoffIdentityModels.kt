package com.nexa.mobile.operations.feature.dispatch.application

import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoffIdentityCommand
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoffIssueResult
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoffMetadataRead
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoffMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoffValidationResult

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
