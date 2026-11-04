package com.nexa.mobile.operations.fulfillmentdelivery.presentation.dispatch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaShapes
import com.nexa.mobile.operations.fulfillmentdelivery.domain.delivery.DriverLiveLocation
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.DispatchDriverCandidate
import com.nexa.mobile.operations.fulfillmentdelivery.domain.dispatch.PreparedFulfillmentDriverAssignment
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.R
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.common.OperationsGlyph
import com.nexa.mobile.operations.fulfillmentdelivery.presentation.common.OperationsGlyphKind
import kotlinx.coroutines.delay

@Composable
fun DispatchAssignmentScreen(
    state: DispatchAssignmentUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectDriver: (String) -> Unit,
    onAssign: () -> Unit,
    onReplay: () -> Unit,
    onRouteClosed: () -> Unit,
    onChangePlan: (() -> Unit)? = null,
    onIdentifyHandoff: (() -> Unit)? = null,
    onRefreshDriverLocation: () -> Unit = {},
    onDriverLocationVisibilityChanged: (Boolean) -> Unit = {},
    onShowDriverLocationMap: (@Composable (DriverLiveLocation) -> Unit)? = null
) {
    val closeAction = rememberUpdatedState(onRouteClosed)
    DisposableEffect(Unit) {
        onDispose { closeAction.value() }
    }
    DispatchDriverLocationAutoRefresh(
        state = state,
        onRefresh = onRefreshDriverLocation,
        onVisibilityChanged = onDriverLocationVisibilityChanged
    )

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.dispatch_assignment_back))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OperationsGlyph(OperationsGlyphKind.Dispatch)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            stringResource(R.string.dispatch_assignment_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.dispatch_assignment_authority_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { AssignmentStatusCard(state.status) }

            state.fulfillmentId?.let {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = NexaColors.Surface),
                        border = BorderStroke(1.dp, NexaColors.Border),
                        shape = NexaShapes.card
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OperationsGlyph(OperationsGlyphKind.Dispatch)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    stringResource(R.string.dispatch_assignment_work_heading),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NexaColors.TextPrimary
                                )
                                state.readiness?.let { readiness ->
                                    Text(
                                        if (readiness.ready) {
                                            stringResource(R.string.dispatch_assignment_ready)
                                        } else {
                                            stringResource(R.string.dispatch_assignment_not_ready)
                                        },
                                        color = if (readiness.ready) {
                                            NexaColors.Success
                                        } else {
                                            NexaColors.Warning
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (state.status == DispatchAssignmentStatus.Loading ||
                state.status == DispatchAssignmentStatus.Saving
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.dispatch_assignment_loading))
                    }
                }
            }

            state.assignment?.let { assignment ->
                item {
                    AssignmentFactCard(assignment)
                }
                if (state.canTrackDriverLocation) {
                    item {
                        DriverLocationCard(
                            state = state,
                            onRefresh = onRefreshDriverLocation,
                            onShowMap = onShowDriverLocationMap
                        )
                    }
                }
                if (onIdentifyHandoff != null &&
                    assignment.current && assignment.deliveryId != null &&
                    state.status == DispatchAssignmentStatus.Current &&
                    state.pendingIntent == null
                ) {
                    item {
                        OutlinedButton(onClick = onIdentifyHandoff) {
                            Text("Identificar traspaso preparado")
                        }
                    }
                }
                if (onChangePlan != null) {
                    item {
                        OutlinedButton(onClick = onChangePlan) {
                            Text(stringResource(R.string.dispatch_assignment_change_plan))
                        }
                    }
                }
            }

            if (state.assignment == null && state.candidates.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.dispatch_assignment_choose_driver),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(state.candidates, key = { it.membershipId.lowercase() }) { candidate ->
                    DriverCandidateCard(
                        candidate = candidate,
                        selected = state.selectedMembershipId == candidate.membershipId,
                        enabled = state.status == DispatchAssignmentStatus.Current &&
                            state.readiness?.ready == true && state.assignmentPermission,
                        onClick = { onSelectDriver(candidate.membershipId) }
                    )
                }
            }

            item {
                Button(
                    onClick = onAssign,
                    enabled = state.canAssign,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.dispatch_assignment_assign)) }
                if (state.canReplay) {
                    OutlinedButton(
                        onClick = onReplay,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.dispatch_assignment_replay)) }
                }
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = state.status != DispatchAssignmentStatus.Loading &&
                        state.status != DispatchAssignmentStatus.Saving,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.dispatch_assignment_refresh)) }
            }
        }
    }
}

