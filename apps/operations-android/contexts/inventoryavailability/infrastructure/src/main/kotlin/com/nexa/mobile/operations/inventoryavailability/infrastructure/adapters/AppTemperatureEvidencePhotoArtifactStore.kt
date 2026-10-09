package com.nexa.mobile.operations.inventoryavailability.infrastructure.adapters

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidencePhotoCandidate
import com.nexa.mobile.operations.inventoryavailability.application.model.warehouse.TemperatureEvidencePhotoSelection
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encrypts selected thermal media before staging and exposes plaintext only for one upload. */
class AppTemperatureEvidencePhotoArtifactStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.noBackupFilesDir, "temperature-evidence-photo")
    private val uploadRoot = File(appContext.noBackupFilesDir, "temperature-evidence-upload")
    private val uploadMutex = Mutex()

    suspend fun <T> withEncryptedCandidate(
        selection: TemperatureEvidencePhotoSelection,
        candidate: TemperatureEvidencePhotoCandidate,
        block: suspend (TemperatureEvidencePhotoCandidate) -> T
    ): T? = withContext(Dispatchers.IO) {
        uploadMutex.withLock {
            discardAbandonedArtifacts()
            val stagedCandidate = candidate.file.takeIf(::isOwnedStagedCandidate)
            var plaintext: ByteArray? = null
            var encrypted: ByteArray? = null
            var persisted: ByteArray? = null
            var restored: ByteArray? = null
            var uploadFile: File? = null
            var artifact: AtomicFile? = null
            try {
                if (stagedCandidate == null || !stagedCandidate.isFile ||
                    stagedCandidate.length() != candidate.byteSize ||
                    candidate.byteSize !in 1..MAX_BYTES
                ) {
                    return@withLock null
                }
                val sourceBytes = stagedCandidate.readBounded()
                plaintext = sourceBytes
                if (sourceBytes.size.toLong() != candidate.byteSize ||
                    sha256(sourceBytes) != candidate.checksumSha256 ||
                    !matchesMagic(sourceBytes, candidate.declaredContentType)
                ) {
                    return@withLock null
                }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, keyForWrite())
                cipher.updateAAD(selection.binding())
                val encryptedBytes = ENVELOPE + cipher.iv + cipher.doFinal(sourceBytes)
                encrypted = encryptedBytes
                check(root.isDirectory || root.mkdirs())
                val atomic = AtomicFile(File(root, "${selection.fileKey()}.artifact"))
                artifact = atomic
                val output = atomic.startWrite()
                try {
                    output.write(encryptedBytes)
                    output.fd.sync()
                    atomic.finishWrite(output)
                } catch (failure: Exception) {
                    atomic.failWrite(output)
                    throw failure
                }

                val persistedBytes = atomic.openRead().use { it.readBounded(MAX_ENCRYPTED_BYTES) }
                persisted = persistedBytes
                val headerSize = ENVELOPE.size + IV_LENGTH
                require(
                    persistedBytes.size > headerSize && persistedBytes
                        .copyOfRange(0, ENVELOPE.size).contentEquals(ENVELOPE)
                )
                val iv = persistedBytes.copyOfRange(ENVELOPE.size, headerSize)
                val decrypt = Cipher.getInstance(TRANSFORMATION)
                decrypt.init(
                    Cipher.DECRYPT_MODE,
                    keyForRead() ?: error("Temperature evidence photo key is unavailable"),
                    GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
                )
                decrypt.updateAAD(selection.binding())
                val restoredBytes = decrypt.doFinal(
                    persistedBytes.copyOfRange(headerSize, persistedBytes.size)
                )
                restored = restoredBytes
                require(restoredBytes.size.toLong() == candidate.byteSize)
                check(uploadRoot.isDirectory || uploadRoot.mkdirs())
                val temporaryUpload =
                    File(uploadRoot, "${UUID.randomUUID()}.${candidate.extension()}")
                uploadFile = temporaryUpload
                FileOutputStream(temporaryUpload).use { stream ->
                    stream.write(restoredBytes)
                    stream.fd.sync()
                }
                block(candidate.copy(file = temporaryUpload))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                stagedCandidate?.delete()
                plaintext?.fill(0)
                encrypted?.fill(0)
                persisted?.fill(0)
                restored?.fill(0)
                uploadFile?.delete()
                artifact?.baseFile?.delete()
                artifact?.let { atomic ->
                    File("${atomic.baseFile.path}.bak").delete()
                    File("${atomic.baseFile.path}.new").delete()
                }
            }
        }
    }

    fun discardAbandonedArtifacts() {
        listOf(root, uploadRoot).forEach { directory ->
            directory.listFiles()?.forEach(File::delete)
        }
    }

    fun discardStagedCandidate(candidate: TemperatureEvidencePhotoCandidate) {
        candidate.file.takeIf(::isOwnedStagedCandidate)?.delete()
    }

    private fun isOwnedStagedCandidate(file: File): Boolean = try {
        val stagedRoot = File(appContext.noBackupFilesDir, "private-image-selection")
            .canonicalFile
        val selected = file.canonicalFile
        selected.isFile && selected != stagedRoot &&
            selected.toPath().startsWith(stagedRoot.toPath())
    } catch (_: Exception) {
        false
    }

    private fun TemperatureEvidencePhotoSelection.binding(): ByteArray {
        val values = listOf(
            scope.userId,
            scope.tenantId,
            scope.workspaceId,
            scope.membershipId,
            authorityEpoch.toString(),
            subjectType.name,
            subjectId,
            warehouseId,
            expectedLotVersion?.toString().orEmpty()
        )
        val bytes = values.map { it.toByteArray(Charsets.UTF_8) }
        val output = ByteBuffer.allocate(bytes.sumOf { 4 + it.size }).order(ByteOrder.BIG_ENDIAN)
        bytes.forEach { output.putInt(it.size).put(it) }
        return output.array()
    }

    private fun TemperatureEvidencePhotoSelection.fileKey(): String = sha256(binding())

    private fun TemperatureEvidencePhotoCandidate.extension(): String = when (declaredContentType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        else -> "webp"
    }

    private fun File.readBounded(maximumBytes: Int = MAX_BYTES.toInt()): ByteArray =
        inputStream().use { it.readBounded(maximumBytes) }

    private fun InputStream.readBounded(maximumBytes: Int = MAX_BYTES.toInt()): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(result.size() + count <= maximumBytes)
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun matchesMagic(bytes: ByteArray, contentType: String): Boolean = when (contentType) {
        "image/jpeg" -> bytes.size >= 3 && bytes[0] == 0xff.toByte() &&
            bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()

        "image/png" ->
            bytes.size >= PNG_SIGNATURE.size &&
                bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)

        "image/webp" ->
            bytes.size >= 12 &&
                String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"

        else -> false
    }

    private fun keyForRead(): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun keyForWrite(): SecretKey = synchronized(KEY_LOCK) {
        keyForRead()?.let { return@synchronized it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "com.nexa.mobile.operations.temperature-evidence-photo.v1"
        const val IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val MAX_BYTES = 10L * 1024 * 1024
        const val MAX_ENCRYPTED_BYTES = MAX_BYTES.toInt() + 64
        val ENVELOPE =
            byteArrayOf(
                'N'.code.toByte(),
                'X'.code.toByte(),
                'T'.code.toByte(),
                'P'.code.toByte(),
                1
            )
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a
        )
        val KEY_LOCK = Any()
    }
}
