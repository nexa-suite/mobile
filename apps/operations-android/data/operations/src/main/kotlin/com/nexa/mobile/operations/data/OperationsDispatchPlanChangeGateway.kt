package com.nexa.mobile.operations.data

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.DispatchAssignmentNetworkOutcome as AssignmentOutcome
import com.nexa.mobile.operations.core.network.DispatchDriverAssignmentProjection as DriverAssignmentProjection
import com.nexa.mobile.operations.core.network.DispatchPlanChangeNetworkOutcome as PlanChangeOutcome
import com.nexa.mobile.operations.core.network.DispatchPlanChangeRequest
import com.nexa.mobile.operations.core.network.DispatchReadinessNetworkOutcome as ReadinessOutcome
import com.nexa.mobile.operations.core.network.DispatchReadinessProjection as ReadinessProjection
import com.nexa.mobile.operations.core.network.NexaDispatchAssignmentGateway
import com.nexa.mobile.operations.core.network.NexaDispatchReadinessGateway
import com.nexa.mobile.operations.feature.dispatch.application.DispatchPlanChangeGateway
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchDriverCandidate
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeGatewayResult as PlanChangeGatewayResult
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeIntent
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeScopeIdentity as PlanChangeScopeIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchPlanChangeSnapshot
import com.nexa.mobile.operations.feature.dispatch.model.DispatchReadiness
import com.nexa.mobile.operations.feature.dispatch.model.PreparedFulfillmentDriverAssignment
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OperationsDispatchPlanChangeGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val assignments: NexaDispatchAssignmentGateway,
    private val readiness: NexaDispatchReadinessGateway
) : DispatchPlanChangeGateway {
    override suspend fun load(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): PlanChangeGatewayResult {
        val authorization = authorize(context, intent = null)
        if (authorization !is Authorization.Current) return authorization.toResult()
        val currentReadiness = when (val result = readiness.detail(fulfillmentId)) {
            is ReadinessOutcome.Detail -> result.item.toFeature()
            else -> return result.toReadFailure()
        }
        if (currentReadiness.fulfillmentId != fulfillmentId) {
            return PlanChangeGatewayResult.ServiceUnavailable
        }
        val candidates = when (val result = assignments.candidates()) {
            is AssignmentOutcome.Candidates -> result.items.map {
                DispatchDriverCandidate(it.membershipId, it.email, it.displayName)
            }

            else -> return result.toAssignmentFailure(mutation = false)
        }
        val current = when (val result = assignments.current(fulfillmentId)) {
            is AssignmentOutcome.Current -> result.item?.toFeature()
            else -> return result.toAssignmentFailure(mutation = false)
        }
        val history = when (val result = assignments.history(fulfillmentId)) {
            is PlanChangeOutcome.History -> result.items.map { it.toFeature() }
            else -> return result.toPlanFailure(mutation = false)
        }
        if ((current == null && history.isNotEmpty()) ||
            (
                current !=
                    null &&
                    (
                        history.none { it.id == current.id && it.current } ||
                            history.count { it.current } != 1
                        )
                )
        ) {
            return PlanChangeGatewayResult.ServiceUnavailable
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)
        return PlanChangeGatewayResult.Snapshot(
            DispatchPlanChangeSnapshot(currentReadiness, candidates, current, history)
        )
    }

    override suspend fun change(
        readiness: DispatchReadiness,
        assignment: PreparedFulfillmentDriverAssignment,
        intent: DispatchPlanChangeIntent,
        context: DispatchAuthorityContext
    ): PlanChangeGatewayResult {
        val authorization = authorize(context, intent)
        if (authorization !is Authorization.Current) return authorization.toResult()
        if (intent.scope != context.scopeIdentity() ||
            (!intent.requestsDriverChange && !intent.requestsScheduleChange) ||
            intent.fulfillmentId != readiness.fulfillmentId || !assignment.current ||
            assignment.id != intent.expectedAssignmentId ||
            assignment.fulfillmentVersion != intent.expectedAssignmentVersion ||
            readiness.fulfillmentVersion != intent.expectedFulfillmentVersion ||
            readiness.physicalAllocationId != intent.physicalAllocationId ||
            readiness.physicalAllocationVersion != intent.physicalAllocationVersion
        ) {
            return PlanChangeGatewayResult.Stale
        }

        val currentReadiness = when (val result = this.readiness.detail(intent.fulfillmentId)) {
            is ReadinessOutcome.Detail -> result.item.toFeature()
            else -> return result.toReadFailure()
        }
        if (!currentReadiness.matches(readiness)) return PlanChangeGatewayResult.Stale
        if (!currentReadiness.ready || currentReadiness.fulfillmentStatus != "READY_FOR_DISPATCH") {
            return PlanChangeGatewayResult.NotReady
        }
        val currentAssignment = when (val result = assignments.current(intent.fulfillmentId)) {
            is AssignmentOutcome.Current -> result.item?.toFeature()
            else -> return result.toAssignmentFailure(mutation = false)
        } ?: return PlanChangeGatewayResult.Stale
        if (!currentAssignment.current || currentAssignment.id != intent.expectedAssignmentId ||
            currentAssignment.fulfillmentVersion != intent.expectedAssignmentVersion ||
            currentAssignment.physicalAllocationId != intent.physicalAllocationId ||
            currentAssignment.physicalAllocationVersion != intent.physicalAllocationVersion
        ) {
            return PlanChangeGatewayResult.Stale
        }
        eligibleFailure(intent.resultResponsibleMembershipId)?.let { return it }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)
        return send(intent)
    }

    override suspend fun replay(
        intent: DispatchPlanChangeIntent,
        context: DispatchAuthorityContext
    ): PlanChangeGatewayResult {
        val authorization = authorize(context, intent)
        if (authorization !is Authorization.Current) return authorization.toResult()
        if (intent.scope !=
            context.scopeIdentity()
        ) {
            return PlanChangeGatewayResult.ContextInvalidated
        }

        // Current read/grant and eligible-driver checks remain mandatory. Frozen versions are
        // deliberately not compared here: the server resolves an exact idempotent replay first.
        when (val result = readiness.detail(intent.fulfillmentId)) {
            is ReadinessOutcome.Detail -> if (
                result.item.fulfillmentId != intent.fulfillmentId
            ) {
                return PlanChangeGatewayResult.ServiceUnavailable
            }

            else -> return result.toReadFailure()
        }
        eligibleFailure(intent.resultResponsibleMembershipId)?.let { return it }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)
        return send(intent)
    }

    private suspend fun send(intent: DispatchPlanChangeIntent): PlanChangeGatewayResult = when (
        val result = assignments.changePlan(
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
        )
    ) {
        is PlanChangeOutcome.Changed ->
            PlanChangeGatewayResult.Changed(result.item.toFeature())

        else -> result.toPlanFailure(mutation = true)
    }

    private suspend fun eligibleFailure(membershipId: String): PlanChangeGatewayResult? =
        when (val result = assignments.candidates()) {
            is AssignmentOutcome.Candidates -> if (
                result.items.any { it.membershipId.equals(membershipId, ignoreCase = true) }
            ) {
                null
            } else {
                PlanChangeGatewayResult.Conflict
            }

            else -> result.toAssignmentFailure(mutation = false)
        }

    private suspend fun authorize(
        context: DispatchAuthorityContext,
        intent: DispatchPlanChangeIntent?
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
        if (identity.permissions.isEmpty() || "dispatch.read" !in identity.permissions) {
            return Authorization.PermissionDenied
        }
        if (intent?.requestsDriverChange == true && "dispatch.assign" !in identity.permissions) {
            return Authorization.PermissionDenied
        }
        if (intent?.requestsScheduleChange == true &&
            "dispatch.schedule" !in identity.permissions
        ) {
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
        ) {
            return false
        }
        val identity = context.identity ?: return false
        val verified = sessions.verifiedSession.value ?: return false
        return verified.matches(identity) && "dispatch.read" in identity.permissions
    }

    private suspend fun authorityDrift(context: DispatchAuthorityContext) = when {
        sessions.sessionState.value != SessionState.Active ->
            PlanChangeGatewayResult.SessionInvalidated

        sessions.verifiedSession.value?.let { current ->
            context.identity?.let { current.matches(it) } == true
        } == true -> PlanChangeGatewayResult.SessionInvalidated

        else -> PlanChangeGatewayResult.ContextInvalidated
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

    private fun DriverAssignmentProjection.toFeature() = PreparedFulfillmentDriverAssignment(
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

    private fun ReadinessProjection.toFeature() = DispatchReadinessProjectionAdapter.toFeature(this)

    private fun DispatchAuthorityContext.scopeIdentity() = identity?.let {
        PlanChangeScopeIdentity(it.userId, it.tenantId, it.workspaceId, it.membershipId)
    }

    private fun ReadinessOutcome.toReadFailure() = when (this) {
        ReadinessOutcome.NetworkUnavailable ->
            PlanChangeGatewayResult.NetworkUnavailable

        ReadinessOutcome.ServiceUnavailable ->
            PlanChangeGatewayResult.ServiceUnavailable

        ReadinessOutcome.PermissionDenied ->
            PlanChangeGatewayResult.PermissionDenied

        ReadinessOutcome.ContextInvalidated ->
            PlanChangeGatewayResult.ContextInvalidated

        ReadinessOutcome.SessionInvalidated ->
            PlanChangeGatewayResult.SessionInvalidated

        is ReadinessOutcome.ListResult,
        is ReadinessOutcome.Detail ->
            PlanChangeGatewayResult.ServiceUnavailable
    }

    private fun AssignmentOutcome.toAssignmentFailure(mutation: Boolean) = when (this) {
        AssignmentOutcome.NetworkUnavailable ->
            PlanChangeGatewayResult.NetworkUnavailable

        AssignmentOutcome.UnknownOutcome ->
            PlanChangeGatewayResult.UnknownOutcome

        AssignmentOutcome.ServiceUnavailable ->
            PlanChangeGatewayResult.ServiceUnavailable

        AssignmentOutcome.PermissionDenied ->
            PlanChangeGatewayResult.PermissionDenied

        AssignmentOutcome.ContextInvalidated ->
            PlanChangeGatewayResult.ContextInvalidated

        AssignmentOutcome.SessionInvalidated ->
            PlanChangeGatewayResult.SessionInvalidated

        AssignmentOutcome.Stale -> PlanChangeGatewayResult.Stale

        AssignmentOutcome.Conflict -> PlanChangeGatewayResult.Conflict

        is AssignmentOutcome.Candidates,
        is AssignmentOutcome.Current,
        is AssignmentOutcome.Assigned -> if (mutation) {
            PlanChangeGatewayResult.UnknownOutcome
        } else {
            PlanChangeGatewayResult.ServiceUnavailable
        }
    }

    private fun PlanChangeOutcome.toPlanFailure(mutation: Boolean) = when (this) {
        PlanChangeOutcome.NetworkUnavailable ->
            PlanChangeGatewayResult.NetworkUnavailable

        PlanChangeOutcome.UnknownOutcome ->
            PlanChangeGatewayResult.UnknownOutcome

        PlanChangeOutcome.ServiceUnavailable ->
            PlanChangeGatewayResult.ServiceUnavailable

        PlanChangeOutcome.PermissionDenied ->
            PlanChangeGatewayResult.PermissionDenied

        PlanChangeOutcome.ContextInvalidated ->
            PlanChangeGatewayResult.ContextInvalidated

        PlanChangeOutcome.SessionInvalidated ->
            PlanChangeGatewayResult.SessionInvalidated

        PlanChangeOutcome.Stale -> PlanChangeGatewayResult.Stale

        PlanChangeOutcome.Conflict -> PlanChangeGatewayResult.Conflict

        is PlanChangeOutcome.History,
        is PlanChangeOutcome.Changed -> if (mutation) {
            PlanChangeGatewayResult.UnknownOutcome
        } else {
            PlanChangeGatewayResult.ServiceUnavailable
        }
    }

    private fun Authorization.toResult() = when (this) {
        Authorization.SessionInvalidated -> PlanChangeGatewayResult.SessionInvalidated
        Authorization.ContextInvalidated -> PlanChangeGatewayResult.ContextInvalidated
        Authorization.PermissionDenied -> PlanChangeGatewayResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }
}
