package com.nexa.mobile.operations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexa.mobile.operations.catalogcommercialpolicy.presentation.workentry.WarehouseUiState
import com.nexa.mobile.operations.core.auth.session.SessionState
import com.nexa.mobile.operations.core.designsystem.NexaSearchField
import com.nexa.mobile.operations.core.designsystem.NexaTaskRow
import com.nexa.mobile.operations.core.designsystem.NexaWorkSection
import com.nexa.mobile.operations.core.designsystem.NexaWorkSummary
import com.nexa.mobile.operations.tenantaccessgovernance.domain.model.access.VerifiedContextAuthority
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessStage
import com.nexa.mobile.operations.tenantaccessgovernance.presentation.access.AccessUiState

/** Reachable capability entries, including explicit unavailable states, use current authority. */
internal data class ConnectedOperationEntry(
    val key: String,
    val label: String,
    val readPermissions: Set<String>,
    val visibleInHub: Boolean = true,
    val readRoles: Set<String> = emptySet(),
    val requiredReadPermissionGroups: List<Set<String>> = emptyList()
) {
    init {
        require(key.isNotBlank() && label.isNotBlank() && readPermissions.isNotEmpty())
        require(requiredReadPermissionGroups.all { it.isNotEmpty() })
    }
}

/** In-memory route, invalidated by authority replacement; never persisted as authority. */
internal data class ConnectedOperationRoute(
    val entryKey: String,
    val authorityEpoch: Long,
    val authority: VerifiedContextAuthority
) {
    override fun toString(): String = "ConnectedOperationRoute(entry=$entryKey, authority=REDACTED)"
}

internal object ConnectedOperationsNavigation {
    /** Verified Access context is authority; warehouse projection may be absent for this role. */
    @Suppress("UNUSED_PARAMETER")
    fun currentAuthority(
        session: SessionState,
        access: AccessUiState,
        warehouse: WarehouseUiState
    ): VerifiedContextAuthority? {
        if (session != SessionState.Active || access.stage != AccessStage.WorkAuthorized ||
            access.authorityEpoch <= 0
        ) {
            return null
        }
        val context = access.activeContext?.takeIf { it.isCurrent } ?: return null
        val authority = context.verifiedAuthority ?: return null
        if (listOf(
                authority.userId,
                authority.tenantId,
                authority.workspaceId,
                authority.membershipId
            )
                .any(String::isBlank)
        ) {
            return null
        }
        return authority
    }

    fun visibleEntries(
        entries: List<ConnectedOperationEntry>,
        session: SessionState,
        access: AccessUiState,
        warehouse: WarehouseUiState
    ): List<ConnectedOperationEntry> {
        val authority = currentAuthority(session, access, warehouse) ?: return emptyList()
        return uniqueEntries(entries).filter { entry ->
            entry.visibleInHub &&
                permits(authority, entry)
        }
    }

    fun open(
        entry: ConnectedOperationEntry,
        session: SessionState,
        access: AccessUiState,
        warehouse: WarehouseUiState
    ): ConnectedOperationRoute? {
        val authority = currentAuthority(session, access, warehouse) ?: return null
        if (!permits(authority, entry)) return null
        return ConnectedOperationRoute(
            entry.key,
            access.authorityEpoch,
            authority.copy(
                permissions = authority.permissions.toSet(),
                roles = authority.roles.toSet()
            )
        )
    }

    fun permits(
        route: ConnectedOperationRoute,
        entries: List<ConnectedOperationEntry>,
        session: SessionState,
        access: AccessUiState,
        warehouse: WarehouseUiState
    ): Boolean {
        val entry = uniqueEntries(entries).singleOrNull { it.key == route.entryKey } ?: return false
        if (route.authorityEpoch != access.authorityEpoch) return false
        val current = currentAuthority(session, access, warehouse) ?: return false
        return current == route.authority &&
            permits(current, entry)
    }

    private fun permits(
        authority: VerifiedContextAuthority,
        entry: ConnectedOperationEntry
    ): Boolean {
        val roles = entry.requiredRoles()
        return roles.isNotEmpty() && authority.roles.any(roles::contains) &&
            authority.permissions.any(entry.readPermissions::contains) &&
            entry.requiredReadPermissionGroups.all { group ->
                authority.permissions.any(group::contains)
            }
    }

