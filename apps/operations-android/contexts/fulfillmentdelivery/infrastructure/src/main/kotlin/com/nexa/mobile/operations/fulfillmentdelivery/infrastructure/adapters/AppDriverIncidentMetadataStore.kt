package com.nexa.mobile.operations.fulfillmentdelivery.infrastructure.adapters

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentCommand
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentEvidenceDraft as IncidentEvidenceDraft
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentEvidenceStage as IncidentEvidenceStage
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentMetadata
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentMetadataRead
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentMetadataStore as IncidentMetadataStore
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentMetadataWrite as IncidentMetadataWrite
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentRecordStatus
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverIncidentSelectionContext as IncidentSelectionContext
import com.nexa.mobile.operations.fulfillmentdelivery.application.delivery.DriverProofFileCandidate
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverIncidentType
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Typed encrypted incident draft and exact command storage; contains no authorization state. */
class AppDriverIncidentMetadataStore(
    context: Context,
    private val local: AndroidScopedMetadataStore
) : IncidentMetadataStore {
    private val appContext = context.applicationContext
    private val artifactDirectory =
        File(appContext.noBackupFilesDir, "driver-delivery-incident-evidence")
    private val plaintextDirectory = File(appContext.cacheDir, "driver-delivery-incident-upload")
    override suspend fun load(scope: DriverAttemptScopeIdentity): DriverIncidentMetadataRead =
        mutex(scope).withLock {
            when (val stored = local.load(scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> DriverIncidentMetadataRead.Unavailable

                is ScopedMetadataRead.Value -> {
                    val payload = stored.payload
                        ?: return@withLock DriverIncidentMetadataRead.Available(null)
                    val metadata = decode(payload)
                        ?: return@withLock DriverIncidentMetadataRead.Unavailable
                    if (metadata.scope !=
                        scope
                    ) {
                        return@withLock DriverIncidentMetadataRead.Unavailable
                    }
                    val evidence = metadata.evidence?.let { candidate ->
                        when (candidate.stage) {
                            IncidentEvidenceStage.UploadPending -> candidate.copy(
                                stage = IncidentEvidenceStage.UploadUnknownOutcome
                            )

                            IncidentEvidenceStage.AttachPending -> candidate.copy(
                                stage = IncidentEvidenceStage.AttachUnknownOutcome
                            )

                            else -> candidate
                        }
                    }
                    val status = if (metadata.status == DriverIncidentRecordStatus.Pending) {
                        DriverIncidentRecordStatus.UnknownOutcome
                    } else {
                        metadata.status
                    }
                    if (status != metadata.status || evidence != metadata.evidence) {
                        val recovered = metadata.copy(status = status, evidence = evidence)
                        if (local.save(scope.toLocal(), encode(recovered))) {
                            DriverIncidentMetadataRead.Available(recovered)
                        } else {
                            DriverIncidentMetadataRead.Unavailable
                        }
                    } else {
                        DriverIncidentMetadataRead.Available(metadata)
                    }
                }
            }
        }

    override suspend fun saveDraft(metadata: DriverIncidentMetadata): IncidentMetadataWrite =
        mutex(metadata.scope).withLock {
            when (val stored = local.load(metadata.scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> IncidentMetadataWrite.Unavailable

                is ScopedMetadataRead.Value -> {
                    val current = stored.payload?.let(::decode)
                    if (stored.payload != null &&
                        current == null
                    ) {
                        return@withLock IncidentMetadataWrite.Unavailable
                    }
                    when {
                        metadata.status != DriverIncidentRecordStatus.Draft ||
                            metadata.command != null ->
                            IncidentMetadataWrite.Unavailable

                        current == null -> save(metadata)

                        current.scope != metadata.scope -> IncidentMetadataWrite.Unavailable

                        current.deliveryId != metadata.deliveryId ||
                            current.attemptId != metadata.attemptId ->
                            IncidentMetadataWrite.Conflict

                        current.draftId != metadata.draftId -> IncidentMetadataWrite.Conflict

                        current.status != DriverIncidentRecordStatus.Draft ->
                            IncidentMetadataWrite.Conflict

                        else -> save(metadata)
                    }
                }
            }
        }

    override suspend fun persistIntent(metadata: DriverIncidentMetadata): IncidentMetadataWrite =
        mutex(metadata.scope).withLock {
            when (val stored = local.load(metadata.scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> IncidentMetadataWrite.Unavailable

                is ScopedMetadataRead.Value -> {
                    val current = stored.payload?.let(::decode)
                        ?: return@withLock IncidentMetadataWrite.Stale
                    val command = metadata.command
                    if (metadata.status == DriverIncidentRecordStatus.Draft || command == null) {
                        return@withLock IncidentMetadataWrite.Unavailable
                    }
                    if (current.scope != metadata.scope ||
                        current.deliveryId != metadata.deliveryId ||
                        current.attemptId != metadata.attemptId ||
                        current.draftId != metadata.draftId
                    ) {
                        return@withLock IncidentMetadataWrite.Conflict
                    }
                    when {
                        current.command == command && current.status == metadata.status -> save(
                            metadata
                        )

                        current.command == command &&
                            current.status == DriverIncidentRecordStatus.UnknownOutcome &&
                            metadata.status == DriverIncidentRecordStatus.Pending ->
                            IncidentMetadataWrite.Conflict

                        current.status == DriverIncidentRecordStatus.Draft &&
                            current.command == null &&
                            current.draftId == metadata.draftId &&
                            current.reason == metadata.reason &&
                            current.description == metadata.description &&
                            current.place == metadata.place &&
                            current.type == metadata.type -> save(
                            metadata
                        )

                        else -> IncidentMetadataWrite.Conflict
                    }
                }
            }
        }

    override suspend fun persistRecorded(metadata: DriverIncidentMetadata): IncidentMetadataWrite =
        mutex(metadata.scope).withLock {
            when (val stored = local.load(metadata.scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> IncidentMetadataWrite.Unavailable

                is ScopedMetadataRead.Value -> {
                    val current = stored.payload?.let(::decode)
                        ?: return@withLock IncidentMetadataWrite.Stale
                    if (metadata.status != DriverIncidentRecordStatus.RecordedWithEvidence ||
                        metadata.command != null || metadata.incidentId.isNullOrBlank() ||
                        metadata.evidence == null || current.scope != metadata.scope ||
                        current.deliveryId != metadata.deliveryId ||
                        current.attemptId != metadata.attemptId ||
                        current.draftId != metadata.draftId || current.command == null ||
                        current.reason != metadata.reason ||
                        current.description != metadata.description ||
                        current.place != metadata.place || current.type != metadata.type ||
                        current.severity != metadata.severity ||
                        current.operationalExceptionId != metadata.operationalExceptionId ||
                        current.evidence != metadata.evidence
                    ) {
                        return@withLock IncidentMetadataWrite.Conflict
                    }
                    save(metadata)
                }
            }
        }

    override suspend fun stageReturnedEvidence(
        context: IncidentSelectionContext,
        candidate: DriverProofFileCandidate
    ): IncidentMetadataWrite = mutex(context.scope).withLock {
        try {
            when (val stored = local.load(context.scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> IncidentMetadataWrite.Unavailable

                is ScopedMetadataRead.Value -> {
                    val current = stored.payload?.let(::decode)
                        ?: return@withLock IncidentMetadataWrite.Stale
                    if (
                        current.scope != context.scope ||
                        current.deliveryId != context.deliveryId ||
                        current.attemptId != context.attemptId ||
                        current.draftId != context.draftId ||
                        current.draftVersion != context.deliveryVersion ||
                        current.status != DriverIncidentRecordStatus.Draft ||
                        current.command != null ||
                        current.evidence?.stage !in setOf(null, IncidentEvidenceStage.Staged)
                    ) {
                        return@withLock IncidentMetadataWrite.Conflict
                    }
                    val evidence = IncidentEvidenceDraft(
                        fileToken = UUID.randomUUID().toString(),
                        originalFilename = candidate.originalFilename,
                        contentType = candidate.declaredContentType,
                        byteSize = candidate.byteSize,
                        checksumSha256 = candidate.checksumSha256
                    )
                    if (!writeEncryptedArtifact(context.scope, evidence, candidate)) {
                        return@withLock IncidentMetadataWrite.Unavailable
                    }
                    val saved = save(current.copy(evidence = evidence))
                    if (saved == IncidentMetadataWrite.Saved) {
                        current.evidence?.let {
                            deleteEncryptedArtifact(context.scope, it.fileToken)
                        }
                    } else {
                        deleteEncryptedArtifact(context.scope, evidence.fileToken)
                    }
                    saved
                }
            }
        } finally {
            candidate.file.delete()
        }
    }

    override suspend fun updateRecordedEvidence(
        metadata: DriverIncidentMetadata
    ): IncidentMetadataWrite = mutex(metadata.scope).withLock {
        when (val stored = local.load(metadata.scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> IncidentMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val current = stored.payload?.let(::decode)
                    ?: return@withLock IncidentMetadataWrite.Stale
                val before = current.evidence
                val after = metadata.evidence
                if (metadata.status != DriverIncidentRecordStatus.RecordedWithEvidence ||
                    metadata.command != null ||
                    current.status != DriverIncidentRecordStatus.RecordedWithEvidence ||
                    current.scope != metadata.scope || current.deliveryId != metadata.deliveryId ||
                    current.attemptId != metadata.attemptId ||
                    current.draftId != metadata.draftId ||
                    current.incidentId != metadata.incidentId || before == null || after == null ||
                    before.fileToken != after.fileToken ||
                    before.originalFilename != after.originalFilename ||
                    before.contentType != after.contentType || before.byteSize != after.byteSize ||
                    before.checksumSha256 != after.checksumSha256 ||
                    !validEvidenceTransition(before.stage, after.stage)
                ) {
                    return@withLock IncidentMetadataWrite.Conflict
                }
                save(metadata)
            }
        }
    }

    override suspend fun loadCandidate(
        metadata: DriverIncidentMetadata
    ): DriverProofFileCandidate? = mutex(metadata.scope).withLock {
        metadata.evidence?.let { decryptArtifact(metadata.scope, it) }
    }

    override suspend fun clearCandidate(metadata: DriverIncidentMetadata): Boolean =
        mutex(metadata.scope).withLock {
            metadata.evidence?.let { deleteEncryptedArtifact(metadata.scope, it.fileToken) } ?: true
        }

    override suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        deliveryId: String,
        idempotencyKey: String
    ): IncidentMetadataWrite = mutex(scope).withLock {
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> IncidentMetadataWrite.Unavailable

            is ScopedMetadataRead.Value -> {
                val payload = stored.payload ?: return@withLock IncidentMetadataWrite.Saved
                val current =
                    decode(payload) ?: return@withLock IncidentMetadataWrite.Unavailable
                if (current.scope != scope || current.deliveryId != deliveryId ||
                    current.command?.idempotencyKey != idempotencyKey
                ) {
                    IncidentMetadataWrite.Stale
                } else if (current.evidence != null) {
                    save(
                        current.copy(
                            status = DriverIncidentRecordStatus.Draft,
                            command = null,
                            incidentId = null,
                            recordedAt = null,
                            recordedByMembershipId = null,
                            deliveryVersion = null,
                            severity = null,
                            operationalExceptionId = null
                        )
                    )
                } else if (local.clear(scope.toLocal())) {
                    IncidentMetadataWrite.Saved
                } else {
                    IncidentMetadataWrite.Unavailable
                }
            }
        }
    }

    private suspend fun save(metadata: DriverIncidentMetadata): IncidentMetadataWrite =
        if (local.save(metadata.scope.toLocal(), encode(metadata))) {
            IncidentMetadataWrite.Saved
        } else {
            IncidentMetadataWrite.Unavailable
        }

    private fun encode(metadata: DriverIncidentMetadata): String {
        val command = metadata.command
        return JsonObject(
            linkedMapOf(
                "schema" to JsonPrimitive(1),
                "userId" to JsonPrimitive(metadata.scope.userId),
                "tenantId" to JsonPrimitive(metadata.scope.tenantId),
                "workspaceId" to JsonPrimitive(metadata.scope.workspaceId),
                "membershipId" to JsonPrimitive(metadata.scope.membershipId),
                "deliveryId" to JsonPrimitive(metadata.deliveryId),
                "attemptId" to JsonPrimitive(metadata.attemptId),
                "draftId" to JsonPrimitive(metadata.draftId),
                "draftVersion" to JsonPrimitive(metadata.draftVersion),
                "reason" to JsonPrimitive(metadata.reason),
                "description" to JsonPrimitive(metadata.description),
                "place" to JsonPrimitive(metadata.place),
                "type" to (metadata.type?.let { JsonPrimitive(it.name) } ?: JsonNull),
                "status" to JsonPrimitive(metadata.status.name),
                "command" to (
                    command?.let {
                        JsonObject(
                            linkedMapOf(
                                "expectedVersion" to JsonPrimitive(it.expectedVersion),
                                "idempotencyKey" to JsonPrimitive(it.idempotencyKey),
                                "reason" to JsonPrimitive(it.reason),
                                "description" to JsonPrimitive(it.description),
                                "place" to JsonPrimitive(it.place),
                                "frozenBody" to JsonPrimitive(it.frozenBody),
                                "type" to
                                    (it.type?.let { type -> JsonPrimitive(type.name) } ?: JsonNull)
                            )
                        )
                    } ?: JsonPrimitive("")
                    ),
                "incidentId" to (metadata.incidentId?.let(::JsonPrimitive) ?: JsonNull),
                "evidence" to (metadata.evidence?.let(::encodeEvidence) ?: JsonNull),
                "recordedAt" to (metadata.recordedAt?.let(::JsonPrimitive) ?: JsonNull),
                "recordedByMembershipId" to
                    (metadata.recordedByMembershipId?.let(::JsonPrimitive) ?: JsonNull),
                "deliveryVersion" to (metadata.deliveryVersion?.let(::JsonPrimitive) ?: JsonNull),
                "severity" to (metadata.severity?.let(::JsonPrimitive) ?: JsonNull),
                "operationalExceptionId" to
                    (metadata.operationalExceptionId?.let(::JsonPrimitive) ?: JsonNull)
            )
        ).toString()
    }

    private fun decode(payload: String): DriverIncidentMetadata? = try {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.requiredLong("schema") == 1L)
        val scope = DriverAttemptScopeIdentity(
            root.requiredString("userId"),
            root.requiredString("tenantId"),
            root.requiredString("workspaceId"),
            root.requiredString("membershipId")
        )
        val deliveryId = root.requiredString("deliveryId")
        val attemptId = root.requiredString("attemptId")
        val commandValue = root["command"]
            ?: error("Driver incident metadata command is missing")
        val command = if (commandValue is JsonObject) {
            DriverIncidentCommand(
                deliveryId = deliveryId,
                attemptId = attemptId,
                expectedVersion = commandValue.requiredLong("expectedVersion"),
                idempotencyKey = commandValue.requiredString("idempotencyKey"),
                reason = commandValue.requiredString("reason"),
                description = commandValue.requiredString("description"),
                place = commandValue.requiredString("place"),
                frozenBody = commandValue.requiredString("frozenBody"),
                type = commandValue.optionalString("type")?.let(DriverIncidentType::valueOf)
            )
        } else {
            require(commandValue.jsonPrimitive.content == "")
            null
        }
        DriverIncidentMetadata(
            scope = scope,
            deliveryId = deliveryId,
            attemptId = attemptId,
            draftVersion = root.requiredLong("draftVersion"),
            reason = root.requiredStringAllowEmpty("reason"),
            description = root.requiredStringAllowEmpty("description"),
            place = root.requiredStringAllowEmpty("place"),
            status = DriverIncidentRecordStatus.valueOf(root.requiredString("status")),
            command = command,
            draftId = root.optionalString("draftId") ?: java.util.UUID.randomUUID().toString(),
            incidentId = root.optionalString("incidentId"),
            evidence = root.optionalObject("evidence")?.let(::decodeEvidence),
            recordedAt = root.optionalString("recordedAt"),
            recordedByMembershipId = root.optionalString("recordedByMembershipId"),
            deliveryVersion = root.optionalLong("deliveryVersion"),
            type = root.optionalString("type")?.let(DriverIncidentType::valueOf),
            severity = root.optionalString("severity"),
            operationalExceptionId = root.optionalString("operationalExceptionId")
        )
    } catch (_: Exception) {
        null
    }

    private fun encodeEvidence(evidence: IncidentEvidenceDraft) = JsonObject(
        linkedMapOf(
            "fileToken" to JsonPrimitive(evidence.fileToken),
            "originalFilename" to JsonPrimitive(evidence.originalFilename),
            "contentType" to JsonPrimitive(evidence.contentType),
            "byteSize" to JsonPrimitive(evidence.byteSize),
            "checksumSha256" to JsonPrimitive(evidence.checksumSha256),
            "stage" to JsonPrimitive(evidence.stage.name),
            "uploadIdempotencyKey" to
                (evidence.uploadIdempotencyKey?.let(::JsonPrimitive) ?: JsonNull),
            "evidenceId" to (evidence.evidenceId?.let(::JsonPrimitive) ?: JsonNull),
            "attachIdempotencyKey" to
                (evidence.attachIdempotencyKey?.let(::JsonPrimitive) ?: JsonNull),
            "attachExpectedVersion" to
                (evidence.attachExpectedVersion?.let(::JsonPrimitive) ?: JsonNull),
            "attachBody" to (evidence.attachBody?.let(::JsonPrimitive) ?: JsonNull)
        )
    )

    private fun decodeEvidence(value: JsonObject) = IncidentEvidenceDraft(
        fileToken = value.requiredString("fileToken"),
        originalFilename = value.requiredString("originalFilename"),
        contentType = value.requiredString("contentType"),
        byteSize = value.requiredLong("byteSize"),
        checksumSha256 = value.requiredString("checksumSha256"),
        stage = IncidentEvidenceStage.valueOf(value.requiredString("stage")),
        uploadIdempotencyKey = value.optionalString("uploadIdempotencyKey"),
        evidenceId = value.optionalString("evidenceId"),
        attachIdempotencyKey = value.optionalString("attachIdempotencyKey"),
        attachExpectedVersion = value.optionalLong("attachExpectedVersion"),
        attachBody = value.optionalString("attachBody")
    )

    private fun validEvidenceTransition(
        before: IncidentEvidenceStage,
        after: IncidentEvidenceStage
    ): Boolean = when (before) {
        IncidentEvidenceStage.Staged -> after in setOf(
            IncidentEvidenceStage.Staged,
            IncidentEvidenceStage.UploadPending
        )

        IncidentEvidenceStage.UploadPending -> after in setOf(
            IncidentEvidenceStage.UploadPending,
            IncidentEvidenceStage.UploadUnknownOutcome,
            IncidentEvidenceStage.AwaitingAvailability,
            IncidentEvidenceStage.Staged
        )

        IncidentEvidenceStage.UploadUnknownOutcome -> after in setOf(
            IncidentEvidenceStage.UploadUnknownOutcome,
            IncidentEvidenceStage.UploadPending,
            IncidentEvidenceStage.AwaitingAvailability,
            IncidentEvidenceStage.Staged
        )

        IncidentEvidenceStage.AwaitingAvailability -> after in setOf(
            IncidentEvidenceStage.AwaitingAvailability,
            IncidentEvidenceStage.AvailableForReview
        )

        IncidentEvidenceStage.AvailableForReview -> after in setOf(
            IncidentEvidenceStage.AvailableForReview,
            IncidentEvidenceStage.AwaitingAvailability,
            IncidentEvidenceStage.AttachPending
        )

        IncidentEvidenceStage.AttachPending -> after in setOf(
            IncidentEvidenceStage.AttachPending,
            IncidentEvidenceStage.AttachUnknownOutcome,
            IncidentEvidenceStage.Linked,
            IncidentEvidenceStage.AvailableForReview
        )

        IncidentEvidenceStage.AttachUnknownOutcome -> after in setOf(
            IncidentEvidenceStage.AttachUnknownOutcome,
            IncidentEvidenceStage.AttachPending,
            IncidentEvidenceStage.Linked
        )

        IncidentEvidenceStage.Linked -> after == IncidentEvidenceStage.Linked
    }

    private fun writeEncryptedArtifact(
        scope: DriverAttemptScopeIdentity,
        evidence: IncidentEvidenceDraft,
        candidate: DriverProofFileCandidate
    ): Boolean = try {
        require(candidate.file.isFile && candidate.file.length() == candidate.byteSize)
        val digest = MessageDigest.getInstance("SHA-256")
        check(artifactDirectory.isDirectory || artifactDirectory.mkdirs())
        val atomic = AtomicFile(artifactFile(scope, evidence.fileToken))
        val output = atomic.startWrite()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, artifactKeyForWrite())
            cipher.updateAAD(artifactBinding(scope, evidence.fileToken))
            output.write(ARTIFACT_HEADER)
            output.write(cipher.iv)
            var total = 0L
            FileInputStream(candidate.file).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_EVIDENCE_BYTES)
                    digest.update(buffer, 0, count)
                    cipher.update(buffer, 0, count)?.let(output::write)
                }
            }
            require(
                total == evidence.byteSize && digest.digest().toHex() == evidence.checksumSha256
            )
            output.write(cipher.doFinal())
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun decryptArtifact(
        scope: DriverAttemptScopeIdentity,
        evidence: IncidentEvidenceDraft
    ): DriverProofFileCandidate? {
        var plaintext: File? = null
        return try {
            val bytes = AtomicFile(artifactFile(scope, evidence.fileToken)).openRead().use {
                it.readBytes()
            }
            require(
                bytes.size in (ARTIFACT_HEADER.size + IV_BYTES + GCM_TAG_BYTES)..MAX_ENCRYPTED_BYTES
            )
            require(bytes.copyOfRange(0, ARTIFACT_HEADER.size).contentEquals(ARTIFACT_HEADER))
            val iv = bytes.copyOfRange(ARTIFACT_HEADER.size, ARTIFACT_HEADER.size + IV_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                artifactKeyForRead() ?: return null,
                GCMParameterSpec(128, iv)
            )
            cipher.updateAAD(artifactBinding(scope, evidence.fileToken))
            check(plaintextDirectory.isDirectory || plaintextDirectory.mkdirs())
            plaintext = File.createTempFile("incident-upload-", ".bin", plaintextDirectory)
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            FileOutputStream(plaintext).use { output ->
                val encrypted = bytes.copyOfRange(ARTIFACT_HEADER.size + IV_BYTES, bytes.size)
                var offset = 0
                while (offset < encrypted.size) {
                    val count = minOf(BUFFER_SIZE, encrypted.size - offset)
                    val decoded = cipher.update(encrypted, offset, count)
                    if (decoded != null) {
                        total += decoded.size
                        require(total <= MAX_EVIDENCE_BYTES)
                        digest.update(decoded)
                        output.write(decoded)
                    }
                    offset += count
                }
                val tail = cipher.doFinal()
                total += tail.size
                require(total <= MAX_EVIDENCE_BYTES)
                digest.update(tail)
                output.write(tail)
            }
            require(
                total == evidence.byteSize && digest.digest().toHex() == evidence.checksumSha256
            )
            DriverProofFileCandidate(
                plaintext,
                evidence.originalFilename,
                evidence.contentType,
                evidence.byteSize,
                evidence.checksumSha256
            )
        } catch (_: Exception) {
            plaintext?.delete()
            null
        }
    }

    private fun deleteEncryptedArtifact(scope: DriverAttemptScopeIdentity, token: String): Boolean =
        try {
            val file = artifactFile(scope, token)
            AtomicFile(file).delete()
            !file.exists() && !File("${file.path}.bak").exists() &&
                !File("${file.path}.new").exists()
        } catch (_: Exception) {
            false
        }

    private fun artifactFile(scope: DriverAttemptScopeIdentity, token: String): File {
        val name = artifactBinding(scope, token).sha256().toHex()
        return File(artifactDirectory, "$name.enc")
    }

    private fun artifactBinding(scope: DriverAttemptScopeIdentity, token: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            listOf(
                "NEXA-DRIVER-INCIDENT-ARTIFACT-v1",
                scope.userId,
                scope.tenantId,
                scope.workspaceId,
                scope.membershipId,
                token
            ).forEach { value ->
                val encoded = value.toByteArray(Charsets.UTF_8)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
        }
        return bytes.toByteArray()
    }

    private fun artifactKeyForRead(): SecretKey? = try {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey(ARTIFACT_KEY_ALIAS, null) as? SecretKey
    } catch (_: Exception) {
        null
    }

    private fun artifactKeyForWrite(): SecretKey = artifactKeyForRead() ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    ARTIFACT_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }

    private fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun JsonObject.optionalObject(key: String): JsonObject? =
        when (val element = this[key]) {
            null, JsonNull -> null
            else -> element as? JsonObject ?: error("Driver incident metadata field is invalid")
        }

    private fun JsonObject.optionalString(key: String): String? = when (val element = this[key]) {
        null, JsonNull -> null

        else -> element.jsonPrimitive.takeIf(JsonPrimitive::isString)?.content
            ?: error("Driver incident metadata field is invalid")
    }

    private fun JsonObject.optionalLong(key: String): Long? = when (val element = this[key]) {
        null, JsonNull -> null

        else -> element.jsonPrimitive.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?: error("Driver incident metadata field is invalid")
    }

    private fun mutex(scope: DriverAttemptScopeIdentity): Mutex = locks.computeIfAbsent(scope) {
        Mutex()
    }

    private fun DriverAttemptScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf(String::isNotBlank) ?: error("Driver incident metadata field is invalid")

    private fun JsonObject.requiredStringAllowEmpty(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?: error("Driver incident metadata field is invalid")

    private fun JsonObject.requiredLong(key: String): Long =
        this[key]?.jsonPrimitive?.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?: error("Driver incident metadata field is invalid")

    private companion object {
        val locks = ConcurrentHashMap<DriverAttemptScopeIdentity, Mutex>()
        const val ARTIFACT_KEY_ALIAS =
            "com.nexa.mobile.operations.driver-delivery-incident-artifact.v1"
        val ARTIFACT_HEADER = "NXDI1".toByteArray(Charsets.US_ASCII)
        const val IV_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val BUFFER_SIZE = 8192
        const val MAX_EVIDENCE_BYTES = 10L * 1024L * 1024L
        const val MAX_ENCRYPTED_BYTES = MAX_EVIDENCE_BYTES + 64
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DriverIncidentMetadataModule {
    @Provides
    @Singleton
    fun provideDriverIncidentMetadataStore(
        @ApplicationContext context: Context
    ): IncidentMetadataStore = AppDriverIncidentMetadataStore(
        context,
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DriverDeliveryIncident)
    )
}
