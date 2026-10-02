package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.CycleCountCorrectionCommand
import com.nexa.mobile.operations.core.network.CycleCountCountCommand
import com.nexa.mobile.operations.core.network.CycleCountLotProjection
import com.nexa.mobile.operations.core.network.CycleCountNetworkOutcome
import com.nexa.mobile.operations.core.network.NexaCycleCountGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.warehouse.CycleCountAuthority
import com.nexa.mobile.operations.feature.warehouse.CycleCountCorrection
import com.nexa.mobile.operations.feature.warehouse.CycleCountCorrectionIntent
import com.nexa.mobile.operations.feature.warehouse.CycleCountGateway
import com.nexa.mobile.operations.feature.warehouse.CycleCountIntent
import com.nexa.mobile.operations.feature.warehouse.CycleCountLookupResult
import com.nexa.mobile.operations.feature.warehouse.CycleCountLot
import com.nexa.mobile.operations.feature.warehouse.CycleCountMetadataStore
import com.nexa.mobile.operations.feature.warehouse.CycleCountRecord
import com.nexa.mobile.operations.feature.warehouse.CycleCountResult
import com.nexa.mobile.operations.feature.warehouse.CycleCountViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Keeps authorization and session-epoch checks at the app boundary, outside the transport module. */
@Singleton
internal class OperationsCycleCountGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val cycleCounts: NexaCycleCountGateway
) : CycleCountGateway {
    override suspend fun lots(authority: CycleCountAuthority, page: Int): CycleCountLookupResult {
        val before = authorize(authority, LOT_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLookupFailure()
        val result = safeLookup {
            when (val response = cycleCounts.lots(page)) {
                is CycleCountNetworkOutcome.Lots -> CycleCountLookupResult.Lots(
                    response.items.map { it.toFeatureLot() },
                    response.page,
                    response.total
                )

                CycleCountNetworkOutcome.NetworkUnavailable ->
                    CycleCountLookupResult.NetworkUnavailable

                CycleCountNetworkOutcome.PermissionDenied -> CycleCountLookupResult.PermissionDenied

                CycleCountNetworkOutcome.ContextInvalidated ->
                    CycleCountLookupResult.ContextInvalidated

                CycleCountNetworkOutcome.SessionInvalidated ->
                    CycleCountLookupResult.SessionInvalidated

                else -> CycleCountLookupResult.ServiceUnavailable
            }
        }
        return if (currentAfter(
                authority,
                before.lease,
                LOT_READ_PERMISSIONS
            )
        ) {
            result
        } else {
            authorityDrift(authority)
        }
    }

    override suspend fun record(
        intent: CycleCountIntent,
        authority: CycleCountAuthority
    ): CycleCountResult {
        if (intent.scope != authority.scope) return CycleCountResult.ContextInvalidated
        val before = authorize(authority, COUNT_WRITE_PERMISSIONS)
        if (before !is Authorization.Current) return before.toCommandFailure()
        val result = try {
            cycleCounts.record(
                CycleCountCountCommand(
                    idempotencyKey = intent.idempotencyKey,
                    lotId = intent.lot.id,
                    warehouseId = intent.lot.warehouseId,
                    zoneId = intent.lot.zoneId,
                    lotVersion = intent.lot.version,
                    expectedQuantityText = intent.lot.onHandText,
                    observedQuantityText = intent.observedQuantityText,
                    unit = intent.lot.unit,
                    membershipId = authority.scope.membershipId,
                    frozenBody = intent.frozenBody
                )
            ).toFeatureResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CycleCountResult.UnknownOutcome
        }
        return if (currentAfter(authority, before.lease, COUNT_WRITE_PERMISSIONS)) {
            result
        } else {
            when (authorityDrift(authority)) {
                CycleCountLookupResult.SessionInvalidated -> CycleCountResult.SessionInvalidated
                else -> CycleCountResult.ContextInvalidated
            }
        }
    }

    override suspend fun applyCorrection(
        intent: CycleCountCorrectionIntent,
        authority: CycleCountAuthority
    ): CycleCountResult {
        if (intent.scope != authority.scope) return CycleCountResult.ContextInvalidated
        val before = authorize(authority, CORRECTION_PERMISSIONS)
        if (before !is Authorization.Current) return before.toCommandFailure()
        val result = try {
            cycleCounts.applyCorrection(
                CycleCountCorrectionCommand(
                    idempotencyKey = intent.idempotencyKey,
                    countId = intent.count.id,
                    lotId = intent.count.lotId,
                    warehouseId = intent.count.warehouseId,
                    zoneId = intent.count.zoneId,
                    expectedLotVersion = intent.expectedLotVersion,
                    expectedQuantityText = intent.count.expectedQuantityText,
                    observedQuantityText = intent.count.observedQuantityText,
                    unit = intent.count.unit,
                    membershipId = authority.scope.membershipId
                )
            ).toFeatureResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CycleCountResult.UnknownOutcome
        }
        return if (currentAfter(authority, before.lease, CORRECTION_PERMISSIONS)) {
            result
        } else {
            when (authorityDrift(authority)) {
                CycleCountLookupResult.SessionInvalidated -> CycleCountResult.SessionInvalidated
                else -> CycleCountResult.ContextInvalidated
            }
        }
    }

    private suspend fun authorize(
        authority: CycleCountAuthority,
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
        val allowed = if (required ==
            LOT_READ_PERMISSIONS
        ) {
            authority.permissions.any(required::contains)
        } else {
            authority.permissions.containsAll(required)
        }
        return if (allowed) Authorization.Current(lease) else Authorization.PermissionDenied
    }

    private suspend fun currentAfter(
        authority: CycleCountAuthority,
        originalLease: AccessTokenLease,
        required: Set<String>
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(originalLease.epoch)
        ) {
            return false
        }
        val current = sessions.verifiedSession.value ?: return false
        val allowed = if (required ==
            LOT_READ_PERMISSIONS
        ) {
            authority.permissions.any(required::contains)
        } else {
            authority.permissions.containsAll(required)
        }
        return current.matches(authority) && allowed
    }

    private suspend fun authorityDrift(authority: CycleCountAuthority): CycleCountLookupResult =
        when {
            sessions.sessionState.value != SessionState.Active ->
                CycleCountLookupResult.SessionInvalidated

            sessions.verifiedSession.value?.matches(
                authority
            ) == true -> CycleCountLookupResult.SessionInvalidated

            else -> CycleCountLookupResult.ContextInvalidated
        }

    private fun VerifiedSession.matches(authority: CycleCountAuthority): Boolean =
        hasAuthorizedContext && userId == authority.scope.userId &&
            tenantId == authority.scope.tenantId &&
            workspaceId == authority.scope.workspaceId &&
            membershipId == authority.scope.membershipId &&
            permissions == authority.permissions

    private fun Authorization.toLookupFailure(): CycleCountLookupResult = when (this) {
        Authorization.SessionInvalidated -> CycleCountLookupResult.SessionInvalidated
        Authorization.ContextInvalidated -> CycleCountLookupResult.ContextInvalidated
        Authorization.PermissionDenied -> CycleCountLookupResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a lookup failure")
    }

    private fun Authorization.toCommandFailure(): CycleCountResult = when (this) {
        Authorization.SessionInvalidated -> CycleCountResult.SessionInvalidated
        Authorization.ContextInvalidated -> CycleCountResult.ContextInvalidated
        Authorization.PermissionDenied -> CycleCountResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a command failure")
    }

    private fun CycleCountNetworkOutcome.toFeatureResult(): CycleCountResult = when (this) {
        is CycleCountNetworkOutcome.Recorded -> CycleCountResult.Recorded(
            CycleCountRecord(
                count.id, count.lotId, count.warehouseId, count.zoneId, count.lotVersion,
                count.expectedQuantity.toPlainString(), count.observedQuantity.toPlainString(),
                count.unit, count.status, count.actorMembershipId, count.recordedAt.toString()
            )
        )

        is CycleCountNetworkOutcome.Applied -> CycleCountResult.Applied(
            CycleCountCorrection(
                correction.id, correction.countId, correction.lotId,
                correction.warehouseId, correction.zoneId,
                correction.lotVersionBefore, correction.lotVersionAfter,
                correction.quantityBefore.toPlainString(), correction.quantityAfter.toPlainString(),
                correction.quantityDelta.toPlainString(), correction.unit,
                correction.actorMembershipId, correction.recordedAt.toString()
            )
        )

        is CycleCountNetworkOutcome.Rejected -> CycleCountResult.Rejected(code)

        CycleCountNetworkOutcome.UnknownOutcome -> CycleCountResult.UnknownOutcome

        CycleCountNetworkOutcome.PreconditionFailed -> CycleCountResult.PreconditionFailed

        CycleCountNetworkOutcome.Conflict -> CycleCountResult.Conflict

        CycleCountNetworkOutcome.NetworkUnavailable -> CycleCountResult.NetworkUnavailable

        CycleCountNetworkOutcome.ServiceUnavailable -> CycleCountResult.ServiceUnavailable

        CycleCountNetworkOutcome.PermissionDenied -> CycleCountResult.PermissionDenied

        CycleCountNetworkOutcome.ContextInvalidated -> CycleCountResult.ContextInvalidated

        CycleCountNetworkOutcome.SessionInvalidated -> CycleCountResult.SessionInvalidated

        is CycleCountNetworkOutcome.Lots -> CycleCountResult.ServiceUnavailable
    }

    private fun CycleCountLotProjection.toFeatureLot() = CycleCountLot(
        id, warehouseId, zoneId, catalogItemId, batchNumber, expirationDate.toString(),
        onHand.toPlainString(), reserved.toPlainString(), available.toPlainString(),
        unit, status, version
    )

    private suspend fun safeLookup(
        block: suspend () -> CycleCountLookupResult
    ): CycleCountLookupResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CycleCountLookupResult.ServiceUnavailable
    }

    private sealed interface Authorization {
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
        data class Current(val lease: AccessTokenLease) : Authorization
    }

    private companion object {
        val LOT_READ_PERMISSIONS = setOf("warehouse:read", "warehouse.read", "inventory.read")
        val COUNT_WRITE_PERMISSIONS = setOf("warehouse:write")
        val CORRECTION_PERMISSIONS = setOf("inventory.adjust", "warehouse:write")
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object CycleCountGatewayBindings {
    @Provides
    @Singleton
    fun nexaCycleCountGateway(protectedCalls: ProtectedCallExecutor): NexaCycleCountGateway =
        NexaCycleCountGateway(protectedCalls)

    @Provides
    @Singleton
    fun cycleCountGateway(gateway: OperationsCycleCountGateway): CycleCountGateway = gateway

    @Provides
    @Singleton
    fun cycleCountMetadataStore(backend: CycleCountMetadataBackend): CycleCountMetadataStore =
        AppCycleCountMetadataStore(backend)

    @Provides
    @Singleton
    fun cycleCountMetadataBackend(
        backend: AndroidCycleCountMetadataBackend
    ): CycleCountMetadataBackend = backend
}

internal object CycleCountViewModelBindings {
    fun viewModelFactory(
        gateway: CycleCountGateway,
        metadataStore: CycleCountMetadataStore
    ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CycleCountViewModel::class.java))
            return CycleCountViewModel(gateway, metadataStore) as T
        }
    }
}
