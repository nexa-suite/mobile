package com.nexa.mobile.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.NexaReceivingGateway
import com.nexa.mobile.operations.core.network.NexaStockConditionGateway
import com.nexa.mobile.operations.core.network.NexaTemperatureEvidenceGateway
import com.nexa.mobile.operations.core.network.ReceivingNetworkOutcome
import com.nexa.mobile.operations.core.network.StockConditionNetworkOutcome
import com.nexa.mobile.operations.core.network.TemperatureEvidenceCommandWire
import com.nexa.mobile.operations.core.network.TemperatureEvidenceNetworkOutcome
import com.nexa.mobile.operations.core.network.TemperatureSubjectTypeWire
import com.nexa.mobile.operations.core.network.TemperatureUnitWire
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.warehouse.TemperatureEvidenceAuthority
import com.nexa.mobile.operations.feature.warehouse.TemperatureEvidenceFacts
import com.nexa.mobile.operations.feature.warehouse.TemperatureEvidenceGateway
import com.nexa.mobile.operations.feature.warehouse.TemperatureEvidenceMetadataStore
import com.nexa.mobile.operations.feature.warehouse.TemperatureEvidencePayload
import com.nexa.mobile.operations.feature.warehouse.TemperatureEvidenceSubject
import com.nexa.mobile.operations.feature.warehouse.TemperatureEvidenceSubjectType
import com.nexa.mobile.operations.feature.warehouse.TemperatureEvidenceUnit
import com.nexa.mobile.operations.feature.warehouse.TemperatureEvidenceViewModel
import com.nexa.mobile.operations.feature.warehouse.TemperatureLookupResult
import com.nexa.mobile.operations.feature.warehouse.TemperatureSubmitResult
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
internal class OperationsTemperatureEvidenceGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val receiving: NexaReceivingGateway,
    private val stockCondition: NexaStockConditionGateway,
    private val temperature: NexaTemperatureEvidenceGateway
) : TemperatureEvidenceGateway {
    override suspend fun subjects(
        type: TemperatureEvidenceSubjectType,
        authority: TemperatureEvidenceAuthority
    ): TemperatureLookupResult {
        val before = authorize(authority, SUBJECT_LOOKUP_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLookupFailure()
        val result = try {
            when (type) {
                TemperatureEvidenceSubjectType.WAREHOUSE -> receiving.warehouses().toSubjects()
                TemperatureEvidenceSubjectType.LOT -> stockCondition.lots().toSubjects()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TemperatureLookupResult.ServiceUnavailable
        }
        return if (currentAfter(authority, before.lease)) result else authorityDriftLookup(authority)
    }

    override suspend fun record(
        payload: TemperatureEvidencePayload,
        idempotencyKey: String,
        authority: TemperatureEvidenceAuthority
    ): TemperatureSubmitResult {
        val before = authorize(authority, RECEIPT_PERMISSIONS)
        if (before !is Authorization.Current) return before.toSubmitFailure()
        val result = try {
            temperature.record(
                TemperatureEvidenceCommandWire(
                    subjectType = when (payload.subjectType) {
                        TemperatureEvidenceSubjectType.LOT -> TemperatureSubjectTypeWire.LOT
                        TemperatureEvidenceSubjectType.WAREHOUSE -> TemperatureSubjectTypeWire.WAREHOUSE
                    },
                    subjectId = payload.subjectId,
                    value = payload.value,
                    unit = when (payload.unit) {
                        TemperatureEvidenceUnit.CELSIUS -> TemperatureUnitWire.CELSIUS
                        TemperatureEvidenceUnit.FAHRENHEIT -> TemperatureUnitWire.FAHRENHEIT
                    },
                    occurredAt = java.time.Instant.parse(payload.occurredAt)
                ),
                idempotencyKey,
                authority.membershipId
            ).toFeatureResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TemperatureSubmitResult.UnknownOutcome
        }
        return if (currentAfter(authority, before.lease)) result else when (
            authorityDriftLookup(authority)
        ) {
            TemperatureLookupResult.SessionInvalidated -> TemperatureSubmitResult.SessionInvalidated
            else -> TemperatureSubmitResult.ContextInvalidated
        }
    }

    private suspend fun authorize(
        authority: TemperatureEvidenceAuthority,
        permissions: Set<String>
    ): Authorization {
        if (sessions.sessionState.value != SessionState.Active) return Authorization.SessionInvalidated
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (authority.authorityEpoch <= 0 || !verified.matches(authority)) {
            return Authorization.ContextInvalidated
        }
        return if (authority.permissions.any(permissions::contains)) Authorization.Current(lease)
        else Authorization.PermissionDenied
    }

    private suspend fun currentAfter(
        authority: TemperatureEvidenceAuthority,
        originalLease: AccessTokenLease
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(originalLease.epoch)
        ) return false
        return sessions.verifiedSession.value?.matches(authority) == true
    }

    private suspend fun authorityDriftLookup(
        authority: TemperatureEvidenceAuthority
    ): TemperatureLookupResult = when {
        sessions.sessionState.value != SessionState.Active -> TemperatureLookupResult.SessionInvalidated
        sessions.verifiedSession.value?.matches(authority) == true ->
            TemperatureLookupResult.SessionInvalidated
        else -> TemperatureLookupResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(authority: TemperatureEvidenceAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private fun Authorization.toLookupFailure(): TemperatureLookupResult = when (this) {
        Authorization.SessionInvalidated -> TemperatureLookupResult.SessionInvalidated
        Authorization.ContextInvalidated -> TemperatureLookupResult.ContextInvalidated
        Authorization.PermissionDenied -> TemperatureLookupResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Authorization.toSubmitFailure(): TemperatureSubmitResult = when (this) {
        Authorization.SessionInvalidated -> TemperatureSubmitResult.SessionInvalidated
        Authorization.ContextInvalidated -> TemperatureSubmitResult.ContextInvalidated
        Authorization.PermissionDenied -> TemperatureSubmitResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun ReceivingNetworkOutcome.toSubjects(): TemperatureLookupResult = when (this) {
        is ReceivingNetworkOutcome.Warehouses -> TemperatureLookupResult.Subjects(
            items.map {
                TemperatureEvidenceSubject(
                    id = it.id,
                    type = TemperatureEvidenceSubjectType.WAREHOUSE,
                    primaryLabel = "${it.name} · ${it.code}",
                    detailLabel = it.status
                )
            }
        )

        ReceivingNetworkOutcome.NetworkUnavailable -> TemperatureLookupResult.NetworkUnavailable
        ReceivingNetworkOutcome.PermissionDenied -> TemperatureLookupResult.PermissionDenied
        ReceivingNetworkOutcome.ContextInvalidated -> TemperatureLookupResult.ContextInvalidated
        ReceivingNetworkOutcome.SessionInvalidated -> TemperatureLookupResult.SessionInvalidated
        else -> TemperatureLookupResult.ServiceUnavailable
    }

    private fun StockConditionNetworkOutcome.toSubjects(): TemperatureLookupResult = when (this) {
        is StockConditionNetworkOutcome.Lots -> TemperatureLookupResult.Subjects(
            items.map {
                TemperatureEvidenceSubject(
                    id = it.id,
                    type = TemperatureEvidenceSubjectType.LOT,
                    primaryLabel = "${it.batchNumber} · ${it.status}",
                    detailLabel = "Warehouse ${it.warehouseId} · zone ${it.zoneId} · " +
                        "expires ${it.expirationDate}"
                )
            }
        )

        StockConditionNetworkOutcome.NetworkUnavailable -> TemperatureLookupResult.NetworkUnavailable
        StockConditionNetworkOutcome.PermissionDenied -> TemperatureLookupResult.PermissionDenied
        StockConditionNetworkOutcome.ContextInvalidated -> TemperatureLookupResult.ContextInvalidated
        StockConditionNetworkOutcome.SessionInvalidated -> TemperatureLookupResult.SessionInvalidated
        else -> TemperatureLookupResult.ServiceUnavailable
    }

    private fun TemperatureEvidenceNetworkOutcome.toFeatureResult(): TemperatureSubmitResult =
        when (this) {
            is TemperatureEvidenceNetworkOutcome.Confirmed -> TemperatureSubmitResult.Confirmed(
                TemperatureEvidenceFacts(
                    id = response.id,
                    subjectType = when (response.subjectType) {
                        TemperatureSubjectTypeWire.LOT -> TemperatureEvidenceSubjectType.LOT
                        TemperatureSubjectTypeWire.WAREHOUSE -> TemperatureEvidenceSubjectType.WAREHOUSE
                    },
                    subjectId = response.subjectId,
                    lotId = response.lotId,
                    warehouseId = response.warehouseId,
                    value = response.value,
                    unit = when (response.unit) {
                        TemperatureUnitWire.CELSIUS -> TemperatureEvidenceUnit.CELSIUS
                        TemperatureUnitWire.FAHRENHEIT -> TemperatureEvidenceUnit.FAHRENHEIT
                    },
                    occurredAt = response.occurredAt,
                    actorMembershipId = response.actorMembershipId,
                    status = response.status,
                    source = response.source
                )
            )

            is TemperatureEvidenceNetworkOutcome.Rejected -> TemperatureSubmitResult.Rejected(code)
            TemperatureEvidenceNetworkOutcome.UnknownOutcome,
            TemperatureEvidenceNetworkOutcome.NetworkUnavailable -> TemperatureSubmitResult.UnknownOutcome
            TemperatureEvidenceNetworkOutcome.ServiceUnavailable -> TemperatureSubmitResult.ServiceUnavailable
            TemperatureEvidenceNetworkOutcome.PermissionDenied -> TemperatureSubmitResult.PermissionDenied
            TemperatureEvidenceNetworkOutcome.ContextInvalidated -> TemperatureSubmitResult.ContextInvalidated
            TemperatureEvidenceNetworkOutcome.SessionInvalidated -> TemperatureSubmitResult.SessionInvalidated
        }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        val SUBJECT_LOOKUP_PERMISSIONS = setOf("warehouse.read", "inventory.read", "warehouse:read")
        val RECEIPT_PERMISSIONS = setOf("inventory.receive")
    }
}

internal class TemperatureEvidenceGatewayBindings @Inject constructor(
    private val gateway: OperationsTemperatureEvidenceGateway,
    private val metadataStore: TemperatureEvidenceMetadataStore
) {
    fun viewModelFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TemperatureEvidenceViewModel::class.java))
            return TemperatureEvidenceViewModel(gateway, metadataStore) as T
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object TemperatureEvidenceGatewayModule {
    @Provides
    @Singleton
    fun temperatureEvidenceGateway(protectedCalls: ProtectedCallExecutor):
        NexaTemperatureEvidenceGateway = NexaTemperatureEvidenceGateway(protectedCalls)
}
