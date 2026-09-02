package com.nexa.mobile.feature.access

import com.nexa.mobile.core.network.ApiClientSurface
import com.nexa.mobile.core.network.ApiError
import com.nexa.mobile.core.network.ApiErrorCategory
import com.nexa.mobile.core.network.ApiResult
import com.nexa.mobile.core.network.AuthenticationSession
import com.nexa.mobile.core.network.NativeAuthentication
import com.nexa.mobile.core.storage.SessionMaterial
import com.nexa.mobile.core.storage.SessionStore
import com.nexa.mobile.core.testing.CoroutinesTestRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = CoroutinesTestRule(dispatcher)

    @Test
    fun missingEndpointOrSurfaceStaysBlockedAndDoesNotCallGateway() = runTest(dispatcher.scheduler) {
        var calls = 0
        val viewModel = SignInViewModel(
            gateway = SignInGateway { _, _, _, _ ->
                calls += 1
                ApiResult.Success(authentication())
            },
            surface = null,
            sessionStore = FakeSessionStore(),
        )

        viewModel.submit()
        advanceUntilIdle()

        assertEquals(SignInStatus.Blocked, viewModel.formState.value.status)
        assertEquals(0, calls)
    }

    @Test
    fun blankFieldsBecomeValidationFailureAndClearPassword() = runTest(dispatcher.scheduler) {
        val viewModel = viewModelWith { ApiResult.Success(authentication()) }
        viewModel.onIdentifierChanged("operator@example.test")
        viewModel.onPasswordChanged("transient-password")

        viewModel.submit()
        advanceUntilIdle()

        assertEquals(
            SignInStatus.Failed(SignInFailure.VALIDATION),
            viewModel.formState.value.status,
        )
        assertEquals("", viewModel.formState.value.password)
    }

    @Test
    fun submitUsesNativeSignInDirectlyWithTrimmedCredentialsAndCallerSurface() = runTest(dispatcher.scheduler) {
        val result = CompletableDeferred<ApiResult<NativeAuthentication>>()
        var capturedIdentifier: String? = null
        var capturedPassword: String? = null
        var capturedWorkspaceSlug: String? = null
        var capturedSurface: ApiClientSurface? = null
        val store = FakeSessionStore()
        val viewModel = SignInViewModel(
            gateway = SignInGateway { identifier, password, workspaceSlug, surface ->
                capturedIdentifier = identifier
                capturedPassword = password
                capturedWorkspaceSlug = workspaceSlug
                capturedSurface = surface
                result.await()
            },
            surface = ApiClientSurface.PLATFORM,
            sessionStore = store,
        )
        viewModel.onIdentifierChanged(" operator@example.test ")
        viewModel.onWorkspaceChanged(" cold-chain ")
        viewModel.onPasswordChanged("transient-password")

        viewModel.submit()
        runCurrent()

        assertEquals(SignInStatus.Loading, viewModel.formState.value.status)
        assertEquals("", viewModel.formState.value.password)
        assertEquals("operator@example.test", capturedIdentifier)
        assertEquals("transient-password", capturedPassword)
        assertEquals("cold-chain", capturedWorkspaceSlug)
        assertEquals(ApiClientSurface.PLATFORM, capturedSurface)

        result.complete(ApiResult.Success(authentication()))
        advanceUntilIdle()

        assertEquals(SignInStatus.Authenticated, viewModel.formState.value.status)
        assertEquals("access-token", store.material?.accessToken)
        assertEquals("refresh-token", store.material?.refreshToken)
    }

    @Test
    fun unauthorizedResponseMapsToLocalizedFailureState() = runTest(dispatcher.scheduler) {
        val viewModel = viewModelWith {
            ApiResult.Failure(ApiError(category = ApiErrorCategory.UNAUTHORIZED, status = 401))
        }
        fillForm(viewModel)

        viewModel.submit()
        advanceUntilIdle()

        assertEquals(
            SignInStatus.Failed(SignInFailure.UNAUTHORIZED),
            viewModel.formState.value.status,
        )
    }

    @Test
    fun storageFailureClearsStoreAndNeverClaimsAuthenticated() = runTest(dispatcher.scheduler) {
        val store = FakeSessionStore(failOnWrite = true)
        val viewModel = SignInViewModel(
            gateway = SignInGateway { _, _, _, _ -> ApiResult.Success(authentication()) },
            surface = ApiClientSurface.PLATFORM,
            sessionStore = store,
        )
        fillForm(viewModel)

        viewModel.submit()
        advanceUntilIdle()

        assertEquals(SignInStatus.Failed(SignInFailure.STORAGE), viewModel.formState.value.status)
        assertTrue(store.clearWasAttempted)
        assertNull(store.material)
    }

    private fun viewModelWith(
        result: suspend () -> ApiResult<NativeAuthentication>,
    ): SignInViewModel = SignInViewModel(
        gateway = SignInGateway { _, _, _, _ -> result() },
        surface = ApiClientSurface.PLATFORM,
        sessionStore = FakeSessionStore(),
    )

    private fun fillForm(viewModel: SignInViewModel) {
        viewModel.onIdentifierChanged("operator@example.test")
        viewModel.onWorkspaceChanged("cold-chain")
        viewModel.onPasswordChanged("transient-password")
    }

    private fun authentication(): NativeAuthentication = NativeAuthentication(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        expiresInSeconds = 900,
        surface = ApiClientSurface.PLATFORM,
        session = AuthenticationSession(
            userId = "user-1",
            displayName = "Operator",
            email = "operator@example.test",
            preferredLanguage = "en-US",
            tenantId = "tenant-1",
            tenantSlug = "tenant",
            workspaceId = "workspace-1",
            workspaceSlug = "cold-chain",
            membershipId = "membership-1",
            surface = "PLATFORM",
        ),
    )

    private class FakeSessionStore(
        private val failOnWrite: Boolean = false,
    ) : SessionStore {
        var material: SessionMaterial? = null
        var clearWasAttempted = false

        override suspend fun read(): SessionMaterial? = material

        override suspend fun write(material: SessionMaterial) {
            if (failOnWrite) error("simulated storage failure")
            this.material = material
        }

        override suspend fun clear() {
            clearWasAttempted = true
            material = null
        }
    }
}
