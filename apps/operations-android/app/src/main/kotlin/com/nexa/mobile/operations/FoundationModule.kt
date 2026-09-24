package com.nexa.mobile.operations

import android.content.Context
import com.nexa.mobile.operations.core.auth.credentials.AndroidRefreshCredentialStore
import com.nexa.mobile.operations.core.auth.credentials.RefreshCredentialStore
import com.nexa.mobile.operations.core.auth.session.AccessTokenSource
import com.nexa.mobile.operations.core.auth.session.AuthRemoteGateway
import com.nexa.mobile.operations.core.auth.session.SessionCoordinator
import com.nexa.mobile.operations.core.network.ApiEndpoint
import com.nexa.mobile.operations.core.network.ApiHttpClient
import com.nexa.mobile.operations.core.network.NexaAuthGateway
import com.nexa.mobile.operations.core.network.ProtectedCallExecutor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object FoundationModule {
    @Provides
    @Singleton
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun credentialStore(@ApplicationContext context: Context): RefreshCredentialStore =
        AndroidRefreshCredentialStore(context)

    @Provides
    @Singleton
    fun endpoint(): ApiEndpoint = ApiEndpoint(BuildConfig.API_BASE_URL)

    @Provides
    @Singleton
    fun httpClient(endpoint: ApiEndpoint): OkHttpClient = ApiHttpClient.create(endpoint)

    @Provides
    @Singleton
    fun remoteGateway(endpoint: ApiEndpoint, client: OkHttpClient): AuthRemoteGateway =
        NexaAuthGateway.create(endpoint, client)

    @Provides
    @Singleton
    fun sessionCoordinator(
        store: RefreshCredentialStore,
        remote: AuthRemoteGateway,
        scope: CoroutineScope
    ): SessionCoordinator = SessionCoordinator(store, remote, scope)

    @Provides
    fun accessTokenSource(coordinator: SessionCoordinator): AccessTokenSource = coordinator

    @Provides
    @Singleton
    fun protectedCalls(
        endpoint: ApiEndpoint,
        client: OkHttpClient,
        tokens: AccessTokenSource
    ): ProtectedCallExecutor = ProtectedCallExecutor(endpoint, client, tokens)
}
