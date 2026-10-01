package com.nexa.mobile.operations

import com.nexa.mobile.operations.feature.dispatch.DispatchHandoffIdentityViewModel
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoffIdentityScreen
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureViewModel
import com.nexa.mobile.operations.feature.dispatch.DispatchTemperatureScreen
import com.nexa.mobile.operations.feature.dispatch.DispatchPlanChangeViewModel
import com.nexa.mobile.operations.feature.dispatch.DispatchPlanChangeScreen
import com.nexa.mobile.operations.feature.dispatch.DispatchAssignmentViewModel
import com.nexa.mobile.operations.feature.dispatch.DispatchAssignmentScreen
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyGateway
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyEvidenceArtifactStore
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancySelectionContext
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyEvidenceCandidate
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyArtifactWrite
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyStartContext
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyAuthority
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyScope
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyDraftStore
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyViewModel
import com.nexa.mobile.operations.feature.warehouse.InboundDiscrepancyScreen
import com.nexa.mobile.operations.feature.warehouse.StockTransferReceiptGateway
import com.nexa.mobile.operations.feature.warehouse.StockTransferReceiptObservationMetadataStore
import com.nexa.mobile.operations.feature.warehouse.StockTransferReceiptMetadataStore
import com.nexa.mobile.operations.feature.warehouse.StockTransferReceiptViewModel
import com.nexa.mobile.operations.feature.warehouse.StockTransferReceiptScreen
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsGateway
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsMetadataStore
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoverViewModel
import com.nexa.mobile.operations.feature.dispatch.DispatchHandoverScreen
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsViewModel
import com.nexa.mobile.operations.feature.dispatch.DispatchOutgoingGoodsScreen
import com.nexa.mobile.operations.feature.dispatch.DispatchReadinessDetailStatus
import com.nexa.mobile.operations.feature.warehouse.StockTransferAuthority
import com.nexa.mobile.operations.feature.warehouse.StockTransferViewModel
import com.nexa.mobile.operations.feature.warehouse.StockTransferScreen
import com.nexa.mobile.operations.commercial.FieldVisitViewModel
import com.nexa.mobile.operations.commercial.FieldVisitScreen
import com.nexa.mobile.operations.commercial.BusinessDocumentsViewModel
import com.nexa.mobile.operations.commercial.BusinessDocumentsScreen
import com.nexa.mobile.operations.visibility.OperationsOverviewScreen
import com.nexa.mobile.operations.commercial.FieldRequestViewModel
import com.nexa.mobile.operations.commercial.FieldRequestScreen
import com.nexa.mobile.operations.feature.delivery.DriverHandoffTokenViewModel
import com.nexa.mobile.operations.feature.delivery.DriverHandoffTokenScreen
import com.nexa.mobile.operations.feature.delivery.DriverIncidentSelectionContext
import com.nexa.mobile.operations.feature.delivery.DriverIncidentMetadataWrite
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryIncidentViewModel
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryIncidentScreen
import com.nexa.mobile.operations.feature.delivery.DriverProofSelectionContext
import com.nexa.mobile.operations.feature.delivery.DriverProofMetadataStore
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryLoadStatus
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryAuthority
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryViewModel
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryScreen
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionsViewModel
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryInstructionsScreen
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionsViewModel
import com.nexa.mobile.operations.feature.delivery.DriverDeliveryOperationalExceptionsScreen
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
import com.nexa.mobile.operations.feature.warehouse.CycleCountAuthority
import com.nexa.mobile.operations.feature.warehouse.CycleCountScope
import com.nexa.mobile.operations.feature.warehouse.CycleCountGateway
import com.nexa.mobile.operations.feature.warehouse.CycleCountMetadataStore
import com.nexa.mobile.operations.feature.warehouse.CycleCountViewModel
import com.nexa.mobile.operations.feature.warehouse.CycleCountScreen
import com.nexa.mobile.operations.feature.warehouse.WarehouseAutomationScreen
import com.nexa.mobile.operations.feature.warehouse.WarehouseBatchViewModel
import com.nexa.mobile.operations.feature.warehouse.WarehouseBatchScreen
import com.nexa.mobile.operations.feature.warehouse.LotSubstitutionGateway
import com.nexa.mobile.operations.feature.warehouse.LotSubstitutionMetadataStore
import com.nexa.mobile.operations.feature.warehouse.LotSubstitutionViewModel
import com.nexa.mobile.operations.feature.warehouse.LotSubstitutionWork
import com.nexa.mobile.operations.feature.warehouse.LotSubstitutionScreen
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
    @Inject internal lateinit var dispatchHandoffIdentityBindings: DispatchHandoffIdentityGatewayBindings
    private val dispatchHandoffIdentityViewModel: DispatchHandoffIdentityViewModel by viewModels { dispatchHandoffIdentityBindings.viewModelFactory() }
    @Inject internal lateinit var dispatchTemperatureFactory: DispatchTemperatureViewModelFactory
    private val dispatchTemperatureViewModel: DispatchTemperatureViewModel by viewModels { dispatchTemperatureFactory }
    @Inject internal lateinit var driverHandoffTokenBindings: DriverHandoffTokenGatewayBindings
    private val driverHandoffTokenViewModel: DriverHandoffTokenViewModel by viewModels { driverHandoffTokenBindings.viewModelFactory() }
    @Inject internal lateinit var driverIncidentBindings: DriverDeliveryIncidentGatewayBindings
    private val driverIncidentViewModel: DriverDeliveryIncidentViewModel by viewModels { driverIncidentBindings.viewModelFactory() }
    @Inject internal lateinit var driverDeliveryBindings: DriverDeliveryGatewayBindings
    private val driverDeliveryViewModel: DriverDeliveryViewModel by viewModels { driverDeliveryBindings.viewModelFactory() }
    @Inject internal lateinit var driverDeliveryInstructionsBindings: DriverDeliveryInstructionsBindings
    private val driverDeliveryInstructionsViewModel: DriverDeliveryInstructionsViewModel by viewModels {
        driverDeliveryInstructionsBindings.viewModelFactory()
    }
    @Inject internal lateinit var driverDeliveryOperationalExceptionsBindings: DriverDeliveryOperationalExceptionsBindings
    private val driverDeliveryOperationalExceptionsViewModel: DriverDeliveryOperationalExceptionsViewModel by viewModels {
        driverDeliveryOperationalExceptionsBindings.viewModelFactory()
    }
    @Inject internal lateinit var fieldRequestFactory: FieldRequestViewModelFactory
    private val fieldRequestViewModel: FieldRequestViewModel by viewModels { fieldRequestFactory }
    @Inject internal lateinit var businessDocumentsFactory: BusinessDocumentsViewModelFactory
    private val businessDocumentsViewModel: BusinessDocumentsViewModel by viewModels { businessDocumentsFactory }
    @Inject internal lateinit var fieldVisitFactory: FieldVisitViewModelFactory
    private val fieldVisitViewModel: FieldVisitViewModel by viewModels { fieldVisitFactory }
    @Inject internal lateinit var inboundDiscrepancyGateway: InboundDiscrepancyGateway
    @Inject internal lateinit var inboundDiscrepancyArtifacts: InboundDiscrepancyEvidenceArtifactStore
    @Inject internal lateinit var inboundDiscrepancyStore: InboundDiscrepancyDraftStore
    private val inboundDiscrepancyViewModel: InboundDiscrepancyViewModel by viewModels { InboundDiscrepancyViewModelBindings.viewModelFactory(inboundDiscrepancyGateway, inboundDiscrepancyStore, inboundDiscrepancyArtifacts) }
    @Inject internal lateinit var lotSubstitutionGateway: LotSubstitutionGateway
    @Inject internal lateinit var lotSubstitutionMetadataStore: LotSubstitutionMetadataStore
    private val lotSubstitutionViewModel: LotSubstitutionViewModel by viewModels {
        LotSubstitutionGatewayBindings.viewModelFactory(lotSubstitutionGateway, lotSubstitutionMetadataStore)
    }
    @Inject internal lateinit var cycleCountGateway: CycleCountGateway
    @Inject internal lateinit var cycleCountMetadataStore: CycleCountMetadataStore
    private val cycleCountViewModel: CycleCountViewModel by viewModels { CycleCountViewModelBindings.viewModelFactory(cycleCountGateway, cycleCountMetadataStore) }
    @Inject internal lateinit var stockTransferReceiptGateway: StockTransferReceiptGateway
    @Inject internal lateinit var stockTransferReceiptMetadataStore: StockTransferReceiptMetadataStore
    @Inject internal lateinit var stockTransferReceiptObservationMetadataStore: StockTransferReceiptObservationMetadataStore
    private val stockTransferReceiptViewModel: StockTransferReceiptViewModel by viewModels {
        StockTransferReceiptViewModelBindings.viewModelFactory(stockTransferReceiptGateway, stockTransferReceiptMetadataStore, stockTransferReceiptObservationMetadataStore)
    }
    @Inject internal lateinit var dispatchHandoverFactory: DispatchHandoverViewModelFactory
    private val dispatchHandoverViewModel: DispatchHandoverViewModel by viewModels { dispatchHandoverFactory }
    @Inject internal lateinit var dispatchOutgoingGoodsGateway: DispatchOutgoingGoodsGateway
    @Inject internal lateinit var dispatchOutgoingGoodsMetadataStore: DispatchOutgoingGoodsMetadataStore
    private val dispatchOutgoingGoodsViewModel: DispatchOutgoingGoodsViewModel by viewModels {
        DispatchOutgoingGoodsViewModelFactory(dispatchOutgoingGoodsGateway, dispatchOutgoingGoodsMetadataStore)
    }
    @Inject internal lateinit var stockTransferBindings: StockTransferGatewayBindings
    private val stockTransferViewModel: StockTransferViewModel by viewModels { stockTransferBindings.viewModelFactory() }
    @Inject internal lateinit var dispatchPlanChangeFactory: DispatchPlanChangeViewModelFactory
    private val dispatchPlanChangeViewModel: DispatchPlanChangeViewModel by viewModels { dispatchPlanChangeFactory }
    @Inject internal lateinit var dispatchAssignmentFactory: DispatchAssignmentViewModelFactory
    private val dispatchAssignmentViewModel: DispatchAssignmentViewModel by viewModels { dispatchAssignmentFactory }
    private var pendingDispositionLot by mutableStateOf<String?>(null)
    private val warehouseBatchViewModel: WarehouseBatchViewModel by viewModels()
    private var connectedRoute by mutableStateOf<ConnectedOperationRoute?>(null)
    private var showDriverInstructions by mutableStateOf(false)
    private var showDriverOperationalExceptions by mutableStateOf(false)
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

    private var pendingInboundEvidencePicker by mutableStateOf<InboundDiscrepancySelectionContext?>(null)
    private var pendingInboundEvidenceFile by mutableStateOf<InboundDiscrepancySelectionContext?>(null)
    private var pendingDriverIncidentPicker by mutableStateOf<DriverIncidentSelectionContext?>(null)
    private var pendingDriverIncidentFile by mutableStateOf<DriverIncidentSelectionContext?>(null)
    private var pendingDriverIncidentExceptionDeliveryId by mutableStateOf<String?>(null)
    private var pendingDriverProofPicker by mutableStateOf<DriverProofSelectionContext?>(null)
    @Inject internal lateinit var driverProofMetadataStore: DriverProofMetadataStore
    private var pendingDriverProofFile by mutableStateOf<DriverProofSelectionContext?>(null)
    private val driverProofFileSelection by lazy { AppDriverProofFileSelection(applicationContext) }

    private var pendingScannerPermissionReturn by mutableStateOf<PendingScannerPermissionReturn?>(
        null
    )

    private var identifiedStorageSkuId: String? by mutableStateOf(null)

    override fun onDestroy() {
        pendingDriverProofFile = null
        pendingDriverProofPicker = null
        pendingDriverIncidentPicker = null
        pendingDriverIncidentFile = null
        pendingDriverIncidentExceptionDeliveryId = null
        pendingInboundEvidencePicker = null
        pendingInboundEvidenceFile = null
        super.onDestroy()
    }

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
        driverProofFileSelection.discardAbandonedSelections()
        enableEdgeToEdge()
        setContent {
            OperationsTheme {
                val driverProofPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    val selection = pendingDriverProofPicker
                    pendingDriverProofPicker = null
                    if (uri != null && selection != null) lifecycleScope.launch {
                        val scope = selection.scope
                        val scopeKey = listOf(scope.userId, scope.tenantId, scope.workspaceId, scope.membershipId,
                            selection.deliveryId, selection.attemptId, selection.proofId).joinToString("") { "${it.length}:$it" }
                        val candidate = driverProofFileSelection.prepare(uri, scopeKey)
                        if (candidate != null && driverProofMetadataStore.stageReturnedProofSelection(selection, candidate)) {
                            pendingDriverProofFile = selection
                        } else {
                            android.widget.Toast.makeText(this@MainActivity,
                                "No se pudo conservar la imagen. Selecciona una imagen válida e inténtalo otra vez.",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
                val driverIncidentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    val selection = pendingDriverIncidentPicker
                    pendingDriverIncidentPicker = null
                    if (uri != null && selection != null) lifecycleScope.launch {
                        val scope = selection.scope
                        val scopeKey = listOf(scope.userId, scope.tenantId, scope.workspaceId, scope.membershipId,
                            selection.deliveryId, selection.attemptId, selection.draftId).joinToString("") { "${it.length}:$it" }
                        val candidate = driverProofFileSelection.prepare(uri, scopeKey)
                        val staged = candidate?.let { driverIncidentBindings.stageReturnedEvidence(selection, it) }
                        if (staged == DriverIncidentMetadataWrite.Saved) {
                            pendingDriverIncidentFile = selection
                        } else {
                            android.widget.Toast.makeText(this@MainActivity, "No se pudo conservar la imagen de incidencia.",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
                val inboundEvidencePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    val selection = pendingInboundEvidencePicker
                    pendingInboundEvidencePicker = null
                    if (uri != null && selection != null) lifecycleScope.launch {
                        val scope = selection.scope
                        val scopeKey = listOf(scope.userId, scope.tenantId, scope.workspaceId, scope.membershipId,
                            selection.warehouseId, selection.caseId).joinToString("") { "${it.length}:$it" }
                        val candidate = driverProofFileSelection.prepare(uri, scopeKey)
                        val staged = if (candidate == null) null else try {
                            inboundDiscrepancyViewModel.stageReturnedSelection(selection, InboundDiscrepancyEvidenceCandidate(
                                candidate.file, candidate.originalFilename, candidate.declaredContentType, candidate.byteSize, candidate.checksumSha256))
                        } finally { driverProofFileSelection.discard(candidate) }
                        if (staged == InboundDiscrepancyArtifactWrite.Saved) {
                            pendingInboundEvidenceFile = selection
                        } else {
                            android.widget.Toast.makeText(this@MainActivity, "No se pudo conservar la imagen de recepción.",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
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
                val dispatchPlanChangeState by dispatchPlanChangeViewModel.state.collectAsStateWithLifecycle()
                val dispatchAssignmentState by dispatchAssignmentViewModel.state.collectAsStateWithLifecycle()
                val inboundDiscrepancyState by inboundDiscrepancyViewModel.state.collectAsStateWithLifecycle()
                val lotSubstitutionState by lotSubstitutionViewModel.state.collectAsStateWithLifecycle()
                val cycleCountState by cycleCountViewModel.state.collectAsStateWithLifecycle()
                val stockTransferReceiptState by stockTransferReceiptViewModel.state.collectAsStateWithLifecycle()
                val dispatchHandoverState by dispatchHandoverViewModel.state.collectAsStateWithLifecycle()
                val dispatchOutgoingGoodsState by dispatchOutgoingGoodsViewModel.state.collectAsStateWithLifecycle()
                val stockTransferState by stockTransferViewModel.state.collectAsStateWithLifecycle()
                val fieldVisitState by fieldVisitViewModel.state.collectAsStateWithLifecycle()
                val businessDocumentsState by businessDocumentsViewModel.state.collectAsStateWithLifecycle()
                val fieldRequestState by fieldRequestViewModel.state.collectAsStateWithLifecycle()
                val dispatchHandoffIdentityState by dispatchHandoffIdentityViewModel.state.collectAsStateWithLifecycle()
                val dispatchTemperatureState by dispatchTemperatureViewModel.state.collectAsStateWithLifecycle()
                val driverHandoffTokenState by driverHandoffTokenViewModel.state.collectAsStateWithLifecycle()
                val driverIncidentState by driverIncidentViewModel.state.collectAsStateWithLifecycle()
                val driverDeliveryState by driverDeliveryViewModel.state.collectAsStateWithLifecycle()
                val driverInstructionsState by driverDeliveryInstructionsViewModel.state.collectAsStateWithLifecycle()
                val driverOperationalExceptionsState by driverDeliveryOperationalExceptionsViewModel.state.collectAsStateWithLifecycle()
                BackHandler(enabled = showDriverInstructions, onBack = ::closeDriverDeliveryInstructions)
                BackHandler(enabled = showDriverOperationalExceptions, onBack = ::closeDriverDeliveryOperationalExceptions)
                val dispatchReadinessState by dispatchReadinessViewModel.state.collectAsStateWithLifecycle()
                val dispositionState by dispositionViewModel.state.collectAsStateWithLifecycle()
                val warehouseBatchState by warehouseBatchViewModel.state.collectAsStateWithLifecycle()
                val pickingState by pickingViewModel.state.collectAsStateWithLifecycle()
                androidx.compose.runtime.LaunchedEffect(pickingState) { warehouseBatchViewModel.observe(pickingState) }
                val stockConditionState =
                    stockConditionViewModel.state.collectAsStateWithLifecycle().value
                val receivingState = receivingViewModel.state.collectAsStateWithLifecycle().value
                val scannerReturnContinuationPending =
                    pendingScannerPermissionReturn != null
                val verifiedContext = operationsGateway.currentContext
                    .collectAsStateWithLifecycle(initialValue = null).value
                var previousSessionState by remember { mutableStateOf<SessionState?>(null) }
                var logoutRequested by remember { mutableStateOf(false) }

                androidx.compose.runtime.LaunchedEffect(pendingInboundEvidenceFile, state, accessState.stage,
                    accessState.authorityEpoch, warehouseState.authorityEpoch, inboundDiscrepancyState) {
                    val pending = pendingInboundEvidenceFile ?: return@LaunchedEffect
                    if (state != SessionState.Active) {
                        if (state in setOf(SessionState.SignedOut, SessionState.ReauthenticationRequired, SessionState.LocalProtectionError)) {
                            pendingInboundEvidenceFile = null
                        }
                        return@LaunchedEffect
                    }
                    val proof = ConnectedOperationsNavigation.currentAuthority(state, accessState, warehouseState) ?: return@LaunchedEffect
                    val scope = pending.scope
                    if (proof.userId != scope.userId || proof.tenantId != scope.tenantId || proof.workspaceId != scope.workspaceId ||
                        proof.membershipId != scope.membershipId || "document.upload" !in proof.permissions || "inventory.receive" !in proof.permissions) {
                        pendingInboundEvidenceFile = null
                        return@LaunchedEffect
                    }
                    if (connectedRoute?.entryKey != "warehouse.inbound-discrepancy") {
                        val entry = CONNECTED_OPERATIONS.single { it.key == "warehouse.inbound-discrepancy" }
                        ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)?.let { route ->
                            closeConnectedOperation()
                            connectedRoute = route
                            inboundDiscrepancyViewModel.activate(InboundDiscrepancyAuthority(
                                InboundDiscrepancyScope(proof.userId, proof.tenantId, proof.workspaceId, proof.membershipId), route.authorityEpoch),
                                InboundDiscrepancyStartContext(pending.warehouseId))
                        }
                        return@LaunchedEffect
                    }
                    if (inboundDiscrepancyViewModel.reloadStagedEvidence(pending)) pendingInboundEvidenceFile = null
                }
                androidx.compose.runtime.LaunchedEffect(pendingDriverIncidentFile, state, accessState.stage,
                    accessState.authorityEpoch, warehouseState.authorityEpoch) {
                    val pending = pendingDriverIncidentFile ?: return@LaunchedEffect
                    if (state != SessionState.Active) {
                        if (state in setOf(SessionState.SignedOut, SessionState.ReauthenticationRequired, SessionState.LocalProtectionError)) {
                            pendingDriverIncidentFile = null
                        }
                        return@LaunchedEffect
                    }
                    val proof = ConnectedOperationsNavigation.currentAuthority(state, accessState, warehouseState) ?: return@LaunchedEffect
                    val scope = pending.scope
                    if (proof.userId != scope.userId || proof.tenantId != scope.tenantId || proof.workspaceId != scope.workspaceId ||
                        proof.membershipId != scope.membershipId || "document.upload" !in proof.permissions || "dispatch.start_route" !in proof.permissions) {
                        pendingDriverIncidentFile = null
                        return@LaunchedEffect
                    }
                    val entry = CONNECTED_OPERATIONS.single { it.key == "driver.incident" }
                    ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)?.let { route ->
                        closeConnectedOperation()
                        connectedRoute = route
                        driverIncidentViewModel.activate(DriverDeliveryAuthority(proof.userId, proof.tenantId, proof.workspaceId,
                            proof.membershipId, proof.permissions, route.authorityEpoch), pending.deliveryId, pending.attemptId,
                            pending.deliveryVersion, pending.confirmedTerminalOutcome)
                        pendingDriverIncidentFile = null
                    }
                }
                androidx.compose.runtime.LaunchedEffect(
                    pendingDriverIncidentExceptionDeliveryId,
                    state,
                    accessState.authorityEpoch,
                    warehouseState.authorityEpoch,
                    driverDeliveryState
                ) {
                    val deliveryId = pendingDriverIncidentExceptionDeliveryId ?: return@LaunchedEffect
                    if (state != SessionState.Active) {
                        if (state in setOf(SessionState.SignedOut, SessionState.ReauthenticationRequired,
                                SessionState.LocalProtectionError)
                        ) pendingDriverIncidentExceptionDeliveryId = null
                        return@LaunchedEffect
                    }
                    val verified = ConnectedOperationsNavigation.currentAuthority(state, accessState, warehouseState)
                        ?: return@LaunchedEffect
                    if (connectedRoute?.entryKey != "driver.deliveries") {
                        val entry = CONNECTED_OPERATIONS.single { it.key == "driver.deliveries" }
                        val route = ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)
                        if (route == null) {
                            pendingDriverIncidentExceptionDeliveryId = null
                            return@LaunchedEffect
                        }
                        closeConnectedOperation()
                        connectedRoute = route
                        driverDeliveryViewModel.activate(DriverDeliveryAuthority(
                            verified.userId, verified.tenantId, verified.workspaceId, verified.membershipId,
                            verified.permissions, route.authorityEpoch
                        ))
                        return@LaunchedEffect
                    }
                    val route = connectedRoute ?: return@LaunchedEffect
                    if (!ConnectedOperationsNavigation.permits(
                            route, CONNECTED_OPERATIONS, state, accessState, warehouseState
                        )
                    ) {
                        pendingDriverIncidentExceptionDeliveryId = null
                        return@LaunchedEffect
                    }
                    if (driverDeliveryState.listStatus != DriverDeliveryLoadStatus.Ready) return@LaunchedEffect
                    if (driverDeliveryState.deliveries.none { it.id == deliveryId }) {
                        pendingDriverIncidentExceptionDeliveryId = null
                        return@LaunchedEffect
                    }
                    if (driverDeliveryState.selectedDelivery?.id != deliveryId) {
                        driverDeliveryViewModel.selectDelivery(deliveryId)
                        return@LaunchedEffect
                    }
                    if (driverDeliveryState.detailStatus == DriverDeliveryLoadStatus.Loading) return@LaunchedEffect
                    if (driverDeliveryState.detailStatus != DriverDeliveryLoadStatus.Ready ||
                        driverDeliveryState.authorizedOperationalExceptionsDeliveryId != deliveryId
                    ) {
                        pendingDriverIncidentExceptionDeliveryId = null
                        return@LaunchedEffect
                    }
                    driverDeliveryOperationalExceptionsViewModel.activate(
                        DriverDeliveryAuthority(
                            route.authority.userId, route.authority.tenantId, route.authority.workspaceId,
                            route.authority.membershipId, route.authority.permissions, route.authorityEpoch
                        ), deliveryId
                    )
                    pendingDriverIncidentExceptionDeliveryId = null
                    showDriverOperationalExceptions = true
                }
                androidx.compose.runtime.LaunchedEffect(pendingDriverProofFile, state, accessState.stage,
                    accessState.authorityEpoch, warehouseState.authorityEpoch, driverDeliveryState) {
                    val pending = pendingDriverProofFile ?: return@LaunchedEffect
                    if (state != SessionState.Active) {
                        if (state in setOf(SessionState.SignedOut, SessionState.ReauthenticationRequired, SessionState.LocalProtectionError)) {
                            pendingDriverProofFile = null
                        }
                        return@LaunchedEffect
                    }
                    val proof = ConnectedOperationsNavigation.currentAuthority(state, accessState, warehouseState) ?: return@LaunchedEffect
                    val scope = pending.scope
                    if (proof.userId != scope.userId || proof.tenantId != scope.tenantId || proof.workspaceId != scope.workspaceId ||
                        proof.membershipId != scope.membershipId || "document.upload" !in proof.permissions || "dispatch.start_route" !in proof.permissions) {
                        pendingDriverProofFile = null
                        return@LaunchedEffect
                    }
                    if (connectedRoute?.entryKey != "driver.deliveries") {
                        val entry = CONNECTED_OPERATIONS.single { it.key == "driver.deliveries" }
                        ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)?.let { route ->
                            closeConnectedOperation(); connectedRoute = route
                            driverDeliveryViewModel.activate(DriverDeliveryAuthority(proof.userId, proof.tenantId, proof.workspaceId,
                                proof.membershipId, proof.permissions, route.authorityEpoch))
                        }
                        return@LaunchedEffect
                    }
                    if (driverDeliveryState.selectedDelivery?.id != pending.deliveryId &&
                        driverDeliveryState.listStatus == DriverDeliveryLoadStatus.Ready) {
                        driverDeliveryViewModel.selectDelivery(pending.deliveryId)
                        return@LaunchedEffect
                    }
                    if (driverDeliveryViewModel.reloadStagedProofSelection(pending)) {
                        pendingDriverProofFile = null
                    }
                }

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
                        identifiedStorageSkuId = null
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
                val openLotSubstitution: ((String) -> Unit)? = if (accessState.activeContext?.verifiedAuthority?.permissions?.contains("inventory.adjust") == true) { { lineId ->
                    val allocation = pickingState.allocation
                    val fulfillment = pickingState.fulfillment
                    val line = allocation?.lines?.singleOrNull { it.physicalAllocationLineId == lineId }
                    val entry = CONNECTED_OPERATIONS.single { it.key == "warehouse.lot-substitution" }
                    val route = ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)
                    if (route != null && line != null && fulfillment != null && allocation != null &&
                        pickingState.authorityEpoch == route.authorityEpoch && line.remainingQuantity.signum() > 0) {
                        val work = LotSubstitutionWork(fulfillment.id, allocation.allocationId,
                            line.physicalAllocationLineId, line.skuId, line.catalogItemId, line.lotId,
                            line.warehouseId, line.zoneId, line.remainingQuantity.toPlainString(), line.unit, allocation.version)
                        closeConnectedOperation()
                        pickingOpened = false
                        pickingViewModel.invalidate()
                        connectedRoute = route
                        val authority = route.authority
                        lotSubstitutionViewModel.activate(PickingAuthority(authority.userId, authority.tenantId,
                            authority.workspaceId, authority.membershipId, authority.permissions, route.authorityEpoch), work)
                    }
                } } else null
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
                                    "warehouse.cycle-count" -> cycleCountViewModel.activate(CycleCountAuthority(
                                        CycleCountScope(authority.userId, authority.tenantId, authority.workspaceId, authority.membershipId),
                                        route.authorityEpoch, authority.permissions))
                                    "warehouse.automation" -> warehouseState.activeContext?.let(stockConditionViewModel::activate)
                            "warehouse.batch" -> {
                                        val proof = PickingAuthority(authority.userId, authority.tenantId, authority.workspaceId,
                                            authority.membershipId, authority.permissions, route.authorityEpoch)
                                        warehouseBatchViewModel.activate(proof)
                                        pickingWorkListEpoch = route.authorityEpoch
                                        pickingWorkListViewModel.activate(proof)
                                    }
                                    "warehouse.inbound-discrepancy" -> inboundDiscrepancyViewModel.activate(InboundDiscrepancyAuthority(
                                        InboundDiscrepancyScope(authority.userId, authority.tenantId, authority.workspaceId, authority.membershipId),
                                        route.authorityEpoch))
                                    "warehouse.transfer-receipt" -> stockTransferReceiptViewModel.activate(StockTransferAuthority(
                                        authority.userId, authority.tenantId, authority.workspaceId, authority.membershipId,
                                        authority.permissions, route.authorityEpoch))
                                    "warehouse.transfer" -> stockTransferViewModel.activate(StockTransferAuthority(
                                        authority.userId, authority.tenantId, authority.workspaceId, authority.membershipId,
                                        authority.permissions, route.authorityEpoch))
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
                            "warehouse.lot-substitution" -> LotSubstitutionScreen(lotSubstitutionState,
                                onBack = ::closeConnectedOperation,
                                onLoadAlternatives = { lotSubstitutionViewModel.loadAlternatives() },
                                onSelectAlternative = lotSubstitutionViewModel::selectAlternative,
                                onReasonChanged = lotSubstitutionViewModel::updateReason,
                                onRequest = lotSubstitutionViewModel::requestSubstitution,
                                onRecoverSameIntent = lotSubstitutionViewModel::retryUnknownOutcome,
                                onRefreshCurrentAllocation = lotSubstitutionViewModel::refreshCurrentAllocation)
                            "driver.coordination-limits" -> OperationsCapabilityLimitsScreen(true, ::closeConnectedOperation)
                            "dispatch.coordination-limits" -> OperationsCapabilityLimitsScreen(false, ::closeConnectedOperation)
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
                            "dispatch.handover" -> androidx.compose.foundation.layout.Column {
                                androidx.compose.material3.TextButton(onClick = ::closeConnectedOperation) { androidx.compose.material3.Text("Volver") }
                                DispatchHandoverScreen(dispatchHandoverState, dispatchHandoverViewModel::refresh,
                                    dispatchHandoverViewModel::confirm, dispatchHandoverViewModel::replayUnknownOutcome)
                            }
                            "dispatch.outgoing-goods" -> DispatchOutgoingGoodsScreen(
                                state = dispatchOutgoingGoodsState, onBack = ::closeConnectedOperation,
                                onRefresh = dispatchOutgoingGoodsViewModel::refresh,
                                onObservedLotChanged = dispatchOutgoingGoodsViewModel::changeObservedLot,
                                onObservedQuantityChanged = dispatchOutgoingGoodsViewModel::changeObservedQuantity,
                                onRecord = dispatchOutgoingGoodsViewModel::record,
                                onRetry = dispatchOutgoingGoodsViewModel::retryUnknownOutcome,
                                onRouteClosed = dispatchOutgoingGoodsViewModel::deactivate,
                                onResolutionReasonChanged = dispatchOutgoingGoodsViewModel::changeResolutionReason,
                                onResolveDiscrepancy = dispatchOutgoingGoodsViewModel::resolveDiscrepancy
                            )
                            "dispatch.assignment" -> DispatchAssignmentScreen(
                                state = dispatchAssignmentState, onBack = ::closeConnectedOperation,
                                onRefresh = dispatchAssignmentViewModel::refresh,
                                onSelectDriver = dispatchAssignmentViewModel::selectDriver,
                                onAssign = dispatchAssignmentViewModel::assign,
                                onReplay = dispatchAssignmentViewModel::retryUnknownOutcome,
                                onRouteClosed = dispatchAssignmentViewModel::deactivate,
                                onIdentifyHandoff = if (connectedRoute?.authority?.permissions?.contains("logistics:read") == true) { {
                                    val assignment = dispatchAssignmentState.assignment
                                    val deliveryId = assignment?.deliveryId
                                    if (assignment != null && assignment.current && deliveryId != null) {
                                        val entry = CONNECTED_OPERATIONS.single { it.key == "dispatch.handoff-identity" }
                                        ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)?.let { route ->
                                            closeConnectedOperation()
                                            connectedRoute = route
                                            val authority = route.authority
                                            dispatchHandoffIdentityViewModel.activate(DispatchAuthorityContext(route.authorityEpoch,
                                                DispatchAuthorityIdentity(authority.userId, authority.tenantId, authority.workspaceId,
                                                    authority.membershipId, authority.permissions)), deliveryId, assignment.id)
                                        }
                                    }
                                } } else null,
                                onChangePlan = {
                                    val fulfillmentId = dispatchAssignmentState.fulfillmentId
                                    val entry = CONNECTED_OPERATIONS.single { it.key == "dispatch.plan-change" }
                                    if (fulfillmentId != null) ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)?.let { route ->
                                        closeConnectedOperation()
                                        connectedRoute = route
                                        val authority = route.authority
                                        dispatchPlanChangeViewModel.activate(fulfillmentId, DispatchAuthorityContext(route.authorityEpoch,
                                            DispatchAuthorityIdentity(authority.userId, authority.tenantId, authority.workspaceId,
                                                authority.membershipId, authority.permissions)))
                                    }
                                }
                            )
                            "dispatch.handoff-identity" -> androidx.compose.foundation.layout.Column {
                                androidx.compose.material3.TextButton(onClick = ::closeConnectedOperation) { androidx.compose.material3.Text("Volver") }
                                DispatchHandoffIdentityScreen(dispatchHandoffIdentityState,
                                    dispatchHandoffIdentityViewModel::issue, dispatchHandoffIdentityViewModel::retrySame,
                                    dispatchHandoffIdentityViewModel::issueReplacement, dispatchHandoffIdentityViewModel::validate,
                                    dispatchHandoffIdentityViewModel::tokenChanged, dispatchHandoffIdentityViewModel::hideToken,
                                    dispatchHandoffIdentityViewModel::deactivate)
                            }
                            "dispatch.temperature" -> DispatchTemperatureScreen(
                                dispatchTemperatureState, ::closeConnectedOperation,
                                dispatchTemperatureViewModel::refresh, dispatchTemperatureViewModel::updateValue,
                                onRecord = dispatchTemperatureViewModel::record, onRouteClosed = dispatchTemperatureViewModel::deactivate,
                                onRetryUnknownOutcome = dispatchTemperatureViewModel::retryUnknownOutcome
                            )
                            "dispatch.plan-change" -> DispatchPlanChangeScreen(
                                state = dispatchPlanChangeState, onBack = ::closeConnectedOperation,
                                onRefresh = dispatchPlanChangeViewModel::refresh,
                                onSelectDriver = dispatchPlanChangeViewModel::selectDriver,
                                onScheduleChanged = dispatchPlanChangeViewModel::updatePlannedDispatchAt,
                                onSave = dispatchPlanChangeViewModel::changePlan,
                                onReplay = dispatchPlanChangeViewModel::retryUnknownOutcome,
                                onRouteClosed = dispatchPlanChangeViewModel::deactivate
                            )
                                    "warehouse.automation" -> WarehouseAutomationScreen(
                                state = stockConditionState, onBack = ::closeConnectedOperation,
                                onRefresh = stockConditionViewModel::refresh, onSelectLot = stockConditionViewModel::selectLot,
                                canRecordTemperature = connectedRoute?.authority?.permissions?.contains("inventory.receive") == true,
                                onManualTemperature = {
                                    val entry = CONNECTED_OPERATIONS.single { it.key == "warehouse.temperature" }
                                    ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)?.let { route ->
                                        closeConnectedOperation(); connectedRoute = route
                                        val proof = route.authority
                                        temperatureEvidenceViewModel.activate(TemperatureEvidenceAuthority(proof.userId, proof.tenantId,
                                            proof.workspaceId, proof.membershipId, proof.permissions, route.authorityEpoch))
                                    }
                                }
                            )
                            "warehouse.batch" -> {
                                if (warehouseBatchState.selectedId == null) WarehouseBatchScreen(
                                    state = warehouseBatchState, work = pickingWorkListState,
                                    onBack = ::closeConnectedOperation, onRefresh = pickingWorkListViewModel::reload,
                                    onNextPage = pickingWorkListViewModel::nextPage, onPreviousPage = pickingWorkListViewModel::previousPage,
                                    onAdd = { warehouseBatchViewModel.add(it, pickingWorkListViewModel.state.value) },
                                    onMove = warehouseBatchViewModel::move, onReview = warehouseBatchViewModel::review,
                                    onOpen = { id ->
                                        val route = connectedRoute
                                        if (route != null && ConnectedOperationsNavigation.permits(route, CONNECTED_OPERATIONS, state, accessState, warehouseState) && warehouseBatchViewModel.select(id)) {
                                            val proof = route.authority
                                            pickingViewModel.activate(PickingAuthority(proof.userId, proof.tenantId, proof.workspaceId,
                                                proof.membershipId, proof.permissions, route.authorityEpoch), id)
                                        }
                                    }
                                ) else PickingScreen(
                                    state = pickingState,
                                    onBack = { warehouseBatchViewModel.observe(pickingViewModel.state.value); warehouseBatchViewModel.closeItem(); pickingViewModel.invalidate(); pickingWorkListViewModel.reload() },
                                    onReload = pickingViewModel::reload, onSelectOffer = pickingViewModel::selectOffer,
                                    onLotIdentifierChanged = pickingViewModel::lotIdentifierChanged,
                                    onQuantityChanged = pickingViewModel::quantityChanged, onStartPicking = pickingViewModel::startPicking,
                                    onConfirmPick = pickingViewModel::confirmPick, onRetryUnknownOutcome = pickingViewModel::retryUnknownOutcome,
                                    onRetryIntentCleanup = pickingViewModel::retryIntentCleanup,
                                onProposeLotSubstitution = openLotSubstitution
                                )
                            }
                            "warehouse.inbound-discrepancy" -> InboundDiscrepancyScreen(
                                state = inboundDiscrepancyState, onBack = ::closeConnectedOperation,
                                onWarehouseChanged = inboundDiscrepancyViewModel::warehouseChanged,
                                onExpectedSkuChanged = inboundDiscrepancyViewModel::expectedSkuChanged,
                                onObservedSkuChanged = inboundDiscrepancyViewModel::observedSkuChanged,
                                onExpectedBatchChanged = inboundDiscrepancyViewModel::expectedBatchChanged,
                                onObservedBatchChanged = inboundDiscrepancyViewModel::observedBatchChanged,
                                onKindChanged = inboundDiscrepancyViewModel::kindChanged,
                                onReasonDetailsChanged = inboundDiscrepancyViewModel::reasonDetailsChanged,
                                onExpectedQuantityChanged = inboundDiscrepancyViewModel::expectedQuantityChanged,
                                onObservedQuantityChanged = inboundDiscrepancyViewModel::observedQuantityChanged,
                                onUnitChanged = inboundDiscrepancyViewModel::unitChanged,
                                onObservationNotesChanged = inboundDiscrepancyViewModel::observationNotesChanged,
                                onSaveDraft = inboundDiscrepancyViewModel::saveDraft,
                                onCreateCase = inboundDiscrepancyViewModel::createCase,
                                onSelectEvidence = {
                                    val route = connectedRoute
                                    if (route != null && "document.upload" in route.authority.permissions &&
                                        ConnectedOperationsNavigation.permits(route, CONNECTED_OPERATIONS, state, accessState, warehouseState)) {
                                        inboundDiscrepancyViewModel.selectionContextForCurrentCase()?.let { selection ->
                                            pendingInboundEvidencePicker = selection
                                            inboundEvidencePicker.launch("image/*")
                                        }
                                    }
                                },
                                onUploadEvidence = { inboundDiscrepancyViewModel.uploadEvidence() },
                                onRefreshEvidence = inboundDiscrepancyViewModel::refreshEvidence,
                                onSubmitForReview = { inboundDiscrepancyViewModel.submitForReview() },
                                onRetryPendingAction = inboundDiscrepancyViewModel::retryPendingAction,
                                onRequestDiscard = inboundDiscrepancyViewModel::requestDiscard,
                                onConfirmDiscard = inboundDiscrepancyViewModel::confirmDiscard,
                                onCancelDiscard = inboundDiscrepancyViewModel::cancelDiscard
                            )
                            "warehouse.cycle-count" -> CycleCountScreen(
                                state = cycleCountState,
                                canCorrect = connectedRoute?.authority?.permissions?.let { "inventory.adjust" in it && "warehouse:write" in it } == true,
                                onBack = ::closeConnectedOperation, onReloadLots = cycleCountViewModel::reloadLots,
                                onSelectLot = cycleCountViewModel::selectLot, onQuantityChanged = cycleCountViewModel::observeQuantityChanged,
                                onRecord = cycleCountViewModel::recordCount, onRetryCount = cycleCountViewModel::retryCountUnknownOutcome,
                                onApplyCorrection = cycleCountViewModel::applyCorrection,
                                onRetryCorrection = cycleCountViewModel::retryCorrectionUnknownOutcome,
                                onLoadMoreLots = cycleCountViewModel::loadMoreLots, hasMoreLots = cycleCountState.hasMoreLots,
                                onRefreshStaleCount = cycleCountViewModel::refreshStaleCount
                            )
                            "warehouse.transfer-receipt" -> StockTransferReceiptScreen(
                                state = stockTransferReceiptState, onBack = ::closeConnectedOperation,
                                onReloadWarehouses = stockTransferReceiptViewModel::reloadWarehouses,
                                onSelectDestinationWarehouse = stockTransferReceiptViewModel::selectDestinationWarehouse,
                                onLoadMoreTransfers = stockTransferReceiptViewModel::loadMoreTransfers,
                                onSelectTransfer = stockTransferReceiptViewModel::selectTransfer,
                                onReceiveExpectedQuantity = stockTransferReceiptViewModel::receiveExpectedQuantity,
                                onRetryUnknownOutcome = stockTransferReceiptViewModel::retryUnknownOutcome,
                                onRetryIntentCleanup = stockTransferReceiptViewModel::retryIntentCleanup,
                                onObserveArrival = stockTransferReceiptViewModel::observeArrival,
                                onRetryObservation = stockTransferReceiptViewModel::retryObservationUnknownOutcome,
                                onCleanupObservation = stockTransferReceiptViewModel::retryObservationIntentCleanup
                            )
                            "warehouse.transfer" -> StockTransferScreen(
                                state = stockTransferState, onBack = ::closeConnectedOperation,
                                onSelectSourceLot = stockTransferViewModel::selectSourceLot,
                                onSelectDestinationWarehouse = stockTransferViewModel::selectDestinationWarehouse,
                                onSelectDestinationZone = stockTransferViewModel::selectDestinationZone,
                                onQuantityChanged = stockTransferViewModel::quantityChanged,
                                onReasonChanged = stockTransferViewModel::reasonChanged,
                                onReloadSourceLots = stockTransferViewModel::reloadSourceLots,
                                onReloadWarehouses = stockTransferViewModel::reloadWarehouses,
                                onReloadZones = stockTransferViewModel::reloadZones,
                                onStartTransfer = stockTransferViewModel::startTransfer,
                                onRetryUnknownOutcome = stockTransferViewModel::retryUnknownOutcome,
                                onRetryIntentCleanup = stockTransferViewModel::retryIntentCleanup,
                                onStartAnotherTransfer = stockTransferViewModel::startAnotherTransfer
                            )
                            "commercial.visit" -> FieldVisitScreen(fieldVisitState, ::closeConnectedOperation, fieldVisitViewModel,
                                connectedRoute?.authority?.permissions?.any { it == "client.manage" || it == "sales:write" } == true)
                            "commercial.documents" -> BusinessDocumentsScreen(businessDocumentsState, ::closeConnectedOperation,
                                businessDocumentsViewModel::refresh, businessDocumentsViewModel::previousPage,
                                businessDocumentsViewModel::nextPage, businessDocumentsViewModel::open,
                                businessDocumentsViewModel::closeContent)
                            "commercial.request" -> FieldRequestScreen(fieldRequestState, ::closeConnectedOperation, fieldRequestViewModel)
                            "driver.deliveries" -> if (showDriverOperationalExceptions) {
                                DriverDeliveryOperationalExceptionsScreen(
                                    state = driverOperationalExceptionsState,
                                    onBack = ::closeDriverDeliveryOperationalExceptions,
                                    onRefresh = driverDeliveryOperationalExceptionsViewModel::refresh,
                                    onClaim = driverDeliveryOperationalExceptionsViewModel::claim,
                                    onSendForReview = driverDeliveryOperationalExceptionsViewModel::sendForReview,
                                    onRetrySameCommand = driverDeliveryOperationalExceptionsViewModel::retrySameCommand
                                )
                            } else if (showDriverInstructions) {
                                DriverDeliveryInstructionsScreen(
                                    state = driverInstructionsState,
                                    onBack = ::closeDriverDeliveryInstructions,
                                    onRefresh = driverDeliveryInstructionsViewModel::refresh,
                                    onSelectInstruction = driverDeliveryInstructionsViewModel::setInstructionSelected,
                                    onAcknowledgeSelected = driverDeliveryInstructionsViewModel::acknowledgeSelected,
                                    onRetryUnknownAcknowledgement = driverDeliveryInstructionsViewModel::retryUnknownAcknowledgement
                                )
                            } else DriverDeliveryScreen(
                                state = driverDeliveryState, onBack = ::closeConnectedOperation,
                                onRefresh = driverDeliveryViewModel::refresh,
                                onSelectDelivery = driverDeliveryViewModel::selectDelivery,
                                onBeginDelivery = driverDeliveryViewModel::beginSelectedDelivery,
                                onRetryUnknownStart = driverDeliveryViewModel::retryUnknownStart,
                                onRecordOutcome = driverDeliveryViewModel::recordOutcome,
                                onRetryUnknownOutcome = driverDeliveryViewModel::retryUnknownOutcome,
                                onChooseProofFile = {
                                    val route = connectedRoute
                                    if (route != null && ConnectedOperationsNavigation.permits(route, CONNECTED_OPERATIONS, state, accessState, warehouseState)) {
                                        driverDeliveryViewModel.beginProofFileSelection()?.let { selection ->
                                            pendingDriverProofPicker = selection
                                            driverProofPicker.launch("image/*")
                                        }
                                    }
                                },
                                onUploadSelectedProofEvidence = driverDeliveryViewModel::uploadSelectedProofEvidence,
                                onCreateProof = driverDeliveryViewModel::createProof,
                                onRefreshProofEvidence = driverDeliveryViewModel::refreshProofEvidence,
                                onRetryUnknownProof = driverDeliveryViewModel::retryUnknownProof,
                                onAttachAvailableProofEvidence = driverDeliveryViewModel::attachAvailableProofEvidence,
                                onOpenHandoffCode = if (connectedRoute?.authority?.permissions?.contains("logistics:write") == true) { { deliveryId, attemptId, version ->
                                    val entry = CONNECTED_OPERATIONS.single { it.key == "driver.handoff-code" }
                                    ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)?.let { route ->
                                        closeConnectedOperation()
                                        connectedRoute = route
                                        val authority = route.authority
                                        driverHandoffTokenViewModel.activate(DriverDeliveryAuthority(authority.userId, authority.tenantId,
                                            authority.workspaceId, authority.membershipId, authority.permissions, route.authorityEpoch),
                                            deliveryId, attemptId, version)
                                    }
                                } } else null,
                                onOpenIncident = { deliveryId, attemptId, version, terminal ->
                                    val entry = CONNECTED_OPERATIONS.single { it.key == "driver.incident" }
                                    ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)?.let { route ->
                                        closeConnectedOperation()
                                        connectedRoute = route
                                        val authority = route.authority
                                        driverIncidentViewModel.activate(DriverDeliveryAuthority(authority.userId, authority.tenantId,
                                            authority.workspaceId, authority.membershipId, authority.permissions, route.authorityEpoch),
                                            deliveryId, attemptId, version, terminal)
                                    }
                                },
                                onSignalArrival = driverDeliveryViewModel::signalArrival,
                                onRetryUnknownArrival = driverDeliveryViewModel::retryUnknownArrival,
                                onOpenInstructions = { deliveryId ->
                                    val route = connectedRoute
                                    if (route != null &&
                                        driverDeliveryViewModel.state.value.authorizedInstructionsDeliveryId == deliveryId &&
                                        ConnectedOperationsNavigation.permits(
                                            route, CONNECTED_OPERATIONS, state, accessState, warehouseState
                                        )
                                    ) {
                                        val authority = route.authority
                                        driverDeliveryInstructionsViewModel.activate(
                                            DriverDeliveryAuthority(
                                                authority.userId, authority.tenantId, authority.workspaceId,
                                                authority.membershipId, authority.permissions, route.authorityEpoch
                                            ),
                                            deliveryId
                                        )
                                        showDriverInstructions = true
                                    }
                                },
                                onOpenOperationalExceptions = { deliveryId ->
                                    val route = connectedRoute
                                    if (route != null &&
                                        driverDeliveryViewModel.state.value.authorizedOperationalExceptionsDeliveryId == deliveryId &&
                                        ConnectedOperationsNavigation.permits(
                                            route, CONNECTED_OPERATIONS, state, accessState, warehouseState
                                        )
                                    ) {
                                        val authority = route.authority
                                        driverDeliveryOperationalExceptionsViewModel.activate(
                                            DriverDeliveryAuthority(
                                                authority.userId, authority.tenantId, authority.workspaceId,
                                                authority.membershipId, authority.permissions, route.authorityEpoch
                                            ),
                                            deliveryId
                                        )
                                        showDriverOperationalExceptions = true
                                    }
                                },
                                onOpenDirections = { destination ->
                                    val route = connectedRoute
                                    val currentDestination = driverDeliveryViewModel.state.value.authorizedDirectionsDestination
                                    if (route == null || currentDestination != destination ||
                                        !ConnectedOperationsNavigation.permits(route, CONNECTED_OPERATIONS, state, accessState, warehouseState)
                                    ) false else {
                                        try {
                                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(destination))))
                                            true
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            false
                                        } catch (_: SecurityException) {
                                            false
                                        }
                                    }
                                }
                            )
                            "driver.handoff-code" -> androidx.compose.foundation.layout.Column {
                                androidx.compose.material3.TextButton(onClick = ::closeConnectedOperation) { androidx.compose.material3.Text("Volver") }
                                DriverHandoffTokenScreen(driverHandoffTokenState,
                                    driverHandoffTokenViewModel::issueOrRetrySame,
                                    driverHandoffTokenViewModel::refresh,
                                    driverHandoffTokenViewModel::clearToken)
                            }
                            "driver.incident" -> androidx.compose.foundation.layout.Column {
                                androidx.compose.material3.TextButton(onClick = ::closeConnectedOperation) {
                                    androidx.compose.material3.Text("Volver")
                                }
                                DriverDeliveryIncidentScreen(
                                    state = driverIncidentState,
                                    onTypeChanged = driverIncidentViewModel::editType,
                                    onReasonChanged = driverIncidentViewModel::editReason,
                                    onDescriptionChanged = driverIncidentViewModel::editDescription,
                                    onPlaceChanged = driverIncidentViewModel::editPlace,
                                    onSaveDraft = driverIncidentViewModel::saveDraft,
                                    onReviewDraft = driverIncidentViewModel::reviewDraft,
                                    onSubmitIncident = driverIncidentViewModel::submitIncident,
                                    onRetryUnknownOutcome = driverIncidentViewModel::retryUnknownOutcome,
                                    onSelectEvidence = {
                                        val route = connectedRoute
                                        if (route != null && "document.upload" in route.authority.permissions &&
                                            ConnectedOperationsNavigation.permits(route, CONNECTED_OPERATIONS, state, accessState, warehouseState)) {
                                            driverIncidentViewModel.prepareEvidenceSelection()?.let { selection ->
                                                pendingDriverIncidentPicker = selection
                                                driverIncidentPicker.launch("image/*")
                                            }
                                        }
                                    },
                                    onUploadEvidence = driverIncidentViewModel::uploadEvidence,
                                    onCheckEvidenceAvailability = driverIncidentViewModel::checkEvidenceAvailability,
                                    onReviewEvidenceLink = driverIncidentViewModel::reviewEvidenceLink,
                                    onAttachEvidence = driverIncidentViewModel::attachEvidence,
                                    onOpenOperationalExceptions = { deliveryId ->
                                        pendingDriverIncidentExceptionDeliveryId = deliveryId
                                    }
                                )
                            }
                            "commercial.catalog" -> CommercialCatalogScreen(
                                state = commercialCatalogState,
                                onBack = ::closeConnectedOperation,
                                onCustomerIdChanged = commercialCatalogViewModel::customerIdChanged,
                                onQueryChanged = commercialCatalogViewModel::queryChanged,
                                onSearch = commercialCatalogViewModel::search,
                                onNextPage = commercialCatalogViewModel::nextPage,
                                onSelectProduct = commercialCatalogViewModel::selectProduct,
                                onRouteClosed = commercialCatalogViewModel::deactivate,
                                onPrepareRequest = { customerId, productId ->
                                    val entry = CONNECTED_OPERATIONS.single { it.key == "commercial.request" }
                                    ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)?.let { route ->
                                        closeConnectedOperation()
                                        connectedRoute = route
                                        val authority = route.authority
                                        fieldRequestViewModel.activate(CommercialAuthority(authority.userId, authority.tenantId,
                                            authority.workspaceId, authority.membershipId, authority.permissions, route.authorityEpoch), customerId, productId)
                                    }
                                }
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
                                onRouteClosed = dispatchReadinessViewModel::deactivate,
                                onRecordTemperature = { fulfillmentId ->
                                    val current = dispatchReadinessViewModel.state.value
                                    if (current.detail?.fulfillmentId == fulfillmentId && current.detailStatus == DispatchReadinessDetailStatus.Current) {
                                        val entry = CONNECTED_OPERATIONS.single { it.key == "dispatch.temperature" }
                                        ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)?.let { route ->
                                            closeConnectedOperation()
                                            connectedRoute = route
                                            val authority = route.authority
                                            dispatchTemperatureViewModel.activate(fulfillmentId, DispatchAuthorityContext(route.authorityEpoch,
                                                DispatchAuthorityIdentity(authority.userId, authority.tenantId, authority.workspaceId,
                                                    authority.membershipId, authority.permissions)))
                                        }
                                    }
                                },
                                onConfirmHandover = if (connectedRoute?.authority?.permissions?.contains("fulfillment.manage") == true) {
                                    { fulfillmentId ->
                                        val detail = dispatchReadinessViewModel.state.value.detail
                                        if (detail?.fulfillmentId == fulfillmentId && dispatchReadinessViewModel.state.value.detailStatus == DispatchReadinessDetailStatus.Current) {
                                            val entry = CONNECTED_OPERATIONS.single { it.key == "dispatch.handover" }
                                            ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)?.let { route ->
                                                closeConnectedOperation(); connectedRoute = route
                                                val proof = route.authority
                                                dispatchHandoverViewModel.activate(detail, DispatchAuthorityContext(route.authorityEpoch,
                                                    DispatchAuthorityIdentity(proof.userId, proof.tenantId, proof.workspaceId, proof.membershipId, proof.permissions)))
                                            }
                                        }
                                    }
                                } else null,
                                onVerifyOutgoingGoods = if (connectedRoute?.authority?.permissions?.contains("fulfillment.manage") == true) {
                                    { fulfillmentId ->
                                        val detail = dispatchReadinessViewModel.state.value.detail
                                        if (detail?.fulfillmentId == fulfillmentId &&
                                            dispatchReadinessViewModel.state.value.detailStatus == DispatchReadinessDetailStatus.Current
                                        ) {
                                            val entry = CONNECTED_OPERATIONS.single { it.key == "dispatch.outgoing-goods" }
                                            ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)?.let { route ->
                                                closeConnectedOperation()
                                                connectedRoute = route
                                                val authority = route.authority
                                                dispatchOutgoingGoodsViewModel.activate(detail, DispatchAuthorityContext(route.authorityEpoch,
                                                    DispatchAuthorityIdentity(authority.userId, authority.tenantId, authority.workspaceId,
                                                        authority.membershipId, authority.permissions)))
                                            }
                                        }
                                    }
                                } else null,
                                onAssignFulfillment = { fulfillmentId ->
                                    val entry = CONNECTED_OPERATIONS.single { it.key == "dispatch.assignment" }
                                    ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)?.let { route ->
                                        closeConnectedOperation()
                                        connectedRoute = route
                                        val authority = route.authority
                                        dispatchAssignmentViewModel.activate(fulfillmentId, DispatchAuthorityContext(route.authorityEpoch,
                                            DispatchAuthorityIdentity(authority.userId, authority.tenantId, authority.workspaceId,
                                                authority.membershipId, authority.permissions)))
                                    }
                                }
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
                                onRetryIntentCleanup = pickingViewModel::retryIntentCleanup,
                                onProposeLotSubstitution = openLotSubstitution
                            )
                        }
                    },
                    onViewIdentifiedStorage = if (accessState.activeContext?.verifiedAuthority?.permissions?.any {
                            it in setOf("warehouse.read", "inventory.read", "warehouse:read") } == true) { { skuId ->
                        val confirmed = scannerState as? com.nexa.mobile.operations.feature.warehouse.ProductScannerUiState.Confirmed
                        if (confirmed?.sku?.skuId?.toString() == skuId && confirmed.authorityEpoch == accessState.authorityEpoch) {
                            warehouseState.activeContext?.let { context ->
                                identifiedStorageSkuId = skuId
                                stockConditionViewModel.activate(context)
                                warehouseViewModel.openStockCondition()
                            }
                        }
                    } } else null,
                    onViewStock = {
                        identifiedStorageSkuId = null
                        warehouseState.activeContext?.let { context ->
                            stockConditionViewModel.activate(context)
                            warehouseViewModel.openStockCondition()
                        }
                    },
                    stockConditionContent = {
                        StockConditionScreen(
                            stockConditionState,
                            identifiedSkuId = identifiedStorageSkuId,
                            onBack = { identifiedStorageSkuId = null; warehouseViewModel.back() },
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
                            onReportDiscrepancy = if (receivingState.canReceive && !receivingState.isIntentFrozen &&
                                receivingState.selectedWarehouseId != null) {
                                {
                                    val warehouseId = receivingState.selectedWarehouseId
                                    if (warehouseId != null) {
                                        val observed = InboundDiscrepancyStartContext(warehouseId, receivingState.product?.skuId,
                                            receivingState.product?.displayName, receivingState.batchNumber,
                                            receivingState.quantityText, receivingState.unit)
                                        val entry = CONNECTED_OPERATIONS.single { it.key == "warehouse.inbound-discrepancy" }
                                        ConnectedOperationsNavigation.open(entry, state, accessState, warehouseState)?.let { route ->
                                            closeConnectedOperation()
                                            connectedRoute = route
                                            val proof = route.authority
                                            inboundDiscrepancyViewModel.activate(InboundDiscrepancyAuthority(
                                                InboundDiscrepancyScope(proof.userId, proof.tenantId, proof.workspaceId, proof.membershipId),
                                                route.authorityEpoch), observed)
                                        }
                                    }
                                }
                            } else null,
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

    private fun closeDriverDeliveryInstructions() {
        val deliveryId = driverDeliveryInstructionsViewModel.state.value.deliveryId
        showDriverInstructions = false
        driverDeliveryInstructionsViewModel.invalidate()
        if (deliveryId != null && connectedRoute?.entryKey == "driver.deliveries") {
            driverDeliveryViewModel.selectDelivery(deliveryId)
        }
    }

    private fun closeDriverDeliveryOperationalExceptions() {
        val deliveryId = driverDeliveryOperationalExceptionsViewModel.state.value.deliveryId
        showDriverOperationalExceptions = false
        driverDeliveryOperationalExceptionsViewModel.invalidate()
        if (deliveryId != null && connectedRoute?.entryKey == "driver.deliveries") {
            driverDeliveryViewModel.selectDelivery(deliveryId)
        }
    }

    private fun closeConnectedOperation() {
        if (connectedRoute != null && warehouseViewModel.state.value.route != WarehouseRoute.WorkEntry) {
            warehouseViewModel.back()
        }
        if (connectedRoute?.entryKey == "warehouse.automation") stockConditionViewModel.deactivate()
        if (connectedRoute?.entryKey == "warehouse.batch") {
            pickingViewModel.invalidate()
            pickingWorkListViewModel.invalidate()
        }
        warehouseBatchViewModel.invalidate()
        pendingDispositionLot = null
        showDriverInstructions = false
        showDriverOperationalExceptions = false
        driverDeliveryInstructionsViewModel.invalidate()
        driverDeliveryOperationalExceptionsViewModel.invalidate()
        connectedRoute = null
        dispositionViewModel.deactivate()
        dispatchAssignmentViewModel.deactivate()
        dispatchPlanChangeViewModel.deactivate()
        inboundDiscrepancyViewModel.deactivate()
        stockTransferReceiptViewModel.deactivate()
        lotSubstitutionViewModel.deactivate()
        cycleCountViewModel.deactivate()
        dispatchOutgoingGoodsViewModel.deactivate()
        dispatchHandoverViewModel.deactivate()
        stockTransferViewModel.deactivate()
        fieldVisitViewModel.deactivate()
        businessDocumentsViewModel.deactivate()
        fieldRequestViewModel.deactivate()
        driverDeliveryViewModel.invalidate()
        dispatchHandoffIdentityViewModel.deactivate()
        dispatchTemperatureViewModel.deactivate()
        driverHandoffTokenViewModel.invalidate()
        driverIncidentViewModel.invalidate()
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
    ConnectedOperationEntry("warehouse.lot-substitution", "Sustitución razonada de lote", setOf("inventory.adjust"), visibleInHub = false),
    ConnectedOperationEntry("driver.coordination-limits", "Privacidad y coordinación: límites actuales", setOf("dispatch.read")),
    ConnectedOperationEntry("dispatch.coordination-limits", "Identidad, cargas y transportista: límites actuales", setOf("dispatch.read")),
    ConnectedOperationEntry("warehouse.cycle-count", "Conteo físico y corrección autorizada", setOf("warehouse:write")),
    ConnectedOperationEntry("dispatch.handover", "Confirmar salida y evidencia de handoff", setOf("fulfillment.manage"), visibleInHub = false),
    ConnectedOperationEntry("warehouse.automation", "Observaciones y límite de automatización", setOf("warehouse.read", "inventory.read", "warehouse:read")),
    ConnectedOperationEntry("warehouse.batch", "Preparar grupo de picking", setOf("fulfillment.read", "fulfillment:read")),
    ConnectedOperationEntry("dispatch.handoff-identity", "Identificar traspaso", setOf("logistics:read"), visibleInHub = false),
    ConnectedOperationEntry("dispatch.temperature", "Temperatura de preparación", setOf("fulfillment.read"), visibleInHub = false),
    ConnectedOperationEntry("dispatch.plan-change", "Cambiar conductor u horario", setOf("dispatch.read", "logistics:read"), visibleInHub = false),
    ConnectedOperationEntry("dispatch.assignment", "Asignar desde preparación de despacho", setOf("dispatch.read"), visibleInHub = false),
    ConnectedOperationEntry("warehouse.inbound-discrepancy", "Discrepancia: borrador local", setOf("inventory.receive")),
    ConnectedOperationEntry("warehouse.transfer-receipt", "Recibir traslado en destino", setOf("warehouse:write")),
    ConnectedOperationEntry("dispatch.outgoing-goods", "Verificar salida", setOf("fulfillment.manage"), visibleInHub = false),
    ConnectedOperationEntry("warehouse.transfer", "Traslado interno", setOf("warehouse:write")),
    ConnectedOperationEntry("commercial.visit", "Visita al cliente", setOf("client.read", "sales:read")),
    ConnectedOperationEntry("commercial.documents", "Documentos del cliente", setOf("document.read")),
    ConnectedOperationEntry("operations.overview", "Vista operativa", setOf("dispatch.read")),
    ConnectedOperationEntry("operations.exceptions", "Trabajo bloqueado", setOf("dispatch.read")),
    ConnectedOperationEntry("commercial.request", "Preparar solicitud", setOf("client.read", "sales:read")),
    ConnectedOperationEntry("driver.handoff-code", "Presentar código de entrega", setOf("dispatch.read", "logistics:write"), visibleInHub = false),
    ConnectedOperationEntry("driver.incident", "Registrar incidencia de entrega", setOf("dispatch.read", "dispatch.start_route"), visibleInHub = false),
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
