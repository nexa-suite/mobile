package com.nexa.mobile.operations.data

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.DispatchAssignmentNetworkOutcome as AssignmentOutcome
import com.nexa.mobile.operations.core.network.DispatchDriverAssignmentProjection as DriverAssignmentProjection
import com.nexa.mobile.operations.core.network.DispatchDriverAssignmentRequest as DriverAssignmentRequest
import com.nexa.mobile.operations.core.network.DispatchReadinessNetworkOutcome as ReadinessOutcome
import com.nexa.mobile.operations.core.network.DispatchReadinessProjection as ReadinessProjection
import com.nexa.mobile.operations.core.network.NexaDispatchAssignmentGateway
import com.nexa.mobile.operations.core.network.NexaDispatchReadinessGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.dispatch.application.DispatchAssignmentGateway
import com.nexa.mobile.operations.feature.dispatch.application.DispatchReadinessGateway
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAssignmentGatewayResult as AssignmentGatewayResult
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAssignmentIntent
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAssignmentScopeIdentity as AssignmentScopeIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAssignmentSnapshot
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchDriverCandidate
import com.nexa.mobile.operations.feature.dispatch.model.DispatchReadiness
import com.nexa.mobile.operations.feature.dispatch.model.DispatchReadinessGatewayResult as ReadinessGatewayResult
import com.nexa.mobile.operations.feature.dispatch.model.DispatchReadinessLine
import com.nexa.mobile.operations.feature.dispatch.model.PreparedFulfillmentDriverAssignment
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DispatchReadinessGatewayBindings {
    @Provides
    @Singleton
    fun dispatchReadinessGateway(
        protectedCalls: ProtectedCallExecutor
    ): NexaDispatchReadinessGateway = NexaDispatchReadinessGateway(protectedCalls)

    @Provides
    @Singleton
    fun dispatchAssignmentNetworkGateway(
        protectedCalls: ProtectedCallExecutor
    ): NexaDispatchAssignmentGateway = NexaDispatchAssignmentGateway(protectedCalls)
}

