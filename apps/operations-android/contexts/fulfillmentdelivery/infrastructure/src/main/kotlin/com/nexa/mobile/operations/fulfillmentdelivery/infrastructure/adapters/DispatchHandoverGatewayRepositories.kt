package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchHandoverGatewayResult as HandoverGatewayResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchHandoverEvidence
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchHandoverReceipt
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchHandoverSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsAllocation as OutgoingGoodsAllocation
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsCheck
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsCheckLine as OutgoingGoodsCheckLine
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsDiscrepancy as OutgoingGoodsDiscrepancy
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsLine
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchReadiness
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.PreparedFulfillmentDriverAssignment
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.JsonDispatchRequestBodyCodec
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.DispatchAssignmentNetworkOutcome as AssignmentOutcome
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.DispatchDriverAssignmentProjection as DriverAssignmentProjection
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.DispatchReadinessNetworkOutcome as ReadinessOutcome
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.FulfillmentDispatchNetworkOutcome as DispatchOutcome
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.FulfillmentDispatchProjection as DispatchProjection
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.FulfillmentHandoffEvidenceNetworkOutcome as HandoffEvidenceOutcome
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.FulfillmentHandoffEvidenceProjection as HandoffEvidenceProjection
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.NexaDispatchAssignmentGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.NexaDispatchReadinessGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.NexaFulfillmentDispatchGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.NexaOutgoingGoodsCheckGateway
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.OutgoingGoodsCheckNetworkOutcome as OutgoingGoodsCheckOutcome
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.OutgoingGoodsCheckProjection
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.PhysicalAllocationProjection
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DispatchHandoverGatewayBindings {
    @Provides
    @Singleton
    fun fulfillmentDispatchGateway(
        protectedCalls: ProtectedCallExecutor
    ): NexaFulfillmentDispatchGateway = NexaFulfillmentDispatchGateway(protectedCalls)

    @Provides
    @Singleton
    fun dispatchHandoverGateway(
        operations: OperationsDispatchHandoverGateway
    ): DispatchHandoverGateway = operations
}