@Composable
private fun DispatchDriverLocationAutoRefresh(
    state: DispatchAssignmentUiState,
    onRefresh: () -> Unit,
    onVisibilityChanged: (Boolean) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        )
    }
    val visibilityAction = rememberUpdatedState(onVisibilityChanged)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isResumed = true
                    visibilityAction.value(true)
                }

                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> {
                    isResumed = false
                    visibilityAction.value(false)
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        visibilityAction.value(isResumed)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            visibilityAction.value(false)
        }
    }

    val refreshAction = rememberUpdatedState(onRefresh)
    val assignedMembershipId = state.assignment
        ?.takeIf { state.canTrackDriverLocation }
        ?.responsibleMembershipId
    LaunchedEffect(
        lifecycleOwner,
        isResumed,
        state.authorityEpoch,
        state.fulfillmentId,
        state.status,
        state.readiness?.deliveryId,
        state.readiness?.deliveryStatus,
        state.assignment?.deliveryId,
        assignedMembershipId
    ) {
        if (!isResumed || state.status != DispatchAssignmentStatus.Current ||
            state.authorityEpoch <= 0 || assignedMembershipId == null
        ) {
            return@LaunchedEffect
        }
        refreshAction.value()
        while (true) {
            delay(DRIVER_LOCATION_REFRESH_INTERVAL_MILLIS)
            refreshAction.value()
        }
    }
}

@Composable
private fun DriverLocationCard(
    state: DispatchAssignmentUiState,
    onRefresh: () -> Unit,
    onShowMap: (@Composable (DriverLiveLocation) -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OperationsGlyph(OperationsGlyphKind.Driver)
                Text(
                    stringResource(R.string.dispatch_driver_location_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                stringResource(R.string.dispatch_driver_location_authority),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when (state.driverLocationStatus) {
                DispatchDriverLocationStatus.Idle -> Text(
                    stringResource(R.string.dispatch_driver_location_idle)
                )

                DispatchDriverLocationStatus.Loading -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.dispatch_driver_location_loading))
                }

                DispatchDriverLocationStatus.Current -> state.driverLocation?.let { location ->
                    Text(
                        stringResource(
                            R.string.dispatch_driver_location_coordinates,
                            location.latitude,
                            location.longitude
                        )
                    )
                    Text(
                        stringResource(
                            R.string.dispatch_driver_location_accuracy,
                            location.accuracyMeters
                        )
                    )
                    Text(
                        stringResource(
                            R.string.dispatch_driver_location_captured,
                            location.capturedAt
                        )
                    )
                    Text(
                        stringResource(
                            R.string.dispatch_driver_location_expires,
                            location.expiresAt
                        )
                    )
                    onShowMap?.invoke(location)
                } ?: Text(stringResource(R.string.dispatch_driver_location_no_recent))

                DispatchDriverLocationStatus.NoRecentLocation -> Text(
                    stringResource(R.string.dispatch_driver_location_no_recent)
                )

                DispatchDriverLocationStatus.AssignmentChanged -> Text(
                    stringResource(R.string.dispatch_driver_location_assignment_changed)
                )

                DispatchDriverLocationStatus.PermissionDenied -> Text(
                    stringResource(R.string.dispatch_driver_location_permission)
                )

                DispatchDriverLocationStatus.NetworkUnavailable -> Text(
                    stringResource(R.string.dispatch_driver_location_network)
                )

                DispatchDriverLocationStatus.ServiceUnavailable -> Text(
                    stringResource(R.string.dispatch_driver_location_service)
                )
            }
            if (state.driverLocationStatus != DispatchDriverLocationStatus.Loading) {
                OutlinedButton(onClick = onRefresh) {
                    Text(stringResource(R.string.dispatch_driver_location_refresh))
                }
            }
        }
    }
}

