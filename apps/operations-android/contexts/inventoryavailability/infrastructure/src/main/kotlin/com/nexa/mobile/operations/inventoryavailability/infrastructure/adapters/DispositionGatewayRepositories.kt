package com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.DispositionAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.DispositionGatewayResult
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.DispositionGateway
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.DispositionLotFacts
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotDispositionAction
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotDispositionCommand
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.DispositionLotProjection
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.DispositionNetworkOutcome
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.NexaDispositionGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Module
@InstallIn(SingletonComponent::class)
object DispositionGatewayBindings {
    @Provides
    @Singleton
    fun nexaDispositionGateway(protectedCalls: ProtectedCallExecutor): NexaDispositionGateway =
        NexaDispositionGateway(protectedCalls)
}

/** Binds feature requests to the current session lease and full verified identity snapshot. */
@Singleton
class OperationsDispositionGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val disposition: NexaDispositionGateway
) : DispositionGateway {
    override suspend fun lot(
        lotId: String,
        authority: DispositionAuthority
    ): DispositionGatewayResult {
        val before = authorize(authority, LOT_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toFeatureResult()
        val result = try {
            disposition.lot(lotId).toFeatureResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DispositionGatewayResult.ServiceUnavailable
        }
        return if (isCurrent(
                authority,
                before.lease
            )
        ) {
            result
        } else {
            authorityDrift(isMutation = false)
        }
    }

    override suspend fun dispose(
        command: LotDispositionCommand,
        idempotencyKey: String,
        authority: DispositionAuthority
    ): DispositionGatewayResult {
        val requiredPermission = when (command.disposition) {
            LotDispositionAction.RELEASE -> "inventory.release"

            LotDispositionAction.HOLD,
            LotDispositionAction.WASTE,
            LotDispositionAction.RETURN_TO_SUPPLIER -> "inventory.waste"
        }
        val before = authorize(authority, setOf(requiredPermission))
        if (before !is Authorization.Current) return before.toFeatureResult()
        val result = try {
            disposition.dispose(
                lotId = command.lotId,
                disposition = command.disposition.name,
                reason = command.reason,
                expectedVersion = command.expectedVersion,
                idempotencyKey = idempotencyKey,
                affectedQuantity = command.partialEvaluation?.affectedQuantity,
                temperatureEvaluationId =
                    command.partialEvaluation?.temperatureEvaluationId
            ).toFeatureResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DispositionGatewayResult.UnknownOutcome
        }
        return if (isCurrent(authority, before.lease)) result else authorityDrift(isMutation = true)
    }

    private suspend fun authorize(
        authority: DispositionAuthority,
        requiredPermissions: Set<String>
    ): Authorization {
        if (sessions.sessionState.value !=
            SessionState.Active
        ) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (authority.authorityEpoch <= 0 || !verified.matches(authority)) {
            return Authorization.ContextInvalidated
        }
        if (authority.permissions.none { it in requiredPermissions }) {
            return Authorization.PermissionDenied
        }
        return Authorization.Current(lease)
    }

    private suspend fun isCurrent(
        authority: DispositionAuthority,
        originalLease: AccessTokenLease
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(originalLease.epoch)
        ) {
            return false
        }
        return sessions.verifiedSession.value?.matches(authority) == true
    }

    private suspend fun authorityDrift(isMutation: Boolean): DispositionGatewayResult = when {
        isMutation -> DispositionGatewayResult.UnknownOutcome

        sessions.sessionState.value != SessionState.Active ->
            DispositionGatewayResult.SessionInvalidated

        else -> DispositionGatewayResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(authority: DispositionAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private fun Authorization.toFeatureResult(): DispositionGatewayResult = when (this) {
        Authorization.SessionInvalidated -> DispositionGatewayResult.SessionInvalidated
        Authorization.ContextInvalidated -> DispositionGatewayResult.ContextInvalidated
        Authorization.PermissionDenied -> DispositionGatewayResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun DispositionNetworkOutcome.toFeatureResult(): DispositionGatewayResult =
        when (this) {
            is DispositionNetworkOutcome.Lot -> DispositionGatewayResult.Lot(item.toFeature())

            is DispositionNetworkOutcome.Confirmed -> DispositionGatewayResult.Confirmed(
                item.toFeature()
            )

            is DispositionNetworkOutcome.Rejected -> DispositionGatewayResult.Rejected(code)

            DispositionNetworkOutcome.UnknownOutcome -> DispositionGatewayResult.UnknownOutcome

            DispositionNetworkOutcome.PreconditionFailed ->
                DispositionGatewayResult.PreconditionFailed

            DispositionNetworkOutcome.Conflict -> DispositionGatewayResult.Conflict

            DispositionNetworkOutcome.NetworkUnavailable ->
                DispositionGatewayResult.NetworkUnavailable

            DispositionNetworkOutcome.ServiceUnavailable ->
                DispositionGatewayResult.ServiceUnavailable

            DispositionNetworkOutcome.PermissionDenied ->
                DispositionGatewayResult.PermissionDenied

            DispositionNetworkOutcome.ContextInvalidated ->
                DispositionGatewayResult.ContextInvalidated

            DispositionNetworkOutcome.SessionInvalidated ->
                DispositionGatewayResult.SessionInvalidated
        }

    private fun DispositionLotProjection.toFeature() = DispositionLotFacts(
        id = id,
        warehouseId = warehouseId,
        zoneId = zoneId,
        catalogItemId = catalogItemId,
        skuId = skuId,
        batchNumber = batchNumber,
        expirationDate = expirationDate,
        receivedAt = receivedAt,
        onHand = onHand,
        reserved = reserved,
        available = available,
        unit = unit,
        status = status,
        version = version
    )

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        val LOT_READ_PERMISSIONS = setOf("inventory.read", "warehouse.read", "warehouse:read")
    }
}