/** Evaluates current BC-06 facts before consuming inventory and recording a real handover. */
@Singleton
class OperationsDispatchHandoverGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val readiness: NexaDispatchReadinessGateway,
    private val assignments: NexaDispatchAssignmentGateway,
    private val outgoingGoods: NexaOutgoingGoodsCheckGateway,
    private val dispatches: NexaFulfillmentDispatchGateway,
    private val requestBodyCodec: JsonDispatchRequestBodyCodec
) : DispatchHandoverGateway {
    override suspend fun load(
        fulfillment: DispatchReadiness,
        context: DispatchAuthorityContext
    ): HandoverGatewayResult {
        val authorization = authorize(context)
        if (authorization !is Authorization.Current) return authorization.toResult()

        when (val current = dispatches.current(fulfillment.fulfillmentId)) {
            is DispatchOutcome.Current -> if (current.fulfillment.status ==
                HANDED_OVER
            ) {
                val value = current.fulfillment
                val receipt = value.toHandoverReceipt()
                return when (
                    val evidence = dispatches.currentHandoffEvidence(
                        fulfillment.fulfillmentId
                    )
                ) {
                    is HandoffEvidenceOutcome.Evidence -> {
                        if (evidence.value.deliveryId != receipt.deliveryId ||
                            evidence.value.fulfillmentId != fulfillment.fulfillmentId
                        ) {
                            HandoverGatewayResult.ServiceUnavailable
                        } else {
                            HandoverGatewayResult.AlreadyCompleted(
                                receipt.copy(evidence = evidence.value.toFeature())
                            )
                        }
                    }

                    else -> evidence.toHandoverFailure()
                }
            }

            else -> if (current !is DispatchOutcome.Current) {
                return current.toHandoverFailure()
            }
        }

        val currentReadiness = when (val result = readiness.detail(fulfillment.fulfillmentId)) {
            is ReadinessOutcome.Detail ->
                DispatchReadinessProjectionAdapter.toFeature(
                    result.item
                )

            else -> return result.toHandoverFailure()
        }
        if (!currentReadiness.fulfillmentId.equals(fulfillment.fulfillmentId, ignoreCase = true)) {
            return HandoverGatewayResult.Stale
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)

        val physical = when (val result = outgoingGoods.snapshot(fulfillment.fulfillmentId)) {
            is OutgoingGoodsCheckOutcome.Snapshot -> result
            else -> return result.toHandoverFailure()
        }
        if (!physical.allocation.id.equals(
                currentReadiness.physicalAllocationId,
                ignoreCase = true
            ) ||
            physical.allocation.version != currentReadiness.physicalAllocationVersion
        ) {
            return HandoverGatewayResult.Stale
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)

        val assignment = when (val result = assignments.current(fulfillment.fulfillmentId)) {
            is AssignmentOutcome.Current -> result.item?.toFeature()
            else -> return result.toHandoverFailure()
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)

        return HandoverGatewayResult.Snapshot(
            DispatchHandoverSnapshot(
                readiness = currentReadiness,
                allocation = physical.allocation.toFeature(),
                outgoingCheck = physical.currentCheck?.toFeature(),
                driverAssignment = assignment
            )
        )
    }

    override suspend fun dispatch(
        fulfillment: DispatchReadiness,
        snapshot: DispatchHandoverSnapshot?,
        command: DispatchHandoverCommand,
        context: DispatchAuthorityContext
    ): HandoverGatewayResult {
        val authorization = authorize(context)
        if (authorization !is Authorization.Current) return authorization.toResult()
        if (command.fulfillmentId != fulfillment.fulfillmentId ||
            !requestBodyCodec.isValid(command)
        ) {
            return HandoverGatewayResult.Stale
        }

        if (snapshot != null) {
            if (command.expectedFulfillmentVersion != snapshot.readiness.fulfillmentVersion ||
                command.physicalAllocationId != snapshot.allocation.id ||
                command.physicalAllocationVersion != snapshot.allocation.version ||
                command.driverAssignmentId != snapshot.driverAssignment?.id ||
                command.driverAssignmentVersion != snapshot.driverAssignment?.fulfillmentVersion ||
                command.outgoingGoodsCheckId != snapshot.outgoingCheck?.id
            ) {
                return HandoverGatewayResult.Stale
            }
            val fresh = when (val result = load(snapshot.readiness, context)) {
                is HandoverGatewayResult.Snapshot -> result.value
                is HandoverGatewayResult.AlreadyCompleted -> return result
                else -> return result
            }
            if (!fresh.canConfirm ||
                fresh.readiness.fulfillmentVersion != command.expectedFulfillmentVersion ||
                fresh.allocation.id != command.physicalAllocationId ||
                fresh.allocation.version != command.physicalAllocationVersion ||
                fresh.driverAssignment?.id != command.driverAssignmentId ||
                fresh.driverAssignment?.fulfillmentVersion != command.driverAssignmentVersion ||
                fresh.outgoingCheck?.id != command.outgoingGoodsCheckId
            ) {
                return HandoverGatewayResult.Stale
            }
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)

        return when (
            val result = dispatches.dispatch(
                command.fulfillmentId,
                command.expectedFulfillmentVersion,
                command.idempotencyKey,
                command.exactRequestBody
            )
        ) {
            is DispatchOutcome.Dispatched -> {
                if (!isCurrent(context, authorization.lease)) {
                    authorityDrift(context)
                } else {
                    when (val evidence = dispatches.currentHandoffEvidence(command.fulfillmentId)) {
                        is HandoffEvidenceOutcome.Evidence -> {
                            val fact = evidence.value
                            val currentMembershipId = context.identity?.membershipId
                            if (currentMembershipId == null || !fact.correlatesToDispatch(
                                    command,
                                    result.fulfillment.deliveryId,
                                    currentMembershipId
                                )
                            ) {
                                HandoverGatewayResult.UnknownOutcome
                            } else if (!isCurrent(context, authorization.lease)) {
                                authorityDrift(context)
                            } else {
                                HandoverGatewayResult.Dispatched(
                                    result.fulfillment.toHandoverReceipt().copy(
                                        evidence = fact.toFeature()
                                    )
                                )
                            }
                        }

                        else -> evidence.toHandoverFailure()
                    }
                }
            }

            is DispatchOutcome.Current -> if (result.fulfillment.status ==
                HANDED_OVER
            ) {
                HandoverGatewayResult.AlreadyCompleted(
                    result.fulfillment.toHandoverReceipt()
                )
            } else {
                HandoverGatewayResult.ServiceUnavailable
            }

            else -> result.toHandoverFailure()
        }
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
        return if (MANAGE_PERMISSION in identity.permissions &&
            READ_PERMISSION in identity.permissions
        ) {
            Authorization.Current(lease)
        } else {
            Authorization.PermissionDenied
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
            MANAGE_PERMISSION in identity.permissions && READ_PERMISSION in identity.permissions
    }

    private suspend fun authorityDrift(context: DispatchAuthorityContext) = when {
        sessions.sessionState.value != SessionState.Active ->
            HandoverGatewayResult.SessionInvalidated

        sessions.verifiedSession.value?.let { verified ->
            context.identity?.let { expected -> verified.matches(expected) } == true
        } == true -> HandoverGatewayResult.SessionInvalidated

        else -> HandoverGatewayResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(expected: DispatchAuthorityIdentity): Boolean =
        hasAuthorizedContext && userId == expected.userId && tenantId == expected.tenantId &&
            workspaceId == expected.workspaceId && membershipId == expected.membershipId &&
            permissions == expected.permissions

    private fun DriverAssignmentProjection.toFeature() = PreparedFulfillmentDriverAssignment(
        id = id,
        fulfillmentId = fulfillmentId,
        fulfillmentVersion = fulfillmentVersion,
        physicalAllocationId = physicalAllocationId,
        physicalAllocationVersion = physicalAllocationVersion,
        responsibleMembershipId = responsibleMembershipId,
        responsibleDisplayName = responsibleDisplayName,
        assignedAt = assignedAt,
        deliveryId = deliveryId
    )

    private fun PhysicalAllocationProjection.toFeature() = OutgoingGoodsAllocation(
        id = id,
        status = status,
        version = version,
        asOf = asOf,
        lines = lines.map {
            DispatchOutgoingGoodsLine(
                physicalAllocationLineId = it.id,
                skuId = it.skuId,
                catalogItemId = it.catalogItemId,
                expectedLotId = it.lotId,
                allocatedQuantity = it.quantity,
                releasedQuantity = it.releasedQuantity,
                consumedQuantity = it.consumedQuantity,
                remainingQuantity = it.remainingQuantity,
                unit = it.unit
            )
        }
    )

    private fun OutgoingGoodsCheckProjection.toFeature() = DispatchOutgoingGoodsCheck(
        id = id,
        fulfillmentId = fulfillmentId,
        fulfillmentVersion = fulfillmentVersion,
        physicalAllocationId = physicalAllocationId,
        physicalAllocationVersion = physicalAllocationVersion,
        matches = matches,
        current = current,
        openDiscrepancy = openDiscrepancy,
        checkedAt = checkedAt,
        lines = lines.map {
            OutgoingGoodsCheckLine(
                it.physicalAllocationLineId,
                it.expectedLotId,
                it.observedLotId,
                it.expectedQuantity,
                it.observedQuantity,
                it.unit,
                it.matches
            )
        },
        replayed = replayed,
        discrepancy = discrepancy?.let { detail ->
            OutgoingGoodsDiscrepancy(
                id = detail.id,
                fulfillmentVersion = detail.fulfillmentVersion,
                physicalAllocationId = detail.physicalAllocationId,
                physicalAllocationVersion = detail.physicalAllocationVersion,
                checkedByMembershipId = detail.checkedByMembershipId,
                checkedAt = detail.checkedAt,
                lines = detail.lines.map {
                    OutgoingGoodsCheckLine(
                        it.physicalAllocationLineId,
                        it.expectedLotId,
                        it.observedLotId,
                        it.expectedQuantity,
                        it.observedQuantity,
                        it.unit,
                        it.matches
                    )
                }
            )
        }
    )

    private fun DispatchProjection.toHandoverReceipt() = DispatchHandoverReceipt(
        fulfillmentId = fulfillmentId,
        fulfillmentStatus = status,
        fulfillmentVersion = version,
        deliveryId = requireNotNull(deliveryId),
        deliveryStatus = requireNotNull(deliveryStatus),
        deliveryVersion = requireNotNull(deliveryVersion),
        recordedAt = updatedAt
    )

    private fun HandoffEvidenceProjection.toFeature() = DispatchHandoverEvidence(
        evidenceId = id,
        fulfillmentVersion = fulfillmentVersion,
        deliveryId = deliveryId,
        warehouseActorMembershipId = warehouseActorMembershipId,
        dispatchActorMembershipId = dispatchActorMembershipId,
        driverAssignmentId = driverAssignmentId,
        driverMembershipId = driverMembershipId,
        physicalAllocationId = physicalAllocationId,
        physicalAllocationVersion = physicalAllocationVersion,
        outgoingGoodsCheckId = outgoingGoodsCheckId,
        occurredAt = occurredAt,
        current = current
    )

    private fun ReadinessOutcome.toHandoverFailure(): HandoverGatewayResult = when (this) {
        ReadinessOutcome.NetworkUnavailable ->
            HandoverGatewayResult.NetworkUnavailable

        ReadinessOutcome.ServiceUnavailable ->
            HandoverGatewayResult.ServiceUnavailable

        ReadinessOutcome.PermissionDenied ->
            HandoverGatewayResult.PermissionDenied

        ReadinessOutcome.ContextInvalidated ->
            HandoverGatewayResult.ContextInvalidated

        ReadinessOutcome.SessionInvalidated ->
            HandoverGatewayResult.SessionInvalidated

        is ReadinessOutcome.Detail,
        is ReadinessOutcome.ListResult ->
            HandoverGatewayResult.ServiceUnavailable
    }

    private fun OutgoingGoodsCheckOutcome.toHandoverFailure(): HandoverGatewayResult = when (this) {
        OutgoingGoodsCheckOutcome.NetworkUnavailable ->
            HandoverGatewayResult.NetworkUnavailable

        OutgoingGoodsCheckOutcome.ServiceUnavailable ->
            HandoverGatewayResult.ServiceUnavailable

        OutgoingGoodsCheckOutcome.PermissionDenied ->
            HandoverGatewayResult.PermissionDenied

        OutgoingGoodsCheckOutcome.ContextInvalidated ->
            HandoverGatewayResult.ContextInvalidated

        OutgoingGoodsCheckOutcome.SessionInvalidated ->
            HandoverGatewayResult.SessionInvalidated

        OutgoingGoodsCheckOutcome.Stale -> HandoverGatewayResult.Stale

        OutgoingGoodsCheckOutcome.Conflict -> HandoverGatewayResult.Conflict

        OutgoingGoodsCheckOutcome.UnknownOutcome ->
            HandoverGatewayResult.UnknownOutcome

        is OutgoingGoodsCheckOutcome.Recorded,
        is OutgoingGoodsCheckOutcome.Snapshot,
        is OutgoingGoodsCheckOutcome.Resolved ->
            HandoverGatewayResult.ServiceUnavailable
    }

    private fun AssignmentOutcome.toHandoverFailure(): HandoverGatewayResult = when (this) {
        AssignmentOutcome.NetworkUnavailable ->
            HandoverGatewayResult.NetworkUnavailable

        AssignmentOutcome.ServiceUnavailable ->
            HandoverGatewayResult.ServiceUnavailable

        AssignmentOutcome.PermissionDenied ->
            HandoverGatewayResult.PermissionDenied

        AssignmentOutcome.ContextInvalidated ->
            HandoverGatewayResult.ContextInvalidated

        AssignmentOutcome.SessionInvalidated ->
            HandoverGatewayResult.SessionInvalidated

        AssignmentOutcome.Stale -> HandoverGatewayResult.Stale

        AssignmentOutcome.Conflict -> HandoverGatewayResult.Conflict

        AssignmentOutcome.UnknownOutcome ->
            HandoverGatewayResult.UnknownOutcome

        is AssignmentOutcome.Assigned,
        is AssignmentOutcome.Candidates,
        is AssignmentOutcome.Current ->
            HandoverGatewayResult.ServiceUnavailable
    }

    private fun DispatchOutcome.toHandoverFailure(): HandoverGatewayResult = when (this) {
        DispatchOutcome.NetworkUnavailable ->
            HandoverGatewayResult.NetworkUnavailable

        DispatchOutcome.UnknownOutcome ->
            HandoverGatewayResult.UnknownOutcome

        DispatchOutcome.ServiceUnavailable ->
            HandoverGatewayResult.ServiceUnavailable

        DispatchOutcome.PermissionDenied ->
            HandoverGatewayResult.PermissionDenied

        DispatchOutcome.ContextInvalidated ->
            HandoverGatewayResult.ContextInvalidated

        DispatchOutcome.SessionInvalidated ->
            HandoverGatewayResult.SessionInvalidated

        DispatchOutcome.Stale -> HandoverGatewayResult.Stale

        DispatchOutcome.Conflict -> HandoverGatewayResult.Conflict

        is DispatchOutcome.Current,
        is DispatchOutcome.Dispatched ->
            HandoverGatewayResult.ServiceUnavailable
    }

    private fun HandoffEvidenceOutcome.toHandoverFailure(): HandoverGatewayResult = when (this) {
        HandoffEvidenceOutcome.NetworkUnavailable ->
            HandoverGatewayResult.NetworkUnavailable

        HandoffEvidenceOutcome.ServiceUnavailable ->
            HandoverGatewayResult.ServiceUnavailable

        HandoffEvidenceOutcome.PermissionDenied ->
            HandoverGatewayResult.PermissionDenied

        HandoffEvidenceOutcome.ContextInvalidated ->
            HandoverGatewayResult.ContextInvalidated

        HandoffEvidenceOutcome.SessionInvalidated ->
            HandoverGatewayResult.SessionInvalidated

        is HandoffEvidenceOutcome.Evidence ->
            HandoverGatewayResult.ServiceUnavailable
    }

    private fun Authorization.toResult(): HandoverGatewayResult = when (this) {
        Authorization.SessionInvalidated -> HandoverGatewayResult.SessionInvalidated
        Authorization.ContextInvalidated -> HandoverGatewayResult.ContextInvalidated
        Authorization.PermissionDenied -> HandoverGatewayResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        const val MANAGE_PERMISSION = "fulfillment.manage"
        const val READ_PERMISSION = "dispatch.read"
        const val HANDED_OVER = "HANDED_OVER"
    }
}

internal fun HandoffEvidenceProjection.correlatesToDispatch(
    command: DispatchHandoverCommand,
    expectedDeliveryId: String?,
    expectedDispatchActorMembershipId: String
): Boolean = current && expectedDeliveryId != null &&
    dispatchActorMembershipId?.equals(expectedDispatchActorMembershipId, ignoreCase = true) ==
    true &&
    fulfillmentId.equals(command.fulfillmentId, ignoreCase = true) &&
    deliveryId.equals(expectedDeliveryId, ignoreCase = true) &&
    fulfillmentVersion == command.expectedFulfillmentVersion + 1 &&
    physicalAllocationId.equals(command.physicalAllocationId, ignoreCase = true) &&
    physicalAllocationVersion == command.physicalAllocationVersion &&
    driverAssignmentId.equals(command.driverAssignmentId, ignoreCase = true) &&
    outgoingGoodsCheckId.equals(command.outgoingGoodsCheckId, ignoreCase = true)
