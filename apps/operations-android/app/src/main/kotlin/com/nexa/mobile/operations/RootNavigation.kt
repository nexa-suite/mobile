package com.nexa.mobile.operations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.feature.access.AccessNotice
import com.nexa.mobile.operations.feature.access.AccessScreen
import com.nexa.mobile.operations.feature.access.AccessStage
import com.nexa.mobile.operations.feature.access.AccessUiState
import com.nexa.mobile.operations.feature.access.ContextChooserMode
import com.nexa.mobile.operations.feature.access.ContextChooserPhase
import com.nexa.mobile.operations.feature.access.ContextChooserScreen
import com.nexa.mobile.operations.feature.warehouse.ConfirmedSkuScreen
import com.nexa.mobile.operations.feature.warehouse.OperationsWorkEntryScreen
import com.nexa.mobile.operations.feature.warehouse.ProductSearchScreen
import com.nexa.mobile.operations.feature.warehouse.WarehouseRoute
import com.nexa.mobile.operations.feature.warehouse.WarehouseUiState

internal enum class RootDestination {
    Bootstrapping,
    SignedOut,
    Restoring,
    ContextRequired,
    Active,
    ReauthenticationRequired,
    LocalProtectionError
}

internal enum class ProductDestination {
    Access,
    ContextChooser,
    WorkEntry,
    ProductSearch,
    ConfirmedSku
}

internal fun SessionState.rootDestination(): RootDestination = when (this) {
    SessionState.Bootstrapping -> RootDestination.Bootstrapping
    SessionState.SignedOut -> RootDestination.SignedOut
    is SessionState.Restoring -> RootDestination.Restoring
    SessionState.ContextRequired -> RootDestination.ContextRequired
    SessionState.Active -> RootDestination.Active
    SessionState.ReauthenticationRequired -> RootDestination.ReauthenticationRequired
    SessionState.LocalProtectionError -> RootDestination.LocalProtectionError
}

