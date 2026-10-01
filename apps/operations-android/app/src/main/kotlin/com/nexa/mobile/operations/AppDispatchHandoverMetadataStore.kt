package com.nexa.mobile.operations

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoverCommand
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoverIntent
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoverMetadataRead
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoverMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoverMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsIntentStatus
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsScopeIdentity
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

/** Durable exact dispatch transition intent, isolated from assignment and outgoing-check facts. */
internal class AppDispatchHandoverMetadataStore(
    private val local: ScopedMetadataStore
) : DispatchHandoverMetadataStore {
    override suspend fun loadIntent(
        scope: DispatchOutgoingGoodsScopeIdentity,
        fulfillmentId: String
    ): DispatchHandoverMetadataRead = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchHandoverMetadataRead.Unavailable
            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) emptyList() else {
                        return@withLock DispatchHandoverMetadataRead.Unavailable
                    }
                val intent = intents.firstOrNull { it.command.fulfillmentId == fulfillmentId }
                    ?: return@withLock DispatchHandoverMetadataRead.Available(null)
                if (intent.scope != scope || !intent.command.isValid()) {
                    return@withLock DispatchHandoverMetadataRead.Unavailable
                }
                if (intent.status == DispatchOutgoingGoodsIntentStatus.Pending) {
                    val recovered = intent.copy(status = DispatchOutgoingGoodsIntentStatus.UnknownOutcome)
                    val updated = intents.map { if (it.command.fulfillmentId == fulfillmentId) recovered else it }
                    if (local.save(scope.toLocal(), encode(updated))) {
                        DispatchHandoverMetadataRead.Available(recovered)
                    } else {
                        DispatchHandoverMetadataRead.Unavailable
                    }
                } else {
                    DispatchHandoverMetadataRead.Available(intent)
                }
            }
        }
    }

    override suspend fun saveIntent(intent: DispatchHandoverIntent): DispatchHandoverMetadataWrite =
        mutex(intent.scope).withLock {
            if (!intent.command.isValid()) return@withLock DispatchHandoverMetadataWrite.Conflict
            when (val stored = local.load(intent.scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> DispatchHandoverMetadataWrite.Unavailable
                is ScopedMetadataRead.Value -> {
                    val intents = stored.payload?.let(::decode)
                        ?: if (stored.payload == null) emptyList() else {
                            return@withLock DispatchHandoverMetadataWrite.Unavailable
                        }
                    val current = intents.firstOrNull { it.command.fulfillmentId == intent.command.fulfillmentId }
                    if (current == null) {
                        if (intents.size >= MAX_INTENTS) return@withLock DispatchHandoverMetadataWrite.Unavailable
                        write(intent.scope, intents + intent)
                    } else if (current.scope != intent.scope || current.command != intent.command) {
                        DispatchHandoverMetadataWrite.Conflict
                    } else if (current.status == DispatchOutgoingGoodsIntentStatus.UnknownOutcome &&
                        intent.status == DispatchOutgoingGoodsIntentStatus.Pending
                    ) {
                        DispatchHandoverMetadataWrite.Conflict
                    } else if (current == intent) {
                        DispatchHandoverMetadataWrite.Saved
                    } else {
                        write(intent.scope, intents.map {
                            if (it.command.fulfillmentId == intent.command.fulfillmentId) intent else it
                        })
                    }
                }
            }
        }

    override suspend fun clearIntent(
        scope: DispatchOutgoingGoodsScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): DispatchHandoverMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DispatchHandoverMetadataWrite.Unavailable
            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) emptyList() else {
                        return@withLock DispatchHandoverMetadataWrite.Unavailable
                    }
                val current = intents.firstOrNull { it.command.fulfillmentId == fulfillmentId }
                    ?: return@withLock DispatchHandoverMetadataWrite.Saved
                if (current.scope != scope || current.command.idempotencyKey != idempotencyKey) {
                    DispatchHandoverMetadataWrite.Stale
                } else {
                    val remaining = intents.filterNot { it.command.fulfillmentId == fulfillmentId }
                    val cleared = if (remaining.isEmpty()) local.clear(scope.toLocal()) else
                        local.save(scope.toLocal(), encode(remaining))
                    if (cleared) DispatchHandoverMetadataWrite.Saved else
                        DispatchHandoverMetadataWrite.Unavailable
                }
            }
        }
    }

    private suspend fun write(
        scope: DispatchOutgoingGoodsScopeIdentity,
        intents: List<DispatchHandoverIntent>
    ): DispatchHandoverMetadataWrite = if (local.save(scope.toLocal(), encode(intents))) {
        DispatchHandoverMetadataWrite.Saved
    } else {
        DispatchHandoverMetadataWrite.Unavailable
    }

    private fun encode(intents: List<DispatchHandoverIntent>): String = buildJsonObject {
        put("schema", JsonPrimitive(SCHEMA))
        put("commands", JsonArray(intents.map { intent ->
            buildJsonObject {
                put("userId", JsonPrimitive(intent.scope.userId))
                put("tenantId", JsonPrimitive(intent.scope.tenantId))
                put("workspaceId", JsonPrimitive(intent.scope.workspaceId))
                put("membershipId", JsonPrimitive(intent.scope.membershipId))
                put("fulfillmentId", JsonPrimitive(intent.command.fulfillmentId))
                put("expectedFulfillmentVersion", JsonPrimitive(intent.command.expectedFulfillmentVersion))
                put("physicalAllocationId", JsonPrimitive(intent.command.physicalAllocationId))
                put("physicalAllocationVersion", JsonPrimitive(intent.command.physicalAllocationVersion))
                put("driverAssignmentId", JsonPrimitive(intent.command.driverAssignmentId))
                put("driverAssignmentVersion", JsonPrimitive(intent.command.driverAssignmentVersion))
                put("outgoingGoodsCheckId", JsonPrimitive(intent.command.outgoingGoodsCheckId))
                put("idempotencyKey", JsonPrimitive(intent.command.idempotencyKey))
                put("exactRequestBody", JsonPrimitive(intent.command.exactRequestBody))
                put("status", JsonPrimitive(intent.status.name))
            }
        }))
    }.toString()

    private fun decode(payload: String): List<DispatchHandoverIntent>? = try {
        val envelope = Json.parseToJsonElement(payload).jsonObject
        if (envelope.requiredLong("schema") != SCHEMA) return null
        val intents = envelope["commands"]?.jsonArray?.map { element ->
            val value = element.jsonObject
            DispatchHandoverIntent(
                scope = DispatchOutgoingGoodsScopeIdentity(
                    value.requiredString("userId"),
                    value.requiredString("tenantId"),
                    value.requiredString("workspaceId"),
                    value.requiredString("membershipId")
                ),
                command = DispatchHandoverCommand(
                    fulfillmentId = value.requiredString("fulfillmentId"),
                    expectedFulfillmentVersion = value.requiredLong("expectedFulfillmentVersion"),
                    physicalAllocationId = value.requiredString("physicalAllocationId"),
                    physicalAllocationVersion = value.requiredLong("physicalAllocationVersion"),
                    driverAssignmentId = value.requiredString("driverAssignmentId"),
                    driverAssignmentVersion = value.requiredLong("driverAssignmentVersion"),
                    outgoingGoodsCheckId = value.requiredString("outgoingGoodsCheckId"),
                    idempotencyKey = value.requiredString("idempotencyKey"),
                    exactRequestBody = value.requiredStringAllowEmpty("exactRequestBody")
                ),
                status = DispatchOutgoingGoodsIntentStatus.valueOf(value.requiredString("status"))
            )
        } ?: error("Dispatch intents are missing")
        if (intents.map { it.command.fulfillmentId }.distinct().size != intents.size ||
            intents.any { !it.command.isValid() }
        ) null else intents
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf(String::isNotBlank) ?: error("Dispatch intent field is invalid")

    private fun JsonObject.requiredLong(key: String): Long =
        this[key]?.jsonPrimitive?.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?: error("Dispatch intent version is invalid")

    private fun JsonObject.requiredStringAllowEmpty(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?: error("Dispatch intent body is invalid")

    private fun DispatchOutgoingGoodsScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun mutex(scope: DispatchOutgoingGoodsScopeIdentity): Mutex =
        locks.computeIfAbsent(scope) { Mutex() }

    private companion object {
        const val SCHEMA = 2L
        const val MAX_INTENTS = 32
        val locks = ConcurrentHashMap<DispatchOutgoingGoodsScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object AppDispatchHandoverMetadataBindings {
    @Provides
    @Singleton
    fun dispatchHandoverMetadataStore(
        @ApplicationContext context: Context
    ): DispatchHandoverMetadataStore = AppDispatchHandoverMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.FulfillmentDispatch)
    )
}
