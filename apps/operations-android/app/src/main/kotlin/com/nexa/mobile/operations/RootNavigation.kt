package com.nexa.mobile.operations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.WarehouseCatalogFilterChoice
import com.nexa.mobile.operations.catalogcommercialpolicy.application.model.warehouse.WarehouseCatalogFilterKind
import com.nexa.mobile.operations.catalogcommercialpolicy.infrastructure.adapters.CatalogOperationsContextAdapter
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ConfirmedSkuScreen
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductScannerScreen
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductScannerUiState
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.warehouse.ProductSearchScreen
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.OperationsWorkEntryScreen
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WarehouseRoute
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WarehouseUiState
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.designsystem.NexaActiveContextBar
import com.nexa.mobile.operations.core.designsystem.NexaTaskRow
import com.nexa.mobile.operations.core.designsystem.NexaWorkSummary
import com.nexa.mobile.operations.tenantaccessgovernance.application.access.OtherSessionsRevocationResult
import com.nexa.mobile.operations.tenantaccessgovernance.application.access.OwnPasswordChangeResult
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.WorkforceContextSummary
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessNotice
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessScreen
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessStage
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessUiState
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.ContextChooserMode
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.ContextChooserPhase
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.ContextChooserScreen
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.ContextChooserUiState
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
        connectedOperationRoute?.entryKey
    )
    val quickActionsDescription = stringResource(R.string.work_nav_actions)
    val quickActionEntries = visibleConnectedEntries.filterNot { entry ->
        navigationSections.any { section -> section.entry.key == entry.key }
    }
    val workspaceChromeVisible = protectedContentAllowed && accessState.chooser == null
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
                    profileSheetVisible -> navigationSections.size + 1

                    destination == ProductDestination.WorkEntry -> 0

                    connectedOperationRoute != null ->
                        navigationSections.indexOfFirst {
                            connectedOperationRoute.entryKey in it.destinationKeys
                        }.let { index -> if (index >= 0) index + 1 else 0 }

                    warehouseState.route != WarehouseRoute.WorkEntry ->
                        navigationSections.indexOfFirst {
                            it.entry.key.startsWith("warehouse.")
                        }.let { index -> if (index >= 0) index + 1 else 0 }

                    else -> 0
                }
                OperationsWorkspaceNavigationBar(
                    selectedIndex = selectedIndex,
                    sections = navigationSections,
                    onHome = onNavigateHome,
                    onOpen = onOpenConnectedOperation,
                    onProfile = { profileSheetVisible = true }
                )
            }
        },
        floatingActionButton = {
            if (workspaceChromeVisible && quickActionEntries.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { quickActionsVisible = true },
                    modifier = Modifier.semantics {
                        contentDescription = quickActionsDescription
                    }
                ) {
                    Text("+", modifier = Modifier.clearAndSetSemantics {})
                }
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
                                if (activeRoles.any {
                                        it in setOf("WAREHOUSE", "WAREHOUSE_OPERATOR")
                                    }
                                ) {
                                    OperationsWorkEntryScreen(
                                        state = warehouseState,
                                        additionalCapabilityLabels =
                                            operationsWorkEntryAdditionalCapabilityLabels(),
                                        tenantLabel = stringResource(R.string.work_nav_tenant),
                                        workspaceLabel = stringResource(
                                            R.string.work_nav_workspace
                                        ),
                                        roleLabel = activeRoleLabel
                                            ?: stringResource(R.string.work_nav_role_missing),
                                        onOpenProfile = { profileSheetVisible = true },
                                        hasAdditionalWork = visibleConnectedEntries.isNotEmpty(),
                                        additionalWorkContent = additionalWorkContent,
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
                                } else if (visibleConnectedEntries.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .padding(horizontal = 16.dp, vertical = 20.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        OperationsIdentityCard(
                                            context = accessState.activeContext,
                                            roleLabel = activeRoleLabel,
                                            onChangeContext = onChangeContext
                                        )
                                        NexaWorkSummary(
                                            title = stringResource(R.string.work_hub_title),
                                            description = stringResource(R.string.work_hub_support)
                                        )
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .padding(horizontal = 16.dp, vertical = 20.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        OperationsIdentityCard(
                                            context = accessState.activeContext,
                                            roleLabel = activeRoleLabel,
                                            onChangeContext = onChangeContext
                                        )
                                        NexaWorkSummary(
                                            title = stringResource(R.string.work_hub_title),
                                            description = stringResource(R.string.work_hub_support)
                                        )
                                    }
                                }
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
                                    onOpenProfile = { profileSheetVisible = true }
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
                                    onViewStorage = onViewIdentifiedStorage
                                )
                            } else {
                                OperationsWorkEntryScreen(
                                    state = warehouseState,
                                    onChangeContext = onChangeContext,
                                    onIdentifyProduct = onIdentifyProduct,
                                    onScanProductCode = onScanProductCode,
                                    onOpenProfile = { profileSheetVisible = true }
                                )
                            }
                        }

                        else -> error("Unknown Product destination")
                    }
                }
            )
            NavDisplay(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
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
    if (workspaceChromeVisible && profileSheetVisible) {
        ModalBottomSheet(onDismissRequest = { profileSheetVisible = false }) {
            val context = accessState.activeContext
            val authority = context?.verifiedAuthority
            val roles = authority?.roles.orEmpty()
            val roleLabel = operationsRoleLabel(roles)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.work_nav_profile_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    stringResource(R.string.work_nav_identity),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    accessState.identifier.ifBlank { authority?.userId.orEmpty() },
                    style = MaterialTheme.typography.bodyLarge
                )
                if (!authority?.userId.isNullOrBlank()) {
                    Text(
                        stringResource(R.string.work_nav_user_id),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(authority.userId, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    stringResource(R.string.work_nav_tenant),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(context?.companyName.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    authority?.tenantId.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.work_nav_workspace),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(context?.workspaceName.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    authority?.workspaceId.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.work_nav_role),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(roleLabel ?: stringResource(R.string.work_nav_role_missing))
                if (!authority?.membershipId.isNullOrBlank()) {
                    Text(
                        stringResource(R.string.work_nav_membership_id),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        authority.membershipId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BiometricProfileSetting()
                OutlinedButton(
                    onClick = { passwordChangeDialogVisible = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.work_nav_change_password))
                }
                OutlinedButton(
                    onClick = { revokeSessionsDialogVisible = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.work_nav_revoke_other_sessions))
                }
                OutlinedButton(
                    onClick = {
                        profileSheetVisible = false
                        onChangeContext()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.work_nav_change_context))
                }
                OutlinedButton(
                    onClick = {
                        profileSheetVisible = false
                        onLogout()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.work_nav_logout))
                }
            }
        }
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
    if (workspaceChromeVisible && quickActionsVisible && quickActionEntries.isNotEmpty()) {
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
                quickActionEntries.forEach { entry ->
                    NexaTaskRow(
                        title = entry.label,
                        description = stringResource(R.string.connected_operation_open_support),
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
    "DISPATCH", "DISPATCH_COORDINATOR" -> R.string.work_role_dispatch
    "DRIVER" -> R.string.work_role_driver
    else -> null
}

@Composable
private fun OtherSessionsRevocationDialog(
    onDismiss: () -> Unit,
    onRevoke: suspend () -> OtherSessionsRevocationResult
) {
    val scope = rememberCoroutineScope()
    val revokedMessage = stringResource(R.string.work_nav_sessions_revoked)
    val expiredMessage = stringResource(R.string.work_nav_password_session_expired)
    val unavailableMessage = stringResource(R.string.work_nav_sessions_unavailable)
    var submitting by remember { mutableStateOf(false) }
    var revoked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.work_nav_revoke_other_sessions)) },
        text = {
            Column {
                Text(
                    if (revoked) {
                        revokedMessage
                    } else {
                        stringResource(R.string.work_nav_sessions_confirmation)
                    }
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    if (revoked) {
                        onDismiss()
                    } else {
                        submitting = true
                        error = null
                        scope.launch {
                            when (onRevoke()) {
                                OtherSessionsRevocationResult.Revoked -> revoked = true

                                OtherSessionsRevocationResult.SessionExpired ->
                                    error =
                                        expiredMessage

                                OtherSessionsRevocationResult.Unavailable ->
                                    error =
                                        unavailableMessage
                            }
                            submitting = false
                        }
                    }
                }
            ) {
                Text(
                    if (revoked) {
                        stringResource(R.string.work_nav_done)
                    } else if (submitting) {
                        stringResource(R.string.work_nav_saving)
                    } else {
                        stringResource(R.string.work_nav_revoke)
                    }
                )
            }
        },
        dismissButton = {
            if (!revoked) {
                TextButton(enabled = !submitting, onClick = onDismiss) {
                    Text(stringResource(R.string.work_nav_cancel))
                }
            }
        }
    )
}

