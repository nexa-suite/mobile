package com.nexa.mobile.operations.tenantaccessgovernance.infrastructure.adapters

import com.nexa.mobile.operations.tenantaccessgovernance.application.publicapi.catalogReadHint

import com.nexa.mobile.operations.core.auth.session.IssuedNativeSession
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.tenantaccessgovernance.infrastructure.transport.AccessContextSelectionOutcome
import com.nexa.mobile.operations.tenantaccessgovernance.infrastructure.transport.AccessContextsOutcome

import com.nexa.mobile.operations.tenantaccessgovernance.infrastructure.transport.IdentitySignInOutcome
import com.nexa.mobile.operations.tenantaccessgovernance.infrastructure.transport.NativeAccessContext
import com.nexa.mobile.operations.tenantaccessgovernance.infrastructure.transport.NativeAuthenticationSession
import com.nexa.mobile.operations.tenantaccessgovernance.infrastructure.transport.NexaIdentityAccessGateway




import com.nexa.mobile.operations.tenantaccessgovernance.application.access.AccessGateway
import com.nexa.mobile.operations.tenantaccessgovernance.application.access.ContextListResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.access.ContextSelectionResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.access.CurrentSessionContextResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.access.SignInResult
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.PermissionHint
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.VerifiedContextAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.WorkforceContextSummary






import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

@Singleton
class OperationsAccessGateway @Inject constructor(
    private val identity: NexaIdentityAccessGateway,
    private val sessions: SessionCoordinator
) : AccessGateway {
    val currentContext = sessions.verifiedSession.map { it?.toWorkforceContext() }
    override suspend fun signIn(identifier: String, password: String): SignInResult =
        when (val result = identity.identitySignIn(identifier, password)) {
            is IdentitySignInOutcome.Authenticated -> {
                val context = establishContext(result.issuedSession, result.sessionContext, null)
                if (context ==
                    null
                ) {
                    SignInResult.ServiceUnavailable
                } else {
                    SignInResult.Authenticated(context)
                }
            }

            IdentitySignInOutcome.SelectionRequired -> SignInResult.SelectionRequired

            IdentitySignInOutcome.NoWorkContext -> SignInResult.NoWorkContext

            IdentitySignInOutcome.Rejected -> SignInResult.Rejected

            IdentitySignInOutcome.NetworkUnavailable -> SignInResult.NetworkUnavailable

            IdentitySignInOutcome.ServiceUnavailable,
            IdentitySignInOutcome.UnknownOutcome -> SignInResult.ServiceUnavailable
        }

    override suspend fun currentSessionContext(): CurrentSessionContextResult {
        if (sessions.sessionState.value != SessionState.Active) {
            return if (sessions.sessionState.value == SessionState.ContextRequired) {
                CurrentSessionContextResult.Invalid
            } else {
                CurrentSessionContextResult.Unavailable
            }
        }
        val verified = sessions.verifiedSession.value ?: return CurrentSessionContextResult.Invalid
        val context = verified.toWorkforceContext()
            ?: return CurrentSessionContextResult.Invalid
        return CurrentSessionContextResult.Available(context)
    }

    override suspend fun listContexts(): ContextListResult {
        val access = sessions.currentAccess()
        val result = identity.accessContexts(access?.value)
        if (result == AccessContextsOutcome.SessionExpired && access != null) {
            sessions.rejectCurrentAccess(access)
        }
        return when (result) {
            is AccessContextsOutcome.Available -> ContextListResult.Available(
                result.contexts.map { it.toWorkforceContext() }
            )

            AccessContextsOutcome.TicketExpired -> ContextListResult.TicketExpired

            AccessContextsOutcome.SessionExpired -> ContextListResult.SessionExpired

            AccessContextsOutcome.Rejected -> ContextListResult.ServiceUnavailable

            AccessContextsOutcome.NetworkUnavailable -> ContextListResult.NetworkUnavailable

            AccessContextsOutcome.ServiceUnavailable -> ContextListResult.ServiceUnavailable
        }
    }

    override suspend fun selectContext(context: WorkforceContextSummary): ContextSelectionResult {
        val access = sessions.currentAccess()
        val result = identity.selectAccessContext(context.key, access?.value)
        if (result == AccessContextSelectionOutcome.SessionExpired && access != null) {
            sessions.rejectCurrentAccess(access)
        }
        return when (result) {
            is AccessContextSelectionOutcome.Authenticated -> {
                val verified = establishContext(
                    result.issuedSession,
                    result.sessionContext,
                    context.key
                )
                if (verified == null) {
                    ContextSelectionResult.UnknownOutcome
                } else {
                    ContextSelectionResult.Confirmed(verified)
                }
            }

            AccessContextSelectionOutcome.Rejected -> ContextSelectionResult.Rejected

            AccessContextSelectionOutcome.SessionExpired -> ContextSelectionResult.SessionExpired

            AccessContextSelectionOutcome.UnknownOutcome -> ContextSelectionResult.UnknownOutcome
        }
    }

    private suspend fun establishContext(
        issued: IssuedNativeSession,
        expected: NativeAuthenticationSession,
        selectedMembershipId: String?
    ): WorkforceContextSummary? {
        if (expected.surface != "PLATFORM" ||
            expected.userId.isNullOrBlank() ||
            expected.tenantId.isNullOrBlank() ||
            expected.workspaceId.isNullOrBlank() ||
            expected.membershipId.isNullOrBlank() ||
            (selectedMembershipId != null && expected.membershipId != selectedMembershipId)
        ) {
            return null
        }
        if (!sessions.establish(issued)) return null
        val verified = sessions.verifiedSession.value ?: return null
        if (!verified.matches(expected)) {
            sessions.invalidateContext()
            return null
        }
        return verified.toWorkforceContext()
    }

    private fun VerifiedSession.matches(expected: NativeAuthenticationSession): Boolean =
        hasAuthorizedContext && userId == expected.userId && tenantId == expected.tenantId &&
            workspaceId == expected.workspaceId && membershipId == expected.membershipId

    private fun NativeAccessContext.toWorkforceContext() = WorkforceContextSummary(
        key = membershipId,
        companyName = tenantName,
        workspaceName = workspaceName,
        permissionHint = PermissionHint.Unknown
    )
}

fun VerifiedSession.toWorkforceContext(): WorkforceContextSummary? {
    if (!hasAuthorizedContext) return null
    val membership = membershipId?.takeIf(String::isNotBlank) ?: return null
    val user = userId?.takeIf(String::isNotBlank) ?: return null
    val tenantId = tenantId?.takeIf(String::isNotBlank) ?: return null
    val workspaceId = workspaceId?.takeIf(String::isNotBlank) ?: return null
    val tenant = tenantName?.takeIf(String::isNotBlank) ?: return null
    val workspace = workspaceName?.takeIf(String::isNotBlank) ?: return null
    val permissionHint = catalogReadHint(permissions)
    return WorkforceContextSummary(
        key = membership,
        companyName = tenant,
        workspaceName = workspace,
        permissionHint = permissionHint,
        verifiedAuthority = VerifiedContextAuthority(
            userId = user,
            tenantId = tenantId,
            workspaceId = workspaceId,
            membershipId = membership,
            permissions = permissions.toSet()
        )
    )
}
