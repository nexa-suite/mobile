package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.DispatchAssignmentNetworkOutcome
import com.nexa.mobile.operations.core.network.DispatchDriverAssignmentProjection
import com.nexa.mobile.operations.core.network.DispatchPlanChangeNetworkOutcome
import com.nexa.mobile.operations.core.network.DispatchPlanChangeRequest
import com.nexa.mobile.operations.core.network.DispatchReadinessNetworkOutcome
import com.nexa.mobile.operations.core.network.DispatchReadinessProjection
import com.nexa.mobile.operations.core.network.NexaDispatchAssignmentGateway
import com.nexa.mobile.operations.core.network.NexaDispatchReadinessGateway
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.DispatchDriverCandidate
import com.nexa.mobile.operations.feature.dispatch.DispatchPlanChangeGateway
import com.nexa.mobile.operations.feature.dispatch.DispatchPlanChangeGatewayResult
import com.nexa.mobile.operations.feature.dispatch.DispatchPlanChangeIntent
import com.nexa.mobile.operations.feature.dispatch.DispatchPlanChangeMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchPlanChangeScopeIdentity
import com.nexa.mobile.operations.feature.dispatch.DispatchPlanChangeSnapshot
import com.nexa.mobile.operations.feature.dispatch.DispatchPlanChangeViewModel
import com.nexa.mobile.operations.feature.dispatch.DispatchReadiness
import com.nexa.mobile.operations.feature.dispatch.PreparedFulfillmentDriverAssignment
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class OperationsDispatchPlanChangeGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val assignments: NexaDispatchAssignmentGateway,
    private val readiness: NexaDispatchReadinessGateway
) : DispatchPlanChangeGateway {
    override suspend fun load(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): DispatchPlanChangeGatewayResult {
        val authorization = authorize(context, intent = null)
        if (authorization !is Authorization.Current) return authorization.toResult()
        val currentReadiness = when (val result = readiness.detail(fulfillmentId)) {
            is DispatchReadinessNetworkOutcome.Detail -> result.item.toFeature()
            else -> return result.toReadFailure()
        }
        if (currentReadiness.fulfillmentId != fulfillmentId) {
            return DispatchPlanChangeGatewayResult.ServiceUnavailable
        }
        val candidates = when (val result = assignments.candidates()) {
            is DispatchAssignmentNetworkOutcome.Candidates -> result.items.map {
                DispatchDriverCandidate(it.membershipId, it.email, it.displayName)
            }

            else -> return result.toAssignmentFailure(mutation = false)
        }
        val current = when (val result = assignments.current(fulfillmentId)) {
            is DispatchAssignmentNetworkOutcome.Current -> result.item?.toFeature()
            else -> return result.toAssignmentFailure(mutation = false)
        }
        val history = when (val result = assignments.history(fulfillmentId)) {
            is DispatchPlanChangeNetworkOutcome.History -> result.items.map { it.toFeature() }
            else -> return result.toPlanFailure(mutation = false)
        }
        if (current == null && history.isNotEmpty() || current != null &&
            (history.none { it.id == current.id && it.current } ||
                history.count { it.current } != 1)
        ) return DispatchPlanChangeGatewayResult.ServiceUnavailable
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)
        return DispatchPlanChangeGatewayResult.Snapshot(
            DispatchPlanChangeSnapshot(currentReadiness, candidates, current, history)
        )
    }

    override suspend fun change(
        readiness: DispatchReadiness,
        assignment: PreparedFulfillmentDriverAssignment,
        intent: DispatchPlanChangeIntent,
        context: DispatchAuthorityContext
    ): DispatchPlanChangeGatewayResult {
        val authorization = authorize(context, intent)
        if (authorization !is Authorization.Current) return authorization.toResult()
        if (intent.scope != context.scopeIdentity() ||
            !intent.requestsDriverChange && !intent.requestsScheduleChange ||
            intent.fulfillmentId != readiness.fulfillmentId || !assignment.current ||
            assignment.id != intent.expectedAssignmentId ||
            assignment.fulfillmentVersion != intent.expectedAssignmentVersion ||
            readiness.fulfillmentVersion != intent.expectedFulfillmentVersion ||
            readiness.physicalAllocationId != intent.physicalAllocationId ||
            readiness.physicalAllocationVersion != intent.physicalAllocationVersion
        ) return DispatchPlanChangeGatewayResult.Stale

        val currentReadiness = when (val result = this.readiness.detail(intent.fulfillmentId)) {
            is DispatchReadinessNetworkOutcome.Detail -> result.item.toFeature()
            else -> return result.toReadFailure()
        }
        if (!currentReadiness.matches(readiness)) return DispatchPlanChangeGatewayResult.Stale
        if (!currentReadiness.ready || currentReadiness.fulfillmentStatus != "READY_FOR_DISPATCH") {
            return DispatchPlanChangeGatewayResult.NotReady
        }
        val currentAssignment = when (val result = assignments.current(intent.fulfillmentId)) {
            is DispatchAssignmentNetworkOutcome.Current -> result.item?.toFeature()
            else -> return result.toAssignmentFailure(mutation = false)
        } ?: return DispatchPlanChangeGatewayResult.Stale
        if (!currentAssignment.current || currentAssignment.id != intent.expectedAssignmentId ||
            currentAssignment.fulfillmentVersion != intent.expectedAssignmentVersion ||
            currentAssignment.physicalAllocationId != intent.physicalAllocationId ||
            currentAssignment.physicalAllocationVersion != intent.physicalAllocationVersion
        ) return DispatchPlanChangeGatewayResult.Stale
        eligibleFailure(intent.resultResponsibleMembershipId)?.let { return it }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)
        return send(intent)
    }

    override suspend fun replay(
        intent: DispatchPlanChangeIntent,
        context: DispatchAuthorityContext
    ): DispatchPlanChangeGatewayResult {
        val authorization = authorize(context, intent)
        if (authorization !is Authorization.Current) return authorization.toResult()
        if (intent.scope != context.scopeIdentity()) return DispatchPlanChangeGatewayResult.ContextInvalidated

        // Current read/grant and eligible-driver checks remain mandatory. Frozen versions are
        // deliberately not compared here: the server resolves an exact idempotent replay first.
        when (val result = readiness.detail(intent.fulfillmentId)) {
            is DispatchReadinessNetworkOutcome.Detail -> if (
                result.item.fulfillmentId != intent.fulfillmentId
            ) return DispatchPlanChangeGatewayResult.ServiceUnavailable

            else -> return result.toReadFailure()
        }
        eligibleFailure(intent.resultResponsibleMembershipId)?.let { return it }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)
        return send(intent)
    }

    private suspend fun send(intent: DispatchPlanChangeIntent): DispatchPlanChangeGatewayResult =
        when (val result = assignments.changePlan(
            DispatchPlanChangeRequest(
                fulfillmentId = intent.fulfillmentId,
                expectedFulfillmentVersion = intent.expectedFulfillmentVersion,
                expectedAssignmentId = intent.expectedAssignmentId,
                expectedAssignmentVersion = intent.expectedAssignmentVersion,
                physicalAllocationId = intent.physicalAllocationId,
                physicalAllocationVersion = intent.physicalAllocationVersion,
                resultResponsibleMembershipId = intent.resultResponsibleMembershipId,
                resultPlannedDispatchAt = intent.resultPlannedDispatchAt,
                requestBody = intent.requestBody,
                idempotencyKey = intent.idempotencyKey
            )
        )) {
            is DispatchPlanChangeNetworkOutcome.Changed ->
                DispatchPlanChangeGatewayResult.Changed(result.item.toFeature())

            else -> result.toPlanFailure(mutation = true)
        }

    private suspend fun eligibleFailure(membershipId: String): DispatchPlanChangeGatewayResult? =
        when (val result = assignments.candidates()) {
            is DispatchAssignmentNetworkOutcome.Candidates -> if (
                result.items.any { it.membershipId.equals(membershipId, ignoreCase = true) }
            ) null else DispatchPlanChangeGatewayResult.Conflict

            else -> result.toAssignmentFailure(mutation = false)
        }

    private suspend fun authorize(
        context: DispatchAuthorityContext,
        intent: DispatchPlanChangeIntent?
    ): Authorization {
        if (sessions.sessionState.value != SessionState.Active) return Authorization.SessionInvalidated
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val identity = context.identity ?: return Authorization.ContextInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (context.authorityEpoch <= 0 || listOf(
                identity.userId,
                identity.tenantId,
                identity.workspaceId,
                identity.membershipId
            ).any(String::isBlank) || !verified.matches(identity)
        ) return Authorization.ContextInvalidated
        if (identity.permissions.isEmpty() || "dispatch.read" !in identity.permissions) {
            return Authorization.PermissionDenied
        }
        if (intent?.requestsDriverChange == true && "dispatch.assign" !in identity.permissions) {
            return Authorization.PermissionDenied
        }
        if (intent?.requestsScheduleChange == true && "dispatch.schedule" !in identity.permissions) {
            return Authorization.PermissionDenied
        }
        return Authorization.Current(lease)
    }

    private suspend fun isCurrent(
        context: DispatchAuthorityContext,
        originalLease: AccessTokenLease
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(originalLease.epoch)
        ) return false
        val identity = context.identity ?: return false
        val verified = sessions.verifiedSession.value ?: return false
        return verified.matches(identity) && "dispatch.read" in identity.permissions
    }

    private suspend fun authorityDrift(context: DispatchAuthorityContext) = when {
        sessions.sessionState.value != SessionState.Active -> DispatchPlanChangeGatewayResult.SessionInvalidated
        sessions.verifiedSession.value?.let { current ->
            context.identity?.let { current.matches(it) } == true
        } == true -> DispatchPlanChangeGatewayResult.SessionInvalidated
        else -> DispatchPlanChangeGatewayResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(expected: DispatchAuthorityIdentity): Boolean =
        hasAuthorizedContext && userId == expected.userId && tenantId == expected.tenantId &&
            workspaceId == expected.workspaceId && membershipId == expected.membershipId &&
            permissions == expected.permissions

    private fun DispatchReadiness.matches(expected: DispatchReadiness): Boolean =
        subjectKind == expected.subjectKind && fulfillmentId == expected.fulfillmentId &&
            fulfillmentVersion == expected.fulfillmentVersion &&
            physicalAllocationId == expected.physicalAllocationId &&
            physicalAllocationVersion == expected.physicalAllocationVersion &&
            ready == expected.ready && fulfillmentStatus == expected.fulfillmentStatus

    private fun DispatchDriverAssignmentProjection.toFeature() = PreparedFulfillmentDriverAssignment(
        id = id,
        fulfillmentId = fulfillmentId,
        fulfillmentVersion = fulfillmentVersion,
        physicalAllocationId = physicalAllocationId,
        physicalAllocationVersion = physicalAllocationVersion,
        responsibleMembershipId = responsibleMembershipId,
        responsibleDisplayName = responsibleDisplayName,
        assignedAt = assignedAt,
        deliveryId = deliveryId,
        plannedDispatchAt = plannedDispatchAt,
        current = current
    )

    private fun DispatchReadinessProjection.toFeature() =
        DispatchReadinessProjectionAdapter.toFeature(this)

    private fun DispatchAuthorityContext.scopeIdentity() = identity?.let {
        DispatchPlanChangeScopeIdentity(it.userId, it.tenantId, it.workspaceId, it.membershipId)
    }

    private fun DispatchReadinessNetworkOutcome.toReadFailure() = when (this) {
        DispatchReadinessNetworkOutcome.NetworkUnavailable -> DispatchPlanChangeGatewayResult.NetworkUnavailable
        DispatchReadinessNetworkOutcome.ServiceUnavailable -> DispatchPlanChangeGatewayResult.ServiceUnavailable
        DispatchReadinessNetworkOutcome.PermissionDenied -> DispatchPlanChangeGatewayResult.PermissionDenied
        DispatchReadinessNetworkOutcome.ContextInvalidated -> DispatchPlanChangeGatewayResult.ContextInvalidated
        DispatchReadinessNetworkOutcome.SessionInvalidated -> DispatchPlanChangeGatewayResult.SessionInvalidated
        is DispatchReadinessNetworkOutcome.ListResult,
        is DispatchReadinessNetworkOutcome.Detail -> DispatchPlanChangeGatewayResult.ServiceUnavailable
    }

    private fun DispatchAssignmentNetworkOutcome.toAssignmentFailure(mutation: Boolean) = when (this) {
        DispatchAssignmentNetworkOutcome.NetworkUnavailable -> DispatchPlanChangeGatewayResult.NetworkUnavailable
        DispatchAssignmentNetworkOutcome.UnknownOutcome -> DispatchPlanChangeGatewayResult.UnknownOutcome
        DispatchAssignmentNetworkOutcome.ServiceUnavailable -> DispatchPlanChangeGatewayResult.ServiceUnavailable
        DispatchAssignmentNetworkOutcome.PermissionDenied -> DispatchPlanChangeGatewayResult.PermissionDenied
        DispatchAssignmentNetworkOutcome.ContextInvalidated -> DispatchPlanChangeGatewayResult.ContextInvalidated
        DispatchAssignmentNetworkOutcome.SessionInvalidated -> DispatchPlanChangeGatewayResult.SessionInvalidated
        DispatchAssignmentNetworkOutcome.Stale -> DispatchPlanChangeGatewayResult.Stale
        DispatchAssignmentNetworkOutcome.Conflict -> DispatchPlanChangeGatewayResult.Conflict
        is DispatchAssignmentNetworkOutcome.Candidates,
        is DispatchAssignmentNetworkOutcome.Current,
        is DispatchAssignmentNetworkOutcome.Assigned -> if (mutation) {
            DispatchPlanChangeGatewayResult.UnknownOutcome
        } else DispatchPlanChangeGatewayResult.ServiceUnavailable
    }

    private fun DispatchPlanChangeNetworkOutcome.toPlanFailure(mutation: Boolean) = when (this) {
        DispatchPlanChangeNetworkOutcome.NetworkUnavailable -> DispatchPlanChangeGatewayResult.NetworkUnavailable
        DispatchPlanChangeNetworkOutcome.UnknownOutcome -> DispatchPlanChangeGatewayResult.UnknownOutcome
        DispatchPlanChangeNetworkOutcome.ServiceUnavailable -> DispatchPlanChangeGatewayResult.ServiceUnavailable
        DispatchPlanChangeNetworkOutcome.PermissionDenied -> DispatchPlanChangeGatewayResult.PermissionDenied
        DispatchPlanChangeNetworkOutcome.ContextInvalidated -> DispatchPlanChangeGatewayResult.ContextInvalidated
        DispatchPlanChangeNetworkOutcome.SessionInvalidated -> DispatchPlanChangeGatewayResult.SessionInvalidated
        DispatchPlanChangeNetworkOutcome.Stale -> DispatchPlanChangeGatewayResult.Stale
        DispatchPlanChangeNetworkOutcome.Conflict -> DispatchPlanChangeGatewayResult.Conflict
        is DispatchPlanChangeNetworkOutcome.History,
        is DispatchPlanChangeNetworkOutcome.Changed -> if (mutation) {
            DispatchPlanChangeGatewayResult.UnknownOutcome
        } else DispatchPlanChangeGatewayResult.ServiceUnavailable
    }

    private fun Authorization.toResult() = when (this) {
        Authorization.SessionInvalidated -> DispatchPlanChangeGatewayResult.SessionInvalidated
        Authorization.ContextInvalidated -> DispatchPlanChangeGatewayResult.ContextInvalidated
        Authorization.PermissionDenied -> DispatchPlanChangeGatewayResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }
}

internal class DispatchPlanChangeViewModelFactory @Inject constructor(
    private val gateway: OperationsDispatchPlanChangeGateway,
    private val metadata: DispatchPlanChangeMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DispatchPlanChangeViewModel::class.java))
        return DispatchPlanChangeViewModel(gateway, metadata) as T
    }
}
