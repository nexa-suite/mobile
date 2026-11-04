package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.auth.biometric.BiometricSessionScope

/** Local crypto proof survives only the server revalidation's temporary loss of UI context. */
internal data class BiometricReentryProof(
    val attempt: Long = 0,
    private val scope: BiometricSessionScope? = null,
    private val verified: Boolean = false
) {
    fun begin(scope: BiometricSessionScope): BiometricReentryProof =
        BiometricReentryProof(attempt + 1, scope)

    fun invalidate(): BiometricReentryProof = BiometricReentryProof(attempt + 1)

    fun complete(attempt: Long, verified: Boolean): BiometricReentryProof =
        if (this.attempt == attempt && scope != null) copy(verified = verified) else this

    fun observe(
        currentScope: BiometricSessionScope?,
        sessionCanRestoreScope: Boolean
    ): BiometricReentryProof = when {
        !sessionCanRestoreScope -> invalidate()
        currentScope != null && scope != null && currentScope != scope -> invalidate()
        else -> this
    }

    fun permits(
        currentScope: BiometricSessionScope?,
        sessionActive: Boolean,
        sessionCanRestoreScope: Boolean
    ): Boolean = verified && scope != null && scope == currentScope &&
        sessionActive && sessionCanRestoreScope
}
