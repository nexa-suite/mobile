package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsCommand as OutgoingGoodsCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsIntent as OutgoingGoodsIntent
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsIntentStatus as OutgoingGoodsIntentStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsMetadataRead as OutgoingGoodsMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsMetadataStore as OutgoingGoodsMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsMetadataWrite as OutgoingGoodsMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsObservation as OutgoingGoodsObservation
import com.nexa.mobile.operations.fulfillmentdelivery.application.dispatch.DispatchOutgoingGoodsScopeIdentity as OutgoingGoodsScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchOutgoingGoodsCommandType as OutgoingGoodsCommandType
import com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.dispatch.JsonDispatchRequestBodyCodec
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
class AppDispatchOutgoingGoodsMetadataStore(
    private val local: ScopedMetadataStore,
    private val requestBodyCodec: JsonDispatchRequestBodyCodec
) : OutgoingGoodsMetadataStore {
    override suspend fun loadIntent(
        scope: OutgoingGoodsScopeIdentity,
        fulfillmentId: String
    ): OutgoingGoodsMetadataRead = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> OutgoingGoodsMetadataRead.Unavailable

            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) {
                        emptyList()
                    } else {
                        return@withLock OutgoingGoodsMetadataRead.Unavailable
                    }
                val intent = intents.firstOrNull { it.command.fulfillmentId == fulfillmentId }
                    ?: return@withLock OutgoingGoodsMetadataRead.Available(null)
                if (intent.scope != scope || !intent.command.hasConsistentBody()) {
                    return@withLock OutgoingGoodsMetadataRead.Unavailable
                }
                if (intent.status == OutgoingGoodsIntentStatus.Pending) {
                    val recovered = intent.copy(
                        status = OutgoingGoodsIntentStatus.UnknownOutcome
                    )
                    val updated = intents.map {
                        if (it.command.fulfillmentId == fulfillmentId) recovered else it
                    }
                    if (local.save(scope.toLocal(), encode(updated))) {
                        OutgoingGoodsMetadataRead.Available(recovered)
                    } else {
                        OutgoingGoodsMetadataRead.Unavailable
                    }
                } else {
                    OutgoingGoodsMetadataRead.Available(intent)
                }
            }
        }
    }

    override suspend fun saveIntent(intent: OutgoingGoodsIntent): OutgoingGoodsMetadataWrite =
        mutex(intent.scope).withLock {
            if (!intent.command.hasConsistentBody()) {
                return@withLock OutgoingGoodsMetadataWrite.Conflict
            }
            when (val stored = local.load(intent.scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> OutgoingGoodsMetadataWrite.Unavailable

                is ScopedMetadataRead.Value -> {
                    val intents = stored.payload?.let(::decode)
                        ?: if (stored.payload == null) {
                            emptyList()
                        } else {
                            return@withLock OutgoingGoodsMetadataWrite.Unavailable
                        }
                    val current = intents.firstOrNull {
                        it.command.fulfillmentId ==
                            intent.command.fulfillmentId
                    }
                    if (current == null) {
                        if (intents.size >= MAX_PENDING_INTENTS) {
                            return@withLock OutgoingGoodsMetadataWrite.Unavailable
                        }
                        write(intent.scope, intents + intent)
                    } else if (current.scope != intent.scope || !current.sameCommand(intent)) {
                        OutgoingGoodsMetadataWrite.Conflict
                    } else if (current.status == OutgoingGoodsIntentStatus.UnknownOutcome &&
                        intent.status == OutgoingGoodsIntentStatus.Pending
                    ) {
                        OutgoingGoodsMetadataWrite.Conflict
                    } else if (current == intent) {
                        OutgoingGoodsMetadataWrite.Saved
                    } else {
                        write(
                            intent.scope,
                            intents.map {
                                if (it.command.fulfillmentId ==
                                    intent.command.fulfillmentId
                                ) {
                                    intent
                                } else {
                                    it
                                }
                            }
                        )
                    }
                }
            }
        }

    override suspend fun clearIntent(
        scope: OutgoingGoodsScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): OutgoingGoodsMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> OutgoingGoodsMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val intents = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) {
                        emptyList()
                    } else {
                        return@withLock OutgoingGoodsMetadataWrite.Unavailable
                    }
                val current = intents.firstOrNull { it.command.fulfillmentId == fulfillmentId }
                    ?: return@withLock OutgoingGoodsMetadataWrite.Saved
                if (current.scope != scope || current.command.idempotencyKey != idempotencyKey) {
                    OutgoingGoodsMetadataWrite.Stale
                } else {
                    val remaining = intents.filterNot { it.command.fulfillmentId == fulfillmentId }
                    val cleared = if (remaining.isEmpty()) {
                        local.clear(scope.toLocal())
                    } else {
                        local.save(scope.toLocal(), encode(remaining))
                    }
                    if (cleared) {
                        OutgoingGoodsMetadataWrite.Saved
                    } else {
                        OutgoingGoodsMetadataWrite.Unavailable
                    }
                }
            }
        }
    }

    private suspend fun write(
        scope: OutgoingGoodsScopeIdentity,
        intents: List<OutgoingGoodsIntent>
    ): OutgoingGoodsMetadataWrite = if (local.save(scope.toLocal(), encode(intents))) {
        OutgoingGoodsMetadataWrite.Saved
    } else {
        OutgoingGoodsMetadataWrite.Unavailable
    }

    private fun encode(intents: List<OutgoingGoodsIntent>): String = buildJsonObject {
        put("schema", JsonPrimitive(SCHEMA_VERSION))
        put("commands", JsonArray(intents.map(::encodeIntent)))
    }.toString()

    private fun encodeIntent(intent: OutgoingGoodsIntent): JsonObject = buildJsonObject {
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
        put("type", JsonPrimitive(intent.command.type.name))
        put(
            "discrepancyCheckId",
            intent.command.discrepancyCheckId?.let(::JsonPrimitive) ?: JsonNull
        )
        put("matchingCheckId", intent.command.matchingCheckId?.let(::JsonPrimitive) ?: JsonNull)
        put("reason", intent.command.reason?.let(::JsonPrimitive) ?: JsonNull)
        put("status", JsonPrimitive(intent.status.name))
        put(
            "observations",
            JsonArray(
                intent.command.observations.map { observation ->
                    buildJsonObject {
                        put(
                            "physicalAllocationLineId",
                            JsonPrimitive(observation.physicalAllocationLineId)
                        )
                        put(
                            "observedLotId",
                            observation.observedLotId?.let(::JsonPrimitive) ?: JsonNull
                        )
                        put(
                            "observedQuantity",
                            JsonPrimitive(observation.observedQuantity.toPlainString())
                        )
                    }
                }
            )
        )
    }

    private fun decode(payload: String): List<OutgoingGoodsIntent>? = try {
        val envelope = Json.parseToJsonElement(payload).jsonObject
        val schema = envelope.requiredLong("schema")
        if (schema !in 1L..SCHEMA_VERSION) {
            null
        } else {
            val intents = envelope["commands"]?.jsonArray?.map { element ->
                val value = element.jsonObject
                val scope = OutgoingGoodsScopeIdentity(
                    value.requiredString("userId"),
                    value.requiredString("tenantId"),
                    value.requiredString("workspaceId"),
                    value.requiredString("membershipId")
                )
                val observations = value["observations"]?.jsonArray?.map { observationElement ->
                    val observation = observationElement.jsonObject
                    OutgoingGoodsObservation(
                        physicalAllocationLineId = observation.requiredString(
                            "physicalAllocationLineId"
                        ),
                        observedLotId = observation["observedLotId"]?.let { lot ->
                            if (lot == JsonNull) null else lot.jsonPrimitive.content
                        },
                        observedQuantity = BigDecimal(
                            observation.requiredString("observedQuantity")
                        )
                    )
                } ?: error("Outgoing goods observations are missing")
                OutgoingGoodsIntent(
                    scope = scope,
                    command = OutgoingGoodsCommand(
                        fulfillmentId = value.requiredString("fulfillmentId"),
                        expectedFulfillmentVersion = value.requiredLong(
                            "expectedFulfillmentVersion"
                        ),
                        physicalAllocationId = value.requiredString("physicalAllocationId"),
                        physicalAllocationVersion = value.requiredLong("physicalAllocationVersion"),
                        observations = observations,
                        idempotencyKey = value.requiredString("idempotencyKey"),
                        exactRequestBody = value.requiredString("exactRequestBody"),
                        type =
                            value.optionalString(
                                "type"
                            )?.let(OutgoingGoodsCommandType::valueOf)
                                ?: OutgoingGoodsCommandType.RecordCheck,
                        discrepancyCheckId = value.optionalString("discrepancyCheckId"),
                        matchingCheckId = value.optionalString("matchingCheckId"),
                        reason = value.optionalString("reason")
                    ),
                    status = OutgoingGoodsIntentStatus.valueOf(
                        value.requiredString("status")
                    )
                )
            } ?: error("Outgoing goods commands are missing")
            if (intents.map { it.command.fulfillmentId }.distinct().size != intents.size ||
                intents.any { !it.command.hasConsistentBody() }
            ) {
                null
            } else {
                intents
            }
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

    private fun JsonObject.optionalString(key: String): String? = this[key]?.let {
        if (it == JsonNull) null else it.jsonPrimitive.content.takeIf(String::isNotBlank)
    }

    private fun OutgoingGoodsIntent.sameCommand(other: OutgoingGoodsIntent): Boolean =
        scope == other.scope && command == other.command

    private fun OutgoingGoodsCommand.hasConsistentBody(): Boolean =
        fulfillmentId.isNotBlank() && expectedFulfillmentVersion >= 0 &&
            physicalAllocationId.isNotBlank() && physicalAllocationVersion >= 0 &&
            idempotencyKey.isNotBlank() && requestBodyCodec.isValid(this) && when (type) {
                OutgoingGoodsCommandType.RecordCheck -> observations.isNotEmpty() &&
                    discrepancyCheckId == null && matchingCheckId == null && reason == null

                OutgoingGoodsCommandType.ResolveDiscrepancy -> observations.isEmpty() &&
                    !discrepancyCheckId.isNullOrBlank() && !matchingCheckId.isNullOrBlank() &&
                    discrepancyCheckId != matchingCheckId &&
                    reason?.let { it.isNotBlank() && it.length <= 1000 } == true
            }

    private fun OutgoingGoodsScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun mutex(scope: OutgoingGoodsScopeIdentity): Mutex =
        locks.computeIfAbsent(scope) { Mutex() }

    private companion object {
        const val SCHEMA_VERSION = 2L
        const val MAX_PENDING_INTENTS = 32
        val locks = ConcurrentHashMap<OutgoingGoodsScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppDispatchOutgoingGoodsMetadataBindings {
    @Provides
    @Singleton
    fun dispatchOutgoingGoodsMetadataStore(
        @ApplicationContext context: Context,
        requestBodyCodec: JsonDispatchRequestBodyCodec
    ): OutgoingGoodsMetadataStore = AppDispatchOutgoingGoodsMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.OutgoingGoodsCheck),
        requestBodyCodec
    )
}
