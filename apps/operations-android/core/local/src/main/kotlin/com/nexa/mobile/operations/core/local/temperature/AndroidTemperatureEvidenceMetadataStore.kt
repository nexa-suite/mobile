package com.nexa.mobile.operations.core.local.temperature

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers

class AndroidTemperatureEvidenceMetadataStore internal constructor(
    context: Context,
    directoryName: String = DEFAULT_DIRECTORY,
    keyAlias: String = DEFAULT_KEY_ALIAS
) : TemperatureEvidenceMetadataStore by TemperatureEvidenceMetadataStoreCore(
    storage = AtomicTemperatureRecordStorage(
        File(context.applicationContext.noBackupFilesDir, directoryName)
    ),
    cipher = AndroidKeystoreTemperatureRecordCipher(keyAlias),
    ioDispatcher = Dispatchers.IO
) {
    constructor(context: Context) : this(context, DEFAULT_DIRECTORY, DEFAULT_KEY_ALIAS)

    private companion object {
        const val DEFAULT_DIRECTORY = "temperature-evidence-metadata"
        const val DEFAULT_KEY_ALIAS = "com.nexa.mobile.operations.temperature-evidence-metadata.v1"
    }
}

private class AtomicTemperatureRecordStorage(private val directory: File) :
    TemperatureRecordStorage {
    override val lockNamespace: String = directory.absoluteFile.normalize().path

    override fun read(fileKey: String): ByteArray? {
        val file = recordFile(fileKey)
        return try {
            AtomicFile(file).openRead().use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(4 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= MAX_ENCRYPTED_RECORD_BYTES)
                    bytes.write(buffer, 0, count)
                }
                bytes.toByteArray()
            }
        } catch (failure: FileNotFoundException) {
            if (directory.exists() &&
                (!directory.canRead() || recordArtifacts(file).any(File::exists))
            ) {
                throw failure
            }
            null
        }
    }

    override fun write(fileKey: String, encryptedRecord: ByteArray) {
        check(directory.isDirectory || directory.mkdirs())
        val atomic = AtomicFile(recordFile(fileKey))
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(encryptedRecord)
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            output?.let(atomic::failWrite)
            throw failure
        }
    }

    private fun recordFile(fileKey: String): File {
        require(fileKey.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$fileKey.record")
    }

    private fun recordArtifacts(file: File): List<File> =
        listOf(file, File("${file.path}.bak"), File("${file.path}.new"))

    private companion object {
        const val MAX_ENCRYPTED_RECORD_BYTES = 5 + 1 + 12 + 4 + 16 + 16 * 1024
    }
}

private class AndroidKeystoreTemperatureRecordCipher(private val keyAlias: String) :
    TemperatureRecordCipher {
    override fun encrypt(scope: TemperatureMetadataScope, plaintext: ByteArray): ByteArray {
        require(plaintext.size <= MAX_RECORD_BYTES)
        val header = envelopeHeader()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyForWrite())
        cipher.updateAAD(header + TemperatureMetadataScopeBinding.additionalData(scope))
        val iv = cipher.iv
        check(iv.size == IV_LENGTH)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(header.size + 1 + iv.size + 4 + ciphertext.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(header)
            .put(iv.size.toByte())
            .put(iv)
            .putInt(ciphertext.size)
            .put(ciphertext)
            .array()
    }

    override fun decrypt(scope: TemperatureMetadataScope, encryptedRecord: ByteArray): ByteArray {
        require(encryptedRecord.size in MIN_RECORD_BYTES..MAX_ENCRYPTED_RECORD_BYTES)
        val header = envelopeHeader()
        val input = ByteBuffer.wrap(encryptedRecord).order(ByteOrder.BIG_ENDIAN)
        val storedHeader = ByteArray(header.size).also(input::get)
        require(storedHeader.contentEquals(header))
        require(input.get().toInt() and 0xff == IV_LENGTH)
        val iv = ByteArray(IV_LENGTH).also(input::get)
        val ciphertextSize = input.int
        require(ciphertextSize >= GCM_TAG_LENGTH_BYTES && ciphertextSize == input.remaining())
        val ciphertext = ByteArray(ciphertextSize).also(input::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyForRead(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(header + TemperatureMetadataScopeBinding.additionalData(scope))
        return cipher.doFinal(ciphertext).also { require(it.size <= MAX_RECORD_BYTES) }
    }

    private fun keyForRead(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return store.getKey(keyAlias, null) as? SecretKey
            ?: error("Temperature evidence metadata key is unavailable")
    }

    private fun keyForWrite(): SecretKey = synchronized(KEY_CREATION_LOCK) {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return@synchronized it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKey()
    }

    private fun envelopeHeader(): ByteArray = byteArrayOf(
        'N'.code.toByte(),
        'X'.code.toByte(),
        'T'.code.toByte(),
        'E'.code.toByte(),
        SCHEMA_VERSION.toByte()
    )

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val SCHEMA_VERSION = 1
        const val IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
        const val MAX_RECORD_BYTES = 16 * 1024
        const val MIN_RECORD_BYTES = 5 + 1 + IV_LENGTH + 4 + GCM_TAG_LENGTH_BYTES
        const val MAX_ENCRYPTED_RECORD_BYTES = MIN_RECORD_BYTES + MAX_RECORD_BYTES
        val KEY_CREATION_LOCK = Any()
    }
}
