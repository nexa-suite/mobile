package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.feature.access.AccessStage
import com.nexa.mobile.operations.feature.access.AccessUiState
import com.nexa.mobile.operations.feature.access.PermissionHint
import com.nexa.mobile.operations.feature.access.VerifiedContextAuthority
import com.nexa.mobile.operations.feature.access.WorkforceContextSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class ScannerPermissionReturnTest {
    private val authority = VerifiedContextAuthority(
        userId = "user-1",
        tenantId = "tenant-1",
        workspaceId = "workspace-1",
        membershipId = "membership-1",
        permissions = setOf("catalog.read")
    )
    private val pending = PendingScannerPermissionReturn(
        authority = authority,
        source = ScannerPermissionReturnSource.RuntimePrompt,
        permissionGranted = true,
        foregroundReturnObserved = true,
        sessionRevalidationObserved = true
    )

    @Test fun waitsForFreshSessionAndContextBeforeOneShotUiResume() {
        assertEquals(
            ScannerPermissionReturnDecision.Wait,
            pending.copy(sessionRevalidationObserved = false).decision(
                SessionState.Active,
                access(authority)
            )
        )
        assertEquals(
            ScannerPermissionReturnDecision.Resume(true, false),
            pending.decision(SessionState.Active, access(authority))
        )
        assertEquals(
            ScannerPermissionReturnDecision.Drop,
            pending.decision(SessionState.ReauthenticationRequired, access(authority))
        )
    }

    @Test fun dropsPermissionReturnWhenAnyVerifiedScopeOrPermissionChanges() {
        val otherMembership = authority.copy(membershipId = "membership-2")
        val otherPermission = authority.copy(permissions = setOf("catalog.read", "inventory.read"))
        assertEquals(
            ScannerPermissionReturnDecision.Drop,
            pending.decision(SessionState.Active, access(otherMembership))
        )
        assertEquals(
            ScannerPermissionReturnDecision.Drop,
            pending.decision(SessionState.Active, access(otherPermission))
        )
    }

    @Test fun fastValidationCompletionDoesNotRequireObservingRestoring() {
        val validating = pending.copy(sessionRevalidationObserved = false).beginRevalidation()
        val completed = validating.completeRevalidation(validating.revalidationId)!!
        assertEquals(
            ScannerPermissionReturnDecision.Resume(true, false),
            completed.decision(SessionState.Active, access(authority))
        )
    }

    @Test fun staleCompletionCannotReviveClearedOrNewPermissionReturn() {
        val previous = pending.beginRevalidation()
        val next = pending.beginRevalidation()
        assertEquals(next, next.completeRevalidation(previous.revalidationId))
        assertEquals(
            ScannerPermissionReturnDecision.Wait,
            next.completeRevalidation(previous.revalidationId)!!.decision(
                SessionState.Active,
                access(authority)
            )
        )
        assertEquals(null, null.completeRevalidation(previous.revalidationId))
    }

    private fun access(currentAuthority: VerifiedContextAuthority) = AccessUiState(
        stage = AccessStage.WorkAuthorized,
        activeContext = WorkforceContextSummary(
            key = currentAuthority.membershipId,
            companyName = "Company",
            workspaceName = "Workspace",
            permissionHint = PermissionHint.Available,
            isCurrent = true,
            verifiedAuthority = currentAuthority
        )
    )
}
