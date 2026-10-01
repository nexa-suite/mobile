package com.nexa.mobile.operations

import com.nexa.mobile.operations.commercial.FieldVisitViewModel
import com.nexa.mobile.operations.commercial.FieldVisitScreen
import com.nexa.mobile.operations.commercial.BusinessDocumentsViewModel
import com.nexa.mobile.operations.commercial.BusinessDocumentsScreen
import com.nexa.mobile.operations.visibility.OperationsOverviewScreen
import com.nexa.mobile.operations.commercial.FieldRequestViewModel
import com.nexa.mobile.operations.commercial.FieldRequestScreen
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryViewModel
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryScreen
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.designsystem.OperationsTheme
import com.nexa.mobile.operations.core.device.scanner.CameraXProductCodeScanner
import com.nexa.mobile.operations.feature.access.AccessNotice
import com.nexa.mobile.operations.feature.access.AccessStage
import com.nexa.mobile.operations.feature.access.AccessUiState
import com.nexa.mobile.operations.feature.access.AccessViewModel
import com.nexa.mobile.operations.feature.access.PermissionHint
import com.nexa.mobile.operations.feature.access.WorkforceContextSummary
import com.nexa.mobile.operations.feature.warehouse.ActiveOperationsContext
import com.nexa.mobile.operations.feature.warehouse.ConfirmedReceivingProduct
import com.nexa.mobile.operations.commercial.CommercialCatalogScreen
import com.nexa.mobile.operations.commercial.CommercialCatalogViewModel
import com.nexa.mobile.operations.commercial.CustomerProgressScreen
import com.nexa.mobile.operations.commercial.CustomerProgressViewModel
import com.nexa.mobile.operations.commercial.CommercialAuthority
import com.nexa.mobile.operations.commercial.CustomerSearchScreen
import com.nexa.mobile.operations.commercial.CustomerSearchViewModel
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityContext
import com.nexa.mobile.operations.feature.dispatch.DispatchAuthorityIdentity
import com.nexa.mobile.operations.feature.dispatch.DispatchReadinessScreen
import com.nexa.mobile.operations.feature.dispatch.DispatchReadinessViewModel
import com.nexa.mobile.operations.feature.warehouse.TemperatureEvidenceAuthority
import com.nexa.mobile.operations.feature.warehouse.TemperatureEvidenceScreen
import com.nexa.mobile.operations.feature.warehouse.TemperatureEvidenceViewModel
import com.nexa.mobile.operations.feature.warehouse.DispositionAuthority
import com.nexa.mobile.operations.feature.warehouse.DispositionMetadataStatus
import com.nexa.mobile.operations.feature.warehouse.DispositionScreen
import com.nexa.mobile.operations.feature.warehouse.DispositionViewModel
import com.nexa.mobile.operations.feature.warehouse.PickingAuthority
import com.nexa.mobile.operations.feature.warehouse.PickingWorkListScreen
import com.nexa.mobile.operations.feature.warehouse.PickingWorkListViewModel
import com.nexa.mobile.operations.feature.warehouse.PickingEntryScreen
import com.nexa.mobile.operations.feature.warehouse.PickingScreen
import com.nexa.mobile.operations.feature.warehouse.PickingViewModel
import com.nexa.mobile.operations.feature.warehouse.ProductScannerViewModel
import com.nexa.mobile.operations.feature.warehouse.ReceivingAuthority
import com.nexa.mobile.operations.feature.warehouse.ReceivingProductReference
import com.nexa.mobile.operations.feature.warehouse.ReceivingScreen
import com.nexa.mobile.operations.feature.warehouse.ReceivingViewModel
import com.nexa.mobile.operations.feature.warehouse.StockConditionScreen
import com.nexa.mobile.operations.feature.warehouse.StockConditionViewModel
import com.nexa.mobile.operations.feature.warehouse.TaskVisibilityHint
import com.nexa.mobile.operations.feature.warehouse.VerifiedOperationsIdentity
import com.nexa.mobile.operations.feature.warehouse.WarehouseRoute
import com.nexa.mobile.operations.feature.warehouse.WarehouseViewModel
import com.nexa.mobile.operations.feature.warehouse.WorkEntryStatus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject internal lateinit var receivingBindings: ReceivingGatewayBindings
    private val receivingViewModel: ReceivingViewModel by viewModels {
        receivingBindings.viewModelFactory()
    }

    @Inject internal lateinit var stockConditionFactory: StockConditionViewModelFactory
    private val stockConditionViewModel: StockConditionViewModel by viewModels {
        stockConditionFactory
    }

    @Inject internal lateinit var pickingWorkListBindings: PickingWorkListGatewayBindings
    private val pickingWorkListViewModel: PickingWorkListViewModel by viewModels {
        pickingWorkListBindings.viewModelFactory()
    }
    private var pickingManualEntry by mutableStateOf(false)
    @Inject internal lateinit var pickingBindings: PickingGatewayBindings
    private val pickingViewModel: PickingViewModel by viewModels {
        pickingBindings.viewModelFactory()
    }
    @Inject internal lateinit var dispositionFactory: DispositionViewModelFactory
    private val dispositionViewModel: DispositionViewModel by viewModels { dispositionFactory }
    @Inject internal lateinit var commercialCatalogFactory: CommercialCatalogViewModelFactory
    private val commercialCatalogViewModel: CommercialCatalogViewModel by viewModels { commercialCatalogFactory }
    @Inject internal lateinit var customerProgressFactory: CustomerProgressViewModelFactory
    private val customerProgressViewModel: CustomerProgressViewModel by viewModels { customerProgressFactory }
    @Inject internal lateinit var customerSearchFactory: CustomerSearchViewModelFactory
    private val customerSearchViewModel: CustomerSearchViewModel by viewModels { customerSearchFactory }
    @Inject internal lateinit var temperatureEvidenceBindings: TemperatureEvidenceGatewayBindings
    private val temperatureEvidenceViewModel: TemperatureEvidenceViewModel by viewModels {
        temperatureEvidenceBindings.viewModelFactory()
    }
    @Inject internal lateinit var dispatchReadinessFactory: DispatchReadinessViewModelFactory
    private val dispatchReadinessViewModel: DispatchReadinessViewModel by viewModels { dispatchReadinessFactory }
    @Inject internal lateinit var driverDeliveryBindings: DriverDeliveryGatewayBindings
    private val driverDeliveryViewModel: DriverDeliveryViewModel by viewModels { driverDeliveryBindings.viewModelFactory() }
    @Inject internal lateinit var fieldRequestFactory: FieldRequestViewModelFactory
    private val fieldRequestViewModel: FieldRequestViewModel by viewModels { fieldRequestFactory }
    @Inject internal lateinit var businessDocumentsFactory: BusinessDocumentsViewModelFactory
    private val businessDocumentsViewModel: BusinessDocumentsViewModel by viewModels { businessDocumentsFactory }
    @Inject internal lateinit var fieldVisitFactory: FieldVisitViewModelFactory
    private val fieldVisitViewModel: FieldVisitViewModel by viewModels { fieldVisitFactory }
    private var pendingDispositionLot by mutableStateOf<String?>(null)
    private var connectedRoute by mutableStateOf<ConnectedOperationRoute?>(null)
    private var pickingReference by mutableStateOf("")
    private var pickingOpened by mutableStateOf(false)
    private var pickingWorkListEpoch by mutableStateOf(0L)
    private var choosingReceivingProduct by mutableStateOf(false)

    @Inject internal lateinit var operationsGateway: OperationsAccessGateway

    @Inject internal lateinit var scannerOperationsGateway: ScannerOperationsGateway

    private val viewModel: RootViewModel by viewModels()
    private val accessViewModel: AccessViewModel by viewModels {
        AccessViewModelFactory(operationsGateway)
    }
    private val warehouseViewModel: WarehouseViewModel by viewModels {
        WarehouseViewModelFactory(operationsGateway)
    }
    private val scannerViewModel: ProductScannerViewModel by viewModels {
        ProductScannerViewModelFactory(scannerOperationsGateway)
    }

    private var pendingScannerPermissionReturn by mutableStateOf<PendingScannerPermissionReturn?>(
        null
    )

    override fun onResume() {
        super.onResume()
        if (viewModel.state.value == SessionState.Active) {
            invalidateProtectedContentForForegroundReturn()
        }
        pendingScannerPermissionReturn?.let { pending ->
            pendingScannerPermissionReturn = pending.beginRevalidation().copy(
                permissionGranted = if (pending.source ==
                    ScannerPermissionReturnSource.AppSettings
                ) {
                    cameraPermissionGranted()
                } else {
                    pending.permissionGranted
                },
                permanentlyDenied = if (pending.source ==
                    ScannerPermissionReturnSource.AppSettings
                ) {
                    !cameraPermissionGranted()
                } else {
                    pending.permanentlyDenied
                }
            )
        }
        verifyScannerForegroundReturn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OperationsTheme {
                val cameraPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    val pending = pendingScannerPermissionReturn
                    if (pending != null) {
                        val permanent = !granted &&
                            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                        pendingScannerPermissionReturn = pending.copy(
                            permissionGranted = granted,
                            permanentlyDenied = permanent
                        )
                        if (!pending.foregroundReturnObserved) {
                            beginScannerForegroundRevalidation()
                        }
                    }
                }
                val cameraScanner = remember {
                    CameraXProductCodeScanner(applicationContext)
                }
                DisposableEffect(cameraScanner) {
                    onDispose { cameraScanner.close() }
                }
                val state = viewModel.state.collectAsStateWithLifecycle().value
                val accessState = accessViewModel.state.collectAsStateWithLifecycle().value
                val warehouseState = warehouseViewModel.state.collectAsStateWithLifecycle().value
                val scannerState = scannerViewModel.state.collectAsStateWithLifecycle().value
                val commercialCatalogState by commercialCatalogViewModel.state.collectAsStateWithLifecycle()
                val pickingWorkListState by pickingWorkListViewModel.state.collectAsStateWithLifecycle()
                val customerProgressState by customerProgressViewModel.state.collectAsStateWithLifecycle()
                val customerSearchState by customerSearchViewModel.state.collectAsStateWithLifecycle()
                val temperatureEvidenceState by temperatureEvidenceViewModel.state.collectAsStateWithLifecycle()
                val fieldVisitState by fieldVisitViewModel.state.collectAsStateWithLifecycle()
                val businessDocumentsState by businessDocumentsViewModel.state.collectAsStateWithLifecycle()
                val fieldRequestState by fieldRequestViewModel.state.collectAsStateWithLifecycle()
                val driverDeliveryState by driverDeliveryViewModel.state.collectAsStateWithLifecycle()
                val dispatchReadinessState by dispatchReadinessViewModel.state.collectAsStateWithLifecycle()
                val dispositionState by dispositionViewModel.state.collectAsStateWithLifecycle()
                val pickingState by pickingViewModel.state.collectAsStateWithLifecycle()
                val stockConditionState =
                    stockConditionViewModel.state.collectAsStateWithLifecycle().value
                val receivingState = receivingViewModel.state.collectAsStateWithLifecycle().value
                val scannerReturnContinuationPending =
                    pendingScannerPermissionReturn != null
                val verifiedContext = operationsGateway.currentContext
                    .collectAsStateWithLifecycle(initialValue = null).value
                var previousSessionState by remember { mutableStateOf<SessionState?>(null) }
                var logoutRequested by remember { mutableStateOf(false) }

                androidx.compose.runtime.LaunchedEffect(
                    pendingScannerPermissionReturn,
                    state,
                    accessState
                ) {
                    val pending = pendingScannerPermissionReturn ?: return@LaunchedEffect
                    when (val decision = pending.decision(state, accessState)) {
                        ScannerPermissionReturnDecision.Wait -> Unit

                        ScannerPermissionReturnDecision.Drop -> {
                            pendingScannerPermissionReturn = null
                            scannerViewModel.sessionInvalidated()
                        }

                        is ScannerPermissionReturnDecision.Resume -> {
                            val active = accessState.activeContext
                            val authority = active?.verifiedAuthority
                            if (active == null || authority == null) {
                                pendingScannerPermissionReturn = null
                                scannerViewModel.contextInvalidated()
                                return@LaunchedEffect
                            }
                            pendingScannerPermissionReturn = null
                            val context = active.toActiveOperationsContext(
                                accessState.authorityEpoch
                            )
                            warehouseViewModel.enterOperations(
                                context,
                                when (active.permissionHint) {
                                    PermissionHint.Available -> TaskVisibilityHint.Available
                                    PermissionHint.Unavailable -> TaskVisibilityHint.Unavailable
                                    PermissionHint.Unknown -> TaskVisibilityHint.Unknown
                                }
                            )
                            scannerViewModel.enterOperations(context)
                            scannerViewModel.scannerRouteOpened(
                                permissionGranted = decision.permissionGranted,
                                permanentlyDenied = decision.permanentlyDenied
                            )
                            if (!decision.permissionGranted) {
                                scannerViewModel.cameraPermissionDenied(
                                    decision.permanentlyDenied
                                )
                            }
                            warehouseViewModel.openScanner()
                        }
                    }
                }

                androidx.compose.runtime.LaunchedEffect(state, verifiedContext) {
                    if (state == SessionState.Active) {
                        verifiedContext?.let(accessViewModel::sessionContextRevalidated)
                    }
                }

                androidx.compose.runtime.LaunchedEffect(state) {
                    if (state == SessionState.SignedOut || state == SessionState.ContextRequired) {
                        logoutRequested = false
                    }
                }

                androidx.compose.runtime.LaunchedEffect(state) {
                    if (previousSessionState == SessionState.Active &&
                        state != SessionState.Active
                    ) {
                        accessViewModel.sessionInvalidated(
                            expired =
                                state == SessionState.ReauthenticationRequired
                        )
                        warehouseViewModel.sessionInvalidated()
                        scannerViewModel.sessionInvalidated()
                    }
                    previousSessionState = state
                }

                androidx.compose.runtime.LaunchedEffect(
                    state,
                    accessState.stage,
                    accessState.chooser,
                    accessState.notice,
                    logoutRequested
                ) {
                    if (
                        state == SessionState.Active &&
                        accessState.shouldResolveCurrentContext(logoutRequested)
                    ) {
                        accessViewModel.resolveCurrentSessionContext()
                    }
                }
                androidx.compose.runtime.LaunchedEffect(
                    state,
                    accessState.authorityEpoch,
                    accessState.notice
                ) {
                    if (state == SessionState.Active &&
                        accessState.notice == AccessNotice.UnknownContextOutcome
                    ) {
                        warehouseViewModel.contextInvalidated()
                        viewModel.invalidateContext()
                    }
                }
                androidx.compose.runtime.LaunchedEffect(
                    state,
                    accessState.authorityEpoch,
                    accessState.stage
                ) {
                    if (
                        state == SessionState.Active &&
                        accessState.stage == AccessStage.SessionExpired
                    ) {
                        warehouseViewModel.sessionInvalidated()
                        scannerViewModel.sessionInvalidated()
                        viewModel.logout()
                    }
                }
                androidx.compose.runtime.LaunchedEffect(
                    state,
                    accessState.stage,
                    accessState.authorityEpoch,
                    warehouseState.workEntryStatus,
                    warehouseState.invalidatedFromAuthorityEpoch
                ) {
                    when (activeWarehouseInvalidation(state, accessState, warehouseState)) {
                        WorkEntryStatus.ContextInvalidated -> {
                            accessViewModel.contextInvalidated()
                            viewModel.invalidateContext()
                        }

                        WorkEntryStatus.SessionInvalidated -> {
                            accessViewModel.sessionInvalidated(expired = true)
                            viewModel.logout()
                        }

                        else -> Unit
                    }
                }
                androidx.compose.runtime.LaunchedEffect(
                    state,
                    accessState.stage,
                    accessState.activeContext,
                    accessState.authorityEpoch,
                    scannerReturnContinuationPending
                ) {
                    val context = accessState.activeContext
                    if (
                        state == SessionState.Active &&
                        accessState.stage == AccessStage.WorkAuthorized &&
                        context != null &&
                        !scannerReturnContinuationPending &&
                        warehouseState.activeContext?.authorityEpoch !=
                        accessState.authorityEpoch
                    ) {
                        warehouseViewModel.enterOperations(
                            context = context.toActiveOperationsContext(
                                accessState.authorityEpoch
                            ),
                            permissionHint = context.operationsVisibilityHint()
                        )
                    }
                }

                androidx.compose.runtime.LaunchedEffect(
                    state,
                    accessState.authorityEpoch,
                    accessState.activeContext
                ) {
                    val authority = accessState.activeContext?.verifiedAuthority
                    if (state != SessionState.Active ||
                        accessState.stage != AccessStage.WorkAuthorized ||
                        authority == null || authority.permissions.none {
                            it == "inventory.receive" || it == "warehouse:write"
                        }
                    ) {
                        choosingReceivingProduct = false
                        receivingViewModel.invalidate()
                    }
                }
                androidx.compose.runtime.LaunchedEffect(
                    state,
                    accessState.authorityEpoch,
                    accessState.activeContext
                ) {
                    val permissions = accessState.activeContext?.verifiedAuthority?.permissions
                    if (state != SessionState.Active || permissions == null ||
                        permissions.none {
                            it in
                                setOf("warehouse.read", "inventory.read", "warehouse:read")
                        } ||
                        stockConditionState.authorityEpoch != accessState.authorityEpoch
                    ) {
                        stockConditionViewModel.invalidateContext()
                    }
                }
                androidx.compose.runtime.LaunchedEffect(
                    warehouseState.confirmedSku,
                    choosingReceivingProduct
                ) {
                    val product = warehouseState.confirmedSku
                    if (choosingReceivingProduct && product != null &&
                        product.authorityEpoch == accessState.authorityEpoch
                    ) {
                        receivingViewModel.selectProduct(
                            ConfirmedReceivingProduct(
                                ReceivingProductReference(
                                    product.candidateKey,
                                    null,
                                    product.productDisplayName,
                                    product.sku,
                                    product.unit.orEmpty()
                                ),
                                product.authorityEpoch
                            )
                        )
                        choosingReceivingProduct = false
                        warehouseViewModel.openReceiving()
                    }
                }

                androidx.compose.runtime.LaunchedEffect(
                    state,
                    accessState.authorityEpoch,
                    accessState.activeContext
                ) {
                    val authority = accessState.activeContext?.verifiedAuthority
                    if (state != SessionState.Active ||
                        accessState.stage != AccessStage.WorkAuthorized || authority == null ||
                        authority.permissions.none {
                            it == "fulfillment.read" ||
                                it == "fulfillment:read"
                        } ||
                        (pickingOpened && pickingState.authorityEpoch != accessState.authorityEpoch) ||
                        (pickingWorkListEpoch > 0 &&
                            pickingWorkListEpoch != accessState.authorityEpoch)
                    ) {
                        pickingViewModel.invalidate()
                        pickingWorkListViewModel.invalidate()
                        pickingManualEntry = false
                        pickingReference = ""
                        pickingOpened = false
                    }
                }
                androidx.compose.runtime.LaunchedEffect(
                    state, accessState.authorityEpoch, accessState.activeContext,
                    warehouseState.authorityEpoch, warehouseState.activeContext
                ) {
                    connectedRoute?.let { route ->
                        if (!ConnectedOperationsNavigation.permits(
                                route, CONNECTED_OPERATIONS, state, accessState, warehouseState
                            )
                        ) closeConnectedOperation()
                    }
                }
                androidx.compose.runtime.LaunchedEffect(
                    connectedRoute, dispositionState.metadata, pendingDispositionLot
                ) {
                    val selectedLot = pendingDispositionLot
                    if (selectedLot != null && connectedRoute?.entryKey == "warehouse.disposition" &&
                        dispositionState.metadata == DispositionMetadataStatus.Available
                    ) {
                        pendingDispositionLot = null
                        if (dispositionState.intent == null) {
                            dispositionViewModel.lotIdChanged(selectedLot)
                            dispositionViewModel.loadLot()
                        }
                    }
                }
                RootNavigation(
                    state = state,
                    accessState = accessState,
                    warehouseState = warehouseState,
                    scannerState = scannerState,
                    connectedOperationRoute = connectedRoute,
                    connectedOperationEntries = CONNECTED_OPERATIONS,
                    onConnectedOperationBack = ::closeConnectedOperation,
                    onOpenConnectedOperation = { entry ->
                        ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)
                            ?.let { route ->
                                closeConnectedOperation()
                                connectedRoute = route
                                val authority = route.authority
                                when (entry.key) {
                                    "commercial.visit" -> fieldVisitViewModel.activate(CommercialAuthority(
                                        authority.userId, authority.tenantId, authority.workspaceId, authority.membershipId,
                                        authority.permissions, route.authorityEpoch))
                                    "commercial.documents" -> businessDocumentsViewModel.activate(CommercialAuthority(
                                        authority.userId, authority.tenantId, authority.workspaceId, authority.membershipId,
                                        authority.permissions, route.authorityEpoch))
                                    "commercial.request" -> fieldRequestViewModel.activate(CommercialAuthority(
                                        authority.userId, authority.tenantId, authority.workspaceId, authority.membershipId,
                                        authority.permissions, route.authorityEpoch))
                                    "driver.deliveries" -> driverDeliveryViewModel.activate(
                                        DriverDeliveryAuthority(authority.userId, authority.tenantId, authority.workspaceId,
                                            authority.membershipId, authority.permissions, route.authorityEpoch)
                                    )
                                    "commercial.catalog" -> commercialCatalogViewModel.activate(
                                        CommercialAuthority(
                                            authority.userId, authority.tenantId, authority.workspaceId,
                                            authority.membershipId, authority.permissions, route.authorityEpoch
                                        )
                                    )
                                    "commercial.progress" -> customerProgressViewModel.activate(
                                        CommercialAuthority(
                                            authority.userId, authority.tenantId, authority.workspaceId,
                                            authority.membershipId, authority.permissions, route.authorityEpoch
                                        )
                                    )
                                    "commercial.customers" -> customerSearchViewModel.activate(
                                        CommercialAuthority(
                                            authority.userId, authority.tenantId, authority.workspaceId,
                                            authority.membershipId, authority.permissions, route.authorityEpoch
                                        )
                                    )
                                    "warehouse.temperature" -> temperatureEvidenceViewModel.activate(
                                        TemperatureEvidenceAuthority(
                                            authority.userId, authority.tenantId, authority.workspaceId,
                                            authority.membershipId, authority.permissions, route.authorityEpoch
                                        )
                                    )
                                    "operations.overview", "operations.exceptions", "dispatch.readiness" -> dispatchReadinessViewModel.activate(
                                        DispatchAuthorityContext(
                                            route.authorityEpoch,
                                            DispatchAuthorityIdentity(
                                                authority.userId, authority.tenantId, authority.workspaceId,
                                                authority.membershipId, authority.permissions
                                            )
                                        )
                                    )
                                    "warehouse.disposition" -> dispositionViewModel.activate(
                                        DispositionAuthority(
                                            authority.userId, authority.tenantId, authority.workspaceId,
                                            authority.membershipId, authority.permissions, route.authorityEpoch
                                        )
                                    )
                                }
                            }
                    },
                    connectedOperationContent = {
                        when (connectedRoute?.entryKey) {
                            "operations.overview", "operations.exceptions" -> OperationsOverviewScreen(
                                state = dispatchReadinessState,
                                tenantId = connectedRoute?.authority?.tenantId ?: "",
                                workspaceId = connectedRoute?.authority?.workspaceId ?: "",
                                exceptionsOnly = connectedRoute?.entryKey == "operations.exceptions",
                                onBack = ::closeConnectedOperation,
                                onRefresh = dispatchReadinessViewModel::refresh,
                                onOpenOwningWork = { fulfillmentId ->
                                    val entry = CONNECTED_OPERATIONS.single { it.key == "dispatch.readiness" }
                                    ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)?.let { route ->
                                        connectedRoute = route
                                        dispatchReadinessViewModel.selectFulfillment(fulfillmentId)
                                    }
                                }
                            )
                            "commercial.visit" -> FieldVisitScreen(fieldVisitState, ::closeConnectedOperation, fieldVisitViewModel)
                            "commercial.documents" -> BusinessDocumentsScreen(businessDocumentsState, ::closeConnectedOperation,
                                businessDocumentsViewModel::refresh, businessDocumentsViewModel::previousPage,
                                businessDocumentsViewModel::nextPage, businessDocumentsViewModel::open,
                                businessDocumentsViewModel::closeContent)
                            "commercial.request" -> FieldRequestScreen(fieldRequestState, ::closeConnectedOperation, fieldRequestViewModel)
                            "driver.deliveries" -> DriverDeliveryScreen(
                                state = driverDeliveryState, onBack = ::closeConnectedOperation,
                                onRefresh = driverDeliveryViewModel::refresh,
                                onSelectDelivery = driverDeliveryViewModel::selectDelivery,
                                onBeginDelivery = driverDeliveryViewModel::beginSelectedDelivery,
                                onRetryUnknownStart = driverDeliveryViewModel::retryUnknownStart
                            )
                            "commercial.catalog" -> CommercialCatalogScreen(
                                state = commercialCatalogState,
                                onBack = ::closeConnectedOperation,
                                onCustomerIdChanged = commercialCatalogViewModel::customerIdChanged,
                                onQueryChanged = commercialCatalogViewModel::queryChanged,
                                onSearch = commercialCatalogViewModel::search,
                                onNextPage = commercialCatalogViewModel::nextPage,
                                onSelectProduct = commercialCatalogViewModel::selectProduct,
                                onRouteClosed = commercialCatalogViewModel::deactivate
                            )
                            "commercial.progress" -> CustomerProgressScreen(
                                state = customerProgressState,
                                onBack = ::closeConnectedOperation,
                                onCustomerIdChanged = customerProgressViewModel::customerIdChanged,
                                onCurrencyChanged = customerProgressViewModel::currencyChanged,
                                onRefresh = customerProgressViewModel::refresh,
                                onPreviousPage = customerProgressViewModel::previousPage,
                                onNextPage = customerProgressViewModel::nextPage,
                                onRouteClosed = customerProgressViewModel::deactivate
                            )
                            "commercial.customers" -> CustomerSearchScreen(
                                state = customerSearchState,
                                onBack = ::closeConnectedOperation,
                                onQueryChanged = customerSearchViewModel::queryChanged,
                                onSearch = customerSearchViewModel::search,
                                onSelectCustomer = customerSearchViewModel::selectCustomer,
                                onPreviousPage = customerSearchViewModel::previousPage,
                                onNextPage = customerSearchViewModel::nextPage,
                                onRouteClosed = customerSearchViewModel::deactivate,
                                onPrepareRequest = { customerId ->
                                    val entry = CONNECTED_OPERATIONS.single { it.key == "commercial.request" }
                                    ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)?.let { route ->
                                        closeConnectedOperation()
                                        connectedRoute = route
                                        val authority = route.authority
                                        fieldRequestViewModel.activate(CommercialAuthority(authority.userId, authority.tenantId,
                                            authority.workspaceId, authority.membershipId, authority.permissions, route.authorityEpoch), customerId)
                                    }
                                },
                                onReviewProducts = if (accessState.activeContext?.verifiedAuthority?.permissions
                                    ?.any { it == "catalog.read" || it == "catalog:read" } == true
                                ) { customerId ->
                                    val entry = CONNECTED_OPERATIONS.single { it.key == "commercial.catalog" }
                                    ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)
                                        ?.let { route ->
                                            closeConnectedOperation()
                                            connectedRoute = route
                                            val authority = route.authority
                                            commercialCatalogViewModel.activate(
                                                CommercialAuthority(
                                                    authority.userId, authority.tenantId, authority.workspaceId,
                                                    authority.membershipId, authority.permissions, route.authorityEpoch
                                                )
                                            )
                                            commercialCatalogViewModel.customerIdChanged(customerId)
                                        }
                                } else null,
                                onReviewProgress = { customerId ->
                                    val entry = CONNECTED_OPERATIONS.single { it.key == "commercial.progress" }
                                    ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)
                                        ?.let { route ->
                                            closeConnectedOperation()
                                            connectedRoute = route
                                            val authority = route.authority
                                            customerProgressViewModel.activate(
                                                CommercialAuthority(
                                                    authority.userId, authority.tenantId, authority.workspaceId,
                                                    authority.membershipId, authority.permissions, route.authorityEpoch
                                                )
                                            )
                                            customerProgressViewModel.customerIdChanged(customerId)
                                            customerProgressViewModel.refresh()
                                        }
                                }
                            )
                            "warehouse.temperature" -> TemperatureEvidenceScreen(
                                state = temperatureEvidenceState,
                                onBack = ::closeConnectedOperation,
                                onSubjectTypeChanged = temperatureEvidenceViewModel::subjectTypeChanged,
                                onSelectSubject = temperatureEvidenceViewModel::selectSubject,
                                onSubjectIdChanged = temperatureEvidenceViewModel::subjectIdChanged,
                                onValueChanged = temperatureEvidenceViewModel::valueChanged,
                                onUnitChanged = temperatureEvidenceViewModel::unitChanged,
                                onOccurredAtChanged = temperatureEvidenceViewModel::occurredAtChanged,
                                onReloadSubjects = temperatureEvidenceViewModel::reloadSubjects,
                                onSaveDraft = temperatureEvidenceViewModel::saveDraft,
                                onStageAndRecord = temperatureEvidenceViewModel::stageAndRecord,
                                onRetryUnknownOutcome = temperatureEvidenceViewModel::retryUnknownOutcome,
                                onRetryIntentCleanup = temperatureEvidenceViewModel::retryIntentCleanup,
                                onStartAnotherReading = temperatureEvidenceViewModel::startAnotherReading
                            )
                            "dispatch.readiness" -> DispatchReadinessScreen(
                                state = dispatchReadinessState,
                                onBack = ::closeConnectedOperation,
                                onRefresh = dispatchReadinessViewModel::refresh,
                                onSelectFulfillment = dispatchReadinessViewModel::selectFulfillment,
                                onClearSelection = dispatchReadinessViewModel::clearSelection,
                                onRouteClosed = dispatchReadinessViewModel::deactivate
                            )
                            "warehouse.disposition" -> DispositionScreen(
                                state = dispositionState,
                                onBack = ::closeConnectedOperation,
                                onLotIdChanged = dispositionViewModel::lotIdChanged,
                                onLoadLot = dispositionViewModel::loadLot,
                                onDispositionSelected = dispositionViewModel::selectDisposition,
                                onReasonChanged = dispositionViewModel::reasonChanged,
                                onSaveLocalNote = dispositionViewModel::saveLocalNote,
                                onSubmit = dispositionViewModel::submit,
                                onReplayUnknownOutcome = dispositionViewModel::replayUnknownOutcome,
                                onStartNewDecision = dispositionViewModel::startNewDecision,
                                onRouteClosed = dispositionViewModel::deactivate
                            )
                        }
                    },
                    onPickStock = {
                        pickingWorkListViewModel.invalidate()
                        pickingManualEntry = false
                        pickingReference = ""
                        pickingOpened = false
                        pickingViewModel.invalidate()
                        val authority = ConnectedOperationsNavigation.currentAuthority(
                            state, accessState, warehouseState
                        )
                        if (authority != null) {
                            pickingManualEntry = false
                            pickingWorkListEpoch = accessState.authorityEpoch
                            pickingWorkListViewModel.activate(
                                PickingAuthority(authority.userId, authority.tenantId, authority.workspaceId,
                                    authority.membershipId, authority.permissions, accessState.authorityEpoch)
                            )
                            warehouseViewModel.openPicking()
                        }
                    },
                    pickingContent = {
                        if (!pickingOpened && !pickingManualEntry) {
                            androidx.compose.foundation.layout.Column {
                                androidx.compose.material3.TextButton(onClick = { pickingManualEntry = true }) {
                                    androidx.compose.material3.Text(getString(R.string.picking_manual_reference))
                                }
                                PickingWorkListScreen(
                                    state = pickingWorkListState,
                                    onBack = {
                                        pickingWorkListViewModel.invalidate()
                                        warehouseViewModel.back()
                                    },
                                    onReload = pickingWorkListViewModel::reload,
                                    onPreviousPage = pickingWorkListViewModel::previousPage,
                                    onNextPage = pickingWorkListViewModel::nextPage,
                                    onSelectFulfillment = { fulfillmentId ->
                                        val authority = ConnectedOperationsNavigation.currentAuthority(
                                            state, accessState, warehouseState
                                        )
                                        if (authority != null) {
                                            pickingReference = fulfillmentId
                                            pickingViewModel.activate(
                                                PickingAuthority(authority.userId, authority.tenantId, authority.workspaceId,
                                                    authority.membershipId, authority.permissions, accessState.authorityEpoch),
                                                fulfillmentId
                                            )
                                            pickingOpened = true
                                        }
                                    }
                                )
                            }
                        } else if (!pickingOpened) {
                            PickingEntryScreen(
                                reference = pickingReference,
                                onReferenceChanged = { pickingReference = it },
                                onBack = { pickingManualEntry = false },
                                onOpen = {
                                    val authority = accessState.activeContext?.verifiedAuthority
                                    if (authority != null && pickingReference.isNotBlank()) {
                                        pickingViewModel.activate(
                                            PickingAuthority(
                                                authority.userId,
                                                authority.tenantId,
                                                authority.workspaceId,
                                                authority.membershipId,
                                                authority.permissions,
                                                accessState.authorityEpoch
                                            ),
                                            pickingReference.trim()
                                        )
                                        pickingOpened = true
                                    }
                                }
                            )
                        } else {
                            PickingScreen(
                                state = pickingState,
                                onBack = {
                                    pickingOpened = false
                                    pickingManualEntry = false
                                    pickingViewModel.invalidate()
                                    pickingWorkListViewModel.reload()
                                },
                                onReload = pickingViewModel::reload,
                                onSelectOffer = pickingViewModel::selectOffer,
                                onLotIdentifierChanged = pickingViewModel::lotIdentifierChanged,
                                onQuantityChanged = pickingViewModel::quantityChanged,
                                onStartPicking = pickingViewModel::startPicking,
                                onConfirmPick = pickingViewModel::confirmPick,
                                onRetryUnknownOutcome = pickingViewModel::retryUnknownOutcome,
                                onRetryIntentCleanup = pickingViewModel::retryIntentCleanup
                            )
                        }
                    },
                    onViewStock = {
                        warehouseState.activeContext?.let { context ->
                            stockConditionViewModel.activate(context)
                            warehouseViewModel.openStockCondition()
                        }
                    },
                    stockConditionContent = {
                        StockConditionScreen(
                            stockConditionState,
                            onBack = warehouseViewModel::back,
                            onRefresh = stockConditionViewModel::refresh,
                            onSelectLot = stockConditionViewModel::selectLot,
                            onRouteClosed = stockConditionViewModel::invalidateContext,
                            onDisposition = if (accessState.activeContext?.verifiedAuthority?.permissions
                                    ?.any { it == "inventory.release" || it == "inventory.waste" } == true
                            ) {
                                { lotId ->
                                    val entry = CONNECTED_OPERATIONS.single { it.key == "warehouse.disposition" }
                                    ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)
                                        ?.let { route ->
                                            closeConnectedOperation()
                                            val authority = route.authority
                                            connectedRoute = route
                                            pendingDispositionLot = lotId
                                            dispositionViewModel.activate(
                                                DispositionAuthority(
                                                    authority.userId, authority.tenantId, authority.workspaceId,
                                                    authority.membershipId, authority.permissions, route.authorityEpoch
                                                )
                                            )
                                        }
                                }
                            } else null
                        )
                    },
                    onReceiveStock = {
                        accessState.activeContext?.verifiedAuthority?.let { authority ->
                            receivingViewModel.activate(
                                ReceivingAuthority(
                                    authority.userId,
                                    authority.tenantId,
                                    authority.workspaceId,
                                    authority.membershipId,
                                    authority.permissions,
                                    accessState.authorityEpoch
                                )
                            )
                            warehouseViewModel.openReceiving()
                        }
                    },
                    receivingContent = {
                        ReceivingScreen(
                            state = receivingState,
                            onBack = warehouseViewModel::back,
                            onChooseProduct = {
                                choosingReceivingProduct = true
                                warehouseViewModel.openProductSearch()
                            },
                            onSelectWarehouse = receivingViewModel::selectWarehouse,
                            onSelectZone = receivingViewModel::selectZone,
                            onBatchNumberChanged = receivingViewModel::batchNumberChanged,
                            onExpirationDateChanged = receivingViewModel::expirationDateChanged,
                            onQuantityChanged = receivingViewModel::quantityChanged,
                            onUnitChanged = receivingViewModel::unitChanged,
                            onTemperatureReadingChanged =
                                receivingViewModel::temperatureReadingChanged,
                            onReloadWarehouses = receivingViewModel::reloadWarehouses,
                            onReloadZones = receivingViewModel::reloadZones,
                            onSubmit = receivingViewModel::submit,
                            onRetryUnknownOutcome = receivingViewModel::retryUnknownOutcome,
                            onRetryIntentCleanup = receivingViewModel::retryIntentCleanup,
                            onStartAnotherReceipt = receivingViewModel::startAnotherReceipt
                        )
                    },
                    scannerCameraPreview = { modifier ->
                        ScannerCameraPreview(
                            scanner = cameraScanner,
                            modifier = modifier,
                            onEvent = scannerViewModel::cameraDeviceEvent
                        )
                    },
                    onIdentifierChanged = accessViewModel::identifierChanged,
                    onPasswordChanged = accessViewModel::passwordChanged,
                    onPasswordVisibilityChanged = accessViewModel::togglePasswordVisibility,
                    onSignIn = accessViewModel::signIn,
                    onRetry = {
                        if ((state as? SessionState.Restoring)?.canRetryConnection == true) {
                            viewModel.retrySessionValidation()
                        } else {
                            accessViewModel.retryContextList()
                        }
                    },
                    onLogout = {
                        closeConnectedOperation()
                        pendingScannerPermissionReturn = null
                        choosingReceivingProduct = false
                        receivingViewModel.invalidate()
                        stockConditionViewModel.invalidateContext()
                        pickingViewModel.invalidate()
                        pickingWorkListViewModel.invalidate()
                        pickingManualEntry = false
                        pickingReference = ""
                        pickingOpened = false
                        logoutRequested = true
                        accessViewModel.sessionInvalidated()
                        warehouseViewModel.sessionInvalidated()
                        scannerViewModel.sessionInvalidated()
                        viewModel.logout()
                    },
                    onSelectContext = accessViewModel::selectContext,
                    onContextBack = accessViewModel::backFromContextChooser,
                    onChangeContext = {
                        closeConnectedOperation()
                        pendingScannerPermissionReturn = null
                        choosingReceivingProduct = false
                        receivingViewModel.invalidate()
                        stockConditionViewModel.invalidateContext()
                        pickingViewModel.invalidate()
                        pickingWorkListViewModel.invalidate()
                        pickingManualEntry = false
                        pickingReference = ""
                        pickingOpened = false
                        if (warehouseState.route == WarehouseRoute.Scanner) {
                            scannerViewModel.routeClosed()
                        }
                        accessViewModel.beginContextChange()
                    },
                    onIdentifyProduct = warehouseViewModel::openProductSearch,
                    onScanProductCode = {
                        val active = accessState.activeContext
                        if (active != null && accessState.stage == AccessStage.WorkAuthorized) {
                            val context = active.toActiveOperationsContext(
                                accessState.authorityEpoch
                            )
                            scannerViewModel.enterOperations(context)
                            scannerViewModel.scannerRouteOpened(cameraPermissionGranted())
                            warehouseViewModel.openScanner()
                        }
                    },
                    onWarehouseBack = {
                        if (warehouseState.route == WarehouseRoute.Scanner) {
                            scannerViewModel.routeClosed()
                        }
                        warehouseViewModel.back()
                    },
                    onSearch = warehouseViewModel::submitSearch,
                    onLoadMore = warehouseViewModel::loadMore,
                    onQueryChanged = warehouseViewModel::queryChanged,
                    onSelectCandidate = warehouseViewModel::selectCandidate,
                    onScannerPermissionRequest = {
                        val authority = accessState.activeContext?.verifiedAuthority
                        if (authority != null &&
                            accessState.stage == AccessStage.WorkAuthorized &&
                            warehouseState.route == WarehouseRoute.Scanner
                        ) {
                            pendingScannerPermissionReturn = PendingScannerPermissionReturn(
                                authority = authority,
                                source = ScannerPermissionReturnSource.RuntimePrompt
                            )
                            scannerViewModel.permissionRequestStarted()
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onScannerOpenSettings = {
                        val authority = accessState.activeContext?.verifiedAuthority
                        if (authority != null &&
                            accessState.stage == AccessStage.WorkAuthorized &&
                            warehouseState.route == WarehouseRoute.Scanner
                        ) {
                            pendingScannerPermissionReturn = PendingScannerPermissionReturn(
                                authority = authority,
                                source = ScannerPermissionReturnSource.AppSettings
                            )
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", packageName, null)
                                }
                            )
                        }
                    },
                    onScannerRetry = {
                        scannerViewModel.startScanning(cameraPermissionGranted())
                    },
                    onScannerManualSearch = {
                        scannerViewModel.routeClosed()
                        warehouseViewModel.openProductSearch()
                    }
                )
            }
        }
    }

    private fun cameraPermissionGranted(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun closeConnectedOperation() {
        if (connectedRoute != null && warehouseViewModel.state.value.route != WarehouseRoute.WorkEntry) {
            warehouseViewModel.back()
        }
        pendingDispositionLot = null
        connectedRoute = null
        dispositionViewModel.deactivate()
        fieldVisitViewModel.deactivate()
        businessDocumentsViewModel.deactivate()
        fieldRequestViewModel.deactivate()
        driverDeliveryViewModel.invalidate()
        dispatchReadinessViewModel.deactivate()
        temperatureEvidenceViewModel.deactivate()
        customerSearchViewModel.deactivate()
        customerProgressViewModel.deactivate()
        commercialCatalogViewModel.deactivate()
    }

    private fun invalidateProtectedContentForForegroundReturn() {
        closeConnectedOperation()
        choosingReceivingProduct = false
        receivingViewModel.invalidate()
        stockConditionViewModel.invalidateContext()
        pickingViewModel.invalidate()
        pickingReference = ""
        pickingOpened = false
        accessViewModel.sessionInvalidated()
        warehouseViewModel.sessionInvalidated()
        scannerViewModel.sessionInvalidated()
    }

    private fun verifyScannerForegroundReturn() {
        val revalidationId = pendingScannerPermissionReturn?.revalidationId
        viewModel.verifyForegroundReturn {
            pendingScannerPermissionReturn =
                pendingScannerPermissionReturn.completeRevalidation(revalidationId)
        }
    }

    private fun beginScannerForegroundRevalidation() {
        invalidateProtectedContentForForegroundReturn()
        pendingScannerPermissionReturn?.let { pending ->
            pendingScannerPermissionReturn = pending.beginRevalidation()
        }
        verifyScannerForegroundReturn()
    }
}

