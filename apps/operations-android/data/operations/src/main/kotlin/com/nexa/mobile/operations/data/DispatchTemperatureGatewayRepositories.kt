package com.nexa.mobile.operations.data

import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.network.FulfillmentTemperatureEvidenceProjection as TemperatureEvidenceProjection
import com.nexa.mobile.operations.core.network.FulfillmentTemperatureLotProjection as TemperatureLotProjection
import com.nexa.mobile.operations.core.network.FulfillmentTemperatureNetworkOutcome as TemperatureOutcome
import com.nexa.mobile.operations.core.network.FulfillmentTemperatureReadinessProjection as TemperatureReadinessProjection
import com.nexa.mobile.operations.core.network.NexaFulfillmentTemperatureGateway
import com.nexa.mobile.operations.core.network.NexaReceivingGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.core.network.ReceivingEvidenceProjection
import com.nexa.mobile.operations.core.network.ReceivingNetworkOutcome
import com.nexa.mobile.operations.feature.dispatch.application.DispatchTemperatureGateway
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.model.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperatureCommand
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperatureEvidence as TemperatureEvidence
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperatureGatewayResult as TemperatureGatewayResult
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperatureLot
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperaturePhotoCandidate as TemperaturePhotoCandidate
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperaturePhotoEvidence as TemperaturePhotoEvidence
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperaturePhotoGatewayResult as TemperaturePhotoGatewayResult
import com.nexa.mobile.operations.feature.dispatch.model.DispatchTemperatureReadiness as TemperatureReadiness
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Module
@InstallIn(SingletonComponent::class)
object DispatchTemperatureGatewayBindings {
    @Provides
    @Singleton
    fun dispatchTemperatureNetworkGateway(
        protectedCalls: ProtectedCallExecutor
    ): NexaFulfillmentTemperatureGateway = NexaFulfillmentTemperatureGateway(protectedCalls)

    @Provides
    @Singleton
    fun dispatchTemperatureGateway(
        operations: OperationsDispatchTemperatureGateway
    ): DispatchTemperatureGateway = operations
}

