package com.nexa.mobile.operations

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyDraft
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyDraftRead
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyDraftStore
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyDraftWrite
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyKind
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyScope
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Encrypted app adapter for one inspectable local note. It does not enqueue network work. */
internal class AppInboundDiscrepancyDraftStore(
    private val backend: InboundDiscrepancyScopedMetadataBackend
) : InboundDiscrepancyDraftStore {
    override suspend fun load(scope: InboundDiscrepancyScope): InboundDiscrepancyDraftRead =
        withScopeLock(scope) {
            when (val stored = safeLoad(scope)) {
                InboundDiscrepancyScopedRead.Unavailable -> InboundDiscrepancyDraftRead.Unavailable
                is InboundDiscrepancyScopedRead.Value -> when (val payload = stored.payload) {
                    null -> InboundDiscrepancyDraftRead.Available(null)
                    else -> payload.decodeDraft()?.let(InboundDiscrepancyDraftRead::Available)
                        ?: InboundDiscrepancyDraftRead.Unavailable
                }
            }
        }

    override suspend fun save(
        scope: InboundDiscrepancyScope,
        draft: InboundDiscrepancyDraft
    ): InboundDiscrepancyDraftWrite = withScopeLock(scope) {
        if (!draft.isValid() || draft.id.isBlank()) return@withScopeLock InboundDiscrepancyDraftWrite.Unavailable
        val current = when (val stored = safeLoad(scope)) {
            InboundDiscrepancyScopedRead.Unavailable -> return@withScopeLock InboundDiscrepancyDraftWrite.Unavailable
            is InboundDiscrepancyScopedRead.Value -> stored.payload?.decodeDraft()
                ?: if (stored.payload == null) null
                else return@withScopeLock InboundDiscrepancyDraftWrite.Unavailable
        }
        if (current != null && current.id != draft.id) return@withScopeLock InboundDiscrepancyDraftWrite.Conflict
        val encoded = try {
            discrepancyDraftJson.encodeToString(draft.toStored())
        } catch (_: SerializationException) {
            return@withScopeLock InboundDiscrepancyDraftWrite.Unavailable
        }
        if (backend.save(scope, encoded)) InboundDiscrepancyDraftWrite.Saved
        else InboundDiscrepancyDraftWrite.Unavailable
    }

    override suspend fun discard(
        scope: InboundDiscrepancyScope,
        expectedDraftId: String
    ): InboundDiscrepancyDraftWrite = withScopeLock(scope) {
        if (expectedDraftId.isBlank()) return@withScopeLock InboundDiscrepancyDraftWrite.Conflict
        val current = when (val stored = safeLoad(scope)) {
            InboundDiscrepancyScopedRead.Unavailable -> return@withScopeLock InboundDiscrepancyDraftWrite.Unavailable
            is InboundDiscrepancyScopedRead.Value -> stored.payload?.decodeDraft()
                ?: if (stored.payload == null) null
                else return@withScopeLock InboundDiscrepancyDraftWrite.Unavailable
        } ?: return@withScopeLock InboundDiscrepancyDraftWrite.Conflict
        if (current.id != expectedDraftId) return@withScopeLock InboundDiscrepancyDraftWrite.Conflict
        if (backend.clear(scope)) InboundDiscrepancyDraftWrite.Discarded
        else InboundDiscrepancyDraftWrite.Unavailable
    }

    private suspend fun safeLoad(scope: InboundDiscrepancyScope): InboundDiscrepancyScopedRead = try {
        backend.load(scope)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        InboundDiscrepancyScopedRead.Unavailable
    }

    private suspend fun <T> withScopeLock(scope: InboundDiscrepancyScope, action: suspend () -> T): T =
        locks.getOrPut(scope.lockKey()) { Mutex() }.withLock { action() }

    private fun String.decodeDraft(): InboundDiscrepancyDraft? = try {
        val stored = discrepancyDraftJson.decodeFromString<StoredInboundDiscrepancyDraft>(this)
        if (stored.schemaVersion != SCHEMA_VERSION || stored.id.isBlank() ||
            stored.id.length > MAX_REFERENCE_LENGTH || stored.productReference.length > MAX_REFERENCE_LENGTH ||
            stored.lotOrBatchReference.length > MAX_REFERENCE_LENGTH || stored.reasonDetails.length > MAX_NOTE_LENGTH ||
            stored.expectedQuantityText.length > MAX_QUANTITY_LENGTH ||
            stored.observedQuantityText.length > MAX_QUANTITY_LENGTH || stored.evidencePlan.length > MAX_NOTE_LENGTH ||
            stored.observationNotes.length > MAX_NOTE_LENGTH || stored.capturedAtDeviceMillis <= 0
        ) {
            null
        } else {
            InboundDiscrepancyDraft(
                id = stored.id,
                productReference = stored.productReference,
                lotOrBatchReference = stored.lotOrBatchReference,
                kind = InboundDiscrepancyKind.valueOf(stored.kind),
                reasonDetails = stored.reasonDetails,
                expectedQuantityText = stored.expectedQuantityText,
                observedQuantityText = stored.observedQuantityText,
                evidencePlan = stored.evidencePlan,
                observationNotes = stored.observationNotes,
                capturedAtDeviceMillis = stored.capturedAtDeviceMillis
            )
        }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun InboundDiscrepancyDraft.toStored() = StoredInboundDiscrepancyDraft(
        schemaVersion = SCHEMA_VERSION,
        id = id,
        productReference = productReference,
        lotOrBatchReference = lotOrBatchReference,
        kind = kind.name,
        reasonDetails = reasonDetails,
        expectedQuantityText = expectedQuantityText,
        observedQuantityText = observedQuantityText,
        evidencePlan = evidencePlan,
        observationNotes = observationNotes,
        capturedAtDeviceMillis = capturedAtDeviceMillis
    )

    private fun InboundDiscrepancyDraft.isValid(): Boolean =
        id.isNotBlank() && id.length <= MAX_REFERENCE_LENGTH &&
            productReference.length <= MAX_REFERENCE_LENGTH && lotOrBatchReference.length <= MAX_REFERENCE_LENGTH &&
            reasonDetails.length <= MAX_NOTE_LENGTH && expectedQuantityText.length <= MAX_QUANTITY_LENGTH &&
            observedQuantityText.length <= MAX_QUANTITY_LENGTH && evidencePlan.length <= MAX_NOTE_LENGTH &&
            observationNotes.length <= MAX_NOTE_LENGTH && capturedAtDeviceMillis > 0

    private fun InboundDiscrepancyScope.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun InboundDiscrepancyScope.lockKey(): String {
        val bytes = listOf(userId, tenantId, workspaceId, membershipId).joinToString("\u0000")
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_REFERENCE_LENGTH = 240
        const val MAX_NOTE_LENGTH = 2_000
        const val MAX_QUANTITY_LENGTH = 80
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}

@Serializable
private data class StoredInboundDiscrepancyDraft(
    val schemaVersion: Int,
    val id: String,
    val productReference: String,
    val lotOrBatchReference: String,
    val kind: String,
    val reasonDetails: String,
    val expectedQuantityText: String,
    val observedQuantityText: String,
    val evidencePlan: String,
    val observationNotes: String,
    val capturedAtDeviceMillis: Long
)

private val discrepancyDraftJson = Json { ignoreUnknownKeys = false }

@Module
@InstallIn(SingletonComponent::class)
internal object AppInboundDiscrepancyDraftBindings {
    @Provides
    @Singleton
    fun inboundDiscrepancyDraftStore(@ApplicationContext context: Context): InboundDiscrepancyDraftStore =
        AppInboundDiscrepancyDraftStore(
            AndroidInboundDiscrepancyScopedMetadataBackend(
                AndroidScopedMetadataStore(context, ScopedMetadataPurpose.InboundDiscrepancyDraft)
            )
        )
}

internal object InboundDiscrepancyViewModelBindings {
    fun viewModelFactory(store: InboundDiscrepancyDraftStore): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(InboundDiscrepancyViewModel::class.java))
                return InboundDiscrepancyViewModel(store) as T
            }
        }
}