/** Current dispatch assignment gateway, fenced to the verified context and session lease. */
@Singleton
class OperationsDispatchAssignmentGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val assignments: NexaDispatchAssignmentGateway,
    private val readiness: NexaDispatchReadinessGateway
) : DispatchAssignmentGateway {
    override suspend fun load(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): AssignmentGatewayResult {
        val authorization = authorize(context, requireAssignmentPermission = false)
        if (authorization !is Authorization.Current) return authorization.toResult()

        val currentReadiness = when (val result = readiness.detail(fulfillmentId)) {
            is ReadinessOutcome.Detail -> toFeature(result.item)
            else -> return result.toReadFailure()
        }
        if (currentReadiness.fulfillmentId != fulfillmentId) {
            return AssignmentGatewayResult.ServiceUnavailable
        }
        val candidates = when (val result = assignments.candidates()) {
            is AssignmentOutcome.Candidates -> result.items.map {
                DispatchDriverCandidate(it.membershipId, it.email, it.displayName)
            }

            else -> return result.toAssignmentFailure(mutation = false)
        }
        val currentAssignment = when (val result = assignments.current(fulfillmentId)) {
            is AssignmentOutcome.Current -> result.item?.toFeature()
            else -> return result.toAssignmentFailure(mutation = false)
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)
        return AssignmentGatewayResult.Snapshot(
            DispatchAssignmentSnapshot(currentReadiness, candidates, currentAssignment)
        )
    }

    override suspend fun assign(
        fulfillment: DispatchReadiness,
        responsibleMembershipId: String,
        context: DispatchAuthorityContext,
        idempotencyKey: String
    ): AssignmentGatewayResult {
        val authorization = authorize(context, requireAssignmentPermission = true)
        if (authorization !is Authorization.Current) return authorization.toResult()
        val current = when (val result = readiness.detail(fulfillment.fulfillmentId)) {
            is ReadinessOutcome.Detail -> toFeature(result.item)
            else -> return result.toReadFailure()
        }
        if (!current.matches(fulfillment)) return AssignmentGatewayResult.Stale
        if (!current.ready || current.fulfillmentStatus != "READY_FOR_DISPATCH") {
            return AssignmentGatewayResult.NotReady
        }
        val candidates = when (val result = assignments.candidates()) {
            is AssignmentOutcome.Candidates -> result.items
            else -> return result.toAssignmentFailure(mutation = false)
        }
        if (candidates.none { it.membershipId == responsibleMembershipId }) {
            return AssignmentGatewayResult.Conflict
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)

        val result = assignments.assign(
            DriverAssignmentRequest(
                fulfillmentId = current.fulfillmentId,
                expectedFulfillmentVersion = current.fulfillmentVersion,
                physicalAllocationId = current.physicalAllocationId,
                physicalAllocationVersion = current.physicalAllocationVersion,
                responsibleMembershipId = responsibleMembershipId
            ),
            idempotencyKey
        )
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)
        return when (result) {
            is AssignmentOutcome.Assigned ->
                AssignmentGatewayResult.Assigned(result.item.toFeature())

            else -> result.toAssignmentFailure(mutation = true)
        }
    }

    override suspend fun replay(
        intent: DispatchAssignmentIntent,
        context: DispatchAuthorityContext
    ): AssignmentGatewayResult {
        val authorization = authorize(context, requireAssignmentPermission = true)
        if (authorization !is Authorization.Current) return authorization.toResult()
        val identity = context.identity ?: return AssignmentGatewayResult.ContextInvalidated
        if (intent.scope != AssignmentScopeIdentity(
                identity.userId,
                identity.tenantId,
                identity.workspaceId,
                identity.membershipId
            )
        ) {
            return AssignmentGatewayResult.ContextInvalidated
        }

        val current = when (val result = readiness.detail(intent.fulfillmentId)) {
            is ReadinessOutcome.Detail -> toFeature(result.item)
            else -> return result.toReadFailure()
        }
        if (current.fulfillmentId != intent.fulfillmentId ||
            current.physicalAllocationId != intent.physicalAllocationId ||
            current.physicalAllocationVersion != intent.physicalAllocationVersion
        ) {
            return AssignmentGatewayResult.Stale
        }
        val candidates = when (val result = assignments.candidates()) {
            is AssignmentOutcome.Candidates -> result.items
            else -> return result.toAssignmentFailure(mutation = false)
        }
        if (candidates.none { it.membershipId == intent.responsibleMembershipId }) {
            return AssignmentGatewayResult.Conflict
        }

        when (current.fulfillmentVersion) {
            intent.expectedFulfillmentVersion -> {
                if (!current.ready || current.fulfillmentStatus != "READY_FOR_DISPATCH") {
                    return AssignmentGatewayResult.NotReady
                }
                when (val result = assignments.current(intent.fulfillmentId)) {
                    is AssignmentOutcome.Current -> if (result.item != null) {
                        return AssignmentGatewayResult.Stale
                    }

                    else -> return result.toAssignmentFailure(mutation = false)
                }
            }

            intent.expectedFulfillmentVersion + 1 -> {
                val assigned = when (val result = assignments.current(intent.fulfillmentId)) {
                    is AssignmentOutcome.Current -> result.item
                    else -> return result.toAssignmentFailure(mutation = false)
                } ?: return AssignmentGatewayResult.Stale
                if (assigned.responsibleMembershipId != intent.responsibleMembershipId ||
                    assigned.physicalAllocationId != intent.physicalAllocationId ||
                    assigned.physicalAllocationVersion != intent.physicalAllocationVersion ||
                    assigned.fulfillmentVersion != current.fulfillmentVersion
                ) {
                    return AssignmentGatewayResult.Stale
                }
            }

            else -> return AssignmentGatewayResult.Stale
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)

        val result = assignments.assign(
            DriverAssignmentRequest(
                fulfillmentId = intent.fulfillmentId,
                expectedFulfillmentVersion = intent.expectedFulfillmentVersion,
                physicalAllocationId = intent.physicalAllocationId,
                physicalAllocationVersion = intent.physicalAllocationVersion,
                responsibleMembershipId = intent.responsibleMembershipId
            ),
            intent.idempotencyKey
        )
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)
        return when (result) {
            is AssignmentOutcome.Assigned ->
                AssignmentGatewayResult.Assigned(result.item.toFeature())

            else -> result.toAssignmentFailure(mutation = true)
        }
    }

    private suspend fun authorize(
        context: DispatchAuthorityContext,
        requireAssignmentPermission: Boolean
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
        return when {
            identity.permissions.isEmpty() -> Authorization.ContextInvalidated

            DISPATCH_READ_PERMISSION !in identity.permissions ||
                LOGISTICS_READ_PERMISSION !in identity.permissions ->
                Authorization.PermissionDenied

            requireAssignmentPermission &&
                identity.permissions.none { it in DISPATCH_ASSIGN_PERMISSIONS } ->
                Authorization.PermissionDenied

            else -> Authorization.Current(lease)
        }
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
        val current = sessions.verifiedSession.value ?: return false
        return current.matches(identity) && DISPATCH_READ_PERMISSION in identity.permissions &&
            LOGISTICS_READ_PERMISSION in identity.permissions
    }

    private suspend fun authorityDrift(context: DispatchAuthorityContext) = when {
        sessions.sessionState.value != SessionState.Active ->
            AssignmentGatewayResult.SessionInvalidated

        sessions.verifiedSession.value?.let { verified ->
            context.identity?.let { expected -> verified.matches(expected) } == true
        } == true -> AssignmentGatewayResult.SessionInvalidated

        else -> AssignmentGatewayResult.ContextInvalidated
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
            ready == expected.ready &&
            fulfillmentStatus == expected.fulfillmentStatus

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

    private fun toFeature(item: ReadinessProjection) =
        DispatchReadinessProjectionAdapter.toFeature(item)

    private fun ReadinessOutcome.toReadFailure(): AssignmentGatewayResult = when (this) {
        ReadinessOutcome.NetworkUnavailable ->
            AssignmentGatewayResult.NetworkUnavailable

        ReadinessOutcome.ServiceUnavailable ->
            AssignmentGatewayResult.ServiceUnavailable

        ReadinessOutcome.PermissionDenied ->
            AssignmentGatewayResult.PermissionDenied

        ReadinessOutcome.ContextInvalidated ->
            AssignmentGatewayResult.ContextInvalidated

        ReadinessOutcome.SessionInvalidated ->
            AssignmentGatewayResult.SessionInvalidated

        is ReadinessOutcome.ListResult,
        is ReadinessOutcome.Detail ->
            AssignmentGatewayResult.ServiceUnavailable
    }

    private fun AssignmentOutcome.toAssignmentFailure(mutation: Boolean): AssignmentGatewayResult =
        when (this) {
            AssignmentOutcome.NetworkUnavailable ->
                AssignmentGatewayResult.NetworkUnavailable

            AssignmentOutcome.UnknownOutcome ->
                AssignmentGatewayResult.UnknownOutcome

            AssignmentOutcome.ServiceUnavailable ->
                AssignmentGatewayResult.ServiceUnavailable

            AssignmentOutcome.PermissionDenied ->
                AssignmentGatewayResult.PermissionDenied

            AssignmentOutcome.ContextInvalidated ->
                AssignmentGatewayResult.ContextInvalidated

            AssignmentOutcome.SessionInvalidated ->
                AssignmentGatewayResult.SessionInvalidated

            AssignmentOutcome.Stale -> AssignmentGatewayResult.Stale

            AssignmentOutcome.Conflict -> AssignmentGatewayResult.Conflict

            is AssignmentOutcome.Candidates,
            is AssignmentOutcome.Current,
            is AssignmentOutcome.Assigned -> if (mutation) {
                AssignmentGatewayResult.UnknownOutcome
            } else {
                AssignmentGatewayResult.ServiceUnavailable
            }
        }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private fun Authorization.toResult(): AssignmentGatewayResult = when (this) {
        Authorization.SessionInvalidated -> AssignmentGatewayResult.SessionInvalidated
        Authorization.ContextInvalidated -> AssignmentGatewayResult.ContextInvalidated
        Authorization.PermissionDenied -> AssignmentGatewayResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private companion object {
        const val DISPATCH_READ_PERMISSION = "dispatch.read"
        const val LOGISTICS_READ_PERMISSION = "logistics.read"
        val DISPATCH_ASSIGN_PERMISSIONS = setOf("dispatch.assign", "logistics:write")
    }
}

/** Fences read results to the captured scope, permission snapshot and session lease. */
@Singleton
class OperationsDispatchReadinessGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val readiness: NexaDispatchReadinessGateway
) : DispatchReadinessGateway {
    override suspend fun list(context: DispatchAuthorityContext): ReadinessGatewayResult {
        val authorization = authorize(context)
        if (authorization !is Authorization.Current) return authorization.toResult()
        val result = readiness.list().toFeatureResult()
        return if (isCurrent(context, authorization.lease)) result else authorityDrift(context)
    }

    override suspend fun detail(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): ReadinessGatewayResult {
        val authorization = authorize(context)
        if (authorization !is Authorization.Current) return authorization.toResult()
        val result = readiness.detail(fulfillmentId).toFeatureResult()
        return if (isCurrent(context, authorization.lease)) result else authorityDrift(context)
    }

    private suspend fun authorize(context: DispatchAuthorityContext): Authorization {
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
        return when {
            identity.permissions.isEmpty() -> Authorization.ContextInvalidated
            DISPATCH_READ_PERMISSION !in identity.permissions -> Authorization.PermissionDenied
            else -> Authorization.Current(lease)
        }
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
        return sessions.verifiedSession.value?.matches(identity) == true &&
            DISPATCH_READ_PERMISSION in identity.permissions
    }

    private suspend fun authorityDrift(context: DispatchAuthorityContext) = when {
        sessions.sessionState.value != SessionState.Active ->
            ReadinessGatewayResult.SessionInvalidated

        sessions.verifiedSession.value?.let { verified ->
            context.identity?.let { expected -> verified.matches(expected) } == true
        } == true ->
            ReadinessGatewayResult.SessionInvalidated

        else -> ReadinessGatewayResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(expected: DispatchAuthorityIdentity): Boolean =
        hasAuthorizedContext && userId == expected.userId && tenantId == expected.tenantId &&
            workspaceId == expected.workspaceId && membershipId == expected.membershipId &&
            permissions == expected.permissions

    private fun ReadinessOutcome.toFeatureResult(): ReadinessGatewayResult = when (this) {
        is ReadinessOutcome.ListResult ->
            ReadinessGatewayResult.ListResult(
                items = items.map(DispatchReadinessProjectionAdapter::toFeature),
                asOf = asOf
            )

        is ReadinessOutcome.Detail -> ReadinessGatewayResult.Detail(
            DispatchReadinessProjectionAdapter.toFeature(item)
        )

        ReadinessOutcome.NetworkUnavailable ->
            ReadinessGatewayResult.NetworkUnavailable

        ReadinessOutcome.ServiceUnavailable ->
            ReadinessGatewayResult.ServiceUnavailable

        ReadinessOutcome.PermissionDenied ->
            ReadinessGatewayResult.PermissionDenied

        ReadinessOutcome.ContextInvalidated ->
            ReadinessGatewayResult.ContextInvalidated

        ReadinessOutcome.SessionInvalidated ->
            ReadinessGatewayResult.SessionInvalidated
    }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private fun Authorization.toResult(): ReadinessGatewayResult = when (this) {
        Authorization.SessionInvalidated -> ReadinessGatewayResult.SessionInvalidated
        Authorization.ContextInvalidated -> ReadinessGatewayResult.ContextInvalidated
        Authorization.PermissionDenied -> ReadinessGatewayResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private companion object {
        const val DISPATCH_READ_PERMISSION = "dispatch.read"
    }
}

object DispatchReadinessProjectionAdapter {
    fun toFeature(item: ReadinessProjection) = DispatchReadiness(
        subjectKind = item.subjectKind,
        fulfillmentId = item.fulfillmentId,
        fulfillmentVersion = item.fulfillmentVersion,
        fulfillmentStatus = item.fulfillmentStatus,
        physicalAllocationId = item.physicalAllocationId,
        physicalAllocationStatus = item.physicalAllocationStatus,
        physicalAllocationVersion = item.physicalAllocationVersion,
        deliveryId = item.deliveryId,
        deliveryStatus = item.deliveryStatus,
        deliveryVersion = item.deliveryVersion,
        windowStart = item.windowStart,
        windowEnd = item.windowEnd,
        windowSource = item.windowSource,
        allocationComplete = item.allocationComplete,
        pickingComplete = item.pickingComplete,
        pickingEvidenceComplete = item.pickingEvidenceComplete,
        ready = item.ready,
        reasons = item.reasons,
        lines = item.lines.map {
            DispatchReadinessLine(
                fulfillmentLineId = it.fulfillmentLineId,
                skuId = it.skuId,
                catalogItemId = it.catalogItemId,
                allocatedQuantity = it.allocatedQuantity,
                physicallyAllocatedQuantity = it.physicallyAllocatedQuantity,
                pickedQuantity = it.pickedQuantity,
                evidencedPickedQuantity = it.evidencedPickedQuantity,
                allocationComplete = it.allocationComplete,
                pickingComplete = it.pickingComplete,
                evidenceComplete = it.evidenceComplete
            )
        },
        asOf = item.asOf
    )
}
