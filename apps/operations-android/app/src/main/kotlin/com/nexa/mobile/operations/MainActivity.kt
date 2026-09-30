package com.nexa.mobile.operations

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
import com.nexa.mobile.operations.feature.warehouse.ProductScannerViewModel
import com.nexa.mobile.operations.feature.warehouse.TaskVisibilityHint
import com.nexa.mobile.operations.feature.warehouse.VerifiedOperationsIdentity
import com.nexa.mobile.operations.feature.warehouse.WarehouseRoute
import com.nexa.mobile.operations.feature.warehouse.WarehouseViewModel
import com.nexa.mobile.operations.feature.warehouse.WorkEntryStatus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
                            permissionHint = when (context.permissionHint) {
                                PermissionHint.Available -> TaskVisibilityHint.Available
                                PermissionHint.Unavailable -> TaskVisibilityHint.Unavailable
                                PermissionHint.Unknown -> TaskVisibilityHint.Unknown
                            }
                        )
                    }
                }

                RootNavigation(
                    state = state,
                    accessState = accessState,
                    warehouseState = warehouseState,
                    scannerState = scannerState,
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
                        pendingScannerPermissionReturn = null
                        logoutRequested = true
                        accessViewModel.sessionInvalidated()
                        warehouseViewModel.sessionInvalidated()
                        scannerViewModel.sessionInvalidated()
                        viewModel.logout()
                    },
                    onSelectContext = accessViewModel::selectContext,
                    onContextBack = accessViewModel::backFromContextChooser,
                    onChangeContext = {
                        pendingScannerPermissionReturn = null
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

    private fun invalidateProtectedContentForForegroundReturn() {
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
