package com.nexa.mobile.operations.core.auth.biometric

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Verified server identity and selected context used only to scope this local device key. */
data class BiometricSessionScope(
    val userId: String,
    val tenantId: String,
    val workspaceId: String,
    val membershipId: String
) {
    init {
        require(
            listOf(userId, tenantId, workspaceId, membershipId).all(String::isNotBlank)
        )
    }

    override fun toString(): String = "BiometricSessionScope(REDACTED)"
}

enum class BiometricKeyUse { Enrollment, Unlock }

/** Android Keystore gate for local biometric proof; it does not issue or refresh an API session. */
class BiometricSessionKeyStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun isEnabled(scope: BiometricSessionScope): Boolean =
        runCatching { preferences.getBoolean(enabledPreference(scope), false) }
            .getOrDefault(false)

    /** True only when opt-in and its Android Keystore key are both present. */
    fun isConfigured(scope: BiometricSessionScope): Boolean {
        if (!isEnabled(scope)) return false
        return try {
            loadKeyStore().containsAlias(keyAlias(scope))
        } catch (_: Exception) {
            false
        }
    }

    /** Returns an AES-GCM cipher that must be passed to the platform biometric prompt. */
    fun prepare(scope: BiometricSessionScope, use: BiometricKeyUse): Cipher {
        var keyStore = loadKeyStore()
        val alias = keyAlias(scope)
        if (use == BiometricKeyUse.Enrollment && !keyStore.containsAlias(alias)) {
            generateKey(alias)
            keyStore = loadKeyStore()
        }
        val key = keyStore.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("Biometric session key is unavailable")
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
    }

    /** Completes the authenticated crypto operation before the caller opens private UI. */
    @SuppressLint("ApplySharedPref")
    fun complete(scope: BiometricSessionScope, use: BiometricKeyUse, cipher: Cipher): Boolean {
        check(cipher.doFinal(PROBE).isNotEmpty())
        return when (use) {
            BiometricKeyUse.Enrollment -> preferences.edit()
                .putBoolean(enabledPreference(scope), true)
                .commit()

            BiometricKeyUse.Unlock -> isEnabled(scope)
        }
    }

    /** Removes a partial enrollment after cancellation or a failed setup prompt. */
    @SuppressLint("ApplySharedPref")
    fun cancelEnrollment(scope: BiometricSessionScope) {
        preferences.edit().remove(enabledPreference(scope)).commit()
        deleteKey(scope)
    }

    /** Removes a stale opt-in and unusable key after biometric configuration is invalidated. */
    @SuppressLint("ApplySharedPref")
    fun clearOptIn(scope: BiometricSessionScope): Boolean {
        val preferenceCleared = try {
            preferences.edit().remove(enabledPreference(scope)).commit()
        } catch (_: Exception) {
            false
        }
        val keyCleared = deleteKey(scope)
        return preferenceCleared && keyCleared
    }

    private fun deleteKey(scope: BiometricSessionScope): Boolean = try {
        loadKeyStore().let { keyStore ->
            val alias = keyAlias(scope)
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun generateKey(alias: String) {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(builder.build()) }
            .generateKey()
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private fun keyAlias(scope: BiometricSessionScope): String =
        "$KEY_ALIAS_PREFIX${scopeDigest(scope)}"

    private fun enabledPreference(scope: BiometricSessionScope): String =
        "$ENABLED_PREFERENCE_PREFIX${scopeDigest(scope)}"

    private fun scopeDigest(scope: BiometricSessionScope): String {
        val scopedIdentity = listOf(
            applicationContext.packageName,
            scope.userId,
            scope.tenantId,
            scope.workspaceId,
            scope.membershipId
        ).joinToString(separator = "\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(scopedIdentity.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS_PREFIX = "nexa.operations.biometric."
        const val ENABLED_PREFERENCE_PREFIX = "enabled."
        const val PREFERENCES_NAME = "biometric_session_unlock"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val PROBE = "nexa-operations-local-session".toByteArray(Charsets.UTF_8)
    }
}
