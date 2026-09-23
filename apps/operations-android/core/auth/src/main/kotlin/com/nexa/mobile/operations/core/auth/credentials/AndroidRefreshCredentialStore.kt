package com.nexa.mobile.operations.core.auth.credentials

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidRefreshCredentialStore(
    context: Context,
    fileName: String = "native-session.record",
    private val keyAlias: String = "com.nexa.mobile.operations.native-session.v1",
) : RefreshCredentialStore {
    private val recordFile = File(context.applicationContext.noBackupFilesDir, fileName)
    private val atomicFile = AtomicFile(recordFile)
    private val mutex = Mutex()

    override suspend fun read(): StoredRefreshCredential = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked() }
    }

    override suspend fun takeForRefresh(): StoredRefreshCredential = withContext(Dispatchers.IO) {
        mutex.withLock {
            when (val current = readLocked()) {
                is StoredRefreshCredential.Ready -> {
                    // A committed IN_FLIGHT record contains no R1. A lost response cannot cause R1 reuse.
                    writeLocked(encodeState(STATE_IN_FLIGHT))
                    current
                }
                else -> current
            }
        }
    }

    override suspend fun writeReady(credential: String) = withContext(Dispatchers.IO) {
        require(credential.isNotBlank() && '\r' !in credential && '\n' !in credential)
        mutex.withLock { writeLocked(encodeReady(credential)) }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            atomicFile.delete()
            check(!recordFile.exists() && !File("${recordFile.path}.bak").exists()) {
                "Protected credential record could not be cleared"
            }
        }
    }

    private fun readLocked(): StoredRefreshCredential {
        val bytes = try {
            atomicFile.openRead().use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            return StoredRefreshCredential.Missing
        } catch (_: Exception) {
            atomicFile.delete()
            return StoredRefreshCredential.Unusable
        }
        return try {
            decodeState(decrypt(bytes))
        } catch (_: Exception) {
            // Missing keys, bad tags, torn data and unknown schemas all fail closed.
            atomicFile.delete()
            StoredRefreshCredential.Unusable
        }
    }

    private fun writeLocked(plaintext: ByteArray) {
        val encrypted = encrypt(plaintext)
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(encrypted)
            atomicFile.finishWrite(stream)
        } catch (failure: Exception) {
            stream?.let(atomicFile::failWrite)
            throw failure
        }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyForWrite())
        cipher.updateAAD(HEADER)
        val iv = cipher.iv
        check(iv.size == IV_LENGTH)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(HEADER.size + 1 + iv.size + 4 + ciphertext.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(HEADER)
            .put(iv.size.toByte())
            .put(iv)
            .putInt(ciphertext.size)
            .put(ciphertext)
            .array()
    }

    private fun decrypt(record: ByteArray): ByteArray {
        require(record.size >= HEADER.size + 1 + IV_LENGTH + 4 + 16)
        val buffer = ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN)
        val header = ByteArray(HEADER.size).also(buffer::get)
        require(header.contentEquals(HEADER))
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength == IV_LENGTH)
        val iv = ByteArray(ivLength).also(buffer::get)
        val ciphertextLength = buffer.int
        require(ciphertextLength >= 16 && ciphertextLength == buffer.remaining())
        val ciphertext = ByteArray(ciphertextLength).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyForRead(), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return cipher.doFinal(ciphertext)
    }

    private fun keyForRead(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(keyAlias, null) as? SecretKey
            ?: error("Protected credential key is unavailable")
    }

    private fun keyForWrite(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encodeReady(credential: String): ByteArray {
        val bytes = credential.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + 4 + bytes.size).order(ByteOrder.BIG_ENDIAN)
            .put(STATE_READY)
            .putInt(bytes.size)
            .put(bytes)
            .array()
    }

    private fun encodeState(state: Byte): ByteArray = byteArrayOf(state)

    private fun decodeState(plaintext: ByteArray): StoredRefreshCredential {
        require(plaintext.isNotEmpty())
        if (plaintext[0] == STATE_IN_FLIGHT) {
            require(plaintext.size == 1)
            return StoredRefreshCredential.InFlight
        }
        require(plaintext[0] == STATE_READY && plaintext.size >= 6)
        val buffer = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN)
        buffer.get()
        val length = buffer.int
        require(length > 0 && length == buffer.remaining())
        val bytes = ByteArray(length).also(buffer::get)
        val credential = String(bytes, Charsets.UTF_8)
        require(credential.isNotBlank() && '\r' !in credential && '\n' !in credential)
        return StoredRefreshCredential.Ready(credential)
    }

    private companion object {
        private val HEADER = byteArrayOf('N'.code.toByte(), 'X'.code.toByte(), 'S'.code.toByte(), 'R'.code.toByte(), 1)
        private const val IV_LENGTH = 12
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val STATE_READY: Byte = 1
        private const val STATE_IN_FLIGHT: Byte = 2
    }
}
