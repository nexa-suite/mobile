package com.nexa.mobile.feature.access

import com.nexa.mobile.core.network.ApiClientSurface
import com.nexa.mobile.core.network.ApiError
import com.nexa.mobile.core.network.ApiErrorCategory
import com.nexa.mobile.core.network.ApiResult
import com.nexa.mobile.core.network.AuthenticationSession
import com.nexa.mobile.core.network.NativeAuthentication
import com.nexa.mobile.core.network.WorkspacePreview
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
    fun publicWorkspacePreviewWorksWithoutSurfaceAndDoesNotPersistSecretMaterial() = runTest(dispatcher.scheduler) {
        val previewResult = CompletableDeferred<ApiResult<WorkspacePreview>>()
        val store = FakeSessionStore()
        var previewCalls = 0
        var signInCalls = 0
        val viewModel = SignInViewModel(
            gateway = SignInGateway { _, _, _, _ ->
                signInCalls += 1
                ApiResult.Success(authentication())
            },
            surface = null,
            sessionStore = store,
            workspacePreviewGateway = WorkspacePreviewGateway { workspaceSlug ->
                assertEquals("icisa-test", workspaceSlug)
                previewCalls += 1
                previewResult.await()
            },
        )

        viewModel.onWorkspaceChanged(" icisa-test ")
        viewModel.onPasswordChanged("secret-not-persisted")
        viewModel.submit()
        runCurrent()

        assertEquals(SignInStatus.Loading, viewModel.formState.value.status)
        assertEquals(WorkspacePreviewState.Loading, viewModel.formState.value.workspacePreview)
        assertEquals("", viewModel.formState.value.password)
        assertEquals(1, previewCalls)
        assertEquals(0, signInCalls)

        previewResult.complete(ApiResult.Success(workspacePreview()))
        advanceUntilIdle()

        val ready = viewModel.formState.value.workspacePreview as WorkspacePreviewState.Ready
        assertEquals(SignInStatus.Idle, viewModel.formState.value.status)
        assertEquals("icisa-test", ready.workspaceSlug)
        assertEquals("ICISA", ready.preview.displayName)
        assertNull(store.material)

        viewModel.onIdentifierChanged("operator@example.test")
        viewModel.onPasswordChanged("secret-not-persisted")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(SignInStatus.Blocked, viewModel.formState.value.status)
        assertEquals("", viewModel.formState.value.password)
        assertEquals(0, signInCalls)
        assertNull(store.material)
    }

    @Test
    fun recognizedWorkspacePrecedesSignInAndThenStoresOnlyAuthenticatedSession() = runTest(dispatcher.scheduler) {
        var signInCalls = 0
        val store = FakeSessionStore()
        val viewModel = SignInViewModel(
            gateway = SignInGateway { _, _, _, surface ->
                signInCalls += 1
                assertEquals(ApiClientSurface.PLATFORM, surface)
                ApiResult.Success(authentication())
            },
            surface = ApiClientSurface.PLATFORM,
            sessionStore = store,
            workspacePreviewGateway = WorkspacePreviewGateway {
                ApiResult.Success(workspacePreview())
            },
        )

        viewModel.onWorkspaceChanged("icisa-test")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(SignInStatus.Idle, viewModel.formState.value.status)
        assertEquals(0, signInCalls)
        viewModel.onIdentifierChanged("operator@example.test")
        viewModel.onPasswordChanged("transient-password")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(SignInStatus.Authenticated, viewModel.formState.value.status)
        assertEquals(1, signInCalls)
        assertEquals("access-token", store.material?.accessToken)
    }

    @Test
    fun unknownWorkspaceRemainsPublicAndNeverCallsSignIn() = runTest(dispatcher.scheduler) {
        var signInCalls = 0
        val viewModel = SignInViewModel(
            gateway = SignInGateway { _, _, _, _ ->
                signInCalls += 1
                ApiResult.Success(authentication())
            },
            surface = ApiClientSurface.PLATFORM,
            sessionStore = FakeSessionStore(),
            workspacePreviewGateway = WorkspacePreviewGateway {
                ApiResult.Success(
                    workspacePreview(
                        recognized = false,
                        displayName = null,
                        loginAvailable = false,
                    ),
                )
            },
        )

        viewModel.onWorkspaceChanged("unknown-workspace")
        viewModel.submit()
        advanceUntilIdle()

        val ready = viewModel.formState.value.workspacePreview as WorkspacePreviewState.Ready
        assertEquals(SignInStatus.Idle, viewModel.formState.value.status)
        assertEquals(false, ready.preview.recognized)
        assertEquals(false, ready.preview.loginAvailable)
        assertEquals(0, signInCalls)
    }

    @Test
    fun workspacePreviewFailureMapsToRetryableAccessState() = runTest(dispatcher.scheduler) {
        val viewModel = SignInViewModel(
            gateway = null,
            surface = null,
            sessionStore = FakeSessionStore(),
            workspacePreviewGateway = WorkspacePreviewGateway {
                ApiResult.Failure(ApiError(category = ApiErrorCategory.NETWORK, retryable = true))
            },
        )
        viewModel.onWorkspaceChanged("icisa-test")

        viewModel.submit()
        advanceUntilIdle()

        assertEquals(SignInStatus.Idle, viewModel.formState.value.status)
        assertEquals(
            WorkspacePreviewState.Failed(WorkspacePreviewFailure.NETWORK),
            viewModel.formState.value.workspacePreview,
        )
    }

    @Test
    fun changingWorkspaceInvalidatesPreviousPreviewBeforeNextSubmit() = runTest(dispatcher.scheduler) {
        val viewModel = SignInViewModel(
            gateway = null,
            surface = null,
            sessionStore = FakeSessionStore(),
            workspacePreviewGateway = WorkspacePreviewGateway {
                ApiResult.Success(workspacePreview())
            },
        )
        viewModel.onWorkspaceChanged("icisa-test")
        viewModel.submit()
        advanceUntilIdle()
        assertEquals(WorkspacePreviewState.Ready::class, viewModel.formState.value.workspacePreview::class)

        viewModel.onWorkspaceChanged("another-workspace")

        assertEquals(SignInStatus.Idle, viewModel.formState.value.status)
        assertEquals(WorkspacePreviewState.Idle, viewModel.formState.value.workspacePreview)
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
    fun passwordIsClearedBeforeGatewayCompletesAndSuccessIsStored() = runTest(dispatcher.scheduler) {
        val result = CompletableDeferred<ApiResult<NativeAuthentication>>()
        var capturedPassword: String? = null
        val store = FakeSessionStore()
        val viewModel = SignInViewModel(
            gateway = SignInGateway { _, password, _, _ ->
                capturedPassword = password
                result.await()
            },
            surface = ApiClientSurface.PLATFORM,
            sessionStore = store,
        )
        fillForm(viewModel)

        viewModel.submit()
        runCurrent()

        assertEquals(SignInStatus.Loading, viewModel.formState.value.status)
        assertEquals("", viewModel.formState.value.password)
        assertEquals("transient-password", capturedPassword)

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

    private fun workspacePreview(
        recognized: Boolean = true,
        displayName: String? = "ICISA",
        loginAvailable: Boolean = true,
    ): WorkspacePreview = WorkspacePreview(
        recognized = recognized,
        displayName = displayName,
        workspaceUrl = "https://icisa.example.test",
        logoUrl = null,
        loginAvailable = loginAvailable,
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
