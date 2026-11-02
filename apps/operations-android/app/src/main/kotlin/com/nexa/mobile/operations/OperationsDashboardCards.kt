package com.nexa.mobile.operations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaShapes
import com.nexa.mobile.operations.core.designsystem.R as DesignR
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive

@Composable
internal fun OperationsDashboardCards(
    snapshot: OperationsDashboardSnapshot,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onMetricClick: (OperationsDashboardMetricKey) -> (() -> Unit)? = { null }
) {
    if (snapshot.metrics.isEmpty()) return

    val hero = snapshot.metrics.firstOrNull { it.key == snapshot.role.primaryMetricKey() }
        ?: snapshot.metrics.first()
    val secondary = snapshot.metrics.filter {
        it.key != hero.key && it.key !in SIGNAL_METRIC_KEYS
    }
    val signals = snapshot.metrics.filter { it.key in SIGNAL_METRIC_KEYS }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val largeText = LocalDensity.current.fontScale >= 1.5f
        val wideLayout = !largeText && maxWidth >= 840.dp
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (wideLayout && (secondary.isNotEmpty() || signals.isNotEmpty())) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    DashboardHeroMetric(
                        metric = hero,
                        onClick = onMetricClick(hero.key),
                        actionLabelResource = hero.key.actionLabelResource(),
                        modifier = Modifier.weight(1.25f).heightIn(min = 184.dp)
                    )
                    Column(
                        modifier = Modifier.weight(0.9f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        secondary.forEach { metric ->
                            DashboardCompactMetric(
                                metric = metric,
                                onClick = onMetricClick(metric.key),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (signals.isNotEmpty()) {
                            SignalMetrics(
                                metrics = signals,
                                stacked = false,
                                onMetricClick = onMetricClick
                            )
                        }
                    }
                }
            } else {
                DashboardHeroMetric(
                    metric = hero,
                    onClick = onMetricClick(hero.key),
                    actionLabelResource = hero.key.actionLabelResource(),
                    modifier = Modifier.fillMaxWidth().heightIn(
                        min = if (largeText) 156.dp else 144.dp
                    )
                )
                if (secondary.isNotEmpty()) {
                    if (largeText || secondary.size == 1) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            secondary.forEach { metric ->
                                DashboardCompactMetric(
                                    metric = metric,
                                    onClick = onMetricClick(metric.key),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            secondary.forEach { metric ->
                                DashboardCompactMetric(
                                    metric = metric,
                                    onClick = onMetricClick(metric.key),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                if (signals.isNotEmpty()) {
                    SignalMetrics(
                        metrics = signals,
                        stacked = largeText,
                        onMetricClick = onMetricClick
                    )
                }
            }

            RefreshDashboardAction(onClick = onRefresh)
        }
    }
}

@Composable
private fun DashboardHeroMetric(
    metric: OperationsDashboardMetric,
    onClick: (() -> Unit)?,
    actionLabelResource: Int?,
    modifier: Modifier = Modifier
) {
    val foreground = if (metric.status ==
        OperationsDashboardMetricStatus.Unavailable
    ) {
        NexaColors.TextSecondary
    } else {
        Color.White
    }
    val background = if (metric.status ==
        OperationsDashboardMetricStatus.Unavailable
    ) {
        NexaColors.SurfaceInset
    } else if (metric.key ==
        OperationsDashboardMetricKey.Ready
    ) {
        NexaColors.Success
    } else {
        NexaColors.PrimaryStrong
    }
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(metric.labelResource),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                MetricStatus(metric, foreground = foreground)
            }
            Text(
                text = metric.value?.toString() ?: "—",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 48.sp,
                    lineHeight = 52.sp
                ),
                fontWeight = FontWeight.Bold,
                color = foreground,
                maxLines = 1,
                softWrap = false
            )
            if (onClick != null && actionLabelResource != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        stringResource(actionLabelResource),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = foreground
                    )
                    Icon(
                        painter = painterResource(DesignR.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = foreground,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    if (onClick == null) {
        Surface(modifier = modifier, shape = NexaShapes.card, color = background, content = {
            content()
        })
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = NexaShapes.card,
            color = background,
            content = { content() }
        )
    }
}

@Composable
private fun DashboardCompactMetric(
    metric: OperationsDashboardMetric,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val palette = metric.key.palette()
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = painterResource(metric.key.iconResource()),
                contentDescription = null,
                tint = palette.foreground,
                modifier = Modifier.size(22.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(metric.labelResource),
                    style = MaterialTheme.typography.labelMedium,
                    color = NexaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    metric.value?.toString() ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = NexaColors.TextPrimary,
                    maxLines = 1
                )
            }
            MetricStatus(metric, foreground = palette.foreground)
            if (onClick != null) {
                Icon(
                    painter = painterResource(DesignR.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = NexaColors.TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
    if (onClick == null) {
        Surface(
            modifier = modifier.heightIn(min = 72.dp),
            shape = NexaShapes.card,
            color = palette.surface,
            content = { content() }
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier.heightIn(min = 72.dp),
            shape = NexaShapes.card,
            color = palette.surface,
            content = { content() }
        )
    }
}

@Composable
private fun SignalMetrics(
    metrics: List<OperationsDashboardMetric>,
    stacked: Boolean,
    onMetricClick: (OperationsDashboardMetricKey) -> (() -> Unit)?
) {
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            metrics.forEach { metric ->
                DashboardSignalMetric(
                    metric = metric,
                    onClick = onMetricClick(metric.key),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            metrics.forEach { metric ->
                DashboardSignalMetric(
                    metric = metric,
                    onClick = onMetricClick(metric.key),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DashboardSignalMetric(
    metric: OperationsDashboardMetric,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val palette = if (metric.status == OperationsDashboardMetricStatus.Current &&
        metric.value == 0L
    ) {
        DashboardAccentPalette(NexaColors.TextSecondary, NexaColors.CardSurface)
    } else {
        metric.key.palette()
    }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = painterResource(metric.key.iconResource()),
                contentDescription = null,
                tint = palette.foreground,
                modifier = Modifier.size(20.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(metric.labelResource),
                    style = MaterialTheme.typography.labelMedium,
                    color = NexaColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    metric.value?.toString() ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NexaColors.TextPrimary,
                    maxLines = 1
                )
            }
            MetricStatus(metric, foreground = palette.foreground)
            if (onClick != null) {
                Icon(
                    painter = painterResource(DesignR.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = NexaColors.TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
    if (onClick == null) {
        Surface(
            modifier = modifier.heightIn(min = 68.dp),
            shape = NexaShapes.row,
            color = palette.surface,
            content = { content() }
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier.heightIn(min = 68.dp),
            shape = NexaShapes.row,
            color = palette.surface,
            content = { content() }
        )
    }
}

@Composable
private fun MetricStatus(metric: OperationsDashboardMetric, foreground: Color) {
    val loadingLabel = stringResource(R.string.dashboard_loading_short)
    val unavailableLabel = stringResource(R.string.dashboard_unavailable_short)
    when (metric.status) {
        OperationsDashboardMetricStatus.Loading -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp).semantics {
                contentDescription = loadingLabel
            },
            strokeWidth = 2.dp,
            color = foreground
        )

        OperationsDashboardMetricStatus.Current -> Unit

        OperationsDashboardMetricStatus.Unavailable -> Icon(
            painter = painterResource(DesignR.drawable.ic_warning),
            contentDescription = unavailableLabel,
            tint = foreground,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun RefreshDashboardAction(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            onClick = onClick,
            modifier = Modifier.heightIn(min = 48.dp),
            shape = NexaShapes.button,
            color = NexaColors.Surface
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(DesignR.drawable.ic_sync),
                    contentDescription = null,
                    tint = NexaColors.PrimaryStrong,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    stringResource(R.string.dashboard_refresh),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = NexaColors.PrimaryStrong
                )
            }
        }
    }
}

@Composable
internal fun OperationsDashboardAutoRefresh(
    authorityEpoch: Long,
    scopeKey: String,
    roleKey: String,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    changeSignals: Flow<Unit> = emptyFlow()
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val openAction = rememberUpdatedState(onOpen)
    val refreshAction = rememberUpdatedState(onRefresh)

    androidx.compose.runtime.LaunchedEffect(
        lifecycle,
        authorityEpoch,
        scopeKey,
        roleKey,
        changeSignals
    ) {
        if (authorityEpoch <= 0 || scopeKey.isBlank() || roleKey.isBlank()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            openAction.value()
            merge(
                changeSignals,
                flow {
                    while (isActive) {
                        delay(DASHBOARD_REFRESH_INTERVAL_MILLIS)
                        emit(Unit)
                    }
                }
            ).conflate().collect { refreshAction.value() }
        }
    }
}

private data class DashboardAccentPalette(val foreground: Color, val surface: Color)

private fun OperationsDashboardRole.primaryMetricKey(): OperationsDashboardMetricKey = when (this) {
    OperationsDashboardRole.Warehouse -> OperationsDashboardMetricKey.TotalLots

    OperationsDashboardRole.Dispatch,
    OperationsDashboardRole.Management -> OperationsDashboardMetricKey.Ready

    OperationsDashboardRole.Sales -> OperationsDashboardMetricKey.Customers

    OperationsDashboardRole.Driver,
    OperationsDashboardRole.Unknown -> OperationsDashboardMetricKey.TotalLots
}

private fun OperationsDashboardMetricKey.actionLabelResource(): Int? = when (this) {
    OperationsDashboardMetricKey.TotalLots -> R.string.dashboard_open_stock
    OperationsDashboardMetricKey.Ready -> R.string.dashboard_open_dispatch
    OperationsDashboardMetricKey.Customers -> R.string.dashboard_open_customers
    else -> null
}

private fun OperationsDashboardMetricKey.palette(): DashboardAccentPalette = when (this) {
    OperationsDashboardMetricKey.Preparations -> DashboardAccentPalette(
        NexaColors.Info,
        NexaColors.InfoSurface
    )

    OperationsDashboardMetricKey.TotalLots -> DashboardAccentPalette(
        NexaColors.Info,
        NexaColors.InfoSurface
    )

    OperationsDashboardMetricKey.HeldLots -> DashboardAccentPalette(
        NexaColors.Warning,
        NexaColors.WarningSurface
    )

    OperationsDashboardMetricKey.ExpiredLots -> DashboardAccentPalette(
        NexaColors.Danger,
        NexaColors.DangerSurface
    )

    OperationsDashboardMetricKey.Ready -> DashboardAccentPalette(
        NexaColors.Success,
        NexaColors.SuccessSurface
    )

    OperationsDashboardMetricKey.InRoute -> DashboardAccentPalette(
        NexaColors.Info,
        NexaColors.InfoSurface
    )

    OperationsDashboardMetricKey.Attention -> DashboardAccentPalette(
        NexaColors.AttentionText,
        NexaColors.AttentionSurface
    )

    OperationsDashboardMetricKey.Customers -> DashboardAccentPalette(
        NexaColors.SalesText,
        NexaColors.SalesSurface
    )
}

private fun OperationsDashboardMetricKey.iconResource(): Int = when (this) {
    OperationsDashboardMetricKey.Preparations -> DesignR.drawable.ic_check_square
    OperationsDashboardMetricKey.TotalLots -> DesignR.drawable.ic_box
    OperationsDashboardMetricKey.HeldLots -> DesignR.drawable.ic_pause
    OperationsDashboardMetricKey.ExpiredLots -> DesignR.drawable.ic_calendar
    OperationsDashboardMetricKey.Ready -> DesignR.drawable.ic_check
    OperationsDashboardMetricKey.InRoute -> DesignR.drawable.ic_route
    OperationsDashboardMetricKey.Attention -> DesignR.drawable.ic_warning
    OperationsDashboardMetricKey.Customers -> DesignR.drawable.ic_identity
}

private val SIGNAL_METRIC_KEYS = setOf(
    OperationsDashboardMetricKey.HeldLots,
    OperationsDashboardMetricKey.ExpiredLots,
    OperationsDashboardMetricKey.Attention
)

private const val DASHBOARD_REFRESH_INTERVAL_MILLIS = 15_000L
