package com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.InboundReceiptRequest
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingEvidenceCandidate
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingEvidenceResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.ReceivingSubmitResult
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.ReceivingGateway
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivedLotFacts
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingEvidenceObject
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingWarehouseChoice
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.ReceivingZoneChoice
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.InboundReceiptCommand
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.NexaReceivingGateway
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.ReceivingEvidenceProjection
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.ReceivingNetworkOutcome
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** App boundary binds Receiving to verified identity, current permission, and session epoch. */
@Singleton
class OperationsReceivingGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val receiving: NexaReceivingGateway
) : ReceivingGateway {
    override suspend fun warehouses(authority: ReceivingAuthority): ReceivingLookupResult {
        val before = authorize(authority, WAREHOUSE_LOOKUP_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLookupFailure()
        val result = receiving.warehouses().toLookupResult()
        return if (currentAfter(authority, before.lease)) {
            result
        } else {
            authorityDriftLookupResult(authority)
        }
    }

    override suspend fun zones(
        warehouseId: String,
        authority: ReceivingAuthority
    ): ReceivingLookupResult {
        val before = authorize(authority, WAREHOUSE_LOOKUP_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLookupFailure()
        val result = receiving.zones(warehouseId).toLookupResult()
        return if (currentAfter(authority, before.lease)) {
            result
        } else {
            authorityDriftLookupResult(authority)
        }
    }

    override suspend fun receive(
        request: InboundReceiptRequest,
        idempotencyKey: String,
        authority: ReceivingAuthority
    ): ReceivingSubmitResult {
        val before = authorize(authority, RECEIPT_PERMISSIONS)
        if (before !is Authorization.Current) return before.toSubmitFailure()
        val result = try {
            receiving.receive(request.toTransport(), idempotencyKey).toSubmitResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReceivingSubmitResult.UnknownOutcome
        }
        return if (currentAfter(authority, before.lease)) {
            result
        } else {
            when (authorityDriftLookupResult(authority)) {
                ReceivingLookupResult.SessionInvalidated -> ReceivingSubmitResult.SessionInvalidated
                else -> ReceivingSubmitResult.ContextInvalidated
            }
        }
    }

    override suspend fun uploadTemperatureEvidence(
        warehouseId: String,
        candidate: ReceivingEvidenceCandidate,
        idempotencyKey: String,
        authority: ReceivingAuthority
    ): ReceivingEvidenceResult {
        val before = authorize(
            authority,
            RECEIPT_PERMISSIONS,
            setOf(DOCUMENT_UPLOAD_PERMISSION, DOCUMENT_READ_PERMISSION)
        )
        if (before !is Authorization.Current) return before.toEvidenceFailure()
        val result = try {
            receiving.uploadTemperatureEvidence(
                warehouseId = warehouseId,
                idempotencyKey = idempotencyKey,
                file = candidate.file,
                originalFilename = candidate.originalFilename,
                declaredContentType = candidate.declaredContentType,
                byteSize = candidate.byteSize,
                checksumSha256 = candidate.checksumSha256
            ).toEvidenceResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReceivingEvidenceResult.UnknownOutcome
        }
        return if (currentAfter(
                authority,
                before.lease,
                RECEIPT_PERMISSIONS,
                setOf(DOCUMENT_UPLOAD_PERMISSION, DOCUMENT_READ_PERMISSION)
            )
        ) {
            result
        } else {
            when (authorityDriftLookupResult(authority)) {
                ReceivingLookupResult.SessionInvalidated ->
                    ReceivingEvidenceResult.SessionInvalidated

                else -> ReceivingEvidenceResult.ContextInvalidated
            }
        }
    }

    override suspend fun temperatureEvidenceStatus(
        evidenceId: String,
        warehouseId: String,
        authority: ReceivingAuthority
    ): ReceivingEvidenceResult {
        val before = authorize(authority, RECEIPT_PERMISSIONS, setOf(DOCUMENT_READ_PERMISSION))
        if (before !is Authorization.Current) return before.toEvidenceFailure()
        val result = try {
            receiving.temperatureEvidenceStatus(evidenceId, warehouseId).toEvidenceResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReceivingEvidenceResult.ServiceUnavailable
        }
        return if (currentAfter(
                authority,
                before.lease,
                RECEIPT_PERMISSIONS,
                setOf(DOCUMENT_READ_PERMISSION)
            )
        ) {
            result
        } else {
            when (authorityDriftLookupResult(authority)) {
                ReceivingLookupResult.SessionInvalidated ->
                    ReceivingEvidenceResult.SessionInvalidated

                else -> ReceivingEvidenceResult.ContextInvalidated
            }
        }
    }

    private suspend fun authorize(
        authority: ReceivingAuthority,
        requiredPermissions: Set<String>,
        requiredAllPermissions: Set<String> = emptySet()
    ): Authorization {
        if (sessions.sessionState.value != SessionState.Active) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (!verified.matches(authority)) return Authorization.ContextInvalidated
        if (authority.permissions.none { it in requiredPermissions } ||
            !authority.permissions.containsAll(requiredAllPermissions)
        ) {
            return Authorization.PermissionDenied
        }
        return Authorization.Current(lease)
    }

    private suspend fun currentAfter(
        authority: ReceivingAuthority,
        originalLease: AccessTokenLease,
        requiredPermissions: Set<String> = emptySet(),
        requiredAllPermissions: Set<String> = emptySet()
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(originalLease.epoch)
        ) {
            return false
        }
        val current = sessions.verifiedSession.value ?: return false
        return current.matches(authority) &&
            (
                requiredPermissions.isEmpty() || requiredPermissions.any {
                    it in current.permissions
                }
                ) &&
            current.permissions.containsAll(requiredAllPermissions)
    }

    private suspend fun authorityDriftLookupResult(
        authority: ReceivingAuthority
    ): ReceivingLookupResult = when {
        sessions.sessionState.value != SessionState.Active ->
            ReceivingLookupResult.SessionInvalidated

        sessions.verifiedSession.value?.matches(authority) == true ->
            ReceivingLookupResult.SessionInvalidated

        else -> ReceivingLookupResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(authority: ReceivingAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private fun Authorization.toLookupFailure(): ReceivingLookupResult = when (this) {
        Authorization.SessionInvalidated -> ReceivingLookupResult.SessionInvalidated
        Authorization.ContextInvalidated -> ReceivingLookupResult.ContextInvalidated
        Authorization.PermissionDenied -> ReceivingLookupResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Authorization.toSubmitFailure(): ReceivingSubmitResult = when (this) {
        Authorization.SessionInvalidated -> ReceivingSubmitResult.SessionInvalidated
        Authorization.ContextInvalidated -> ReceivingSubmitResult.ContextInvalidated
        Authorization.PermissionDenied -> ReceivingSubmitResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Authorization.toEvidenceFailure(): ReceivingEvidenceResult = when (this) {
        Authorization.SessionInvalidated -> ReceivingEvidenceResult.SessionInvalidated
        Authorization.ContextInvalidated -> ReceivingEvidenceResult.ContextInvalidated
        Authorization.PermissionDenied -> ReceivingEvidenceResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun ReceivingNetworkOutcome.toLookupResult(): ReceivingLookupResult = when (this) {
        is ReceivingNetworkOutcome.Warehouses -> ReceivingLookupResult.Warehouses(
            items.map { ReceivingWarehouseChoice(it.id, it.code, it.name, it.status) }
        )

        is ReceivingNetworkOutcome.Zones -> ReceivingLookupResult.Zones(
            items.map { ReceivingZoneChoice(it.id, it.warehouseId, it.code, it.name, it.status) }
        )

        ReceivingNetworkOutcome.NetworkUnavailable -> ReceivingLookupResult.NetworkUnavailable

        ReceivingNetworkOutcome.ServiceUnavailable,
        is ReceivingNetworkOutcome.Rejected,
        is ReceivingNetworkOutcome.Confirmed,
        ReceivingNetworkOutcome.UnknownOutcome -> ReceivingLookupResult.ServiceUnavailable

        is ReceivingNetworkOutcome.EvidenceUploaded,
        is ReceivingNetworkOutcome.EvidenceStatus -> ReceivingLookupResult.ServiceUnavailable

        ReceivingNetworkOutcome.PermissionDenied -> ReceivingLookupResult.PermissionDenied

        ReceivingNetworkOutcome.ContextInvalidated -> ReceivingLookupResult.ContextInvalidated

        ReceivingNetworkOutcome.SessionInvalidated -> ReceivingLookupResult.SessionInvalidated
    }

    private fun ReceivingNetworkOutcome.toSubmitResult(): ReceivingSubmitResult = when (this) {
        is ReceivingNetworkOutcome.Confirmed -> ReceivingSubmitResult.Confirmed(
            ReceivedLotFacts(
                id = lot.id,
                warehouseId = lot.warehouseId,
                zoneId = lot.zoneId,
                catalogItemId = lot.catalogItemId,
                skuId = lot.skuId,
                batchNumber = lot.batchNumber,
                expirationDate = lot.expirationDate,
                receivedAt = lot.receivedAt,
                onHand = lot.onHand,
                reserved = lot.reserved,
                available = lot.available,
                unit = lot.unit,
                status = lot.status,
                version = lot.version
            )
        )

        is ReceivingNetworkOutcome.Rejected -> ReceivingSubmitResult.Rejected(code)

        ReceivingNetworkOutcome.UnknownOutcome -> ReceivingSubmitResult.UnknownOutcome

        ReceivingNetworkOutcome.NetworkUnavailable -> ReceivingSubmitResult.UnknownOutcome

        ReceivingNetworkOutcome.ServiceUnavailable -> ReceivingSubmitResult.UnknownOutcome

        ReceivingNetworkOutcome.PermissionDenied -> ReceivingSubmitResult.PermissionDenied

        ReceivingNetworkOutcome.ContextInvalidated -> ReceivingSubmitResult.ContextInvalidated

        ReceivingNetworkOutcome.SessionInvalidated -> ReceivingSubmitResult.SessionInvalidated

        is ReceivingNetworkOutcome.Warehouses,
        is ReceivingNetworkOutcome.Zones,
        is ReceivingNetworkOutcome.EvidenceUploaded,
        is ReceivingNetworkOutcome.EvidenceStatus -> ReceivingSubmitResult.ServiceUnavailable
    }

    private fun ReceivingNetworkOutcome.toEvidenceResult(): ReceivingEvidenceResult = when (this) {
        is ReceivingNetworkOutcome.EvidenceUploaded -> ReceivingEvidenceResult.Loaded(
            evidence.toFeatureEvidence()
        )

        is ReceivingNetworkOutcome.EvidenceStatus -> ReceivingEvidenceResult.Loaded(
            evidence.toFeatureEvidence()
        )

        is ReceivingNetworkOutcome.Rejected -> ReceivingEvidenceResult.Rejected(code)

        ReceivingNetworkOutcome.UnknownOutcome -> ReceivingEvidenceResult.UnknownOutcome

        ReceivingNetworkOutcome.NetworkUnavailable -> ReceivingEvidenceResult.NetworkUnavailable

        ReceivingNetworkOutcome.ServiceUnavailable -> ReceivingEvidenceResult.ServiceUnavailable

        ReceivingNetworkOutcome.PermissionDenied -> ReceivingEvidenceResult.PermissionDenied

        ReceivingNetworkOutcome.ContextInvalidated -> ReceivingEvidenceResult.ContextInvalidated

        ReceivingNetworkOutcome.SessionInvalidated -> ReceivingEvidenceResult.SessionInvalidated

        is ReceivingNetworkOutcome.Confirmed,
        is ReceivingNetworkOutcome.Warehouses,
        is ReceivingNetworkOutcome.Zones -> ReceivingEvidenceResult.ServiceUnavailable
    }

    private fun ReceivingEvidenceProjection.toFeatureEvidence() = ReceivingEvidenceObject(
        id = id,
        subjectType = subjectType,
        subjectId = subjectId,
        lifecycleStatus = lifecycleStatus,
        declaredContentType = declaredContentType,
        byteSize = byteSize
    )

    private fun InboundReceiptRequest.toTransport() = InboundReceiptCommand(
        warehouseId = warehouseId,
        zoneId = zoneId,
        catalogItemId = catalogItemId,
        skuId = skuId,
        batchNumber = batchNumber,
        expirationDate = expirationDate,
        quantity = quantity,
        unit = unit,
        temperatureReading = temperatureReading,
        notes = notes,
        temperatureEvidenceObjectId = temperatureEvidenceObjectId
    )

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        val WAREHOUSE_LOOKUP_PERMISSIONS =
            setOf("warehouse.read", "inventory.read", "warehouse:read")
        val RECEIPT_PERMISSIONS = setOf("inventory.receive", "warehouse:write")
        const val DOCUMENT_UPLOAD_PERMISSION = "document.upload"
        const val DOCUMENT_READ_PERMISSION = "document.read"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ReceivingGatewayModule {
    @Provides
    @Singleton
    fun receivingGateway(protectedCalls: ProtectedCallExecutor) =
        NexaReceivingGateway(protectedCalls)
}
