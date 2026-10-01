package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.DispatchHandoffIdentityNetworkOutcome
import com.nexa.mobile.operations.core.network.NexaDispatchHandoffIdentityGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoffIdentity
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoffIdentityCommand
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoffIdentityGateway
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoffIdentityMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoffIdentityViewModel
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoffIssueResult
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoffValidationResult
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Fences identity issue and validation to the verified full scope and current token lease. */
@Singleton
internal class OperationsDispatchHandoffIdentityGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val handoffs: NexaDispatchHandoffIdentityGateway
) : DispatchHandoffIdentityGateway {
    override suspend fun issue(
        command: DispatchHandoffIdentityCommand,
        context: DispatchAuthorityContext
    ): DispatchHandoffIssueResult {
        val authorization = authorize(context, HANDOFF_WRITE_PERMISSION)
        if (authorization !is Authorization.Current) return authorization.toIssueResult()
        val outcome = try {
            handoffs.issue(command.deliveryId, command.assignmentId,
                command.idempotencyKey, command.frozenBody)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DispatchHandoffIssueResult.UnknownOutcome
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context).toIssueResult()
        return when (outcome) {
            is DispatchHandoffIdentityNetworkOutcome.Identity -> {
                val value = outcome.value
                if (!value.deliveryId.equals(command.deliveryId, ignoreCase = true) ||
                    !value.assignmentId.equals(command.assignmentId, ignoreCase = true)
                ) DispatchHandoffIssueResult.UnknownOutcome
                else {
                    val identity = value.toFeature()
                    value.token?.let { DispatchHandoffIssueResult.Issued(identity, it) }
                        ?: DispatchHandoffIssueResult.AcceptedWithoutToken(identity)
                }
            }
            is DispatchHandoffIdentityNetworkOutcome.Rejected ->
                DispatchHandoffIssueResult.Rejected(outcome.code)
            DispatchHandoffIdentityNetworkOutcome.NotFound -> DispatchHandoffIssueResult.NotFound
            DispatchHandoffIdentityNetworkOutcome.UnknownOutcome,
            DispatchHandoffIdentityNetworkOutcome.Unavailable -> DispatchHandoffIssueResult.UnknownOutcome
            DispatchHandoffIdentityNetworkOutcome.PermissionDenied -> DispatchHandoffIssueResult.PermissionDenied
            DispatchHandoffIdentityNetworkOutcome.ContextInvalidated -> DispatchHandoffIssueResult.ContextInvalidated
            DispatchHandoffIdentityNetworkOutcome.SessionInvalidated -> DispatchHandoffIssueResult.SessionInvalidated
        }
    }

    override suspend fun validate(
        deliveryId: String,
        assignmentId: String,
        token: String,
        context: DispatchAuthorityContext
    ): DispatchHandoffValidationResult {
        val authorization = authorize(context, HANDOFF_READ_PERMISSION)
        if (authorization !is Authorization.Current) return authorization.toValidationResult()
        val outcome = try {
            handoffs.validate(deliveryId, assignmentId, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DispatchHandoffValidationResult.Unavailable
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context).toValidationResult()
        return when (outcome) {
            is DispatchHandoffIdentityNetworkOutcome.Identity -> {
                val value = outcome.value
                if (value.token != null || !value.deliveryId.equals(deliveryId, ignoreCase = true) ||
                    !value.assignmentId.equals(assignmentId, ignoreCase = true)
                ) DispatchHandoffValidationResult.Unavailable
                else DispatchHandoffValidationResult.Validated(value.toFeature())
            }
            is DispatchHandoffIdentityNetworkOutcome.Rejected ->
                DispatchHandoffValidationResult.Rejected(outcome.code)
            DispatchHandoffIdentityNetworkOutcome.NotFound -> DispatchHandoffValidationResult.NotFound
            DispatchHandoffIdentityNetworkOutcome.UnknownOutcome,
            DispatchHandoffIdentityNetworkOutcome.Unavailable -> DispatchHandoffValidationResult.Unavailable
            DispatchHandoffIdentityNetworkOutcome.PermissionDenied -> DispatchHandoffValidationResult.PermissionDenied
            DispatchHandoffIdentityNetworkOutcome.ContextInvalidated -> DispatchHandoffValidationResult.ContextInvalidated
            DispatchHandoffIdentityNetworkOutcome.SessionInvalidated -> DispatchHandoffValidationResult.SessionInvalidated
        }
    }

    private suspend fun authorize(context: DispatchAuthorityContext, permission: String): Authorization {
        if (sessions.sessionState.value != SessionState.Active) return Authorization.SessionInvalidated
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val identity = context.identity ?: return Authorization.ContextInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (context.authorityEpoch <= 0 || listOf(
                identity.userId, identity.tenantId, identity.workspaceId, identity.membershipId
            ).any(String::isBlank) || !verified.matches(identity)
        ) return Authorization.ContextInvalidated
        return if (permission in identity.permissions) Authorization.Current(lease)
        else Authorization.PermissionDenied
    }

    private suspend fun isCurrent(context: DispatchAuthorityContext, lease: AccessTokenLease): Boolean {
        if (sessions.sessionState.value != SessionState.Active || !sessions.isEpochCurrent(lease.epoch)) {
            return false
        }
        val identity = context.identity ?: return false
        return sessions.verifiedSession.value?.matches(identity) == true
    }

    private suspend fun authorityDrift(context: DispatchAuthorityContext): Authorization = when {
        sessions.sessionState.value != SessionState.Active -> Authorization.SessionInvalidated
        sessions.verifiedSession.value?.let { verified ->
            context.identity?.let { verified.matches(it) } == true
        } == true -> Authorization.SessionInvalidated
        else -> Authorization.ContextInvalidated
    }

    private fun VerifiedSession.matches(expected: DispatchAuthorityIdentity): Boolean =
        hasAuthorizedContext && userId == expected.userId && tenantId == expected.tenantId &&
            workspaceId == expected.workspaceId && membershipId == expected.membershipId &&
            permissions == expected.permissions

    private fun Authorization.toIssueResult(): DispatchHandoffIssueResult = when (this) {
        Authorization.SessionInvalidated -> DispatchHandoffIssueResult.SessionInvalidated
        Authorization.ContextInvalidated -> DispatchHandoffIssueResult.ContextInvalidated
        Authorization.PermissionDenied -> DispatchHandoffIssueResult.PermissionDenied
        is Authorization.Current -> error("current authorization is not a failure")
    }

    private fun Authorization.toValidationResult(): DispatchHandoffValidationResult = when (this) {
        Authorization.SessionInvalidated -> DispatchHandoffValidationResult.SessionInvalidated
        Authorization.ContextInvalidated -> DispatchHandoffValidationResult.ContextInvalidated
        Authorization.PermissionDenied -> DispatchHandoffValidationResult.PermissionDenied
        is Authorization.Current -> error("current authorization is not a failure")
    }

    private fun DispatchHandoffIdentityNetworkOutcome.toIssueResult(): DispatchHandoffIssueResult = when (this) {
        DispatchHandoffIdentityNetworkOutcome.SessionInvalidated -> DispatchHandoffIssueResult.SessionInvalidated
        DispatchHandoffIdentityNetworkOutcome.ContextInvalidated -> DispatchHandoffIssueResult.ContextInvalidated
        DispatchHandoffIdentityNetworkOutcome.PermissionDenied -> DispatchHandoffIssueResult.PermissionDenied
        else -> DispatchHandoffIssueResult.UnknownOutcome
    }

    private fun DispatchHandoffIdentityNetworkOutcome.toValidationResult(): DispatchHandoffValidationResult = when (this) {
        DispatchHandoffIdentityNetworkOutcome.SessionInvalidated -> DispatchHandoffValidationResult.SessionInvalidated
        DispatchHandoffIdentityNetworkOutcome.ContextInvalidated -> DispatchHandoffValidationResult.ContextInvalidated
        DispatchHandoffIdentityNetworkOutcome.PermissionDenied -> DispatchHandoffValidationResult.PermissionDenied
        else -> DispatchHandoffValidationResult.Unavailable
    }

    private fun com.nexa.mobile.operations.core.network.DispatchHandoffIdentityProjection.toFeature() =
        DispatchHandoffIdentity(handoffId, deliveryId, assignmentId, deliveryVersion, expiresAt, status)

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        const val HANDOFF_WRITE_PERMISSION = "logistics:write"
        const val HANDOFF_READ_PERMISSION = "logistics:read"
    }
}

internal class DispatchHandoffIdentityGatewayBindings @Inject constructor(
    private val gateway: OperationsDispatchHandoffIdentityGateway,
    private val metadataStore: DispatchHandoffIdentityMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DispatchHandoffIdentityViewModel::class.java))
            return DispatchHandoffIdentityViewModel(gateway, metadataStore) as T
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DispatchHandoffIdentityGatewayModule {
    @Provides
    @Singleton
    fun provideNexaDispatchHandoffIdentityGateway(protectedCalls: ProtectedCallExecutor) =
        NexaDispatchHandoffIdentityGateway(protectedCalls)

    @Provides
    @Singleton
    fun provideDispatchHandoffIdentityGatewayBindings(
        gateway: OperationsDispatchHandoffIdentityGateway,
        metadataStore: DispatchHandoffIdentityMetadataStore
    ) = DispatchHandoffIdentityGatewayBindings(gateway, metadataStore)
}
