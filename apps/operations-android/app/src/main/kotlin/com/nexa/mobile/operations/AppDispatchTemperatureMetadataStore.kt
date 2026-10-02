package com.nexa.mobile.operations

import android.content.Context
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureCommand
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureIntent
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureIntentStatus as TemperatureIntentStatus
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureMetadataRead as TemperatureMetadataRead
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureMetadataStore as TemperatureMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureMetadataWrite as TemperatureMetadataWrite
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperaturePhotoUploadIntent as TemperaturePhotoUploadIntent
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperaturePhotoUploadMetadataRead as TemperaturePhotoUploadMetadataRead
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureScopeIdentity as TemperatureScopeIdentity
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
internal class AppDispatchTemperatureMetadataStore(private val local: ScopedMetadataStore) :
    TemperatureMetadataStore {
    override suspend fun loadIntent(
        scope: TemperatureScopeIdentity,
        fulfillmentId: String
    ): TemperatureMetadataRead = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> TemperatureMetadataRead.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) {
                        MetadataPayload()
                    } else {
                        return@withLock TemperatureMetadataRead.Unavailable
                    }
                val intents = payload.commands
                val intent = intents.firstOrNull { it.command.fulfillmentId == fulfillmentId }
                    ?: return@withLock TemperatureMetadataRead.Available(null)
                if (intent.scope != scope || !intent.command.isValid()) {
                    return@withLock TemperatureMetadataRead.Unavailable
                }
                if (intent.status == TemperatureIntentStatus.Pending) {
                    val recovered = intent.copy(
                        status = TemperatureIntentStatus.UnknownOutcome
                    )
                    val updated = intents.map {
                        if (it.command.fulfillmentId == fulfillmentId) recovered else it
                    }
                    if (local.save(scope.toLocal(), encode(payload.copy(commands = updated)))) {
                        TemperatureMetadataRead.Available(recovered)
                    } else {
                        TemperatureMetadataRead.Unavailable
                    }
                } else {
                    TemperatureMetadataRead.Available(intent)
                }
            }
        }
    }

    override suspend fun saveIntent(intent: DispatchTemperatureIntent): TemperatureMetadataWrite =
        mutex(intent.scope).withLock {
            if (!intent.command.isValid()) return@withLock TemperatureMetadataWrite.Conflict
            when (val stored = local.load(intent.scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> TemperatureMetadataWrite.Unavailable

                is ScopedMetadataRead.Value -> {
                    val payload = stored.payload?.let(::decode)
                        ?: if (stored.payload == null) {
                            MetadataPayload()
                        } else {
                            return@withLock TemperatureMetadataWrite.Unavailable
                        }
                    val intents = payload.commands
                    val current = intents.firstOrNull {
                        it.command.fulfillmentId ==
                            intent.command.fulfillmentId
                    }
                    when {
                        current == null -> if (intents.size >= MAX_INTENTS) {
                            TemperatureMetadataWrite.Unavailable
                        } else {
                            write(intent.scope, payload.copy(commands = intents + intent))
                        }

                        current.scope != intent.scope || current.command != intent.command ->
                            TemperatureMetadataWrite.Conflict

                        current.status == TemperatureIntentStatus.UnknownOutcome &&
                            intent.status == TemperatureIntentStatus.Pending ->
                            TemperatureMetadataWrite.Conflict

                        current == intent -> TemperatureMetadataWrite.Saved

                        else -> write(
                            intent.scope,
                            payload.copy(
                                commands = intents.map {
                                    if (it.command.fulfillmentId ==
                                        intent.command.fulfillmentId
                                    ) {
                                        intent
                                    } else {
                                        it
                                    }
                                }
                            )
                        )
                    }
                }
            }
        }

    override suspend fun clearIntent(
        scope: TemperatureScopeIdentity,
        fulfillmentId: String,
        idempotencyKey: String
    ): TemperatureMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> TemperatureMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) {
                        MetadataPayload()
                    } else {
                        return@withLock TemperatureMetadataWrite.Unavailable
                    }
                val intents = payload.commands
                val current = intents.firstOrNull { it.command.fulfillmentId == fulfillmentId }
                    ?: return@withLock TemperatureMetadataWrite.Saved
                if (current.scope != scope || current.command.idempotencyKey != idempotencyKey) {
                    TemperatureMetadataWrite.Stale
                } else {
                    val remaining = intents.filterNot { it.command.fulfillmentId == fulfillmentId }
                    val updated = payload.copy(commands = remaining)
                    val cleared = if (updated.isEmpty()) {
                        local.clear(scope.toLocal())
                    } else {
                        local.save(scope.toLocal(), encode(updated))
                    }
                    if (cleared) {
                        TemperatureMetadataWrite.Saved
                    } else {
                        TemperatureMetadataWrite.Unavailable
                    }
                }
            }
        }
    }

    override suspend fun loadPhotoUploadIntent(
        scope: TemperatureScopeIdentity,
        fulfillmentId: String,
        lotId: String
    ): TemperaturePhotoUploadMetadataRead = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> TemperaturePhotoUploadMetadataRead.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) {
                        MetadataPayload()
                    } else {
                        return@withLock TemperaturePhotoUploadMetadataRead.Unavailable
                    }
                val intent = payload.photoUploads.firstOrNull {
                    it.fulfillmentId == fulfillmentId && it.lotId == lotId
                }
                if (intent != null && (intent.scope != scope || !intent.isValid())) {
                    TemperaturePhotoUploadMetadataRead.Unavailable
                } else {
                    TemperaturePhotoUploadMetadataRead.Available(intent)
                }
            }
        }
    }

    override suspend fun savePhotoUploadIntent(
        intent: TemperaturePhotoUploadIntent
    ): TemperatureMetadataWrite = mutex(intent.scope).withLock {
        if (!intent.isValid()) return@withLock TemperatureMetadataWrite.Conflict
        when (val stored = local.load(intent.scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> TemperatureMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) {
                        MetadataPayload()
                    } else {
                        return@withLock TemperatureMetadataWrite.Unavailable
                    }
                val current = payload.photoUploads.firstOrNull {
                    it.fulfillmentId == intent.fulfillmentId && it.lotId == intent.lotId
                }
                when {
                    current == null && payload.photoUploads.size >= MAX_INTENTS ->
                        TemperatureMetadataWrite.Unavailable

                    current != null && current != intent ->
                        TemperatureMetadataWrite.Conflict

                    current == intent -> TemperatureMetadataWrite.Saved

                    else -> write(
                        intent.scope,
                        payload.copy(
                            photoUploads =
                                payload.photoUploads + intent
                        )
                    )
                }
            }
        }
    }

    override suspend fun clearPhotoUploadIntent(
        scope: TemperatureScopeIdentity,
        fulfillmentId: String,
        lotId: String,
        idempotencyKey: String
    ): TemperatureMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> TemperatureMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload = stored.payload?.let(::decode)
                    ?: if (stored.payload == null) {
                        MetadataPayload()
                    } else {
                        return@withLock TemperatureMetadataWrite.Unavailable
                    }
                val current = payload.photoUploads.firstOrNull {
                    it.fulfillmentId == fulfillmentId && it.lotId == lotId
                } ?: return@withLock TemperatureMetadataWrite.Saved
                if (current.scope != scope || current.idempotencyKey != idempotencyKey) {
                    TemperatureMetadataWrite.Stale
                } else {
                    val updated = payload.copy(
                        photoUploads = payload.photoUploads.filterNot {
                            it ==
                                current
                        }
                    )
                    val cleared = if (updated.isEmpty()) {
                        local.clear(scope.toLocal())
                    } else {
                        local.save(scope.toLocal(), encode(updated))
                    }
                    if (cleared) {
                        TemperatureMetadataWrite.Saved
                    } else {
                        TemperatureMetadataWrite.Unavailable
                    }
                }
            }
        }
    }

    private suspend fun write(
        scope: TemperatureScopeIdentity,
        payload: MetadataPayload
    ): TemperatureMetadataWrite = if (local.save(scope.toLocal(), encode(payload))) {
        TemperatureMetadataWrite.Saved
    } else {
        TemperatureMetadataWrite.Unavailable
    }

    private fun encode(payload: MetadataPayload): String = buildJsonObject {
        put("schema", JsonPrimitive(SCHEMA))
        put(
            "commands",
            JsonArray(
                payload.commands.map { intent ->
                    buildJsonObject {
                        put("userId", JsonPrimitive(intent.scope.userId))
                        put("tenantId", JsonPrimitive(intent.scope.tenantId))
                        put("workspaceId", JsonPrimitive(intent.scope.workspaceId))
                        put("membershipId", JsonPrimitive(intent.scope.membershipId))
                        put("fulfillmentId", JsonPrimitive(intent.command.fulfillmentId))
                        put(
                            "expectedFulfillmentVersion",
                            JsonPrimitive(intent.command.expectedFulfillmentVersion)
                        )
                        put("lotId", JsonPrimitive(intent.command.lotId))
                        put(
                            "valueCelsius",
                            JsonPrimitive(intent.command.valueCelsius.toPlainString())
                        )
                        put("occurredAt", JsonPrimitive(intent.command.occurredAt.toString()))
                        put("idempotencyKey", JsonPrimitive(intent.command.idempotencyKey))
                        put("exactRequestBody", JsonPrimitive(intent.command.exactRequestBody))
                        intent.command.evidenceObjectId?.let {
                            put("evidenceObjectId", JsonPrimitive(it))
                        }
                        intent.command.expectedLotVersion?.let {
                            put("expectedLotVersion", JsonPrimitive(it))
                        }
                        put("status", JsonPrimitive(intent.status.name))
                    }
                }
            )
        )
        put(
            "photoUploads",
            JsonArray(
                payload.photoUploads.map { intent ->
                    buildJsonObject {
                        put("userId", JsonPrimitive(intent.scope.userId))
                        put("tenantId", JsonPrimitive(intent.scope.tenantId))
                        put("workspaceId", JsonPrimitive(intent.scope.workspaceId))
                        put("membershipId", JsonPrimitive(intent.scope.membershipId))
                        put("fulfillmentId", JsonPrimitive(intent.fulfillmentId))
                        put("lotId", JsonPrimitive(intent.lotId))
                        put("warehouseId", JsonPrimitive(intent.warehouseId))
                        put("idempotencyKey", JsonPrimitive(intent.idempotencyKey))
                        put("originalFilename", JsonPrimitive(intent.originalFilename))
                        put("declaredContentType", JsonPrimitive(intent.declaredContentType))
                        put("byteSize", JsonPrimitive(intent.byteSize))
                        put("checksumSha256", JsonPrimitive(intent.checksumSha256))
                    }
                }
            )
        )
    }.toString()

    private fun decode(encoded: String): MetadataPayload? = try {
        val envelope = Json.parseToJsonElement(encoded).jsonObject
        val schema = envelope.requiredLong("schema")
        if (schema !in SUPPORTED_SCHEMAS) return null
        val intents = envelope["commands"]?.jsonArray?.map { element ->
            val value = element.jsonObject
            DispatchTemperatureIntent(
                scope = TemperatureScopeIdentity(
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
                    exactRequestBody = value.requiredStringAllowEmpty("exactRequestBody"),
                    evidenceObjectId = value.optionalString("evidenceObjectId"),
                    expectedLotVersion = value.optionalLong("expectedLotVersion")
                ),
                status = TemperatureIntentStatus.valueOf(value.requiredString("status"))
            )
        } ?: emptyList()
        val uploads = envelope["photoUploads"]?.jsonArray?.map { element ->
            val value = element.jsonObject
            TemperaturePhotoUploadIntent(
                scope = TemperatureScopeIdentity(
                    value.requiredString("userId"),
                    value.requiredString("tenantId"),
                    value.requiredString("workspaceId"),
                    value.requiredString("membershipId")
                ),
                fulfillmentId = value.requiredString("fulfillmentId"),
                lotId = value.requiredString("lotId"),
                warehouseId = value.requiredString("warehouseId"),
                idempotencyKey = value.requiredString("idempotencyKey"),
                originalFilename = value.requiredString("originalFilename"),
                declaredContentType = value.requiredString("declaredContentType"),
                byteSize = value.requiredLong("byteSize"),
                checksumSha256 = value.requiredString("checksumSha256")
            )
        } ?: emptyList()
        if (intents.map { it.command.fulfillmentId }.distinct().size != intents.size ||
            intents.any { !it.command.isValid() } ||
            uploads.map { it.fulfillmentId to it.lotId }.distinct().size != uploads.size ||
            uploads.any { !it.isValid() }
        ) {
            null
        } else {
            MetadataPayload(intents, uploads)
        }
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

    private fun JsonObject.optionalString(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonObject.optionalLong(key: String): Long? =
        this[key]?.jsonPrimitive?.takeUnless(JsonPrimitive::isString)?.longOrNull

    private fun TemperatureScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun mutex(scope: TemperatureScopeIdentity): Mutex = locks.computeIfAbsent(scope) {
        Mutex()
    }

    private data class MetadataPayload(
        val commands: List<DispatchTemperatureIntent> = emptyList(),
        val photoUploads: List<TemperaturePhotoUploadIntent> = emptyList()
    ) {
        fun isEmpty(): Boolean = commands.isEmpty() && photoUploads.isEmpty()
    }

    private companion object {
        const val SCHEMA = 2L
        val SUPPORTED_SCHEMAS = setOf(1L, SCHEMA)
        const val MAX_INTENTS = 32
        val locks = ConcurrentHashMap<TemperatureScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object AppDispatchTemperatureMetadataBindings {
    @Provides
    @Singleton
    fun dispatchTemperatureMetadataStore(
        @ApplicationContext context: Context
    ): TemperatureMetadataStore = AppDispatchTemperatureMetadataStore(
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DispatchTemperatureEvidence)
    )
}
