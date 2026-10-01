package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.DriverDeliveryNetworkOutcome
import com.nexa.mobile.operations.core.network.DriverDeliveryProjection
import com.nexa.mobile.operations.core.network.NexaDriverDeliveryGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.delivery.DriverAttemptStartCommand
import com.nexa.mobile.operations.feature.delivery.DriverAttemptStartResult
import com.nexa.mobile.operations.feature.delivery.DriverAttemptMetadataStore
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryAttempt
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryGateway
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryLoadResult
import com.nexa.mobile.operations.feature.delivery.DriverDeliverySnapshot
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** App boundary binds driver reads and starts to full verified scope and session epoch. */
@Singleton
internal class OperationsDriverDeliveryGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val deliveryApi: NexaDriverDeliveryGateway
) : DriverDeliveryGateway {
    override suspend fun assignedDeliveries(
        authority: DriverDeliveryAuthority
    ): DriverDeliveryLoadResult {
        val before = authorize(authority, DRIVER_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLoadFailure()
        val outcome = try {
            deliveryApi.assignedDeliveries()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverDeliveryLoadResult.ServiceUnavailable
        }
        if (!currentAfter(authority, before.lease)) return authorityDrift(authority)
        return when (outcome) {
            is DriverDeliveryNetworkOutcome.Assigned -> DriverDeliveryLoadResult.ListLoaded(
                outcome.items.map { it.toFeature() }
            )

            DriverDeliveryNetworkOutcome.NotFound -> DriverDeliveryLoadResult.NotFound

            else -> outcome.toLoadFailure()
        }
    }

    override suspend fun delivery(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): DriverDeliveryLoadResult {
        val before = authorize(authority, DRIVER_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLoadFailure()
        val outcome = try {
            deliveryApi.delivery(deliveryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverDeliveryLoadResult.ServiceUnavailable
        }
        if (!currentAfter(authority, before.lease)) return authorityDrift(authority)
        return when (outcome) {
            is DriverDeliveryNetworkOutcome.Detail -> DriverDeliveryLoadResult.DetailLoaded(
                outcome.item.toFeature()
            )

            DriverDeliveryNetworkOutcome.NotFound -> DriverDeliveryLoadResult.NotFound

            else -> outcome.toLoadFailure()
        }
    }

    override suspend fun startAttempt(
        command: DriverAttemptStartCommand,
        authority: DriverDeliveryAuthority
    ): DriverAttemptStartResult {
        val before = authorize(authority, DRIVER_START_PERMISSIONS)
        if (before !is Authorization.Current) return before.toStartFailure()
        val outcome = try {
            deliveryApi.startAttempt(
                command.deliveryId,
                command.expectedVersion,
                command.idempotencyKey
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverAttemptStartResult.UnknownOutcome
        }
        // A dispatched mutation followed by authority drift has an unknown server outcome.
        if (!currentAfter(authority, before.lease)) return DriverAttemptStartResult.UnknownOutcome
        return when (outcome) {
            is DriverDeliveryNetworkOutcome.Started -> DriverAttemptStartResult.Started(
                outcome.delivery.toFeature(),
                outcome.attempt.toFeature()
            )

            is DriverDeliveryNetworkOutcome.Rejected -> DriverAttemptStartResult.Rejected(
                outcome.code
            )

            DriverDeliveryNetworkOutcome.StaleVersion -> DriverAttemptStartResult.StaleVersion

            DriverDeliveryNetworkOutcome.NotFound -> DriverAttemptStartResult.NotFound

            DriverDeliveryNetworkOutcome.UnknownOutcome -> DriverAttemptStartResult.UnknownOutcome

            DriverDeliveryNetworkOutcome.NetworkUnavailable -> DriverAttemptStartResult.UnknownOutcome

            DriverDeliveryNetworkOutcome.ServiceUnavailable -> DriverAttemptStartResult.UnknownOutcome

            DriverDeliveryNetworkOutcome.PermissionDenied -> DriverAttemptStartResult.PermissionDenied

            DriverDeliveryNetworkOutcome.ContextInvalidated -> DriverAttemptStartResult.ContextInvalidated

            DriverDeliveryNetworkOutcome.SessionInvalidated -> DriverAttemptStartResult.SessionInvalidated

            is DriverDeliveryNetworkOutcome.Assigned,
            is DriverDeliveryNetworkOutcome.Detail -> DriverAttemptStartResult.ServiceUnavailable
        }
    }

    private suspend fun authorize(
        authority: DriverDeliveryAuthority,
        requiredPermissions: Set<String>
    ): Authorization {
        if (sessions.sessionState.value !=
            SessionState.Active
        ) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (!verified.matches(authority)) return Authorization.ContextInvalidated
        if (authority.permissions.none {
                it in requiredPermissions
            }
        ) {
            return Authorization.PermissionDenied
        }
        return Authorization.Current(lease)
    }

    private suspend fun currentAfter(
        authority: DriverDeliveryAuthority,
        originalLease: AccessTokenLease
    ): Boolean = sessions.sessionState.value == SessionState.Active &&
        sessions.isEpochCurrent(originalLease.epoch) &&
        sessions.verifiedSession.value?.matches(authority) == true

    private suspend fun authorityDrift(
        authority: DriverDeliveryAuthority
    ): DriverDeliveryLoadResult = if (sessions.sessionState.value != SessionState.Active) {
        DriverDeliveryLoadResult.SessionInvalidated
    } else if (sessions.verifiedSession.value?.matches(authority) == true) {
        DriverDeliveryLoadResult.SessionInvalidated
    } else {
        DriverDeliveryLoadResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(authority: DriverDeliveryAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private fun Authorization.toLoadFailure(): DriverDeliveryLoadResult = when (this) {
        Authorization.SessionInvalidated -> DriverDeliveryLoadResult.SessionInvalidated
        Authorization.ContextInvalidated -> DriverDeliveryLoadResult.ContextInvalidated
        Authorization.PermissionDenied -> DriverDeliveryLoadResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Authorization.toStartFailure(): DriverAttemptStartResult = when (this) {
        Authorization.SessionInvalidated -> DriverAttemptStartResult.SessionInvalidated
        Authorization.ContextInvalidated -> DriverAttemptStartResult.ContextInvalidated
        Authorization.PermissionDenied -> DriverAttemptStartResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun DriverDeliveryNetworkOutcome.toLoadFailure(): DriverDeliveryLoadResult =
        when (this) {
            DriverDeliveryNetworkOutcome.NetworkUnavailable -> DriverDeliveryLoadResult.NetworkUnavailable
            DriverDeliveryNetworkOutcome.PermissionDenied -> DriverDeliveryLoadResult.PermissionDenied
            DriverDeliveryNetworkOutcome.ContextInvalidated -> DriverDeliveryLoadResult.ContextInvalidated
            DriverDeliveryNetworkOutcome.SessionInvalidated -> DriverDeliveryLoadResult.SessionInvalidated
            DriverDeliveryNetworkOutcome.NotFound -> DriverDeliveryLoadResult.NotFound
            else -> DriverDeliveryLoadResult.ServiceUnavailable
        }

    private fun DriverDeliveryProjection.toFeature() = DriverDeliverySnapshot(
        id = id,
        fulfillmentId = fulfillmentId,
        salesOrderId = salesOrderId,
        status = status,
        destination = destinationSnapshot,
        scheduledAt = scheduledAt,
        dispatchedAt = dispatchedAt,
        deliveredAt = deliveredAt,
        updatedAt = updatedAt,
        version = version,
        activeAttempt = activeAttempt?.toFeature()
    )

    private fun com.nexa.mobile.operations.core.network.DriverDeliveryAttemptProjection.toFeature() =
        DriverDeliveryAttempt(id, attemptNumber, status, startedByMembershipId, startedAt)

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        val DRIVER_READ_PERMISSIONS = setOf("dispatch.read", "logistics:read")
        val DRIVER_START_PERMISSIONS = setOf("dispatch.start_route", "logistics:write")
    }
}

/** Integration entry for Root without feature dependencies on auth, network, or Hilt. */
internal class DriverDeliveryGatewayBindings @Inject constructor(
    private val gateway: OperationsDriverDeliveryGateway,
    private val metadataStore: DriverAttemptMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DriverDeliveryViewModel::class.java))
            return DriverDeliveryViewModel(gateway, metadataStore) as T
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DriverDeliveryGatewayModule {
    @Provides
    @Singleton
    fun driverDeliveryGateway(protectedCalls: ProtectedCallExecutor) =
        NexaDriverDeliveryGateway(protectedCalls)
}
