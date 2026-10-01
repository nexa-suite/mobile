package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.DispatchAssignmentNetworkOutcome
import com.nexa.mobile.operations.core.network.DispatchDriverAssignmentProjection
import com.nexa.mobile.operations.core.network.DispatchReadinessNetworkOutcome
import com.nexa.mobile.operations.core.network.FulfillmentDispatchNetworkOutcome
import com.nexa.mobile.operations.core.network.FulfillmentHandoffEvidenceNetworkOutcome
import com.nexa.mobile.operations.core.network.NexaDispatchAssignmentGateway
import com.nexa.mobile.operations.core.network.NexaDispatchReadinessGateway
import com.nexa.mobile.operations.core.network.NexaFulfillmentDispatchGateway
import com.nexa.mobile.operations.core.network.NexaOutgoingGoodsCheckGateway
import com.nexa.mobile.operations.core.network.OutgoingGoodsCheckNetworkOutcome
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoverCommand
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoverEvidence
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoverGateway
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoverGatewayResult
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoverMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoverReceipt
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoverSnapshot
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoverViewModel
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsAllocation
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsCheck
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsCheckLine
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsLine
import com.nexa.mobile.operations.feature.dispatch.DispatchReadiness
import com.nexa.mobile.operations.feature.dispatch.PreparedFulfillmentDriverAssignment
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DispatchHandoverGatewayBindings {
    @Provides
    @Singleton
    fun fulfillmentDispatchGateway(protectedCalls: ProtectedCallExecutor): NexaFulfillmentDispatchGateway =
        NexaFulfillmentDispatchGateway(protectedCalls)

    @Provides
    @Singleton
    fun dispatchHandoverGateway(operations: OperationsDispatchHandoverGateway): DispatchHandoverGateway =
        operations
}

