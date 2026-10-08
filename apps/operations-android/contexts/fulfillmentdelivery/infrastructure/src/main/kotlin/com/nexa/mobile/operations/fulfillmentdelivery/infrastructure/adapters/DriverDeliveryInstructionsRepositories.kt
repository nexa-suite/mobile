package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import android.content.Context
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.DriverDeliveryInstructionAcknowledgementResponseTransport as AcknowledgementResponseTransport
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.DriverDeliveryInstructionAcknowledgementTransport as AcknowledgementTransport
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.DriverDeliveryInstructionsNetworkOutcome as InstructionsNetworkOutcome
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.transport.NexaDriverDeliveryInstructionsGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionMetadataStore as InstructionMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionsGateway as InstructionsGateway
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryInstruction
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionAcknowledgementCommand as AcknowledgementCommand
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryInstructionAcknowledgementFact as AcknowledgementFact
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionAcknowledgementResult as AcknowledgementResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryInstructionAcknowledgementSummary as AcknowledgementSummary
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionIntentMetadata as InstructionIntentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionIntentStatus as InstructionIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryInstructionKind as InstructionKind
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionMetadataRead as InstructionMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionMetadataWrite as InstructionMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverDeliveryInstructionsLoadResult as InstructionsLoadResult
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverDeliveryInstructionsSnapshot as InstructionsSnapshot
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.delivery.driverDeliveryInstructionAcknowledgementBody
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Singleton
class OperationsDriverDeliveryInstructionsGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val api: NexaDriverDeliveryInstructionsGateway
) : InstructionsGateway {
    override suspend fun currentInstructions(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): InstructionsLoadResult {
        val before = authorize(authority, DRIVER_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLoadResult()
        val outcome = try {
            api.currentInstructions(deliveryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return InstructionsLoadResult.ServiceUnavailable
        }
        if (!currentAfter(authority, before.lease)) return authorityDrift()
        return when (outcome) {
            is InstructionsNetworkOutcome.Loaded -> try {
                InstructionsLoadResult.Loaded(
                    InstructionsSnapshot(
                        deliveryId = outcome.value.deliveryId,
                        deliveryVersion = outcome.value.deliveryVersion,
                        instructionSetVersion = outcome.value.instructionSetVersion,
                        instructions = outcome.value.instructions.map { row ->
                            DriverDeliveryInstruction(
                                id = row.id,
                                kind = InstructionKind.valueOf(row.kind),
                                content = row.content,
                                instructionVersion = row.instructionVersion,
                                critical = row.critical,
                                acknowledged = row.acknowledged,
                                acknowledgedAt = row.acknowledgedAt,
                                acknowledgedByMembershipId = row.acknowledgedByMembershipId,
                                sourceKind = row.sourceKind,
                                recordedByMembershipId = row.recordedByMembershipId,
                                recordedAt = row.recordedAt
                            )
                        }
                    )
                )
            } catch (_: Exception) {
                InstructionsLoadResult.ServiceUnavailable
            }

            else -> outcome.toLoadResult()
        }
    }

    override suspend fun acknowledgeCriticalInstructions(
        command: AcknowledgementCommand,
        authority: DriverDeliveryAuthority
    ): AcknowledgementResult {
        val before = authorize(authority, DRIVER_START_PERMISSIONS)
        if (before !is Authorization.Current) return before.toAcknowledgementResult()
        val outcome = try {
            api.acknowledgeCriticalInstructions(
                command.deliveryId,
                command.instructionSetVersion,
                command.instructionIds,
                command.idempotencyKey,
                command.frozenBody
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AcknowledgementResult.UnknownOutcome
        }
        if (!currentAfter(authority, before.lease)) {
            return AcknowledgementResult.UnknownOutcome
        }
        return when (outcome) {
            is InstructionsNetworkOutcome.Acknowledged -> {
                val summary = outcome.value.toFeatureSummary()
                if (summary == null) {
                    AcknowledgementResult.UnknownOutcome
                } else {
                    AcknowledgementResult.Acknowledged(summary)
                }
            }

            else -> outcome.toAcknowledgementResult()
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

    private suspend fun authorityDrift(): InstructionsLoadResult =
        if (sessions.sessionState.value != SessionState.Active) {
            InstructionsLoadResult.SessionInvalidated
        } else {
            InstructionsLoadResult.ContextInvalidated
        }

    private fun VerifiedSession.matches(authority: DriverDeliveryAuthority): Boolean =
        hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
            workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
            permissions == authority.permissions

    private fun Authorization.toLoadResult(): InstructionsLoadResult = when (this) {
        Authorization.SessionInvalidated -> InstructionsLoadResult.SessionInvalidated
        Authorization.ContextInvalidated -> InstructionsLoadResult.ContextInvalidated
        Authorization.PermissionDenied -> InstructionsLoadResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Authorization.toAcknowledgementResult(): AcknowledgementResult = when (this) {
        Authorization.SessionInvalidated ->
            AcknowledgementResult.SessionInvalidated

        Authorization.ContextInvalidated ->
            AcknowledgementResult.ContextInvalidated

        Authorization.PermissionDenied ->
            AcknowledgementResult.PermissionDenied

        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun InstructionsNetworkOutcome.toLoadResult(): InstructionsLoadResult = when (this) {
        InstructionsNetworkOutcome.NotFound ->
            InstructionsLoadResult.NotFound

        InstructionsNetworkOutcome.NetworkUnavailable ->
            InstructionsLoadResult.NetworkUnavailable

        InstructionsNetworkOutcome.PermissionDenied ->
            InstructionsLoadResult.PermissionDenied

        InstructionsNetworkOutcome.ContextInvalidated ->
            InstructionsLoadResult.ContextInvalidated

        InstructionsNetworkOutcome.SessionInvalidated ->
            InstructionsLoadResult.SessionInvalidated

        else -> InstructionsLoadResult.ServiceUnavailable
    }

    private fun InstructionsNetworkOutcome.toAcknowledgementResult(): AcknowledgementResult =
        when (this) {
            is InstructionsNetworkOutcome.Rejected ->
                AcknowledgementResult.Rejected(code)

            InstructionsNetworkOutcome.NotFound ->
                AcknowledgementResult.NotFound

            InstructionsNetworkOutcome.StaleVersion ->
                AcknowledgementResult.StaleVersion

            InstructionsNetworkOutcome.UnknownOutcome ->
                AcknowledgementResult.UnknownOutcome

            InstructionsNetworkOutcome.NetworkUnavailable ->
                AcknowledgementResult.UnknownOutcome

            InstructionsNetworkOutcome.ServiceUnavailable ->
                AcknowledgementResult.UnknownOutcome

            InstructionsNetworkOutcome.PermissionDenied ->
                AcknowledgementResult.PermissionDenied

            InstructionsNetworkOutcome.ContextInvalidated ->
                AcknowledgementResult.ContextInvalidated

            InstructionsNetworkOutcome.SessionInvalidated ->
                AcknowledgementResult.SessionInvalidated

            is InstructionsNetworkOutcome.Loaded,
            is InstructionsNetworkOutcome.Acknowledged ->
                AcknowledgementResult.ServiceUnavailable
        }

    private fun AcknowledgementResponseTransport.toFeatureSummary(): AcknowledgementSummary? = try {
        AcknowledgementSummary(
            deliveryId = deliveryId,
            instructionSetVersion = instructionSetVersion,
            acknowledgements = acknowledgements.map { it.toFeatureFact() },
            replayed = replayed
        )
    } catch (_: Exception) {
        null
    }

    private fun AcknowledgementTransport.toFeatureFact() = AcknowledgementFact(
        instructionId,
        instructionVersion,
        acknowledgedByMembershipId,
        acknowledgedAt
    )

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

/** Typed critical-acknowledgement intent in the existing encrypted, identity-scoped store. */
class AppDriverDeliveryInstructionMetadataStore(private val local: AndroidScopedMetadataStore) :
    InstructionMetadataStore {
    override suspend fun loadIntent(scope: DriverAttemptScopeIdentity): InstructionMetadataRead =
        mutex(scope).withLock {
            when (val stored = local.load(scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> InstructionMetadataRead.Unavailable

                is ScopedMetadataRead.Value -> {
                    val payload =
                        stored.payload
                            ?: return@withLock InstructionMetadataRead.Available(null)
                    val intent =
                        decode(payload)
                            ?: return@withLock InstructionMetadataRead.Unavailable
                    if (intent.scope !=
                        scope
                    ) {
                        return@withLock InstructionMetadataRead.Unavailable
                    }
                    if (intent.status == InstructionIntentStatus.Pending) {
                        val recovered = intent.copy(
                            status = InstructionIntentStatus.UnknownOutcome
                        )
                        if (local.save(scope.toLocal(), encode(recovered))) {
                            InstructionMetadataRead.Available(recovered)
                        } else {
                            InstructionMetadataRead.Unavailable
                        }
                    } else {
                        InstructionMetadataRead.Available(intent)
                    }
                }
            }
        }

    override suspend fun saveIntent(intent: InstructionIntentMetadata): InstructionMetadataWrite =
        mutex(intent.scope).withLock {
            when (val stored = local.load(intent.scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> InstructionMetadataWrite.Unavailable

                is ScopedMetadataRead.Value -> {
                    val current = stored.payload?.let(::decode)
                    if (stored.payload != null &&
                        current == null
                    ) {
                        return@withLock InstructionMetadataWrite.Unavailable
                    }
                    when {
                        current == null -> save(intent)

                        current.scope != intent.scope ->
                            InstructionMetadataWrite.Unavailable

                        current.command != intent.command ||
                            current.initiatedByMembershipId != intent.initiatedByMembershipId ->
                            InstructionMetadataWrite.Conflict

                        current.status == InstructionIntentStatus.UnknownOutcome &&
                            intent.status == InstructionIntentStatus.Pending ->
                            InstructionMetadataWrite.Conflict

                        current == intent -> InstructionMetadataWrite.Saved

                        else -> save(intent)
                    }
                }
            }
        }

    override suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): InstructionMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> InstructionMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload =
                    stored.payload ?: return@withLock InstructionMetadataWrite.Saved
                val current =
                    decode(payload)
                        ?: return@withLock InstructionMetadataWrite.Unavailable
                if (current.scope != scope || current.command.idempotencyKey != idempotencyKey) {
                    InstructionMetadataWrite.Stale
                } else if (local.clear(scope.toLocal())) {
                    InstructionMetadataWrite.Saved
                } else {
                    InstructionMetadataWrite.Unavailable
                }
            }
        }
    }

    private suspend fun save(intent: InstructionIntentMetadata): InstructionMetadataWrite =
        if (local.save(intent.scope.toLocal(), encode(intent))) {
            InstructionMetadataWrite.Saved
        } else {
            InstructionMetadataWrite.Unavailable
        }

    private fun encode(intent: InstructionIntentMetadata): String {
        val command = intent.command
        val versions = JsonObject(command.instructionVersions.mapValues { JsonPrimitive(it.value) })
        return JsonObject(
            mapOf(
                "schema" to JsonPrimitive(1),
                "userId" to JsonPrimitive(intent.scope.userId),
                "tenantId" to JsonPrimitive(intent.scope.tenantId),
                "workspaceId" to JsonPrimitive(intent.scope.workspaceId),
                "membershipId" to JsonPrimitive(intent.scope.membershipId),
                "deliveryId" to JsonPrimitive(command.deliveryId),
                "instructionSetVersion" to JsonPrimitive(command.instructionSetVersion),
                "instructionVersions" to versions,
                "idempotencyKey" to JsonPrimitive(command.idempotencyKey),
                "frozenBody" to JsonPrimitive(command.frozenBody),
                "initiatedByMembershipId" to JsonPrimitive(intent.initiatedByMembershipId),
                "initiatedAt" to JsonPrimitive(intent.initiatedAt),
                "status" to JsonPrimitive(intent.status.name)
            )
        ).toString()
    }

    private fun decode(payload: String): InstructionIntentMetadata? = try {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.requiredLong("schema") == 1L)
        val scope = DriverAttemptScopeIdentity(
            root.requiredString("userId"),
            root.requiredString("tenantId"),
            root.requiredString("workspaceId"),
            root.requiredString("membershipId")
        )
        val versionObject =
            root["instructionVersions"]?.jsonObject ?: error("Missing instruction versions")
        val versions = versionObject.mapValues { (_, value) ->
            value.jsonPrimitive.takeUnless(JsonPrimitive::isString)?.longOrNull
                ?: error("Invalid instruction version")
        }
        val deliveryId = root.requiredString("deliveryId")
        val body = root.requiredStringAllowEmpty("frozenBody")
        require(body == driverDeliveryInstructionAcknowledgementBody(versions.keys))
        InstructionIntentMetadata(
            scope = scope,
            command = AcknowledgementCommand(
                deliveryId = deliveryId,
                instructionSetVersion = root.requiredLong("instructionSetVersion"),
                instructionVersions = versions,
                idempotencyKey = root.requiredString("idempotencyKey"),
                frozenBody = body
            ),
            initiatedByMembershipId = root.requiredString("initiatedByMembershipId"),
            initiatedAt = root.requiredString("initiatedAt"),
            status = InstructionIntentStatus.valueOf(root.requiredString("status"))
        )
    } catch (_: Exception) {
        null
    }

    private fun mutex(scope: DriverAttemptScopeIdentity): Mutex = locks.computeIfAbsent(scope) {
        Mutex()
    }

    private fun DriverAttemptScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun JsonObject.requiredString(key: String): String = this[key]?.jsonPrimitive
        ?.takeIf(JsonPrimitive::isString)?.content?.takeIf(String::isNotBlank)
        ?: error("Invalid delivery instruction metadata field")

    private fun JsonObject.requiredStringAllowEmpty(key: String): String = this[key]?.jsonPrimitive
        ?.takeIf(JsonPrimitive::isString)?.content
        ?: error("Invalid delivery instruction metadata field")

    private fun JsonObject.requiredLong(key: String): Long = this[key]?.jsonPrimitive
        ?.takeUnless(JsonPrimitive::isString)?.longOrNull
        ?: error("Invalid delivery instruction metadata field")

    private companion object {
        val locks = ConcurrentHashMap<DriverAttemptScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DriverDeliveryInstructionsModule {
    @Provides
    @Singleton
    fun provideInstructionsApi(protectedCalls: ProtectedCallExecutor) =
        NexaDriverDeliveryInstructionsGateway(protectedCalls)

    @Provides
    @Singleton
    fun provideInstructionsGateway(
        sessions: SessionCoordinator,
        api: NexaDriverDeliveryInstructionsGateway
    ): InstructionsGateway = OperationsDriverDeliveryInstructionsGateway(sessions, api)

    @Provides
    @Singleton
    fun provideInstructionsMetadataStore(
        @ApplicationContext context: Context
    ): InstructionMetadataStore = AppDriverDeliveryInstructionMetadataStore(
        AndroidScopedMetadataStore(
            context,
            ScopedMetadataPurpose.DriverDeliveryInstructionAcknowledgement
        )
    )
}
