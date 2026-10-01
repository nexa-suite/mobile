package com.nexa.mobile.operations

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsCommand
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsIntent
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsIntentStatus
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsMetadataRead
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsObservation
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsScopeIdentity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
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

/** Typed frozen-request adapter over separately encrypted OutgoingGoodsCheck storage. */
internal class AppDispatchOutgoingGoodsMetadataStore(
    private val local: ScopedMetadataStore
) : DispatchOutgoingGoodsMetadataStore {
    override suspend fun loadIntent(
        scope: DispatchOutgoingGoodsScopeIdentity,
        fulfillmentId: String
    ): DispatchOutgoingGoodsMetadataRead = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchOutgoingGoodsMetadataRead.Unavailable
            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) emptyList() else {
                        return@withLock DispatchOutgoingGoodsMetadataRead.Unavailable
                    }
                val intent = intents.firstOrNull { it.command.fulfillmentId == fulfillmentId }
                    ?: return@withLock DispatchOutgoingGoodsMetadataRead.Available(null)
                if (intent.scope != scope || !intent.command.hasConsistentBody()) {
                    return@withLock DispatchOutgoingGoodsMetadataRead.Unavailable
                }
                if (intent.status == DispatchOutgoingGoodsIntentStatus.Pending) {
                    val recovered = intent.copy(status = DispatchOutgoingGoodsIntentStatus.UnknownOutcome)
                    val updated = intents.map {
                        if (it.command.fulfillmentId == fulfillmentId) recovered else it
                    }
                    if (local.save(scope.toLocal(), encode(updated))) {
                        DispatchOutgoingGoodsMetadataRead.Available(recovered)
                    } else {
                        DispatchOutgoingGoodsMetadataRead.Unavailable
                    }
                } else {
                    DispatchOutgoingGoodsMetadataRead.Available(intent)
                }
            }
        }
    }

    override suspend fun saveIntent(
        intent: DispatchOutgoingGoodsIntent
    ): DispatchOutgoingGoodsMetadataWrite = mutex(intent.scope).withLock {
        if (!intent.command.hasConsistentBody()) return@withLock DispatchOutgoingGoodsMetadataWrite.Conflict
        when (val stored = local.load(intent.scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchOutgoingGoodsMetadataWrite.Unavailable
            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) emptyList() else {
                        return@withLock DispatchOutgoingGoodsMetadataWrite.Unavailable
                    }
                val current = intents.firstOrNull { it.command.fulfillmentId == intent.command.fulfillmentId }
                if (current == null) {
                    if (intents.size >= MAX_PENDING_INTENTS) {
                        return@withLock DispatchOutgoingGoodsMetadataWrite.Unavailable
                    }
                    write(intent.scope, intents + intent)
                } else if (current.scope != intent.scope || !current.sameCommand(intent)) {
                    DispatchOutgoingGoodsMetadataWrite.Conflict
                } else if (current.status == DispatchOutgoingGoodsIntentStatus.UnknownOutcome &&
                    intent.status == DispatchOutgoingGoodsIntentStatus.Pending
                ) {
                    DispatchOutgoingGoodsMetadataWrite.Conflict
                } else if (current == intent) {
                    DispatchOutgoingGoodsMetadataWrite.Saved
                } else {
                    write(
                        intent.scope,
                        intents.map { if (it.command.fulfillmentId == intent.command.fulfillmentId) intent else it }
                    )
                }
            }
        }
    }

    override suspend fun clearIntent(
        scope: DispatchOutgoingGoodsScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): DispatchOutgoingGoodsMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchOutgoingGoodsMetadataWrite.Unavailable
            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) emptyList() else {
                        return@withLock DispatchOutgoingGoodsMetadataWrite.Unavailable
                    }
                val current = intents.firstOrNull { it.command.fulfillmentId == fulfillmentId }
                    ?: return@withLock DispatchOutgoingGoodsMetadataWrite.Saved
                if (current.scope != scope || current.command.idempotencyKey != idempotencyKey) {
                    DispatchOutgoingGoodsMetadataWrite.Stale
                } else {
                    val remaining = intents.filterNot { it.command.fulfillmentId == fulfillmentId }
                    val cleared = if (remaining.isEmpty()) local.clear(scope.toLocal()) else
                        local.save(scope.toLocal(), encode(remaining))
                    if (cleared) DispatchOutgoingGoodsMetadataWrite.Saved else
                        DispatchOutgoingGoodsMetadataWrite.Unavailable
                }
            }
        }
    }

    private suspend fun write(
        scope: DispatchOutgoingGoodsScopeIdentity,
        intents: List<DispatchOutgoingGoodsIntent>
    ): DispatchOutgoingGoodsMetadataWrite = if (local.save(scope.toLocal(), encode(intents))) {
        DispatchOutgoingGoodsMetadataWrite.Saved
    } else {
        DispatchOutgoingGoodsMetadataWrite.Unavailable
    }

    private fun encode(intents: List<DispatchOutgoingGoodsIntent>): String = buildJsonObject {
        put("schema", JsonPrimitive(SCHEMA_VERSION))
        put("commands", JsonArray(intents.map(::encodeIntent)))
    }.toString()

    private fun encodeIntent(intent: DispatchOutgoingGoodsIntent): JsonObject = buildJsonObject {
        put("userId", JsonPrimitive(intent.scope.userId))
        put("tenantId", JsonPrimitive(intent.scope.tenantId))
        put("workspaceId", JsonPrimitive(intent.scope.workspaceId))
        put("membershipId", JsonPrimitive(intent.scope.membershipId))
        put("fulfillmentId", JsonPrimitive(intent.command.fulfillmentId))
        put("expectedFulfillmentVersion", JsonPrimitive(intent.command.expectedFulfillmentVersion))
        put("physicalAllocationId", JsonPrimitive(intent.command.physicalAllocationId))
        put("physicalAllocationVersion", JsonPrimitive(intent.command.physicalAllocationVersion))
        put("idempotencyKey", JsonPrimitive(intent.command.idempotencyKey))
        put("exactRequestBody", JsonPrimitive(intent.command.exactRequestBody))
        put("status", JsonPrimitive(intent.status.name))
        put("observations", JsonArray(intent.command.observations.map { observation ->
            buildJsonObject {
                put("physicalAllocationLineId", JsonPrimitive(observation.physicalAllocationLineId))
                put("observedLotId", observation.observedLotId?.let(::JsonPrimitive) ?: JsonNull)
                put("observedQuantity", JsonPrimitive(observation.observedQuantity.toPlainString()))
            }
        }))
    }

    private fun decode(payload: String): List<DispatchOutgoingGoodsIntent>? = try {
        val envelope = Json.parseToJsonElement(payload).jsonObject
        if (envelope.requiredLong("schema") != SCHEMA_VERSION) {
            null
        } else {
            val intents = envelope["commands"]?.jsonArray?.map { element ->
                val value = element.jsonObject
                val scope = DispatchOutgoingGoodsScopeIdentity(
                    value.requiredString("userId"),
                    value.requiredString("tenantId"),
                    value.requiredString("workspaceId"),
                    value.requiredString("membershipId")
                )
                val observations = value["observations"]?.jsonArray?.map { observationElement ->
                    val observation = observationElement.jsonObject
                    DispatchOutgoingGoodsObservation(
                        physicalAllocationLineId = observation.requiredString("physicalAllocationLineId"),
                        observedLotId = observation["observedLotId"]?.let { lot ->
                            if (lot == JsonNull) null else lot.jsonPrimitive.content
                        },
                        observedQuantity = BigDecimal(observation.requiredString("observedQuantity"))
                    )
                } ?: error("Outgoing goods observations are missing")
                DispatchOutgoingGoodsIntent(
                    scope = scope,
                    command = DispatchOutgoingGoodsCommand(
                        fulfillmentId = value.requiredString("fulfillmentId"),
                        expectedFulfillmentVersion = value.requiredLong("expectedFulfillmentVersion"),
                        physicalAllocationId = value.requiredString("physicalAllocationId"),
                        physicalAllocationVersion = value.requiredLong("physicalAllocationVersion"),
                        observations = observations,
                        idempotencyKey = value.requiredString("idempotencyKey"),
                        exactRequestBody = value.requiredString("exactRequestBody")
                    ),
                    status = DispatchOutgoingGoodsIntentStatus.valueOf(value.requiredString("status"))
                )
            } ?: error("Outgoing goods commands are missing")
            if (intents.map { it.command.fulfillmentId }.distinct().size != intents.size ||
                intents.any { !it.command.hasConsistentBody() }
            ) null else intents
        }
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf(String::isNotBlank)
            ?: error("Outgoing goods metadata field is invalid")

    private fun JsonObject.requiredLong(key: String): Long =
        this[key]?.jsonPrimitive?.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?: error("Outgoing goods metadata number is invalid")

    private fun DispatchOutgoingGoodsIntent.sameCommand(other: DispatchOutgoingGoodsIntent): Boolean =
        scope == other.scope && command == other.command

    private fun DispatchOutgoingGoodsCommand.hasConsistentBody(): Boolean =
        fulfillmentId.isNotBlank() && expectedFulfillmentVersion >= 0 &&
            physicalAllocationId.isNotBlank() && physicalAllocationVersion >= 0 &&
            idempotencyKey.isNotBlank() && exactRequestBody == requestBody()

    private fun DispatchOutgoingGoodsCommand.requestBody(): String = buildString {
        append("{\"physicalAllocationId\":\"").append(physicalAllocationId)
            .append("\",\"physicalAllocationVersion\":").append(physicalAllocationVersion)
            .append(",\"observations\":[")
        observations.forEachIndexed { index, observation ->
            if (index > 0) append(',')
            append("{\"physicalAllocationLineId\":\"").append(observation.physicalAllocationLineId)
                .append("\",\"observedLotId\":")
            if (observation.observedLotId == null) append("null")
            else append('"').append(observation.observedLotId).append('"')
            append(",\"observedQuantity\":").append(observation.observedQuantity.toPlainString()).append('}')
        }
        append("]}")
    }

    private fun DispatchOutgoingGoodsScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun mutex(scope: DispatchOutgoingGoodsScopeIdentity): Mutex =
        locks.computeIfAbsent(scope) { Mutex() }

    private companion object {
        const val SCHEMA_VERSION = 1L
        const val MAX_PENDING_INTENTS = 32
        val locks = ConcurrentHashMap<DispatchOutgoingGoodsScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object AppDispatchOutgoingGoodsMetadataBindings {
    @Provides
    @Singleton
    fun dispatchOutgoingGoodsMetadataStore(
        @ApplicationContext context: Context
    ): DispatchOutgoingGoodsMetadataStore = AppDispatchOutgoingGoodsMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.OutgoingGoodsCheck)
    )
}
