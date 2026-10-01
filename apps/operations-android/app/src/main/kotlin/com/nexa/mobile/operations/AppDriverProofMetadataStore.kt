package com.nexa.mobile.operations

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.feature.delivery.DriverAttemptScopeIdentity
import com.nexa.mobile.operations.feature.delivery.DriverProofEvidenceKind
import com.nexa.mobile.operations.feature.delivery.DriverProofFileCandidate
import com.nexa.mobile.operations.feature.delivery.DriverProofIntentMetadata
import com.nexa.mobile.operations.feature.delivery.DriverProofIntentStage
import com.nexa.mobile.operations.feature.delivery.DriverProofIntentStatus
import com.nexa.mobile.operations.feature.delivery.DriverProofMetadataRead
import com.nexa.mobile.operations.feature.delivery.DriverProofMetadataStore
import com.nexa.mobile.operations.feature.delivery.DriverProofMetadataWrite
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
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Stores a typed retry record plus a separately encrypted, scope-bound proof artifact. */
internal class AppDriverProofMetadataStore(
    context: Context,
    private val local: AndroidScopedMetadataStore
) : DriverProofMetadataStore {
    private val appContext = context.applicationContext
    private val artifactDirectory = File(appContext.noBackupFilesDir, "driver-proof-evidence")
    private val plaintextDirectory = File(appContext.cacheDir, "driver-proof-upload")

    override suspend fun loadIntent(scope: DriverAttemptScopeIdentity): DriverProofMetadataRead =
        mutex(scope).withLock {
            when (val stored = local.load(scope.toLocal())) {
                ScopedMetadataRead.Unavailable -> DriverProofMetadataRead.Unavailable
                is ScopedMetadataRead.Value -> {
                    val payload = stored.payload
                        ?: return@withLock DriverProofMetadataRead.Available(null)
                    val intent = decode(payload)
                        ?: return@withLock DriverProofMetadataRead.Unavailable
                    if (intent.scope != scope) return@withLock DriverProofMetadataRead.Unavailable
                    val isDispatchedStage = intent.stage in setOf(
                        DriverProofIntentStage.CreatingProof,
                        DriverProofIntentStage.UploadingEvidence,
                        DriverProofIntentStage.AttachingEvidence
                    )
                    if (intent.status == DriverProofIntentStatus.Pending && isDispatchedStage) {
                        val recovered = intent.copy(status = DriverProofIntentStatus.UnknownOutcome)
                        if (local.save(scope.toLocal(), encode(recovered))) {
                            DriverProofMetadataRead.Available(recovered)
                        } else {
                            DriverProofMetadataRead.Unavailable
                        }
                    } else {
                        DriverProofMetadataRead.Available(intent)
                    }
                }
            }
        }

    override suspend fun saveIntent(intent: DriverProofIntentMetadata): DriverProofMetadataWrite =
        mutex(intent.scope).withLock { saveIntentLocked(intent) }

    override suspend fun stageCandidate(
        intent: DriverProofIntentMetadata,
        candidate: DriverProofFileCandidate
    ): DriverProofMetadataWrite = mutex(intent.scope).withLock {
        val token = intent.candidateFileToken
        if (token.isNullOrBlank() || intent.stage != DriverProofIntentStage.EvidenceReadyForReview ||
            intent.candidateByteSize != candidate.byteSize || intent.candidateChecksumSha256 != candidate.checksumSha256 ||
            intent.candidateFilename != candidate.originalFilename || intent.candidateContentType != candidate.declaredContentType
        ) return@withLock DriverProofMetadataWrite.Conflict
        val current = readCurrent(intent.scope)
        if (current !is CurrentIntent.Value) return@withLock current.toWrite()
        val persistedProof = current.intent
        if (persistedProof == null ||
            persistedProof.stage != DriverProofIntentStage.ProofCreated ||
            persistedProof.status != DriverProofIntentStatus.Pending ||
            !sameIntent(persistedProof, intent) ||
            persistedProof.proofId == null || persistedProof.proofId != intent.proofId ||
            persistedProof.proofVersion != intent.proofVersion
        ) return@withLock DriverProofMetadataWrite.Conflict
        if (!writeEncryptedArtifact(intent.scope, token, candidate)) {
            return@withLock DriverProofMetadataWrite.Unavailable
        }
        val result = saveIntentLocked(intent)
        if (result != DriverProofMetadataWrite.Saved) deleteEncryptedArtifact(intent.scope, token)
        result
    }

    override suspend fun loadCandidate(intent: DriverProofIntentMetadata): DriverProofFileCandidate? =
        mutex(intent.scope).withLock {
            val token = intent.candidateFileToken ?: return@withLock null
            val filename = intent.candidateFilename ?: return@withLock null
            val contentType = intent.candidateContentType ?: return@withLock null
            val size = intent.candidateByteSize ?: return@withLock null
            val checksum = intent.candidateChecksumSha256 ?: return@withLock null
            decryptArtifact(intent.scope, token, filename, contentType, size, checksum)
        }

    override suspend fun clearCandidate(intent: DriverProofIntentMetadata): Boolean =
        mutex(intent.scope).withLock {
            val token = intent.candidateFileToken ?: return@withLock true
            deleteEncryptedArtifact(intent.scope, token)
        }

    override suspend fun clearIntent(
        scope: DriverAttemptScopeIdentity,
        createIdempotencyKey: String
    ): DriverProofMetadataWrite = mutex(scope).withLock {
        when (val current = readCurrent(scope)) {
            CurrentIntent.Unavailable -> DriverProofMetadataWrite.Unavailable
            is CurrentIntent.Value -> {
                val intent = current.intent ?: return@withLock DriverProofMetadataWrite.Saved
                if (intent.scope != scope || intent.createIdempotencyKey != createIdempotencyKey) {
                    return@withLock DriverProofMetadataWrite.Stale
                }
                val artifactDeleted = deleteEncryptedArtifact(scope, intent.candidateFileToken)
                if (!artifactDeleted) return@withLock DriverProofMetadataWrite.Unavailable
                if (local.clear(scope.toLocal())) DriverProofMetadataWrite.Saved
                else DriverProofMetadataWrite.Unavailable
            }
        }
    }

    private suspend fun saveIntentLocked(intent: DriverProofIntentMetadata): DriverProofMetadataWrite {
        when (val current = readCurrent(intent.scope)) {
            CurrentIntent.Unavailable -> return DriverProofMetadataWrite.Unavailable
            is CurrentIntent.Value -> {
                val existing = current.intent
                if (existing != null) {
                    if (!sameIntent(existing, intent)) return DriverProofMetadataWrite.Conflict
                    if (existing.status == DriverProofIntentStatus.UnknownOutcome &&
                        intent.status == DriverProofIntentStatus.Pending &&
                        existing.stage == intent.stage
                    ) return DriverProofMetadataWrite.Conflict
                    if (existing == intent) return DriverProofMetadataWrite.Saved
                }
            }
        }
        return if (local.save(intent.scope.toLocal(), encode(intent))) {
            DriverProofMetadataWrite.Saved
        } else {
            DriverProofMetadataWrite.Unavailable
        }
    }

    private suspend fun readCurrent(scope: DriverAttemptScopeIdentity): CurrentIntent =
        when (val stored = local.load(scope.toLocal())) {
            ScopedMetadataRead.Unavailable -> CurrentIntent.Unavailable
            is ScopedMetadataRead.Value -> {
                val payload = stored.payload ?: return CurrentIntent.Value(null)
                val intent = decode(payload) ?: return CurrentIntent.Unavailable
                if (intent.scope != scope) CurrentIntent.Unavailable else CurrentIntent.Value(intent)
            }
        }

    private fun sameIntent(left: DriverProofIntentMetadata, right: DriverProofIntentMetadata): Boolean =
        left.scope == right.scope &&
            left.deliveryId == right.deliveryId && left.attemptId == right.attemptId &&
            left.createExpectedVersion == right.createExpectedVersion &&
            left.createIdempotencyKey == right.createIdempotencyKey &&
            left.createBody == right.createBody && left.receiverName == right.receiverName &&
            left.capturedAt == right.capturedAt && left.notes == right.notes &&
            left.proofId == right.proofId && left.proofVersion == right.proofVersion

    private fun CurrentIntent.toWrite(): DriverProofMetadataWrite = when (this) {
        CurrentIntent.Unavailable -> DriverProofMetadataWrite.Unavailable
        is CurrentIntent.Value -> DriverProofMetadataWrite.Conflict
    }

    private fun encode(intent: DriverProofIntentMetadata): String {
        val values = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "schema" to JsonPrimitive(1),
            "userId" to JsonPrimitive(intent.scope.userId),
            "tenantId" to JsonPrimitive(intent.scope.tenantId),
            "workspaceId" to JsonPrimitive(intent.scope.workspaceId),
            "membershipId" to JsonPrimitive(intent.scope.membershipId),
            "deliveryId" to JsonPrimitive(intent.deliveryId),
            "attemptId" to JsonPrimitive(intent.attemptId),
            "createExpectedVersion" to JsonPrimitive(intent.createExpectedVersion),
            "createIdempotencyKey" to JsonPrimitive(intent.createIdempotencyKey),
            "createBody" to JsonPrimitive(intent.createBody),
            "receiverName" to JsonPrimitive(intent.receiverName),
            "capturedAt" to JsonPrimitive(intent.capturedAt),
            "notes" to (intent.notes?.let(::JsonPrimitive) ?: JsonNull),
            "stage" to JsonPrimitive(intent.stage.name),
            "status" to JsonPrimitive(intent.status.name)
        )
        fun addString(key: String, value: String?) { values[key] = value?.let(::JsonPrimitive) ?: JsonNull }
        fun addLong(key: String, value: Long?) { values[key] = value?.let(::JsonPrimitive) ?: JsonNull }
        addString("proofId", intent.proofId)
        addLong("proofVersion", intent.proofVersion)
        addString("evidenceKind", intent.evidenceKind?.name)
        addString("evidenceId", intent.evidenceId)
        addString("evidenceUploadKey", intent.evidenceUploadKey)
        addString("candidateFileToken", intent.candidateFileToken)
        addString("candidateFilename", intent.candidateFilename)
        addString("candidateContentType", intent.candidateContentType)
        addLong("candidateByteSize", intent.candidateByteSize)
        addString("candidateChecksumSha256", intent.candidateChecksumSha256)
        addLong("attachExpectedVersion", intent.attachExpectedVersion)
        addString("attachKey", intent.attachKey)
        addString("attachBody", intent.attachBody)
        return JsonObject(values).toString()
    }

    private fun decode(payload: String): DriverProofIntentMetadata? = try {
        val root = Json.parseToJsonElement(payload).jsonObject
        require(root.requiredLong("schema") == 1L)
        val scope = DriverAttemptScopeIdentity(
            root.requiredString("userId"), root.requiredString("tenantId"),
            root.requiredString("workspaceId"), root.requiredString("membershipId")
        )
        DriverProofIntentMetadata(
            scope = scope,
            deliveryId = root.requiredString("deliveryId"),
            attemptId = root.requiredString("attemptId"),
            createExpectedVersion = root.requiredLong("createExpectedVersion"),
            createIdempotencyKey = root.requiredString("createIdempotencyKey"),
            createBody = root.requiredString("createBody"),
            receiverName = root.requiredString("receiverName"),
            capturedAt = root.requiredString("capturedAt"),
            notes = root.optionalString("notes"),
            stage = DriverProofIntentStage.valueOf(root.requiredString("stage")),
            status = DriverProofIntentStatus.valueOf(root.requiredString("status")),
            proofId = root.optionalString("proofId"),
            proofVersion = root.optionalLong("proofVersion"),
            evidenceKind = root.optionalString("evidenceKind")?.let(DriverProofEvidenceKind::valueOf),
            evidenceId = root.optionalString("evidenceId"),
            evidenceUploadKey = root.optionalString("evidenceUploadKey"),
            candidateFileToken = root.optionalString("candidateFileToken"),
            candidateFilename = root.optionalString("candidateFilename"),
            candidateContentType = root.optionalString("candidateContentType"),
            candidateByteSize = root.optionalLong("candidateByteSize"),
            candidateChecksumSha256 = root.optionalString("candidateChecksumSha256"),
            attachExpectedVersion = root.optionalLong("attachExpectedVersion"),
            attachKey = root.optionalString("attachKey"),
            attachBody = root.optionalString("attachBody")
        )
    } catch (_: Exception) {
        null
    }

    private fun writeEncryptedArtifact(
        scope: DriverAttemptScopeIdentity,
        token: String,
        candidate: DriverProofFileCandidate
    ): Boolean = try {
        require(candidate.file.isFile && candidate.file.length() == candidate.byteSize)
        require(token.matches(Regex("[0-9a-fA-F-]{36}")))
        val digest = MessageDigest.getInstance("SHA-256")
        val outputFile = artifactFile(scope, token)
        check(artifactDirectory.isDirectory || artifactDirectory.mkdirs())
        val atomic = AtomicFile(outputFile)
        val output = atomic.startWrite()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, artifactKeyForWrite())
            require(cipher.iv.size == IV_BYTES)
            cipher.updateAAD(artifactBinding(scope, token))
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
            require(total == candidate.byteSize)
            require(digest.digest().toHex() == candidate.checksumSha256)
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
        token: String,
        filename: String,
        contentType: String,
        byteSize: Long,
        checksum: String
    ): DriverProofFileCandidate? {
        var plaintext: File? = null
        return try {
            val file = artifactFile(scope, token)
            require(file.isFile && file.length() in (ARTIFACT_HEADER.size + IV_BYTES + GCM_TAG_BYTES)..MAX_ENCRYPTED_BYTES)
            val atomic = AtomicFile(file)
            val bytes = atomic.openRead().use { it.readBytes() }
            require(bytes.copyOfRange(0, ARTIFACT_HEADER.size).contentEquals(ARTIFACT_HEADER))
            val iv = bytes.copyOfRange(ARTIFACT_HEADER.size, ARTIFACT_HEADER.size + IV_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, artifactKeyForRead() ?: return null, GCMParameterSpec(128, iv))
            cipher.updateAAD(artifactBinding(scope, token))
            check(plaintextDirectory.isDirectory || plaintextDirectory.mkdirs())
            plaintext = File.createTempFile("proof-upload-", ".bin", plaintextDirectory)
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
            require(total == byteSize && digest.digest().toHex() == checksum)
            DriverProofFileCandidate(plaintext, filename, contentType, byteSize, checksum)
        } catch (_: Exception) {
            plaintext?.delete()
            null
        }
    }

    private fun deleteEncryptedArtifact(scope: DriverAttemptScopeIdentity, token: String?): Boolean {
        if (token == null) return true
        return try {
            val file = artifactFile(scope, token)
            AtomicFile(file).delete()
            !file.exists() && !File("${file.path}.bak").exists() && !File("${file.path}.new").exists()
        } catch (_: Exception) {
            false
        }
    }

    private fun artifactFile(scope: DriverAttemptScopeIdentity, token: String): File {
        require(token.matches(Regex("[0-9a-fA-F-]{36}")))
        val name = MessageDigest.getInstance("SHA-256")
            .digest(artifactBinding(scope, token)).toHex()
        return File(artifactDirectory, "$name.enc")
    }

    private fun artifactBinding(scope: DriverAttemptScopeIdentity, token: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            listOf(
                "NEXA-DRIVER-PROOF-ARTIFACT-v1", scope.userId, scope.tenantId,
                scope.workspaceId, scope.membershipId, token
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
            .getKey(KEY_ALIAS, null) as? SecretKey
    } catch (_: Exception) {
        null
    }

    private fun artifactKeyForWrite(): SecretKey = artifactKeyForRead() ?:
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }

    private fun mutex(scope: DriverAttemptScopeIdentity): Mutex = locks.computeIfAbsent(scope) { Mutex() }

    private fun DriverAttemptScopeIdentity.toLocal() =
        ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf(String::isNotBlank) ?: error("Driver proof metadata is invalid")

    private fun JsonObject.optionalString(key: String): String? = when (val value = this[key]) {
        null, JsonNull -> null
        else -> value.jsonPrimitive.takeIf(JsonPrimitive::isString)?.content
            ?: error("Driver proof metadata is invalid")
    }

    private fun JsonObject.requiredLong(key: String): Long =
        this[key]?.jsonPrimitive?.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?: error("Driver proof metadata is invalid")

    private fun JsonObject.optionalLong(key: String): Long? = when (val value = this[key]) {
        null, JsonNull -> null
        else -> value.jsonPrimitive.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?: error("Driver proof metadata is invalid")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private sealed interface CurrentIntent {
        data class Value(val intent: DriverProofIntentMetadata?) : CurrentIntent
        data object Unavailable : CurrentIntent
    }

    private companion object {
        const val KEY_ALIAS = "com.nexa.mobile.operations.driver-proof-artifact.v1"
        const val BUFFER_SIZE = 8192
        const val IV_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val MAX_EVIDENCE_BYTES = 10L * 1024L * 1024L
        const val MAX_ENCRYPTED_BYTES = MAX_EVIDENCE_BYTES + 64L
        val ARTIFACT_HEADER = "NXDP1".toByteArray(Charsets.US_ASCII)
        val locks = ConcurrentHashMap<DriverAttemptScopeIdentity, Mutex>()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DriverProofMetadataModule {
    @Provides
    @Singleton
    fun provideDriverProofMetadataStore(
        @ApplicationContext context: Context
    ): DriverProofMetadataStore = AppDriverProofMetadataStore(
        context,
        AndroidScopedMetadataStore(context, ScopedMetadataPurpose.DriverProofEvidence)
    )
}
