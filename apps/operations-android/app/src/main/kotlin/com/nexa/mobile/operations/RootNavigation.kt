package com.nexa.mobile.operations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.nexa.mobile.operations.feature.access.PermissionHint
import com.nexa.mobile.operations.feature.warehouse.ConfirmedSkuScreen
import com.nexa.mobile.operations.feature.warehouse.OperationsWorkEntryScreen
import com.nexa.mobile.operations.feature.warehouse.ProductScannerScreen
import com.nexa.mobile.operations.feature.warehouse.ProductScannerUiState
import com.nexa.mobile.operations.feature.warehouse.ProductSearchScreen
import com.nexa.mobile.operations.feature.warehouse.TaskVisibilityHint
import com.nexa.mobile.operations.feature.warehouse.WarehouseRoute
import com.nexa.mobile.operations.feature.warehouse.WarehouseUiState
import com.nexa.mobile.operations.feature.warehouse.WorkEntryStatus

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
    ConfirmedSku,
    Receiving,
    StockCondition,
    Picking,
    ConnectedOperation,
    Scanner
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
    onScanProductCode: () -> Unit = {},
    onWarehouseBack: () -> Unit = {},
    onSearch: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onQueryChanged: (String) -> Unit = {},
    onSelectCandidate: (String) -> Unit = {},
    scannerState: ProductScannerUiState = ProductScannerUiState.PermissionNotRequested(0),
    scannerCameraPreview: @Composable (Modifier) -> Unit = {},
    onScannerPermissionRequest: () -> Unit = {},
    onScannerOpenSettings: () -> Unit = {},
    onScannerRetry: () -> Unit = {},
    onScannerManualSearch: () -> Unit = {},
    onReceiveStock: () -> Unit = {},
    receivingContent: @Composable () -> Unit = {},
    onViewStock: () -> Unit = {},
    stockConditionContent: @Composable () -> Unit = {},
    onPickStock: () -> Unit = {},
    pickingContent: @Composable () -> Unit = {},
    connectedOperationRoute: ConnectedOperationRoute? = null,
    connectedOperationEntries: List<ConnectedOperationEntry> = emptyList(),
    onOpenConnectedOperation: (ConnectedOperationEntry) -> Unit = {},
    connectedOperationContent: @Composable () -> Unit = {},
    additionalWorkContent: @Composable () -> Unit = {},
    onConnectedOperationBack: () -> Unit = {}
) {
    val isSessionActive = state == SessionState.Active
    val currentContext = accessState.activeContext
    val warehouseContextMatches =
        currentContext != null &&
            warehouseState.activeContext?.authorityEpoch == accessState.authorityEpoch
    val protectedContentAllowed =
        isSessionActive && accessState.stage == AccessStage.WorkAuthorized &&
            currentContext?.isCurrent == true && warehouseContextMatches

    val onWarehouseBackState = rememberUpdatedState(onWarehouseBack)
    LaunchedEffect(
        state,
        accessState.stage,
        accessState.authorityEpoch,
        currentContext,
        warehouseState.permissionHint,
        warehouseState.workEntryStatus,
        warehouseState.route,
        warehouseState.authorityEpoch,
        connectedOperationRoute?.entryKey
    ) {
        val permissionLostAtCurrentEpoch =
            state == SessionState.Active &&
                accessState.stage == AccessStage.WorkAuthorized &&
                warehouseState.authorityEpoch == accessState.authorityEpoch &&
                !OperationsCapabilities.permitsEntry(
                    warehouseState.route,
                    state,
                    accessState,
                    warehouseState
                )
        if (permissionLostAtCurrentEpoch && warehouseState.route != WarehouseRoute.WorkEntry) {
            repeat(OperationsCapabilities.deepRouteInvalidationBackCount(warehouseState.route)) {
                onWarehouseBackState.value()
            }
        }
    }

    val searchAllowed = OperationsCapabilities.permits(
        WarehouseRoute.ProductSearch,
        state,
        accessState,
        warehouseState
    )
    val skuAllowed = OperationsCapabilities.permits(
        WarehouseRoute.ConfirmedSku,
        state,
        accessState,
        warehouseState
    )
    val scannerAllowed = OperationsCapabilities.permits(
        WarehouseRoute.Scanner,
        state,
        accessState,
        warehouseState,
        scannerState
    )

    val destination = when {
        protectedContentAllowed && connectedOperationRoute != null &&
            ConnectedOperationsNavigation.permits(
                connectedOperationRoute, connectedOperationEntries, state, accessState, warehouseState
            ) -> ProductDestination.ConnectedOperation
        protectedContentAllowed -> when (warehouseState.route) {
            WarehouseRoute.WorkEntry -> ProductDestination.WorkEntry

            WarehouseRoute.ProductSearch -> if (searchAllowed) {
                ProductDestination.ProductSearch
            } else {
                ProductDestination.WorkEntry
            }

            WarehouseRoute.ConfirmedSku -> if (skuAllowed) {
                ProductDestination.ConfirmedSku
            } else {
                ProductDestination.WorkEntry
            }

            WarehouseRoute.Picking -> if (OperationsCapabilities.permitsEntry(
                    WarehouseRoute.Picking,
                    state,
                    accessState,
                    warehouseState
                )
            ) {
                ProductDestination.Picking
            } else {
                ProductDestination.WorkEntry
            }

            WarehouseRoute.StockCondition -> if (OperationsCapabilities.permitsEntry(
                    WarehouseRoute.StockCondition,
                    state,
                    accessState,
                    warehouseState
                )
            ) {
                ProductDestination.StockCondition
            } else {
                ProductDestination.WorkEntry
            }

            WarehouseRoute.Receiving -> if (OperationsCapabilities.permitsEntry(
                    WarehouseRoute.Receiving,
                    state,
                    accessState,
                    warehouseState
                )
            ) {
                ProductDestination.Receiving
            } else {
                ProductDestination.WorkEntry
            }

            WarehouseRoute.Scanner -> if (scannerAllowed) {
                ProductDestination.Scanner
            } else {
                ProductDestination.WorkEntry
            }
        }

        accessState.chooser != null -> ProductDestination.ContextChooser

        else -> ProductDestination.Access
    }

    NexaSystemBars(authCanopyVisible = destination == ProductDestination.Access)

    key(
        state.rootDestination(),
        destination,
        accessState.authorityEpoch,
        warehouseState.authorityEpoch,
        connectedOperationRoute?.entryKey
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

                ProductDestination.ConnectedOperation -> mutableStateListOf<Any>(
                    ProductDestination.WorkEntry, ProductDestination.ConnectedOperation
                )

                ProductDestination.Picking -> mutableStateListOf<Any>(
                    ProductDestination.WorkEntry,
                    ProductDestination.Picking
                )

                ProductDestination.StockCondition -> mutableStateListOf<Any>(
                    ProductDestination.WorkEntry,
                    ProductDestination.StockCondition
                )

                ProductDestination.Receiving -> mutableStateListOf<Any>(
                    ProductDestination.WorkEntry,
                    ProductDestination.Receiving
                )

                ProductDestination.Scanner -> mutableStateListOf<Any>(
                    ProductDestination.WorkEntry,
                    ProductDestination.Scanner
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
                                state = if (protectedContentAllowed) {
                                    warehouseState
                                } else {
                                    warehouseState.copy(activeContext = null)
                                },
                                additionalWorkContent = {
                                    ConnectedOperationsEntries(
                                        entries = ConnectedOperationsNavigation.visibleEntries(
                                            connectedOperationEntries, state, accessState, warehouseState
                                        ),
                                        onOpen = onOpenConnectedOperation
                                    )
                                    additionalWorkContent()
                                },
                                onPickStock = {
                                    if (OperationsCapabilities.permitsEntry(
                                            WarehouseRoute.Picking,
                                            state,
                                            accessState,
                                            warehouseState
                                        )
                                    ) {
                                        onPickStock()
                                    }
                                },
                                onViewStock = {
                                    if (OperationsCapabilities.permitsEntry(
                                            WarehouseRoute.StockCondition,
                                            state,
                                            accessState,
                                            warehouseState
                                        )
                                    ) {
                                        onViewStock()
                                    }
                                },
                                onReceiveStock = {
                                    if (OperationsCapabilities.permitsEntry(
                                            WarehouseRoute.Receiving,
                                            state,
                                            accessState,
                                            warehouseState
                                        )
                                    ) {
                                        onReceiveStock()
                                    }
                                },
                                onChangeContext = onChangeContext,
                                onIdentifyProduct = {
                                    if (OperationsCapabilities.permitsEntry(
                                            WarehouseRoute.ProductSearch,
                                            state,
                                            accessState,
                                            warehouseState
                                        )
                                    ) {
                                        onIdentifyProduct()
                                    }
                                },
                                onScanProductCode = {
                                    if (OperationsCapabilities.permitsEntry(
                                            WarehouseRoute.Scanner,
                                            state,
                                            accessState,
                                            warehouseState
                                        )
                                    ) {
                                        onScanProductCode()
                                    }
                                },
                                capabilities = OperationsCapabilities.workEntryCapabilities(
                                    state,
                                    accessState,
                                    warehouseState
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    ProductDestination.ProductSearch -> {
                        val search = warehouseState.search
                        if (search != null && searchAllowed) {
                            WarehouseContentNotice(accessState.notice) {
                                ProductSearchScreen(
                                    state = search,
                                    activeContext = warehouseState.activeContext,
                                    onChangeContext = onChangeContext,
                                    onBack = onWarehouseBack,
                                    onQueryChanged = { query ->
                                        if (OperationsCapabilities.permits(
                                                WarehouseRoute.ProductSearch,
                                                state,
                                                accessState,
                                                warehouseState
                                            )
                                        ) {
                                            onQueryChanged(query)
                                        }
                                    },
                                    onSearch = {
                                        if (OperationsCapabilities.permits(
                                                WarehouseRoute.ProductSearch,
                                                state,
                                                accessState,
                                                warehouseState
                                            )
                                        ) {
                                            onSearch()
                                        }
                                    },
                                    onLoadMore = {
                                        if (OperationsCapabilities.permits(
                                                WarehouseRoute.ProductSearch,
                                                state,
                                                accessState,
                                                warehouseState
                                            )
                                        ) {
                                            onLoadMore()
                                        }
                                    },
                                    onSelectCandidate = { key ->
                                        if (OperationsCapabilities.permits(
                                                WarehouseRoute.ProductSearch,
                                                state,
                                                accessState,
                                                warehouseState
                                            )
                                        ) {
                                            onSelectCandidate(key)
                                        }
                                    },
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
                        if (confirmed != null && skuAllowed) {
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

                    ProductDestination.Receiving -> receivingContent()

                    ProductDestination.StockCondition -> stockConditionContent()

                    ProductDestination.Picking -> pickingContent()

                    ProductDestination.ConnectedOperation -> connectedOperationContent()

                    ProductDestination.Scanner -> {
                        if (scannerAllowed) {
                            ProductScannerScreen(
                                state = scannerState,
                                activeContext = warehouseState.activeContext,
                                cameraPreview = scannerCameraPreview,
                                onBack = onWarehouseBack,
                                onChangeContext = onChangeContext,
                                onRequestPermission = onScannerPermissionRequest,
                                onOpenSettings = onScannerOpenSettings,
                                onRetryScan = onScannerRetry,
                                onManualSearch = onScannerManualSearch
                            )
                        } else {
                            OperationsWorkEntryScreen(
                                state = warehouseState,
                                onChangeContext = onChangeContext,
                                onIdentifyProduct = onIdentifyProduct,
                                onScanProductCode = onScanProductCode
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
                    ProductDestination.ConnectedOperation -> onConnectedOperationBack()

                    ProductDestination.ProductSearch,
                    ProductDestination.ConfirmedSku,
                    ProductDestination.Receiving,
                    ProductDestination.StockCondition,
                    ProductDestination.Picking,
                    ProductDestination.Scanner -> onWarehouseBack()

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
                    ProductDestination.ConfirmedSku,
                    ProductDestination.Receiving,
                    ProductDestination.StockCondition,
                    ProductDestination.Picking,
                    ProductDestination.Scanner -> NavEntry(entryKey) {
                        if (entryKey == destination) currentEntryRenderer.value(entryKey)
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
