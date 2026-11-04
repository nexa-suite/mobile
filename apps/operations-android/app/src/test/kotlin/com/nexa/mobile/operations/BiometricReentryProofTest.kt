package com.nexa.mobile.operations

import com.nexa.mobile.operations.core.auth.biometric.BiometricSessionScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricReentryProofTest {
    private val scope = BiometricSessionScope("user", "tenant", "workspace", "membership")

    @Test
    fun serverResultBeforeContextRestorationUnlocksOnlyRestoredScope() {
        val started = BiometricReentryProof().begin(scope)
        val restoring = started.observe(null, true).complete(started.attempt, true)
        assertFalse(restoring.permits(null, false, true))
        assertFalse(restoring.permits(scope, false, true))
        assertTrue(restoring.observe(scope, true).permits(scope, true, true))
    }

    @Test
    fun serverResultAfterContextRestorationUnlocks() {
        val started = BiometricReentryProof().begin(scope)
        val restored = started.observe(null, true).observe(scope, true)
        assertFalse(restored.permits(scope, true, true))
        assertTrue(restored.complete(started.attempt, true).permits(scope, true, true))
    }

    @Test
    fun serverFailureNeverUnlocks() {
        val started = BiometricReentryProof().begin(scope)
        assertFalse(started.complete(started.attempt, false).permits(scope, true, true))
    }

    @Test
    fun differentAuthorizedContextInvalidatesProofEvenWhenOriginalReturns() {
        val started = BiometricReentryProof().begin(scope)
        val changed = started.observe(scope.copy(workspaceId = "other"), true)
        assertFalse(changed.complete(started.attempt, true).permits(scope, true, true))
    }

    @Test
    fun terminalSessionInvalidatesLateServerResult() {
        val started = BiometricReentryProof().begin(scope)
        val signedOut = started.observe(null, false)
        assertFalse(signedOut.complete(started.attempt, true).permits(scope, true, true))
    }

    @Test
    fun backgroundInvalidatesPendingAndCompletedProof() {
        val started = BiometricReentryProof().begin(scope)
        assertFalse(started.invalidate().complete(started.attempt, true).permits(scope, true, true))
        assertFalse(started.complete(started.attempt, true).invalidate().permits(scope, true, true))
    }

    @Test
    fun newAttemptRejectsEarlierCompletion() {
        val first = BiometricReentryProof().begin(scope)
        val second = first.invalidate().begin(scope)
        assertFalse(second.complete(first.attempt, true).permits(scope, true, true))
        assertTrue(second.complete(second.attempt, true).permits(scope, true, true))
    }

    @Test
    fun explicitChooserInvalidatesProofEvenWhenSameContextReturns() {
        val started = BiometricReentryProof().begin(scope)
        val chooser = started.complete(started.attempt, true).observe(scope, false)
        assertFalse(chooser.observe(scope, true).permits(scope, true, true))
        assertFalse(chooser.complete(started.attempt, true).permits(scope, true, true))
    }

    @Test
    fun everyIdentityBoundaryInvalidatesProof() {
        val alternatives = listOf(
            scope.copy(userId = "other"),
            scope.copy(tenantId = "other"),
            scope.copy(workspaceId = "other"),
            scope.copy(membershipId = "other")
        )
        alternatives.forEach { other ->
            val started = BiometricReentryProof().begin(scope)
            val changed = started.observe(other, true)
            assertFalse(changed.complete(started.attempt, true).permits(other, true, true))
            assertFalse(changed.observe(scope, true).permits(scope, true, true))
        }
    }

    @Test
    fun backgroundBeforePlatformSuccessRejectsAttemptAndLateServerCompletion() {
        val promptStarted = BiometricReentryProof().begin(scope)
        val backgrounded = promptStarted.invalidate()
        assertTrue(backgrounded.attempt != promptStarted.attempt)
        assertFalse(
            backgrounded.complete(promptStarted.attempt, true).permits(scope, true, true)
        )
    }
}