private fun WorkforceContextSummary.toActiveOperationsContext(
    authorityEpoch: Long
): ActiveOperationsContext = ActiveOperationsContext(
    companyName = companyName,
    workspaceName = workspaceName,
    authorityEpoch = authorityEpoch,
    verifiedIdentity = verifiedAuthority?.let { authority ->
        VerifiedOperationsIdentity(
            userId = authority.userId,
            tenantId = authority.tenantId,
            workspaceId = authority.workspaceId,
            membershipId = authority.membershipId,
            permissions = authority.permissions.toSet()
        )
    }
)

private fun AccessUiState.shouldResolveCurrentContext(logoutRequested: Boolean): Boolean =
    !logoutRequested &&
        notice != AccessNotice.UnknownContextOutcome &&
        chooser == null &&
        (
            stage == AccessStage.IdentityRequired ||
                (stage == AccessStage.WorkAuthorized && activeContext == null)
            )

private fun WorkforceContextSummary.operationsVisibilityHint(): TaskVisibilityHint =
    if (verifiedAuthority?.permissions?.any {
            it in
                setOf(
                    "inventory.receive",
                    "warehouse:write",
                    "warehouse.read",
                    "inventory.read",
                    "warehouse:read",
                    "fulfillment.read",
                    "fulfillment:read"
                ) || CONNECTED_OPERATIONS.any { entry -> it in entry.readPermissions }
        } == true
    ) {
        TaskVisibilityHint.Available
    } else {
        when (permissionHint) {
            PermissionHint.Available -> TaskVisibilityHint.Available
            PermissionHint.Unavailable -> TaskVisibilityHint.Unavailable
            PermissionHint.Unknown -> TaskVisibilityHint.Unknown
        }
    }


