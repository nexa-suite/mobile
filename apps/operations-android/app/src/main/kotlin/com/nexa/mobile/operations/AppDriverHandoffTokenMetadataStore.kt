package com.nexa.mobile.operations

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.feature.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.DriverHandoffIssueCommand
import com.nexa.mobile.operations.feature.delivery.DriverHandoffMetadataRead
import com.nexa.mobile.operations.feature.delivery.DriverHandoffMetadataWrite
import com.nexa.mobile.operations.feature.delivery.DriverHandoffTokenMetadataStore as HandoffTokenMetadataStore
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Stores idempotency commands only. Raw one-time handoff tokens never enter local storage. */
internal class AppDriverHandoffTokenMetadataStore(private val local: AndroidScopedMetadataStore) :
    HandoffTokenMetadataStore {
    override suspend fun load(
        scope: DriverAttemptScopeIdentity,
        deliveryId: String,
        attemptId: String
    ): DriverHandoffMetadataRead = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DriverHandoffMetadataRead.Unavailable

            is ScopedMetadataRead.Value -> {
                val commands = decode(stored.payload, scope)
                    ?: return@withLock DriverHandoffMetadataRead.Unavailable
                DriverHandoffMetadataRead.Available(
                    commands.firstOrNull {
                        it.deliveryId == deliveryId && it.attemptId == attemptId
                    }
                )
            }
        }
    }

    override suspend fun persistIntent(
        scope: DriverAttemptScopeIdentity,
        command: DriverHandoffIssueCommand
    ): DriverHandoffMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DriverHandoffMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val commands = decode(stored.payload, scope)
                    ?: return@withLock DriverHandoffMetadataWrite.Unavailable
                val existing = commands.firstOrNull {
                    it.deliveryId == command.deliveryId && it.attemptId == command.attemptId
                }
                when {
                    existing == command -> DriverHandoffMetadataWrite.Saved

                    existing != null -> DriverHandoffMetadataWrite.Conflict

                    commands.size >= MAX_COMMANDS -> DriverHandoffMetadataWrite.Unavailable

                    local.save(scope.toLocal(), encode(scope, commands + command)) ->
                        DriverHandoffMetadataWrite.Saved

                    else -> DriverHandoffMetadataWrite.Unavailable
                }
            }
        }
    }

    override suspend fun clearKnownRejection(
        scope: DriverAttemptScopeIdentity,
        deliveryId: String,
        attemptId: String,
        idempotencyKey: String
    ): DriverHandoffMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DriverHandoffMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val commands = decode(stored.payload, scope)
                    ?: return@withLock DriverHandoffMetadataWrite.Unavailable
                val existing = commands.firstOrNull {
                    it.deliveryId == deliveryId && it.attemptId == attemptId
                } ?: return@withLock DriverHandoffMetadataWrite.Saved
                if (existing.idempotencyKey != idempotencyKey) {
                    DriverHandoffMetadataWrite.Stale
                } else {
                    val remaining = commands.filterNot { it == existing }
                    if (local.save(scope.toLocal(), encode(scope, remaining))) {
                        DriverHandoffMetadataWrite.Saved
                    } else {
                        DriverHandoffMetadataWrite.Unavailable
                    }
                }
            }
        }
    }

    private fun encode(
        scope: DriverAttemptScopeIdentity,
        commands: List<DriverHandoffIssueCommand>
    ) = JsonObject(
        linkedMapOf(
            "schema" to JsonPrimitive(SCHEMA),
            "userId" to JsonPrimitive(scope.userId),
            "tenantId" to JsonPrimitive(scope.tenantId),
            "workspaceId" to JsonPrimitive(scope.workspaceId),
            "membershipId" to JsonPrimitive(scope.membershipId),
            "commands" to JsonArray(commands.map(::encodeCommand))
        )
    ).toString()

    private fun decode(
        payload: String?,
        scope: DriverAttemptScopeIdentity
    ): List<DriverHandoffIssueCommand>? {
        if (payload == null) return emptyList()
        return try {
            val root = Json.parseToJsonElement(payload).jsonObject
            require(root.requiredLong("schema") == SCHEMA)
            require(root.requiredString("userId") == scope.userId)
            require(root.requiredString("tenantId") == scope.tenantId)
            require(root.requiredString("workspaceId") == scope.workspaceId)
            require(root.requiredString("membershipId") == scope.membershipId)
            val commands = root["commands"]?.jsonArray?.map { decodeCommand(it.jsonObject) }
                ?: error("commands missing")
            require(commands.size <= MAX_COMMANDS)
            require(commands.map { it.deliveryId to it.attemptId }.distinct().size == commands.size)
            commands
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeCommand(command: DriverHandoffIssueCommand) = JsonObject(
        linkedMapOf(
            "deliveryId" to JsonPrimitive(command.deliveryId),
            "attemptId" to JsonPrimitive(command.attemptId),
            "expectedVersion" to JsonPrimitive(command.expectedVersion),
            "idempotencyKey" to JsonPrimitive(command.idempotencyKey),
            "frozenBody" to JsonPrimitive(command.frozenBody)
        )
    )

    private fun decodeCommand(value: JsonObject) = DriverHandoffIssueCommand(
        deliveryId = value.requiredString("deliveryId"),
        attemptId = value.requiredString("attemptId"),
        expectedVersion = value.requiredLong("expectedVersion"),
        idempotencyKey = value.requiredString("idempotencyKey"),
        frozenBody = value.requiredString("frozenBody")
    )

    private fun JsonObject.requiredString(key: String): String = this[key]?.jsonPrimitive?.takeIf(
        JsonPrimitive::isString
    )?.content?.takeIf(String::isNotBlank)
        ?: error("handoff metadata field invalid")

    private fun JsonObject.requiredLong(key: String): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: error("handoff metadata field invalid")

    private fun DriverAttemptScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun mutex(scope: DriverAttemptScopeIdentity): Mutex = locks.getOrPut(scope) { Mutex() }

    private companion object {
        const val SCHEMA = 1L
        const val MAX_COMMANDS = 256
        val locks = ConcurrentHashMap<DriverAttemptScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DriverHandoffTokenMetadataModule {
    @Provides
    @Singleton
    fun provideDriverHandoffTokenMetadataStore(
        @ApplicationContext context: Context
    ): HandoffTokenMetadataStore = AppDriverHandoffTokenMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DriverHandoffToken)
    )
}
