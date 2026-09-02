package com.nexa.mobile.operations

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.core.network.ApiClientSurface
import com.nexa.mobile.core.network.ApiError
import com.nexa.mobile.core.network.ApiErrorCategory
import com.nexa.mobile.core.network.ApiResult
import com.nexa.mobile.core.network.CurrentSession
import com.nexa.mobile.core.network.NativeAccessClient
import com.nexa.mobile.core.network.NativeAuthentication
import com.nexa.mobile.core.network.NativeCatalogClient
import com.nexa.mobile.core.network.NativeRefreshCredentials
import com.nexa.mobile.core.storage.KeystoreSessionStore
import com.nexa.mobile.core.storage.SessionMaterial
import com.nexa.mobile.core.storage.SessionStore
import com.nexa.mobile.feature.access.AccessNavigation
import com.nexa.mobile.feature.access.ConfirmedSessionContext
import com.nexa.mobile.feature.access.LaunchViewModel
import com.nexa.mobile.feature.access.SessionConfirmation
import com.nexa.mobile.feature.access.SessionRevocation
import com.nexa.mobile.feature.access.RetrySafety
import com.nexa.mobile.feature.access.SessionRefreshCoordinator
import com.nexa.mobile.feature.access.SignInGateway
import com.nexa.mobile.feature.access.SignInViewModel
import com.nexa.mobile.feature.warehouse.SkuIdentifierResolver
import com.nexa.mobile.feature.warehouse.WarehouseScreen
import com.nexa.mobile.feature.warehouse.WarehouseViewModel

class MainActivity : ComponentActivity() {
    private val sessionStore: SessionStore by lazy {
        KeystoreSessionStore(applicationContext)
    }

    private val nativeAccessClient: NativeAccessClient? by lazy {
        BuildConfig.NEXA_API_BASE_URL
            .takeIf(String::isNotBlank)
            ?.let { baseUrl -> runCatching { NativeAccessClient(baseUrl) }.getOrNull() }
    }

    private val nativeCatalogClient: NativeCatalogClient? by lazy {
        BuildConfig.NEXA_API_BASE_URL
            .takeIf(String::isNotBlank)
            ?.let { baseUrl -> runCatching { NativeCatalogClient(baseUrl) }.getOrNull() }
    }

    private val configuredSurface: ApiClientSurface? by lazy {
        BuildConfig.NEXA_API_SURFACE
            .takeIf { it == ApiClientSurface.PLATFORM.wireValue }
            ?.let { ApiClientSurface.PLATFORM }
    }

    private val sessionRefreshCoordinator: SessionRefreshCoordinator? by lazy {
        createSessionRefreshCoordinator()
    }

    private val launchViewModel: LaunchViewModel by lazy {
        ViewModelProvider(
            this,
            LaunchViewModel.Factory(
                sessionStore = sessionStore,
                sessionConfirmation = createSessionConfirmation(),
                sessionRevocation = createSessionRevocation(),
            ),
        )[LaunchViewModel::class.java]
    }

    private val signInViewModel: SignInViewModel by lazy {
        ViewModelProvider(
            this,
            SignInViewModel.Factory(
                gateway = nativeAccessClient?.let { client ->
                    SignInGateway { identifier, password, workspaceSlug, surface ->
                        client.signIn(identifier, password, workspaceSlug, surface)
                    }
                },
                surface = configuredSurface,
                sessionStore = sessionStore,
            ),
        )[SignInViewModel::class.java]
    }

    private val warehouseViewModel: WarehouseViewModel by lazy {
        ViewModelProvider(
            this,
            WarehouseViewModel.Factory(
                resolver = nativeCatalogClient?.let { client ->
                    val coordinator = sessionRefreshCoordinator
                    SkuIdentifierResolver { identifier, accessToken ->
                        if (coordinator == null) {
                            client.resolveSku(identifier, accessToken)
                        } else {
                            coordinator.execute(retrySafety = RetrySafety.SAFE) { currentAccessToken ->
                                client.resolveSku(identifier, currentAccessToken)
                            }
                        }
                    }
                },
                sessionStore = sessionStore,
            ),
        )[WarehouseViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val warehouseState by warehouseViewModel.uiState.collectAsStateWithLifecycle()
            AccessNavigation(
                viewModel = launchViewModel,
                signInViewModel = signInViewModel,
                warehouseEntry = { onBack ->
                    WarehouseScreen(
                        state = warehouseState,
                        onIdentifierChanged = warehouseViewModel::onIdentifierChanged,
                        onStartCamera = warehouseViewModel::startScanning,
                        onCameraPermissionDenied = warehouseViewModel::onCameraPermissionDenied,
                        onCameraUnavailable = warehouseViewModel::onCameraUnavailable,
                        onBarcodeDetected = warehouseViewModel::onBarcodeDetected,
                        onResolveManually = warehouseViewModel::resolveManually,
                        onBack = onBack,
                    )
                },
            )
        }
    }

