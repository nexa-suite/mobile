package com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters

import android.content.Context
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.model.warehouse.PickingAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.application.publicapi.CurrentPickingAllocationQuery
import com.nexa.mobile.operations.fulfillmentdelivery.application.publicapi.CurrentPickingAllocationResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionCurrentResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionIntent
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionIntentStatus
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionMetadataRead
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionMetadataWrite
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.LotSubstitutionScopeIdentity
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.LotSubstitutionGateway
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.LotSubstitutionMetadataStore
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionAlternative
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionCurrentFacts
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionRequest
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.LotSubstitutionWork
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.LotSubstitutionCommandNetwork
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.LotSubstitutionNetworkOutcome as LotSubstitutionOutcome
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.LotSubstitutionRequestNetworkProjection
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.NexaLotSubstitutionGateway
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.NexaStockConditionGateway
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.StockConditionNetworkOutcome as StockConditionOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface LotSubstitutionMetadataBackend {
    suspend fun load(scope: ScopedMetadataScope): ScopedMetadataRead
    suspend fun save(scope: ScopedMetadataScope, payload: String): Boolean
    suspend fun clear(scope: ScopedMetadataScope): Boolean
}

@Singleton
class AndroidLotSubstitutionMetadataBackend @Inject constructor(
    @ApplicationContext context: Context
) : LotSubstitutionMetadataBackend {
    private val store =
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.LotSubstitutionRequest)
    override suspend fun load(scope: ScopedMetadataScope): ScopedMetadataRead = store.load(scope)
    override suspend fun save(scope: ScopedMetadataScope, payload: String): Boolean =
        store.save(scope, payload)
    override suspend fun clear(scope: ScopedMetadataScope): Boolean = store.clear(scope)
}

