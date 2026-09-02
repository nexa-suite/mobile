package com.nexa.mobile.operations

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.nexa.mobile.core.network.ApiClientSurface
import com.nexa.mobile.core.network.ApiError
import com.nexa.mobile.core.network.ApiErrorCategory
import com.nexa.mobile.core.network.ApiResult
import com.nexa.mobile.core.network.NativeAccessClient
import com.nexa.mobile.core.network.NativeRefreshCredentials
import com.nexa.mobile.core.storage.KeystoreSessionStore
import com.nexa.mobile.core.storage.SessionMaterial
import com.nexa.mobile.core.storage.SessionStore
import com.nexa.mobile.feature.access.AccessNavigation
import com.nexa.mobile.feature.access.LaunchViewModel
import com.nexa.mobile.feature.access.SessionConfirmation
import com.nexa.mobile.feature.access.SignInGateway
import com.nexa.mobile.feature.access.SignInViewModel

class MainActivity : ComponentActivity() {
    private val sessionStore: SessionStore by lazy {
        KeystoreSessionStore(applicationContext)
    }

    private val nativeAccessClient: NativeAccessClient? by lazy {
        BuildConfig.NEXA_API_BASE_URL
            .takeIf(String::isNotBlank)
            ?.let(::NativeAccessClient)
    }

    private val configuredSurface: ApiClientSurface? by lazy {
        ApiClientSurface.parse(BuildConfig.NEXA_API_SURFACE)
    }

    private val launchViewModel: LaunchViewModel by lazy {
        ViewModelProvider(
            this,
            LaunchViewModel.Factory(
                sessionStore = sessionStore,
                sessionConfirmation = createSessionConfirmation(),
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AccessNavigation(viewModel = launchViewModel, signInViewModel = signInViewModel)
        }
    }

    private fun createSessionConfirmation(): SessionConfirmation? {
        val client = nativeAccessClient ?: return null
        val surface = configuredSurface ?: return null
        return SessionConfirmation { session ->
            if (session.surface != surface.wireValue) {
                ApiResult.Failure(
                    ApiError(
                        category = ApiErrorCategory.FORBIDDEN,
                        code = "AUTH_SURFACE_MISMATCH",
                    ),
                )
            } else {
                confirmOrRefresh(client, sessionStore, session, surface)
            }
        }
    }
}

private suspend fun confirmOrRefresh(
    client: NativeAccessClient,
    sessionStore: SessionStore,
    session: SessionMaterial,
    surface: ApiClientSurface,
): ApiResult<Unit> {
    return when (val confirmation = client.currentSession(session.accessToken)) {
        is ApiResult.Success -> {
            if (confirmation.value.surface == surface.wireValue) {
                ApiResult.Success(Unit)
            } else {
                ApiResult.Failure(
                    ApiError(
                        category = ApiErrorCategory.FORBIDDEN,
                        code = "AUTH_SURFACE_MISMATCH",
                    ),
                )
            }
        }
        is ApiResult.Failure -> {
            if (confirmation.error.category != ApiErrorCategory.UNAUTHORIZED) {
                confirmation
            } else {
                refreshSession(client, sessionStore, session, surface)
            }
        }
    }
}

private suspend fun refreshSession(
    client: NativeAccessClient,
    sessionStore: SessionStore,
    session: SessionMaterial,
    surface: ApiClientSurface,
): ApiResult<Unit> {
    val refreshed = client.refresh(
        NativeRefreshCredentials(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            surface = surface,
        ),
    )
    if (refreshed is ApiResult.Failure) {
        runCatching { sessionStore.clear() }
        return refreshed
    }

    val authentication = (refreshed as ApiResult.Success).value
    return runCatching {
        sessionStore.write(
            SessionMaterial(
                accessToken = authentication.accessToken,
                refreshToken = authentication.refreshToken,
                accessTokenExpiresAtEpochSeconds =
                    (System.currentTimeMillis() / 1_000L) + authentication.expiresInSeconds,
                surface = authentication.surface.wireValue,
            ),
        )
        ApiResult.Success(Unit)
    }.getOrElse {
        runCatching { sessionStore.clear() }
        ApiResult.Failure(
            ApiError(
                category = ApiErrorCategory.UNKNOWN,
                code = "SESSION_STORAGE_UNAVAILABLE",
            ),
        )
    }
}
