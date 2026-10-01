package com.nexa.mobile.operations

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.network.DriverDeliveryInstructionAcknowledgementResponseTransport
import com.nexa.mobile.operations.core.network.DriverDeliveryInstructionAcknowledgementTransport
import com.nexa.mobile.operations.core.network.DriverDeliveryInstructionsNetworkOutcome
import com.nexa.mobile.operations.core.network.NexaDriverDeliveryInstructionsGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstruction
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionAcknowledgementCommand
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionAcknowledgementFact
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionAcknowledgementResult
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionAcknowledgementSummary
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionIntentMetadata
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionIntentStatus
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionKind
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionMetadataRead
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionMetadataStore
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionMetadataWrite
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionsGateway
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionsLoadResult
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionsSnapshot
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionsViewModel
import com.nexa.mobile.operations.feature.delivery.driverDeliveryInstructionAcknowledgementBody
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Singleton
internal class OperationsDriverDeliveryInstructionsGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val api: NexaDriverDeliveryInstructionsGateway
) : DriverDeliveryInstructionsGateway {
    override suspend fun currentInstructions(
        deliveryId: String,
        authority: DriverDeliveryAuthority
    ): DriverDeliveryInstructionsLoadResult {
        val before = authorize(authority, DRIVER_READ_PERMISSIONS)
        if (before !is Authorization.Current) return before.toLoadResult()
        val outcome = try {
            api.currentInstructions(deliveryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DriverDeliveryInstructionsLoadResult.ServiceUnavailable
        }
        if (!currentAfter(authority, before.lease)) return authorityDrift()
        return when (outcome) {
            is DriverDeliveryInstructionsNetworkOutcome.Loaded -> try {
                DriverDeliveryInstructionsLoadResult.Loaded(
                    DriverDeliveryInstructionsSnapshot(
                        deliveryId = outcome.value.deliveryId,
                        deliveryVersion = outcome.value.deliveryVersion,
                        instructionSetVersion = outcome.value.instructionSetVersion,
                        instructions = outcome.value.instructions.map { row ->
                            DriverDeliveryInstruction(
                                id = row.id,
                                kind = DriverDeliveryInstructionKind.valueOf(row.kind),
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
                DriverDeliveryInstructionsLoadResult.ServiceUnavailable
            }

            else -> outcome.toLoadResult()
        }
    }

    override suspend fun acknowledgeCriticalInstructions(
        command: DriverDeliveryInstructionAcknowledgementCommand,
        authority: DriverDeliveryAuthority
    ): DriverDeliveryInstructionAcknowledgementResult {
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
            return DriverDeliveryInstructionAcknowledgementResult.UnknownOutcome
        }
        if (!currentAfter(authority, before.lease)) {
            return DriverDeliveryInstructionAcknowledgementResult.UnknownOutcome
        }
        return when (outcome) {
            is DriverDeliveryInstructionsNetworkOutcome.Acknowledged -> {
                val summary = outcome.value.toFeatureSummary()
                if (summary == null) DriverDeliveryInstructionAcknowledgementResult.UnknownOutcome
                else DriverDeliveryInstructionAcknowledgementResult.Acknowledged(summary)
            }

            else -> outcome.toAcknowledgementResult()
        }
    }

    private suspend fun authorize(
        authority: DriverDeliveryAuthority,
        requiredPermissions: Set<String>
    ): Authorization {
        if (sessions.sessionState.value != SessionState.Active) return Authorization.SessionInvalidated
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (!verified.matches(authority)) return Authorization.ContextInvalidated
        if (authority.permissions.none { it in requiredPermissions }) return Authorization.PermissionDenied
        return Authorization.Current(lease)
    }

    private suspend fun currentAfter(
        authority: DriverDeliveryAuthority,
        originalLease: AccessTokenLease
    ): Boolean = sessions.sessionState.value == SessionState.Active &&
        sessions.isEpochCurrent(originalLease.epoch) && sessions.verifiedSession.value?.matches(authority) == true

    private suspend fun authorityDrift(): DriverDeliveryInstructionsLoadResult =
        if (sessions.sessionState.value != SessionState.Active) {
            DriverDeliveryInstructionsLoadResult.SessionInvalidated
        } else {
            DriverDeliveryInstructionsLoadResult.ContextInvalidated
        }

    private fun com.nexa.mobile.operations.core.auth.session.VerifiedSession.matches(
        authority: DriverDeliveryAuthority
    ): Boolean = hasAuthorizedContext && userId == authority.userId && tenantId == authority.tenantId &&
        workspaceId == authority.workspaceId && membershipId == authority.membershipId &&
        permissions == authority.permissions

    private fun Authorization.toLoadResult(): DriverDeliveryInstructionsLoadResult = when (this) {
        Authorization.SessionInvalidated -> DriverDeliveryInstructionsLoadResult.SessionInvalidated
        Authorization.ContextInvalidated -> DriverDeliveryInstructionsLoadResult.ContextInvalidated
        Authorization.PermissionDenied -> DriverDeliveryInstructionsLoadResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun Authorization.toAcknowledgementResult(): DriverDeliveryInstructionAcknowledgementResult = when (this) {
        Authorization.SessionInvalidated -> DriverDeliveryInstructionAcknowledgementResult.SessionInvalidated
        Authorization.ContextInvalidated -> DriverDeliveryInstructionAcknowledgementResult.ContextInvalidated
        Authorization.PermissionDenied -> DriverDeliveryInstructionAcknowledgementResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun DriverDeliveryInstructionsNetworkOutcome.toLoadResult(): DriverDeliveryInstructionsLoadResult = when (this) {
        DriverDeliveryInstructionsNetworkOutcome.NotFound -> DriverDeliveryInstructionsLoadResult.NotFound
        DriverDeliveryInstructionsNetworkOutcome.NetworkUnavailable -> DriverDeliveryInstructionsLoadResult.NetworkUnavailable
        DriverDeliveryInstructionsNetworkOutcome.PermissionDenied -> DriverDeliveryInstructionsLoadResult.PermissionDenied
        DriverDeliveryInstructionsNetworkOutcome.ContextInvalidated -> DriverDeliveryInstructionsLoadResult.ContextInvalidated
        DriverDeliveryInstructionsNetworkOutcome.SessionInvalidated -> DriverDeliveryInstructionsLoadResult.SessionInvalidated
        else -> DriverDeliveryInstructionsLoadResult.ServiceUnavailable
    }

    private fun DriverDeliveryInstructionsNetworkOutcome.toAcknowledgementResult():
        DriverDeliveryInstructionAcknowledgementResult = when (this) {
        is DriverDeliveryInstructionsNetworkOutcome.Rejected ->
            DriverDeliveryInstructionAcknowledgementResult.Rejected(code)
        DriverDeliveryInstructionsNetworkOutcome.NotFound -> DriverDeliveryInstructionAcknowledgementResult.NotFound
        DriverDeliveryInstructionsNetworkOutcome.StaleVersion -> DriverDeliveryInstructionAcknowledgementResult.StaleVersion
        DriverDeliveryInstructionsNetworkOutcome.UnknownOutcome -> DriverDeliveryInstructionAcknowledgementResult.UnknownOutcome
        DriverDeliveryInstructionsNetworkOutcome.NetworkUnavailable -> DriverDeliveryInstructionAcknowledgementResult.UnknownOutcome
        DriverDeliveryInstructionsNetworkOutcome.ServiceUnavailable -> DriverDeliveryInstructionAcknowledgementResult.UnknownOutcome
        DriverDeliveryInstructionsNetworkOutcome.PermissionDenied -> DriverDeliveryInstructionAcknowledgementResult.PermissionDenied
        DriverDeliveryInstructionsNetworkOutcome.ContextInvalidated -> DriverDeliveryInstructionAcknowledgementResult.ContextInvalidated
        DriverDeliveryInstructionsNetworkOutcome.SessionInvalidated -> DriverDeliveryInstructionAcknowledgementResult.SessionInvalidated
        is DriverDeliveryInstructionsNetworkOutcome.Loaded,
        is DriverDeliveryInstructionsNetworkOutcome.Acknowledged ->
            DriverDeliveryInstructionAcknowledgementResult.ServiceUnavailable
    }

    private fun DriverDeliveryInstructionAcknowledgementResponseTransport.toFeatureSummary():
        DriverDeliveryInstructionAcknowledgementSummary? = try {
        DriverDeliveryInstructionAcknowledgementSummary(
            deliveryId = deliveryId,
            instructionSetVersion = instructionSetVersion,
            acknowledgements = acknowledgements.map { it.toFeatureFact() },
            replayed = replayed
        )
    } catch (_: Exception) {
        null
    }

    private fun DriverDeliveryInstructionAcknowledgementTransport.toFeatureFact() =
        DriverDeliveryInstructionAcknowledgementFact(
            instructionId, instructionVersion, acknowledgedByMembershipId, acknowledgedAt
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

internal class DriverDeliveryInstructionsBindings @Inject constructor(
    private val gateway: DriverDeliveryInstructionsGateway,
    private val metadataStore: DriverDeliveryInstructionMetadataStore
) {
    fun viewModelFactory() = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DriverDeliveryInstructionsViewModel::class.java))
            return DriverDeliveryInstructionsViewModel(gateway, metadataStore) as T
        }
    }
}

/** Typed critical-acknowledgement intent in the existing encrypted, identity-scoped store. */
internal class AppDriverDeliveryInstructionMetadataStore(
    private val local: AndroidScopedMetadataStore
) : DriverDeliveryInstructionMetadataStore {
    override suspend fun loadIntent(
        scope: DriverAttemptScopeIdentity
    ): DriverDeliveryInstructionMetadataRead = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DriverDeliveryInstructionMetadataRead.Unavailable
            is ScopedMetadataRead.Value -> {
                val payload = stored.payload ?: return@withLock DriverDeliveryInstructionMetadataRead.Available(null)
                val intent = decode(payload) ?: return@withLock DriverDeliveryInstructionMetadataRead.Unavailable
                if (intent.scope != scope) return@withLock DriverDeliveryInstructionMetadataRead.Unavailable
                if (intent.status == DriverDeliveryInstructionIntentStatus.Pending) {
                    val recovered = intent.copy(status = DriverDeliveryInstructionIntentStatus.UnknownOutcome)
                    if (local.save(scope.toLocal(), encode(recovered))) {
                        DriverDeliveryInstructionMetadataRead.Available(recovered)
                    } else {
                        DriverDeliveryInstructionMetadataRead.Unavailable
                    }
                } else {
                    DriverDeliveryInstructionMetadataRead.Available(intent)
                }
            }
        }
    }

    override suspend fun saveIntent(
        intent: DriverDeliveryInstructionIntentMetadata
    ): DriverDeliveryInstructionMetadataWrite = mutex(intent.scope).withLock {
        when (val stored = local.load(intent.scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DriverDeliveryInstructionMetadataWrite.Unavailable
            is ScopedMetadataRead.Value -> {
                val current = stored.payload?.let(::decode)
                if (stored.payload != null && current == null) return@withLock DriverDeliveryInstructionMetadataWrite.Unavailable
                when {
                    current == null -> save(intent)
                    current.scope != intent.scope -> DriverDeliveryInstructionMetadataWrite.Unavailable
                    current.command != intent.command || current.initiatedByMembershipId != intent.initiatedByMembershipId ->
                        DriverDeliveryInstructionMetadataWrite.Conflict
                    current.status == DriverDeliveryInstructionIntentStatus.UnknownOutcome &&
                        intent.status == DriverDeliveryInstructionIntentStatus.Pending ->
                        DriverDeliveryInstructionMetadataWrite.Conflict
                    current == intent -> DriverDeliveryInstructionMetadataWrite.Saved
                    else -> save(intent)
                }
            }
        }
    }

    override suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverDeliveryInstructionMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DriverDeliveryInstructionMetadataWrite.Unavailable
            is ScopedMetadataRead.Value -> {
                val payload = stored.payload ?: return@withLock DriverDeliveryInstructionMetadataWrite.Saved
                val current = decode(payload) ?: return@withLock DriverDeliveryInstructionMetadataWrite.Unavailable
                if (current.scope != scope || current.command.idempotencyKey != idempotencyKey) {
                    DriverDeliveryInstructionMetadataWrite.Stale
                } else if (local.clear(scope.toLocal())) {
                    DriverDeliveryInstructionMetadataWrite.Saved
                } else {
                    DriverDeliveryInstructionMetadataWrite.Unavailable
                }
            }
        }
    }

    private suspend fun save(intent: DriverDeliveryInstructionIntentMetadata): DriverDeliveryInstructionMetadataWrite =
        if (local.save(intent.scope.toLocal(), encode(intent))) {
            DriverDeliveryInstructionMetadataWrite.Saved
        } else {
            DriverDeliveryInstructionMetadataWrite.Unavailable
        }

    private fun encode(intent: DriverDeliveryInstructionIntentMetadata): String {
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

    private fun decode(payload: String): DriverDeliveryInstructionIntentMetadata? = try {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.requiredLong("schema") == 1L)
        val scope = DriverAttemptScopeIdentity(
            root.requiredString("userId"), root.requiredString("tenantId"),
            root.requiredString("workspaceId"), root.requiredString("membershipId")
        )
        val versionObject = root["instructionVersions"]?.jsonObject ?: error("Missing instruction versions")
        val versions = versionObject.mapValues { (_, value) ->
            value.jsonPrimitive.takeUnless(JsonPrimitive::isString)?.longOrNull
                ?: error("Invalid instruction version")
        }
        val deliveryId = root.requiredString("deliveryId")
        val body = root.requiredStringAllowEmpty("frozenBody")
        require(body == driverDeliveryInstructionAcknowledgementBody(versions.keys))
        DriverDeliveryInstructionIntentMetadata(
            scope = scope,
            command = DriverDeliveryInstructionAcknowledgementCommand(
                deliveryId = deliveryId,
                instructionSetVersion = root.requiredLong("instructionSetVersion"),
                instructionVersions = versions,
                idempotencyKey = root.requiredString("idempotencyKey"),
                frozenBody = body
            ),
            initiatedByMembershipId = root.requiredString("initiatedByMembershipId"),
            initiatedAt = root.requiredString("initiatedAt"),
            status = DriverDeliveryInstructionIntentStatus.valueOf(root.requiredString("status"))
        )
    } catch (_: Exception) {
        null
    }

    private fun mutex(scope: DriverAttemptScopeIdentity): Mutex = locks.computeIfAbsent(scope) { Mutex() }

    private fun DriverAttemptScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun JsonObject.requiredString(key: String): String = this[key]?.jsonPrimitive
        ?.takeIf(JsonPrimitive::isString)?.content?.takeIf(String::isNotBlank)
        ?: error("Invalid delivery instruction metadata field")

    private fun JsonObject.requiredStringAllowEmpty(key: String): String = this[key]?.jsonPrimitive
        ?.takeIf(JsonPrimitive::isString)?.content ?: error("Invalid delivery instruction metadata field")

    private fun JsonObject.requiredLong(key: String): Long = this[key]?.jsonPrimitive
        ?.takeUnless(JsonPrimitive::isString)?.longOrNull ?: error("Invalid delivery instruction metadata field")

    private companion object {
        val locks = ConcurrentHashMap<DriverAttemptScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DriverDeliveryInstructionsModule {
    @Provides
    @Singleton
    fun provideInstructionsApi(protectedCalls: ProtectedCallExecutor) =
        NexaDriverDeliveryInstructionsGateway(protectedCalls)

    @Provides
    @Singleton
    fun provideInstructionsGateway(
        sessions: SessionCoordinator,
        api: NexaDriverDeliveryInstructionsGateway
    ): DriverDeliveryInstructionsGateway = OperationsDriverDeliveryInstructionsGateway(sessions, api)

    @Provides
    @Singleton
    fun provideInstructionsMetadataStore(
        @ApplicationContext context: Context
    ): DriverDeliveryInstructionMetadataStore = AppDriverDeliveryInstructionMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DriverDeliveryInstructionAcknowledgement)
    )
}
