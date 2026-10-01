package com.nexa.mobile.operations

import android.content.Context
import android.util.AtomicFile
import com.nexa.mobile.operations.core.local.scoped.AndroidScopedMetadataStore
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataPurpose
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataRead
import com.nexa.mobile.operations.core.local.scoped.ScopedMetadataScope
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyArtifactIdentity
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyArtifactRead
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyArtifactWrite
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyEvidenceArtifact
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyEvidenceArtifactStore
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyEvidenceCandidate
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyScope
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancySelectionContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Encrypts image bytes before disk staging; artifact key binds identity, warehouse and case. */
internal class AppInboundDiscrepancyEvidenceArtifactStore(context: Context) : InboundDiscrepancyEvidenceArtifactStore {
    private val appContext = context.applicationContext
    private val root = File(appContext.noBackupFilesDir, "inbound-discrepancy-evidence")
    private val metadata = AndroidScopedMetadataStore(appContext, ScopedMetadataPurpose.InboundDiscrepancyEvidence)
    private val uploadRoot = File(appContext.noBackupFilesDir, "inbound-discrepancy-upload")

    override suspend fun stageReturnedSelection(
        selection: InboundDiscrepancySelectionContext,
        candidate: InboundDiscrepancyEvidenceCandidate
    ): InboundDiscrepancyArtifactWrite = withIdentityLock(selection.scope, selection.warehouseId, selection.caseId) {
        withContext(Dispatchers.IO) {
            var plain: ByteArray? = null
            var savedFile: AtomicFile? = null
            try {
                if (!candidate.isValidFile()) return@withContext InboundDiscrepancyArtifactWrite.Unavailable
                val identity = InboundDiscrepancyArtifactIdentity(selection.scope, selection.warehouseId, selection.caseId)
                val old = readMetadata(identity.scope)
                if (old === MetadataRead.Unavailable) return@withContext InboundDiscrepancyArtifactWrite.Unavailable
                val previous = (old as MetadataRead.Value).metadata
                if (previous != null && (previous.warehouseId != identity.warehouseId || previous.caseId != identity.caseId)) {
                    return@withContext InboundDiscrepancyArtifactWrite.Conflict
                }
                plain = readBounded(candidate.file)
                if (plain.size.toLong() != candidate.byteSize || sha256(plain) != candidate.checksumSha256 ||
                    !matchesMagic(plain, candidate.declaredContentType)
                ) return@withContext InboundDiscrepancyArtifactWrite.Unavailable
                val artifactId = UUID.randomUUID().toString()
                val encrypted = encrypt(identity, artifactId, plain)
                val file = artifactFile(identity, artifactId)
                savedFile = file
                check(root.isDirectory || root.mkdirs())
                val output = file.startWrite()
                try {
                    output.write(encrypted)
                    output.fd.sync()
                    file.finishWrite(output)
                } catch (failure: Exception) {
                    file.failWrite(output)
                    throw failure
                } finally {
                    encrypted.fill(0)
                }
                val savedMetadata = ArtifactMetadata(
                    schemaVersion = SCHEMA_VERSION,
                    warehouseId = identity.warehouseId,
                    caseId = identity.caseId,
                    artifactId = artifactId,
                    filename = safeFilename(candidate.originalFilename),
                    contentType = candidate.declaredContentType,
                    byteSize = candidate.byteSize,
                    checksumSha256 = candidate.checksumSha256
                )
                val encoded = artifactJson.encodeToString(savedMetadata)
                if (!metadata.save(identity.scope.toLocal(), encoded)) {
                    file.delete()
                    return@withContext InboundDiscrepancyArtifactWrite.Unavailable
                }
                previous?.let { artifactFile(identity, it.artifactId).delete() }
                InboundDiscrepancyArtifactWrite.Saved
            } catch (cancelled: CancellationException) {
                savedFile?.delete()
                throw cancelled
            } catch (_: Exception) {
                savedFile?.delete()
                InboundDiscrepancyArtifactWrite.Unavailable
            } finally {
                plain?.fill(0)
            }
        }
    }

