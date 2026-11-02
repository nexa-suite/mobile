package com.nexa.mobile.operations

import android.app.Activity
import android.content.DialogInterface
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nexa.mobile.operations.core.auth.biometric.BiometricKeyUse
import com.nexa.mobile.operations.core.auth.biometric.BiometricSessionKeyStore
import com.nexa.mobile.operations.core.auth.biometric.BiometricSessionScope
import com.nexa.mobile.operations.core.designsystem.NexaAuthCanopy
import com.nexa.mobile.operations.core.designsystem.R as DesignSystemR

private data class BiometricSessionActions(
    val scope: BiometricSessionScope?,
    val sessionActive: Boolean,
    val enabled: Boolean,
    val biometricAvailable: Boolean,
    val statusResource: Int,
    val setEnabled: (Boolean) -> Unit,
    val dismissStatus: () -> Unit
)

private val LocalBiometricSessionActions = compositionLocalOf<BiometricSessionActions?> { null }

/** Insert inside the Profile sheet composed below [BiometricSessionGate]. */
@Composable
internal fun BiometricProfileSetting(modifier: Modifier = Modifier) {
    val actions = LocalBiometricSessionActions.current ?: return
    if (!actions.sessionActive || actions.scope == null) return

    val title = stringResource(R.string.biometric_unlock_enable)
    val details = stringResource(R.string.biometric_unlock_profile_description)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clearAndSetSemantics {}
                )
                Text(
                    details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = actions.enabled,
                onCheckedChange = actions.setEnabled,
                enabled = actions.enabled || actions.biometricAvailable,
                modifier = Modifier.semantics {
                    contentDescription = "$title. $details"
                }
            )
        }
        if (actions.statusResource != 0) {
            val unavailable = actions.statusResource == R.string.biometric_unlock_unavailable
            Text(
                text = stringResource(actions.statusResource),
                color = if (unavailable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
            if (!unavailable) {
                TextButton(onClick = actions.dismissStatus) {
                    Text(stringResource(R.string.biometric_unlock_dismiss))
                }
            }
        }
    }
}