internal sealed interface InboundDiscrepancyScopedRead {
    data class Value(val payload: String?) : InboundDiscrepancyScopedRead
    data object Unavailable : InboundDiscrepancyScopedRead
}

internal interface InboundDiscrepancyScopedMetadataBackend {
    suspend fun load(scope: InboundDiscrepancyScope): InboundDiscrepancyScopedRead
    suspend fun save(scope: InboundDiscrepancyScope, payload: String): Boolean
    suspend fun clear(scope: InboundDiscrepancyScope): Boolean
}

internal class AndroidInboundDiscrepancyScopedMetadataBackend(
    private val store: AndroidScopedMetadataStore
) : InboundDiscrepancyScopedMetadataBackend {
    override suspend fun load(scope: InboundDiscrepancyScope): InboundDiscrepancyScopedRead =
        when (val result = store.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> InboundDiscrepancyScopedRead.Unavailable
            is ScopedMetadataRead.Value -> InboundDiscrepancyScopedRead.Value(result.payload)
        }

    override suspend fun save(scope: InboundDiscrepancyScope, payload: String): Boolean =
        store.save(scope.toLocal(), payload)

    override suspend fun clear(scope: InboundDiscrepancyScope): Boolean = store.clear(scope.toLocal())

    private fun InboundDiscrepancyScope.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)
}
