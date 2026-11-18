package com.nexa.mobile.operations

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

internal class FcmPushInstallationStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun resetRequired(): Boolean = preferences.getBoolean(KEY_RESET_REQUIRED, true)

    fun scopeFingerprint(): String? = preferences.getString(KEY_SCOPE_FINGERPRINT, null)

    fun markResetRequired(): Boolean = commit {
        putBoolean(KEY_RESET_REQUIRED, true)
    }

    fun recordScopeAfterReset(scopeFingerprint: String): Boolean = commit {
        putString(KEY_SCOPE_FINGERPRINT, scopeFingerprint)
        putBoolean(KEY_RESET_REQUIRED, false)
    }

    fun clearScopeAndKeepResetRequired(): Boolean = commit {
        remove(KEY_SCOPE_FINGERPRINT)
        putBoolean(KEY_RESET_REQUIRED, true)
    }

    fun recordRegisteredScope(scopeFingerprint: String): Boolean = recordScopeAfterReset(
        scopeFingerprint
    )

    // KTX edit discards Editor.commit's failure result; registration must fail closed if durable
    // reset state is not saved.
    @SuppressLint("UseKtx")
    private inline fun commit(edit: SharedPreferences.Editor.() -> Unit): Boolean {
        val editor = preferences.edit()
        edit(editor)
        return editor.commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "nexa_fcm_registration_state"
        const val KEY_SCOPE_FINGERPRINT = "authorized_scope_fingerprint"
        const val KEY_RESET_REQUIRED = "firebase_installation_reset_required"
    }
}
