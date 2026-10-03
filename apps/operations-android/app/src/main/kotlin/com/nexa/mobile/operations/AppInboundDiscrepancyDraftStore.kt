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
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyEvidenceArtifactStore
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyGateway
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyKind
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyPendingAction
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Encrypted app adapter for locally retained observations and frozen retry metadata. */
internal class AppInboundDiscrepancyDraftStore(
    private val backend: InboundDiscrepancyScopedMetadataBackend
) : InboundDiscrepancyDraftStore {
    override suspend fun load(scope: InboundDiscrepancyScope): InboundDiscrepancyDraftRead =
        withScopeLock(scope) {
            when (val stored = safeLoad(scope)) {
                InboundDiscrepancyScopedRead.Unavailable -> InboundDiscrepancyDraftRead.Unavailable

                is InboundDiscrepancyScopedRead.Value -> stored.payload?.let { it.decodeDraft() }
                    ?.let(InboundDiscrepancyDraftRead::Available)
                    ?: if (stored.payload == null) {
                        InboundDiscrepancyDraftRead.Available(null)
                    } else {
                        InboundDiscrepancyDraftRead.Unavailable
                    }
            }
        }

    override suspend fun save(
        scope: InboundDiscrepancyScope,
        draft: InboundDiscrepancyDraft
    ): InboundDiscrepancyDraftWrite = withScopeLock(scope) {
        if (!draft.isValid()) return@withScopeLock InboundDiscrepancyDraftWrite.Unavailable
        val stored = safeLoad(scope)
        val existing = when (stored) {
            InboundDiscrepancyScopedRead.Unavailable ->
                return@withScopeLock InboundDiscrepancyDraftWrite.Unavailable

            is InboundDiscrepancyScopedRead.Value -> stored.payload?.decodeDraft()
                ?: if (stored.payload ==
                    null
                ) {
                    null
                } else {
                    return@withScopeLock InboundDiscrepancyDraftWrite.Unavailable
                }
        }
        if (existing != null &&
            existing.id != draft.id
        ) {
            return@withScopeLock InboundDiscrepancyDraftWrite.Conflict
        }
        val payload = try {
            discrepancyJson.encodeToString(draft.toStored())
        } catch (
            _: SerializationException
        ) {
            return@withScopeLock InboundDiscrepancyDraftWrite.Unavailable
        }
        if (backend.save(scope, payload)) {
            InboundDiscrepancyDraftWrite.Saved
        } else {
            InboundDiscrepancyDraftWrite.Unavailable
        }
    }

    override suspend fun discard(
        scope: InboundDiscrepancyScope,
        expectedDraftId: String
    ): InboundDiscrepancyDraftWrite = withScopeLock(scope) {
        if (expectedDraftId.isBlank()) return@withScopeLock InboundDiscrepancyDraftWrite.Conflict
        val existing = when (val stored = safeLoad(scope)) {
            InboundDiscrepancyScopedRead.Unavailable ->
                return@withScopeLock InboundDiscrepancyDraftWrite.Unavailable

            is InboundDiscrepancyScopedRead.Value -> stored.payload?.decodeDraft()
                ?: if (stored.payload ==
                    null
                ) {
                    null
                } else {
                    return@withScopeLock InboundDiscrepancyDraftWrite.Unavailable
                }
        } ?: return@withScopeLock InboundDiscrepancyDraftWrite.Conflict
        if (existing.id != expectedDraftId || existing.caseId != null ||
            existing.pendingAction != null
        ) {
            return@withScopeLock InboundDiscrepancyDraftWrite.Conflict
        }
        if (backend.clear(scope)) {
            InboundDiscrepancyDraftWrite.Discarded
        } else {
            InboundDiscrepancyDraftWrite.Unavailable
        }
    }

    private suspend fun safeLoad(scope: InboundDiscrepancyScope): InboundDiscrepancyScopedRead =
        try {
            backend.load(scope)
        } catch (
            cancelled: CancellationException
        ) {
            throw cancelled
        } catch (_: Exception) {
            InboundDiscrepancyScopedRead.Unavailable
        }

    private suspend fun <T> withScopeLock(
        scope: InboundDiscrepancyScope,
        action: suspend () -> T
    ): T = locks.getOrPut(scope.lockKey()) { Mutex() }.withLock { action() }

    private fun String.decodeDraft(): InboundDiscrepancyDraft? = try {
        val stored = discrepancyJson.decodeFromString<StoredInboundDiscrepancyDraft>(this)
        if (stored.schemaVersion != SCHEMA_VERSION || stored.id.isBlank() ||
            stored.capturedAtDeviceMillis <= 0 ||
            stored.id.length > MAX_REFERENCE_LENGTH ||
            stored.warehouseId.length > MAX_REFERENCE_LENGTH ||
            stored.expectedSkuId.length > MAX_REFERENCE_LENGTH ||
            stored.observedSkuId.length > MAX_REFERENCE_LENGTH ||
            (stored.observedSkuLabel?.length ?: 0) > MAX_NOTE_LENGTH ||
            stored.expectedBatchReference.length > MAX_REFERENCE_LENGTH ||
            stored.observedBatchReference.length > MAX_REFERENCE_LENGTH ||
            stored.expectedQuantityText.length > MAX_QUANTITY_LENGTH ||
            stored.observedQuantityText.length > MAX_QUANTITY_LENGTH ||
            stored.unit.length > 32 || stored.reasonDetails.length > MAX_NOTE_LENGTH ||
            stored.observationNotes.length > MAX_NOTE_LENGTH ||
            (stored.createBody?.length ?: 0) > MAX_COMMAND_LENGTH ||
            (stored.submitBody?.length ?: 0) > MAX_COMMAND_LENGTH
        ) {
            return null
        }
        InboundDiscrepancyDraft(
            id = stored.id,
            warehouseId = stored.warehouseId,
            expectedSkuId = stored.expectedSkuId,
            observedSkuId = stored.observedSkuId,
            observedSkuLabel = stored.observedSkuLabel,
            expectedBatchReference = stored.expectedBatchReference,
            observedBatchReference = stored.observedBatchReference,
            expectedQuantityText = stored.expectedQuantityText,
            observedQuantityText = stored.observedQuantityText,
            unit = stored.unit,
            kind = InboundDiscrepancyKind.valueOf(stored.kind),
            reasonDetails = stored.reasonDetails,
            observationNotes = stored.observationNotes,
            capturedAtDeviceMillis = stored.capturedAtDeviceMillis,
            createIdempotencyKey = stored.createIdempotencyKey,
            createBody = stored.createBody,
            caseId = stored.caseId,
            caseVersion = stored.caseVersion,
            caseStatus = stored.caseStatus,
            evidenceUploadKey = stored.evidenceUploadKey,
            evidenceId = stored.evidenceId,
            evidenceStatus = stored.evidenceStatus,
            submitIdempotencyKey = stored.submitIdempotencyKey,
            submitBody = stored.submitBody,
            pendingAction = stored.pendingAction?.let(InboundDiscrepancyPendingAction::valueOf)
        )
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun InboundDiscrepancyDraft.toStored() = StoredInboundDiscrepancyDraft(
        schemaVersion = SCHEMA_VERSION,
        id = id,
        warehouseId = warehouseId,
        expectedSkuId = expectedSkuId,
        observedSkuId = observedSkuId,
        observedSkuLabel = observedSkuLabel,
        expectedBatchReference = expectedBatchReference,
        observedBatchReference = observedBatchReference,
        expectedQuantityText = expectedQuantityText,
        observedQuantityText = observedQuantityText,
        unit = unit,
        kind = kind.name,
        reasonDetails = reasonDetails,
        observationNotes = observationNotes,
        capturedAtDeviceMillis = capturedAtDeviceMillis,
        createIdempotencyKey = createIdempotencyKey,
        createBody = createBody,
        caseId = caseId,
        caseVersion = caseVersion,
        caseStatus = caseStatus,
        evidenceUploadKey = evidenceUploadKey,
        evidenceId = evidenceId,
        evidenceStatus = evidenceStatus,
        submitIdempotencyKey = submitIdempotencyKey,
        submitBody = submitBody,
        pendingAction = pendingAction?.name
    )

    private fun InboundDiscrepancyDraft.isValid(): Boolean =
        id.isNotBlank() && id.length <= MAX_REFERENCE_LENGTH &&
            warehouseId.length <= MAX_REFERENCE_LENGTH &&
            expectedSkuId.length <= MAX_REFERENCE_LENGTH &&
            observedSkuId.length <= MAX_REFERENCE_LENGTH &&
            (observedSkuLabel?.length ?: 0) <= MAX_NOTE_LENGTH &&
            expectedBatchReference.length <= MAX_REFERENCE_LENGTH &&
            observedBatchReference.length <= MAX_REFERENCE_LENGTH &&
            expectedQuantityText.length <= MAX_QUANTITY_LENGTH &&
            observedQuantityText.length <= MAX_QUANTITY_LENGTH &&
            unit.length <= 32 && reasonDetails.length <= MAX_NOTE_LENGTH &&
            observationNotes.length <= MAX_NOTE_LENGTH &&
            capturedAtDeviceMillis > 0 && (createBody?.length ?: 0) <= MAX_COMMAND_LENGTH &&
            (submitBody?.length ?: 0) <= MAX_COMMAND_LENGTH && caseVersion?.let { it >= 0 } != false

    private fun InboundDiscrepancyScope.lockKey(): String {
        val bytes = listOf(
            userId,
            tenantId,
            workspaceId,
            membershipId
        ).joinToString("\u0000").toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
    }

    private fun InboundDiscrepancyScope.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private companion object {
        const val SCHEMA_VERSION = 2
        const val MAX_REFERENCE_LENGTH = 240
        const val MAX_QUANTITY_LENGTH = 80
        const val MAX_NOTE_LENGTH = 2_000
        const val MAX_COMMAND_LENGTH = 8_000
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}

@Serializable
private data class StoredInboundDiscrepancyDraft(
    val schemaVersion: Int,
    val id: String,
    val warehouseId: String,
    val expectedSkuId: String,
    val observedSkuId: String,
    val observedSkuLabel: String? = null,
    val expectedBatchReference: String,
    val observedBatchReference: String,
    val expectedQuantityText: String,
    val observedQuantityText: String,
    val unit: String,
    val kind: String,
    val reasonDetails: String,
    val observationNotes: String,
    val capturedAtDeviceMillis: Long,
    val createIdempotencyKey: String? = null,
    val createBody: String? = null,
    val caseId: String? = null,
    val caseVersion: Long? = null,
    val caseStatus: String? = null,
    val evidenceUploadKey: String? = null,
    val evidenceId: String? = null,
    val evidenceStatus: String? = null,
    val submitIdempotencyKey: String? = null,
    val submitBody: String? = null,
    val pendingAction: String? = null
)

private val discrepancyJson = Json { ignoreUnknownKeys = false }

@Module
@InstallIn(SingletonComponent::class)
internal object AppInboundDiscrepancyDraftBindings {
    @Provides
    @Singleton
    fun inboundDiscrepancyDraftStore(
        @ApplicationContext context: Context
    ): InboundDiscrepancyDraftStore = AppInboundDiscrepancyDraftStore(
        AndroidInboundDiscrepancyScopedMetadataBackend(
            AndroidScopedMetadataStore(context, ScopedMetadataPurpose.InboundDiscrepancyDraft)
        )
    )

    @Provides
    @Singleton
    fun inboundDiscrepancyArtifactStore(
        @ApplicationContext context: Context
    ): InboundDiscrepancyEvidenceArtifactStore = AppInboundDiscrepancyEvidenceArtifactStore(context)
}

internal object InboundDiscrepancyViewModelBindings {
    fun viewModelFactory(
        gateway: InboundDiscrepancyGateway,
        drafts: InboundDiscrepancyDraftStore,
        artifacts: InboundDiscrepancyEvidenceArtifactStore
    ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(InboundDiscrepancyViewModel::class.java))
            return InboundDiscrepancyViewModel(gateway, drafts, artifacts) as T
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
    override suspend fun clear(scope: InboundDiscrepancyScope): Boolean =
        store.clear(scope.toLocal())
    private fun InboundDiscrepancyScope.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)
}