@Composable
private fun OwnPasswordChangeDialog(
    onDismiss: () -> Unit,
    onChangePassword: suspend (String, String) -> OwnPasswordChangeResult
) {
    val scope = rememberCoroutineScope()
    val mismatchMessage = stringResource(R.string.work_nav_password_mismatch)
    val rejectedMessage = stringResource(R.string.work_nav_password_rejected)
    val expiredMessage = stringResource(R.string.work_nav_password_session_expired)
    val unavailableMessage = stringResource(R.string.work_nav_password_unavailable)
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.work_nav_change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (changed) {
                    Text(stringResource(R.string.work_nav_password_changed))
                } else {
                    OutlinedTextField(
                        value = currentPassword,
                        onValueChange = {
                            currentPassword = it
                            error = null
                        },
                        label = { Text(stringResource(R.string.work_nav_current_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !submitting
                    )
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            error = null
                        },
                        label = { Text(stringResource(R.string.work_nav_new_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !submitting
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = {
                            confirmation = it
                            error = null
                        },
                        label = { Text(stringResource(R.string.work_nav_confirm_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !submitting
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && (
                    changed || (
                        currentPassword.isNotEmpty() &&
                            newPassword.isNotEmpty() && confirmation.isNotEmpty()
                        )
                    ),
                onClick = {
                    if (changed) {
                        onDismiss()
                    } else if (newPassword != confirmation) {
                        error = mismatchMessage
                    } else {
                        submitting = true
                        error = null
                        scope.launch {
                            when (onChangePassword(currentPassword, newPassword)) {
                                OwnPasswordChangeResult.Changed -> {
                                    currentPassword = ""
                                    newPassword = ""
                                    confirmation = ""
                                    changed = true
                                }

                                OwnPasswordChangeResult.Rejected -> error = rejectedMessage

                                OwnPasswordChangeResult.SessionExpired -> error = expiredMessage

                                OwnPasswordChangeResult.Unavailable -> error = unavailableMessage
                            }
                            submitting = false
                        }
                    }
                }
            ) {
                Text(
                    if (changed) {
                        stringResource(R.string.work_nav_done)
                    } else if (submitting) {
                        stringResource(R.string.work_nav_saving)
                    } else {
                        stringResource(R.string.work_nav_save)
                    }
                )
            }
        },
        dismissButton = {
            if (!changed) {
                TextButton(enabled = !submitting, onClick = onDismiss) {
                    Text(stringResource(R.string.work_nav_cancel))
                }
            }
        }
    )
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
