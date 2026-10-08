package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.VerifiedContextAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessStage
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessUiState
import java.util.UUID

internal enum class ScannerPermissionReturnSource { RuntimePrompt, AppSettings }

/** Route-only continuation. It intentionally contains no code, scanner result, or epoch. */
internal data class PendingScannerPermissionReturn(
    val authority: VerifiedContextAuthority,
    val source: ScannerPermissionReturnSource,
    val permissionGranted: Boolean? = null,
    val permanentlyDenied: Boolean = false,
    val foregroundReturnObserved: Boolean = false,
    val sessionRevalidationObserved: Boolean = false,
    val revalidationId: String? = null
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

/** Completion belongs to one foreground validation, never a later permission request. */
internal fun PendingScannerPermissionReturn.beginRevalidation(): PendingScannerPermissionReturn =
    copy(
        foregroundReturnObserved = true,
        sessionRevalidationObserved = false,
        revalidationId = UUID.randomUUID().toString()
    )

internal fun PendingScannerPermissionReturn?.completeRevalidation(
    completedId: String?
): PendingScannerPermissionReturn? =
    if (this != null && completedId != null && revalidationId == completedId) {
        copy(sessionRevalidationObserved = true)
    } else {
        this
    }