@Composable
internal fun BiometricSessionGate(
    activity: ComponentActivity,
    sessionActive: Boolean,
    scope: BiometricSessionScope?,
    onPasswordFallback: () -> Unit,
    onVerifySession: (onVerified: (Boolean) -> Unit) -> Unit,
    content: @Composable () -> Unit
) {
    val store = remember(activity.applicationContext) {
        BiometricSessionKeyStore(activity.applicationContext)
    }
    var enabled by remember(scope) { mutableStateOf(scope?.let(store::isEnabled) == true) }
    var unlocked by remember(scope) { mutableStateOf(false) }
    var passwordFallbackScope by remember { mutableStateOf<BiometricSessionScope?>(null) }
    var statusResource by remember(scope) { mutableIntStateOf(0) }
    var biometricAvailable by remember(scope) {
        mutableStateOf(canAuthenticateStrongBiometric(activity))
    }
    var promptCancellation by remember { mutableStateOf<CancellationSignal?>(null) }
    val currentScope by rememberUpdatedState(scope)
    val currentSessionActive by rememberUpdatedState(sessionActive)

    LaunchedEffect(sessionActive, scope) {
        enabled = sessionActive && scope?.let(store::isEnabled) == true
        if (sessionActive && scope != null) {
            // A successful password sign-in after an explicit fallback already
            // re-authenticated with the server; do not prompt twice in a row.
            unlocked = passwordFallbackScope == scope
            passwordFallbackScope = null
        } else {
            unlocked = false
        }
        biometricAvailable = canAuthenticateStrongBiometric(activity)
        statusResource = if (sessionActive && scope != null && !biometricAvailable) {
            R.string.biometric_unlock_unavailable
        } else {
            0
        }
    }

    DisposableEffect(activity.lifecycle, scope, sessionActive, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && enabled && sessionActive) {
                unlocked = false
                passwordFallbackScope = null
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            promptCancellation?.cancel()
            promptCancellation = null
        }
    }

    fun usePasswordFallback(requestedScope: BiometricSessionScope) {
        if (!sessionActive || currentScope != requestedScope) return
        promptCancellation?.cancel()
        promptCancellation = null
        if (!runCatching { store.isConfigured(requestedScope) }.getOrDefault(false)) {
            val cleared = runCatching { store.clearOptIn(requestedScope) }.getOrDefault(false)
            if (!cleared) {
                statusResource = R.string.biometric_unlock_storage_error
                return
            }
            enabled = false
        }
        passwordFallbackScope = requestedScope
        unlocked = false
        statusResource = 0
        onPasswordFallback()
    }

    fun launchPrompt(requestedScope: BiometricSessionScope, use: BiometricKeyUse) {
        if (promptCancellation != null) return
        statusResource = 0
        biometricAvailable = canAuthenticateStrongBiometric(activity)
        if (!biometricAvailable) {
            statusResource = R.string.biometric_unlock_unavailable
            return
        }

        val cipher = try {
            store.prepare(requestedScope, use)
        } catch (_: Exception) {
            if (use == BiometricKeyUse.Enrollment) store.cancelEnrollment(requestedScope)
            statusResource = R.string.biometric_unlock_error
            return
        }
        val signal = CancellationSignal()
        promptCancellation = signal

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                promptCancellation = null
                if (currentScope != requestedScope || !currentSessionActive) {
                    if (use == BiometricKeyUse.Enrollment) {
                        store.cancelEnrollment(requestedScope)
                    }
                    return
                }
                if (use == BiometricKeyUse.Enrollment) store.cancelEnrollment(requestedScope)
                statusResource = when (errorCode) {
                    BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
                    BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT ->
                        R.string.biometric_unlock_lockout

                    BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS,
                    BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
                    BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                        R.string.biometric_unlock_unavailable

                    BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
                    BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> if (use ==
                        BiometricKeyUse.Enrollment
                    ) {
                        R.string.biometric_unlock_setup_cancelled
                    } else {
                        R.string.biometric_unlock_cancelled
                    }

                    else -> R.string.biometric_unlock_error
                }
            }

            override fun onAuthenticationFailed() {
                if (currentScope == requestedScope && currentSessionActive) {
                    statusResource = R.string.biometric_unlock_not_recognized
                }
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                promptCancellation = null
                if (currentScope != requestedScope || !currentSessionActive) {
                    if (use == BiometricKeyUse.Enrollment) {
                        store.cancelEnrollment(requestedScope)
                    }
                    return
                }
                val authenticatedCipher = result.cryptoObject?.cipher
                val completed = try {
                    authenticatedCipher != null &&
                        store.complete(requestedScope, use, authenticatedCipher)
                } catch (_: Exception) {
                    false
                }
                if (!completed) {
                    if (use == BiometricKeyUse.Enrollment) store.cancelEnrollment(requestedScope)
                    statusResource = R.string.biometric_unlock_error
                    return
                }
                statusResource = 0
                when (use) {
                    BiometricKeyUse.Enrollment -> {
                        enabled = true
                        unlocked = true
                    }

                    BiometricKeyUse.Unlock -> {
                        onVerifySession { verified ->
                            if (currentScope != requestedScope || !currentSessionActive) {
                                return@onVerifySession
                            }
                            if (verified) {
                                unlocked = true
                                statusResource = 0
                            } else {
                                statusResource = R.string.biometric_unlock_session_unverified
                            }
                        }
                    }
                }
            }
        }

        try {
            val promptTitle = if (use == BiometricKeyUse.Enrollment) {
                R.string.biometric_unlock_prompt_enable_title
            } else {
                R.string.biometric_unlock_prompt_unlock_title
            }
            val negativeButton = if (use == BiometricKeyUse.Unlock) {
                R.string.biometric_unlock_password
            } else {
                R.string.biometric_unlock_cancel
            }
            val builder = BiometricPrompt.Builder(activity)
                .setTitle(activity.getString(promptTitle))
                .setSubtitle(activity.getString(R.string.biometric_unlock_prompt_subtitle))
                .setNegativeButton(
                    activity.getString(negativeButton),
                    activity.mainExecutor
                ) { _: DialogInterface, _: Int ->
                    promptCancellation = null
                    if (currentScope == requestedScope && currentSessionActive) {
                        if (use == BiometricKeyUse.Enrollment) {
                            store.cancelEnrollment(requestedScope)
                            statusResource = R.string.biometric_unlock_setup_cancelled
                        } else {
                            usePasswordFallback(requestedScope)
                        }
                    }
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            }
            builder.build().authenticate(
                BiometricPrompt.CryptoObject(cipher),
                signal,
                activity.mainExecutor,
                callback
            )
        } catch (_: Exception) {
            promptCancellation = null
            if (use == BiometricKeyUse.Enrollment) store.cancelEnrollment(requestedScope)
            statusResource = R.string.biometric_unlock_error
        }
    }

    fun setBiometricEnabled(requested: Boolean) {
        val requestedScope = scope?.takeIf { sessionActive } ?: return
        statusResource = 0
        if (requested) {
            launchPrompt(requestedScope, BiometricKeyUse.Enrollment)
            return
        }
        val cleared = runCatching { store.clearOptIn(requestedScope) }.getOrDefault(false)
        if (cleared) {
            enabled = false
            unlocked = false
        } else {
            statusResource = R.string.biometric_unlock_storage_error
        }
    }

    val actions = BiometricSessionActions(
        scope = scope,
        sessionActive = sessionActive,
        enabled = enabled,
        biometricAvailable = biometricAvailable,
        statusResource = statusResource,
        setEnabled = ::setBiometricEnabled,
        dismissStatus = { statusResource = 0 }
    )

    val lockRequired = sessionActive && scope != null && enabled && !unlocked
    if (lockRequired) {
        NexaSystemBars(lightSurfaceVisible = true)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                val compact = maxHeight < 480.dp
                Column(modifier = Modifier.fillMaxSize()) {
                    NexaAuthCanopy(
                        modifier = Modifier.fillMaxWidth()
                            .weight(if (compact) 0.24f else 0.38f)
                    ) {
                        Image(
                            painter = painterResource(DesignSystemR.drawable.nexa_wordmark_white),
                            contentDescription = stringResource(
                                DesignSystemR.string.nexa_brand_name
                            ),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.align(Alignment.TopStart)
                                .padding(start = 24.dp, top = if (compact) 16.dp else 40.dp)
                                .size(width = 112.dp, height = 35.dp)
                        )
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .weight(if (compact) 0.76f else 0.62f)
                            .padding(horizontal = 28.dp, vertical = if (compact) 8.dp else 24.dp),
                        verticalArrangement = Arrangement.spacedBy(
                            if (compact) 8.dp else 16.dp,
                            alignment = Alignment.CenterVertically
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.biometric_unlock_locked_title),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.biometric_unlock_locked_details),
                            textAlign = TextAlign.Center
                        )
                        if (statusResource != 0) {
                            Text(
                                text = stringResource(statusResource),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                },
                                textAlign = TextAlign.Center
                            )
                        }
                        if (biometricAvailable) {
                            val unlockDescription = stringResource(R.string.biometric_unlock_unlock)
                            IconButton(
                                onClick = { launchPrompt(scope, BiometricKeyUse.Unlock) },
                                modifier = Modifier.size(48.dp).semantics {
                                    contentDescription = unlockDescription
                                }
                            ) {
                                FingerprintGlyph(Modifier.size(32.dp))
                            }
                        }
                        TextButton(onClick = { usePasswordFallback(scope) }) {
                            Text(stringResource(R.string.biometric_unlock_password))
                        }
                    }
                }
            }
        }
        return
    }

    CompositionLocalProvider(LocalBiometricSessionActions provides actions) {
        content()
    }
}

