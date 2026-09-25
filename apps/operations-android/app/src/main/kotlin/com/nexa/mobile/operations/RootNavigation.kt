package com.nexa.mobile.operations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackBanner
import com.nexa.mobile.operations.core.designsystem.NexaFeedbackTone
import com.nexa.mobile.operations.feature.access.AccessNotice
import com.nexa.mobile.operations.feature.access.AccessScreen
import com.nexa.mobile.operations.feature.access.AccessStage
import com.nexa.mobile.operations.feature.access.AccessUiState
import com.nexa.mobile.operations.feature.access.ContextChooserMode
import com.nexa.mobile.operations.feature.access.ContextChooserPhase
import com.nexa.mobile.operations.feature.access.ContextChooserScreen
import com.nexa.mobile.operations.feature.access.R as AccessR
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
        val backStack = remember(destination) { mutableStateListOf<Any>(destination) }
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
                    ProductDestination.Access -> NavEntry(entryKey) {
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

                    ProductDestination.ContextChooser -> NavEntry(entryKey) {
                        ContextChooserScreen(
                            state = accessState.chooser ?: AccessUiState().chooserFallback(),
                            notice = accessState.notice,
                            onSelect = onSelectContext,
                            onRetry = onRetry,
                            onBack = onContextBack,
                            onClearLocalSession = onLogout
                        )
                    }

                    ProductDestination.WorkEntry -> NavEntry(entryKey) {
                        WarehouseContentNotice(accessState.notice) {
                            OperationsWorkEntryScreen(
                                state = warehouseState,
                                onChangeContext = onChangeContext,
                                onIdentifyProduct = onIdentifyProduct,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    ProductDestination.ProductSearch -> NavEntry(entryKey) {
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

                    ProductDestination.ConfirmedSku -> NavEntry(entryKey) {
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
    }
}

@Composable
private fun WarehouseContentNotice(
    notice: AccessNotice?,
    content: @Composable ColumnScope.() -> Unit
) {
    val resource = when (notice) {
        AccessNotice.ContextSelectionRejected -> AccessR.string.access_notice_context_rejected
        AccessNotice.ContextSelectionUnavailable -> AccessR.string.access_notice_context_unavailable
        else -> null
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (resource != null) {
            NexaFeedbackBanner(
                message = androidx.compose.ui.res.stringResource(resource),
                tone = NexaFeedbackTone.Warning,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        content()
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
        stage = if (stage == AccessStage.NoContexts) stage else AccessStage.IdentityRequired,
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
