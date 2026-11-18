package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchTransitStartScopeIdentity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Purpose-bound durable retry identity for the explicit Dispatch transit command. */
class AppDispatchTransitStartMetadataStore(private val local: ScopedMetadataStore) :
    DispatchTransitStartMetadataStore {
    override suspend fun loadIntent(
        scope: DispatchTransitStartScopeIdentity,
        deliveryId: String
    ): DispatchTransitStartMetadataRead = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchTransitStartMetadataRead.Unavailable

            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) {
                        emptyList()
                    } else {
                        return@withLock DispatchTransitStartMetadataRead.Unavailable
                    }
                val intent = intents.firstOrNull {
                    it.command.deliveryId == deliveryId
                }
                    ?: return@withLock DispatchTransitStartMetadataRead.Available(null)
                if (intent.scope != scope || !intent.command.isValid()) {
                    return@withLock DispatchTransitStartMetadataRead.Unavailable
                }
                if (intent.status == DispatchTransitStartIntentStatus.Pending) {
                    val recovered = intent.copy(
                        status = DispatchTransitStartIntentStatus.UnknownOutcome
                    )
                    val updated = intents.map {
                        if (it.command.deliveryId == deliveryId) recovered else it
                    }
                    if (local.save(scope.toLocal(), encode(updated))) {
                        DispatchTransitStartMetadataRead.Available(recovered)
                    } else {
                        DispatchTransitStartMetadataRead.Unavailable
                    }
                } else {
                    DispatchTransitStartMetadataRead.Available(intent)
                }
            }
        }
    }

    override suspend fun saveIntent(
        intent: DispatchTransitStartIntent
    ): DispatchTransitStartMetadataWrite = mutex(intent.scope).withLock {
        if (!intent.scope.isValid() || !intent.command.isValid()) {
            return@withLock DispatchTransitStartMetadataWrite.Conflict
        }
        when (val stored = local.load(intent.scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchTransitStartMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) {
                        emptyList()
                    } else {
                        return@withLock DispatchTransitStartMetadataWrite.Unavailable
                    }
                val current = intents.firstOrNull {
                    it.command.deliveryId == intent.command.deliveryId
                }
                when {
                    current == null && intents.size >= MAX_INTENTS ->
                        DispatchTransitStartMetadataWrite.Unavailable

                    current == null -> write(intent.scope, intents + intent)

                    current.scope != intent.scope || current.command != intent.command ->
                        DispatchTransitStartMetadataWrite.Conflict

                    current.status == DispatchTransitStartIntentStatus.UnknownOutcome &&
                        intent.status == DispatchTransitStartIntentStatus.Pending ->
                        DispatchTransitStartMetadataWrite.Conflict

                    current == intent -> DispatchTransitStartMetadataWrite.Saved

                    else -> write(
                        intent.scope,
                        intents.map {
                            if (it.command.deliveryId == intent.command.deliveryId) intent else it
                        }
                    )
                }
            }
        }
    }

    override suspend fun clearIntent(
        scope: DispatchTransitStartScopeIdentity,
        deliveryId: String,
        idempotencyKey: String
    ): DispatchTransitStartMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchTransitStartMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) {
                        emptyList()
                    } else {
                        return@withLock DispatchTransitStartMetadataWrite.Unavailable
                    }
                val current = intents.firstOrNull { it.command.deliveryId == deliveryId }
                    ?: return@withLock DispatchTransitStartMetadataWrite.Saved
                if (current.scope != scope || current.command.idempotencyKey != idempotencyKey) {
                    return@withLock DispatchTransitStartMetadataWrite.Stale
                }
                val remaining = intents.filterNot { it.command.deliveryId == deliveryId }
                val cleared = if (remaining.isEmpty()) {
                    local.clear(scope.toLocal())
                } else {
                    local.save(scope.toLocal(), encode(remaining))
                }
                if (cleared) {
                    DispatchTransitStartMetadataWrite.Saved
                } else {
                    DispatchTransitStartMetadataWrite.Unavailable
                }
            }
        }
    }

    private suspend fun write(
        scope: DispatchTransitStartScopeIdentity,
        intents: List<DispatchTransitStartIntent>
    ): DispatchTransitStartMetadataWrite = if (local.save(scope.toLocal(), encode(intents))) {
        DispatchTransitStartMetadataWrite.Saved
    } else {
        DispatchTransitStartMetadataWrite.Unavailable
    }

    private fun encode(intents: List<DispatchTransitStartIntent>): String = buildJsonObject {
        put("schema", JsonPrimitive(SCHEMA))
        put(
            "commands",
            JsonArray(
                intents.map { intent ->
                    buildJsonObject {
                        put("userId", JsonPrimitive(intent.scope.userId))
                        put("tenantId", JsonPrimitive(intent.scope.tenantId))
                        put("workspaceId", JsonPrimitive(intent.scope.workspaceId))
                        put("membershipId", JsonPrimitive(intent.scope.membershipId))
                        put("deliveryId", JsonPrimitive(intent.command.deliveryId))
                        put("fulfillmentId", JsonPrimitive(intent.command.fulfillmentId))
                        put("deliveryEtag", JsonPrimitive(intent.command.deliveryEtag))
                        put("idempotencyKey", JsonPrimitive(intent.command.idempotencyKey))
                        put("status", JsonPrimitive(intent.status.name))
                    }
                }
            )
        )
    }.toString()

    private fun decode(payload: String): List<DispatchTransitStartIntent>? = try {
        val root = json.parseToJsonElement(payload).jsonObject
        if (root.requiredLong("schema") != SCHEMA) return null
        val intents = root["commands"]?.jsonArray?.map { element ->
            val value = element.jsonObject
            DispatchTransitStartIntent(
                scope = DispatchTransitStartScopeIdentity(
                    userId = value.requiredString("userId"),
                    tenantId = value.requiredString("tenantId"),
                    workspaceId = value.requiredString("workspaceId"),
                    membershipId = value.requiredString("membershipId")
                ),
                command = DispatchTransitStartCommand(
                    deliveryId = value.requiredString("deliveryId"),
                    fulfillmentId = value.requiredString("fulfillmentId"),
                    deliveryEtag = value.requiredString("deliveryEtag"),
                    idempotencyKey = value.requiredString("idempotencyKey")
                ),
                status = DispatchTransitStartIntentStatus.valueOf(value.requiredString("status"))
            )
        } ?: return null
        if (intents.map { it.command.deliveryId }.distinct().size != intents.size ||
            intents.any { !it.scope.isValid() || !it.command.isValid() }
        ) {
            null
        } else {
            intents
        }
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf(String::isNotBlank) ?: error("Transit intent field is invalid")

    private fun JsonObject.requiredLong(key: String): Long =
        this[key]?.jsonPrimitive?.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?: error("Transit intent version is invalid")

    private fun DispatchTransitStartScopeIdentity.isValid(): Boolean =
        listOf(userId, tenantId, workspaceId, membershipId).all {
            it.isNotBlank() && it.length <= 160
        }

    private fun DispatchTransitStartScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun mutex(scope: DispatchTransitStartScopeIdentity): Mutex =
        locks.computeIfAbsent(scope) { Mutex() }

    private companion object {
        const val SCHEMA = 1L
        const val MAX_INTENTS = 32
        val json = Json { ignoreUnknownKeys = false }
        val locks = ConcurrentHashMap<DispatchTransitStartScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppDispatchTransitStartMetadataBindings {
    @Provides
    @Singleton
    fun dispatchTransitStartMetadataStore(
        @ApplicationContext context: Context
    ): DispatchTransitStartMetadataStore = AppDispatchTransitStartMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DispatchTransitStart)
    )
}
