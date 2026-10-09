package com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters

import android.content.Context
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidenceAuthority
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidencePhoto
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidencePhotoCandidate
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidencePhotoSelection
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureLookupResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperaturePhotoResult
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureSubmitResult
import com.nexa.mobile.operations.inventoryavailability.application.warehouse.TemperatureEvidenceGateway
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceFacts
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidencePayload
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceSelectionFacts
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceSubject
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceSubjectType
import com.nexa.mobile.operations.inventoryavailability.domain.model.warehouse.TemperatureEvidenceUnit
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.NexaReceivingGateway
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.NexaStockConditionGateway
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.NexaTemperatureEvidenceGateway
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.ReceivingEvidenceProjection
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.ReceivingNetworkOutcome
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.StockConditionLotProjection
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.StockConditionNetworkOutcome as StockConditionOutcome
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.TemperatureEvidenceCommandWire
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.TemperatureEvidenceNetworkOutcome as TemperatureEvidenceOutcome
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.TemperatureEvidenceResponseWire
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.TemperatureEvidenceSelectionWireProjection
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.TemperatureSubjectTypeWire
import com.nexa.mobile.operations.inventoryavailability.infrastructure.transport.TemperatureUnitWire
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class OperationsTemperatureEvidenceGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val receiving: NexaReceivingGateway,
    private val stockCondition: NexaStockConditionGateway,
    private val temperature: NexaTemperatureEvidenceGateway,
    @ApplicationContext context: Context
) : TemperatureEvidenceGateway {
    private val photoArtifacts = AppTemperatureEvidencePhotoArtifactStore(context)

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
        return if (currentAfter(
                authority,
                before.lease
            )
        ) {
            result
        } else {
            authorityDriftLookup(authority)
        }
    }

    override suspend fun subject(
        type: TemperatureEvidenceSubjectType,
        subjectId: String,
        authority: TemperatureEvidenceAuthority
    ): TemperatureLookupResult {
        val before = authorize(authority, SUBJECT_LOOKUP_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLookupFailure()
        val result = try {
            when (type) {
                TemperatureEvidenceSubjectType.LOT -> when (
                    val lot = stockCondition.lot(
                        subjectId
                    )
                ) {
                    is StockConditionOutcome.Lot -> TemperatureLookupResult.Subjects(
                        listOf(lot.item.toTemperatureSubject())
                    )

                    StockConditionOutcome.NetworkUnavailable ->
                        TemperatureLookupResult.NetworkUnavailable

                    StockConditionOutcome.PermissionDenied ->
                        TemperatureLookupResult.PermissionDenied

                    StockConditionOutcome.ContextInvalidated ->
                        TemperatureLookupResult.ContextInvalidated

                    StockConditionOutcome.SessionInvalidated ->
                        TemperatureLookupResult.SessionInvalidated

                    else -> TemperatureLookupResult.ServiceUnavailable
                }

                TemperatureEvidenceSubjectType.WAREHOUSE ->
                    receiving.warehouses().toSubjects().filterSubject(subjectId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TemperatureLookupResult.ServiceUnavailable
        }
        return if (currentAfter(
                authority,
                before.lease
            )
        ) {
            result
        } else {
            authorityDriftLookup(authority)
        }
    }

    override suspend fun snapshot(
        evidenceId: String,
        authority: TemperatureEvidenceAuthority
    ): TemperatureLookupResult {
        val before = authorize(authority, SUBJECT_LOOKUP_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLookupFailure()
        val result = try {
            when (val snapshot = temperature.snapshot(evidenceId)) {
                is TemperatureEvidenceOutcome.Confirmed ->
                    TemperatureLookupResult.EvidenceSnapshot(snapshot.response.toFeatureFacts())

                is TemperatureEvidenceOutcome.Rejected ->
                    TemperatureLookupResult.Rejected(snapshot.code)

                TemperatureEvidenceOutcome.Stale -> TemperatureLookupResult.Stale

                TemperatureEvidenceOutcome.PermissionDenied ->
                    TemperatureLookupResult.PermissionDenied

                TemperatureEvidenceOutcome.ContextInvalidated ->
                    TemperatureLookupResult.ContextInvalidated

                TemperatureEvidenceOutcome.SessionInvalidated ->
                    TemperatureLookupResult.SessionInvalidated

                TemperatureEvidenceOutcome.NetworkUnavailable ->
                    TemperatureLookupResult.NetworkUnavailable

                else -> TemperatureLookupResult.ServiceUnavailable
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TemperatureLookupResult.ServiceUnavailable
        }
        return if (currentAfter(
                authority,
                before.lease
            )
        ) {
            result
        } else {
            authorityDriftLookup(authority)
        }
    }

    override suspend fun uploadPhoto(
        selection: TemperatureEvidencePhotoSelection,
        candidate: TemperatureEvidencePhotoCandidate,
        idempotencyKey: String,
        authority: TemperatureEvidenceAuthority
    ): TemperaturePhotoResult {
        try {
            val requiredPermissions = setOf(DOCUMENT_UPLOAD_PERMISSION, DOCUMENT_READ_PERMISSION)
            val before = authorize(authority, RECEIPT_PERMISSIONS, requiredPermissions)
            if (before !is Authorization.Current) return before.toPhotoFailure()
            if (selection.scope != authority.scope ||
                selection.authorityEpoch != authority.authorityEpoch ||
                !isUuid(selection.warehouseId) || idempotencyKey.isBlank() ||
                idempotencyKey.length > 160
            ) {
                return TemperaturePhotoResult.ContextInvalidated
            }
            val subjectCheck = revalidatePhotoWarehouse(selection)
            if (subjectCheck != null) return subjectCheck
            if (!currentAfter(authority, before.lease)) return authorityDriftPhoto(authority)
            val result =
                photoArtifacts.withEncryptedCandidate(selection, candidate) { encryptedCandidate ->
                    try {
                        receiving.uploadTemperatureEvidence(
                            warehouseId = selection.warehouseId,
                            idempotencyKey = idempotencyKey,
                            file = encryptedCandidate.file,
                            originalFilename = encryptedCandidate.originalFilename,
                            declaredContentType = encryptedCandidate.declaredContentType,
                            byteSize = encryptedCandidate.byteSize,
                            checksumSha256 = encryptedCandidate.checksumSha256
                        ).toPhotoResult(selection.warehouseId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        TemperaturePhotoResult.UnknownOutcome
                    }
                } ?: TemperaturePhotoResult.Rejected("INVALID_EVIDENCE")
            return if (currentAfter(
                    authority,
                    before.lease
                )
            ) {
                result
            } else {
                authorityDriftPhoto(authority)
            }
        } finally {
            photoArtifacts.discardStagedCandidate(candidate)
        }
    }

    override suspend fun photoStatus(
        evidenceId: String,
        warehouseId: String,
        authority: TemperatureEvidenceAuthority
    ): TemperaturePhotoResult {
        val before = authorize(authority, RECEIPT_PERMISSIONS, setOf(DOCUMENT_READ_PERMISSION))
        if (before !is Authorization.Current) return before.toPhotoFailure()
        if (!isUuid(evidenceId) || !isUuid(warehouseId)) {
            return TemperaturePhotoResult.Rejected("INVALID_REQUEST")
        }
        val result = try {
            receiving.temperatureEvidenceStatus(evidenceId, warehouseId).toPhotoResult(warehouseId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TemperaturePhotoResult.ServiceUnavailable
        }
        return if (currentAfter(authority, before.lease)) result else authorityDriftPhoto(authority)
    }

    private suspend fun revalidatePhotoWarehouse(
        selection: TemperatureEvidencePhotoSelection
    ): TemperaturePhotoResult? = when (selection.subjectType) {
        TemperatureEvidenceSubjectType.LOT -> {
            if (selection.expectedLotVersion == null) {
                TemperaturePhotoResult.Rejected("INVENTORY_LOT_CONCURRENCY_CONFLICT")
            } else {
                when (val read = stockCondition.lot(selection.subjectId)) {
                    is StockConditionOutcome.Lot -> if (
                        read.item.warehouseId == selection.warehouseId &&
                        read.item.version == selection.expectedLotVersion
                    ) {
                        null
                    } else {
                        TemperaturePhotoResult.Rejected("INVENTORY_LOT_CONCURRENCY_CONFLICT")
                    }

                    StockConditionOutcome.NetworkUnavailable ->
                        TemperaturePhotoResult.NetworkUnavailable

                    StockConditionOutcome.PermissionDenied ->
                        TemperaturePhotoResult.PermissionDenied

                    StockConditionOutcome.ContextInvalidated ->
                        TemperaturePhotoResult.ContextInvalidated

                    StockConditionOutcome.SessionInvalidated ->
                        TemperaturePhotoResult.SessionInvalidated

                    else -> TemperaturePhotoResult.Rejected("INVENTORY_LOT_NOT_FOUND")
                }
            }
        }

        TemperatureEvidenceSubjectType.WAREHOUSE -> when (val read = receiving.warehouses()) {
            is ReceivingNetworkOutcome.Warehouses -> if (
                selection.subjectId == selection.warehouseId &&
                read.items.any {
                    it.id == selection.warehouseId &&
                        it.status.equals("ACTIVE", ignoreCase = true)
                }
            ) {
                null
            } else {
                TemperaturePhotoResult.Rejected("WAREHOUSE_NOT_FOUND")
            }

            ReceivingNetworkOutcome.NetworkUnavailable -> TemperaturePhotoResult.NetworkUnavailable

            ReceivingNetworkOutcome.PermissionDenied -> TemperaturePhotoResult.PermissionDenied

            ReceivingNetworkOutcome.ContextInvalidated -> TemperaturePhotoResult.ContextInvalidated

            ReceivingNetworkOutcome.SessionInvalidated -> TemperaturePhotoResult.SessionInvalidated

            else -> TemperaturePhotoResult.ServiceUnavailable
        }
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

                        TemperatureEvidenceSubjectType.WAREHOUSE ->
                            TemperatureSubjectTypeWire.WAREHOUSE
                    },
                    subjectId = payload.subjectId,
                    value = payload.value,
                    unit = when (payload.unit) {
                        TemperatureEvidenceUnit.CELSIUS -> TemperatureUnitWire.CELSIUS
                        TemperatureEvidenceUnit.FAHRENHEIT -> TemperatureUnitWire.FAHRENHEIT
                    },
                    occurredAt = Instant.parse(payload.occurredAt),
                    evidenceObjectId = payload.evidenceObjectId,
                    expectedLotVersion = payload.expectedLotVersion,
                    affectedQuantity = payload.affectedQuantity,
                    reason = payload.reason,
                    sourceEvidenceId = payload.sourceEvidenceId
                ),
                idempotencyKey,
                authority.membershipId
            ).toFeatureResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TemperatureSubmitResult.UnknownOutcome
        }
        return if (currentAfter(authority, before.lease)) {
            result
        } else {
            when (
                authorityDriftLookup(authority)
            ) {
                TemperatureLookupResult.SessionInvalidated ->
                    TemperatureSubmitResult.SessionInvalidated

                else -> TemperatureSubmitResult.ContextInvalidated
            }
        }
    }

    private suspend fun authorize(
        authority: TemperatureEvidenceAuthority,
        permissions: Set<String>,
        requiredAllPermissions: Set<String> = emptySet()
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
        return if (authority.permissions.any(permissions::contains) &&
            authority.permissions.containsAll(requiredAllPermissions)
        ) {
            Authorization.Current(lease)
        } else {
            Authorization.PermissionDenied
        }
    }

    private suspend fun currentAfter(
        authority: TemperatureEvidenceAuthority,
        originalLease: AccessTokenLease
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(originalLease.epoch)
        ) {
            return false
        }
        return sessions.verifiedSession.value?.matches(authority) == true
    }

    private suspend fun authorityDriftLookup(
        authority: TemperatureEvidenceAuthority
    ): TemperatureLookupResult = when {
        sessions.sessionState.value != SessionState.Active ->
            TemperatureLookupResult.SessionInvalidated

        sessions.verifiedSession.value?.matches(authority) == true ->
            TemperatureLookupResult.SessionInvalidated

        else -> TemperatureLookupResult.ContextInvalidated
    }

    private suspend fun authorityDriftPhoto(authority: TemperatureEvidenceAuthority) = when {
        sessions.sessionState.value != SessionState.Active ->
            TemperaturePhotoResult.SessionInvalidated

        sessions.verifiedSession.value?.matches(authority) == true ->
            TemperaturePhotoResult.SessionInvalidated

        else -> TemperaturePhotoResult.ContextInvalidated
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

    private fun Authorization.toPhotoFailure(): TemperaturePhotoResult = when (this) {
        Authorization.SessionInvalidated -> TemperaturePhotoResult.SessionInvalidated
        Authorization.ContextInvalidated -> TemperaturePhotoResult.ContextInvalidated
        Authorization.PermissionDenied -> TemperaturePhotoResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun ReceivingNetworkOutcome.toSubjects(): TemperatureLookupResult = when (this) {
        is ReceivingNetworkOutcome.Warehouses -> TemperatureLookupResult.Subjects(
            items.map {
                TemperatureEvidenceSubject(
                    id = it.id,
                    type = TemperatureEvidenceSubjectType.WAREHOUSE,
                    primaryLabel = "${it.name} · ${it.code}",
                    detailLabel = it.status,
                    warehouseId = it.id
                )
            }
        )

        ReceivingNetworkOutcome.NetworkUnavailable -> TemperatureLookupResult.NetworkUnavailable

        ReceivingNetworkOutcome.PermissionDenied -> TemperatureLookupResult.PermissionDenied

        ReceivingNetworkOutcome.ContextInvalidated -> TemperatureLookupResult.ContextInvalidated

        ReceivingNetworkOutcome.SessionInvalidated -> TemperatureLookupResult.SessionInvalidated

        else -> TemperatureLookupResult.ServiceUnavailable
    }

    private fun StockConditionOutcome.toSubjects(): TemperatureLookupResult = when (this) {
        is StockConditionOutcome.Lots -> TemperatureLookupResult.Subjects(
            items.map {
                it.toTemperatureSubject()
            }
        )

        StockConditionOutcome.NetworkUnavailable ->
            TemperatureLookupResult.NetworkUnavailable

        StockConditionOutcome.PermissionDenied -> TemperatureLookupResult.PermissionDenied

        StockConditionOutcome.ContextInvalidated ->
            TemperatureLookupResult.ContextInvalidated

        StockConditionOutcome.SessionInvalidated ->
            TemperatureLookupResult.SessionInvalidated

        else -> TemperatureLookupResult.ServiceUnavailable
    }

    private fun TemperatureEvidenceOutcome.toFeatureResult(): TemperatureSubmitResult =
        when (this) {
            is TemperatureEvidenceOutcome.Confirmed ->
                TemperatureSubmitResult.Confirmed(response.toFeatureFacts())

            is TemperatureEvidenceOutcome.Rejected -> TemperatureSubmitResult.Rejected(code)

            TemperatureEvidenceOutcome.Stale -> TemperatureSubmitResult.Stale

            TemperatureEvidenceOutcome.UnknownOutcome,
            TemperatureEvidenceOutcome.NetworkUnavailable ->
                TemperatureSubmitResult.UnknownOutcome

            TemperatureEvidenceOutcome.ServiceUnavailable ->
                TemperatureSubmitResult.ServiceUnavailable

            TemperatureEvidenceOutcome.PermissionDenied ->
                TemperatureSubmitResult.PermissionDenied

            TemperatureEvidenceOutcome.ContextInvalidated ->
                TemperatureSubmitResult.ContextInvalidated

            TemperatureEvidenceOutcome.SessionInvalidated ->
                TemperatureSubmitResult.SessionInvalidated
        }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private companion object {
        val SUBJECT_LOOKUP_PERMISSIONS = setOf("warehouse.read", "inventory.read", "warehouse:read")
        val RECEIPT_PERMISSIONS = setOf("inventory.receive", "warehouse:write")
        val PHOTO_PERMISSIONS = setOf("document.upload", "document.read")
        const val DOCUMENT_UPLOAD_PERMISSION = "document.upload"
        const val DOCUMENT_READ_PERMISSION = "document.read"
        val UUID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun isUuid(value: String): Boolean = UUID_PATTERN.matches(value)
    }
}

private fun StockConditionLotProjection.toTemperatureSubject() = TemperatureEvidenceSubject(
    id = id,
    type = TemperatureEvidenceSubjectType.LOT,
    primaryLabel = "$batchNumber · $status",
    detailLabel = "Warehouse $warehouseId · zone $zoneId · expires $expirationDate",
    warehouseId = warehouseId,
    lotVersion = version,
    physicalRemaining = physicalRemaining,
    quantityUnit = unit
)

private fun TemperatureLookupResult.filterSubject(subjectId: String): TemperatureLookupResult =
    when (this) {
        is TemperatureLookupResult.Subjects -> {
            val matches = items.filter { it.id == subjectId }
            if (matches.size == 1) {
                TemperatureLookupResult.Subjects(matches)
            } else {
                TemperatureLookupResult.Rejected("WAREHOUSE_NOT_FOUND")
            }
        }

        else -> this
    }

private fun ReceivingNetworkOutcome.toPhotoResult(warehouseId: String): TemperaturePhotoResult =
    when (this) {
        is ReceivingNetworkOutcome.EvidenceUploaded -> evidence.toPhotoResult(warehouseId)
        is ReceivingNetworkOutcome.EvidenceStatus -> evidence.toPhotoResult(warehouseId)
        is ReceivingNetworkOutcome.Rejected -> TemperaturePhotoResult.Rejected(code)
        ReceivingNetworkOutcome.UnknownOutcome -> TemperaturePhotoResult.UnknownOutcome
        ReceivingNetworkOutcome.NetworkUnavailable -> TemperaturePhotoResult.NetworkUnavailable
        ReceivingNetworkOutcome.ServiceUnavailable -> TemperaturePhotoResult.ServiceUnavailable
        ReceivingNetworkOutcome.PermissionDenied -> TemperaturePhotoResult.PermissionDenied
        ReceivingNetworkOutcome.ContextInvalidated -> TemperaturePhotoResult.ContextInvalidated
        ReceivingNetworkOutcome.SessionInvalidated -> TemperaturePhotoResult.SessionInvalidated
        else -> TemperaturePhotoResult.ServiceUnavailable
    }

private fun ReceivingEvidenceProjection.toPhotoResult(warehouseId: String) =
    if (subjectType.equals("WAREHOUSE", ignoreCase = true) && subjectId == warehouseId) {
        TemperaturePhotoResult.Evidence(
            TemperatureEvidencePhoto(id, subjectType, subjectId, lifecycleStatus)
        )
    } else {
        TemperaturePhotoResult.Rejected("EVIDENCE_SUBJECT_MISMATCH")
    }

private fun TemperatureEvidenceResponseWire.toFeatureFacts() = TemperatureEvidenceFacts(
    id = id,
    subjectType = when (subjectType) {
        TemperatureSubjectTypeWire.LOT -> TemperatureEvidenceSubjectType.LOT
        TemperatureSubjectTypeWire.WAREHOUSE -> TemperatureEvidenceSubjectType.WAREHOUSE
    },
    subjectId = subjectId,
    lotId = lotId,
    warehouseId = warehouseId,
    value = value,
    unit = when (unit) {
        TemperatureUnitWire.CELSIUS -> TemperatureEvidenceUnit.CELSIUS
        TemperatureUnitWire.FAHRENHEIT -> TemperatureEvidenceUnit.FAHRENHEIT
    },
    occurredAt = occurredAt,
    actorMembershipId = actorMembershipId,
    status = status,
    source = source,
    evidenceObjectId = evidenceObjectId,
    expectedLotVersion = expectedLotVersion,
    resultingLotVersion = resultingLotVersion,
    inventoryTemperatureEvaluationId = inventoryTemperatureEvaluationId,
    inventoryLotStatus = inventoryLotStatus,
    affectedQuantity = affectedQuantity,
    remainingHeldQuantity = remainingHeldQuantity,
    reason = reason,
    sourceEvidenceId = sourceEvidenceId,
    exceptionId = exceptionId,
    exceptionStatus = exceptionStatus,
    evaluationStatus = evaluationStatus,
    disposition = disposition,
    selections = selections.map(TemperatureEvidenceSelectionWireProjection::toFeatureFacts)
)

private fun TemperatureEvidenceSelectionWireProjection.toFeatureFacts() =
    TemperatureEvidenceSelectionFacts(
        temperatureEvidenceId,
        lotId,
        affectedQuantity,
        expectedLotVersion,
        resultingLotVersion,
        inventoryTemperatureEvaluationId,
        inventoryLotStatus,
        remainingHeldQuantity,
        actorMembershipId,
        occurredAt,
        evidenceObjectId,
        reason,
        evaluationStatus,
        disposition,
        blocksCommittedExecution
    )

@Module
@InstallIn(SingletonComponent::class)
object TemperatureEvidenceGatewayModule {
    @Provides
    @Singleton
    fun temperatureEvidenceGateway(
        protectedCalls: ProtectedCallExecutor
    ): NexaTemperatureEvidenceGateway = NexaTemperatureEvidenceGateway(protectedCalls)
}
