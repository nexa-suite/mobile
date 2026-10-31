package com.nexa.mobile.operations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaShapes
import com.nexa.mobile.operations.core.designsystem.NexaTopAppBar
import com.nexa.mobile.operations.core.designsystem.R as DesignR

private data class HomeAction(
    val key: String,
    val titleResource: Int,
    val icon: Int,
    val onClick: () -> Unit
)

@Composable
internal fun OperationsHomeScreen(
    company: String,
    workspace: String,
    role: String,
    entries: List<ConnectedOperationEntry>,
    dashboard: OperationsDashboardSnapshot?,
    onRefresh: () -> Unit,
    onOpen: (ConnectedOperationEntry) -> Unit,
    onReceive: (() -> Unit)? = null,
    onPick: (() -> Unit)? = null,
    onStock: (() -> Unit)? = null,
    onQuickActions: (() -> Unit)? = null
) {
    val roleType = dashboard?.role ?: OperationsDashboardRole.Unknown
    val actions = buildHomeActions(roleType, entries, onOpen, onReceive)
    Column(Modifier.fillMaxSize()) {
        NexaTopAppBar(title = "Operations")
        Box(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                Modifier.widthIn(max = 1200.dp).fillMaxWidth()
                    .padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeContext(company = company, workspace = workspace, role = role)

                dashboard?.let { snapshot ->
                    val dispatchReadiness = entries.firstOrNull { it.key == "dispatch.readiness" }
                    val customerList = entries.firstOrNull { it.key == "commercial.customers" }
                    val dispatchReadinessAction: (() -> Unit)? =
                        dispatchReadiness?.let { entry -> { onOpen(entry) } }
                    OperationsDashboardCards(
                        snapshot = snapshot,
                        onRefresh = onRefresh,
                        onMetricClick = { key ->
                            when (snapshot.role) {
                                OperationsDashboardRole.Warehouse -> when (key) {
                                    OperationsDashboardMetricKey.Preparations -> onPick

                                    OperationsDashboardMetricKey.TotalLots,
                                    OperationsDashboardMetricKey.HeldLots,
                                    OperationsDashboardMetricKey.ExpiredLots -> onStock

                                    else -> null
                                }

                                OperationsDashboardRole.Dispatch,
                                OperationsDashboardRole.Management -> when (key) {
                                    OperationsDashboardMetricKey.Preparations,
                                    OperationsDashboardMetricKey.Ready,
                                    OperationsDashboardMetricKey.InRoute,
                                    OperationsDashboardMetricKey.Attention ->
                                        dispatchReadinessAction

                                    else -> null
                                }

                                OperationsDashboardRole.Sales -> if (
                                    key == OperationsDashboardMetricKey.Customers
                                ) {
                                    customerList?.let { entry -> { onOpen(entry) } }
                                } else {
                                    null
                                }

                                OperationsDashboardRole.Driver,
                                OperationsDashboardRole.Unknown -> null
                            }
                        }
                    )
                }

                if (actions.isNotEmpty()) {
                    HomeQuickActionsSection(actions = actions, onMoreActions = onQuickActions)
                }
            }
        }
    }
}