    /** Explicit role policy for surfaced work; unknown entries fail closed. */
    private val roleRequirementsByEntryKey = mapOf(
        "warehouse.lot-substitution" to setOf("WAREHOUSE", "WAREHOUSE_OPERATOR"),
        "warehouse.cycle-count" to setOf("WAREHOUSE", "WAREHOUSE_OPERATOR"),
        "warehouse.automation" to setOf("WAREHOUSE", "WAREHOUSE_OPERATOR"),
        "warehouse.batch" to setOf("WAREHOUSE", "WAREHOUSE_OPERATOR"),
        "warehouse.inbound-discrepancy" to setOf("WAREHOUSE", "WAREHOUSE_OPERATOR"),
        "warehouse.transfer-receipt" to setOf("WAREHOUSE", "WAREHOUSE_OPERATOR"),
        "warehouse.transfer" to setOf("WAREHOUSE", "WAREHOUSE_OPERATOR"),
        "warehouse.temperature" to setOf("WAREHOUSE", "WAREHOUSE_OPERATOR"),
        "warehouse.disposition" to setOf("WAREHOUSE", "WAREHOUSE_OPERATOR"),
        "dispatch.coordination-limits" to setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR"),
        "dispatch.handover" to setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR"),
        "dispatch.handoff-identity" to setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR"),
        "dispatch.temperature" to setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR"),
        "dispatch.plan-change" to setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR"),
        "dispatch.assignment" to setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR"),
        "dispatch.outgoing-goods" to setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR"),
        "dispatch.instructions" to setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR"),
        "dispatch.loads" to setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR"),
        "dispatch.readiness" to setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR"),
        // Driver-facing screens stay within Dispatch navigation; the API still
        // enforces the current delivery assignment and required permissions.
        "driver.coordination-limits" to setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR"),
        "driver.handoff-code" to setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR"),
        "driver.execution-temperature" to setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR"),
        "driver.incident" to setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR"),
        "driver.deliveries" to setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR"),
        "driver.loads" to setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR"),
        "commercial.visit" to setOf("SALES", "SALES_REPRESENTATIVE"),
        "commercial.documents" to setOf("SALES", "SALES_REPRESENTATIVE"),
        "commercial.request" to setOf("SALES", "SALES_REPRESENTATIVE"),
        "commercial.direct-order" to setOf("SALES", "SALES_REPRESENTATIVE"),
        "commercial.catalog" to setOf("SALES", "SALES_REPRESENTATIVE"),
        "commercial.instructions" to setOf("SALES", "SALES_REPRESENTATIVE"),
        "commercial.progress" to setOf("SALES", "SALES_REPRESENTATIVE"),
        "commercial.customers" to setOf("SALES", "SALES_REPRESENTATIVE"),
        "operations.overview" to setOf("BUSINESS_OPERATIONS_MANAGER", "BOM"),
        "operations.exceptions" to setOf("BUSINESS_OPERATIONS_MANAGER", "BOM"),
        "internal.execution-holds" to setOf("BUSINESS_OPERATIONS_MANAGER", "BOM"),
        "operations.notifications" to setOf(
            "BUSINESS_OPERATIONS_MANAGER",
            "BOM",
            "SALES",
            "SALES_REPRESENTATIVE",
            "WAREHOUSE",
            "WAREHOUSE_OPERATOR",
            "LOGISTICS",
            "DISPATCH",
            "DISPATCH_COORDINATOR"
        ),
        "bom.exceptions" to setOf("BUSINESS_OPERATIONS_MANAGER", "BOM")
    )

    private fun ConnectedOperationEntry.requiredRoles(): Set<String> =
        readRoles.ifEmpty { roleRequirementsByEntryKey[key].orEmpty() }