private val CONNECTED_OPERATIONS = listOf(
    ConnectedOperationEntry("commercial.visit", "Visita al cliente", setOf("client.read", "sales:read")),
    ConnectedOperationEntry("commercial.documents", "Documentos del cliente", setOf("document.read")),
    ConnectedOperationEntry("operations.overview", "Vista operativa", setOf("dispatch.read")),
    ConnectedOperationEntry("operations.exceptions", "Trabajo bloqueado", setOf("dispatch.read")),
    ConnectedOperationEntry("commercial.request", "Preparar solicitud", setOf("client.read", "sales:read")),
    ConnectedOperationEntry("driver.deliveries", "Mis entregas", setOf("dispatch.read", "logistics:read")),
    ConnectedOperationEntry("commercial.catalog", "Catálogo comercial", setOf("catalog.read", "catalog:read")),
    ConnectedOperationEntry("commercial.progress", "Compromisos y crédito", setOf("client.read", "sales:read")),
    ConnectedOperationEntry("commercial.customers", "Clientes y compradores", setOf("client.read", "sales:read")),
    ConnectedOperationEntry("warehouse.temperature", "Registrar temperatura", setOf("inventory.receive")),
    ConnectedOperationEntry("dispatch.readiness", "Preparación de despacho", setOf("dispatch.read")),
    ConnectedOperationEntry(
        "warehouse.disposition", "Disposición de existencias",
        setOf("warehouse.read", "inventory.read", "warehouse:read")
    )
)
