package com.nexa.mobile.operations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.WarehouseCatalogFilterChoice
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.WarehouseCatalogFilterKind
import com.nexa.mobile.operations.catalogcommercialpolicy.domain.model.warehouse.ConfirmedScannedSku
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.adapters.CatalogOperationsContextAdapter
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ConfirmedSkuScreen
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductScannerScreen
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductScannerUiState
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductSearchScreen
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.OperationsWorkEntryScreen
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WarehouseRoute
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WarehouseUiState
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.designsystem.LocalNexaProfileAction
import com.nexa.mobile.operations.core.designsystem.NexaActiveContextBar
import com.nexa.mobile.operations.core.designsystem.NexaTaskRow
import com.nexa.mobile.operations.core.designsystem.NexaWorkSummary
import com.nexa.mobile.operations.core.designsystem.R as DesignR
import com.nexa.mobile.operations.tenantaccessgovernance.application.access.OtherSessionsRevocationResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.access.OwnPasswordChangeResult
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.WorkforceContextSummary
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.profile.OwnProfile
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessNotice
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessScreen
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessStage
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessUiState
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.ContextChooserMode
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.ContextChooserPhase
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.ContextChooserScreen
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.ContextChooserUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

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
@OptIn(ExperimentalMaterial3Api::class)
internal fun RootNavigation(
    state: SessionState,
    accessState: AccessUiState,
    warehouseState: WarehouseUiState,
    ownProfileContent: (@Composable (() -> Unit) -> Unit)? = null,
    ownProfile: OwnProfile? = null,
    onProfileOpened: () -> Unit = {},
    dashboard: OperationsDashboardSnapshot? = null,
    dashboardChangeSignals: Flow<Unit> = emptyFlow(),
    onDashboardOpened: () -> Unit = {},
    onDashboardRefresh: () -> Unit = {},
    onIdentifierChanged: (String) -> Unit = {},
    onPasswordChanged: (String) -> Unit = {},
    onPasswordVisibilityChanged: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onRecoveryEmailChanged: (String) -> Unit = {},
    onRequestPasswordReset: () -> Unit = {},
    onChangePassword: suspend (String, String) -> OwnPasswordChangeResult = { _, _ ->
        OwnPasswordChangeResult.Unavailable
    },
    onRevokeOtherSessions: suspend () -> OtherSessionsRevocationResult = {
        OtherSessionsRevocationResult.Unavailable
    },
    onRetry: () -> Unit = {},
    onLogout: () -> Unit = {},
    onNavigateHome: () -> Unit = {},
    onSelectContext: (String) -> Unit = {},
    onContextBack: () -> Unit = {},
    onChangeContext: () -> Unit = {},
    onIdentifyProduct: () -> Unit = {},
    onScanProductCode: () -> Unit = {},
    onWarehouseBack: () -> Unit = {},
    onSearch: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onQueryChanged: (String) -> Unit = {},
    onOpenFilterOptions: (WarehouseCatalogFilterKind) -> Unit = {},
    onCloseFilterOptions: () -> Unit = {},
    onFilterSearchChanged: (String) -> Unit = {},
    onLoadFilterOptions: () -> Unit = {},
    onLoadMoreFilterOptions: () -> Unit = {},
    onRetryFilterOptions: () -> Unit = {},
    onSelectFilter: (
        WarehouseCatalogFilterKind,
        WarehouseCatalogFilterChoice?
    ) -> Unit = { _, _ -> },
    onSelectCandidate: (String) -> Unit = {},
    scannerState: ProductScannerUiState = ProductScannerUiState.PermissionNotRequested(0),
    scannerCameraPreview: @Composable (Modifier) -> Unit = {},
    onScannerPermissionRequest: () -> Unit = {},
    onScannerOpenSettings: () -> Unit = {},
    onScannerRetry: () -> Unit = {},
    onScannerManualSearch: () -> Unit = {},
    onReceiveStock: () -> Unit = {},
    onReceiveScannedProduct: ((ConfirmedScannedSku) -> Unit)? = null,
    receivingContent: @Composable () -> Unit = {},
    onViewStock: () -> Unit = {},
    onViewIdentifiedStorage: ((String) -> Unit)? = null,
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
    val activeAuthority = ConnectedOperationsNavigation.currentAuthority(
        state,
        accessState,
        warehouseState
    )
    val protectedContentAllowed = activeAuthority != null

    val onWarehouseBackState = rememberUpdatedState(onWarehouseBack)
    LaunchedEffect(
        state,
        accessState.stage,
        accessState.authorityEpoch,
        accessState.activeContext,
        warehouseState.permissionHint,
        warehouseState.workEntryStatus,
        warehouseState.route,
        warehouseState.authorityEpoch,
        connectedOperationRoute?.entryKey,
        setOf(WarehouseRoute.StockCondition, WarehouseRoute.Picking, WarehouseRoute.ProductSearch)
            .filterTo(linkedSetOf()) { route ->
                OperationsCapabilities.permitsEntry(route, state, accessState, warehouseState)
            }
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
    val visibleConnectedEntries = ConnectedOperationsNavigation.visibleEntries(
        connectedOperationEntries,
        state,
        accessState,
        warehouseState
    )
    val activeRoles = activeAuthority?.roles.orEmpty()
    val activeRoleLabel = operationsRoleLabel(activeRoles)
    val navigationSections = operationsNavigationSections(
        visibleConnectedEntries,
        activeRoles,
        connectedOperationRoute?.entryKey,
        warehouseRoutes = setOf(
            WarehouseRoute.StockCondition,
            WarehouseRoute.Picking,
            WarehouseRoute.ProductSearch
        ).filter { route ->
            OperationsCapabilities.permitsEntry(route, state, accessState, warehouseState)
        }.toSet()
    )
    val quickActionsDescription = stringResource(R.string.work_nav_actions)
    val quickActionKeys = setOf(
        "warehouse.batch", "warehouse.temperature", "warehouse.cycle-count",
        "dispatch.readiness", "dispatch.assignment", "dispatch.loads", "driver.deliveries",
        "driver.handoff-code", "commercial.direct-order", "commercial.request",
        "operations.notifications"
    )
    val quickActionEntries = if (warehouseState.route == WarehouseRoute.WorkEntry &&
        connectedOperationRoute == null
    ) {
        visibleConnectedEntries.filter { entry ->
            entry.key in quickActionKeys &&
                navigationSections.none { section -> section.entry?.key == entry.key }
        }
    } else {
        emptyList()
    }
    val workspaceChromeVisible = protectedContentAllowed && accessState.chooser == null
    val warehouseEntryVisible =
        warehouseState.route in setOf(WarehouseRoute.WorkEntry, WarehouseRoute.StockCondition) &&
            connectedOperationRoute == null
    val warehouseScanQuickActionAvailable = warehouseEntryVisible &&
        OperationsCapabilities.permitsEntry(
            WarehouseRoute.Scanner,
            state,
            accessState,
            warehouseState
        )
    val warehouseReceiveQuickActionAvailable = warehouseEntryVisible &&
        OperationsCapabilities.permitsEntry(
            WarehouseRoute.Receiving,
            state,
            accessState,
            warehouseState
        )
    val hasWarehouseQuickActions = warehouseScanQuickActionAvailable ||
        warehouseReceiveQuickActionAvailable
    val quickActionsAvailable = quickActionEntries.isNotEmpty() || hasWarehouseQuickActions
    var ownProfileVisible by remember(accessState.authorityEpoch) { mutableStateOf(false) }
    var profileSheetVisible by remember(accessState.authorityEpoch) {
        mutableStateOf(false)
    }
    var passwordChangeDialogVisible by remember(accessState.authorityEpoch) {
        mutableStateOf(false)
    }
    var revokeSessionsDialogVisible by remember(accessState.authorityEpoch) {
        mutableStateOf(false)
    }
    var quickActionsVisible by remember(accessState.authorityEpoch) {
        mutableStateOf(false)
    }

    val destination = when {
        protectedContentAllowed && connectedOperationRoute != null &&
            ConnectedOperationsNavigation.permits(
                connectedOperationRoute,
                connectedOperationEntries,
                state,
                accessState,
                warehouseState
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

    NexaSystemBars(lightSurfaceVisible = true)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (workspaceChromeVisible) {
                val selectedIndex = when {
                    destination == ProductDestination.WorkEntry -> 0

                    connectedOperationRoute != null ->
                        navigationSections.indexOfFirst {
                            connectedOperationRoute.entryKey in it.destinationKeys
                        }.let { index -> if (index >= 0) index + 1 else 0 }

                    warehouseState.route != WarehouseRoute.WorkEntry ->
                        navigationSections.indexOfFirst {
                            it.warehouseRoute == when (warehouseState.route) {
                                WarehouseRoute.Receiving,
                                WarehouseRoute.StockCondition -> WarehouseRoute.StockCondition

                                WarehouseRoute.Scanner,
                                WarehouseRoute.ConfirmedSku,
                                WarehouseRoute.ProductSearch -> WarehouseRoute.ProductSearch

                                else -> warehouseState.route
                            }
                        }.let { index -> if (index >= 0) index + 1 else 0 }

                    else -> 0
                }
                OperationsWorkspaceNavigationBar(
                    selectedIndex = selectedIndex,
                    sections = navigationSections,
                    onHome = onNavigateHome,
                    onOpen = onOpenConnectedOperation,
                    onWarehouseRoute = { route ->
                        when (route) {
                            WarehouseRoute.StockCondition -> onViewStock()
                            WarehouseRoute.Picking -> onPickStock()
                            WarehouseRoute.ProductSearch -> onIdentifyProduct()
                            else -> Unit
                        }
                    }
                )
            }
        }

    ) { contentPadding ->
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
                        ProductDestination.WorkEntry,
                        ProductDestination.ConnectedOperation
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
                            val displayedState = accessState.forSession(
                                state,
                                protectedContentAllowed
                            )
                            AccessScreen(
                                state = displayedState,
                                onIdentifierChanged = onIdentifierChanged,
                                onPasswordChanged = onPasswordChanged,
                                onPasswordVisibilityChanged = onPasswordVisibilityChanged,
                                onSignIn = onSignIn,
                                onRecoveryEmailChanged = onRecoveryEmailChanged,
                                onRequestPasswordReset = onRequestPasswordReset,
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
                                OperationsDashboardAutoRefresh(
                                    authorityEpoch = accessState.authorityEpoch,
                                    scopeKey = accessState.activeContext?.key.orEmpty(),
                                    roleKey = activeRoles.sorted().joinToString(","),
                                    changeSignals = dashboardChangeSignals,
                                    onOpen = onDashboardOpened,
                                    onRefresh = onDashboardRefresh
                                )
                                OperationsHomeScreen(
                                    company = accessState.activeContext?.companyName.orEmpty(),
                                    workspace = accessState.activeContext?.workspaceName.orEmpty(),
                                    role = activeRoleLabel.orEmpty(),
                                    entries = visibleConnectedEntries,
                                    dashboard = dashboard,
                                    onRefresh = onDashboardRefresh,
                                    onQuickActions = if (quickActionsAvailable) {
                                        (
                                            {
                                                quickActionsVisible =
                                                    true
                                            }
                                            )
                                    } else {
                                        null
                                    },
                                    onOpen = onOpenConnectedOperation,
                                    onReceive = if (OperationsCapabilities.permitsEntry(
                                            WarehouseRoute.Receiving,
                                            state,
                                            accessState,
                                            warehouseState
                                        )
                                    ) {
                                        onReceiveStock
                                    } else {
                                        null
                                    },
                                    onPick = if (OperationsCapabilities.permitsEntry(
                                            WarehouseRoute.Picking,
                                            state,
                                            accessState,
                                            warehouseState
                                        )
                                    ) {
                                        onPickStock
                                    } else {
                                        null
                                    },
                                    onStock = if (OperationsCapabilities.permitsEntry(
                                            WarehouseRoute.StockCondition,
                                            state,
                                            accessState,
                                            warehouseState
                                        )
                                    ) {
                                        onViewStock
                                    } else {
                                        null
                                    }
                                )
                            }
                        }

                        ProductDestination.ProductSearch -> {
                            val search = warehouseState.search
                            if (search != null && searchAllowed) {
                                WarehouseContentNotice(accessState.notice) {
                                    ProductSearchScreen(
                                        state = search,
                                        activeContext = warehouseState.activeContext?.let(
                                            CatalogOperationsContextAdapter::from
                                        ),
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
                                        onOpenFilterOptions = { kind ->
                                            if (OperationsCapabilities.permits(
                                                    WarehouseRoute.ProductSearch,
                                                    state,
                                                    accessState,
                                                    warehouseState
                                                )
                                            ) {
                                                onOpenFilterOptions(kind)
                                            }
                                        },
                                        onCloseFilterOptions = {
                                            if (OperationsCapabilities.permits(
                                                    WarehouseRoute.ProductSearch,
                                                    state,
                                                    accessState,
                                                    warehouseState
                                                )
                                            ) {
                                                onCloseFilterOptions()
                                            }
                                        },
                                        onFilterSearchChanged = { value ->
                                            if (OperationsCapabilities.permits(
                                                    WarehouseRoute.ProductSearch,
                                                    state,
                                                    accessState,
                                                    warehouseState
                                                )
                                            ) {
                                                onFilterSearchChanged(value)
                                            }
                                        },
                                        onLoadFilterOptions = {
                                            if (OperationsCapabilities.permits(
                                                    WarehouseRoute.ProductSearch,
                                                    state,
                                                    accessState,
                                                    warehouseState
                                                )
                                            ) {
                                                onLoadFilterOptions()
                                            }
                                        },
                                        onLoadMoreFilterOptions = {
                                            if (OperationsCapabilities.permits(
                                                    WarehouseRoute.ProductSearch,
                                                    state,
                                                    accessState,
                                                    warehouseState
                                                )
                                            ) {
                                                onLoadMoreFilterOptions()
                                            }
                                        },
                                        onRetryFilterOptions = {
                                            if (OperationsCapabilities.permits(
                                                    WarehouseRoute.ProductSearch,
                                                    state,
                                                    accessState,
                                                    warehouseState
                                                )
                                            ) {
                                                onRetryFilterOptions()
                                            }
                                        },
                                        onSelectFilter = { kind, choice ->
                                            if (OperationsCapabilities.permits(
                                                    WarehouseRoute.ProductSearch,
                                                    state,
                                                    accessState,
                                                    warehouseState
                                                )
                                            ) {
                                                onSelectFilter(kind, choice)
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
                                    onIdentifyProduct = onIdentifyProduct,
                                    onOpenProfile = {
                                        onProfileOpened()
                                        profileSheetVisible = true
                                    }
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
                                    activeContext = warehouseState.activeContext?.let(
                                        CatalogOperationsContextAdapter::from
                                    ),
                                    cameraPreview = scannerCameraPreview,
                                    onBack = onWarehouseBack,
                                    onChangeContext = onChangeContext,
                                    onRequestPermission = onScannerPermissionRequest,
                                    onOpenSettings = onScannerOpenSettings,
                                    onRetryScan = onScannerRetry,
                                    onManualSearch = onScannerManualSearch,
                                    onViewStorage = onViewIdentifiedStorage,
                                    onReceiveProduct = onReceiveScannedProduct
                                )
                            } else {
                                OperationsWorkEntryScreen(
                                    state = warehouseState,
                                    onChangeContext = onChangeContext,
                                    onIdentifyProduct = onIdentifyProduct,
                                    onScanProductCode = onScanProductCode,
                                    onOpenProfile = {
                                        onProfileOpened()
                                        profileSheetVisible = true
                                    }
                                )
                            }
                        }

                        else -> error("Unknown Product destination")
                    }
                }
            )
            CompositionLocalProvider(
                LocalNexaProfileAction provides if (workspaceChromeVisible) {
                    {
                        onProfileOpened()
                        profileSheetVisible = true
                    }
                } else {
                    null
                }
            ) {
                NavDisplay(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .consumeWindowInsets(contentPadding),
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
                            ProductDestination.ConnectedOperation,
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
    }
    if (workspaceChromeVisible && profileSheetVisible) {
        OperationsProfileSheet(
            profile = ownProfile?.takeIf { it.userId == activeAuthority?.userId },
            fallbackEmail = accessState.identifier,
            company = accessState.activeContext?.companyName.orEmpty(),
            role = activeRoleLabel.orEmpty(),
            onDismiss = { profileSheetVisible = false },
            onDetails = if (ownProfileContent !=
                null
            ) {
                (
                    {
                        profileSheetVisible = false
                        ownProfileVisible = true
                    }
                    )
            } else {
                null
            },
            onPassword = { passwordChangeDialogVisible = true },
            onOtherSessions = { revokeSessionsDialogVisible = true },
            onChangeWorkspace = {
                profileSheetVisible = false
                onChangeContext()
            },
            onLogout = {
                profileSheetVisible = false
                onLogout()
            }
        )
    }
    if (workspaceChromeVisible && ownProfileVisible && ownProfileContent != null) {
        ownProfileContent { ownProfileVisible = false }
    }
    if (workspaceChromeVisible && passwordChangeDialogVisible) {
        OwnPasswordChangeDialog(
            onDismiss = { passwordChangeDialogVisible = false },
            onChangePassword = onChangePassword
        )
    }
    if (workspaceChromeVisible && revokeSessionsDialogVisible) {
        OtherSessionsRevocationDialog(
            onDismiss = { revokeSessionsDialogVisible = false },
            onRevoke = onRevokeOtherSessions
        )
    }
    if (workspaceChromeVisible && quickActionsVisible && quickActionsAvailable) {
        ModalBottomSheet(onDismissRequest = { quickActionsVisible = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.work_nav_actions),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    stringResource(R.string.work_nav_actions_support),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (warehouseScanQuickActionAvailable) {
                    NexaTaskRow(
                        title = stringResource(R.string.warehouse_quick_scan_title),
                        description = stringResource(R.string.warehouse_quick_scan_support),
                        iconResource = DesignR.drawable.ic_barcode,
                        onClick = {
                            quickActionsVisible = false
                            onScanProductCode()
                        }
                    )
                }
                if (warehouseReceiveQuickActionAvailable) {
                    NexaTaskRow(
                        title = stringResource(R.string.warehouse_quick_receive_title),
                        description = stringResource(R.string.warehouse_quick_receive_support),
                        iconResource = DesignR.drawable.ic_truck,
                        onClick = {
                            quickActionsVisible = false
                            onReceiveStock()
                        }
                    )
                }
                quickActionEntries.forEach { entry ->
                    NexaTaskRow(
                        title = entry.label,
                        description = stringResource(entry.descriptionResource()),
                        iconResource = entry.workIconResource(),
                        onClick = {
                            quickActionsVisible = false
                            onOpenConnectedOperation(entry)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun OperationsIdentityCard(
    context: WorkforceContextSummary?,
    roleLabel: String?,
    onChangeContext: () -> Unit
) {
    context ?: return
    NexaActiveContextBar(
        companyName = context.companyName,
        workspaceName = context.workspaceName,
        tenantLabel = stringResource(R.string.work_nav_tenant),
        workspaceLabel = stringResource(R.string.work_nav_workspace),
        roleLabel = roleLabel ?: stringResource(R.string.work_nav_role_missing),
        compact = true,
        enabled = true,
        onClick = onChangeContext
    )
}

@Composable
private fun operationsRoleLabel(roleCodes: Set<String>): String? {
    val normalized = roleCodes.map { it.trim().uppercase() }
        .filter(String::isNotEmpty)
        .distinct()
        .sorted()
    if (normalized.isEmpty()) return null
    if (normalized.size > 1) {
        return stringResource(
            R.string.work_nav_role_multiple,
            normalized.joinToString(" · ") { it.replace('_', ' ') }
        )
    }
    val roleCode = normalized.single()
    val resource = operationsRoleResource(roleCode)
    return if (resource != null) {
        stringResource(resource)
    } else {
        stringResource(R.string.work_nav_role_unmapped, roleCode)
    }
}

private fun operationsRoleResource(roleCode: String): Int? = when (roleCode) {
    "COMPANY_OWNER" -> R.string.work_role_company_owner
    "TENANT_ADMIN", "TENANT_ADMINISTRATOR" -> R.string.work_role_tenant_admin
    "BOM", "BUSINESS_OPERATIONS_MANAGER" -> R.string.work_role_bom
    "SALES", "SALES_REPRESENTATIVE", "COMMERCIAL" -> R.string.work_role_sales
    "WAREHOUSE", "WAREHOUSE_OPERATOR" -> R.string.work_role_warehouse
    "LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR" -> R.string.work_role_dispatch
    "DRIVER" -> R.string.work_role_driver
    else -> null
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

private fun AccessUiState.chooserFallback() = ContextChooserUiState(
    mode = ContextChooserMode.Initial,
    phase = ContextChooserPhase.Loading
)
