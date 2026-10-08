package com.nexa.mobile.operations.data

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.NexaPickingGateway
import com.nexa.mobile.operations.core.network.PickingAllocationLineProjection as NetworkAllocationLine
import com.nexa.mobile.operations.core.network.PickingAllocationProjection as NetworkAllocation
import com.nexa.mobile.operations.core.network.PickingConfirmationRequest
import com.nexa.mobile.operations.core.network.PickingFulfillmentLineProjection as NetworkFulfillmentLine
import com.nexa.mobile.operations.core.network.PickingFulfillmentProjection as NetworkFulfillment
import com.nexa.mobile.operations.core.network.PickingNetworkOutcome
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.warehouse.application.PickingGateway
import com.nexa.mobile.operations.feature.warehouse.model.FulfillmentPickingLine
import com.nexa.mobile.operations.feature.warehouse.model.FulfillmentPickingSnapshot
import com.nexa.mobile.operations.feature.warehouse.model.PickingAllocationLine
import com.nexa.mobile.operations.feature.warehouse.model.PickingAllocationProjection
import com.nexa.mobile.operations.feature.warehouse.model.PickingAuthority
import com.nexa.mobile.operations.feature.warehouse.model.PickingConfirmationCommand
import com.nexa.mobile.operations.feature.warehouse.model.PickingFulfillmentSnapshot as FeatureSnapshot
import com.nexa.mobile.operations.feature.warehouse.model.PickingIntentCommand
import com.nexa.mobile.operations.feature.warehouse.model.PickingLoadResult
import com.nexa.mobile.operations.feature.warehouse.model.PickingMutationResult
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** App boundary binds picking to full verified scope, exact permissions, and session epoch. */
@Singleton
class OperationsPickingGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val picking: NexaPickingGateway
) : PickingGateway {
    override suspend fun load(
        fulfillmentId: String,
        authority: PickingAuthority
    ): PickingLoadResult {
        val before = authorize(authority, FULFILLMENT_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLoadFailure()
        val fulfillmentOutcome = try {
            picking.fulfillment(fulfillmentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PickingLoadResult.ServiceUnavailable
        }
        if (!currentAfter(authority, before.lease)) return authorityDrift(authority)
        val fulfillment = when (fulfillmentOutcome) {
            is PickingNetworkOutcome.Fulfillment -> fulfillmentOutcome.value.toFeature()
            PickingNetworkOutcome.NotFound -> return PickingLoadResult.NotFound
            else -> return fulfillmentOutcome.toLoadFailure()
        }
        val allocationOutcome = try {
            picking.allocation(fulfillmentId)
        } catch (_: Exception) {
            return if (currentAfter(authority, before.lease)) {
                PickingLoadResult.ServiceUnavailable
            } else {
                authorityDrift(authority)
            }
        }
        if (!currentAfter(authority, before.lease)) return authorityDrift(authority)
        return when (allocationOutcome) {
            is PickingNetworkOutcome.Allocation -> {
                if (allocationOutcome.value.status.equals("ALLOCATED", ignoreCase = true)) {
                    PickingLoadResult.Loaded(
                        FeatureSnapshot(fulfillment, allocationOutcome.value.toFeature())
                    )
                } else {
                    PickingLoadResult.AllocationUnavailable(fulfillment)
                }
            }

            PickingNetworkOutcome.NotFound -> PickingLoadResult.AllocationUnavailable(fulfillment)

            else -> allocationOutcome.toLoadFailure().let { result ->
                if (result is PickingLoadResult.NotFound) {
                    PickingLoadResult.AllocationUnavailable(fulfillment)
                } else {
                    result
                }
            }
        }
    }

    override suspend fun startPicking(
        command: PickingIntentCommand.Start,
        idempotencyKey: String,
        authority: PickingAuthority
    ): PickingMutationResult {
        val before = authorize(authority, FULFILLMENT_WRITE_PERMISSIONS)
        if (before !is Authorization.Current) return before.toMutationFailure()
        val outcome = try {
            picking.startPicking(
                command.fulfillmentId,
                command.expectedFulfillmentVersion,
                idempotencyKey
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PickingMutationResult.UnknownOutcome
        }
        return if (currentAfter(authority, before.lease)) {
            outcome.toMutationResult()
        } else {
            authorityDriftMutation(authority)
        }
    }

    override suspend fun confirmPicking(
        command: PickingConfirmationCommand,
        idempotencyKey: String,
        authority: PickingAuthority
    ): PickingMutationResult {
        val before = authorize(authority, FULFILLMENT_WRITE_PERMISSIONS)
        if (before !is Authorization.Current) return before.toMutationFailure()
        val outcome = try {
            picking.confirmPicking(command.toNetwork(), idempotencyKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PickingMutationResult.UnknownOutcome
        }
        return if (currentAfter(authority, before.lease)) {
            outcome.toMutationResult()
        } else {
            authorityDriftMutation(authority)
        }
    }

    private suspend fun authorize(
        authority: PickingAuthority,
        requiredPermissions: Set<String>
    ): Authorization {
        if (sessions.sessionState.value != SessionState.Active) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (!verified.matches(authority)) return Authorization.ContextInvalidated
        if (authority.permissions.none { it in requiredPermissions }) {
            return Authorization.PermissionDenied
        }
        return Authorization.Current(lease)
    }

    private suspend fun currentAfter(
        authority: PickingAuthority,
        originalLease: AccessTokenLease
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(originalLease.epoch)
        ) {
            return false
        }
        return sessions.verifiedSession.value?.matches(authority) == true
    }

    private suspend fun authorityDrift(authority: PickingAuthority): PickingLoadResult {
        val stillSameContext = sessions.verifiedSession.value?.matches(authority) == true
        return if (sessions.sessionState.value == SessionState.Active && stillSameContext) {
            PickingLoadResult.SessionInvalidated
        } else if (sessions.sessionState.value != SessionState.Active) {
            PickingLoadResult.SessionInvalidated
        } else {
            PickingLoadResult.ContextInvalidated
        }
    }

    private suspend fun authorityDriftMutation(authority: PickingAuthority): PickingMutationResult {
        val stillSameContext = sessions.verifiedSession.value?.matches(authority) == true
        return if (sessions.sessionState.value == SessionState.Active && stillSameContext) {
            PickingMutationResult.SessionInvalidated
        } else if (sessions.sessionState.value != SessionState.Active) {
            PickingMutationResult.SessionInvalidated
        } else {
            PickingMutationResult.ContextInvalidated
        }
    }

    private fun VerifiedSession.matches(authority: PickingAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private fun Authorization.toLoadFailure(): PickingLoadResult = when (this) {
        Authorization.SessionInvalidated -> PickingLoadResult.SessionInvalidated
        Authorization.ContextInvalidated -> PickingLoadResult.ContextInvalidated
        Authorization.PermissionDenied -> PickingLoadResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Authorization.toMutationFailure(): PickingMutationResult = when (this) {
        Authorization.SessionInvalidated -> PickingMutationResult.SessionInvalidated
        Authorization.ContextInvalidated -> PickingMutationResult.ContextInvalidated
        Authorization.PermissionDenied -> PickingMutationResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun PickingNetworkOutcome.toLoadFailure(): PickingLoadResult = when (this) {
        PickingNetworkOutcome.NetworkUnavailable -> PickingLoadResult.NetworkUnavailable
        PickingNetworkOutcome.PermissionDenied -> PickingLoadResult.PermissionDenied
        PickingNetworkOutcome.ContextInvalidated -> PickingLoadResult.ContextInvalidated
        PickingNetworkOutcome.SessionInvalidated -> PickingLoadResult.SessionInvalidated
        PickingNetworkOutcome.NotFound -> PickingLoadResult.NotFound
        else -> PickingLoadResult.ServiceUnavailable
    }

    private fun PickingNetworkOutcome.toMutationResult(): PickingMutationResult = when (this) {
        is PickingNetworkOutcome.Updated -> PickingMutationResult.Confirmed(value.toFeature())

        is PickingNetworkOutcome.Rejected -> PickingMutationResult.Rejected(code)

        PickingNetworkOutcome.StaleVersion -> PickingMutationResult.StaleVersion

        PickingNetworkOutcome.UnknownOutcome,
        PickingNetworkOutcome.NetworkUnavailable -> PickingMutationResult.UnknownOutcome

        PickingNetworkOutcome.PermissionDenied -> PickingMutationResult.PermissionDenied

        PickingNetworkOutcome.ContextInvalidated -> PickingMutationResult.ContextInvalidated

        PickingNetworkOutcome.SessionInvalidated -> PickingMutationResult.SessionInvalidated

        PickingNetworkOutcome.ServiceUnavailable -> PickingMutationResult.UnknownOutcome

        PickingNetworkOutcome.NotFound -> PickingMutationResult.Rejected("FULFILLMENT_NOT_FOUND")

        is PickingNetworkOutcome.Fulfillment,
        is PickingNetworkOutcome.Allocation -> PickingMutationResult.ServiceUnavailable
    }

    private fun NetworkFulfillment.toFeature() = FulfillmentPickingSnapshot(
        id = id,
        status = status,
        version = version,
        lines = lines.map { it.toFeature() }
    )

    private fun NetworkFulfillmentLine.toFeature() = FulfillmentPickingLine(
        id = id,
        skuId = skuId,
        catalogItemId = catalogItemId,
        allocatedQuantity = allocatedQuantity,
        pickedQuantity = pickedQuantity,
        remainingQuantity = remainingQuantity,
        unit = unit
    )

    private fun NetworkAllocation.toFeature() = PickingAllocationProjection(
        allocationId = allocationId,
        status = status,
        version = version,
        asOf = asOf,
        lines = lines.map { it.toFeature() }
    )

    private fun NetworkAllocationLine.toFeature() = PickingAllocationLine(
        physicalAllocationLineId = physicalAllocationLineId,
        skuId = skuId,
        catalogItemId = catalogItemId,
        warehouseId = warehouseId,
        zoneId = zoneId,
        lotId = lotId,
        quantity = quantity,
        releasedQuantity = releasedQuantity,
        consumedQuantity = consumedQuantity,
        remainingQuantity = remainingQuantity,
        unit = unit,
        expirationDate = expirationDate
    )

    private fun PickingConfirmationCommand.toNetwork() = PickingConfirmationRequest(
        fulfillmentId = fulfillmentId,
        expectedFulfillmentVersion = expectedFulfillmentVersion,
        allocationVersion = allocationVersion,
        fulfillmentLineId = fulfillmentLineId,
        skuId = skuId,
        physicalAllocationLineId = physicalAllocationLineId,
        lotId = lotId,
        warehouseId = warehouseId,
        quantity = quantity,
        unit = unit
    )

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        val FULFILLMENT_READ_PERMISSIONS = setOf("fulfillment.read", "fulfillment:read")
        val FULFILLMENT_WRITE_PERMISSIONS = setOf("fulfillment.manage", "warehouse:write")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object PickingGatewayModule {
    @Provides
    @Singleton
    fun featurePickingGateway(implementation: OperationsPickingGateway): PickingGateway =
        implementation

    @Provides
    @Singleton
    fun pickingGateway(protectedCalls: ProtectedCallExecutor) = NexaPickingGateway(protectedCalls)
}