@Composable
private fun AssignmentStatusCard(status: DispatchAssignmentStatus) {
    val (title, body) = when (status) {
        DispatchAssignmentStatus.Initial ->
            R.string.dispatch_assignment_initial_title to R.string.dispatch_assignment_initial_body

        DispatchAssignmentStatus.Loading ->
            R.string.dispatch_assignment_loading_title to R.string.dispatch_assignment_loading

        DispatchAssignmentStatus.Saving ->
            R.string.dispatch_assignment_saving_title to R.string.dispatch_assignment_saving_body

        DispatchAssignmentStatus.Current ->
            R.string.dispatch_assignment_current_title to R.string.dispatch_assignment_current_body

        DispatchAssignmentStatus.NotReady ->
            R.string.dispatch_assignment_not_ready_title to
                R.string.dispatch_assignment_not_ready_body

        DispatchAssignmentStatus.PermissionUnknown ->
            R.string.dispatch_assignment_permission_unknown_title to
                R.string.dispatch_assignment_permission_unknown_body

        DispatchAssignmentStatus.PermissionDenied ->
            R.string.dispatch_assignment_permission_denied_title to
                R.string.dispatch_assignment_permission_denied_body

        DispatchAssignmentStatus.Stale ->
            R.string.dispatch_assignment_stale_title to R.string.dispatch_assignment_stale_body

        DispatchAssignmentStatus.Conflict ->
            R.string.dispatch_assignment_conflict_title to
                R.string.dispatch_assignment_conflict_body

        DispatchAssignmentStatus.UnknownOutcome ->
            R.string.dispatch_assignment_unknown_title to R.string.dispatch_assignment_unknown_body

        DispatchAssignmentStatus.NetworkUnavailable ->
            R.string.dispatch_assignment_network_title to R.string.dispatch_assignment_network_body

        DispatchAssignmentStatus.ServiceUnavailable ->
            R.string.dispatch_assignment_service_title to R.string.dispatch_assignment_service_body

        DispatchAssignmentStatus.ContextInvalidated ->
            R.string.dispatch_assignment_context_title to R.string.dispatch_assignment_context_body

        DispatchAssignmentStatus.SessionInvalidated ->
            R.string.dispatch_assignment_session_title to R.string.dispatch_assignment_session_body
    }
    val (containerColor, borderColor, titleColor) = when (status) {
        DispatchAssignmentStatus.Current -> Triple(
            NexaColors.SuccessSurface,
            NexaColors.SuccessBorder,
            NexaColors.Success
        )

        DispatchAssignmentStatus.NotReady,
        DispatchAssignmentStatus.PermissionUnknown,
        DispatchAssignmentStatus.Stale,
        DispatchAssignmentStatus.UnknownOutcome,
        DispatchAssignmentStatus.NetworkUnavailable,
        DispatchAssignmentStatus.ServiceUnavailable -> Triple(
            NexaColors.WarningSurface,
            NexaColors.WarningBorder,
            NexaColors.Warning
        )

        DispatchAssignmentStatus.PermissionDenied,
        DispatchAssignmentStatus.Conflict,
        DispatchAssignmentStatus.ContextInvalidated,
        DispatchAssignmentStatus.SessionInvalidated -> Triple(
            NexaColors.DangerSurface,
            NexaColors.DangerBorder,
            NexaColors.Danger
        )

        DispatchAssignmentStatus.Initial,
        DispatchAssignmentStatus.Loading,
        DispatchAssignmentStatus.Saving -> Triple(
            NexaColors.InfoSurface,
            NexaColors.InfoBorder,
            NexaColors.Info
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(title),
                color = titleColor,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private const val DRIVER_LOCATION_REFRESH_INTERVAL_MILLIS = 15_000L

@Composable
private fun DriverCandidateCard(
    candidate: DispatchDriverCandidate,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "${candidate.displayName}, ${candidate.email}" },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                NexaColors.InfoSurface
            } else {
                NexaColors.Surface
            }
        ),
        border = BorderStroke(1.dp, if (selected) NexaColors.Primary else NexaColors.Border),
        shape = NexaShapes.card
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = if (selected) NexaColors.PrimaryContainer else NexaColors.SurfaceInset
            ) {
                Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Text(
                        candidate.displayName.trim().firstOrNull()?.uppercase() ?: "?",
                        color = NexaColors.PrimaryStrong,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    candidate.displayName,
                    color = NexaColors.TextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    candidate.email,
                    color = NexaColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        }
    }
}

@Composable
private fun AssignmentFactCard(assignment: PreparedFulfillmentDriverAssignment) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NexaColors.SuccessSurface),
        border = BorderStroke(1.dp, NexaColors.SuccessBorder),
        shape = NexaShapes.card
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OperationsGlyph(OperationsGlyphKind.Driver)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    stringResource(R.string.dispatch_assignment_assigned),
                    color = NexaColors.Success,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    assignment.responsibleDisplayName,
                    color = NexaColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(
                        R.string.dispatch_assignment_when,
                        assignment.assignedAt.toString()
                    ),
                    color = NexaColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
