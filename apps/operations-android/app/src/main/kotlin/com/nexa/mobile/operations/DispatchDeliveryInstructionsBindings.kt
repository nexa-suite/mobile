package com.nexa.mobile.operations

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.auth.session.AccessTokenLease
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.auth.session.VerifiedSession
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.core.network.NexaOperationalDeliveryInstructionsGateway
import com.nexa.mobile.operations.core.network.OperationalDeliveryInstructionsNetworkResult
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.DispatchDeliveryInstruction
import com.nexa.mobile.operations.feature.dispatch.DispatchDeliveryInstructionIntent
import com.nexa.mobile.operations.feature.dispatch.DispatchDeliveryInstructionIntentStatus
import com.nexa.mobile.operations.feature.dispatch.DispatchDeliveryInstructionKind
import com.nexa.mobile.operations.feature.dispatch.DispatchDeliveryInstructionMetadataRead
import com.nexa.mobile.operations.feature.dispatch.DispatchDeliveryInstructionMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchDeliveryInstructionMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.DispatchDeliveryInstructionReceipt
import com.nexa.mobile.operations.feature.dispatch.DispatchDeliveryInstructionScopeIdentity
import com.nexa.mobile.operations.feature.dispatch.DispatchDeliveryInstructionsGateway
import com.nexa.mobile.operations.feature.dispatch.DispatchDeliveryInstructionsGatewayResult
import com.nexa.mobile.operations.feature.dispatch.DispatchDeliveryInstructionsSnapshot
import com.nexa.mobile.operations.feature.dispatch.DispatchDeliveryInstructionsViewModel
import com.nexa.mobile.operations.feature.dispatch.dispatchDeliveryInstructionRequestBody
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Module
@InstallIn(SingletonComponent::class)
internal object DispatchDeliveryInstructionsBindings {
    @Provides
    @Singleton
    fun operationalDeliveryInstructionsApi(
        protectedCalls: ProtectedCallExecutor
    ): NexaOperationalDeliveryInstructionsGateway = NexaOperationalDeliveryInstructionsGateway(protectedCalls)

    @Provides
    @Singleton
    fun dispatchDeliveryInstructionMetadataStore(
        @ApplicationContext context: Context
    ): DispatchDeliveryInstructionMetadataStore = AppDispatchDeliveryInstructionMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DispatchDeliveryInstructionCommand)
    )

    @Provides
    @Singleton
    fun dispatchDeliveryInstructionsGateway(
        operations: OperationsDispatchDeliveryInstructionsGateway
    ): DispatchDeliveryInstructionsGateway = operations
}

