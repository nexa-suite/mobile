package com.nexa.mobile.operations.data

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.NexaReceivingGateway
import com.nexa.mobile.operations.core.network.NexaStockTransferGateway
import com.nexa.mobile.operations.core.network.ReceivingNetworkOutcome
import com.nexa.mobile.operations.core.network.StockTransferLookupNetworkOutcome as StockTransferLookupOutcome
import com.nexa.mobile.operations.core.network.StockTransferNetworkOutcome as StockTransferOutcome
import com.nexa.mobile.operations.core.network.StockTransferProjection
import com.nexa.mobile.operations.core.network.StockTransferReceiptObservationNetworkOutcome as StockTransferReceiptObservationOutcome
import com.nexa.mobile.operations.feature.warehouse.application.StockTransferReceiptGateway
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferAuthority
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptIntent
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptLookupResult
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptObservation
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptObservationIntent
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptObservationResult
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptResult
import com.nexa.mobile.operations.feature.warehouse.model.StockTransferReceiptTransfer
import com.nexa.mobile.operations.feature.warehouse.model.TransferWarehouseChoice
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class OperationsStockTransferReceiptGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val receiving: NexaReceivingGateway,
    private val transfers: NexaStockTransferGateway
) : StockTransferReceiptGateway {
    override suspend fun warehouses(
        authority: StockTransferAuthority
    ): StockTransferReceiptLookupResult {
        val before = authorize(authority, LOOKUP_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLookupFailure()
        val result = safeLookup {
            when (val response = receiving.warehouses()) {
                is ReceivingNetworkOutcome.Warehouses ->
                    StockTransferReceiptLookupResult.Warehouses(
                        response.items.map {
                            TransferWarehouseChoice(it.id, it.code, it.name, it.status)
                        }
                    )

                ReceivingNetworkOutcome.NetworkUnavailable ->
                    StockTransferReceiptLookupResult.NetworkUnavailable

                ReceivingNetworkOutcome.PermissionDenied ->
                    StockTransferReceiptLookupResult.PermissionDenied

                ReceivingNetworkOutcome.ContextInvalidated ->
                    StockTransferReceiptLookupResult.ContextInvalidated

                ReceivingNetworkOutcome.SessionInvalidated ->
                    StockTransferReceiptLookupResult.SessionInvalidated

                else -> StockTransferReceiptLookupResult.ServiceUnavailable
            }
        }
        return if (currentAfter(
                authority,
                before.lease,
                LOOKUP_PERMISSIONS
            )
        ) {
            result
        } else {
            authorityDrift(authority)
        }
    }

    override suspend fun transfers(
        destinationWarehouseId: String,
        page: Int,
        authority: StockTransferAuthority
    ): StockTransferReceiptLookupResult {
        val before = authorize(authority, LOOKUP_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLookupFailure()
        val result = safeLookup {
            when (val response = transfers.transfersForDestination(destinationWarehouseId, page)) {
                is StockTransferLookupOutcome.Page ->
                    StockTransferReceiptLookupResult.TransferPage(
                        items = response.items.map { it.toReceiptTransfer() },
                        page = response.page,
                        total = response.total
                    )

                StockTransferLookupOutcome.NetworkUnavailable ->
                    StockTransferReceiptLookupResult.NetworkUnavailable

                StockTransferLookupOutcome.PermissionDenied ->
                    StockTransferReceiptLookupResult.PermissionDenied

                StockTransferLookupOutcome.ContextInvalidated ->
                    StockTransferReceiptLookupResult.ContextInvalidated

                StockTransferLookupOutcome.SessionInvalidated ->
                    StockTransferReceiptLookupResult.SessionInvalidated

                else -> StockTransferReceiptLookupResult.ServiceUnavailable
            }
        }
        return if (currentAfter(
                authority,
                before.lease,
                LOOKUP_PERMISSIONS
            )
        ) {
            result
        } else {
            authorityDrift(authority)
        }
    }

    override suspend fun transfer(
        transferId: String,
        authority: StockTransferAuthority
    ): StockTransferReceiptLookupResult {
        val before = authorize(authority, LOOKUP_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLookupFailure()
        val result = safeLookup {
            when (val response = transfers.transfer(transferId)) {
                is StockTransferLookupOutcome.Transfer ->
                    StockTransferReceiptLookupResult.Transfer(response.item.toReceiptTransfer())

                StockTransferLookupOutcome.NetworkUnavailable ->
                    StockTransferReceiptLookupResult.NetworkUnavailable

                StockTransferLookupOutcome.PermissionDenied ->
                    StockTransferReceiptLookupResult.PermissionDenied

                StockTransferLookupOutcome.ContextInvalidated ->
                    StockTransferReceiptLookupResult.ContextInvalidated

                StockTransferLookupOutcome.SessionInvalidated ->
                    StockTransferReceiptLookupResult.SessionInvalidated

                else -> StockTransferReceiptLookupResult.ServiceUnavailable
            }
        }
        return if (currentAfter(
                authority,
                before.lease,
                LOOKUP_PERMISSIONS
            )
        ) {
            result
        } else {
            authorityDrift(authority)
        }
    }

    override suspend fun receive(
        intent: StockTransferReceiptIntent,
        authority: StockTransferAuthority
    ): StockTransferReceiptResult {
        if (intent.scope != authority.scope) return StockTransferReceiptResult.ContextInvalidated
        val before = authorize(authority, WRITE_PERMISSIONS)
        if (before !is Authorization.Current) return before.toCommandFailure()
        val result = try {
            transfers.receiveTransfer(
                intent.transfer.toNetworkProjection(),
                intent.idempotencyKey
            ).toReceiptResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            StockTransferReceiptResult.UnknownOutcome
        }
        return if (currentAfter(authority, before.lease, WRITE_PERMISSIONS)) {
            result
        } else {
            when (authorityDrift(authority)) {
                StockTransferReceiptLookupResult.SessionInvalidated ->
                    StockTransferReceiptResult.SessionInvalidated

                else -> StockTransferReceiptResult.ContextInvalidated
            }
        }
    }

    override suspend fun observeArrival(
        intent: StockTransferReceiptObservationIntent,
        authority: StockTransferAuthority
    ): StockTransferReceiptObservationResult {
        if (intent.scope !=
            authority.scope
        ) {
            return StockTransferReceiptObservationResult.ContextInvalidated
        }
        val before = authorize(authority, WRITE_PERMISSIONS)
        if (before !is Authorization.Current) return before.toObservationFailure()
        val result = try {
            when (
                val response = transfers.observeTransferArrival(
                    expectedTransfer = intent.transfer.toNetworkProjection(),
                    observedBatchNumber = intent.observedBatchNumber,
                    observedExpirationDate = intent.observedExpirationDate,
                    observedQuantity = requireNotNull(
                        intent.observedQuantityText.toBigDecimalOrNull()
                    ),
                    unit = intent.observedUnit,
                    idempotencyKey = intent.idempotencyKey
                )
            ) {
                is StockTransferReceiptObservationOutcome.Recorded ->
                    if (response.observation.actorMembershipId == authority.membershipId) {
                        response.toReceiptObservationResult()
                    } else {
                        StockTransferReceiptObservationResult.UnknownOutcome
                    }

                else -> response.toReceiptObservationResult()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            StockTransferReceiptObservationResult.UnknownOutcome
        }
        return if (currentAfter(authority, before.lease, WRITE_PERMISSIONS)) {
            result
        } else {
            when (authorityDrift(authority)) {
                StockTransferReceiptLookupResult.SessionInvalidated ->
                    StockTransferReceiptObservationResult.SessionInvalidated

                else -> StockTransferReceiptObservationResult.ContextInvalidated
            }
        }
    }

    private suspend fun authorize(
        authority: StockTransferAuthority,
        required: Set<String>
    ): Authorization {
        if (sessions.sessionState.value !=
            SessionState.Active
        ) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (!verified.matches(authority)) return Authorization.ContextInvalidated
        return if (authority.permissions.any { it in required }) {
            Authorization.Current(lease)
        } else {
            Authorization.PermissionDenied
        }
    }

    private suspend fun currentAfter(
        authority: StockTransferAuthority,
        originalLease: AccessTokenLease,
        required: Set<String>
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(originalLease.epoch)
        ) {
            return false
        }
        val current = sessions.verifiedSession.value ?: return false
        return current.matches(authority) && authority.permissions.any { it in required }
    }

    private suspend fun authorityDrift(
        authority: StockTransferAuthority
    ): StockTransferReceiptLookupResult = when {
        sessions.sessionState.value != SessionState.Active ->
            StockTransferReceiptLookupResult.SessionInvalidated

        sessions.verifiedSession.value?.matches(
            authority
        ) == true -> StockTransferReceiptLookupResult.SessionInvalidated

        else -> StockTransferReceiptLookupResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(authority: StockTransferAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private fun Authorization.toLookupFailure(): StockTransferReceiptLookupResult = when (this) {
        Authorization.SessionInvalidated -> StockTransferReceiptLookupResult.SessionInvalidated
        Authorization.ContextInvalidated -> StockTransferReceiptLookupResult.ContextInvalidated
        Authorization.PermissionDenied -> StockTransferReceiptLookupResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a lookup failure")
    }

    private fun Authorization.toCommandFailure(): StockTransferReceiptResult = when (this) {
        Authorization.SessionInvalidated -> StockTransferReceiptResult.SessionInvalidated
        Authorization.ContextInvalidated -> StockTransferReceiptResult.ContextInvalidated
        Authorization.PermissionDenied -> StockTransferReceiptResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a command failure")
    }

    private fun Authorization.toObservationFailure(): StockTransferReceiptObservationResult =
        when (this) {
            Authorization.SessionInvalidated ->
                StockTransferReceiptObservationResult.SessionInvalidated

            Authorization.ContextInvalidated ->
                StockTransferReceiptObservationResult.ContextInvalidated

            Authorization.PermissionDenied -> StockTransferReceiptObservationResult.PermissionDenied

            is Authorization.Current -> error("authorized result is not an observation failure")
        }

    private fun StockTransferOutcome.toReceiptResult(): StockTransferReceiptResult = when (this) {
        is StockTransferOutcome.Confirmed -> StockTransferReceiptResult.Confirmed(
            transfer.toReceiptTransfer()
        )

        is StockTransferOutcome.Rejected -> StockTransferReceiptResult.Rejected(code)

        StockTransferOutcome.UnknownOutcome -> StockTransferReceiptResult.UnknownOutcome

        StockTransferOutcome.PreconditionFailed ->
            StockTransferReceiptResult.PreconditionFailed

        StockTransferOutcome.Conflict -> StockTransferReceiptResult.Conflict

        StockTransferOutcome.NetworkUnavailable ->
            StockTransferReceiptResult.NetworkUnavailable

        StockTransferOutcome.ServiceUnavailable ->
            StockTransferReceiptResult.ServiceUnavailable

        StockTransferOutcome.PermissionDenied ->
            StockTransferReceiptResult.PermissionDenied

        StockTransferOutcome.ContextInvalidated ->
            StockTransferReceiptResult.ContextInvalidated

        StockTransferOutcome.SessionInvalidated ->
            StockTransferReceiptResult.SessionInvalidated
    }

    private fun StockTransferReceiptObservationOutcome.toReceiptObservationResult():
        StockTransferReceiptObservationResult =
        when (this) {
            is StockTransferReceiptObservationOutcome.Recorded -> {
                val value = observation
                StockTransferReceiptObservationResult.Recorded(
                    StockTransferReceiptObservation(
                        observationId = value.observationId,
                        transferId = value.transferId,
                        transferVersion = value.transferVersion,
                        observedBatchNumber = value.observedBatchNumber,
                        observedExpirationDate = value.observedExpirationDate,
                        observedQuantityText = value.observedQuantity.toPlainString(),
                        observedUnit = value.observedUnit,
                        hasDifference = value.hasDifference,
                        actorMembershipId = value.actorMembershipId,
                        recordedAt = value.recordedAt
                    )
                )
            }

            is StockTransferReceiptObservationOutcome.Rejected ->
                StockTransferReceiptObservationResult.Rejected(
                    code
                )

            StockTransferReceiptObservationOutcome.UnknownOutcome ->
                StockTransferReceiptObservationResult.UnknownOutcome

            StockTransferReceiptObservationOutcome.PreconditionFailed ->
                StockTransferReceiptObservationResult.PreconditionFailed

            StockTransferReceiptObservationOutcome.Conflict ->
                StockTransferReceiptObservationResult.Conflict

            StockTransferReceiptObservationOutcome.NetworkUnavailable ->
                StockTransferReceiptObservationResult.NetworkUnavailable

            StockTransferReceiptObservationOutcome.ServiceUnavailable ->
                StockTransferReceiptObservationResult.ServiceUnavailable

            StockTransferReceiptObservationOutcome.PermissionDenied ->
                StockTransferReceiptObservationResult.PermissionDenied

            StockTransferReceiptObservationOutcome.ContextInvalidated ->
                StockTransferReceiptObservationResult.ContextInvalidated

            StockTransferReceiptObservationOutcome.SessionInvalidated ->
                StockTransferReceiptObservationResult.SessionInvalidated
        }

    private fun StockTransferProjection.toReceiptTransfer() = StockTransferReceiptTransfer(
        id = id,
        sourceWarehouseId = sourceWarehouseId,
        sourceZoneId = sourceZoneId,
        sourceLotId = sourceLotId,
        destinationWarehouseId = destinationWarehouseId,
        destinationZoneId = destinationZoneId,
        destinationLotId = destinationLotId,
        skuId = skuId,
        catalogItemId = catalogItemId,
        batchNumber = batchNumber,
        expirationDate = expirationDate,
        requestedQuantityText = requestedQuantity.toPlainString(),
        transferredQuantityText = transferredQuantity.toPlainString(),
        mode = mode,
        unit = unit,
        status = status,
        reason = reason,
        sourceVersionBefore = sourceVersionBefore,
        sourceVersionAfter = sourceVersionAfter,
        destinationVersionAfter = destinationVersionAfter,
        version = version,
        dispatchedAt = dispatchedAt,
        receivedAt = receivedAt
    )

    private fun StockTransferReceiptTransfer.toNetworkProjection() = StockTransferProjection(
        id = id,
        sourceWarehouseId = sourceWarehouseId,
        sourceZoneId = sourceZoneId,
        sourceLotId = sourceLotId,
        destinationWarehouseId = destinationWarehouseId,
        destinationZoneId = destinationZoneId,
        destinationLotId = destinationLotId,
        skuId = skuId,
        catalogItemId = catalogItemId,
        requestedQuantity = requireNotNull(requestedQuantityText.toBigDecimalOrNull()),
        transferredQuantity = requireNotNull(transferredQuantityText.toBigDecimalOrNull()),
        mode = mode,
        unit = unit,
        status = status,
        reason = reason,
        sourceVersionBefore = sourceVersionBefore,
        sourceVersionAfter = sourceVersionAfter,
        destinationVersionAfter = destinationVersionAfter,
        version = version,
        dispatchedAt = dispatchedAt,
        receivedAt = receivedAt,
        batchNumber = batchNumber,
        expirationDate = expirationDate
    )

    private suspend fun safeLookup(block: suspend () -> StockTransferReceiptLookupResult) = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        StockTransferReceiptLookupResult.ServiceUnavailable
    }

    private sealed interface Authorization {
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
        data class Current(val lease: AccessTokenLease) : Authorization
    }

    private companion object {
        val LOOKUP_PERMISSIONS = setOf("warehouse:read", "warehouse.read", "inventory.read")
        val WRITE_PERMISSIONS = setOf("warehouse:write")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object StockTransferReceiptGatewayBindings {
    @Provides
    @Singleton
    fun stockTransferReceiptGateway(
        gateway: OperationsStockTransferReceiptGateway
    ): StockTransferReceiptGateway = gateway
}
