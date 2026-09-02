package com.nexa.mobile.feature.access

import com.nexa.mobile.core.network.ApiError
import com.nexa.mobile.core.network.ApiErrorCategory
import com.nexa.mobile.core.network.ApiResult
import com.nexa.mobile.core.storage.SessionMaterial
import com.nexa.mobile.core.storage.SessionStore
import com.nexa.mobile.core.testing.CoroutinesTestRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = CoroutinesTestRule(dispatcher)

    @Test
    fun initialStateDoesNotClaimAccess() {
        val viewModel = viewModel()

        assertEquals(LaunchUiState.Initial, viewModel.uiState.value)
    }

    @Test
    fun missingLocalSessionStaysUnauthenticated() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel(store = FakeSessionStore(null))

        viewModel.restore()
        advanceUntilIdle()

        assertEquals(LaunchUiState.NoSession, viewModel.uiState.value)
    }

    @Test
    fun restorationShowsLoadingUntilServerConfirmation() = runTest(dispatcher.scheduler) {
        val confirmationResult = CompletableDeferred<ApiResult<ConfirmedSessionContext>>()
        val viewModel = viewModel(
            confirmation = SessionConfirmation { confirmationResult.await() },
        )

        viewModel.restore()
        runCurrent()
        assertEquals(LaunchUiState.Loading, viewModel.uiState.value)

        confirmationResult.complete(ApiResult.Success(confirmedContext))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is LaunchUiState.Confirmed)
    }

    @Test
    fun confirmationPublishesServerAuthoritativePresentationContext() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel(
            confirmation = SessionConfirmation {
                ApiResult.Success(confirmedContext)
            },
        )

        viewModel.restore()
        advanceUntilIdle()

        assertEquals(
            ConfirmedSessionContext(
                user = ConfirmedSessionContext.User(
                    displayName = "Icísa",
                    email = "icisa@example.test",
                    preferredLanguage = "es-419",
                ),
                tenant = ConfirmedSessionContext.Tenant("icisa"),
                workspace = ConfirmedSessionContext.Workspace("operations"),
                roles = setOf("OPERATIONS"),
                capabilities = setOf("catalog:read", "inventory:read"),
            ),
            (viewModel.uiState.value as LaunchUiState.Confirmed).context,
        )
    }

    @Test
    fun restorationStorageFailureFailsClosed() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel(store = FakeSessionStore(session, failOnRead = true))

        viewModel.restore()
        advanceUntilIdle()

        assertEquals(LaunchUiState.Unavailable(LaunchFailure.STORAGE), viewModel.uiState.value)
        assertTrue(viewModel.uiState.value !is LaunchUiState.Confirmed)
    }

    @Test
    fun unauthorizedConfirmationFailsClosed() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel(
            confirmation = SessionConfirmation {
                ApiResult.Failure(ApiError(category = ApiErrorCategory.UNAUTHORIZED, status = 401))
            },
        )

        viewModel.restore()
        advanceUntilIdle()

        assertEquals(LaunchUiState.Unauthorized, viewModel.uiState.value)
    }

    @Test
    fun storedSessionWithoutConfirmedApiSurfaceStaysBlocked() = runTest(dispatcher.scheduler) {
        val viewModel = LaunchViewModel(
            sessionStore = FakeSessionStore(session),
            sessionConfirmation = null,
        )

        viewModel.restore()
        advanceUntilIdle()

        assertEquals(
            LaunchUiState.Unavailable(LaunchFailure.AUTH_SURFACE_BLOCKED),
            viewModel.uiState.value,
        )
    }

    @Test
    fun forbiddenConfirmationAlsoFailsClosed() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel(
            confirmation = SessionConfirmation {
                ApiResult.Failure(ApiError(category = ApiErrorCategory.FORBIDDEN, status = 403))
            },
        )

        viewModel.restore()
        advanceUntilIdle()

        assertEquals(LaunchUiState.Unauthorized, viewModel.uiState.value)
    }

    @Test
    fun networkFailureDoesNotExposeProtectedContent() = runTest(dispatcher.scheduler) {
        val viewModel = viewModel(
            confirmation = SessionConfirmation {
                ApiResult.Failure(ApiError(category = ApiErrorCategory.NETWORK, retryable = true))
            },
        )

        viewModel.restore()
        advanceUntilIdle()

        assertEquals(
            LaunchUiState.Unavailable(LaunchFailure.NETWORK),
            viewModel.uiState.value,
        )
    }

    @Test
    fun logoutClearsLocalSessionAndReturnsToNoSession() = runTest(dispatcher.scheduler) {
        val store = FakeSessionStore(session)
        val viewModel = viewModel(store = store)

        viewModel.logout()
        advanceUntilIdle()

        assertTrue(store.clearWasAttempted)
        assertEquals(LaunchUiState.NoSession, viewModel.uiState.value)
    }

    @Test
    fun logoutExecutesOptionalRemoteRevocationBeforeLocalCleanup() = runTest(dispatcher.scheduler) {
        val store = FakeSessionStore(session)
        var revoked: SessionMaterial? = null
        val viewModel = LaunchViewModel(
            sessionStore = store,
            sessionRevocation = SessionRevocation {
                revoked = it
                ApiResult.Success(Unit)
            },
        )

        viewModel.logout()
        advanceUntilIdle()

        assertEquals(session, revoked)
        assertTrue(store.clearWasAttempted)
        assertEquals(LaunchUiState.NoSession, viewModel.uiState.value)
    }

    @Test
    fun failedRemoteLogoutStillClearsLocalSession() = runTest(dispatcher.scheduler) {
        val store = FakeSessionStore(session)
        val viewModel = LaunchViewModel(
            sessionStore = store,
            sessionRevocation = SessionRevocation {
                ApiResult.Failure(ApiError(category = ApiErrorCategory.NETWORK, retryable = true))
            },
        )

        viewModel.logout()
        advanceUntilIdle()

        assertTrue(store.clearWasAttempted)
        assertEquals(null, store.read())
        assertEquals(LaunchUiState.NoSession, viewModel.uiState.value)
    }

    @Test
    fun restoreReconfirmsStoredSessionAfterPreviousFailure() = runTest(dispatcher.scheduler) {
        var confirmations = 0
        val viewModel = viewModel(
            confirmation = SessionConfirmation {
                confirmations += 1
                if (confirmations == 1) {
                    ApiResult.Failure(ApiError(category = ApiErrorCategory.UNAUTHORIZED, status = 401))
                } else {
                    ApiResult.Success(confirmedContext)
                }
            },
        )

        viewModel.restore()
        advanceUntilIdle()
        assertEquals(LaunchUiState.Unauthorized, viewModel.uiState.value)

        viewModel.restore()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is LaunchUiState.Confirmed)
        assertEquals(2, confirmations)
    }

    @Test
    fun logoutStorageFailureDoesNotClaimSessionWasCleared() = runTest(dispatcher.scheduler) {
        val store = FakeSessionStore(session, failOnClear = true)
        val viewModel = viewModel(store = store)

        viewModel.logout()
        advanceUntilIdle()

        assertTrue(store.clearWasAttempted)
        assertEquals(
            LaunchUiState.Unavailable(LaunchFailure.STORAGE),
            viewModel.uiState.value,
        )
    }

    private fun viewModel(
        store: FakeSessionStore = FakeSessionStore(session),
        confirmation: SessionConfirmation = SessionConfirmation { ApiResult.Success(confirmedContext) },
    ): LaunchViewModel = LaunchViewModel(store, confirmation)

    private class FakeSessionStore(
        initial: SessionMaterial?,
        private val failOnRead: Boolean = false,
        private val failOnClear: Boolean = false,
    ) : SessionStore {
        private var material = initial
        var clearWasAttempted = false

        override suspend fun read(): SessionMaterial? {
            if (failOnRead) error("simulated storage read failure")
            return material
        }

        override suspend fun write(material: SessionMaterial) {
            this.material = material
        }

        override suspend fun clear() {
            clearWasAttempted = true
            if (failOnClear) error("simulated storage failure")
            material = null
        }
    }

    private companion object {
        val confirmedContext = ConfirmedSessionContext(
            user = ConfirmedSessionContext.User(
                displayName = "Icísa",
                email = "icisa@example.test",
                preferredLanguage = "es-419",
            ),
            tenant = ConfirmedSessionContext.Tenant("icisa"),
            workspace = ConfirmedSessionContext.Workspace("operations"),
            roles = setOf("OPERATIONS"),
            capabilities = setOf("catalog:read", "inventory:read"),
        )

        val session = SessionMaterial(
            accessToken = "access-token-for-test",
            refreshToken = "refresh-token-for-test",
            accessTokenExpiresAtEpochSeconds = 1_900_000_000,
            surface = "PLATFORM",
        )
    }
}