/** Product content is reachable only from an active session and a confirmed context. */
@Composable
internal fun RootNavigation(
    state: SessionState,
    accessState: AccessUiState,
    warehouseState: WarehouseUiState,
    onIdentifierChanged: (String) -> Unit = {},
    onPasswordChanged: (String) -> Unit = {},
    onPasswordVisibilityChanged: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onRetry: () -> Unit = {},
    onLogout: () -> Unit = {},
    onSelectContext: (String) -> Unit = {},
    onContextBack: () -> Unit = {},
    onChangeContext: () -> Unit = {},
    onIdentifyProduct: () -> Unit = {},
    onWarehouseBack: () -> Unit = {},
    onSearch: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onQueryChanged: (String) -> Unit = {},
    onSelectCandidate: (String) -> Unit = {}
) {
    val isSessionActive = state == SessionState.Active
    val currentContext = accessState.activeContext
    val warehouseContextMatches =
        currentContext != null &&
            warehouseState.activeContext?.authorityEpoch == accessState.authorityEpoch
    val protectedContentAllowed =
        isSessionActive && accessState.stage == AccessStage.WorkAuthorized &&
            currentContext != null && warehouseContextMatches

    val destination = when {
        protectedContentAllowed -> when (warehouseState.route) {
            WarehouseRoute.WorkEntry -> ProductDestination.WorkEntry

            WarehouseRoute.ProductSearch -> ProductDestination.ProductSearch

            WarehouseRoute.ConfirmedSku -> if (
                warehouseState.confirmedSku?.authorityEpoch == accessState.authorityEpoch
            ) {
                ProductDestination.ConfirmedSku
            } else {
                ProductDestination.WorkEntry
            }
        }

        accessState.chooser != null -> ProductDestination.ContextChooser

        else -> ProductDestination.Access
    }

    key(
        state.rootDestination(),
        destination,
        accessState.authorityEpoch,
        warehouseState.authorityEpoch
    ) {
        val backStack = remember(destination, accessState.chooser?.phase) {
            when (destination) {
                ProductDestination.ProductSearch -> mutableStateListOf<Any>(
                    ProductDestination.WorkEntry,
                    ProductDestination.ProductSearch
                )

                ProductDestination.ConfirmedSku -> mutableStateListOf<Any>(
                    ProductDestination.WorkEntry,
                    ProductDestination.ProductSearch,
                    ProductDestination.ConfirmedSku
                )

                ProductDestination.ContextChooser -> if (
                    isSessionActive &&
                    accessState.chooser?.mode == ContextChooserMode.Change &&
                    accessState.chooser?.phase != ContextChooserPhase.SelectionPending
                ) {
                    mutableStateListOf<Any>(ProductDestination.WorkEntry, destination)
                } else {
                    mutableStateListOf<Any>(destination)
                }

                else -> mutableStateListOf<Any>(destination)
            }
        }
        val currentEntryRenderer = rememberUpdatedState<@Composable (Any) -> Unit>(
            newValue = { entryKey ->
                when (entryKey) {
                    ProductDestination.Access -> {
                        val displayedState = accessState.forSession(state, protectedContentAllowed)
                        AccessScreen(
                            state = displayedState,
                            onIdentifierChanged = onIdentifierChanged,
                            onPasswordChanged = onPasswordChanged,
                            onPasswordVisibilityChanged = onPasswordVisibilityChanged,
                            onSignIn = onSignIn,
                            onRetry = onRetry,
                            onClearLocalSession = onLogout,
                            showRetry =
                                (state as? SessionState.Restoring)?.canRetryConnection == true
                        )
                    }

                    ProductDestination.ContextChooser -> {
                        ContextChooserScreen(
                            state = accessState.chooser ?: AccessUiState().chooserFallback(),
                            notice = accessState.notice,
                            onSelect = onSelectContext,
                            onRetry = onRetry,
                            onBack = onContextBack,
                            onClearLocalSession = onLogout
                        )
                    }

                    ProductDestination.WorkEntry -> {
                        WarehouseContentNotice(accessState.notice) {
                            OperationsWorkEntryScreen(
                                state = warehouseState,
                                onChangeContext = onChangeContext,
                                onIdentifyProduct = onIdentifyProduct,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    ProductDestination.ProductSearch -> {
                        val search = warehouseState.search
                        if (search != null && protectedContentAllowed) {
                            WarehouseContentNotice(accessState.notice) {
                                ProductSearchScreen(
                                    state = search,
                                    activeContext = warehouseState.activeContext,
                                    onChangeContext = onChangeContext,
                                    onBack = onWarehouseBack,
                                    onQueryChanged = onQueryChanged,
                                    onSearch = onSearch,
                                    onLoadMore = onLoadMore,
                                    onSelectCandidate = onSelectCandidate,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            AccessScreen(
                                state = AccessUiState(stage = AccessStage.ResolvingContexts),
                                onIdentifierChanged = {},
                                onPasswordChanged = {},
                                onPasswordVisibilityChanged = {},
                                onSignIn = onSignIn
                            )
                        }
                    }

                    ProductDestination.ConfirmedSku -> {
                        val confirmed = warehouseState.confirmedSku
                        if (confirmed != null && protectedContentAllowed) {
                            WarehouseContentNotice(accessState.notice) {
                                ConfirmedSkuScreen(
                                    state = confirmed,
                                    onChangeContext = onChangeContext,
                                    onBack = onWarehouseBack,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            OperationsWorkEntryScreen(
                                state = warehouseState,
                                onChangeContext = onChangeContext,
                                onIdentifyProduct = onIdentifyProduct
                            )
                        }
                    }

                    else -> error("Unknown Product destination")
                }
            }
        )
        NavDisplay(
            backStack = backStack,
            onBack = {
                when (destination) {
                    ProductDestination.ProductSearch,
                    ProductDestination.ConfirmedSku -> onWarehouseBack()

                    ProductDestination.ContextChooser -> {
                        val chooser = accessState.chooser
                        if (
                            chooser?.mode == ContextChooserMode.Change &&
                            chooser.phase != ContextChooserPhase.SelectionPending
                        ) {
                            onContextBack()
                        }
                    }

                    ProductDestination.Access, ProductDestination.WorkEntry -> Unit
                }
            },
            entryProvider = { entryKey ->
                when (entryKey) {
                    ProductDestination.Access,
                    ProductDestination.ContextChooser,
                    ProductDestination.WorkEntry,
                    ProductDestination.ProductSearch,
                    ProductDestination.ConfirmedSku -> NavEntry(entryKey) {
                        currentEntryRenderer.value(entryKey)
                    }

                    else -> error("Unknown Product destination")
                }
            }
        )
    }
}

private fun AccessUiState.forSession(
    session: SessionState,
    protectedContentAllowed: Boolean
): AccessUiState = when (session) {
    SessionState.Bootstrapping, is SessionState.Restoring -> copy(
        stage = AccessStage.RestoringSession,
        chooser = null,
        activeContext = null
    )

    SessionState.SignedOut -> copy(
        stage = when (stage) {
            AccessStage.Authenticating, AccessStage.NoContexts -> stage
            else -> AccessStage.IdentityRequired
        },
        chooser = null,
        activeContext = null
    )

    SessionState.ReauthenticationRequired -> copy(
        stage = AccessStage.SessionExpired,
        notice = AccessNotice.SessionExpired,
        chooser = null,
        activeContext = null
    )

    SessionState.LocalProtectionError -> copy(
        stage = AccessStage.LocalProtectionError,
        chooser = null,
        activeContext = null
    )

    SessionState.ContextRequired -> copy(
        stage = AccessStage.IdentityRequired,
        chooser = null,
        activeContext = null
    )

    SessionState.Active -> if (!protectedContentAllowed && activeContext == null) {
        copy(
            stage = if (stage == AccessStage.IdentityRequired) {
                AccessStage.RestoringSession
            } else {
                stage
            },
            chooser = null
        )
    } else {
        this
    }
}

private fun AccessUiState.chooserFallback() =
    com.nexa.mobile.operations.feature.access.ContextChooserUiState(
        mode = ContextChooserMode.Initial,
        phase = ContextChooserPhase.Loading
    )
