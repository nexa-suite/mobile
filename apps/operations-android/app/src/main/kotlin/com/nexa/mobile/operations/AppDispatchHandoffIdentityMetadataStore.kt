package com.nexa.mobile.operations

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoffIdentityCommand
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoffIdentityMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoffMetadataRead
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoffMetadataWrite
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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

/** Encrypted durable issue intent only. Handoff tokens are never persisted. */
internal class AppDispatchHandoffIdentityMetadataStore(
    private val local: AndroidScopedMetadataStore
) : DispatchHandoffIdentityMetadataStore {
    override suspend fun load(
        scope: DispatchAuthorityIdentity,
        deliveryId: String,
        assignmentId: String
    ): DispatchHandoffMetadataRead = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchHandoffMetadataRead.Unavailable
            is ScopedMetadataRead.Value -> {
                val commands = decode(stored.payload, scope)
                    ?: return@withLock DispatchHandoffMetadataRead.Unavailable
                DispatchHandoffMetadataRead.Available(
                    commands.firstOrNull { it.deliveryId == deliveryId && it.assignmentId == assignmentId }
                )
            }
        }
    }

    override suspend fun persistIntent(
        scope: DispatchAuthorityIdentity,
        command: DispatchHandoffIdentityCommand,
        replacingIdempotencyKey: String?
    ): DispatchHandoffMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchHandoffMetadataWrite.Unavailable
            is ScopedMetadataRead.Value -> {
                val commands = decode(stored.payload, scope)
                    ?: return@withLock DispatchHandoffMetadataWrite.Unavailable
                val existing = commands.firstOrNull {
                    it.deliveryId == command.deliveryId && it.assignmentId == command.assignmentId
                }
                when {
                    existing == command -> DispatchHandoffMetadataWrite.Saved
                    existing != null && replacingIdempotencyKey != null &&
                        existing.idempotencyKey == replacingIdempotencyKey -> {
                        val replacement = commands.map { if (it == existing) command else it }
                        if (local.save(scope.toLocal(), encode(scope, replacement))) {
                            DispatchHandoffMetadataWrite.Saved
                        } else DispatchHandoffMetadataWrite.Unavailable
                    }
                    existing != null -> DispatchHandoffMetadataWrite.Conflict
                    commands.size >= MAX_COMMANDS -> DispatchHandoffMetadataWrite.Unavailable
                    local.save(scope.toLocal(), encode(scope, commands + command)) ->
                        DispatchHandoffMetadataWrite.Saved
                    else -> DispatchHandoffMetadataWrite.Unavailable
                }
            }
        }
    }

    override suspend fun clearCommand(
        scope: DispatchAuthorityIdentity,
        deliveryId: String,
        assignmentId: String,
        idempotencyKey: String
    ): DispatchHandoffMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchHandoffMetadataWrite.Unavailable
            is ScopedMetadataRead.Value -> {
                val commands = decode(stored.payload, scope)
                    ?: return@withLock DispatchHandoffMetadataWrite.Unavailable
                val existing = commands.firstOrNull {
                    it.deliveryId == deliveryId && it.assignmentId == assignmentId
                } ?: return@withLock DispatchHandoffMetadataWrite.Saved
                if (existing.idempotencyKey != idempotencyKey) {
                    DispatchHandoffMetadataWrite.Stale
                } else if (local.save(
                        scope.toLocal(),
                        encode(scope, commands.filterNot { it == existing })
                    )
                ) {
                    DispatchHandoffMetadataWrite.Saved
                } else {
                    DispatchHandoffMetadataWrite.Unavailable
                }
            }
        }
    }

    private fun encode(scope: DispatchAuthorityIdentity, commands: List<DispatchHandoffIdentityCommand>) =
        JsonObject(
            linkedMapOf(
                "schema" to JsonPrimitive(SCHEMA),
                "userId" to JsonPrimitive(scope.userId),
                "tenantId" to JsonPrimitive(scope.tenantId),
                "workspaceId" to JsonPrimitive(scope.workspaceId),
                "membershipId" to JsonPrimitive(scope.membershipId),
                "commands" to JsonArray(commands.map(::encodeCommand))
            )
        ).toString()

    private fun decode(payload: String?, scope: DispatchAuthorityIdentity): List<DispatchHandoffIdentityCommand>? {
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
            require(commands.map { it.deliveryId to it.assignmentId }.distinct().size == commands.size)
            commands
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeCommand(command: DispatchHandoffIdentityCommand) = JsonObject(
        linkedMapOf(
            "deliveryId" to JsonPrimitive(command.deliveryId),
            "assignmentId" to JsonPrimitive(command.assignmentId),
            "idempotencyKey" to JsonPrimitive(command.idempotencyKey),
            "frozenBody" to JsonPrimitive(command.frozenBody)
        )
    )

    private fun decodeCommand(value: JsonObject) = DispatchHandoffIdentityCommand(
        deliveryId = value.requiredString("deliveryId"),
        assignmentId = value.requiredString("assignmentId"),
        idempotencyKey = value.requiredString("idempotencyKey"),
        frozenBody = value.requiredString("frozenBody")
    )

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf(String::isNotBlank) ?: error("handoff metadata field invalid")

    private fun JsonObject.requiredLong(key: String): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: error("handoff metadata field invalid")

    private fun DispatchAuthorityIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun mutex(scope: DispatchAuthorityIdentity): Mutex = locks.getOrPut(scope) { Mutex() }

    private companion object {
        const val SCHEMA = 1L
        const val MAX_COMMANDS = 256
        val locks = java.util.concurrent.ConcurrentHashMap<DispatchAuthorityIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DispatchHandoffIdentityMetadataModule {
    @Provides
    @Singleton
    fun provideDispatchHandoffIdentityMetadataStore(
        @ApplicationContext context: Context
    ): DispatchHandoffIdentityMetadataStore = AppDispatchHandoffIdentityMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DispatchHandoffIdentity)
    )
}
