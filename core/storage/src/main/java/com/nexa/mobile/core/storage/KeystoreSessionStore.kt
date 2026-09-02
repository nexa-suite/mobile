package com.nexa.mobile.core.storage

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores only encrypted session material in private app preferences.
 *
 * Passwords are intentionally not part of [SessionMaterial]. The AES/GCM key
 * is generated and retained by Android Keystore. A corrupt or invalidated
 * value is treated as absent and removed; no token or raw payload is logged.
 */
class KeystoreSessionStore(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override suspend fun read(): SessionMaterial? = withContext(Dispatchers.IO) {
        val encoded = preferences.getString(SESSION_KEY, null) ?: return@withContext null
        runCatching {
            val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
            SessionCipher(key()).decrypt(encrypted)
        }.getOrElse {
            clearSynchronously()
            null
        }
    }

    @SuppressLint("ApplySharedPref")
    override suspend fun write(material: SessionMaterial) = withContext(Dispatchers.IO) {
        val encrypted = SessionCipher(key()).encrypt(material)
        check(preferences.edit().putString(SESSION_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "Session material could not be persisted"
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        clearSynchronously()
    }

    @SuppressLint("ApplySharedPref")
    private fun clearSynchronously() {
        check(preferences.edit().remove(SESSION_KEY).commit()) {
            "Session material could not be cleared"
        }
    }

    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey) ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "nexa.mobile.session.v1"
        const val PREFERENCES_NAME = "nexa_mobile_secure_session"
        const val SESSION_KEY = "encrypted_session_material"
        val KEY_LOCK = Any()
    }
}

internal class SessionCipher(private val secretKey: SecretKey) {
    fun encrypt(material: SessionMaterial): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val ciphertext = cipher.doFinal(SessionMaterialCodec.encode(material))
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ciphertext
    }

    fun decrypt(payload: ByteArray): SessionMaterial {
        require(payload.isNotEmpty()) { "Encrypted session payload is empty" }
        val ivLength = payload[0].toInt()
        require(ivLength in 12..16 && payload.size > ivLength + 1) { "Encrypted session payload is invalid" }
        val iv = payload.copyOfRange(1, ivLength + 1)
        val ciphertext = payload.copyOfRange(ivLength + 1, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return SessionMaterialCodec.decode(cipher.doFinal(ciphertext))
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

private object SessionMaterialCodec {
    fun encode(material: SessionMaterial): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeUTF(material.accessToken)
            output.writeUTF(material.refreshToken)
            output.writeLong(material.accessTokenExpiresAtEpochSeconds)
            output.writeUTF(material.surface)
        }
        bytes.toByteArray()
    }

    fun decode(payload: ByteArray): SessionMaterial = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        SessionMaterial(
            accessToken = input.readUTF(),
            refreshToken = input.readUTF(),
            accessTokenExpiresAtEpochSeconds = input.readLong(),
            surface = input.readUTF(),
        )
    }
}
