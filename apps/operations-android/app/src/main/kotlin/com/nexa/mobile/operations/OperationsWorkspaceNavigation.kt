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
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WarehouseRoute
import com.nexa.mobile.operations.core.designsystem.NexaColors
import com.nexa.mobile.operations.core.designsystem.NexaSizes
import com.nexa.mobile.operations.core.designsystem.R as DesignSystemR

// Destinations keep a stable position within the current role and verified capabilities.
internal data class OperationsNavigationSection(
    val labelResource: Int,
    val iconResource: Int,
    val entry: ConnectedOperationEntry?,
    val destinationKeys: Set<String>,
    val warehouseRoute: WarehouseRoute? = null
)

internal fun operationsNavigationSections(
    entries: List<ConnectedOperationEntry>,
    roles: Set<String>,
    activeEntryKey: String? = null,
    warehouseRoutes: Set<WarehouseRoute> = emptySet()
): List<OperationsNavigationSection> {
    fun entry(key: String, label: Int, icon: Int, related: Set<String> = setOf(key)) =
        entries.firstOrNull { it.key == key }?.let {
            OperationsNavigationSection(label, icon, it, related)
        }
    fun warehouse(route: WarehouseRoute, label: Int, icon: Int) =
        route.takeIf { it in warehouseRoutes }?.let {
            val related = when (route) {
                WarehouseRoute.Picking -> setOf("warehouse.batch", "warehouse.lot-substitution")

                WarehouseRoute.StockCondition -> entries.filter {
                    it.key.startsWith("warehouse.") &&
                        it.key !in setOf("warehouse.batch", "warehouse.lot-substitution")
                }.map { it.key }.toSet()

                else -> emptySet()
            }
            OperationsNavigationSection(label, icon, null, related, it)
        }
    return when {
        roles.any { it in setOf("WAREHOUSE", "WAREHOUSE_OPERATOR") } -> listOfNotNull(
            warehouse(
                WarehouseRoute.StockCondition,
                R.string.nav_inventory,
                DesignSystemR.drawable.ic_box
            ),
            warehouse(
                WarehouseRoute.Picking,
                R.string.nav_picking,
                DesignSystemR.drawable.ic_check_square
            ),
            warehouse(
                WarehouseRoute.ProductSearch,
                R.string.nav_catalog,
                DesignSystemR.drawable.ic_shopping_bag
            )
        )

        roles.any { it in setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR") } -> listOfNotNull(
            entry(
                "dispatch.readiness",
                R.string.work_hub_dispatch,
                DesignSystemR.drawable.ic_send,
                entries.filter {
                    it.key.startsWith("dispatch.") && it.key != "dispatch.loads"
                }.map { it.key }.toSet()
            ),
            entry(
                "driver.deliveries",
                R.string.nav_deliveries,
                DesignSystemR.drawable.ic_truck,
                entries.filter { it.key.startsWith("driver.") }.map { it.key }.toSet()
            ),
            entry("dispatch.loads", R.string.nav_loads, DesignSystemR.drawable.ic_box)
        )

        roles.any { it in setOf("SALES", "SALES_REPRESENTATIVE", "COMMERCIAL") } -> listOfNotNull(
            entry(
                "commercial.customers",
                R.string.nav_customers,
                DesignSystemR.drawable.ic_identity
            ),
            entry("commercial.catalog", R.string.nav_catalog, DesignSystemR.drawable.ic_box),
            entry(
                "commercial.progress",
                R.string.nav_orders,
                DesignSystemR.drawable.ic_shopping_bag,
                setOf(
                    "commercial.progress",
                    "commercial.direct-order",
                    "commercial.request",
                    "commercial.instructions"
                )
            )
        )

        else -> listOfNotNull(
            entry("operations.overview", R.string.nav_overview, DesignSystemR.drawable.ic_box),
            entry(
                "bom.exceptions",
                R.string.nav_attention,
                DesignSystemR.drawable.ic_warning,
                setOf("bom.exceptions", "operations.exceptions")
            ),
            entry(
                "operations.notifications",
                R.string.nav_updates,
                DesignSystemR.drawable.ic_check_square
            )
        )
    }
}

@Composable
internal fun OperationsWorkspaceNavigationBar(
    selectedIndex: Int,
    sections: List<OperationsNavigationSection>,
    onHome: () -> Unit,
    onOpen: (ConnectedOperationEntry) -> Unit,
    onWarehouseRoute: (WarehouseRoute) -> Unit = {}
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
                    onClick = {
                        section.entry?.let(onOpen)
                        section.warehouseRoute?.let(onWarehouseRoute)
                    },
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
        }
    }
}
