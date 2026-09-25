package com.nexa.mobile.operations.feature.access

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccessViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun signInTrimsIdentifierAndRequiresExplicitContextConfirmation() = runTest {
        val gateway = FakeAccessGateway().apply {
            signInResult = SignInResult.SelectionRequired
            contextsResult = ContextListResult.Available(listOf(contextA, contextB))
        }
        val viewModel = AccessViewModel(gateway)
        viewModel.identifierChanged("  operator@nexa.demo  ")
        viewModel.passwordChanged("secret")
        viewModel.signIn()
        advanceUntilIdle()

        assertEquals("operator@nexa.demo", gateway.identifier)
        assertEquals("secret", gateway.password)
        assertEquals(AccessStage.ContextChooser, viewModel.state.value.stage)
        assertEquals(ContextChooserPhase.Choices, viewModel.state.value.chooser?.phase)
        assertNull(viewModel.state.value.activeContext)
        assertEquals("", viewModel.state.value.password)
        assertFalse(viewModel.state.value.toString().contains("secret"))
        assertTrue(gateway.selectionCalls.isEmpty())
    }

    @Test
    fun oneContextFromAccessTicketNeedsExplicitSelectionConfirmation() = runTest {
        val gateway = FakeAccessGateway().apply {
            contextsResult = ContextListResult.Available(listOf(contextA))
        }
        val viewModel = AccessViewModel(gateway)
        viewModel.openInitialContextChooser()
        advanceUntilIdle()

        assertTrue(gateway.selectionCalls.isEmpty())
        assertEquals(AccessStage.ContextChooser, viewModel.state.value.stage)
        assertNull(viewModel.state.value.activeContext)
        assertEquals(ContextChooserPhase.Choices, viewModel.state.value.chooser?.phase)

        viewModel.selectContext(contextA.key)
        advanceUntilIdle()
        assertEquals(1, gateway.selectionCalls.size)
        assertEquals(AccessStage.WorkAuthorized, viewModel.state.value.stage)
        assertEquals(contextA.key, viewModel.state.value.activeContext?.key)
    }

    @Test
    fun restoredSessionUsesOnlyAConfirmedCurrentContext() = runTest {
        val gateway = FakeAccessGateway().apply {
            currentContextResult = CurrentSessionContextResult.Available(contextA)
        }
        val viewModel = AccessViewModel(gateway)
        viewModel.resolveCurrentSessionContext()
        advanceUntilIdle()

        assertEquals(AccessStage.WorkAuthorized, viewModel.state.value.stage)
        assertEquals(contextA.key, viewModel.state.value.activeContext?.key)
        assertEquals(1L, viewModel.state.value.authorityEpoch)

        gateway.currentContextResult = CurrentSessionContextResult.Invalid
        viewModel.resolveCurrentSessionContext()
        advanceUntilIdle()
        assertEquals(AccessStage.IdentityRequired, viewModel.state.value.stage)
        assertNull(viewModel.state.value.activeContext)
        assertEquals(AccessNotice.UnknownContextOutcome, viewModel.state.value.notice)
    }

    @Test
    fun rejectedOneUseTicketSelectionFailsClosedWithoutReplay() = runTest {
        val gateway = FakeAccessGateway().apply {
            contextsResult = ContextListResult.Available(listOf(contextA, contextB))
            selectionResult = ContextSelectionResult.Rejected
        }
        val viewModel = AccessViewModel(gateway)
        viewModel.signInWithTestCredentials()
        advanceUntilIdle()
        viewModel.selectContext(contextB.key)
        advanceUntilIdle()

        assertEquals(AccessStage.IdentityRequired, viewModel.state.value.stage)
        assertNull(viewModel.state.value.activeContext)
        assertNull(viewModel.state.value.chooser)
        assertEquals(AccessNotice.ContextSelectionRejected, viewModel.state.value.notice)
        assertEquals(1, gateway.selectionCalls.size)
    }

    @Test
    fun singleContextSignInUsesIssuedSessionWithoutListingContexts() = runTest {
        val gateway = FakeAccessGateway().apply {
            signInResult = SignInResult.Authenticated(contextA)
        }
        val viewModel = AccessViewModel(gateway)
        viewModel.signInWithTestCredentials()
        advanceUntilIdle()

        assertEquals(AccessStage.WorkAuthorized, viewModel.state.value.stage)
        assertEquals(contextA.key, viewModel.state.value.activeContext?.key)
        assertEquals(0, gateway.contextListCalls)
    }

    @Test
    fun lateContextListCannotRestoreStateAfterSessionInvalidation() = runTest {
        val deferred = CompletableDeferred<ContextListResult>()
        val gateway = FakeAccessGateway().apply { contextsDeferred = deferred }
        val viewModel = AccessViewModel(gateway)

        viewModel.openInitialContextChooser()
        runCurrent()
        viewModel.sessionInvalidated(expired = true)
        deferred.complete(ContextListResult.Available(listOf(contextA)))
        advanceUntilIdle()

        assertEquals(AccessStage.SessionExpired, viewModel.state.value.stage)
        assertNull(viewModel.state.value.activeContext)
        assertNull(viewModel.state.value.chooser)
    }

    private class FakeAccessGateway : AccessGateway {
        var signInResult: SignInResult = SignInResult.SelectionRequired
        var currentContextResult: CurrentSessionContextResult =
            CurrentSessionContextResult.Invalid
        var contextsResult: ContextListResult = ContextListResult.Available(emptyList())
        var selectionResult: ContextSelectionResult = ContextSelectionResult.Confirmed(contextA)
        var contextsDeferred: CompletableDeferred<ContextListResult>? = null
        var contextListCalls = 0
        var identifier: String? = null
        var password: String? = null
        val selectionCalls = mutableListOf<WorkforceContextSummary>()

        override suspend fun signIn(identifier: String, password: String): SignInResult {
            this.identifier = identifier
            this.password = password
            return signInResult
        }

        override suspend fun listContexts(): ContextListResult {
            contextListCalls++
            return contextsDeferred?.await() ?: contextsResult
        }

        override suspend fun currentSessionContext(): CurrentSessionContextResult =
            currentContextResult

        override suspend fun selectContext(
            context: WorkforceContextSummary
        ): ContextSelectionResult {
            selectionCalls += context
            return selectionResult
        }
    }

    private fun AccessViewModel.signInWithTestCredentials() {
        identifierChanged("operator@example.test")
        passwordChanged("secret")
        signIn()
    }

    private companion object {
        val contextA = WorkforceContextSummary(
            key = "synthetic-a",
            companyName = "Nexa Demo Distribución",
            workspaceName = "Almacén Principal",
            permissionHint = PermissionHint.Available
        )
        val contextB = WorkforceContextSummary(
            key = "synthetic-b",
            companyName = "Nexa Demo Norte",
            workspaceName = "Almacén Secundario",
            permissionHint = PermissionHint.Available
        )
    }
}