/** Checks live session identity around each protected cold-chain request. */
@Singleton
class OperationsDispatchTemperatureGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val temperature: NexaFulfillmentTemperatureGateway,
    private val receiving: NexaReceivingGateway
) : DispatchTemperatureGateway {
    override suspend fun current(
        fulfillmentId: String,
        context: DispatchAuthorityContext
    ): TemperatureGatewayResult {
        val authorization = authorize(context, requireWrite = false)
        if (authorization !is Authorization.Current) return authorization.toResult()
        val result = temperature.current(fulfillmentId).toFeatureResult()
        return if (isCurrent(
                context,
                authorization.lease,
                requireWrite = false
            )
        ) {
            result
        } else {
            authorityDrift()
        }
    }

    override suspend fun record(
        command: DispatchTemperatureCommand,
        context: DispatchAuthorityContext
    ): TemperatureGatewayResult {
        val authorization = authorize(context, requireWrite = true)
        if (authorization !is Authorization.Current) return authorization.toResult()
        if (!command.isValid()) return TemperatureGatewayResult.ServiceUnavailable
        val result = temperature.record(
            fulfillmentId = command.fulfillmentId,
            expectedFulfillmentVersion = command.expectedFulfillmentVersion,
            lotId = command.lotId,
            valueCelsius = command.valueCelsius,
            occurredAt = command.occurredAt,
            idempotencyKey = command.idempotencyKey,
            exactRequestBody = command.exactRequestBody,
            expectedLotVersion = command.expectedLotVersion,
            evidenceObjectId = command.evidenceObjectId
        ).toFeatureResult(command.fulfillmentId)
        return if (isCurrent(
                context,
                authorization.lease,
                requireWrite = true
            )
        ) {
            result
        } else {
            authorityDrift()
        }
    }

    override suspend fun uploadExcursionPhoto(
        warehouseId: String,
        candidate: TemperaturePhotoCandidate,
        idempotencyKey: String,
        context: DispatchAuthorityContext
    ): TemperaturePhotoGatewayResult {
        val authorization = authorize(context, requireWrite = true, requirePhotoAccess = true)
        if (authorization !is Authorization.Current) return authorization.toPhotoResult()
        if (!isUuid(warehouseId) || idempotencyKey.isBlank() || idempotencyKey.length > 160 ||
            !candidate.file.isFile || candidate.file.length() != candidate.byteSize
        ) {
            return TemperaturePhotoGatewayResult.Rejected("INVALID_EVIDENCE")
        }
        val result = try {
            receiving.uploadTemperatureEvidence(
                warehouseId = warehouseId,
                idempotencyKey = idempotencyKey,
                file = candidate.file,
                originalFilename = candidate.originalFilename,
                declaredContentType = candidate.declaredContentType,
                byteSize = candidate.byteSize,
                checksumSha256 = candidate.checksumSha256
            ).toPhotoResult(warehouseId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TemperaturePhotoGatewayResult.UnknownOutcome
        }
        return if (isCurrent(
                context,
                authorization.lease,
                requireWrite = true,
                requirePhotoAccess = true
            )
        ) {
            result
        } else {
            authorityDriftPhotoResult()
        }
    }

    override suspend fun excursionPhotoStatus(
        evidenceObjectId: String,
        warehouseId: String,
        context: DispatchAuthorityContext
    ): TemperaturePhotoGatewayResult {
        val authorization = authorize(context, requireWrite = true, requirePhotoAccess = true)
        if (authorization !is Authorization.Current) return authorization.toPhotoResult()
        if (!isUuid(evidenceObjectId) || !isUuid(warehouseId)) {
            return TemperaturePhotoGatewayResult.Rejected("INVALID_REQUEST")
        }
        val result = try {
            receiving.temperatureEvidenceStatus(
                evidenceObjectId,
                warehouseId
            ).toPhotoResult(warehouseId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TemperaturePhotoGatewayResult.ServiceUnavailable
        }
        return if (isCurrent(
                context,
                authorization.lease,
                requireWrite = true,
                requirePhotoAccess = true
            )
        ) {
            result
        } else {
            authorityDriftPhotoResult()
        }
    }

    private suspend fun authorize(
        context: DispatchAuthorityContext,
        requireWrite: Boolean,
        requirePhotoAccess: Boolean = false
    ): Authorization {
        if (sessions.sessionState.value !=
            SessionState.Active
        ) {
            return Authorization.SessionInvalidated
        }
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val identity = context.identity ?: return Authorization.ContextInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (context.authorityEpoch <= 0 || listOf(
                identity.userId,
                identity.tenantId,
                identity.workspaceId,
                identity.membershipId
            ).any(String::isBlank) || !verified.matches(identity)
        ) {
            return Authorization.ContextInvalidated
        }
        if (identity.permissions.isEmpty()) return Authorization.ContextInvalidated
        if (identity.permissions.none { it in FULFILLMENT_READ_PERMISSIONS } ||
            (requireWrite && identity.permissions.none { it in FULFILLMENT_MANAGE_PERMISSIONS }) ||
            (requirePhotoAccess && !identity.permissions.containsAll(PHOTO_EVIDENCE_PERMISSIONS))
        ) {
            return Authorization.PermissionDenied
        }
        return Authorization.Current(lease)
    }

    private suspend fun isCurrent(
        context: DispatchAuthorityContext,
        originalLease: AccessTokenLease,
        requireWrite: Boolean,
        requirePhotoAccess: Boolean = false
    ): Boolean {
        if (sessions.sessionState.value != SessionState.Active ||
            !sessions.isEpochCurrent(originalLease.epoch)
        ) {
            return false
        }
        val identity = context.identity ?: return false
        val permissions = identity.permissions
        return sessions.verifiedSession.value?.matches(identity) == true &&
            permissions.any { it in FULFILLMENT_READ_PERMISSIONS } &&
            (!requireWrite || permissions.any { it in FULFILLMENT_MANAGE_PERMISSIONS }) &&
            (!requirePhotoAccess || permissions.containsAll(PHOTO_EVIDENCE_PERMISSIONS))
    }

    private suspend fun authorityDrift(): TemperatureGatewayResult =
        if (sessions.sessionState.value != SessionState.Active) {
            TemperatureGatewayResult.SessionInvalidated
        } else {
            TemperatureGatewayResult.ContextInvalidated
        }

    private suspend fun authorityDriftPhotoResult(): TemperaturePhotoGatewayResult =
        if (sessions.sessionState.value != SessionState.Active) {
            TemperaturePhotoGatewayResult.SessionInvalidated
        } else {
            TemperaturePhotoGatewayResult.ContextInvalidated
        }

    private fun VerifiedSession.matches(expected: DispatchAuthorityIdentity): Boolean =
        hasAuthorizedContext && userId == expected.userId && tenantId == expected.tenantId &&
            workspaceId == expected.workspaceId && membershipId == expected.membershipId &&
            permissions == expected.permissions

    private fun TemperatureOutcome.toFeatureResult(): TemperatureGatewayResult = when (this) {
        is TemperatureOutcome.Current -> readiness.toFeatureResult()

        TemperatureOutcome.OutsideRangeBackendContractGap ->
            TemperatureGatewayResult.OutsideRangeBackendContractGap

        TemperatureOutcome.UnknownOutcome ->
            TemperatureGatewayResult.UnknownOutcome

        TemperatureOutcome.NetworkUnavailable ->
            TemperatureGatewayResult.NetworkUnavailable

        TemperatureOutcome.ServiceUnavailable ->
            TemperatureGatewayResult.ServiceUnavailable

        TemperatureOutcome.PermissionDenied ->
            TemperatureGatewayResult.PermissionDenied

        TemperatureOutcome.ContextInvalidated ->
            TemperatureGatewayResult.ContextInvalidated

        TemperatureOutcome.SessionInvalidated ->
            TemperatureGatewayResult.SessionInvalidated

        TemperatureOutcome.Stale -> TemperatureGatewayResult.Stale

        TemperatureOutcome.Conflict ->
            TemperatureGatewayResult.Conflict

        is TemperatureOutcome.Recorded ->
            TemperatureGatewayResult.ServiceUnavailable
    }

    private fun TemperatureOutcome.toFeatureResult(
        fulfillmentId: String
    ): TemperatureGatewayResult = when (this) {
        is TemperatureOutcome.Recorded -> {
            val featureEvidence = evidence.toFeature(fulfillmentId)
            if (featureEvidence == null) {
                TemperatureGatewayResult.UnknownOutcome
            } else {
                TemperatureGatewayResult.Recorded(featureEvidence)
            }
        }

        TemperatureOutcome.OutsideRangeBackendContractGap ->
            TemperatureGatewayResult.OutsideRangeBackendContractGap

        TemperatureOutcome.UnknownOutcome ->
            TemperatureGatewayResult.UnknownOutcome

        TemperatureOutcome.NetworkUnavailable ->
            TemperatureGatewayResult.NetworkUnavailable

        TemperatureOutcome.ServiceUnavailable ->
            TemperatureGatewayResult.ServiceUnavailable

        TemperatureOutcome.PermissionDenied ->
            TemperatureGatewayResult.PermissionDenied

        TemperatureOutcome.ContextInvalidated ->
            TemperatureGatewayResult.ContextInvalidated

        TemperatureOutcome.SessionInvalidated ->
            TemperatureGatewayResult.SessionInvalidated

        TemperatureOutcome.Stale -> TemperatureGatewayResult.Stale

        TemperatureOutcome.Conflict -> TemperatureGatewayResult.Conflict

        is TemperatureOutcome.Current ->
            TemperatureGatewayResult.ServiceUnavailable
    }

    private fun TemperatureReadinessProjection.toFeatureResult(): TemperatureGatewayResult {
        val lots = mutableListOf<DispatchTemperatureLot>()
        for (lot in this.lots) {
            val evidence =
                lot.latestEvidence?.toFeature(fulfillmentId) ?: if (lot.latestEvidence == null) {
                    null
                } else {
                    return TemperatureGatewayResult.ServiceUnavailable
                }
            lots += lot.toFeature(evidence)
        }
        return TemperatureGatewayResult.Current(
            TemperatureReadiness(
                fulfillmentId,
                fulfillmentStatus,
                fulfillmentVersion,
                physicalAllocationId,
                physicalAllocationVersion,
                temperatureRequiredForFulfillment,
                asOf,
                lots
            )
        )
    }

    private fun TemperatureLotProjection.toFeature(evidence: TemperatureEvidence?) =
        DispatchTemperatureLot(
            skuId,
            lotId,
            warehouseId,
            zoneId,
            skuColdChainRequired,
            requiredForFulfillment,
            minimumCelsius,
            maximumCelsius,
            status,
            evidence,
            version
        )

    private fun TemperatureEvidenceProjection.toFeature(
        fulfillmentId: String
    ): TemperatureEvidence? {
        val version = fulfillmentVersion ?: return null
        if (subjectType != "FULFILLMENT" || !subjectId.equals(fulfillmentId, ignoreCase = true) ||
            unit != "CELSIUS"
        ) {
            return null
        }
        return TemperatureEvidence(
            id,
            fulfillmentId,
            version,
            lotId,
            value,
            occurredAt,
            actorMembershipId,
            status,
            evidenceObjectId,
            expectedLotVersion,
            resultingLotVersion,
            inventoryTemperatureEvaluationId,
            inventoryLotStatus,
            affectedQuantity
        )
    }

    private sealed interface Authorization {
        data class Current(val lease: AccessTokenLease) : Authorization
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
    }

    private fun Authorization.toResult(): TemperatureGatewayResult = when (this) {
        Authorization.SessionInvalidated -> TemperatureGatewayResult.SessionInvalidated
        Authorization.ContextInvalidated -> TemperatureGatewayResult.ContextInvalidated
        Authorization.PermissionDenied -> TemperatureGatewayResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Authorization.toPhotoResult(): TemperaturePhotoGatewayResult = when (this) {
        Authorization.SessionInvalidated -> TemperaturePhotoGatewayResult.SessionInvalidated
        Authorization.ContextInvalidated -> TemperaturePhotoGatewayResult.ContextInvalidated
        Authorization.PermissionDenied -> TemperaturePhotoGatewayResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun ReceivingNetworkOutcome.toPhotoResult(
        warehouseId: String
    ): TemperaturePhotoGatewayResult = when (this) {
        is ReceivingNetworkOutcome.EvidenceUploaded -> evidence.toPhoto(warehouseId)

        is ReceivingNetworkOutcome.EvidenceStatus -> evidence.toPhoto(warehouseId)

        is ReceivingNetworkOutcome.Rejected -> TemperaturePhotoGatewayResult.Rejected(code)

        ReceivingNetworkOutcome.UnknownOutcome ->
            TemperaturePhotoGatewayResult.UnknownOutcome

        ReceivingNetworkOutcome.NetworkUnavailable ->
            TemperaturePhotoGatewayResult.NetworkUnavailable

        ReceivingNetworkOutcome.ServiceUnavailable ->
            TemperaturePhotoGatewayResult.ServiceUnavailable

        ReceivingNetworkOutcome.PermissionDenied ->
            TemperaturePhotoGatewayResult.PermissionDenied

        ReceivingNetworkOutcome.ContextInvalidated ->
            TemperaturePhotoGatewayResult.ContextInvalidated

        ReceivingNetworkOutcome.SessionInvalidated ->
            TemperaturePhotoGatewayResult.SessionInvalidated

        else -> TemperaturePhotoGatewayResult.ServiceUnavailable
    }

    private fun ReceivingEvidenceProjection.toPhoto(
        warehouseId: String
    ): TemperaturePhotoGatewayResult = if (subjectType != "WAREHOUSE" ||
        subjectId != warehouseId
    ) {
        TemperaturePhotoGatewayResult.Rejected("EVIDENCE_SUBJECT_MISMATCH")
    } else {
        TemperaturePhotoGatewayResult.Evidence(
            TemperaturePhotoEvidence(id, subjectType, subjectId, lifecycleStatus)
        )
    }

    private companion object {
        val FULFILLMENT_READ_PERMISSIONS = setOf("fulfillment.read", "fulfillment:read")
        val FULFILLMENT_MANAGE_PERMISSIONS = setOf("fulfillment.manage", "warehouse:write")
        val PHOTO_EVIDENCE_PERMISSIONS = setOf("document.upload", "document.read")
        val UUID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun isUuid(value: String): Boolean = UUID_PATTERN.matches(value)
    }
}
