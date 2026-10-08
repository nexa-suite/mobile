package com.nexa.mobile.operations.data

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.DispatchHandoffIdentityNetworkOutcome as HandoffIdentityOutcome
import com.nexa.mobile.operations.core.network.DispatchHandoffIdentityProjection as HandoffIdentityProjection
import com.nexa.mobile.operations.core.network.NexaDispatchHandoffIdentityGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.dispatch.application.DispatchHandoffIdentityGateway as HandoffIdentityGateway
import com.nexa.mobile.operations.feature.dispatch.application.DispatchHandoffIdentityMetadataStore as HandoffIdentityMetadataStore
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoffIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoffIdentityCommand as HandoffIdentityCommand
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoffIssueResult
import com.nexa.mobile.operations.feature.dispatch.model.DispatchHandoffValidationResult as HandoffValidationResult
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Fences identity issue and validation to the verified full scope and current token lease. */
@Singleton
class OperationsDispatchHandoffIdentityGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val handoffs: NexaDispatchHandoffIdentityGateway
) : HandoffIdentityGateway {
    override suspend fun issue(
        command: HandoffIdentityCommand,
        context: DispatchAuthorityContext
    ): DispatchHandoffIssueResult {
        val authorization = authorize(context, HANDOFF_WRITE_PERMISSION)
        if (authorization !is Authorization.Current) return authorization.toIssueResult()
        val outcome = try {
            handoffs.issue(
                command.deliveryId,
                command.assignmentId,
                command.idempotencyKey,
                command.frozenBody
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DispatchHandoffIssueResult.UnknownOutcome
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context).toIssueResult()
        return when (outcome) {
            is HandoffIdentityOutcome.Identity -> {
                val value = outcome.value
                if (!value.deliveryId.equals(command.deliveryId, ignoreCase = true) ||
                    !value.assignmentId.equals(command.assignmentId, ignoreCase = true)
                ) {
                    DispatchHandoffIssueResult.UnknownOutcome
                } else {
                    val identity = value.toFeature()
                    value.token?.let { DispatchHandoffIssueResult.Issued(identity, it) }
                        ?: DispatchHandoffIssueResult.AcceptedWithoutToken(identity)
                }
            }

            is HandoffIdentityOutcome.Rejected ->
                DispatchHandoffIssueResult.Rejected(outcome.code)

            HandoffIdentityOutcome.NotFound -> DispatchHandoffIssueResult.NotFound

            HandoffIdentityOutcome.UnknownOutcome,
            HandoffIdentityOutcome.Unavailable ->
                DispatchHandoffIssueResult.UnknownOutcome

            HandoffIdentityOutcome.PermissionDenied ->
                DispatchHandoffIssueResult.PermissionDenied

            HandoffIdentityOutcome.ContextInvalidated ->
                DispatchHandoffIssueResult.ContextInvalidated

            HandoffIdentityOutcome.SessionInvalidated ->
                DispatchHandoffIssueResult.SessionInvalidated
        }
    }

    override suspend fun validate(
        deliveryId: String,
        assignmentId: String,
        token: String,
        context: DispatchAuthorityContext
    ): HandoffValidationResult {
        val authorization = authorize(context, HANDOFF_READ_PERMISSION)
        if (authorization !is Authorization.Current) return authorization.toValidationResult()
        val outcome = try {
            handoffs.validate(deliveryId, assignmentId, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return HandoffValidationResult.Unavailable
        }
        if (!isCurrent(
                context,
                authorization.lease
            )
        ) {
            return authorityDrift(context).toValidationResult()
        }
        return when (outcome) {
            is HandoffIdentityOutcome.Identity -> {
                val value = outcome.value
                if (value.token != null || !value.deliveryId.equals(
                        deliveryId,
                        ignoreCase = true
                    ) ||
                    !value.assignmentId.equals(assignmentId, ignoreCase = true)
                ) {
                    HandoffValidationResult.Unavailable
                } else {
                    HandoffValidationResult.Validated(value.toFeature())
                }
            }

            is HandoffIdentityOutcome.Rejected ->
                HandoffValidationResult.Rejected(outcome.code)

            HandoffIdentityOutcome.NotFound ->
                HandoffValidationResult.NotFound

            HandoffIdentityOutcome.UnknownOutcome,
            HandoffIdentityOutcome.Unavailable ->
                HandoffValidationResult.Unavailable

            HandoffIdentityOutcome.PermissionDenied ->
                HandoffValidationResult.PermissionDenied

            HandoffIdentityOutcome.ContextInvalidated ->
                HandoffValidationResult.ContextInvalidated

            HandoffIdentityOutcome.SessionInvalidated ->
                HandoffValidationResult.SessionInvalidated
        }
    }

    private suspend fun authorize(
        context: DispatchAuthorityContext,
        permission: String
    ): Authorization {
        if (sessions.sessionState.value !=
            SessionState.Active
        ) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val identity = context.identity ?: return Authorization.ContextInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (context.authorityEpoch <= 0 || listOf(
                identity.userId,
                identity.tenantId,
                identity.workspaceId,
                identity.membershipId
            ).any(String::isBlank) || !verified.matches(identity)
        ) {
            return Authorization.ContextInvalidated
        }
        return if (permission in identity.permissions) {
            Authorization.Current(lease)
        } else {
            Authorization.PermissionDenied
        }
    }

    private suspend fun isCurrent(
        context: DispatchAuthorityContext,
        lease: AccessTokenLease
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(lease.epoch)
        ) {
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

    private fun Authorization.toValidationResult(): HandoffValidationResult = when (this) {
        Authorization.SessionInvalidated -> HandoffValidationResult.SessionInvalidated
        Authorization.ContextInvalidated -> HandoffValidationResult.ContextInvalidated
        Authorization.PermissionDenied -> HandoffValidationResult.PermissionDenied
        is Authorization.Current -> error("current authorization is not a failure")
    }

    private fun HandoffIdentityOutcome.toIssueResult(): DispatchHandoffIssueResult = when (this) {
        HandoffIdentityOutcome.SessionInvalidated ->
            DispatchHandoffIssueResult.SessionInvalidated

        HandoffIdentityOutcome.ContextInvalidated ->
            DispatchHandoffIssueResult.ContextInvalidated

        HandoffIdentityOutcome.PermissionDenied ->
            DispatchHandoffIssueResult.PermissionDenied

        else -> DispatchHandoffIssueResult.UnknownOutcome
    }

    private fun HandoffIdentityOutcome.toValidationResult(): HandoffValidationResult = when (this) {
        HandoffIdentityOutcome.SessionInvalidated ->
            HandoffValidationResult.SessionInvalidated

        HandoffIdentityOutcome.ContextInvalidated ->
            HandoffValidationResult.ContextInvalidated

        HandoffIdentityOutcome.PermissionDenied ->
            HandoffValidationResult.PermissionDenied

        else -> HandoffValidationResult.Unavailable
    }

    private fun HandoffIdentityProjection.toFeature() = DispatchHandoffIdentity(
        handoffId,
        deliveryId,
        assignmentId,
        deliveryVersion,
        expiresAt,
        status
    )

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

@Module
@InstallIn(SingletonComponent::class)
object DispatchHandoffIdentityGatewayModule {
    @Provides
    @Singleton
    fun provideNexaDispatchHandoffIdentityGateway(protectedCalls: ProtectedCallExecutor) =
        NexaDispatchHandoffIdentityGateway(protectedCalls)
}