/** Verifies current native session before and after each scoped Dispatch instruction call. */
@Singleton
internal class OperationsDispatchDeliveryInstructionsGateway @Inject constructor(
    private val sessions: SessionCoordinator,
    private val api: NexaOperationalDeliveryInstructionsGateway
) : DispatchDeliveryInstructionsGateway {
    override suspend fun currentInstructions(
        deliveryId: String,
        context: DispatchAuthorityContext
    ): DispatchDeliveryInstructionsGatewayResult {
        val before = authorize(context, mutation = false)
        if (before !is Authorization.Current) return before.toResult()
        val result = try {
            api.currentInstructions(deliveryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DispatchDeliveryInstructionsGatewayResult.ServiceUnavailable
        }
        if (!isCurrent(context, before.lease)) return authorityDrift()
        return when (result) {
            is OperationalDeliveryInstructionsNetworkResult.Current -> try {
                val value = result.value
                DispatchDeliveryInstructionsGatewayResult.Snapshot(
                    DispatchDeliveryInstructionsSnapshot(
                        deliveryId = value.deliveryId,
                        deliveryVersion = value.deliveryVersion,
                        instructionSetVersion = value.instructionSetVersion,
                        instructions = value.instructions.map { row ->
                            DispatchDeliveryInstruction(
                                id = row.id,
                                kind = DispatchDeliveryInstructionKind.valueOf(row.kind),
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
                DispatchDeliveryInstructionsGatewayResult.ServiceUnavailable
            }

            else -> result.toFeature()
        }
    }

    override suspend fun publish(
        intent: DispatchDeliveryInstructionIntent,
        context: DispatchAuthorityContext
    ): DispatchDeliveryInstructionsGatewayResult {
        val before = authorize(context, mutation = true)
        if (before !is Authorization.Current) return before.toResult()
        if (intent.scope != context.scopeIdentity() || intent.expectedDeliveryVersion < 0 ||
            intent.idempotencyKey.isBlank() ||
            intent.exactRequestBody != dispatchDeliveryInstructionRequestBody(
                intent.instructionId, intent.kind, intent.content
            )
        ) return DispatchDeliveryInstructionsGatewayResult.ServiceUnavailable

        val result = try {
            api.publish(
                intent.deliveryId,
                intent.expectedDeliveryVersion,
                intent.idempotencyKey,
                intent.exactRequestBody
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DispatchDeliveryInstructionsGatewayResult.UnknownOutcome
        }
        if (!isCurrent(context, before.lease)) return DispatchDeliveryInstructionsGatewayResult.UnknownOutcome
        return when (result) {
            is OperationalDeliveryInstructionsNetworkResult.Published -> {
                val value = result.value
                DispatchDeliveryInstructionsGatewayResult.Published(
                    DispatchDeliveryInstructionReceipt(
                        deliveryId = value.deliveryId,
                        instructionId = value.instructionId,
                        kind = DispatchDeliveryInstructionKind.valueOf(value.kind),
                        content = value.content,
                        instructionVersion = value.instructionVersion,
                        critical = value.critical,
                        deliveryVersion = value.deliveryVersion,
                        instructionSetVersion = value.instructionSetVersion,
                        replayed = value.replayed
                    )
                )
            }

            else -> result.toFeature()
        }
    }

    private suspend fun authorize(context: DispatchAuthorityContext, mutation: Boolean): Authorization {
        if (sessions.sessionState.value != SessionState.Active) return Authorization.SessionInvalidated
        val lease = sessions.currentAccess() ?: return Authorization.SessionInvalidated
        val identity = context.identity ?: return Authorization.ContextInvalidated
        val verified = sessions.verifiedSession.value ?: return Authorization.ContextInvalidated
        if (context.authorityEpoch <= 0 || listOf(
                identity.userId, identity.tenantId, identity.workspaceId, identity.membershipId
            ).any(String::isBlank) || !verified.matches(identity)
        ) return Authorization.ContextInvalidated
        if (DISPATCH_READ !in identity.permissions || mutation && DISPATCH_SCHEDULE !in identity.permissions) {
            return Authorization.PermissionDenied
        }
        return Authorization.Current(lease)
    }

    private suspend fun isCurrent(
        context: DispatchAuthorityContext,
        lease: AccessTokenLease
    ): Boolean = sessions.sessionState.value == SessionState.Active && sessions.isEpochCurrent(lease.epoch) &&
        context.identity?.let { identity -> sessions.verifiedSession.value?.matches(identity) } == true

    private suspend fun authorityDrift() = if (sessions.sessionState.value != SessionState.Active) {
        DispatchDeliveryInstructionsGatewayResult.SessionInvalidated
    } else {
        DispatchDeliveryInstructionsGatewayResult.ContextInvalidated
    }

    private fun VerifiedSession.matches(identity: DispatchAuthorityIdentity): Boolean =
        hasAuthorizedContext && userId == identity.userId && tenantId == identity.tenantId &&
            workspaceId == identity.workspaceId && membershipId == identity.membershipId &&
            permissions == identity.permissions

    private fun DispatchAuthorityContext.scopeIdentity(): DispatchDeliveryInstructionScopeIdentity? = identity?.let {
        DispatchDeliveryInstructionScopeIdentity(it.userId, it.tenantId, it.workspaceId, it.membershipId)
    }

    private fun Authorization.toResult() = when (this) {
        Authorization.SessionInvalidated -> DispatchDeliveryInstructionsGatewayResult.SessionInvalidated
        Authorization.ContextInvalidated -> DispatchDeliveryInstructionsGatewayResult.ContextInvalidated
        Authorization.PermissionDenied -> DispatchDeliveryInstructionsGatewayResult.PermissionDenied
        is Authorization.Current -> error("authorized result is not a failure")
    }

    private fun OperationalDeliveryInstructionsNetworkResult.toFeature() = when (this) {
        is OperationalDeliveryInstructionsNetworkResult.Rejected -> DispatchDeliveryInstructionsGatewayResult.Rejected(code)
        OperationalDeliveryInstructionsNetworkResult.NotFound -> DispatchDeliveryInstructionsGatewayResult.NotFound
        OperationalDeliveryInstructionsNetworkResult.StaleVersion -> DispatchDeliveryInstructionsGatewayResult.StaleVersion
        OperationalDeliveryInstructionsNetworkResult.UnknownOutcome -> DispatchDeliveryInstructionsGatewayResult.UnknownOutcome
        OperationalDeliveryInstructionsNetworkResult.NetworkUnavailable -> DispatchDeliveryInstructionsGatewayResult.NetworkUnavailable
        OperationalDeliveryInstructionsNetworkResult.ServiceUnavailable -> DispatchDeliveryInstructionsGatewayResult.ServiceUnavailable
        OperationalDeliveryInstructionsNetworkResult.PermissionDenied -> DispatchDeliveryInstructionsGatewayResult.PermissionDenied
        OperationalDeliveryInstructionsNetworkResult.ContextInvalidated -> DispatchDeliveryInstructionsGatewayResult.ContextInvalidated
        OperationalDeliveryInstructionsNetworkResult.SessionInvalidated -> DispatchDeliveryInstructionsGatewayResult.SessionInvalidated
        is OperationalDeliveryInstructionsNetworkResult.Current,
        is OperationalDeliveryInstructionsNetworkResult.Published -> DispatchDeliveryInstructionsGatewayResult.ServiceUnavailable
    }

    private sealed interface Authorization {
        data object SessionInvalidated : Authorization
        data object ContextInvalidated : Authorization
        data object PermissionDenied : Authorization
        data class Current(val lease: AccessTokenLease) : Authorization
    }

    private companion object {
        const val DISPATCH_READ = "dispatch.read"
        const val DISPATCH_SCHEDULE = "dispatch.schedule"
    }
}

/** Encrypted, scope-bound exact-command recovery before Dispatch publication. */
internal class AppDispatchDeliveryInstructionMetadataStore(
    private val local: ScopedMetadataStore
) : DispatchDeliveryInstructionMetadataStore {
    override suspend fun loadIntent(
        scope: DispatchDeliveryInstructionScopeIdentity,
        deliveryId: String
    ): DispatchDeliveryInstructionMetadataRead = mutex(scope).withLock {
        when (val stored = safeLoad(scope)) {
            ScopedMetadataRead.Unavailable -> DispatchDeliveryInstructionMetadataRead.Unavailable
            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) emptyList() else {
                        return@withLock DispatchDeliveryInstructionMetadataRead.Unavailable
                    }
                val intent = intents.firstOrNull { it.deliveryId == deliveryId }
                    ?: return@withLock DispatchDeliveryInstructionMetadataRead.Available(null)
                if (intent.scope != scope) return@withLock DispatchDeliveryInstructionMetadataRead.Unavailable
                if (intent.status == DispatchDeliveryInstructionIntentStatus.Pending) {
                    val recovered = intent.copy(status = DispatchDeliveryInstructionIntentStatus.UnknownOutcome)
                    val updated = intents.map { if (it.deliveryId == deliveryId) recovered else it }
                    if (write(scope, updated)) DispatchDeliveryInstructionMetadataRead.Available(recovered)
                    else DispatchDeliveryInstructionMetadataRead.Unavailable
                } else {
                    DispatchDeliveryInstructionMetadataRead.Available(intent)
                }
            }
        }
    }

    override suspend fun saveIntent(
        intent: DispatchDeliveryInstructionIntent
    ): DispatchDeliveryInstructionMetadataWrite = mutex(intent.scope).withLock {
        when (val stored = safeLoad(intent.scope)) {
            ScopedMetadataRead.Unavailable -> DispatchDeliveryInstructionMetadataWrite.Unavailable
            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) emptyList() else {
                        return@withLock DispatchDeliveryInstructionMetadataWrite.Unavailable
                    }
                val current = intents.firstOrNull { it.deliveryId == intent.deliveryId }
                when {
                    current == null && intents.size >= MAX_PENDING_COMMANDS ->
                        DispatchDeliveryInstructionMetadataWrite.Unavailable

                    current == null -> write(intent.scope, intents + intent).toWriteResult()
                    current.scope != intent.scope || !current.sameCommand(intent) ->
                        DispatchDeliveryInstructionMetadataWrite.Conflict

                    current.status == DispatchDeliveryInstructionIntentStatus.UnknownOutcome &&
                        intent.status == DispatchDeliveryInstructionIntentStatus.Pending ->
                        DispatchDeliveryInstructionMetadataWrite.Conflict

                    current == intent -> DispatchDeliveryInstructionMetadataWrite.Saved
                    else -> write(
                        intent.scope,
                        intents.map { if (it.deliveryId == intent.deliveryId) intent else it }
                    ).toWriteResult()
                }
            }
        }
    }

    override suspend fun clearIntent(
        scope: DispatchDeliveryInstructionScopeIdentity,
        deliveryId: String,
        idempotencyKey: String
    ): DispatchDeliveryInstructionMetadataWrite = mutex(scope).withLock {
        when (val stored = safeLoad(scope)) {
            ScopedMetadataRead.Unavailable -> DispatchDeliveryInstructionMetadataWrite.Unavailable
            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) emptyList() else {
                        return@withLock DispatchDeliveryInstructionMetadataWrite.Unavailable
                    }
                val current = intents.firstOrNull { it.deliveryId == deliveryId }
                    ?: return@withLock DispatchDeliveryInstructionMetadataWrite.Saved
                if (current.scope != scope || current.idempotencyKey != idempotencyKey) {
                    return@withLock DispatchDeliveryInstructionMetadataWrite.Stale
                }
                val remaining = intents.filterNot { it.deliveryId == deliveryId }
                val saved = if (remaining.isEmpty()) safeClear(scope) else write(scope, remaining)
                saved.toWriteResult()
            }
        }
    }

    private suspend fun safeLoad(scope: DispatchDeliveryInstructionScopeIdentity): ScopedMetadataRead = try {
        local.load(scope.toLocal())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ScopedMetadataRead.Unavailable
    }

    private suspend fun safeClear(scope: DispatchDeliveryInstructionScopeIdentity): Boolean = try {
        local.clear(scope.toLocal())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun write(
        scope: DispatchDeliveryInstructionScopeIdentity,
        intents: List<DispatchDeliveryInstructionIntent>
    ): Boolean = try {
        local.save(scope.toLocal(), encode(intents))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun encode(intents: List<DispatchDeliveryInstructionIntent>): String = buildJsonObject {
        put("schema", JsonPrimitive(SCHEMA_VERSION))
        put("commands", JsonArray(intents.map(::encodeIntent)))
    }.toString()

    private fun encodeIntent(intent: DispatchDeliveryInstructionIntent): JsonObject = buildJsonObject {
        put("userId", JsonPrimitive(intent.scope.userId))
        put("tenantId", JsonPrimitive(intent.scope.tenantId))
        put("workspaceId", JsonPrimitive(intent.scope.workspaceId))
        put("membershipId", JsonPrimitive(intent.scope.membershipId))
        put("deliveryId", JsonPrimitive(intent.deliveryId))
        put("expectedDeliveryVersion", JsonPrimitive(intent.expectedDeliveryVersion))
        intent.instructionId?.let { put("instructionId", JsonPrimitive(it)) }
        put("kind", JsonPrimitive(intent.kind.name))
        put("content", JsonPrimitive(intent.content))
        put("exactRequestBody", JsonPrimitive(intent.exactRequestBody))
        put("idempotencyKey", JsonPrimitive(intent.idempotencyKey))
        put("status", JsonPrimitive(intent.status.name))
    }

    private fun decode(payload: String): List<DispatchDeliveryInstructionIntent>? = try {
        val envelope = Json.parseToJsonElement(payload).jsonObject
        if (envelope.requiredLong("schema") != SCHEMA_VERSION) {
            null
        } else {
            val intents = envelope["commands"]?.jsonArray?.map { element ->
                val value = element.jsonObject
                val scope = DispatchDeliveryInstructionScopeIdentity(
                    value.requiredString("userId"), value.requiredString("tenantId"),
                    value.requiredString("workspaceId"), value.requiredString("membershipId")
                )
                val deliveryId = value.requiredString("deliveryId")
                val expectedVersion = value.requiredLong("expectedDeliveryVersion")
                val instructionId = value.optionalString("instructionId")
                val kind = DispatchDeliveryInstructionKind.valueOf(value.requiredString("kind"))
                val content = value.requiredString("content")
                val body = value.requiredString("exactRequestBody")
                val key = value.requiredString("idempotencyKey")
                val status = DispatchDeliveryInstructionIntentStatus.valueOf(value.requiredString("status"))
                if (!UUID_PATTERN.matches(deliveryId) || expectedVersion < 0 ||
                    instructionId != null && !UUID_PATTERN.matches(instructionId) || content.isBlank() ||
                    content.length > DispatchDeliveryInstructionsUiState.MAX_INSTRUCTION_CONTENT ||
                    body != dispatchDeliveryInstructionRequestBody(instructionId, kind, content) ||
                    key.length !in 1..160
                ) error("Stored Dispatch instruction command is invalid")
                DispatchDeliveryInstructionIntent(
                    scope, deliveryId, expectedVersion, instructionId, kind, content, body, key, status
                )
            } ?: error("Stored Dispatch instruction commands are missing")
            if (intents.map { it.deliveryId }.distinct().size != intents.size ||
                intents.map { it.idempotencyKey }.distinct().size != intents.size
            ) null else intents
        }
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.requiredString(key: String): String = this[key]?.jsonPrimitive
        ?.takeIf(JsonPrimitive::isString)?.content?.takeIf(String::isNotBlank)
        ?: error("Stored Dispatch instruction field is invalid")

    private fun JsonObject.optionalString(key: String): String? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.content
        else -> error("Stored Dispatch instruction field is invalid")
    }

    private fun JsonObject.requiredLong(key: String): Long = this[key]?.jsonPrimitive
        ?.takeUnless(JsonPrimitive::isString)?.longOrNull ?: error("Stored Dispatch instruction number is invalid")

    private fun DispatchDeliveryInstructionIntent.sameCommand(
        other: DispatchDeliveryInstructionIntent
    ): Boolean = scope == other.scope && deliveryId == other.deliveryId &&
        expectedDeliveryVersion == other.expectedDeliveryVersion && instructionId == other.instructionId &&
        kind == other.kind && content == other.content && exactRequestBody == other.exactRequestBody &&
        idempotencyKey == other.idempotencyKey

    private fun DispatchDeliveryInstructionScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun Boolean.toWriteResult() = if (this) DispatchDeliveryInstructionMetadataWrite.Saved
    else DispatchDeliveryInstructionMetadataWrite.Unavailable

    private fun mutex(scope: DispatchDeliveryInstructionScopeIdentity): Mutex =
        locks.computeIfAbsent(scope) { Mutex() }

    private companion object {
        const val SCHEMA_VERSION = 1L
        const val MAX_PENDING_COMMANDS = 32
        val UUID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val locks = ConcurrentHashMap<DispatchDeliveryInstructionScopeIdentity, Mutex>()
    }
}

internal class DispatchDeliveryInstructionsViewModelFactory @Inject constructor(
    private val gateway: DispatchDeliveryInstructionsGateway,
    private val metadata: DispatchDeliveryInstructionMetadataStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(DispatchDeliveryInstructionsViewModel::class.java))
        return DispatchDeliveryInstructionsViewModel(gateway, metadata) as T
    }
}
