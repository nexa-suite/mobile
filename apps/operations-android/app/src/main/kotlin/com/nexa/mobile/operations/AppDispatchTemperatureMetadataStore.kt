package com.nexa.mobile.operations

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureCommand
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureIntent
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureIntentStatus
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureMetadataRead
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureScopeIdentity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.math.BigDecimal
import java.time.Instant
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

/** Typed Pending-to-Unknown adapter over purpose-isolated encrypted storage. */
internal class AppDispatchTemperatureMetadataStore(
    private val local: ScopedMetadataStore
) : DispatchTemperatureMetadataStore {
    override suspend fun loadIntent(
        scope: DispatchTemperatureScopeIdentity,
        fulfillmentId: String
    ): DispatchTemperatureMetadataRead = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchTemperatureMetadataRead.Unavailable
            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) emptyList() else {
                        return@withLock DispatchTemperatureMetadataRead.Unavailable
                    }
                val intent = intents.firstOrNull { it.command.fulfillmentId == fulfillmentId }
                    ?: return@withLock DispatchTemperatureMetadataRead.Available(null)
                if (intent.scope != scope || !intent.command.isValid()) {
                    return@withLock DispatchTemperatureMetadataRead.Unavailable
                }
                if (intent.status == DispatchTemperatureIntentStatus.Pending) {
                    val recovered = intent.copy(status = DispatchTemperatureIntentStatus.UnknownOutcome)
                    val updated = intents.map {
                        if (it.command.fulfillmentId == fulfillmentId) recovered else it
                    }
                    if (local.save(scope.toLocal(), encode(updated))) {
                        DispatchTemperatureMetadataRead.Available(recovered)
                    } else {
                        DispatchTemperatureMetadataRead.Unavailable
                    }
                } else {
                    DispatchTemperatureMetadataRead.Available(intent)
                }
            }
        }
    }

    override suspend fun saveIntent(
        intent: DispatchTemperatureIntent
    ): DispatchTemperatureMetadataWrite = mutex(intent.scope).withLock {
        if (!intent.command.isValid()) return@withLock DispatchTemperatureMetadataWrite.Conflict
        when (val stored = local.load(intent.scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchTemperatureMetadataWrite.Unavailable
            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) emptyList() else {
                        return@withLock DispatchTemperatureMetadataWrite.Unavailable
                    }
                val current = intents.firstOrNull { it.command.fulfillmentId == intent.command.fulfillmentId }
                when {
                    current == null -> if (intents.size >= MAX_INTENTS) {
                        DispatchTemperatureMetadataWrite.Unavailable
                    } else {
                        write(intent.scope, intents + intent)
                    }
                    current.scope != intent.scope || current.command != intent.command ->
                        DispatchTemperatureMetadataWrite.Conflict
                    current.status == DispatchTemperatureIntentStatus.UnknownOutcome &&
                        intent.status == DispatchTemperatureIntentStatus.Pending ->
                        DispatchTemperatureMetadataWrite.Conflict
                    current == intent -> DispatchTemperatureMetadataWrite.Saved
                    else -> write(
                        intent.scope,
                        intents.map { if (it.command.fulfillmentId == intent.command.fulfillmentId) intent else it }
                    )
                }
            }
        }
    }

    override suspend fun clearIntent(
        scope: DispatchTemperatureScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): DispatchTemperatureMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchTemperatureMetadataWrite.Unavailable
            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) emptyList() else {
                        return@withLock DispatchTemperatureMetadataWrite.Unavailable
                    }
                val current = intents.firstOrNull { it.command.fulfillmentId == fulfillmentId }
                    ?: return@withLock DispatchTemperatureMetadataWrite.Saved
                if (current.scope != scope || current.command.idempotencyKey != idempotencyKey) {
                    DispatchTemperatureMetadataWrite.Stale
                } else {
                    val remaining = intents.filterNot { it.command.fulfillmentId == fulfillmentId }
                    val cleared = if (remaining.isEmpty()) local.clear(scope.toLocal()) else
                        local.save(scope.toLocal(), encode(remaining))
                    if (cleared) DispatchTemperatureMetadataWrite.Saved
                    else DispatchTemperatureMetadataWrite.Unavailable
                }
            }
        }
    }

    private suspend fun write(
        scope: DispatchTemperatureScopeIdentity,
        intents: List<DispatchTemperatureIntent>
    ): DispatchTemperatureMetadataWrite = if (local.save(scope.toLocal(), encode(intents))) {
        DispatchTemperatureMetadataWrite.Saved
    } else {
        DispatchTemperatureMetadataWrite.Unavailable
    }

    private fun encode(intents: List<DispatchTemperatureIntent>): String = buildJsonObject {
        put("schema", JsonPrimitive(SCHEMA))
        put("commands", JsonArray(intents.map { intent ->
            buildJsonObject {
                put("userId", JsonPrimitive(intent.scope.userId))
                put("tenantId", JsonPrimitive(intent.scope.tenantId))
                put("workspaceId", JsonPrimitive(intent.scope.workspaceId))
                put("membershipId", JsonPrimitive(intent.scope.membershipId))
                put("fulfillmentId", JsonPrimitive(intent.command.fulfillmentId))
                put("expectedFulfillmentVersion", JsonPrimitive(intent.command.expectedFulfillmentVersion))
                put("lotId", JsonPrimitive(intent.command.lotId))
                put("valueCelsius", JsonPrimitive(intent.command.valueCelsius.toPlainString()))
                put("occurredAt", JsonPrimitive(intent.command.occurredAt.toString()))
                put("idempotencyKey", JsonPrimitive(intent.command.idempotencyKey))
                put("exactRequestBody", JsonPrimitive(intent.command.exactRequestBody))
                put("status", JsonPrimitive(intent.status.name))
            }
        }))
    }.toString()

    private fun decode(payload: String): List<DispatchTemperatureIntent>? = try {
        val envelope = Json.parseToJsonElement(payload).jsonObject
        if (envelope.requiredLong("schema") != SCHEMA) return null
        val intents = envelope["commands"]?.jsonArray?.map { element ->
            val value = element.jsonObject
            DispatchTemperatureIntent(
                scope = DispatchTemperatureScopeIdentity(
                    value.requiredString("userId"),
                    value.requiredString("tenantId"),
                    value.requiredString("workspaceId"),
                    value.requiredString("membershipId")
                ),
                command = DispatchTemperatureCommand(
                    fulfillmentId = value.requiredString("fulfillmentId"),
                    expectedFulfillmentVersion = value.requiredLong("expectedFulfillmentVersion"),
                    lotId = value.requiredString("lotId"),
                    valueCelsius = BigDecimal(value.requiredString("valueCelsius")),
                    occurredAt = Instant.parse(value.requiredString("occurredAt")),
                    idempotencyKey = value.requiredString("idempotencyKey"),
                    exactRequestBody = value.requiredStringAllowEmpty("exactRequestBody")
                ),
                status = DispatchTemperatureIntentStatus.valueOf(value.requiredString("status"))
            )
        } ?: error("Temperature commands are missing")
        if (intents.map { it.command.fulfillmentId }.distinct().size != intents.size ||
            intents.any { !it.command.isValid() }
        ) null else intents
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf(String::isNotBlank) ?: error("Temperature metadata field is invalid")

    private fun JsonObject.requiredLong(key: String): Long =
        this[key]?.jsonPrimitive?.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?: error("Temperature metadata version is invalid")

    private fun JsonObject.requiredStringAllowEmpty(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?: error("Temperature metadata body is invalid")

    private fun DispatchTemperatureScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun mutex(scope: DispatchTemperatureScopeIdentity): Mutex = locks.computeIfAbsent(scope) { Mutex() }

    private companion object {
        const val SCHEMA = 1L
        const val MAX_INTENTS = 32
        val locks = ConcurrentHashMap<DispatchTemperatureScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object AppDispatchTemperatureMetadataBindings {
    @Provides
    @Singleton
    fun dispatchTemperatureMetadataStore(
        @ApplicationContext context: Context
    ): DispatchTemperatureMetadataStore = AppDispatchTemperatureMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DispatchTemperatureEvidence)
    )
}