/** Evaluates current BC-06 facts before consuming inventory and recording a real handover. */
@Singleton
internal class OperationsDispatchHandoverGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val readiness: NexaDispatchReadinessGateway,
    private val assignments: NexaDispatchAssignmentGateway,
    private val outgoingGoods: NexaOutgoingGoodsCheckGateway,
    private val dispatches: NexaFulfillmentDispatchGateway
) : DispatchHandoverGateway {
    override suspend fun load(
        fulfillment: DispatchReadiness,
        context: DispatchAuthorityContext
    ): DispatchHandoverGatewayResult {
        val authorization = authorize(context)
        if (authorization !is Authorization.Current) return authorization.toResult()

        when (val current = dispatches.current(fulfillment.fulfillmentId)) {
            is FulfillmentDispatchNetworkOutcome.Current -> if (current.fulfillment.status == HANDED_OVER) {
                val value = current.fulfillment
                val receipt = value.toHandoverReceipt()
                return when (val evidence = dispatches.currentHandoffEvidence(fulfillment.fulfillmentId)) {
                    is FulfillmentHandoffEvidenceNetworkOutcome.Evidence -> {
                        if (evidence.value.deliveryId != receipt.deliveryId ||
                            evidence.value.fulfillmentId != fulfillment.fulfillmentId
                        ) DispatchHandoverGatewayResult.ServiceUnavailable
                        else DispatchHandoverGatewayResult.AlreadyCompleted(
                            receipt.copy(evidence = evidence.value.toFeature())
                        )
                    }
                    else -> evidence.toHandoverFailure()
                }
            }
            else -> if (current !is FulfillmentDispatchNetworkOutcome.Current) {
                return current.toHandoverFailure()
            }
        }

        val currentReadiness = when (val result = readiness.detail(fulfillment.fulfillmentId)) {
            is DispatchReadinessNetworkOutcome.Detail -> DispatchReadinessProjectionAdapter.toFeature(result.item)
            else -> return result.toHandoverFailure()
        }
        if (!currentReadiness.fulfillmentId.equals(fulfillment.fulfillmentId, ignoreCase = true)) {
            return DispatchHandoverGatewayResult.Stale
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)

        val physical = when (val result = outgoingGoods.snapshot(fulfillment.fulfillmentId)) {
            is OutgoingGoodsCheckNetworkOutcome.Snapshot -> result
            else -> return result.toHandoverFailure()
        }
        if (!physical.allocation.id.equals(currentReadiness.physicalAllocationId, ignoreCase = true) ||
            physical.allocation.version != currentReadiness.physicalAllocationVersion
        ) return DispatchHandoverGatewayResult.Stale
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)

        val assignment = when (val result = assignments.current(fulfillment.fulfillmentId)) {
            is DispatchAssignmentNetworkOutcome.Current -> result.item?.toFeature()
            else -> return result.toHandoverFailure()
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)

        return DispatchHandoverGatewayResult.Snapshot(
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
    ): DispatchHandoverGatewayResult {
        val authorization = authorize(context)
        if (authorization !is Authorization.Current) return authorization.toResult()
        if (command.fulfillmentId != fulfillment.fulfillmentId ||
            !command.isValid()
        ) return DispatchHandoverGatewayResult.Stale

        if (snapshot != null) {
            if (command.expectedFulfillmentVersion != snapshot.readiness.fulfillmentVersion ||
                command.physicalAllocationId != snapshot.allocation.id ||
                command.physicalAllocationVersion != snapshot.allocation.version ||
                command.driverAssignmentId != snapshot.driverAssignment?.id ||
                command.driverAssignmentVersion != snapshot.driverAssignment?.fulfillmentVersion ||
                command.outgoingGoodsCheckId != snapshot.outgoingCheck?.id
            ) return DispatchHandoverGatewayResult.Stale
            val fresh = when (val result = load(snapshot.readiness, context)) {
                is DispatchHandoverGatewayResult.Snapshot -> result.value
                is DispatchHandoverGatewayResult.AlreadyCompleted -> return result
                else -> return result
            }
            if (!fresh.canConfirm ||
                fresh.readiness.fulfillmentVersion != command.expectedFulfillmentVersion ||
                fresh.allocation.id != command.physicalAllocationId ||
                fresh.allocation.version != command.physicalAllocationVersion ||
                fresh.driverAssignment?.id != command.driverAssignmentId ||
                fresh.driverAssignment?.fulfillmentVersion != command.driverAssignmentVersion ||
                fresh.outgoingCheck?.id != command.outgoingGoodsCheckId
            ) return DispatchHandoverGatewayResult.Stale
        }
        if (!isCurrent(context, authorization.lease)) return authorityDrift(context)

        return when (val result = dispatches.dispatch(
            command.fulfillmentId,
            command.expectedFulfillmentVersion,
            command.idempotencyKey,
            command.exactRequestBody
        )) {
            is FulfillmentDispatchNetworkOutcome.Dispatched -> {
                if (!isCurrent(context, authorization.lease)) authorityDrift(context)
                else when (val evidence = dispatches.currentHandoffEvidence(command.fulfillmentId)) {
                    is FulfillmentHandoffEvidenceNetworkOutcome.Evidence -> {
                        val fact = evidence.value
                        if (!fact.fulfillmentId.equals(command.fulfillmentId, true) ||
                            fact.deliveryId != result.fulfillment.deliveryId ||
                            fact.fulfillmentVersion != command.expectedFulfillmentVersion + 1 ||
                            fact.physicalAllocationId != command.physicalAllocationId ||
                            fact.physicalAllocationVersion != command.physicalAllocationVersion ||
                            fact.driverAssignmentId != command.driverAssignmentId ||
                            fact.outgoingGoodsCheckId != command.outgoingGoodsCheckId
                        ) DispatchHandoverGatewayResult.UnknownOutcome
                        else if (!isCurrent(context, authorization.lease)) authorityDrift(context)
                        else DispatchHandoverGatewayResult.Dispatched(
                            result.fulfillment.toHandoverReceipt().copy(evidence = fact.toFeature())
                        )
                    }
                    else -> evidence.toHandoverFailure()
                }
            }

            is FulfillmentDispatchNetworkOutcome.Current -> if (result.fulfillment.status == HANDED_OVER) {
                DispatchHandoverGatewayResult.AlreadyCompleted(result.fulfillment.toHandoverReceipt())
            } else {
                DispatchHandoverGatewayResult.ServiceUnavailable
            }

            else -> result.toHandoverFailure()
        }
    }

    private suspend fun authorize(context: DispatchAuthorityContext): Authorization {
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
        return if (MANAGE_PERMISSION in identity.permissions && READ_PERMISSION in identity.permissions) {
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
        ) return false
        val identity = context.identity ?: return false
        return sessions.verifiedSession.value?.matches(identity) == true &&
            MANAGE_PERMISSION in identity.permissions && READ_PERMISSION in identity.permissions
    }

    private suspend fun authorityDrift(context: DispatchAuthorityContext) = when {
        sessions.sessionState.value != SessionState.Active ->
            DispatchHandoverGatewayResult.SessionInvalidated

        sessions.verifiedSession.value?.let { verified ->
            context.identity?.let { expected -> verified.matches(expected) } == true
        } == true -> DispatchHandoverGatewayResult.SessionInvalidated

        else -> DispatchHandoverGatewayResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(expected: DispatchAuthorityIdentity): Boolean =
        hasAuthorizedContext && userId == expected.userId && tenantId == expected.tenantId &&
            workspaceId == expected.workspaceId && membershipId == expected.membershipId &&
            permissions == expected.permissions

    private fun DispatchDriverAssignmentProjection.toFeature() = PreparedFulfillmentDriverAssignment(
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

    private fun com.nexa.mobile.operations.core.network.PhysicalAllocationProjection.toFeature() =
        DispatchOutgoingGoodsAllocation(
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

    private fun com.nexa.mobile.operations.core.network.OutgoingGoodsCheckProjection.toFeature() =
        DispatchOutgoingGoodsCheck(
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
                DispatchOutgoingGoodsCheckLine(
                    it.physicalAllocationLineId,
                    it.expectedLotId,
                    it.observedLotId,
                    it.expectedQuantity,
                    it.observedQuantity,
                    it.unit,
                    it.matches
                )
            },
            replayed = replayed
        )

    private fun com.nexa.mobile.operations.core.network.FulfillmentDispatchProjection.toHandoverReceipt() =
        DispatchHandoverReceipt(
            fulfillmentId = fulfillmentId,
            fulfillmentStatus = status,
            fulfillmentVersion = version,
            deliveryId = requireNotNull(deliveryId),
            deliveryStatus = requireNotNull(deliveryStatus),
            deliveryVersion = requireNotNull(deliveryVersion),
            recordedAt = updatedAt
        )

    private fun com.nexa.mobile.operations.core.network.FulfillmentHandoffEvidenceProjection.toFeature() =
        DispatchHandoverEvidence(
            evidenceId = id,
            fulfillmentVersion = fulfillmentVersion,
            deliveryId = deliveryId,
            warehouseActorMembershipId = warehouseActorMembershipId,
            driverAssignmentId = driverAssignmentId,
            driverMembershipId = driverMembershipId,
            physicalAllocationId = physicalAllocationId,
            physicalAllocationVersion = physicalAllocationVersion,
            outgoingGoodsCheckId = outgoingGoodsCheckId,
            occurredAt = occurredAt,
            current = current
        )

    private fun DispatchReadinessNetworkOutcome.toHandoverFailure(): DispatchHandoverGatewayResult = when (this) {
        DispatchReadinessNetworkOutcome.NetworkUnavailable -> DispatchHandoverGatewayResult.NetworkUnavailable
        DispatchReadinessNetworkOutcome.ServiceUnavailable -> DispatchHandoverGatewayResult.ServiceUnavailable
        DispatchReadinessNetworkOutcome.PermissionDenied -> DispatchHandoverGatewayResult.PermissionDenied
        DispatchReadinessNetworkOutcome.ContextInvalidated -> DispatchHandoverGatewayResult.ContextInvalidated
        DispatchReadinessNetworkOutcome.SessionInvalidated -> DispatchHandoverGatewayResult.SessionInvalidated
        is DispatchReadinessNetworkOutcome.Detail,
        is DispatchReadinessNetworkOutcome.ListResult -> DispatchHandoverGatewayResult.ServiceUnavailable
    }

    private fun OutgoingGoodsCheckNetworkOutcome.toHandoverFailure(): DispatchHandoverGatewayResult = when (this) {
        OutgoingGoodsCheckNetworkOutcome.NetworkUnavailable -> DispatchHandoverGatewayResult.NetworkUnavailable
        OutgoingGoodsCheckNetworkOutcome.ServiceUnavailable -> DispatchHandoverGatewayResult.ServiceUnavailable
        OutgoingGoodsCheckNetworkOutcome.PermissionDenied -> DispatchHandoverGatewayResult.PermissionDenied
        OutgoingGoodsCheckNetworkOutcome.ContextInvalidated -> DispatchHandoverGatewayResult.ContextInvalidated
        OutgoingGoodsCheckNetworkOutcome.SessionInvalidated -> DispatchHandoverGatewayResult.SessionInvalidated
        OutgoingGoodsCheckNetworkOutcome.Stale -> DispatchHandoverGatewayResult.Stale
        OutgoingGoodsCheckNetworkOutcome.Conflict -> DispatchHandoverGatewayResult.Conflict
        OutgoingGoodsCheckNetworkOutcome.UnknownOutcome -> DispatchHandoverGatewayResult.UnknownOutcome
        is OutgoingGoodsCheckNetworkOutcome.Recorded,
        is OutgoingGoodsCheckNetworkOutcome.Snapshot -> DispatchHandoverGatewayResult.ServiceUnavailable
    }

    private fun DispatchAssignmentNetworkOutcome.toHandoverFailure(): DispatchHandoverGatewayResult = when (this) {
        DispatchAssignmentNetworkOutcome.NetworkUnavailable -> DispatchHandoverGatewayResult.NetworkUnavailable
        DispatchAssignmentNetworkOutcome.ServiceUnavailable -> DispatchHandoverGatewayResult.ServiceUnavailable
        DispatchAssignmentNetworkOutcome.PermissionDenied -> DispatchHandoverGatewayResult.PermissionDenied
        DispatchAssignmentNetworkOutcome.ContextInvalidated -> DispatchHandoverGatewayResult.ContextInvalidated
        DispatchAssignmentNetworkOutcome.SessionInvalidated -> DispatchHandoverGatewayResult.SessionInvalidated
        DispatchAssignmentNetworkOutcome.Stale -> DispatchHandoverGatewayResult.Stale
        DispatchAssignmentNetworkOutcome.Conflict -> DispatchHandoverGatewayResult.Conflict
        DispatchAssignmentNetworkOutcome.UnknownOutcome -> DispatchHandoverGatewayResult.UnknownOutcome
        is DispatchAssignmentNetworkOutcome.Assigned,
        is DispatchAssignmentNetworkOutcome.Candidates,
        is DispatchAssignmentNetworkOutcome.Current -> DispatchHandoverGatewayResult.ServiceUnavailable
    }

    private fun FulfillmentDispatchNetworkOutcome.toHandoverFailure(): DispatchHandoverGatewayResult = when (this) {
        FulfillmentDispatchNetworkOutcome.NetworkUnavailable -> DispatchHandoverGatewayResult.NetworkUnavailable
        FulfillmentDispatchNetworkOutcome.UnknownOutcome -> DispatchHandoverGatewayResult.UnknownOutcome
        FulfillmentDispatchNetworkOutcome.ServiceUnavailable -> DispatchHandoverGatewayResult.ServiceUnavailable
        FulfillmentDispatchNetworkOutcome.PermissionDenied -> DispatchHandoverGatewayResult.PermissionDenied
        FulfillmentDispatchNetworkOutcome.ContextInvalidated -> DispatchHandoverGatewayResult.ContextInvalidated
        FulfillmentDispatchNetworkOutcome.SessionInvalidated -> DispatchHandoverGatewayResult.SessionInvalidated
        FulfillmentDispatchNetworkOutcome.Stale -> DispatchHandoverGatewayResult.Stale
        FulfillmentDispatchNetworkOutcome.Conflict -> DispatchHandoverGatewayResult.Conflict
        is FulfillmentDispatchNetworkOutcome.Current,
        is FulfillmentDispatchNetworkOutcome.Dispatched -> DispatchHandoverGatewayResult.ServiceUnavailable
    }

    private fun FulfillmentHandoffEvidenceNetworkOutcome.toHandoverFailure(): DispatchHandoverGatewayResult = when (this) {
        FulfillmentHandoffEvidenceNetworkOutcome.NetworkUnavailable -> DispatchHandoverGatewayResult.NetworkUnavailable
        FulfillmentHandoffEvidenceNetworkOutcome.ServiceUnavailable -> DispatchHandoverGatewayResult.ServiceUnavailable
        FulfillmentHandoffEvidenceNetworkOutcome.PermissionDenied -> DispatchHandoverGatewayResult.PermissionDenied
        FulfillmentHandoffEvidenceNetworkOutcome.ContextInvalidated -> DispatchHandoverGatewayResult.ContextInvalidated
        FulfillmentHandoffEvidenceNetworkOutcome.SessionInvalidated -> DispatchHandoverGatewayResult.SessionInvalidated
        is FulfillmentHandoffEvidenceNetworkOutcome.Evidence -> DispatchHandoverGatewayResult.ServiceUnavailable
    }

    private fun Authorization.toResult(): DispatchHandoverGatewayResult = when (this) {
        Authorization.SessionInvalidated -> DispatchHandoverGatewayResult.SessionInvalidated
        Authorization.ContextInvalidated -> DispatchHandoverGatewayResult.ContextInvalidated
        Authorization.PermissionDenied -> DispatchHandoverGatewayResult.PermissionDenied
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

internal class DispatchHandoverViewModelFactory @Inject constructor(
    private val gateway: DispatchHandoverGateway,
    private val metadata: DispatchHandoverMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DispatchHandoverViewModel::class.java))
        return DispatchHandoverViewModel(gateway, metadata) as T
    }
}
