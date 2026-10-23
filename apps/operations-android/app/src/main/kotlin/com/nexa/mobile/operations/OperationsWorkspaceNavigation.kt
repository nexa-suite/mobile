package com.nexa.mobile.operations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaSizes
import com.nexa.mobile.operations.core.designsystem.R as DesignSystemR

// Home + two work areas + Profile keeps the bottom bar usable on compact phones.
// Remaining authorized actions stay available from the actions menu.
private const val MAX_PRIMARY_SECTIONS = 2

internal data class OperationsNavigationSection(
    val labelResource: Int,
    val iconResource: Int,
    val entry: ConnectedOperationEntry,
    val destinationKeys: Set<String>
)

private enum class OperationsWorkGroup(
    val labelResource: Int,
    val iconResource: Int,
    val preferredEntryKeys: List<String>
) {
    Warehouse(
        R.string.work_hub_warehouse,
        DesignSystemR.drawable.ic_box,
        listOf("warehouse.batch", "warehouse.cycle-count", "warehouse.temperature")
    ),
    Dispatch(
        R.string.work_hub_dispatch,
        DesignSystemR.drawable.ic_truck,
        listOf("dispatch.readiness", "dispatch.loads")
    ),
    Management(
        R.string.work_hub_management,
        DesignSystemR.drawable.ic_identity,
        listOf("operations.overview", "operations.exceptions", "bom.exceptions")
    ),
    Commercial(
        R.string.work_hub_commercial,
        DesignSystemR.drawable.ic_shopping_bag,
        listOf("commercial.customers", "commercial.catalog", "commercial.direct-order")
    ),
    Other(
        R.string.work_hub_other,
        DesignSystemR.drawable.ic_identity,
        emptyList()
    )
}

internal fun operationsNavigationSections(
    entries: List<ConnectedOperationEntry>,
    roles: Set<String>,
    activeEntryKey: String? = null
): List<OperationsNavigationSection> {
    val entriesByGroup = entries.groupBy { it.workGroup() }
    val available = OperationsWorkGroup.entries.filter { entriesByGroup[it].orEmpty().isNotEmpty() }
    val activeGroup = entries.firstOrNull { it.key == activeEntryKey }
        ?.workGroup()
        ?.takeIf { it in available }
    val preferred = when {
        roles.any { it in setOf("WAREHOUSE", "WAREHOUSE_OPERATOR") } ->
            OperationsWorkGroup.Warehouse

        roles.any { it in setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR") } ->
            OperationsWorkGroup.Dispatch

        roles.any { it in setOf("BOM", "BUSINESS_OPERATIONS_MANAGER") } ->
            OperationsWorkGroup.Management

        roles.any { it in setOf("SALES", "SALES_REPRESENTATIVE", "COMMERCIAL") } ->
            OperationsWorkGroup.Commercial

        else -> null
    }
    val currentPreference = preferred?.takeIf { it in available }
    val prioritized = listOfNotNull(activeGroup, currentPreference).distinct()
    val ordered = prioritized + available.filterNot { it in prioritized }
    return ordered.take(MAX_PRIMARY_SECTIONS).map { group ->
        val groupEntries = entriesByGroup.getValue(group)
        val entry = group.preferredEntryKeys.firstNotNullOfOrNull { key ->
            groupEntries.firstOrNull { it.key == key }
        } ?: groupEntries.first()
        OperationsNavigationSection(
            labelResource = group.labelResource,
            iconResource = group.iconResource,
            entry = entry,
            destinationKeys = groupEntries.mapTo(linkedSetOf(), ConnectedOperationEntry::key)
        )
    }
}

@Composable
internal fun OperationsWorkspaceNavigationBar(
    selectedIndex: Int,
    sections: List<OperationsNavigationSection>,
    onHome: () -> Unit,
    onOpen: (ConnectedOperationEntry) -> Unit,
    onProfile: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = NexaColors.Surface,
        border = BorderStroke(NexaSizes.borderWidth, NexaColors.Border)
    ) {
        NavigationBar(
            containerColor = NexaColors.Surface,
            contentColor = NexaColors.TextPrimary,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            val itemColors = NavigationBarItemDefaults.colors(
                selectedIconColor = NexaColors.PrimaryStrong,
                selectedTextColor = NexaColors.PrimaryStrong,
                indicatorColor = NexaColors.PrimaryContainer,
                unselectedIconColor = NexaColors.TextSecondary,
                unselectedTextColor = NexaColors.TextSecondary
            )
            val homeSelected = selectedIndex == 0
            NavigationBarItem(
                selected = homeSelected,
                onClick = onHome,
                icon = {
                    Icon(
                        painter = painterResource(DesignSystemR.drawable.ic_home),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                label = {
                    Text(
                        text = stringResource(R.string.work_nav_home),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (homeSelected) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Medium
                            }
                        )
                    )
                },
                colors = itemColors
            )
            sections.forEachIndexed { index, section ->
                val selected = selectedIndex == index + 1
                NavigationBarItem(
                    selected = selected,
                    onClick = { onOpen(section.entry) },
                    icon = {
                        Icon(
                            painter = painterResource(section.iconResource),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(section.labelResource),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        )
                    },
                    colors = itemColors
                )
            }
            val profileSelected = selectedIndex == sections.size + 1
            NavigationBarItem(
                selected = profileSelected,
                onClick = onProfile,
                icon = {
                    Icon(
                        painter = painterResource(DesignSystemR.drawable.ic_identity),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                },
                label = {
                    Text(
                        text = stringResource(R.string.work_nav_profile),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (profileSelected) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Medium
                            }
                        )
                    )
                },
                colors = itemColors
            )
        }
    }
}

private fun ConnectedOperationEntry.workGroup(): OperationsWorkGroup = when {
    key.startsWith("warehouse.") -> OperationsWorkGroup.Warehouse

    key.startsWith("dispatch.") || key.startsWith("driver.") -> OperationsWorkGroup.Dispatch

    key.startsWith("operations.") || key == "bom.exceptions" -> OperationsWorkGroup.Management

    key.startsWith("commercial.") || key.startsWith("payments.") ->
        OperationsWorkGroup.Commercial

    else -> OperationsWorkGroup.Other
}
