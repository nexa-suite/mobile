package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.feature.access.AccessStage
import com.nexa.mobile.operations.feature.access.AccessUiState
import com.nexa.mobile.operations.feature.access.VerifiedContextAuthority

internal enum class ScannerPermissionReturnSource { RuntimePrompt, AppSettings }

/** Route-only continuation. It intentionally contains no code, scanner result, or epoch. */
internal data class PendingScannerPermissionReturn(
    val authority: VerifiedContextAuthority,
    val source: ScannerPermissionReturnSource,
    val permissionGranted: Boolean? = null,
    val permanentlyDenied: Boolean = false,
    val foregroundReturnObserved: Boolean = false,
    val sessionRevalidationObserved: Boolean = false
) {
    override fun toString(): String =
        "PendingScannerPermissionReturn(source=$source, result=${permissionGranted != null})"
}

internal sealed interface ScannerPermissionReturnDecision {
    data object Wait : ScannerPermissionReturnDecision
    data object Drop : ScannerPermissionReturnDecision
    data class Resume(val permissionGranted: Boolean, val permanentlyDenied: Boolean) :
        ScannerPermissionReturnDecision
}

internal fun PendingScannerPermissionReturn.decision(
    sessionState: SessionState,
    access: AccessUiState
): ScannerPermissionReturnDecision {
    if (sessionState in setOf(
            SessionState.SignedOut,
            SessionState.ContextRequired,
            SessionState.ReauthenticationRequired,
            SessionState.LocalProtectionError
        )
    ) {
        return ScannerPermissionReturnDecision.Drop
    }
    if (sessionState != SessionState.Active || !foregroundReturnObserved ||
        !sessionRevalidationObserved || access.stage != AccessStage.WorkAuthorized
    ) {
        return ScannerPermissionReturnDecision.Wait
    }
    val currentAuthority = access.activeContext?.verifiedAuthority
        ?: return ScannerPermissionReturnDecision.Wait
    if (currentAuthority != authority) return ScannerPermissionReturnDecision.Drop
    val granted = permissionGranted ?: return ScannerPermissionReturnDecision.Wait
    return ScannerPermissionReturnDecision.Resume(granted, permanentlyDenied)
}
