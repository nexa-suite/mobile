package com.nexa.mobile.operations.data

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.feature.delivery.application.DriverOutcomeMetadataStore
import com.nexa.mobile.operations.feature.delivery.model.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.model.DriverOutcomeCommand
import com.nexa.mobile.operations.feature.delivery.model.DriverOutcomeIntentMetadata as OutcomeIntentMetadata
import com.nexa.mobile.operations.feature.delivery.model.DriverOutcomeIntentStatus
import com.nexa.mobile.operations.feature.delivery.model.DriverOutcomeKind
import com.nexa.mobile.operations.feature.delivery.model.DriverOutcomeLineDecision
import com.nexa.mobile.operations.feature.delivery.model.DriverOutcomeMetadataRead
import com.nexa.mobile.operations.feature.delivery.model.DriverOutcomeMetadataWrite
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Typed frozen outcome intent adapter; it never stores session authority or accepted truth. */
class AppDriverOutcomeMetadataStore(private val local: AndroidScopedMetadataStore) :
    DriverOutcomeMetadataStore {
    override suspend fun loadIntent(scope: DriverAttemptScopeIdentity): DriverOutcomeMetadataRead =
        mutex(scope).withLock {
            when (val stored = local.load(scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> DriverOutcomeMetadataRead.Unavailable

                is ScopedMetadataRead.Value -> {
                    val payload = stored.payload
                        ?: return@withLock DriverOutcomeMetadataRead.Available(null)
                    val intent = decode(payload)
                        ?: return@withLock DriverOutcomeMetadataRead.Unavailable
                    if (intent.scope != scope) return@withLock DriverOutcomeMetadataRead.Unavailable
                    if (intent.status == DriverOutcomeIntentStatus.Pending) {
                        val recovered = intent.copy(
                            status = DriverOutcomeIntentStatus.UnknownOutcome
                        )
                        if (local.save(scope.toLocal(), encode(recovered))) {
                            DriverOutcomeMetadataRead.Available(recovered)
                        } else {
                            DriverOutcomeMetadataRead.Unavailable
                        }
                    } else {
                        DriverOutcomeMetadataRead.Available(intent)
                    }
                }
            }
        }

    override suspend fun saveIntent(intent: OutcomeIntentMetadata): DriverOutcomeMetadataWrite =
        mutex(intent.scope).withLock {
            when (val stored = local.load(intent.scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> DriverOutcomeMetadataWrite.Unavailable

                is ScopedMetadataRead.Value -> {
                    val current = stored.payload?.let(::decode)
                    if (stored.payload != null && current == null) {
                        return@withLock DriverOutcomeMetadataWrite.Unavailable
                    }
                    when {
                        current == null -> save(intent)

                        current.scope != intent.scope -> DriverOutcomeMetadataWrite.Unavailable

                        current.command.idempotencyKey != intent.command.idempotencyKey ||
                            current.command.deliveryId != intent.command.deliveryId ||
                            current.command.attemptId != intent.command.attemptId ||
                            current.command.expectedVersion != intent.command.expectedVersion ||
                            current.command.frozenBody != intent.command.frozenBody ->
                            DriverOutcomeMetadataWrite.Conflict

                        current == intent -> DriverOutcomeMetadataWrite.Saved

                        current.status == DriverOutcomeIntentStatus.UnknownOutcome &&
                            intent.status == DriverOutcomeIntentStatus.Pending ->
                            DriverOutcomeMetadataWrite.Conflict

                        else -> save(intent)
                    }
                }
            }
        }

    override suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        idempotencyKey: String
    ): DriverOutcomeMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> DriverOutcomeMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload = stored.payload ?: return@withLock DriverOutcomeMetadataWrite.Saved
                val current =
                    decode(payload) ?: return@withLock DriverOutcomeMetadataWrite.Unavailable
                if (current.scope != scope || current.command.idempotencyKey != idempotencyKey) {
                    DriverOutcomeMetadataWrite.Stale
                } else if (local.clear(scope.toLocal())) {
                    DriverOutcomeMetadataWrite.Saved
                } else {
                    DriverOutcomeMetadataWrite.Unavailable
                }
            }
        }
    }

    private suspend fun save(intent: OutcomeIntentMetadata): DriverOutcomeMetadataWrite =
        if (local.save(intent.scope.toLocal(), encode(intent))) {
            DriverOutcomeMetadataWrite.Saved
        } else {
            DriverOutcomeMetadataWrite.Unavailable
        }

    private fun encode(intent: OutcomeIntentMetadata): String {
        val command = intent.command
        return JsonObject(
            mapOf(
                "schema" to JsonPrimitive(1),
                "userId" to JsonPrimitive(intent.scope.userId),
                "tenantId" to JsonPrimitive(intent.scope.tenantId),
                "workspaceId" to JsonPrimitive(intent.scope.workspaceId),
                "membershipId" to JsonPrimitive(intent.scope.membershipId),
                "deliveryId" to JsonPrimitive(command.deliveryId),
                "attemptId" to JsonPrimitive(command.attemptId),
                "expectedVersion" to JsonPrimitive(command.expectedVersion),
                "idempotencyKey" to JsonPrimitive(command.idempotencyKey),
                "outcome" to JsonPrimitive(command.outcome.name),
                "failureReason" to
                    (
                        command.failureReason?.let(::JsonPrimitive)
                            ?: JsonNull
                        ),
                "notes" to
                    (command.notes?.let(::JsonPrimitive) ?: JsonNull),
                "attemptedAt" to JsonPrimitive(command.attemptedAt),
                "frozenBody" to JsonPrimitive(command.frozenBody),
                "lines" to JsonArray(
                    command.lines.map { line ->
                        JsonObject(
                            mapOf(
                                "fulfillmentLineId" to JsonPrimitive(line.fulfillmentLineId),
                                "skuId" to JsonPrimitive(line.skuId),
                                "attemptedQuantity" to
                                    JsonPrimitive(line.attemptedQuantity.toPlainString()),
                                "deliveredQuantity" to
                                    JsonPrimitive(line.deliveredQuantity.toPlainString()),
                                "rejectedQuantity" to
                                    JsonPrimitive(line.rejectedQuantity.toPlainString()),
                                "cancelledQuantity" to
                                    JsonPrimitive(line.cancelledQuantity.toPlainString()),
                                "unit" to JsonPrimitive(line.unit)
                            )
                        )
                    }
                ),
                "status" to JsonPrimitive(intent.status.name)
            )
        ).toString()
    }

    private fun decode(payload: String): OutcomeIntentMetadata? = try {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.requiredLong("schema") == 1L)
        val scope = DriverAttemptScopeIdentity(
            root.requiredString("userId"),
            root.requiredString("tenantId"),
            root.requiredString("workspaceId"),
            root.requiredString("membershipId")
        )
        val lines = root["lines"]?.jsonArray?.map { element ->
            val line = element.jsonObject
            DriverOutcomeLineDecision(
                line.requiredString("fulfillmentLineId"),
                line.requiredString("skuId"),
                line.requiredDecimal("attemptedQuantity"),
                line.requiredDecimal("deliveredQuantity"),
                line.requiredDecimal("rejectedQuantity"),
                line.requiredDecimal("cancelledQuantity"),
                line.requiredString("unit")
            )
        } ?: error("Outcome metadata lines are invalid")
        val command = DriverOutcomeCommand(
            deliveryId = root.requiredString("deliveryId"),
            attemptId = root.requiredString("attemptId"),
            expectedVersion = root.requiredLong("expectedVersion"),
            idempotencyKey = root.requiredString("idempotencyKey"),
            outcome = DriverOutcomeKind.valueOf(root.requiredString("outcome")),
            failureReason = root.optionalString("failureReason"),
            notes = root.optionalString("notes"),
            attemptedAt = root.requiredString("attemptedAt"),
            lines = lines,
            frozenBody = root.requiredString("frozenBody")
        )
        OutcomeIntentMetadata(
            scope,
            command,
            DriverOutcomeIntentStatus.valueOf(root.requiredString("status"))
        )
    } catch (_: Exception) {
        null
    }

    private fun mutex(scope: DriverAttemptScopeIdentity): Mutex = locks.computeIfAbsent(scope) {
        Mutex()
    }

    private fun DriverAttemptScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf(String::isNotBlank) ?: error("Outcome metadata field is invalid")

    private fun JsonObject.optionalString(key: String): String? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.takeIf(JsonPrimitive::isString)?.content
        else -> error("Outcome metadata optional string is invalid")
    }

    private fun JsonObject.requiredLong(key: String): Long =
        this[key]?.jsonPrimitive?.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?: error("Outcome metadata number is invalid")

    private fun JsonObject.requiredDecimal(key: String): BigDecimal =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?.let(::BigDecimal) ?: error("Outcome metadata decimal is invalid")

    private companion object {
        val locks = ConcurrentHashMap<DriverAttemptScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppDriverOutcomeMetadataBindings {
    @Provides
    @Singleton
    fun driverOutcomeMetadataStore(
        @ApplicationContext context: Context
    ): DriverOutcomeMetadataStore = AppDriverOutcomeMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DriverDeliveryOutcome)
    )
}