    override suspend fun load(identity: InboundDiscrepancyArtifactIdentity): InboundDiscrepancyArtifactRead =
        withIdentityLock(identity.scope, identity.warehouseId, identity.caseId) {
            withContext(Dispatchers.IO) {
                try {
                    when (val result = readMetadata(identity.scope)) {
                        MetadataRead.Unavailable -> InboundDiscrepancyArtifactRead.Unavailable
                        is MetadataRead.Value -> {
                            val stored = result.metadata ?: return@withContext InboundDiscrepancyArtifactRead.Available(null)
                            if (!stored.matches(identity)) return@withContext InboundDiscrepancyArtifactRead.Available(null)
                            val encrypted = artifactFile(identity, stored.artifactId).openRead().use { input -> readBounded(input) }
                            val plain = decrypt(identity, stored.artifactId, encrypted)
                            try {
                                if (plain.size.toLong() != stored.byteSize || sha256(plain) != stored.checksumSha256 ||
                                    !matchesMagic(plain, stored.contentType)
                                ) return@withContext InboundDiscrepancyArtifactRead.Unavailable
                            } finally {
                                encrypted.fill(0)
                                plain.fill(0)
                            }
                            InboundDiscrepancyArtifactRead.Available(stored.toArtifact())
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { InboundDiscrepancyArtifactRead.Unavailable }
            }
        }

    override suspend fun openForUpload(identity: InboundDiscrepancyArtifactIdentity): InboundDiscrepancyEvidenceCandidate? =
        withIdentityLock(identity.scope, identity.warehouseId, identity.caseId) {
            withContext(Dispatchers.IO) {
                var plain: ByteArray? = null
                try {
                    val stored = (readMetadata(identity.scope) as? MetadataRead.Value)?.metadata ?: return@withContext null
                    if (!stored.matches(identity)) return@withContext null
                    val encrypted = artifactFile(identity, stored.artifactId).openRead().use { input -> readBounded(input) }
                    plain = decrypt(identity, stored.artifactId, encrypted)
                    encrypted.fill(0)
                    if (plain!!.size.toLong() != stored.byteSize || sha256(plain!!) != stored.checksumSha256 ||
                        !matchesMagic(plain!!, stored.contentType)
                    ) return@withContext null
                    check(uploadRoot.isDirectory || uploadRoot.mkdirs())
                    val file = File.createTempFile("inbound-evidence-", ".upload", uploadRoot)
                    FileOutputStream(file).use { output ->
                        output.write(plain!!)
                        output.fd.sync()
                    }
                    InboundDiscrepancyEvidenceCandidate(
                        file, stored.filename, stored.contentType, stored.byteSize, stored.checksumSha256
                    )
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                finally { plain?.fill(0) }
            }
        }

    override fun releaseUploadCandidate(candidate: InboundDiscrepancyEvidenceCandidate) {
        runCatching { candidate.file.delete() }
    }

    override suspend fun clear(identity: InboundDiscrepancyArtifactIdentity): Boolean =
        withIdentityLock(identity.scope, identity.warehouseId, identity.caseId) {
            withContext(Dispatchers.IO) {
                try {
                    val current = (readMetadata(identity.scope) as? MetadataRead.Value)?.metadata
                    if (current == null) true
                    else if (!current.matches(identity)) false
                    else if (!metadata.clear(identity.scope.toLocal())) false
                    else {
                        val file = artifactFile(identity, current.artifactId)
                        file.delete()
                        !file.baseFile.exists()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            }
        }

    private suspend fun readMetadata(scope: InboundDiscrepancyScope): MetadataRead = when (val read = metadata.load(scope.toLocal())) {
        ScopedMetadataRead.Unavailable -> MetadataRead.Unavailable
        is ScopedMetadataRead.Value -> read.payload?.let { payload -> try {
            val stored = artifactJson.decodeFromString<ArtifactMetadata>(payload)
            if (stored.schemaVersion != SCHEMA_VERSION || !UUID_PATTERN.matches(stored.artifactId) ||
                stored.warehouseId.isBlank() || stored.caseId.isBlank() || stored.filename.isBlank() ||
                stored.filename.length > 255 || stored.contentType !in ALLOWED_TYPES ||
                stored.byteSize !in 1..MAX_BYTES || !HASH_PATTERN.matches(stored.checksumSha256)
            ) MetadataRead.Unavailable else MetadataRead.Value(stored)
        } catch (_: SerializationException) { MetadataRead.Unavailable }
        catch (_: IllegalArgumentException) { MetadataRead.Unavailable }
        } ?: MetadataRead.Value(null)
    }

    private fun encrypt(identity: InboundDiscrepancyArtifactIdentity, artifactId: String, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyForWrite())
        require(cipher.iv.size == IV_SIZE)
        cipher.updateAAD(binding(identity, artifactId))
        return HEADER + cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(identity: InboundDiscrepancyArtifactIdentity, artifactId: String, encrypted: ByteArray): ByteArray {
        require(encrypted.size.toLong() in (HEADER.size + IV_SIZE + TAG_SIZE).toLong()..MAX_ENCRYPTED_BYTES)
        require(encrypted.copyOfRange(0, HEADER.size).contentEquals(HEADER))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyForRead() ?: error("Evidence key unavailable"),
            GCMParameterSpec(128, encrypted.copyOfRange(HEADER.size, HEADER.size + IV_SIZE)))
        cipher.updateAAD(binding(identity, artifactId))
        return cipher.doFinal(encrypted.copyOfRange(HEADER.size + IV_SIZE, encrypted.size))
    }

    private fun keyForRead(): SecretKey? = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        .getKey(KEY_ALIAS, null) as? SecretKey

    private fun keyForWrite(): SecretKey = keyForRead() ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
        init(android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes("GCM").setEncryptionPaddings("NoPadding").setRandomizedEncryptionRequired(true).build())
        generateKey()
    }

    private fun binding(identity: InboundDiscrepancyArtifactIdentity, artifactId: String): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { writer ->
            writer.write(HEADER)
            listOf(identity.scope.userId, identity.scope.tenantId, identity.scope.workspaceId,
                identity.scope.membershipId, identity.warehouseId, identity.caseId, artifactId).forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                writer.writeInt(bytes.size)
                writer.write(bytes)
            }
        }
        return output.toByteArray()
    }

    private fun artifactFile(identity: InboundDiscrepancyArtifactIdentity, artifactId: String): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(binding(identity, artifactId))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 255) }
        return AtomicFile(File(root, "$name.bin"))
    }

    private fun readBounded(file: File): ByteArray {
        require(file.isFile && file.length() in 1..MAX_ENCRYPTED_BYTES)
        return FileInputStream(file).use { input -> readBounded(input, file.length()) }
    }

    private fun readBounded(input: InputStream, initialSize: Long = 0): ByteArray {
        val output = ByteArrayOutputStream(initialSize.toInt().coerceAtLeast(0))
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_ENCRYPTED_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun InboundDiscrepancyEvidenceCandidate.isValidFile(): Boolean =
        file.isFile && file.length() == byteSize && byteSize in 1..MAX_BYTES &&
            declaredContentType in ALLOWED_TYPES && HASH_PATTERN.matches(checksumSha256) &&
            safeFilename(originalFilename).isNotBlank()

    private fun safeFilename(value: String): String {
        val basename = value.substringAfterLast('/').substringAfterLast('\\').trim()
        return basename.take(255).filterNot(Char::isISOControl)
    }

    private fun matchesMagic(bytes: ByteArray, type: String): Boolean = when (type) {
        "image/jpeg" -> bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        "image/png" -> bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_MAGIC)
        "image/webp" -> bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
        else -> false
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun ArtifactMetadata.matches(identity: InboundDiscrepancyArtifactIdentity): Boolean =
        warehouseId == identity.warehouseId && caseId == identity.caseId

    private fun ArtifactMetadata.toArtifact() = InboundDiscrepancyEvidenceArtifact(
        filename, contentType, byteSize, checksumSha256
    )

    private fun InboundDiscrepancyScope.toLocal() = ScopedMetadataScope(userId, tenantId, workspaceId, membershipId)

    private suspend fun <T> withIdentityLock(scope: InboundDiscrepancyScope, warehouseId: String, caseId: String, action: suspend () -> T): T {
        val key = listOf(scope.userId, scope.tenantId, scope.workspaceId, scope.membershipId, warehouseId, caseId).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return locks.getOrPut(digest) { Mutex() }.withLock { action() }
    }

    private sealed interface MetadataRead {
        data class Value(val metadata: ArtifactMetadata?) : MetadataRead
        data object Unavailable : MetadataRead
    }

    @Serializable
    private data class ArtifactMetadata(
        val schemaVersion: Int,
        val warehouseId: String,
        val caseId: String,
        val artifactId: String,
        val filename: String,
        val contentType: String,
        val byteSize: Long,
        val checksumSha256: String
    )

    private companion object {
        const val SCHEMA_VERSION = 1
        const val KEY_ALIAS = "com.nexa.mobile.operations.inbound-discrepancy-evidence.v1"
        const val MAX_BYTES = 10L * 1024L * 1024L
        const val IV_SIZE = 12
        const val TAG_SIZE = 16
        const val MAX_ENCRYPTED_BYTES = MAX_BYTES + 64
        val HEADER = "NEXA050E".toByteArray(Charsets.US_ASCII)
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val ALLOWED_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        val UUID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val HASH_PATTERN = Regex("[0-9a-f]{64}")
        val artifactJson = Json { ignoreUnknownKeys = false }
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}