    private fun createSessionConfirmation(): SessionConfirmation? {
        val client = nativeAccessClient ?: return null
        val surface = configuredSurface ?: return null
        val coordinator = sessionRefreshCoordinator ?: return null
        return SessionConfirmation { session ->
            if (session.surface != surface.wireValue) {
                ApiResult.Failure(
                    ApiError(
                        category = ApiErrorCategory.FORBIDDEN,
                        code = "AUTH_SURFACE_MISMATCH",
                    ),
                )
            } else {
                coordinator.execute(retrySafety = RetrySafety.SAFE) { accessToken ->
                    when (val confirmation = client.currentSession(accessToken)) {
                        is ApiResult.Success -> confirmation.value.toConfirmedSessionContext(surface)
                        is ApiResult.Failure -> confirmation
                    }
                }
            }
        }
    }

    private fun createSessionRefreshCoordinator(): SessionRefreshCoordinator? {
        val client = nativeAccessClient ?: return null
        val surface = configuredSurface ?: return null
        return SessionRefreshCoordinator(sessionStore) { session ->
            when (val refreshed = client.refresh(
                NativeRefreshCredentials(
                    accessToken = session.accessToken,
                    refreshToken = session.refreshToken,
                    surface = surface,
                ),
            )) {
                is ApiResult.Success -> ApiResult.Success(refreshed.value.toSessionMaterial())
                is ApiResult.Failure -> refreshed
            }
        }
    }

    private fun createSessionRevocation(): SessionRevocation? {
        val client = nativeAccessClient ?: return null
        val surface = configuredSurface ?: return null
        return SessionRevocation { session ->
            if (session.surface != surface.wireValue) {
                ApiResult.Failure(
                    ApiError(
                        category = ApiErrorCategory.FORBIDDEN,
                        code = "AUTH_SURFACE_MISMATCH",
                    ),
                )
            } else {
                client.signOut(
                    NativeRefreshCredentials(
                        accessToken = session.accessToken,
                        refreshToken = session.refreshToken,
                        surface = surface,
                    ),
                )
            }
        }
    }
}

private fun NativeAuthentication.toSessionMaterial(): SessionMaterial = SessionMaterial(
    accessToken = accessToken,
    refreshToken = refreshToken,
    accessTokenExpiresAtEpochSeconds =
        (System.currentTimeMillis() / 1_000L) + expiresInSeconds,
    surface = surface.wireValue,
)

internal fun CurrentSession.toConfirmedSessionContext(
    expectedSurface: ApiClientSurface,
): ApiResult<ConfirmedSessionContext> {
    if (surface != expectedSurface.wireValue) {
        return ApiResult.Failure(
            ApiError(
                category = ApiErrorCategory.FORBIDDEN,
                code = "AUTH_SURFACE_MISMATCH",
            ),
        )
    }
    if (user.displayName.isBlank() || user.email.isBlank() ||
        tenant.tenantSlug.isBlank() || workspace.workspaceSlug.isBlank() ||
        membership.membershipId.isBlank()
    ) {
        return ApiResult.Failure(
            ApiError(
                category = ApiErrorCategory.UNKNOWN,
                code = "SESSION_CONTEXT_MISSING",
            ),
        )
    }
    return ApiResult.Success(
        ConfirmedSessionContext(
            user = ConfirmedSessionContext.User(
                displayName = user.displayName,
                email = user.email,
                preferredLanguage = user.preferredLanguage,
            ),
            tenant = ConfirmedSessionContext.Tenant(tenant.tenantSlug),
            workspace = ConfirmedSessionContext.Workspace(workspace.workspaceSlug),
            roles = membership.roles.toSet(),
            capabilities = membership.permissions.toSet(),
        ),
    )
}