@Composable
private fun FingerprintGlyph(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val side = minOf(size.width, size.height)
        val centerX = size.width / 2f
        val top = (size.height - side) / 2f
        val strokeWidth = 1.8.dp.toPx()
        for (ridge in 0..3) {
            val halfWidth = side * (0.16f + ridge * 0.09f)
            val ridgeTop = top + side * (0.18f + ridge * 0.06f)
            val ridgeBottom = top + side * (0.78f - ridge * 0.04f)
            val path = Path().apply {
                moveTo(centerX, ridgeBottom)
                cubicTo(
                    centerX - halfWidth,
                    ridgeBottom,
                    centerX - halfWidth,
                    ridgeTop,
                    centerX,
                    ridgeTop
                )
                cubicTo(
                    centerX + halfWidth,
                    ridgeTop,
                    centerX + halfWidth,
                    ridgeBottom,
                    centerX + halfWidth * 0.45f,
                    ridgeBottom - side * 0.05f
                )
            }
            drawPath(path, color = primary, style = Stroke(width = strokeWidth))
        }
    }
}

private fun canAuthenticateStrongBiometric(activity: Activity): Boolean {
    return try {
        val manager = activity.getSystemService(BiometricManager::class.java)
            ?: return false
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } else {
            manager.canAuthenticate()
        }
        result == BiometricManager.BIOMETRIC_SUCCESS
    } catch (_: Exception) {
        false
    }
}