    /** Identical declarations collapse; conflicting declarations for a key fail closed. */
    private fun uniqueEntries(
        entries: List<ConnectedOperationEntry>
    ): List<ConnectedOperationEntry> =
        entries.groupBy(ConnectedOperationEntry::key).values.mapNotNull { sameKey ->
            sameKey.distinct().singleOrNull()
        }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ConnectedOperationsEntries(
    entries: List<ConnectedOperationEntry>,
    roles: Set<String>,
    onOpen: (ConnectedOperationEntry) -> Unit
) {
    if (entries.isEmpty()) return
    val groups = entries.groupBy { it.workGroup() }
    val available = WorkGroup.entries.filter { groups[it].orEmpty().isNotEmpty() }
    val preferred = when {
        roles.any { it in setOf("WAREHOUSE", "WAREHOUSE_OPERATOR") } -> WorkGroup.Warehouse

        roles.any { it in setOf("LOGISTICS", "DISPATCH", "DISPATCH_COORDINATOR") } ->
            WorkGroup.Dispatch

        roles.any { it in setOf("BOM", "BUSINESS_OPERATIONS_MANAGER") } -> WorkGroup.Management

        else -> available.first()
    }
    var selected by remember(available, preferred) {
        mutableStateOf(preferred.takeIf { it in available } ?: available.first())
    }
    var query by remember(selected) { mutableStateOf("") }
    var showTools by remember(selected) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        NexaWorkSummary(
            title = stringResource(R.string.work_hub_title),
            description = stringResource(R.string.work_hub_support)
        ) {
            if (available.size > 1) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    available.forEach { group ->
                        FilterChip(
                            selected = selected == group,
                            onClick = { selected = group },
                            label = { Text(stringResource(group.titleResource)) }
                        )
                    }
                }
            }
        }
        val groupEntries = groups[selected].orEmpty()
        if (groupEntries.size > 4) {
            NexaSearchField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.work_hub_search),
                hint = stringResource(R.string.work_hub_search_hint)
            )
        }
        val matching = groupEntries.filter { it.label.contains(query.trim(), ignoreCase = true) }
        val primaryKeys = setOf(
            "warehouse.batch",
            "dispatch.readiness",
            "dispatch.loads",
            "driver.loads",
            "driver.deliveries",
            "commercial.customers",
            "commercial.request",
            "commercial.direct-order",
            "operations.notifications"
        )
        val primary = matching.filter { it.key in primaryKeys }
        val supporting = matching.filterNot { it.key in primaryKeys }
        if (matching.isEmpty()) {
            Text(
                stringResource(R.string.work_hub_no_results),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (primary.isNotEmpty()) {
            NexaWorkSection(title = stringResource(selected.titleResource)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    primary.forEach { entry ->
                        NexaTaskRow(
                            title = entry.label,
                            description = stringResource(entry.descriptionResource()),
                            onClick = { onOpen(entry) }
                        )
                    }
                }
            }
        } else {
            Text(
                stringResource(selected.titleResource),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (supporting.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showTools = !showTools }) {
                    Text(
                        stringResource(
                            if (showTools) {
                                R.string.work_hub_hide_supporting
                            } else {
                                R.string.work_hub_supporting
                            },
                            supporting.size
                        )
                    )
                }
                if (showTools || query.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        supporting.forEach { entry ->
                            NexaTaskRow(
                                title = entry.label,
                                description = stringResource(entry.descriptionResource()),
                                onClick = { onOpen(entry) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class WorkGroup(val titleResource: Int) {
    Warehouse(R.string.work_hub_warehouse),
    Dispatch(R.string.work_hub_dispatch),
    Management(R.string.work_hub_management),
    Commercial(R.string.work_hub_commercial),
    Other(R.string.work_hub_other)
}

private fun ConnectedOperationEntry.workGroup(): WorkGroup = when {
    key.startsWith("warehouse.") -> WorkGroup.Warehouse
    key.startsWith("dispatch.") || key.startsWith("driver.") -> WorkGroup.Dispatch
    key.startsWith("operations.") || key == "bom.exceptions" -> WorkGroup.Management
    key.startsWith("commercial.") || key.startsWith("payments.") -> WorkGroup.Commercial
    else -> WorkGroup.Other
}

private fun ConnectedOperationEntry.descriptionResource(): Int = when (key) {
    "warehouse.batch" -> R.string.work_hub_picking_support
    "dispatch.readiness" -> R.string.work_hub_readiness_support
    "dispatch.loads" -> R.string.work_hub_loads_support
    "driver.loads" -> R.string.work_hub_accept_load_support
    "driver.deliveries" -> R.string.work_hub_deliveries_support
    "operations.overview" -> R.string.work_hub_overview_support
    "operations.exceptions", "bom.exceptions" -> R.string.work_hub_exceptions_support
    "commercial.customers" -> R.string.work_hub_customers_support
    "commercial.request" -> R.string.work_hub_request_support
    "commercial.direct-order" -> R.string.work_hub_direct_order_support
    "warehouse.cycle-count" -> R.string.work_hub_count_support
    "warehouse.transfer", "warehouse.transfer-receipt" -> R.string.work_hub_transfer_support
    "warehouse.temperature", "warehouse.disposition" -> R.string.work_hub_condition_support
    "commercial.documents" -> R.string.work_hub_documents_support
    "operations.notifications" -> R.string.work_hub_notifications_support
    else -> R.string.connected_operation_open_support
}