/** Encrypted local metadata retains only the exact command necessary for manual recovery. */
class AppLotSubstitutionMetadataStore(private val backend: LotSubstitutionMetadataBackend) :
    LotSubstitutionMetadataStore {
    override suspend fun load(scope: LotSubstitutionScopeIdentity): LotSubstitutionMetadataRead =
        locked(scope) {
            read(scope)
        } ?: LotSubstitutionMetadataRead.Unavailable

    override suspend fun freeze(intent: LotSubstitutionIntent): LotSubstitutionMetadataWrite =
        locked(intent.scope) {
            if (!intent.isValid()) return@locked LotSubstitutionMetadataWrite.Unavailable
            when (val existing = read(intent.scope)) {
                LotSubstitutionMetadataRead.Unavailable -> LotSubstitutionMetadataWrite.Unavailable

                is LotSubstitutionMetadataRead.Available -> {
                    val current = existing.value
                    if (current != null && !current.hasSameFrozenCommand(intent)) {
                        LotSubstitutionMetadataWrite.Unavailable
                    } else {
                        write(intent.copy(status = current?.status ?: intent.status))
                    }
                }
            }
        } ?: LotSubstitutionMetadataWrite.Unavailable

    override suspend fun markUnknown(
        scope: LotSubstitutionScopeIdentity,
        idempotencyKey: String
    ): LotSubstitutionMetadataWrite = locked(scope) {
        val current = (read(scope) as? LotSubstitutionMetadataRead.Available)?.value
            ?: return@locked LotSubstitutionMetadataWrite.Unavailable
        if (current.idempotencyKey !=
            idempotencyKey
        ) {
            return@locked LotSubstitutionMetadataWrite.Unavailable
        }
        write(current.copy(status = LotSubstitutionIntentStatus.UnknownOutcome))
    } ?: LotSubstitutionMetadataWrite.Unavailable

    override suspend fun clear(
        scope: LotSubstitutionScopeIdentity,
        idempotencyKey: String
    ): LotSubstitutionMetadataWrite = locked(scope) {
        val current = read(scope)
        if (current !is LotSubstitutionMetadataRead.Available ||
            current.value?.idempotencyKey != idempotencyKey
        ) {
            return@locked LotSubstitutionMetadataWrite.Unavailable
        }
        if (safeBackend { backend.clear(scope.toBackendScope()) } == true) {
            LotSubstitutionMetadataWrite.Saved
        } else {
            LotSubstitutionMetadataWrite.Unavailable
        }
    } ?: LotSubstitutionMetadataWrite.Unavailable

    private suspend fun read(scope: LotSubstitutionScopeIdentity): LotSubstitutionMetadataRead =
        when (val result = safeBackend { backend.load(scope.toBackendScope()) }) {
            null -> LotSubstitutionMetadataRead.Unavailable

            ScopedMetadataRead.Unavailable -> LotSubstitutionMetadataRead.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload = result.payload ?: return LotSubstitutionMetadataRead.Available(null)
                try {
                    val wire = substitutionJson.decodeFromString<LotSubstitutionIntentWire>(payload)
                    val value = wire.toIntent()
                    if (wire.matches(scope) &&
                        value.isValid()
                    ) {
                        LotSubstitutionMetadataRead.Available(value)
                    } else {
                        LotSubstitutionMetadataRead.Unavailable
                    }
                } catch (_: SerializationException) {
                    LotSubstitutionMetadataRead.Unavailable
                } catch (_: IllegalArgumentException) {
                    LotSubstitutionMetadataRead.Unavailable
                }
            }
        }

    private suspend fun write(intent: LotSubstitutionIntent): LotSubstitutionMetadataWrite = try {
        if (safeBackend {
                backend.save(
                    intent.scope.toBackendScope(),
                    substitutionJson.encodeToString(intent.toWire())
                )
            } == true
        ) {
            LotSubstitutionMetadataWrite.Saved
        } else {
            LotSubstitutionMetadataWrite.Unavailable
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        LotSubstitutionMetadataWrite.Unavailable
    }

    private suspend fun <T> safeBackend(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun <T> locked(
        scope: LotSubstitutionScopeIdentity,
        block: suspend () -> T
    ): T? = locks.getOrPut(scope) { Mutex() }.withLock {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun LotSubstitutionScopeIdentity.toBackendScope() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun LotSubstitutionIntent.isValid(): Boolean {
        val quantity = work.preparedQuantityText.toBigDecimalOrNull() ?: return false
        return scope.userId.isNotBlank() && scope.tenantId.isNotBlank() &&
            scope.workspaceId.isNotBlank() &&
            scope.membershipId.isNotBlank() && idempotencyKey.isNotBlank() &&
            idempotencyKey.length <= 160 &&
            work.fulfillmentId.isUuid() && work.allocationId.isUuid() &&
            work.allocationLineId.isUuid() &&
            work.skuId.isUuid() && work.expectedLotId.isUuid() && work.warehouseId.isUuid() &&
            work.zoneId?.let { it.isUuid() } != false && work.catalogItemId.isNotBlank() &&
            quantity.signum() > 0 && work.allocationVersion >= 0 && work.unit.isNotBlank() &&
            alternativeLotId.isUuid() && alternativeLotId != work.expectedLotId &&
            reason.isNotBlank() && reason == reason.trim() && reason.length <= 2_000 &&
            reason.none(Char::isISOControl) && frozenBody.isNotBlank() && frozenBody.length <= 4_096
    }

    private fun LotSubstitutionIntent.toWire() = LotSubstitutionIntentWire(
        schemaVersion = 1,
        userId = scope.userId,
        tenantId = scope.tenantId,
        workspaceId = scope.workspaceId,
        membershipId = scope.membershipId,
        idempotencyKey = idempotencyKey,
        fulfillmentId = work.fulfillmentId,
        allocationId = work.allocationId,
        allocationLineId = work.allocationLineId,
        skuId = work.skuId,
        catalogItemId = work.catalogItemId,
        expectedLotId = work.expectedLotId,
        warehouseId = work.warehouseId,
        zoneId = work.zoneId,
        preparedQuantityText = work.preparedQuantityText,
        unit = work.unit,
        allocationVersion = work.allocationVersion,
        alternativeLotId = alternativeLotId,
        reason = reason,
        frozenBody = frozenBody,
        status = status.name
    )

    private fun LotSubstitutionIntentWire.toIntent() = LotSubstitutionIntent(
        scope = LotSubstitutionScopeIdentity(userId, tenantId, workspaceId, membershipId),
        idempotencyKey = idempotencyKey,
        work = LotSubstitutionWork(
            fulfillmentId, allocationId, allocationLineId, skuId, catalogItemId, expectedLotId,
            warehouseId, zoneId, preparedQuantityText, unit, allocationVersion
        ),
        alternativeLotId = alternativeLotId,
        reason = reason,
        frozenBody = frozenBody,
        status = LotSubstitutionIntentStatus.valueOf(status)
    )

    private fun LotSubstitutionIntentWire.matches(scope: LotSubstitutionScopeIdentity): Boolean =
        schemaVersion == 1 && userId == scope.userId && tenantId == scope.tenantId &&
            workspaceId == scope.workspaceId && membershipId == scope.membershipId

    private fun LotSubstitutionIntent.hasSameFrozenCommand(other: LotSubstitutionIntent): Boolean =
        scope == other.scope && idempotencyKey == other.idempotencyKey && work == other.work &&
            alternativeLotId == other.alternativeLotId && reason == other.reason &&
            frozenBody == other.frozenBody

    private fun String.isUuid(): Boolean = UUID_PATTERN.matches(this)

    private companion object {
        val locks = ConcurrentHashMap<LotSubstitutionScopeIdentity, Mutex>()
        val UUID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val substitutionJson = Json { ignoreUnknownKeys = false }
    }
}

@Serializable
private data class LotSubstitutionIntentWire(
    val schemaVersion: Int,
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String,
    val idempotencyKey: String,
    val fulfillmentId: String,
    val allocationId: String,
    val allocationLineId: String,
    val skuId: String,
    val catalogItemId: String,
    val expectedLotId: String,
    val warehouseId: String,
    val zoneId: String?,
    val preparedQuantityText: String,
    val unit: String,
    val allocationVersion: Long,
    val alternativeLotId: String,
    val reason: String,
    val frozenBody: String,
    val status: String
)

@Singleton
class OperationsLotSubstitutionGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val stockCondition: NexaStockConditionGateway,
    private val substitutions: NexaLotSubstitutionGateway,
    private val pickingAllocation: CurrentPickingAllocationQuery
) : LotSubstitutionGateway {
    override suspend fun alternatives(
        work: LotSubstitutionWork,
        authority: LotSubstitutionAuthority
    ): LotSubstitutionLookupResult {
        val before = authorize(authority, LOOKUP_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLookupFailure()
        val outcome = safeLookup { stockCondition.lots() }
        val mapped = when (outcome) {
            is StockConditionOutcome.Lots -> LotSubstitutionLookupResult.Alternatives(
                outcome.items.map { lot ->
                    LotSubstitutionAlternative(
                        lot.id, lot.warehouseId, lot.zoneId, lot.skuId,
                        lot.catalogItemId, lot.batchNumber,
                        lot.expirationDate.toString(), lot.physicalRemaining.toPlainString(),
                        lot.unit, lot.status, lot.version
                    )
                }
            )

            StockConditionOutcome.NetworkUnavailable ->
                LotSubstitutionLookupResult.NetworkUnavailable

            StockConditionOutcome.PermissionDenied ->
                LotSubstitutionLookupResult.PermissionDenied

            StockConditionOutcome.ContextInvalidated ->
                LotSubstitutionLookupResult.ContextInvalidated

            StockConditionOutcome.SessionInvalidated ->
                LotSubstitutionLookupResult.SessionInvalidated

            else -> LotSubstitutionLookupResult.ServiceUnavailable
        }
        return if (currentAfter(
                authority,
                before.lease,
                LOOKUP_PERMISSIONS
            )
        ) {
            mapped
        } else {
            authorityDrift(authority)
        }
    }

    override suspend fun request(
        intent: LotSubstitutionIntent,
        authority: LotSubstitutionAuthority
    ): LotSubstitutionResult {
        if (intent.scope != authority.scope) return LotSubstitutionResult.ContextInvalidated
        val before = authorize(authority, SUBSTITUTION_PERMISSIONS)
        if (before !is Authorization.Current) return before.toCommandFailure()
        val outcome = try {
            substitutions.request(
                LotSubstitutionCommandNetwork(
                    idempotencyKey = intent.idempotencyKey,
                    allocationVersion = intent.work.allocationVersion,
                    fulfillmentId = intent.work.fulfillmentId,
                    allocationId = intent.work.allocationId,
                    allocationLineId = intent.work.allocationLineId,
                    expectedLotId = intent.work.expectedLotId,
                    alternativeLotId = intent.alternativeLotId,
                    quantityText = intent.work.preparedQuantityText,
                    unit = intent.work.unit,
                    reason = intent.reason,
                    frozenBody = intent.frozenBody
                )
            ).toFeatureResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LotSubstitutionResult.UnknownOutcome
        }
        return if (currentAfter(
                authority,
                before.lease,
                SUBSTITUTION_PERMISSIONS
            )
        ) {
            outcome
        } else {
            authorityDriftCommand(authority)
        }
    }

    override suspend fun currentAllocation(
        work: LotSubstitutionWork,
        authority: LotSubstitutionAuthority
    ): LotSubstitutionCurrentResult {
        val before = authorize(authority, FULFILLMENT_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toCurrentFailure()
        val loaded = try {
            pickingAllocation.current(work.fulfillmentId, authority.toPickingAuthority())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LotSubstitutionCurrentResult.ServiceUnavailable
        }
        if (!currentAfter(
                authority,
                before.lease,
                FULFILLMENT_READ_PERMISSIONS
            )
        ) {
            return authorityDriftCurrent(authority)
        }
        return when (loaded) {
            is CurrentPickingAllocationResult.Loaded -> {
                val allocation = loaded.allocation
                if (allocation.allocationId != work.allocationId) {
                    LotSubstitutionCurrentResult.Current(
                        LotSubstitutionCurrentFacts(
                            allocation.allocationId,
                            allocation.version,
                            null,
                            null,
                            null
                        )
                    )
                } else {
                    val line = allocation.lines.singleOrNull {
                        it.physicalAllocationLineId ==
                            work.allocationLineId
                    }
                    LotSubstitutionCurrentResult.Current(
                        LotSubstitutionCurrentFacts(
                            allocation.allocationId,
                            allocation.version,
                            line?.lotId,
                            line?.remainingQuantity?.toPlainString(),
                            line?.unit
                        )
                    )
                }
            }

            CurrentPickingAllocationResult.NotFound -> LotSubstitutionCurrentResult.NotFound

            CurrentPickingAllocationResult.NetworkUnavailable ->
                LotSubstitutionCurrentResult.NetworkUnavailable

            CurrentPickingAllocationResult.PermissionDenied ->
                LotSubstitutionCurrentResult.PermissionDenied

            CurrentPickingAllocationResult.ContextInvalidated ->
                LotSubstitutionCurrentResult.ContextInvalidated

            CurrentPickingAllocationResult.SessionInvalidated ->
                LotSubstitutionCurrentResult.SessionInvalidated

            CurrentPickingAllocationResult.ServiceUnavailable ->
                LotSubstitutionCurrentResult.ServiceUnavailable
        }
    }

    private fun LotSubstitutionAuthority.toPickingAuthority() = PickingAuthority(
        userId = userId,
        tenantId = tenantId,
        workspaceId = workspaceId,
        membershipId = membershipId,
        permissions = permissions,
        authorityEpoch = authorityEpoch
    )

    private suspend fun authorize(
        authority: LotSubstitutionAuthority,
        required: Set<String>
    ): Authorization {
        if (sessions.sessionState.value !=
            SessionState.Active
        ) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val session = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (!session.matches(authority)) return Authorization.ContextInvalidated
        if (authority.permissions.none(required::contains)) return Authorization.PermissionDenied
        return Authorization.Current(lease)
    }

    private suspend fun currentAfter(
        authority: LotSubstitutionAuthority,
        lease: AccessTokenLease,
        required: Set<String>
    ): Boolean = sessions.sessionState.value == SessionState.Active && sessions.isEpochCurrent(
        lease.epoch
    ) &&
        sessions.verifiedSession.value?.matches(authority) == true &&
        authority.permissions.any(required::contains)

    private fun VerifiedSession.matches(authority: LotSubstitutionAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private suspend fun authorityDrift(
        authority: LotSubstitutionAuthority
    ): LotSubstitutionLookupResult = when {
        sessions.sessionState.value != SessionState.Active ->
            LotSubstitutionLookupResult.SessionInvalidated

        sessions.verifiedSession.value?.matches(
            authority
        ) == true -> LotSubstitutionLookupResult.SessionInvalidated

        else -> LotSubstitutionLookupResult.ContextInvalidated
    }

    private suspend fun authorityDriftCommand(
        authority: LotSubstitutionAuthority
    ): LotSubstitutionResult = when {
        sessions.sessionState.value != SessionState.Active ->
            LotSubstitutionResult.SessionInvalidated

        sessions.verifiedSession.value?.matches(
            authority
        ) == true -> LotSubstitutionResult.SessionInvalidated

        else -> LotSubstitutionResult.ContextInvalidated
    }

    private suspend fun authorityDriftCurrent(
        authority: LotSubstitutionAuthority
    ): LotSubstitutionCurrentResult = when {
        sessions.sessionState.value != SessionState.Active ->
            LotSubstitutionCurrentResult.SessionInvalidated

        sessions.verifiedSession.value?.matches(
            authority
        ) == true -> LotSubstitutionCurrentResult.SessionInvalidated

        else -> LotSubstitutionCurrentResult.ContextInvalidated
    }

    private suspend fun safeLookup(
        block: suspend () -> StockConditionOutcome
    ): StockConditionOutcome = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        StockConditionOutcome.ServiceUnavailable
    }

    private fun LotSubstitutionOutcome.toFeatureResult(): LotSubstitutionResult = when (this) {
        is LotSubstitutionOutcome.Requested -> LotSubstitutionResult.Requested(
            request.toFeatureRequest()
        )

        is LotSubstitutionOutcome.Rejected -> LotSubstitutionResult.Rejected(code)

        is LotSubstitutionOutcome.Stale -> LotSubstitutionResult.Stale(
            currentAllocationVersion
        )

        LotSubstitutionOutcome.Conflict -> LotSubstitutionResult.Conflict

        LotSubstitutionOutcome.UnknownOutcome -> LotSubstitutionResult.UnknownOutcome

        LotSubstitutionOutcome.NetworkUnavailable ->
            LotSubstitutionResult.NetworkUnavailable

        LotSubstitutionOutcome.ServiceUnavailable ->
            LotSubstitutionResult.ServiceUnavailable

        LotSubstitutionOutcome.PermissionDenied -> LotSubstitutionResult.PermissionDenied

        LotSubstitutionOutcome.ContextInvalidated ->
            LotSubstitutionResult.ContextInvalidated

        LotSubstitutionOutcome.SessionInvalidated ->
            LotSubstitutionResult.SessionInvalidated
    }

    private fun LotSubstitutionRequestNetworkProjection.toFeatureRequest() = LotSubstitutionRequest(
        id,
        expectedLotId,
        alternativeLotId,
        quantity.toPlainString(),
        reason,
        status,
        currentAllocationVersion
    )

    private fun Authorization.toLookupFailure() = when (this) {
        Authorization.SessionInvalidated -> LotSubstitutionLookupResult.SessionInvalidated
        Authorization.ContextInvalidated -> LotSubstitutionLookupResult.ContextInvalidated
        Authorization.PermissionDenied -> LotSubstitutionLookupResult.PermissionDenied
        is Authorization.Current -> error("authorized state is not a failure")
    }

    private fun Authorization.toCommandFailure() = when (this) {
        Authorization.SessionInvalidated -> LotSubstitutionResult.SessionInvalidated
        Authorization.ContextInvalidated -> LotSubstitutionResult.ContextInvalidated
        Authorization.PermissionDenied -> LotSubstitutionResult.PermissionDenied
        is Authorization.Current -> error("authorized state is not a failure")
    }

    private fun Authorization.toCurrentFailure() = when (this) {
        Authorization.SessionInvalidated -> LotSubstitutionCurrentResult.SessionInvalidated
        Authorization.ContextInvalidated -> LotSubstitutionCurrentResult.ContextInvalidated
        Authorization.PermissionDenied -> LotSubstitutionCurrentResult.PermissionDenied
        is Authorization.Current -> error("authorized state is not a failure")
    }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        val LOOKUP_PERMISSIONS = setOf("warehouse:read", "warehouse.read", "inventory.read")
        val SUBSTITUTION_PERMISSIONS = setOf("inventory.adjust")
        val FULFILLMENT_READ_PERMISSIONS = setOf("fulfillment.read", "fulfillment:read")
    }
}

private val substitutionJson = Json { ignoreUnknownKeys = false }
private val substitutionLocks = ConcurrentHashMap<LotSubstitutionScopeIdentity, Mutex>()