@Composable
private fun HomeContext(company: String, workspace: String, role: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (company.isNotBlank()) {
            Text(
                company,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = NexaColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (role.isNotBlank()) {
                Surface(shape = NexaShapes.control, color = NexaColors.PrimaryContainer) {
                    Text(
                        role.substringBefore(" · "),
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = NexaColors.PrimaryStrong
                    )
                }
            }
            if (workspace.isNotBlank()) {
                Text(
                    workspace,
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun HomeQuickActionsSection(actions: List<HomeAction>, onMoreActions: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.home_start),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = NexaColors.TextPrimary
            )
            onMoreActions?.let { open ->
                TextButton(
                    onClick = open,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.home_more_actions))
                }
            }
        }
        Box {
            val columns = if (LocalDensity.current.fontScale >= 1.5f) 1 else 2
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.chunked(columns).forEach { rowActions ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowActions.forEach { action ->
                            HomeActionTile(
                                action = action,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(columns - rowActions.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeActionTile(action: HomeAction, modifier: Modifier = Modifier) {
    val palette = action.key.actionPalette()
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Surface(
        onClick = action.onClick,
        modifier = modifier.heightIn(min = 64.dp),
        shape = NexaShapes.row,
        color = palette.surface,
        interactionSource = interactionSource,
        border = if (focused) BorderStroke(2.dp, NexaColors.Primary) else null
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painterResource(action.icon),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = palette.foreground
            )
            Text(
                stringResource(action.titleResource),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = NexaColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                painterResource(DesignR.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = NexaColors.TextMuted
            )
        }
    }
}

private fun buildHomeActions(
    role: OperationsDashboardRole,
    entries: List<ConnectedOperationEntry>,
    onOpen: (ConnectedOperationEntry) -> Unit,
    onReceive: (() -> Unit)?
): List<HomeAction> {
    val priorityKeys = when (role) {
        OperationsDashboardRole.Warehouse -> listOf(
            "warehouse.batch",
            "warehouse.inbound-discrepancy",
            "warehouse.transfer",
            "warehouse.transfer-receipt",
            "warehouse.temperature",
            "warehouse.disposition"
        )

        OperationsDashboardRole.Dispatch -> listOf(
            "dispatch.readiness",
            "dispatch.loads",
            "driver.deliveries",
            "dispatch.instructions",
            "operations.notifications"
        )

        OperationsDashboardRole.Management -> listOf(
            "operations.overview",
            "operations.exceptions",
            "bom.exceptions",
            "operations.notifications"
        )

        OperationsDashboardRole.Sales -> listOf(
            "commercial.customers",
            "commercial.catalog",
            "commercial.direct-order",
            "commercial.request"
        )

        OperationsDashboardRole.Driver -> listOf("driver.deliveries", "driver.loads")

        OperationsDashboardRole.Unknown -> entries.map(ConnectedOperationEntry::key)
    }
    val availableByKey = entries.associateBy(ConnectedOperationEntry::key)
    val actions = buildList {
        if (role == OperationsDashboardRole.Warehouse && onReceive != null) {
            add(
                HomeAction(
                    key = "warehouse.receive",
                    titleResource = R.string.home_receive_title,
                    icon = DesignR.drawable.ic_truck,
                    onClick = onReceive
                )
            )
        }
        priorityKeys.asSequence()
            .mapNotNull(availableByKey::get)
            .distinctBy(ConnectedOperationEntry::key)
            .take((4 - size).coerceAtLeast(0))
            .forEach { entry ->
                val titleResource = entry.key.shortTitleResource() ?: return@forEach
                add(
                    HomeAction(
                        key = entry.key,
                        titleResource = titleResource,
                        icon = entry.workIconResource(),
                        onClick = { onOpen(entry) }
                    )
                )
            }
    }
    return actions
}

private fun String.shortTitleResource(): Int? = when (this) {
    "warehouse.batch" -> R.string.home_action_picking
    "warehouse.inbound-discrepancy" -> R.string.home_action_discrepancy
    "warehouse.transfer" -> R.string.home_action_transfer
    "warehouse.transfer-receipt" -> R.string.home_action_transfer_receipt
    "warehouse.temperature" -> R.string.home_action_temperature
    "warehouse.disposition" -> R.string.home_action_disposition
    "dispatch.readiness" -> R.string.home_action_dispatch_prep
    "dispatch.loads" -> R.string.home_action_loads
    "driver.deliveries" -> R.string.home_action_deliveries
    "driver.loads" -> R.string.home_action_accept_load
    "dispatch.instructions" -> R.string.home_action_instructions
    "commercial.customers" -> R.string.home_action_customers
    "commercial.catalog" -> R.string.home_action_catalog
    "commercial.direct-order" -> R.string.home_action_new_order
    "commercial.request" -> R.string.home_action_request
    "operations.overview" -> R.string.home_action_operations
    "operations.exceptions" -> R.string.home_action_blocked_work
    "bom.exceptions" -> R.string.home_action_exceptions
    "operations.notifications" -> R.string.home_action_updates
    else -> null
}

internal fun ConnectedOperationEntry.workIconResource(): Int = when {
    key == "warehouse.batch" || key == "dispatch.readiness" -> DesignR.drawable.ic_check_square

    key == "warehouse.inbound-discrepancy" || key.contains("exception") ->
        DesignR.drawable.ic_warning

    key == "warehouse.transfer" -> DesignR.drawable.ic_sync

    key == "warehouse.transfer-receipt" -> DesignR.drawable.ic_check

    key == "warehouse.temperature" -> DesignR.drawable.ic_snowflake

    key == "warehouse.disposition" || key == "dispatch.instructions" ->
        DesignR.drawable.ic_document

    key == "dispatch.loads" || key == "driver.loads" -> DesignR.drawable.ic_route

    key == "driver.deliveries" || key == "warehouse.receive" -> DesignR.drawable.ic_truck

    key == "operations.notifications" -> DesignR.drawable.ic_bell

    key == "commercial.catalog" -> DesignR.drawable.ic_box

    key == "commercial.customers" -> DesignR.drawable.ic_identity

    key == "commercial.direct-order" -> DesignR.drawable.ic_shopping_bag

    key == "commercial.request" -> DesignR.drawable.ic_send

    else -> DesignR.drawable.ic_check
}

private data class HomeActionPalette(val foreground: Color, val surface: Color)

private fun String.actionPalette(): HomeActionPalette = when {
    this == "warehouse.temperature" || startsWith("driver.") -> HomeActionPalette(
        NexaColors.ColdRefrigeratedText,
        NexaColors.ColdRefrigeratedSurface
    )

    contains("discrepancy") || contains("exception") || this == "warehouse.disposition" ->
        HomeActionPalette(NexaColors.AttentionText, NexaColors.AttentionSurface)

    this == "commercial.request" -> HomeActionPalette(NexaColors.Warning, NexaColors.WarningSurface)

    this == "commercial.direct-order" || this == "warehouse.transfer" ||
        this == "dispatch.instructions" ->
        HomeActionPalette(Color(0xFF3730A3), Color(0xFFEEF2FF))

    this == "commercial.catalog" || this == "warehouse.batch" ->
        HomeActionPalette(NexaColors.Info, NexaColors.InfoSurface)

    this == "dispatch.readiness" -> HomeActionPalette(NexaColors.Success, NexaColors.SuccessSurface)

    this == "operations.notifications" -> HomeActionPalette(
        NexaColors.Warning,
        NexaColors.WarningSurface
    )

    startsWith("warehouse.") -> HomeActionPalette(NexaColors.Success, NexaColors.SuccessSurface)

    startsWith("dispatch.") -> HomeActionPalette(NexaColors.Info, NexaColors.InfoSurface)

    startsWith("commercial.") -> HomeActionPalette(NexaColors.SalesText, NexaColors.SalesSurface)

    else -> HomeActionPalette(NexaColors.Info, NexaColors.InfoSurface)
}
